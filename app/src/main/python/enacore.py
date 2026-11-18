"""ENΛCORE 1.0: bounded event state, observable agents and empirical hit rates.

Probabilities describe reaching a net price target within a horizon, not ROE,
expected profit or a guarantee. No LLM, credentials or order execution lives here.
The kernel's primary input is Kotlin's existing stream. All time is injectable.
"""
from collections import OrderedDict, deque
from dataclasses import dataclass, field
import json
import math
import threading
import time
import urllib.error
import urllib.request

TARGETS = (0.5, 0.75, 1.0, 1.25, 1.5)
HORIZONS = (15, 30, 60, 120)


def finite(value, default=0.0):
    try:
        value = float(value)
        return value if math.isfinite(value) else default
    except (TypeError, ValueError):
        return default


def clamp(value, lo=-1.0, hi=1.0):
    return max(lo, min(hi, finite(value)))


class EventBus:
    def __init__(self):
        self._listeners = {}
        self.count = 0

    def subscribe(self, topic, callback):
        self._listeners.setdefault(topic, []).append(callback)

    def publish(self, topic, event):
        for callback in tuple(self._listeners.get(topic, ())):
            callback(event)
        self.count += 1


@dataclass
class PairState:
    key: str
    generation: int
    bars: dict = field(default_factory=dict)
    trades: deque = field(default_factory=lambda: deque(maxlen=1500))
    trade_ids: OrderedDict = field(default_factory=OrderedDict)
    metrics: dict = field(default_factory=dict)
    oi: deque = field(default_factory=lambda: deque(maxlen=120))
    matrix_signature: tuple = ()
    matrix_cache: dict = field(default_factory=dict)

    def seed(self, rows, now):
        for row in rows:
            if not isinstance(row, (list, tuple)) or len(row) < 6:
                continue
            r = [finite(v, float('nan')) for v in row[:6]]
            if not all(math.isfinite(v) for v in r) or min(r[1:5]) <= 0:
                continue
            if r[2] < max(r[1], r[3], r[4]) or r[3] > min(r[1], r[2], r[4]):
                continue
            if r[0] < 0 or r[0] > now + 5000 or r[5] < 0:
                continue
            self.bars[int(r[0])] = r
        if len(self.bars) > 4096:
            self.bars = dict(sorted(self.bars.items())[-4096:])

    def ingest_trades(self, rows, category, now):
        for row in rows:
            ts = int(finite(row.get('ts')))
            price, qty = finite(row.get('price')), finite(row.get('qty'))
            if price <= 0 or qty <= 0 or ts <= 0 or ts > now + 5000 or now - ts > 120000:
                continue
            identity = row.get('id') or (ts, price, qty, bool(row.get('buy')))
            if identity in self.trade_ids:
                continue
            self.trade_ids[identity] = True
            if len(self.trade_ids) > 3000:
                self.trade_ids.popitem(last=False)
            # Bybit inverse contracts quote size in USD; normalize to base units.
            self.trades.append((ts, price, qty / price if category == 'inverse' else qty, bool(row.get('buy'))))
        while self.trades and now - self.trades[0][0] > 120000:
            self.trades.popleft()


class PollingAdapter:
    """Only fill missing cross-venue data; never poll Kotlin-owned primary data."""
    def __init__(self, fetch=None):
        self.fetch = fetch or self._fetch
        self.cache = OrderedDict()

    @staticmethod
    def _fetch(url):
        request = urllib.request.Request(url, headers={'User-Agent': 'ENAVERSE/1.0'})
        with urllib.request.urlopen(request, timeout=2) as response:
            return json.loads(response.read(128000))

    def enrich(self, instrument, metrics, now):
        if finite(metrics.get('cross_price')) > 0:
            return dict(metrics)
        key = instrument['key']
        last = self.cache.get(key)
        if last and now < last[0]:
            return dict(metrics, **last[1])
        base, quote = instrument.get('base', ''), instrument.get('quote', 'USDT')
        if not base.isalnum() or not quote.isalnum():
            return dict(metrics)
        # Exact instrument only. XAU is not interchangeable with a gold token.
        metal = base in ('XAU', 'XAG', 'PAXG', 'XAUT', 'GOLD', 'SILVER')
        values, ttl = {}, 60000
        try:
            if metal:
                data = self.fetch('https://www.okx.com/api/v5/market/ticker?instId=' + base + '-' + quote)
                price = finite((data.get('data') or [{}])[0].get('last'))
            else:
                data = self.fetch('https://data-api.binance.vision/api/v3/ticker/price?symbol=' + base + quote)
                price = finite(data.get('price'))
            if price > 0:
                values = {'cross_price': price, 'cross_ts': now}
            else:
                ttl = 600000
        except (OSError, ValueError, KeyError, IndexError):
            ttl = 120000
        self.cache[key] = (now + ttl, values)
        if len(self.cache) > 16:
            self.cache.popitem(last=False)
        return dict(metrics, **values)


def agent(name, score, weight=1.0, available=True, confidence=1.0, reason=''):
    return {'name': name, 'score': clamp(score), 'weight': weight,
            'confidence': clamp(confidence, 0, 1) if available else 0.0,
            'available': bool(available), 'reason': reason if available else 'Нет свежих данных'}


def agents_for(pair, now):
    m = pair.metrics
    rows = [r for ts, r in sorted(pair.bars.items()) if ts + 60000 <= now]
    closes = [r[4] for r in rows]
    trend = ((closes[-1] / closes[-min(21, len(closes))] - 1) * 100) if len(closes) > 1 else 0
    last_change = (closes[-1] / closes[-2] - 1) * 100 if len(closes) > 1 else 0
    atr = sum((r[2] - r[3]) / r[4] * 100 for r in rows[-14:]) / max(1, len(rows[-14:]))
    price = finite(m.get('price'))
    book_fresh = 0 <= now - finite(m.get('book_ts'), -1e12) <= 6000 and bool(m.get('synced'))
    flow_fresh = 0 <= now - finite(m.get('trade_ts'), -1e12) <= 6000
    cross_fresh = 0 <= now - finite(m.get('cross_ts'), -1e12) <= 90000
    delta = finite(m.get('delta')) / 100
    ofi = finite(m.get('ofi'))
    absorption = finite(m.get('absorption'))
    oi_change = 0.0
    if len(pair.oi) >= 2 and pair.oi[0][1] > 0:
        oi_change = (pair.oi[-1][1] / pair.oi[0][1] - 1) * 100
    cvd_denom = sum(t[2] for t in pair.trades) or 1.0
    cvd = sum(t[2] * (1 if t[3] else -1) for t in pair.trades) / cvd_denom
    cross = (finite(m.get('cross_price')) / price - 1) * 100 if price > 0 else 0
    return [
        agent('Delta', delta, 1.2, flow_fresh),
        agent('Order Flow Imbalance', ofi, 1.2, book_fresh),
        agent('Book pressure', finite(m.get('book_imbalance')) / 100, .8, book_fresh),
        agent('CVD', cvd, 1.0, flow_fresh and len(pair.trades) >= 5),
        agent('Absorption', absorption, 1.2, flow_fresh and book_fresh, .9),
        agent('Liquidity persistence', ofi * finite(m.get('persistence')), .6, book_fresh),
        agent('Liquidity pull', -ofi if m.get('liquidity_pull') else 0, .5, book_fresh),
        agent('Trend', trend / max(.1, atr * 4), .8, len(rows) >= 21),
        agent('Impulse', last_change / max(.05, atr), .7, len(rows) >= 2),
        agent('Volatility regime', (trend / max(.1, atr * 8)) * min(1, atr), .4, len(rows) >= 14),
        agent('Open interest', oi_change * (1 if trend > 0 else -1), .7, len(pair.oi) >= 2),
        agent('Cross venue', cross / .2, .6, cross_fresh and finite(m.get('cross_price')) > 0,
              reason='Сравнение одинакового базового актива; базис не равен арбитражу'),
    ]


def wilson_lower(hits, samples):
    if samples <= 0:
        return 0.0
    p, z = hits / samples, 1.96
    return max(0, (p + z*z/(2*samples) - z*math.sqrt(p*(1-p)/samples + z*z/(4*samples*samples))) / (1+z*z/samples))


def probability_matrix(pair, now, fee_pct):
    # Recompute only after a closed 1m candle, not on every ticker update.
    rows = [r for ts, r in sorted(pair.bars.items()) if ts + 60000 <= now]
    signature = (tuple(tuple(r[:5]) for r in rows), round(fee_pct, 5))
    if signature == pair.matrix_signature:
        return pair.matrix_cache
    result = {}
    for side in ('LONG', 'SHORT'):
        cells = []
        for target in TARGETS:
            for horizon in HORIZONS:
                hits, samples = 0, 0
                # Non-overlapping windows avoid presenting correlated samples as independent.
                for start in range(max(0, len(rows) - 1 - horizon), -1, -horizon):
                    window = rows[start:start+horizon+1]
                    if len(window) != horizon+1 or any(window[j+1][0] - window[j][0] != 60000 for j in range(horizon)):
                        continue
                    entry = window[0][4]
                    excursion = ((max(r[2] for r in window[1:]) / entry - 1) * 100 if side == 'LONG'
                                 else (1 - min(r[3] for r in window[1:]) / entry) * 100)
                    samples += 1
                    hits += excursion >= target + fee_pct
                cells.append({'target': target, 'horizon': horizon, 'samples': samples,
                              'hits': hits, 'probability': (hits+1)/(samples+2) if samples else None,
                              'lower95': wilson_lower(hits, samples), 'calibrated': samples >= 20})
        result[side] = cells
    pair.matrix_signature, pair.matrix_cache = signature, result
    return result


class Kernel:
    def __init__(self, max_pairs=8, adapter=None):
        self.bus = EventBus()
        self.pairs = OrderedDict()
        self.max_pairs = max_pairs
        self.adapter = adapter or PollingAdapter()
        self.active_key = None
        self.generation = -1
        self.positions = {}
        self.closed_positions = deque(maxlen=200)
        self.bus.subscribe('snapshot', self._ingest)
        self.lock = threading.RLock()

    def _ingest(self, event):
        pair, payload, now = event
        pair.seed(payload.get('bars', []), now)
        pair.ingest_trades(payload.get('trades', []), payload['instrument'].get('category'), now)
        pair.metrics = payload.get('metrics', {})
        oi = finite(pair.metrics.get('oi'))
        if oi > 0 and (not pair.oi or now - pair.oi[-1][0] >= 10000):
            pair.oi.append((now, oi))

    def process(self, payload, now=None):
        with self.lock:
            return self._process(payload, int(now if now is not None else time.time()*1000))

    def _process(self, payload, now):
        instrument = payload['instrument']
        key, generation = instrument['key'], int(instrument.get('generation', 0))
        if generation < self.generation or (generation == self.generation and self.active_key not in (None, key)):
            return {'discarded': True, 'key': key, 'generation': generation}
        if generation != self.generation or key != self.active_key:
            self.active_key, self.generation = key, generation
            # A->B->A must start a clean stream session, never reuse the old A book.
            self.pairs[key] = PairState(key, generation)
        pair = self.pairs[key]
        self.pairs.move_to_end(key)
        while len(self.pairs) > self.max_pairs:
            self.pairs.popitem(last=False)
        self.bus.publish('snapshot', (pair, payload, now))
        if payload.get('enrich', False):
            pair.metrics = self.adapter.enrich(instrument, pair.metrics, now)
        agents = agents_for(pair, now)
        denominator = sum(a['weight'] * a['confidence'] for a in agents)
        pressure = sum(a['score'] * a['weight'] * a['confidence'] for a in agents) / denominator if denominator else 0
        fee = max(0, finite(instrument.get('round_trip_fee_pct'), .2))
        matrix = probability_matrix(pair, now, fee)
        m = pair.metrics
        book_age = now - int(finite(m.get('book_ts')))
        trade_age = now - int(finite(m.get('trade_ts')))
        bid, ask, price = finite(m.get('bid')), finite(m.get('ask')), finite(m.get('price'))
        spread = (ask-bid) / price * 100 if price > 0 and ask >= bid > 0 else None
        reason, verdict, best = 'Нет направленного преимущества', 'NEUTRAL', None
        if min(book_age, trade_age) < -5000 or max(book_age, trade_age) > 6000 or not m.get('synced'):
            verdict, reason = 'VETO', 'Поток устарел или стакан рассинхронизирован'
        elif spread is None or spread > .15:
            verdict, reason = 'VETO', 'Нет корректного спреда или спред выше 0.15%'
        elif abs(pressure) >= .18:
            side = 'LONG' if pressure > 0 else 'SHORT'
            eligible = [c for c in matrix[side] if c['calibrated'] and c['lower95'] >= .50]
            if eligible:
                best = max(eligible, key=lambda c: (c['lower95'], c['target'] / c['horizon']))
                verdict, reason = 'CONFIRM ' + side, 'Свежий поток, допустимый спред и историческая поддержка'
            else:
                reason = 'Недостаточно независимых исторических наблюдений для AWO'
        return {'key': key, 'generation': generation, 'computed_at': now, 'pressure': pressure,
                'verdict': verdict, 'reason': reason, 'best': best, 'matrix': matrix, 'agents': agents,
                'bars': len(pair.bars), 'trades': len(pair.trades), 'oi_samples': len(pair.oi),
                'events': self.bus.count, 'book_age_ms': max(0, book_age), 'trade_age_ms': max(0, trade_age),
                'spread_pct': spread, 'round_trip_fee_pct': fee,
                'probability_label': 'Историческая частота достижения Net-цели; не гарантия прибыли'}

    def on_position_opened(self, position):
        with self.lock:
            self.positions[position['id']] = dict(position)

    def on_position_closed(self, position):
        with self.lock:
            opened = self.positions.pop(position['id'], {})
            self.closed_positions.append(dict(opened, **position))


_kernel = Kernel()


def process_json(raw):
    return json.dumps(_kernel.process(json.loads(raw)), ensure_ascii=False, allow_nan=False)


def position_json(raw, opened):
    position = json.loads(raw)
    (_kernel.on_position_opened if opened else _kernel.on_position_closed)(position)
    return '{"ok":true}'
