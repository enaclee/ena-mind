import copy
import math
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / 'main' / 'python'))
from enacore import Kernel, PairState, EventBus, PollingAdapter, probability_matrix, wilson_lower

NOW = 1800000000000


def payload(symbol='BTCUSDT', category='linear', generation=0, count=1000):
    bars = []
    for i in range(count):
        price = 100 + i * .01
        bars.append([NOW-(count-i)*60000, price, price*1.025, price*.975, price, 100])
    return {'instrument': {'key': 'Bybit:'+category+':'+symbol, 'generation': generation,
                          'category': category, 'base': symbol.removesuffix('USDT'), 'quote': 'USDT', 'round_trip_fee_pct': .1},
            'bars': bars, 'trades': [], 'metrics': {'price': 110, 'bid': 109.99, 'ask': 110.01,
            'book_ts': NOW, 'trade_ts': NOW, 'synced': True, 'delta': 70, 'ofi': .8,
            'book_imbalance': 60, 'persistence': .9, 'oi': 300}}


class CoreRegressionTests(unittest.TestCase):
    def test_bus_delivers_and_counts(self):
        bus, seen = EventBus(), []
        bus.subscribe('x', seen.append); bus.publish('x', 7)
        self.assertEqual(seen, [7]); self.assertEqual(bus.count, 1)

    def test_all_agents_and_matrix_dimensions(self):
        r = Kernel().process(payload(), NOW)
        self.assertEqual(len(r['agents']), 12)
        self.assertEqual(len(r['matrix']['LONG']), 20)
        self.assertEqual(len(r['matrix']['SHORT']), 20)
        self.assertTrue(all(0 <= c['probability'] <= 1 for c in r['matrix']['LONG']))

    def test_stale_stream_veto(self):
        p = payload(); p['metrics']['trade_ts'] = NOW-7000
        self.assertEqual(Kernel().process(p, NOW)['verdict'], 'VETO')

    def test_future_stream_veto(self):
        p = payload(); p['metrics']['book_ts'] = NOW+6000
        self.assertEqual(Kernel().process(p, NOW)['verdict'], 'VETO')

    def test_wide_spread_veto(self):
        p = payload(); p['metrics']['ask'] = 120
        self.assertEqual(Kernel().process(p, NOW)['verdict'], 'VETO')

    def test_desync_veto(self):
        p = payload(); p['metrics']['synced'] = False
        self.assertEqual(Kernel().process(p, NOW)['verdict'], 'VETO')

    def test_empty_history_does_not_fabricate_probability(self):
        r = Kernel().process(payload(count=0), NOW)
        self.assertTrue(all(c['probability'] is None for c in r['matrix']['LONG']))
        self.assertFalse(r['verdict'].startswith('CONFIRM'))

    def test_probability_monotonic_for_target(self):
        cells = Kernel().process(payload(), NOW)['matrix']['LONG']
        for h in (15, 30, 60, 120):
            values = [c['probability'] for c in cells if c['horizon'] == h]
            self.assertEqual(values, sorted(values, reverse=True))

    def test_fee_cost_reduces_hits(self):
        pair = PairState('x', 0); pair.seed(payload()['bars'], NOW)
        low = probability_matrix(pair, NOW, 0)['LONG']
        high = probability_matrix(pair, NOW, 5)['LONG']
        self.assertTrue(all(a['hits'] >= b['hits'] for a,b in zip(low, high)))

    def test_missing_minutes_not_used_for_calibration(self):
        p = payload(count=40); p['bars'] = p['bars'][::2]
        r = Kernel().process(p, NOW)
        self.assertTrue(all(c['samples'] == 0 for c in r['matrix']['LONG']))

    def test_generations_prevent_aba_contamination(self):
        k = Kernel(); k.process(payload(), NOW)
        k.process(payload('XAUUSDT', generation=1), NOW)
        self.assertTrue(k.process(payload(), NOW)['discarded'])
        r = k.process(payload(generation=2, count=2), NOW)
        self.assertEqual(r['bars'], 2)

    def test_spot_and_inverse_are_separate(self):
        k = Kernel(); k.process(payload(category='linear'), NOW)
        r = k.process(payload(category='spot', generation=1, count=10), NOW)
        self.assertEqual(r['bars'], 10); self.assertIn(':spot:', r['key'])

    def test_pair_cache_is_bounded(self):
        k = Kernel(max_pairs=2)
        for i in range(5): k.process(payload(str(i)+'USDT', generation=i, count=1), NOW)
        self.assertEqual(len(k.pairs), 2)

    def test_inverse_trade_units_and_duplicate_events(self):
        p = payload(category='inverse', count=1)
        p['trades'] = [{'ts': NOW, 'price': 100, 'qty': 1000, 'buy': True}] * 2
        k = Kernel(); r = k.process(p, NOW)
        self.assertEqual(r['trades'], 1)
        self.assertEqual(k.pairs[r['key']].trades[0][2], 10)

    def test_metal_missing_cross_venue_neutral_only_one_agent(self):
        k = Kernel(adapter=PollingAdapter(fetch=lambda _: {'data': []}))
        p = payload('XAUUSDT'); p['enrich'] = True
        r = k.process(p, NOW)
        cross = r['agents'][-1]
        self.assertEqual(cross['confidence'], 0)
        self.assertGreater(sum(a['confidence'] > 0 for a in r['agents'][:-1]), 5)

    def test_hybrid_input_avoids_duplicate_network(self):
        def fail(_): raise AssertionError('Must reuse Kotlin data')
        k = Kernel(adapter=PollingAdapter(fetch=fail)); p = payload(); p['enrich'] = True
        p['metrics'].update(cross_price=110, cross_ts=NOW)
        self.assertEqual(k.process(p, NOW)['agents'][-1]['confidence'], 1)

    def test_small_sample_not_confirmed(self):
        r = Kernel().process(payload(count=50), NOW)
        self.assertFalse(r['verdict'].startswith('CONFIRM'))

    def test_calibrated_history_can_confirm(self):
        r = Kernel().process(payload(), NOW)
        self.assertEqual(r['verdict'], 'CONFIRM LONG')
        self.assertIn(r['best']['horizon'], (15,30))

    def test_malformed_bars_and_nonfinite_numbers_rejected(self):
        p = payload(count=0); p['bars'] = [[NOW,100,90,110,100,1], [NOW,100,math.inf,99,100,1]]
        self.assertEqual(Kernel().process(p, NOW)['bars'], 0)

    def test_position_feedback_preserves_trade_identity(self):
        k = Kernel(); k.on_position_opened({'id': 'p1', 'symbol': 'BTCUSDT'})
        k.on_position_closed({'id': 'p1', 'net_pnl': 3})
        self.assertFalse(k.positions); self.assertEqual(k.closed_positions[0]['symbol'], 'BTCUSDT')


if __name__ == '__main__': unittest.main()
