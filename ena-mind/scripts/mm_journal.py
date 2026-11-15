#!/usr/bin/env python3
"""Журнал v5.0. Логика v4 сохранена + поле regime в записи (нужно для обратной связи:
mm_feedback и mm_council пользуются p_hat из журнала, чтобы замкнуть контур).

Запись плана фиксирует вердикт совета (council_status/side/note) и regime — позже можно
сегментировать edge по фазам рынка (trend_up vs range) и понять, где стратегия реально работает.
"""
import argparse,csv,json,os,datetime,statistics
import mm_config
BASE=mm_config.data_dir()
J=os.path.join(BASE,"journal.csv");H=os.path.join(BASE,"hypotheses.csv")
PRE=["id","ts","pair","side","regime","module","p","horizon_h","entry","stop","tp1","tp2","notional","lev","risk_usd",
     "C_plan","E_plan","rules_version","council_status","council_side","council_note","p_hat"]
POST=["exit","fees_fact","funding_fact","slip","result_usd","scenario_hit","compliant","error_code"]
HP=["id","ts","claim","evidence_for","evidence_against","status","p","regime","horizon_h","outcome","brier"]
def _load(p):
    if not os.path.exists(p): return []
    with open(p,encoding="utf-8",newline="") as f: return list(csv.DictReader(f))
def _save(p,fields,rows):
    os.makedirs(os.path.dirname(p),exist_ok=True)
    with open(p,"w",encoding="utf-8",newline="") as f:
        w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(rows)
def pre(d,require_gate=False):
    for k in ("id","module","p","risk_usd","entry","stop","side"):
        if k not in d or d[k]=="": return {"error":"missing "+k}
    try:
        p=float(d["p"]);r=float(d["risk_usd"]);e=float(d["entry"]);s=float(d["stop"])
        if d["side"] not in ("long","short"): raise ValueError("side must be long|short")
        if not (0<=p<=1 and r>0 and e>0 and s>0): raise ValueError("p in [0,1], risk/entry/stop > 0")
        if not ((s<e) if d["side"]=="long" else (s>e)): raise ValueError("stop on wrong side of entry")
        if d.get("tp1") not in (None,""):
            t=float(d["tp1"])
            if t<=0: raise ValueError("tp1 must be > 0")
            if d["side"]=="long" and not t>e: raise ValueError("tp1 must be > entry for long")
            if d["side"]=="short" and not t<e: raise ValueError("tp1 must be < entry for short")
    except Exception as ex: return {"error":"invalid: %s"%ex}
    if require_gate:
        import mm_risk
        if not mm_risk.passed_today(mm_risk.fingerprint(d),os.path.join(BASE,"state","risk.json")):
            return {"error":"gate: нет PASS за сегодня для именно этого плана (python scripts/mm_risk.py check ...)"}
    rows=_load(J)
    if any(x["id"]==str(d["id"]) for x in rows): return {"error":"id exists"}
    row={k:d.get(k,"") for k in PRE+POST}
    row["ts"]=d.get("ts") or datetime.datetime.now().isoformat(timespec="minutes")
    rows.append(row);_save(J,PRE+POST,rows)
    return {"ok":True,"id":row["id"]}
def post(d):
    rows=_load(J)
    for r in rows:
        if r["id"]==str(d.get("id")):
            for k in POST:
                if k in d: r[k]=str(d[k])
            if not (r["exit"]!="" and r["result_usd"]!="" and r["compliant"] in ("0","1") and r["scenario_hit"] in ("0","1")):
                return {"error":"incomplete post"}
            try: float(r["exit"]);float(r["result_usd"])
            except ValueError: return {"error":"invalid exit/result_usd"}
            _save(J,PRE+POST,rows);return {"ok":True,"id":r["id"]}
    return {"error":"id not found"}
def _stats(sel):
    n=len(sel)
    if n==0: return {"n":0}
    Rs=[float(r["result_usd"])/float(r["risk_usd"]) for r in sel if float(r["risk_usd"])>0]
    wins=sum(1 for r in sel if float(r["result_usd"])>0)
    br=[(float(r["p"])-float(r["scenario_hit"]))**2 for r in sel if r["scenario_hit"]!=""]
    out={"n":n,"winrate":round(wins/n,3),"avgR":round(statistics.mean(Rs),3) if Rs else None,
         "sum_usd":round(sum(float(r["result_usd"]) for r in sel),2),
         "brier":round(statistics.mean(br),4) if br else None}
    no_trade=[r for r in sel if (r.get("council_status") or "")=="NO_TRADE"]
    if no_trade: out["no_trade_entries"]=len(no_trade)
    return out
def stats():
    rows=[r for r in _load(J) if r.get("exit")]
    comp=[r for r in rows if r["compliant"]=="1"];viol=[r for r in rows if r["compliant"]=="0"]
    out={"ALL":_stats(rows),"EDGE_compliant_only":_stats(comp),"DISCIPLINE_violations":_stats(viol)}
    buckets={}
    for r in comp:
        if r["scenario_hit"]=="": continue
        b=min(4,int(float(r["p"])*5));buckets.setdefault(b,[]).append((float(r["p"]),float(r["scenario_hit"])))
    out["calibration"]={str(k):{"n":len(v),"mean_p":round(statistics.mean(x[0] for x in v),3),
                                 "observed":round(statistics.mean(x[1] for x in v),3)} for k,v in sorted(buckets.items())}
    # edge по режимам (главное в v5 — где стратегия реально работает)
    by_regime={}
    for r in comp:
        rg=r.get("regime") or "unknown"
        by_regime.setdefault(rg,[]).append(r)
    out["edge_by_regime"]={rg:_stats(rs) for rg,rs in by_regime.items()}
    out["verdict"]="LOW CONFIDENCE (n<%d): no verdict"%20 if len(comp)<20 else ("provisional" if len(comp)<100 else "review")
    return out
def hyp_add(d):
    for k in ("id","claim","p"):
        if k not in d or d[k]=="": return {"error":"missing "+k}
    try:
        if not 0<=float(d["p"])<=1: raise ValueError("p must be in [0,1]")
    except Exception as ex: return {"error":"invalid: %s"%ex}
    rows=_load(H)
    if any(x["id"]==str(d["id"]) for x in rows): return {"error":"id exists"}
    row={k:d.get(k,"") for k in HP};row["ts"]=datetime.datetime.now().isoformat(timespec="minutes");row["status"]="open"
    rows.append(row);_save(H,HP,rows);return {"ok":True,"id":row["id"]}
def hyp_resolve(d):
    if "outcome" not in d or str(d["outcome"]) not in ("0","1"): return {"error":"outcome must be 0 or 1"}
    rows=_load(H)
    for r in rows:
        if r["id"]==str(d.get("id")):
            r["outcome"]=str(d["outcome"]);r["status"]="resolved"
            r["brier"]=round((float(r["p"])-float(d["outcome"]))**2,4)
            _save(H,HP,rows);return {"ok":True}
    return {"error":"id not found"}
def selftest():
    import tempfile
    global BASE,J,H
    old=(BASE,J,H)
    try:
        with tempfile.TemporaryDirectory() as td:
            BASE,J,H=td,os.path.join(td,"j.csv"),os.path.join(td,"h.csv")
            assert "error" in pre({"id":"X"})
            assert "error" in pre({"id":"B","module":"S1","p":0.55,"risk_usd":1.0,"entry":100,"stop":99,"side":"buy"})
            assert pre({"id":"T1","module":"S1","p":0.55,"risk_usd":1.0,"entry":100,"stop":99,"side":"long","regime":"trend_up"})["ok"]
            assert pre({"id":"T2","module":"S1","p":0.55,"risk_usd":1.0,"entry":100,"stop":99,"side":"long"})["ok"]
            assert pre({"id":"T3","module":"S1","p":0.55,"risk_usd":1.0,"entry":100,"stop":99,"side":"long"})["ok"]
            assert pre({"id":"T4","module":"S1","p":0.55,"risk_usd":1.0,"entry":100,"stop":99,"side":"long","tp1":98})  # wrong side
            assert pre({"id":"T5","module":"S1","p":0.55,"risk_usd":1.0,"entry":100,"stop":99,"side":"long","tp1":102,
                        "council_status":"AGREE","council_side":"long","council_note":"ok","regime":"trend_up","p_hat":0.58})["ok"]
            assert post({"id":"T1","exit":102,"result_usd":1.9,"scenario_hit":1,"compliant":1})["ok"]
            assert post({"id":"T2","exit":99,"result_usd":-1.0,"scenario_hit":0,"compliant":0})["ok"]
            assert post({"id":"T3","exit":100,"result_usd":0,"scenario_hit":0,"compliant":1})["ok"]
            assert pre({"id":"T6","module":"S1","p":0.55,"risk_usd":1.0,"entry":100,"stop":99,"side":"long"})["ok"]
            assert "error" in post({"id":"T6","exit":100})
            s=stats()
            assert s["ALL"]["n"]==3 and s["EDGE_compliant_only"]["n"]==2 and s["DISCIPLINE_violations"]["n"]==1
            assert s["EDGE_compliant_only"]["winrate"]==0.5 and s["ALL"]["winrate"]==round(1/3,3)
            # edge_by_regime присутствует (v5)
            assert "edge_by_regime" in s and "trend_up" in s["edge_by_regime"]
            # ворота
            import mm_risk
            gp={"id":"G1","module":"S1","pair":"BTCUSDT","side":"long","entry":100,"stop":99,"tp1":102,"p":0.55,"risk_usd":1.0,"lev":3}
            assert "gate" in pre(gp,require_gate=True)["error"]
            res={"date":datetime.date.today().isoformat(),"results":[{"fingerprint":mm_risk.fingerprint(gp),"id":"G1","verdict":"PASS"}]}
            mm_risk.record(res,os.path.join(td,"state","risk.json"))
            assert pre(gp,require_gate=True)["ok"]
            assert "gate" in pre(dict(gp,id="G2",stop=98.5),require_gate=True)["error"]
            rows=_load(J);t5=[r for r in rows if r["id"]=="T5"][0]
            assert t5["council_status"]=="AGREE" and t5["council_side"]=="long" and t5["regime"]=="trend_up"
            assert hyp_add({"id":"H1","claim":"test","p":0.6})["ok"]
            assert "error" in hyp_add({"id":"H2","claim":"no p"})
            assert "error" in hyp_resolve({"id":"H1","outcome":2})
            assert hyp_resolve({"id":"H1","outcome":1})["ok"]
    finally:
        BASE,J,H=old
    print("mm_journal: OK")
if __name__=="__main__":
    ap=argparse.ArgumentParser()
    ap.add_argument("cmd",nargs="?",choices=["pre","post","stats","hyp_add","hyp_resolve","selftest"],default="selftest")
    ap.add_argument("json",nargs="?",default="{}")
    ap.add_argument("--selftest",action="store_true",help="alias для cmd=selftest")
    ap.add_argument("--require-gate",action="store_true")
    g=ap.parse_args()
    if g.selftest or g.cmd=="selftest": selftest()
    elif g.cmd=="stats": print(json.dumps(stats(),ensure_ascii=False,indent=1))
    elif g.cmd=="pre": print(json.dumps(pre(json.loads(g.json),require_gate=g.require_gate),ensure_ascii=False))
    else: print(json.dumps({"post":post,"hyp_add":hyp_add,"hyp_resolve":hyp_resolve}[g.cmd](json.loads(g.json)),ensure_ascii=False))
