import json
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'main' / 'python'))
import chain_bridge


class LegacyGenerationTests(unittest.TestCase):
    def test_switch_clears_legacy_state_and_rejects_late_aba_response(self):
        eng = types.SimpleNamespace(sessions={'old': 1}, klines={}, fast={'old': 1}, prev_book={'old': 1})
        def set_klines(symbol, tf, rows):
            eng.klines[(symbol, tf)] = rows
            return True
        eng.set_klines = set_klines
        module = types.SimpleNamespace(get_engine=lambda: eng)
        previous = chain_bridge._instrument_generation
        try:
            chain_bridge._instrument_generation = -1
            with patch.object(chain_bridge, '_check'), patch.dict(sys.modules, mm_engine=module):
                self.assertTrue(json.loads(chain_bridge.engine_set_klines('BTCUSDT', '1', '[[100]]', 1))['ok'])
                self.assertFalse(eng.sessions)
                self.assertFalse(eng.fast)
                self.assertTrue(json.loads(chain_bridge.engine_set_klines('BTCUSDT', '1', '[[200]]', 2))['ok'])
                stale = json.loads(chain_bridge.engine_set_klines('BTCUSDT', '1', '[[999]]', 1))
                self.assertTrue(stale['discarded'])
                self.assertEqual(eng.klines[('BTCUSDT', '1')], [[200]])
        finally:
            chain_bridge._instrument_generation = previous


if __name__ == '__main__':
    unittest.main()
