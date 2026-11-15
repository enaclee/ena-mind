#!/usr/bin/env python3
"""Единый контракт записей между модулями. ENA-MIND v6.0.

Отличия от v5.0:
- добавлен FORECAST (узел 5: прогноз направления, независимый от Risk Gate);
- добавлен NEWS (новостной фон: источник, времена, активы, важность, влияние, актуальность);
- добавлен NODE (узел визуальной цепочки: name/status/summary/reason);
- SNAPSHOT дополнен oi_pct_change и funding_hist_n (история OI/funding теперь реально
  заполняется из Bybit V5);
- статусы источников: AVAILABLE / UNAVAILABLE / STALE / INSUFFICIENT (mm_data.source_status).
"""
SCHEMA_VERSION="6.0"
# Статус качества отдельного источника данных.
SOURCE_STATUS=("AVAILABLE","UNAVAILABLE","STALE","INSUFFICIENT")
# Снимок рынка: ts, символ, цена, производные метрики, грейд источников.
SNAPSHOT=["ts","symbol","price","funding_pct","oi","oi_pct_change","lsr","fng","fng_delta3","fng_delta7","grade","sources"]
# Свечной ряд: ts,open,high,low,close,volume (строка klines.csv, как в v4/v5).
KLINE=["ts","open","high","low","close","volume"]
# Вердикт одного аналитика. ok=False -> INSUFFICIENT (HALT), оценок «на глаз» нет.
ANALYST=["module","ok","side","p","confidence","evidence","invalidate","note"]
# Классификация режима рынка (mm_regime). kind ∈ {trend_up,trend_down,range,volatile,unknown}.
REGIME=["kind","confidence","adx","atr_pct","bb_width_pct","note"]
# Решение совета (mm_council). status: AGREE | NO_TRADE | HALT | REVISIT.
DECISION=["status","side","p","confidence","conflicts","audit","modules","regime","p_hat_from_feedback","note"]
# Узел 5 — прогноз направления (mm_forecast). side ∈ {long,short,neutral}.
# ВАЖНО: прогноз НЕ является торговым решением и не обходит Risk Gate.
# confidence — относительная уверенность (не калиброванная вероятность!), band ∈ LOW/MEDIUM/HIGH.
FORECAST=["side","confidence","band","p","horizon_h","arguments","contradictions",
          "alt_scenario","invalidate","council_status","gate_status","trade_allowed","status","note"]
# Новостной фон (источники подключаются в Android-части, сюда попадает готовый список).
NEWS=["source","published_ts","fetched_ts","assets","importance","impact","relevance","title","url"]
# Узел визуальной цепочки анализа (mm_chain). status: PENDING|RUNNING|OK|STOPPED|SKIPPED.
NODE=["name","status","summary","reason"]
# План сделки (mm_risk / mm_journal pre).
PLAN=["id","module","pair","side","entry","stop","tp1","p","lev","risk_usd"]
# Необязательные поля плана: funding_pct, n_funding, tp2, regime, horizon_h,
# council_status, council_side, council_note, p_hat.
# Запись журнала после выхода (mm_journal post).
RECORD=["id","ts","plan","exit","result_usd","R","compliant","error_code","scenario_hit","regime"]
# Гипотеза (mm_journal hyp_*).
HYPO=["id","ts","claim","evidence_for","evidence_against","p","status","outcome","brier","regime"]
# Запись прогноза в память (mm_forecast store/verify).
FORECAST_RECORD=["id","ts","pair","interval","regime","side","confidence","p","horizon_h",
                 "entry_price","council_status","gate_status","data_grade",
                 "arguments","contradictions","invalidate",
                 "verify_ts","exit_price","move_pct","hit","note"]
# Аналитики, без которых сделка невозможна (HALT при INSUFFICIENT).
MODULES_REQUIRED=("ta","flow","deriv","sentiment")
SIDES=("long","short","flat")
REGIMES=("trend_up","trend_down","range","volatile","unknown")
def validate(rec,fields):
    miss=[k for k in fields if k not in rec]
    return (not miss),miss
def selftest():
    ok,miss=validate({"ts":1,"symbol":"BTC"},SNAPSHOT)
    assert not ok and "price" in miss
    assert validate({k:0 for k in KLINE},KLINE)[0]
    a={"module":"ta","ok":True,"side":"flat","p":0.5,"confidence":0.0,"evidence":[],"invalidate":[],"note":""}
    assert validate(a,ANALYST)[0]
    r={"kind":"trend_up","confidence":0.7,"adx":28.0,"atr_pct":1.5,"bb_width_pct":2.0,"note":""}
    assert validate(r,REGIME)[0]
    d={"status":"AGREE","side":"long","p":0.58,"confidence":0.7,"conflicts":[],"audit":[],"modules":{},
       "regime":"trend_up","p_hat_from_feedback":None,"note":""}
    assert validate(d,DECISION)[0]
    f={"side":"short","confidence":0.63,"band":"MEDIUM","p":0.55,"horizon_h":24,"arguments":[],
       "contradictions":[],"alt_scenario":"","invalidate":[],"council_status":"NO_TRADE",
       "gate_status":"BLOCKED","trade_allowed":False,"status":"OK","note":""}
    assert validate(f,FORECAST)[0]
    n={"source":"test","published_ts":0,"fetched_ts":1,"assets":["BTC"],"importance":"high",
       "impact":-1,"relevance":1.0,"title":"t","url":""}
    assert validate(n,NEWS)[0]
    nd={"name":"COUNCIL","status":"OK","summary":"","reason":""}
    assert validate(nd,NODE)[0]
    assert MODULES_REQUIRED==("ta","flow","deriv","sentiment")
    assert set(REGIMES)>=set(["trend_up","range","volatile"])
    assert "INSUFFICIENT" in SOURCE_STATUS and "STALE" in SOURCE_STATUS
    print("mm_schema: OK")
if __name__=="__main__": selftest()
