"""REGISTRY v8.1 (ТЗ §54-57 v6): Source Registry, Calculation Registry,
Feature Lineage. Реестр источников с честными статусами (configured !=
reachable != data_received != validated) и реестр детерминированных
вычислителей с версиями, формулами и тест-векторами.

Не второй движок: описывает УЖЕ существующие вычислители mm_* (§61 —
«сначала переиспользовать проверенные»).
"""

# ---------------- SOURCE REGISTRY (§54) ----------------
# source_id -> машинно-читаемая конфигурация. endpoints проверены живыми
# запросами в аудит 2026-11-12 (docs/AUDIT.md) и/или используются в коде.

SOURCES={
    "bybit_rest": {
        "provider":"Bybit","adapter_version":"v5",
        "markets":["linear"],"data":["klines","tickers","trades","orderbook","lsr","oi","funding"],
        "endpoints":{"klines":"/v5/market/kline","tickers":"/v5/market/tickers",
                     "recent_trade":"/v5/market/recent-trade","orderbook":"/v5/market/orderbook",
                     "account_ratio":"/v5/market/account-ratio",
                     "open_interest":"/v5/market/open-interest",
                     "funding_history":"/v5/market/funding/history"},
        "api_key":False,"rate_limit":"~10 req/s public","history":"klines<=1000/req",
        "criticality":"critical","status_fields":["reachable","data_received","validated"],
    },
    "bybit_ws": {
        "provider":"Bybit","adapter_version":"v5",
        "markets":["linear"],"data":["tickers","publicTrade","orderbook.50","allLiquidation","kline"],
        "endpoints":{"ws":"wss://stream.bybit.com/v5/public/linear"},
        "api_key":False,"note":"ликвидации ТОЛЬКО здесь (REST нет)",
        "criticality":"critical",
    },
    "binance_public": {
        "provider":"Binance","adapter_version":"data-api.vision",
        "data":["aggTrades","depth","klines","whales>=100k"],
        "endpoints":{"base":"https://data-api.binance.vision/api/v3"},
        "api_key":False,"criticality":"important",
    },
    "okx_public": {
        "provider":"OKX","adapter_version":"v5",
        "data":["trades","tickers","funding","oi"],
        "endpoints":{"base":"https://www.okx.com/api/v5"},
        "api_key":False,"criticality":"important",
    },
    "deribit": {
        "provider":"Deribit","adapter_version":"v2",
        "data":["options DVOL/PCR/IV skew (BTC/ETH)"],
        "endpoints":{"base":"https://www.deribit.com/api/v2"},
        "api_key":False,"criticality":"optional",
    },
    "coingecko": {
        "provider":"CoinGecko","adapter_version":"v3",
        "data":["BTC dominance","market cap"],
        "endpoints":{"global":"/api/v3/global"},"api_key":False,
        "rate_limit":"~10-30 req/min (публичный)","criticality":"optional",
    },
    "defillama": {
        "provider":"DefiLlama","adapter_version":"v2",
        "data":["TVL по цепям"],"endpoints":{"chains":"/v2/chains"},
        "api_key":False,"criticality":"optional",
    },
    "mempool_space": {
        "provider":"mempool.space","adapter_version":"v1",
        "data":["BTC fees","blocks","mempool"],
        "endpoints":{"fees":"/api/v1/fees/recommended","blocks":"/api/v1/blocks",
                     "mempool":"/api/mempool"},
        "api_key":False,"note":"/api/mempool медленный (>20с возможен)",
        "criticality":"optional",
    },
    "blockchain_info": {
        "provider":"blockchain.com","adapter_version":"unconfirmed",
        "data":["крупные BTC-переводы (лента)"],
        "endpoints":{"unconfirmed":"/unconfirmed-transactions?format=json"},
        "api_key":False,"criticality":"optional",
    },
    "fng_alternative": {
        "provider":"alternative.me","adapter_version":"fng",
        "data":["Fear & Greed Index"],"endpoints":{"fng":"/fng/"},
        "api_key":False,"criticality":"optional",
    },
    "etherscan_compatible": {
        "provider":"Etherscan-совместимый (ключ пользователя)","adapter_version":"v2",
        "data":["EVM транзакции/потоки по адресу"],
        "endpoints":{"base":"настраивается (allowlist)"},
        "api_key":True,"status_default":"INSUFFICIENT",
        "criticality":"optional",
        "note":"без ключа — INSUFFICIENT, данные не подменяются (§7 ТЗ)",
    },
    "solana_rpc": {
        "provider":"Solana public RPC","adapter_version":"json-rpc",
        "data":["активность сети (требует POST-методы)"],
        "endpoints":{"rpc":"https://api.mainnet-beta.solana.com"},
        "api_key":False,"status_default":"INSUFFICIENT",
        "criticality":"optional",
        "note":"GET-коллектор не выполняет POST — активность не собирается, честный INSUFFICIENT",
    },
    "newsapi_rss": {
        "provider":"NewsAPI/RSS","adapter_version":"v2/top-headlines + RSS",
        "data":["новости"],"endpoints":{"newsapi":"https://newsapi.org (ключ)",
                                          "rss":"публичные RSS-ленты"},
        "api_key":"optional (RSS без ключа)","criticality":"optional",
    },
}


def source_registry(include_runtime=False):
    """Реестр источников для UI диагностики (§54: экран диагностики).
    include_runtime — добавить последние runtime-статусы из mm_data, если есть."""
    out={}
    for sid,cfg in SOURCES.items():
        entry=dict(cfg)
        entry["source_id"]=sid
        entry.setdefault("status_default","AVAILABLE")
        # разделение статусов (§54.3): configured всегда true (описан в коде);
        # reachable/data_received/validated — runtime, добавляется снаружи
        entry["configured"]=True
        entry["reachable"]=None
        entry["data_received"]=None
        entry["validated"]=None
        entry["used_by_calculation"]=[]
        if include_runtime:
            try:
                import mm_data
                st=mm_data.source_status if hasattr(mm_data,"source_status") else None
                if st: entry["runtime"]=True
            except Exception:
                pass
        out[sid]=entry
    return out


# ---------------- CALCULATION REGISTRY (§56) ----------------
# calculator_id -> {version, formula, inputs, output, golden tests}.
# Каждый вычислитель УЖЕ существует в mm_* — реестр описывает его контракт.

CALCULATORS={
    "ema": {"version":"ema_v1","module":"mm_agents/mm_trend",
            "formula":"e_i = x_i*k + e_(i-1)*(1-k), k=2/(n+1), seed=SMA(n)",
            "inputs":"closes[], n","output":"значение EMA (цена)",
            "golden":{"in":{"vals":[1,2,3,4,5],"n":3},"out":4.0},
            "missing":"None при len<n"},
    "sma": {"version":"sma_v1","module":"mm_agents",
            "formula":"mean последних n","inputs":"vals[], n","output":"среднее",
            "golden":{"in":{"vals":[1,2,3,4,5],"n":5},"out":3.0}},
    "rsi": {"version":"rsi_v1","module":"mm_agents",
            "formula":"Wilder RSI(14)","inputs":"closes[], n=14",
            "output":"0..100","golden_range":[0,100]},
    "atr": {"version":"atr_v1","module":"mm_agents",
            "formula":"TR = max(h-l,|h-pc|,|l-pc|); ATR = Wilder smoothing",
            "inputs":"rows[[ts,o,h,l,c,v]], n=14","output":"ATR (цена)"},
    "regression_slope": {"version":"slope_v1","module":"mm_trend",
            "formula":"OLS slope log(closes) по времени, %/бар",
            "inputs":"closes[], n=30","output":"%/бар (может быть <0)"},
    "slope_t_stat": {"version":"slopet_v1","module":"mm_trend",
            "formula":"t = b/SE(b) регрессии; |t|>=2 — значимость",
            "inputs":"closes[], n=30","output":"t-статистика",
            "golden_note":"t=0 для константной серии (SE=0 -> None)"},
    "swing_points": {"version":"swing_v1","module":"mm_trend",
            "formula":"local max/min с подтверждением k барами ПОСЛЕ точки",
            "inputs":"rows[], k=3","output":"[(idx,ts,price,'H'|'L')]",
            "no_lookahead":True},
    "adx": {"version":"adx_v1","module":"mm_trend",
            "formula":"Wilder ADX(14) + DI±","inputs":"rows[]","output":"0..100"},
    "realized_vol": {"version":"rv_v1","module":"mm_trend",
            "formula":"sqrt(var(log returns)) * 100 за окно",
            "inputs":"closes[], n","output":"% за окно"},
    "cvd": {"version":"cvd_v1","module":"mm_agents/TapeAggregator",
            "formula":"sum(buy_size) - sum(sell_size) по окну ленты",
            "inputs":"trades[]","output":"единицы базового актива"},
    "delta_pct": {"version":"deltapct_v1","module":"mm_agents",
            "formula":"100*(buy-sell)/(buy+sell)","inputs":"buy,sell","output":"-100..100"},
    "orderbook_imbalance": {"version":"obi_v1","module":"BookState/mm_agents",
            "formula":"100*(sumB-sumA)/(sumB+sumA) по top-N уровням",
            "inputs":"bids,asks,N=25","output":"-100..100",
            "note":"лимитные заявки != исполненные сделки (§14)"},
    "funding_pctl": {"version":"fpctl_v1","module":"mm_agents",
            "formula":"процентиль текущего funding в истории 30д",
            "inputs":"funding_hist[], current","output":"0..100"},
    "threshold_pct_24h": {"version":"thr_v1","module":"mm_outlook24",
            "formula":"max(0.75%, 1.5*sigma_24h) — фиксируется ДО оценки",
            "inputs":"sigma_24h_pct|None","output":"% порог UP/DOWN",
            "golden":{"in":{"sigma":None},"out":0.75,
                       "in2":{"sigma":2.0},"out2":3.0}},
    "brier_multiclass": {"version":"brier_v1","module":"mm_outlook24",
            "formula":"mean over records of sum((p_i-y_i)^2)",
            "inputs":"records[{p_up,p_flat,p_down,outcome}]",
            "golden":{"in":{"p":[1,0,0],"outcome":"UP"},"out":0.0,
                       "in2":{"p":[0,0,1],"outcome":"UP"},"out2":2.0}},
    "robust_z": {"version":"rz_v1","module":"mm_onchain_agents",
            "formula":"0.6745*(x-median)/MAD","inputs":"x, history[]",
            "output":"z (аномалия при |z|>=3.5)","min_history":20},
    "netflow_sums": {"version":"nf_v1","module":"mm_onchain_schema",
            "formula":"суммы amount по event_type за окно, только подтверждённые",
            "inputs":"events[], window_sec","output":"dict сумм"},
    "priority_score": {"version":"ps_v1","module":"mm_discovery",
            "formula":"sum(pattern_weight) * quality_factor, cap 10",
            "inputs":"patterns[], data_quality","output":"0..10",
            "note":"НЕ вероятность прибыли (§42.3)"},
}


def calculation_registry():
    """Реестр вычислителей (для UI «Calculation Registry» и аудита §56)."""
    out={}
    for cid,c in CALCULATORS.items():
        entry=dict(c)
        entry["calculator_id"]=cid
        out[cid]=entry
    return out


# ---------------- FEATURE LINEAGE (§57) ----------------

class LineageTracker:
    """Происхождение результата: raw -> normalized -> calculator -> feature.
    Для каждого запуска — point-in-time snapshot (данные cutoff)."""
    def __init__(self,run_id=None):
        import time,uuid
        self.run_id=run_id or ("ln_%s"%uuid.uuid4().hex[:8])
        self.features=[]

    def feature(self,feature_id,calculator_id,value,unit,inputs=None,
                input_features=None,parameters=None,quality=None):
        """Записать feature с полным происхождением (§57)."""
        import time
        rec={"feature_id":feature_id,"calculator_id":calculator_id,
             "calculator_version":(CALCULATORS.get(calculator_id) or {}).get("version","?"),
             "value":value,"unit":unit,
             "input_event_ids":inputs or [],
             "input_feature_ids":input_features or [],
             "parameters":parameters or {},
             "quality_score":quality,
             "run_id":self.run_id,
             "calculated_at":int(time.time()*1000),
             "data_cutoff_time":int(time.time()*1000)}
        self.features.append(rec)
        return rec

    def chain(self,feature_id):
        """Восстановить цепочку происхождения feature (по input_feature_ids)."""
        by_id={f["feature_id"]:f for f in self.features}
        seen=set()
        def walk(fid,acc):
            if fid in seen: return acc
            seen.add(fid)
            f=by_id.get(fid)
            if not f: return acc
            acc.append(f)
            for dep in f.get("input_feature_ids") or []:
                walk(dep,acc)
            return acc
        return list(reversed(walk(feature_id,[])))

    def snapshot(self):
        """Point-in-time snapshot запуска (§57): воспроизводимость расчёта."""
        return {"run_id":self.run_id,"n_features":len(self.features),
                "features":list(self.features)}


def selftest():
    # 1) реестр источников полон и с разделением статусов (§54.3)
    reg=source_registry()
    assert len(reg)>=13
    for sid,e in reg.items():
        assert e["configured"] is True
        assert "criticality" in e and "api_key" in e
    assert reg["bybit_rest"]["api_key"] is False
    assert reg["etherscan_compatible"]["api_key"] is True
    assert reg["etherscan_compatible"]["status_default"]=="INSUFFICIENT"
    assert reg["solana_rpc"]["status_default"]=="INSUFFICIENT"
    # 2) реестр вычислителей: golden-значения совпадают с реальными функциями
    calc=calculation_registry()
    assert len(calc)>=18
    import mm_trend
    g=calc["ema"]["golden"]
    assert mm_trend.ema(g["in"]["vals"],g["in"]["n"])==g["out"]
    g2=calc["threshold_pct_24h"]["golden"]
    import mm_outlook24
    assert mm_outlook24.threshold_pct(g2["in"]["sigma"])==g2["out"]
    assert mm_outlook24.threshold_pct(g2["in2"]["sigma"])==g2["out2"]
    g3=calc["brier_multiclass"]["golden"]
    assert mm_outlook24.brier_score([{"p_up":g3["in"]["p"][0],"p_flat":g3["in"]["p"][1],
                                       "p_down":g3["in"]["p"][2],"outcome":g3["in"]["outcome"]}])==g3["out"]
    assert mm_outlook24.brier_score([{"p_up":g3["in2"]["p"][0],"p_flat":g3["in2"]["p"][1],
                                       "p_down":g3["in2"]["p"][2],"outcome":g3["in2"]["outcome"]}])==g3["out2"]
    # 3) swing_points описан как no_lookahead
    assert calc["swing_points"].get("no_lookahead") is True
    # 4) lineage: цепочка восстановления
    ln=LineageTracker()
    ln.feature("raw_klines_1m","-","[[...]]","ohlcv",inputs=["bybit_klines"])
    ln.feature("ema20","ema",3210.5,"price",
               input_features=["raw_klines_1m"],parameters={"n":20})
    ln.feature("trend_bias","regression_slope",0.012,"%/bar",
               input_features=["ema20","raw_klines_1m"])
    chain=ln.chain("trend_bias")
    assert len(chain)==3
    assert chain[0]["feature_id"]=="raw_klines_1m"
    assert chain[-1]["feature_id"]=="trend_bias"
    # каждая запись с версией вычислителя и cutoff
    for f in ln.features:
        assert f["calculator_version"] and f["data_cutoff_time"]
    snap=ln.snapshot()
    assert snap["n_features"]==3 and snap["run_id"]==ln.run_id
    # 5) приоритет НЕ вероятность прибыли — помечено
    assert "НЕ вероятность" in calc["priority_score"]["note"]
    print("mm_registry: OK")
