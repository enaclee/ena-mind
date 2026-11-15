#!/usr/bin/env python3
"""Обратная связь — замыкает контур ENA-MIND. Логика v5.0 сохранена.

Цепочка: Journal (фактические winrate по buckets p) -> p_hat (взвешенный observed)
-> корректировка приоров аналитиков в mm_council -> новый Decision -> новый Journal.

Это и есть «нейронная связь от точки А до точки Б»: система учится на собственных
ошибках, не требуя переобучения моделей. Если стратегия переоценивает p в фазе range,
после 20+ compliant-сделок p_hat опустится ниже базы, и приоры deriv (контр-тренд)
аккуратно вырастут — council начнёт больше верить deriv в range, меньше ta.

Функции:
- p_hat(): возвращает взвешенный observed hit-rate по buckets p из журнала, или None;
- regime_p_hat(regime): то же, но сегментированно по режиму (где стратегия работает);
- report(): человекочитаемая сводка для пользователя.
"""
import json,os,statistics
import mm_journal
def _calibration_rows():
    """Только compliant-сделки с заполненным scenario_hit."""
    rows=[r for r in mm_journal._load(mm_journal.J) if r.get("exit") and r.get("compliant")=="1" and r["scenario_hit"]!=""]
    return rows
def p_hat(min_n=20):
    """Взвешенный observed hit-rate по buckets p. None, если n<min_n."""
    rows=_calibration_rows()
    if len(rows)<min_n: return None,None
    # buckets по p: [0.0-0.2), [0.2-0.4), [0.4-0.6), [0.6-0.8), [0.8-1.0]
    buckets={}
    for r in rows:
        b=min(4,int(float(r["p"])*5));buckets.setdefault(b,[]).append((float(r["p"]),float(r["scenario_hit"])))
    tot_n=0;obs_sum=0.0
    for v in buckets.values():
        for p,obs in v:
            tot_n+=1;obs_sum+=obs
    if tot_n==0: return None,None
    # взвешенный observed = средний scenario_hit
    return round(obs_sum/tot_n,4),{"n":tot_n,"buckets":{str(k):{"n":len(v),
              "mean_p":round(statistics.mean(x[0] for x in v),3),
              "observed":round(statistics.mean(x[1] for x in v),3)} for k,v in sorted(buckets.items())}}
def regime_p_hat(regime,min_n=10):
    """То же, но сегментированно по режиму: где стратегия работает, где переоценивает."""
    rows=[r for r in _calibration_rows() if (r.get("regime") or "unknown")==regime]
    if len(rows)<min_n: return None
    return round(sum(float(r["scenario_hit"]) for r in rows)/len(rows),4)
def report():
    """Человекочитаемая сводка обратной связи."""
    ph,info=p_hat()
    if ph is None:
        return {"status":"LOW_CONFIDENCE","msg":"в журнале <20 compliant-сделок, p_hat не рассчитан",
                "n":(info or {}).get("n",0)}
    out={"status":"OK","p_hat":ph,"calibration":info}
    # сегментация по режимам
    regimes=sorted({(r.get("regime") or "unknown") for r in _calibration_rows()})
    out["by_regime"]={}
    for rg in regimes:
        ph_r=regime_p_hat(rg,min_n=5)
        if ph_r is not None:
            n=sum(1 for r in _calibration_rows() if (r.get("regime") or "unknown")==rg)
            out["by_regime"][rg]={"p_hat":ph_r,"n":n}
    out["interpretation"]="Если p_hat < 0.5 — стратегия переоценивает вероятность; council слегка опустит приоры."
    return out
def selftest():
    import tempfile
    global BASE,J
    old=(mm_journal.BASE,mm_journal.J)
    try:
        with tempfile.TemporaryDirectory() as td:
            mm_journal.BASE,mm_journal.J=td,os.path.join(td,"j.csv")
            # нет сделок -> None
            assert p_hat()[0] is None
            # <20 -> None (LOW CONFIDENCE)
            for i in range(15):
                mm_journal.pre({"id":"T%d"%i,"module":"S","p":0.6,"risk_usd":1.0,"entry":100,"stop":99,"side":"long","regime":"trend_up"})
                mm_journal.post({"id":"T%d"%i,"exit":102,"result_usd":1.0,"scenario_hit":1,"compliant":1})
            assert p_hat(min_n=20)[0] is None
            # >=20 -> p_hat считается
            for i in range(15,25):
                mm_journal.pre({"id":"T%d"%i,"module":"S","p":0.6,"risk_usd":1.0,"entry":100,"stop":99,"side":"long","regime":"trend_up"})
                mm_journal.post({"id":"T%d"%i,"exit":99,"result_usd":-1.0,"scenario_hit":0,"compliant":1})
            ph,info=p_hat(min_n=20)
            assert ph is not None and 0.0<=ph<=1.0 and info["n"]==25
            # regime_p_hat
            rph=regime_p_hat("trend_up",min_n=5)
            assert rph is not None
            rph_unknown=regime_p_hat("range",min_n=5)
            assert rph_unknown is None  # нет сделок в range
            # report
            rep=report()
            assert rep["status"]=="OK" and "by_regime" in rep and "trend_up" in rep["by_regime"]
    finally:
        mm_journal.BASE,mm_journal.J=old
    print("mm_feedback: OK")
if __name__=="__main__":
    import argparse,os
    ap=argparse.ArgumentParser()
    ap.add_argument("--selftest",action="store_true")
    ap.add_argument("--report",action="store_true")
    g=ap.parse_args()
    if g.selftest: selftest()
    elif g.report: print(json.dumps(report(),ensure_ascii=False,indent=1))
    else: print("use --selftest or --report")
