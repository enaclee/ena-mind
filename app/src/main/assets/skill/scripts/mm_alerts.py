#!/usr/bin/env python3
"""Алерты 24/7 ENA-MIND v6.0. Логика v5.0 сохранена (above/below/stop/target/pct_move/
funding_above/below, side для stop/target, rearm). Котировки — Bybit V5 /v5/market/tickers
(Binance удалён): один вызов даёт lastPrice, prevPrice24h (цена 24ч назад — точная база
для pct_move) и fundingRate. Ничего не торгует."""
import argparse,datetime,json,os,sys,time
import mm_live,mm_config
def _paths():
    BASE=mm_config.data_dir()
    return BASE,os.path.join(BASE,"alerts.json"),os.path.join(BASE,"state","alerts.json")
LEVEL=("above","below","stop","target")
TYPES=LEVEL+("pct_move","funding_above","funding_below")
def _now(): return datetime.datetime.now(datetime.timezone.utc)
def _read(path,default):
    if not os.path.exists(path): return default
    with open(path,encoding="utf-8") as f: return json.load(f)
def _write(path,doc):
    os.makedirs(os.path.dirname(path),exist_ok=True)
    with open(path,"w",encoding="utf-8") as f: json.dump(doc,f,ensure_ascii=False,indent=1)
def _n(x): return ("%g"%x)
def parse_quote(t):
    """Разбор элемента result.list[] от /v5/market/tickers?category=linear (Bybit V5).
    Возвращает {price, open (цена 24ч назад), funding_pct} или None."""
    if not isinstance(t,dict): return None
    q={}
    try:
        if t.get("lastPrice"):
            q["price"]=float(t["lastPrice"])
            if t.get("prevPrice24h"): q["open"]=float(t["prevPrice24h"])
        if "fundingRate" in t and t.get("fundingRate") is not None:
            q["funding_pct"]=round(float(t["fundingRate"])*100,5)
    except (TypeError,ValueError): return None
    return q if "price" in q else None
def fetch_quotes(symbols,pause=0.2,cfg=None):
    """Котировки с Bybit V5 (публичный, без ключей). Возвращает (quotes, errors)."""
    import mm_config as _mc
    quotes={};errors=[]
    c=cfg or _mc.load()
    byb=c.get("sources",{}).get("bybit","https://api.bybit.com")
    for k,sym in enumerate(symbols):
        if k: time.sleep(pause)
        t,e=mm_live._get_simple(byb+"/v5/market/tickers?category=linear&symbol="+sym)
        item=None
        if isinstance(t,dict) and t.get("retCode")==0:
            lst=(t.get("result") or {}).get("list") or []
            item=lst[0] if lst else None
        q=parse_quote(item)
        if q: quotes[sym]=q;quotes[sym]["ts"]=time.time()
        else: errors.append(e or "bad_quote")
    return quotes,errors
def validate(rule):
    t=rule.get("type");rid=rule.get("id") or "?"
    if t not in TYPES: raise ValueError("правило %s: неизвестный type %r"%(rid,t))
    if not rule.get("symbol"): raise ValueError("правило %s: нужен symbol"%rid)
    if t in LEVEL and not isinstance(rule.get("price"),(int,float)): raise ValueError("правило %s: нужен числовой price"%rid)
    if t in ("pct_move","funding_above","funding_below") and not isinstance(rule.get("pct"),(int,float)):
        raise ValueError("правило %s: нужен числовой pct"%rid)
    if t=="pct_move" and rule["pct"]<=0: raise ValueError("правило %s: pct должен быть > 0"%rid)
    if rule.get("direction") not in (None,"up","down"): raise ValueError("правило %s: direction up|down"%rid)
    if rule.get("side") not in (None,"long","short"): raise ValueError("правило %s: side long|short"%rid)
def rule_id(r):
    lvl=r.get("price") if r["type"] in LEVEL else r.get("pct")
    s="-"+(r["side"][:1]) if r.get("side") and r["type"] in ("stop","target") else ""
    return r.get("id") or "%s-%s%s-%s"%(r["symbol"].lower(),r["type"],s,_n(lvl))
def _direction(r):
    t=r["type"]
    if t in ("above","below"): return t
    if t=="stop": return "above" if r.get("side")=="short" else "below"
    if t=="target": return "below" if r.get("side")=="short" else "above"
    return t
def evaluate(rules,quotes,state,now_utc):
    """Чистая функция. Возвращает (rings, new_state)."""
    state=json.loads(json.dumps(state or {}))
    rung=state.setdefault("rung",{});stamp=now_utc.strftime("%Y-%m-%dT%H:%M:%SZ")
    rings=[]
    for r in rules:
        validate(r);rid=rule_id(r);sym=r["symbol"].upper();q=quotes.get(sym)
        if not q: continue
        typ=_direction(r);note=(" ("+r["note"]+")") if r.get("note") else ""
        if typ in ("above","below"):
            p=q["price"];hit=p>=r["price"] if typ=="above" else p<=r["price"]
            ref_name={"stop":"стопа","target":"цели"}.get(r["type"],"уровня")
        elif typ in ("funding_above","funding_below"):
            p=q.get("funding_pct")
            if p is None: continue
            hit=p>=r["pct"] if typ=="funding_above" else p<=r["pct"]
        else: hit=None
        if typ in ("above","below","funding_above","funding_below"):
            if hit and rid not in rung:
                if typ in ("above","below"):
                    msg="%s: цена %s %s %s %s%s"%(sym,_n(p),"достигла или выше" if typ=="above" else "достигла или ниже",ref_name,_n(r["price"]),note)
                else:
                    msg="%s: funding %s%% %s порога %s%%%s"%(sym,_n(p),"≥" if typ=="funding_above" else "≤",_n(r["pct"]),note)
                rings.append({"id":rid,"symbol":sym,"message":msg});rung[rid]=stamp
            elif not hit and rid in rung and r.get("rearm"): del rung[rid]
        else:  # pct_move
            ref=r.get("ref") or q.get("open")
            if not ref: continue
            move=100.0*(q["price"]/float(ref)-1);want=r.get("direction")
            hit=abs(move)>=r["pct"] and (not want or (want=="up")==(move>0))
            today=now_utc.strftime("%Y-%m-%d")
            if hit and rung.get(rid,"")[:10]!=today:
                rings.append({"id":rid,"symbol":sym,"message":"%s: %+.2f%% до %s (от %s)%s"%(sym,move,_n(q["price"]),_n(float(ref)),note)})
                rung[rid]=stamp
    state["last_check"]=stamp
    return rings,state
def cmd_add(a):
    conds={"above":a.above,"below":a.below,"stop":a.stop,"target":a.target,"funding_above":a.funding_above,"funding_below":a.funding_below}
    chosen=[(k,v) for k,v in conds.items() if v is not None]
    if a.pct is not None: chosen.append(("pct_move",a.pct))
    if len(chosen)!=1: print("нужно ровно одно условие: --above/--below/--stop/--target/--pct/--funding-above/--funding-below");return 2
    typ,val=chosen[0];r={"id":a.id,"symbol":a.symbol.upper(),"type":typ}
    r["price" if typ in LEVEL else "pct"]=val
    if a.direction: r["direction"]=a.direction
    if a.side: r["side"]=a.side
    if a.note: r["note"]=a.note
    if a.rearm: r["rearm"]=True
    try: validate(r)
    except ValueError as ex: print(ex);return 2
    r["id"]=rule_id(r);_,RULES,_=_paths();rules=_read(RULES,[])
    if any(x.get("id")==r["id"] for x in rules): print("правило %s уже есть"%r["id"]);return 2
    rules.append(r);_write(RULES,rules);print(json.dumps(r,ensure_ascii=False));return 0
def cmd_check(a):
    _,RULES,STATE=_paths()
    rules=_read(RULES,[])
    if not rules: print("правил нет: добавьте через add");return 0
    errors=[]
    if a.quotes: quotes={k.upper():v for k,v in json.loads(a.quotes).items()}
    else: quotes,errors=fetch_quotes(sorted({r["symbol"].upper() for r in rules}))
    try: rings,state=evaluate(rules,quotes,_read(STATE,{}),_now())
    except ValueError as ex: print(ex);return 2
    if not a.no_write: _write(STATE,state)
    if a.json: print(json.dumps({"rings":rings,"errors":errors,"quotes":quotes},ensure_ascii=False,indent=1))
    else:
        print("\n".join("ЗВОНОК "+x["message"] for x in rings) if rings else "Ничего не сработало")
        for e in errors: print("ошибка источника:",e)
    return 0
def cmd_list(a):
    _,RULES,STATE=_paths()
    state=_read(STATE,{}).get("rung",{})
    for r in _read(RULES,[]): print("%-28s %s %s%s"%(rule_id(r),r["symbol"],r["type"],"  [сработало %s]"%state[rule_id(r)] if rule_id(r) in state else ""))
    return 0
def cmd_reset(a):
    _,RULES,STATE=_paths()
    st=_read(STATE,{});rung=st.setdefault("rung",{})
    if a.id not in rung: print("правило %s не срабатывало"%a.id);return 1
    del rung[a.id];_write(STATE,st);print("сброшено",a.id);return 0
def selftest():
    T=datetime.datetime(2026,1,1,12,0,tzinfo=datetime.timezone.utc);T2=T+datetime.timedelta(days=1)
    q=lambda price,open_=None,f=None:{"BTCUSDT":{"price":price,"open":open_,"funding_pct":f}}
    # parse_quote: элемент Bybit tickers
    pq=parse_quote({"lastPrice":"100.5","prevPrice24h":"100.0","fundingRate":"0.0001"})
    assert pq=={"price":100.5,"open":100.0,"funding_pct":0.01},pq
    assert parse_quote({}) is None and parse_quote({"lastPrice":"x"}) is None
    assert parse_quote(None) is None
    # above: один раз, rearm возвращает
    rules=[{"symbol":"BTCUSDT","type":"above","price":100}]
    r1,s=evaluate(rules,q(101),{},T);assert len(r1)==1
    r2,s=evaluate(rules,q(102),s,T);assert r2==[]
    rules_r=[{"symbol":"BTCUSDT","type":"above","price":100,"rearm":True}]
    r1,s=evaluate(rules_r,q(101),{},T);r2,s=evaluate(rules_r,q(99),s,T);r3,s=evaluate(rules_r,q(101),s,T)
    assert len(r1)==1 and r2==[] and len(r3)==1
    # stop = below (лонг)
    r1,_=evaluate([{"symbol":"BTCUSDT","type":"stop","price":95,"note":"мой стоп"}],q(94),{},T)
    assert len(r1)==1 and "мой стоп" in r1[0]["message"]
    assert evaluate([{"symbol":"BTCUSDT","type":"stop","price":95}],q(96),{},T)[0]==[]
    # stop шорта — сверху
    r1,_=evaluate([{"symbol":"BTCUSDT","type":"stop","price":105,"side":"short"}],q(106),{},T)
    assert len(r1)==1 and "выше" in r1[0]["message"],r1
    assert evaluate([{"symbol":"BTCUSDT","type":"stop","price":105,"side":"short"}],q(104),{},T)[0]==[]
    r1,_=evaluate([{"symbol":"BTCUSDT","type":"target","price":90,"side":"short"}],q(89),{},T)
    assert len(r1)==1 and "ниже" in r1[0]["message"]
    assert rule_id({"symbol":"BTCUSDT","type":"stop","price":105,"side":"short"})!=rule_id({"symbol":"BTCUSDT","type":"stop","price":105,"side":"long"})
    # pct_move: направление, раз в сутки UTC
    pm=[{"symbol":"BTCUSDT","type":"pct_move","pct":3,"direction":"down"}]
    assert evaluate(pm,q(104,100),{},T)[0]==[]
    r1,s=evaluate(pm,q(96,100),{},T);assert len(r1)==1
    assert evaluate(pm,q(95,100),s,T)[0]==[]
    assert len(evaluate(pm,q(95,100),s,T2)[0])==1
    assert evaluate(pm,q(96,None),{},T)[0]==[]
    # funding
    fa=[{"symbol":"BTCUSDT","type":"funding_above","pct":0.05}]
    assert evaluate(fa,q(100,None,0.04),{},T)[0]==[] and len(evaluate(fa,q(100,None,0.05),{},T)[0])==1
    fb=[{"symbol":"BTCUSDT","type":"funding_below","pct":-0.02}]
    assert len(evaluate(fb,q(100,None,-0.03),{},T)[0])==1
    assert evaluate(fa,q(100,None,None),{},T)[0]==[]
    # нет котировки — молчим
    assert evaluate(rules,{},{},T)[0]==[]
    for bad in ({"symbol":"BTCUSDT","type":"nope"},{"type":"above","price":1},{"symbol":"X","type":"above"},
                {"symbol":"X","type":"pct_move","pct":0},{"symbol":"X","type":"pct_move","pct":1,"direction":"sideways"},
                {"symbol":"X","type":"stop","price":1,"side":"sideways"}):
        try: validate(bad);assert False,bad
        except ValueError: pass
    assert rule_id({"symbol":"BTCUSDT","type":"above","price":73900.5})=="btcusdt-above-73900.5"
    print("mm_alerts: OK")
if __name__=="__main__":
    ap=argparse.ArgumentParser(description="Алерты 24/7 (цена, % за 24ч, funding). Ничего не торгует.")
    ap.add_argument("--selftest",action="store_true",help="alias для subcommand selftest")
    sub=ap.add_subparsers(dest="cmd")
    ad=sub.add_parser("add");ad.add_argument("--symbol",required=True);ad.add_argument("--id")
    for k in ("above","below","stop","target","pct","funding-above","funding-below"): ad.add_argument("--"+k,type=float)
    ad.add_argument("--direction",choices=["up","down"]);ad.add_argument("--side",choices=["long","short"])
    ad.add_argument("--note");ad.add_argument("--rearm",action="store_true")
    ck=sub.add_parser("check");ck.add_argument("--quotes",help='офлайн: {"BTCUSDT":{"price":..,"open":..,"funding_pct":..}}')
    ck.add_argument("--no-write",action="store_true");ck.add_argument("--json",action="store_true")
    sub.add_parser("list");rs=sub.add_parser("reset");rs.add_argument("id");sub.add_parser("selftest")
    g=ap.parse_args()
    if g.selftest or g.cmd in (None,"selftest"): selftest()
    else: sys.exit({"add":cmd_add,"check":cmd_check,"list":cmd_list,"reset":cmd_reset}[g.cmd](g))
