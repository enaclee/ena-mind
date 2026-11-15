#!/usr/bin/env python3
"""Ворота перед сделкой v5.0 (Risk Gate). Логика v4 сохранена + читаем cfg через mm_config.
Вето: council NO_TRADE/HALT -> BLOCK; сторона плана != стороне совета -> BLOCK;
data_grade D -> BLOCK; grade C -> p >= min_p_grade_c. BLOCK окончателен."""
import argparse,csv,datetime,hashlib,json,os,sys
import mm_core,mm_config
BASE=mm_config.data_dir()
STATE=os.path.join(BASE,"state","risk.json")
JOURNAL=os.path.join(BASE,"journal.csv")
FP_FIELDS=("pair","side","entry","stop","tp1","risk_usd","lev")
def _num(x):
    try: return float(x)
    except (TypeError,ValueError): return None
def _read(path,default):
    if not os.path.exists(path): return default
    with open(path,encoding="utf-8") as f: return json.load(f)
def _write(path,doc):
    os.makedirs(os.path.dirname(path),exist_ok=True)
    with open(path,"w",encoding="utf-8") as f: json.dump(doc,f,ensure_ascii=False,indent=1)
def fingerprint(plan):
    """Отпечаток плана. lev по умолчанию нормализуется (план без lev == план с lev=default)."""
    cfg=mm_config.load()
    core={}
    for k in FP_FIELDS:
        v=plan.get(k)
        if (k=="lev" and (v is None or v=="")): v=cfg["default_lev"]
        if v is None or v=="": continue
        n=_num(v)
        core[k]=round(n,8) if n is not None else str(v).strip().lower()
    return hashlib.sha256(json.dumps(core,sort_keys=True,separators=(",",":")).encode("utf-8")).hexdigest()[:16]
def load_journal(path=JOURNAL):
    if not os.path.exists(path): return []
    with open(path,encoding="utf-8",newline="") as f: return list(csv.DictReader(f))
def check(plans,cfg=None,journal=None,today=None,decision=None,data_grade=None):
    """Чистая функция. Планы проверяются по порядку, как если бы прошедший уже был открыт.
    decision: DECISION совета. data_grade: 'A'|'C'|'D'|None."""
    c=mm_config.load();c.update(cfg or {})
    rows=list(journal or [])
    today=today or datetime.date.today().isoformat()
    dep=float(c["deposit"])
    blocked={str(b).upper() for b in c["blocked"]}
    open_rows=[r for r in rows if not r.get("exit")]
    today_rows=[r for r in rows if str(r.get("ts",""))[:10]==today]
    pnl_today=sum(_num(r.get("result_usd")) or 0.0 for r in today_rows if r.get("exit"))
    open_keys={(str(r.get("pair","")).upper(),str(r.get("side","")).lower()) for r in open_rows}
    margin_open=0.0
    for r in open_rows:
        n,l=_num(r.get("notional")),_num(r.get("lev"))
        if n and l and l>0: margin_open+=n/l
    n_compliant=sum(1 for r in rows if r.get("exit") and str(r.get("compliant"))=="1")
    seen=set();results=[];passed=0;margin_new=0.0
    dstatus=(decision or {}).get("status");dside=(decision or {}).get("side")
    for i,pl in enumerate(plans,1):
        reasons=[];warns=[];info={}
        pair=str(pl.get("pair") or pl.get("symbol") or "").strip().upper()
        side=str(pl.get("side","")).strip().lower()
        e,s,t,pr,risk=(_num(pl.get(k)) for k in ("entry","stop","tp1","p","risk_usd"))
        lev=_num(pl.get("lev"))
        if lev is None: lev=float(c["default_lev"])
        fp=fingerprint(dict(pl,pair=pair))
        if decision is not None:
            if dstatus in ("NO_TRADE","HALT"):
                reasons.append("council: %s — %s"%(dstatus,(decision or {}).get("note","")[:160]))
            elif dstatus=="AGREE" and dside in ("long","short") and side in ("long","short") and side!=dside:
                reasons.append("council: сторона %s противоречит решению совета %s"%(side,dside))
        if data_grade=="D": reasons.append("data: grade D — выводы запрещены, сделка невозможна")
        if data_grade=="C" and pr is not None and pr<float(c["min_p_grade_c"]):
            reasons.append("data: grade C требует p>=%.2f, в плане %.2f"%(float(c["min_p_grade_c"]),pr))
        if not pair: reasons.append("format: нет pair")
        if side not in ("long","short"): reasons.append("format: side должен быть long или short")
        for name,v in (("entry",e),("stop",s),("tp1",t),("risk_usd",risk)):
            if v is None or v<=0: reasons.append("format: %s должен быть числом > 0"%name)
        if pr is None or not 0<=pr<=1: reasons.append("format: p должен быть в [0,1]")
        if lev<=0: reasons.append("format: lev должен быть > 0")
        if not reasons:
            if side=="long" and not (s<e<t): reasons.append("format: для long нужно stop < entry < tp1")
            if side=="short" and not (t<e<s): reasons.append("format: для short нужно tp1 < entry < stop")
        if not reasons:
            if lev>float(c["max_lev"]): reasons.append("leverage: %.1fx выше лимита %.1fx"%(lev,float(c["max_lev"])))
            stop_pct=abs(e-s)/e*100.0;target_pct=abs(t-e)/e*100.0
            fp_pct=_num(pl.get("funding_pct")) or 0.0;nf=int(_num(pl.get("n_funding")) or 0)
            pays=(side=="long" and fp_pct>0) or (side=="short" and fp_pct<0)
            sign=1 if pays else -1
            cb=mm_core.cost_breakdown(c["market"],c["fee_in"],c["fee_out"],funding_pct=fp_pct,n_funding=nf,funding_sign=sign,cfg=c)
            eco=mm_core.economics(pr,target_pct,stop_pct,cb,c["rr_min"])
            info["economics"]={k:eco[k] for k in ("C_pct","C_worst_pct","net_win_pct","net_loss_pct","RR_net","E_pct")}
            for name,ok in eco["filters"].items():
                if not ok: reasons.append("economics: %s (C=%.3f%%, RR_net=%.2f, E=%.3f%%)"%(name,eco["C_pct"],eco["RR_net"],eco["E_pct"]))
            sz=mm_core.size(risk,stop_pct,e,lev,dep,c["market"],c["fee_in"],c["fee_out"],
                            funding_pct=fp_pct,n_funding=nf,funding_sign=sign,
                            qty_step=c["qty_step"],min_qty=c["min_qty"],min_notional=c["min_notional"],cfg=c)
            if not sz["ok"]:
                why=sz.get("why")
                if why=="min_lot_conflict":
                    reasons.append("size: минимальный лот %s (≈$%.2f) даёт риск $%.2f при запрошенном $%.2f"%(
                        sz["min_lot"],sz["min_notional_usd"],sz["risk_at_min_usd"],risk))
                else: reasons.append("size: "+str(why))
            else:
                info["sizing"]={k:sz[k] for k in ("lot","notional_usd","risk_usd","margin_usd")}
                max_risk=dep*float(c["max_risk_pct"])/100.0
                if sz["risk_usd"]>max_risk+1e-9:
                    reasons.append("risk: фактический риск $%.2f выше лимита $%.2f (%.1f%% депозита)"%(sz["risk_usd"],max_risk,float(c["max_risk_pct"])))
                max_margin=dep*float(c["max_margin_pct"])/100.0
                tot=margin_open+margin_new+sz["margin_usd"]
                if tot>max_margin+1e-9:
                    reasons.append("margin: суммарная маржа $%.2f выше лимита $%.2f (%.0f%% депозита)"%(tot,max_margin,float(c["max_margin_pct"])))
            lb=mm_core.liq_buffer(lev,stop_pct,float(c["mmr_pct"]))
            info["liq_buffer_x"]=lb["buffer_x"]
            if lb["buffer_x"] is not None and lb["buffer_x"]<float(c["min_liq_buffer_x"]):
                reasons.append("liquidation: до ликвидации %.1f стопов, нужно >= %.1f"%(lb["buffer_x"],float(c["min_liq_buffer_x"])))
        key=(pair,side)
        if pair in blocked: reasons.append("blocked: %s в списке запрещённых"%pair)
        if key in seen: reasons.append("duplicate: второй %s %s в этой пачке"%(side,pair))
        if key in open_keys: reasons.append("duplicate: %s %s уже открыт в журнале"%(side,pair))
        if int(c["max_open"]) and len(open_rows)+passed>=int(c["max_open"]):
            reasons.append("open positions: открыто %d (с учётом пачки), лимит %d"%(len(open_rows)+passed,int(c["max_open"])))
        if int(c["max_trades_per_day"]) and len(today_rows)+passed>=int(c["max_trades_per_day"]):
            reasons.append("daily limit: сегодня уже %d сделок (с учётом пачки), лимит %d"%(len(today_rows)+passed,int(c["max_trades_per_day"])))
        max_loss=dep*float(c["max_daily_loss_pct"])/100.0
        if float(c["max_daily_loss_pct"])>0 and pnl_today<=-max_loss:
            reasons.append("daily loss: результат дня $%.2f достиг лимита -$%.2f"%(pnl_today,max_loss))
        seen.add(key)
        if n_compliant<int(c["p_hat_min_n"]): warns.append("LOW CONFIDENCE: p — приор, в журнале n=%d<%d compliant-сделок"%(n_compliant,int(c["p_hat_min_n"])))
        if not reasons and info.get("sizing") and info["sizing"]["margin_usd"]>0.5*dep: warns.append("margin_over_half_deposit")
        verdict="BLOCK" if reasons else "PASS"
        if verdict=="PASS":
            passed+=1;margin_new+=info.get("sizing",{}).get("margin_usd",0.0)
        results.append({"index":i,"id":pl.get("id"),"pair":pair,"side":side,"fingerprint":fp,"verdict":verdict,
                        "reasons":reasons,"warnings":warns,**info})
    return {"date":today,"pass":passed,"block":len(results)-passed,"results":results}
def record(res,path=STATE):
    doc=_read(path,{})
    if doc.get("date")!=res["date"]: doc={"date":res["date"],"verdicts":{}}
    for r in res["results"]:
        doc["verdicts"][r["fingerprint"]]={"id":r["id"],"verdict":r["verdict"],"ts":datetime.datetime.now().isoformat(timespec="seconds")}
    _write(path,doc)
def passed_today(fp,path=STATE,today=None):
    doc=_read(path,{})
    today=today or datetime.date.today().isoformat()
    return doc.get("date")==today and doc.get("verdicts",{}).get(fp,{}).get("verdict")=="PASS"
def report(res):
    out=[]
    for r in res["results"]:
        extra=""
        if "sizing" in r: extra=" лот %s, риск $%.2f, маржа $%.2f"%(r["sizing"]["lot"],r["sizing"]["risk_usd"],r["sizing"]["margin_usd"])
        out.append("%-5s #%d %s %s%s"%(r["verdict"],r["index"],r["side"],r["pair"]," ("+str(r["id"])+")" if r["id"] else "",extra))
        out+=["      - "+x for x in r["reasons"]]+["      ! "+x for x in r["warnings"]]
    out.append("%d PASS, %d BLOCK"%(res["pass"],res["block"]))
    return "\n".join(out)
def _plans(arg):
    doc=_read(arg,None) if os.path.exists(arg) else json.loads(arg)
    return doc if isinstance(doc,list) else [doc]
def _doc(arg):
    return _read(arg,None) if os.path.exists(arg) else json.loads(arg)
def selftest():
    import tempfile
    T="2026-01-01"
    good={"id":"G","pair":"BTCUSDT","side":"long","entry":73700,"stop":73110.4,"tp1":75321.4,"p":0.55,"lev":3,"risk_usd":1.5}
    r=check([good],today=T)["results"][0]
    assert r["verdict"]=="PASS" and r["sizing"]["lot"]==0.002,r
    assert any("LOW CONFIDENCE" in w for w in r["warnings"])
    # регрессия из SKILL_PATCH: стоп 0.7%, цель 2.0% -> отклонение по лимиту издержек
    reg=dict(good,id="R",stop=73184.1,tp1=75174.0)
    r=check([reg],today=T)["results"][0]
    assert r["verdict"]=="BLOCK" and any("cost_le_25pct_stop" in x for x in r["reasons"]),r
    # формат
    assert check([dict(good,stop=74000)],today=T)["results"][0]["verdict"]=="BLOCK"
    assert check([dict(good,side="buy")],today=T)["results"][0]["verdict"]=="BLOCK"
    assert check([dict(good,p=1.5)],today=T)["results"][0]["verdict"]=="BLOCK"
    # минимальный лот
    r=check([dict(good,risk_usd=1.0)],today=T)["results"][0]
    assert r["verdict"]=="BLOCK" and any(x.startswith("size:") for x in r["reasons"]),r
    # лимит риска
    r=check([good],cfg={"max_risk_pct":1.0},today=T)["results"][0]
    assert r["verdict"]=="BLOCK" and any(x.startswith("risk:") for x in r["reasons"]),r
    # ликвидация
    liq=dict(good,entry=100,stop=95,tp1=112,lev=10,risk_usd=10)
    r=check([liq],cfg={"deposit":1000,"min_notional":5,"max_risk_pct":5,"max_margin_pct":100},today=T)["results"][0]
    assert r["verdict"]=="BLOCK" and any(x.startswith("liquidation:") for x in r["reasons"]),r
    # плечо выше лимита
    assert any(x.startswith("leverage:") for x in check([dict(good,lev=20)],today=T)["results"][0]["reasons"])
    # дубликаты
    res=check([good,dict(good,id="G2")],today=T)
    assert res["results"][0]["verdict"]=="PASS" and res["results"][1]["verdict"]=="BLOCK"
    assert any(x.startswith("duplicate:") for x in res["results"][1]["reasons"])
    # журнал: открытая позиция
    openrow={"id":"A","pair":"BTCUSDT","side":"long","exit":"","ts":T+"T10:00","notional":"147.4","lev":"3"}
    r=check([good],journal=[openrow],today=T)["results"][0]
    assert r["verdict"]=="BLOCK" and any(x.startswith("duplicate:") for x in r["reasons"])
    closed={"id":"B","pair":"ETHUSDT","side":"short","exit":"1","ts":T+"T09:00","result_usd":"0.5","compliant":"1"}
    r=check([good],cfg={"max_trades_per_day":1},journal=[closed],today=T)["results"][0]
    assert r["verdict"]=="BLOCK" and any(x.startswith("daily limit:") for x in r["reasons"])
    lost=dict(closed,result_usd="-4")
    r=check([good],journal=[lost],today=T)["results"][0]
    assert r["verdict"]=="BLOCK" and any(x.startswith("daily loss:") for x in r["reasons"]),r
    assert check([good],journal=[dict(lost,ts="2025-12-31T10:00")],today=T)["results"][0]["verdict"]=="PASS"
    assert check([good],cfg={"blocked":["btcusdt"]},today=T)["results"][0]["verdict"]=="BLOCK"
    # funding со знаком
    short=dict(good,id="S",side="short",stop=74289.6,tp1=72078.6)
    cl=check([dict(good,funding_pct=0.05,n_funding=3)],today=T)["results"][0]["economics"]["C_pct"]
    cs=check([dict(short,funding_pct=0.05,n_funding=3)],today=T)["results"][0]["economics"]["C_pct"]
    assert cl>0.176>cs,(cl,cs)
    # отпечаток
    assert fingerprint(good)==fingerprint(dict(good,entry="73700"))
    assert fingerprint(good)!=fingerprint(dict(good,stop=73100))
    assert fingerprint(good)==fingerprint({k:v for k,v in good.items() if k!="lev"})
    # вето Risk Gate
    d_nt={"status":"NO_TRADE","note":"CONFLICT: ta long vs flow short","side":"flat","conflicts":[{}],"audit":[],"modules":{},"p":0.5,"confidence":0.0,"regime":"trend_up","p_hat_from_feedback":None}
    r=check([good],today=T,decision=d_nt)["results"][0]
    assert r["verdict"]=="BLOCK" and any(x.startswith("council:") for x in r["reasons"]),r
    d_halt=dict(d_nt,status="HALT",note="INSUFFICIENT: flow")
    r=check([good],today=T,decision=d_halt)["results"][0]
    assert r["verdict"]=="BLOCK" and any("HALT" in x for x in r["reasons"]),r
    d_long=dict(d_nt,status="AGREE",side="long",note="модули согласны")
    r=check([dict(good,id="S2",side="short",stop=74289.6,tp1=72078.6)],today=T,decision=d_long)["results"][0]
    assert r["verdict"]=="BLOCK" and any("противоречит" in x for x in r["reasons"]),r
    r=check([good],today=T,decision=d_long)["results"][0]
    assert r["verdict"]=="PASS"
    r=check([good],today=T,data_grade="D")["results"][0]
    assert r["verdict"]=="BLOCK" and any(x.startswith("data: grade D") for x in r["reasons"]),r
    r=check([good],today=T,data_grade="C")["results"][0]
    assert r["verdict"]=="BLOCK" and any(x.startswith("data: grade C") for x in r["reasons"]),r
    r=check([dict(good,p=0.65)],today=T,data_grade="C")["results"][0]
    assert r["verdict"]=="PASS"
    # запись вердиктов
    with tempfile.TemporaryDirectory() as td:
        p=os.path.join(td,"state","risk.json")
        res=check([good],today=T);record(res,p)
        assert passed_today(res["results"][0]["fingerprint"],p,T)
        assert not passed_today(res["results"][0]["fingerprint"],p,"2026-01-02")
        assert not passed_today("0000000000000000",p,T)
    print("mm_risk: OK")
if __name__=="__main__":
    ap=argparse.ArgumentParser(description="Ворота перед сделкой (PASS/BLOCK). Ордера не отправляет.")
    ap.add_argument("cmd",choices=["check","selftest"],nargs="?",default="selftest")
    ap.add_argument("--selftest",action="store_true",help="alias для cmd=selftest")
    ap.add_argument("plans",nargs="?")
    ap.add_argument("--config");ap.add_argument("--journal",default=JOURNAL)
    ap.add_argument("--decision");ap.add_argument("--grade",choices=["A","C","D"])
    ap.add_argument("--no-write",action="store_true");ap.add_argument("--json",action="store_true")
    g=ap.parse_args()
    if g.cmd=="selftest" or g.selftest: selftest()
    else:
        if not g.plans: print("нужен путь к JSON или JSON-строка с планом");sys.exit(2)
        try: plans=_plans(g.plans)
        except Exception as ex: print("плохой JSON: %s"%ex);sys.exit(2)
        try: decision=_doc(g.decision) if g.decision else None
        except Exception as ex: print("плохой JSON decision: %s"%ex);sys.exit(2)
        res=check(plans,_read(g.config,{}) if g.config else None,load_journal(g.journal),decision=decision,data_grade=g.grade)
        if not g.no_write: record(res)
        print(json.dumps(res,ensure_ascii=False,indent=1) if g.json else report(res))
        sys.exit(1 if res["block"] else 0)
