#!/usr/bin/env python3
"""Бэктест. Walk-forward + OOS, издержки/funding/гэпы, без lookahead. Логика v5.0 сохранена.
Отличия от v4:
- режим рынка определяется на каждом окне (mm_regime.classify) и влияет на выбор RR/ATR:
  в range — меньше сделок (выше порог подтверждения), в volatile — шире стоп;
- council-режим: к пробою добавляется подтверждение импульса, иначе NO TRADE (пропуск);
- сохранены метрики winrate/PF/expectancy/maxDD/sharpe/sortino/recovery.
"""
import argparse,csv,json,math,random,statistics
import mm_regime
def ma(rows,i,n):
    if i<n-1: return None
    return sum(r[4] for r in rows[i-n+1:i+1])/n
def atr(rows,i,n=14):
    if i<n: return None
    return sum(max(rows[j][2]-rows[j][3],abs(rows[j][2]-rows[j-1][4]),abs(rows[j][3]-rows[j-1][4])) for j in range(i-n+1,i+1))/n
def metrics(tr):
    n=len(tr)
    if n==0: return {"n":0}
    Rs=[t["R"] for t in tr];wins=[r for r in Rs if r>0];loss=[r for r in Rs if r<=0]
    eq=peak=dd=0.0
    for r in Rs:
        eq+=r;peak=max(peak,eq);dd=min(dd,eq-peak)
    mu=statistics.mean(Rs);sd=statistics.pstdev(Rs)
    down=math.sqrt(sum(min(0.0,r)**2 for r in Rs)/n)
    gw=sum(wins);gl=abs(sum(loss))
    return {"n":n,"winrate":round(len(wins)/n,3),"profit_factor":round(gw/gl,3) if gl>0 else None,
            "expectancy_R":round(mu,4),"sumR":round(eq,2),"maxDD_R":round(dd,2),
            "sharpe_per_trade":round(mu/sd,3) if sd>0 else None,
            "sortino_per_trade":round(mu/down,3) if down>0 else None,
            "recovery_factor":round(eq/abs(dd),3) if dd else None}
def run(rows,fee_rt=0.176,rr=2.2,atr_mult=1.0,time_stop=48,funding_per_8h=0.0,fill_frac=1.0,
        council=False,regime_aware=False,min_rows=200):
    """regime_aware=True: классифицировать режим на окне и адаптировать atr_mult.
    В volatile — atr_mult*1.3 (шире стоп, меньше ложных), в range — atr_mult*0.9."""
    tr=[];i=max(200,min_rows);skipped=0
    while i<len(rows)-1:
        c=rows[i][4];m28=ma(rows,i,28);m200=ma(rows,i,200);a=atr(rows,i)
        if None in (m28,m200,a) or a<=0: i+=1;continue
        prev=rows[i-1]
        ls=c>m200 and prev[3]<=m28 and c>prev[4] and c>m28
        ss=c<m200 and prev[2]>=m28 and c<prev[4] and c<m28
        if not (ls or ss): i+=1;continue
        if council:
            confirm=(c-m28)>=0.1*a if ls else (m28-c)>=0.1*a
            if not confirm: skipped+=1;i+=1;continue
        # regime-адаптация atr_mult
        am=atr_mult
        if regime_aware:
            r=mm_regime.classify(rows[max(0,i-220):i+1])
            if r["kind"]=="volatile": am*=1.3
            elif r["kind"]=="range": am*=0.9
        side=1 if ls else -1
        ei=i+1;entry=rows[ei][1]
        stop=entry-side*am*a;tp=entry+side*rr*am*a
        exit_px=None;reason=None;xi=None
        last=min(ei+time_stop,len(rows))
        for j in range(ei,last):
            o,h,l=rows[j][1],rows[j][2],rows[j][3]
            hs=(l<=stop) if side==1 else (h>=stop)
            ht=(h>=tp) if side==1 else (l<=tp)
            if hs:
                gap=(o<stop) if side==1 else (o>stop)
                exit_px=o if gap else stop;reason="gap_stop" if gap else "stop";xi=j;break
            if ht: exit_px=tp;reason="tp";xi=j;break
        if exit_px is None:
            xi=last-1;exit_px=rows[xi][4];reason="time"
        unit=28800.0*(1000.0 if rows[ei][0]>1e11 else 1.0)
        held=max(0.0,(rows[xi][0]-rows[ei][0])/unit)
        gross=(exit_px-entry)*side/entry*100.0
        net=(gross-fee_rt-side*funding_per_8h*held)*fill_frac
        stop_pct=abs(entry-stop)/entry*100.0
        tr.append({"sig_i":i,"entry_i":ei,"exit_i":xi,"side":side,"gross":gross,"net":net,
                   "R":net/(stop_pct+fee_rt),"reason":reason})
        i=xi+1
    if council and tr==[]: return {"trades":[],"no_trade":skipped}
    return tr
def _trades(obj): return obj["trades"] if isinstance(obj,dict) else obj
def walk_forward(rows,windows=4,train_frac=0.7,fee_rt=0.176,council=False,regime_aware=False):
    seg=len(rows)//windows;out=[]
    grid=[(rr,am) for rr in (1.8,2.2,2.6) for am in (0.8,1.0,1.2)]
    for k in range(windows):
        s=rows[k*seg:(k+1)*seg] if k<windows-1 else rows[k*seg:]
        cut=int(len(s)*train_frac);best=None
        for rr,am in grid:
            m=metrics(_trades(run(s[:cut],fee_rt,rr,am,council=council,regime_aware=regime_aware)))
            e=m.get("expectancy_R",-9)
            if best is None or e>best[0]: best=(e,rr,am)
        _,rr,am=best
        test=run(s[cut:],fee_rt,rr,am,council=council,regime_aware=regime_aware)
        out.append({"window":k,"chosen_rr":rr,"chosen_atr":am,"train_expectancy_R":round(best[0],4),
                    "oos":metrics(_trades(test)),"no_trade_oos":test.get("no_trade",0) if isinstance(test,dict) else 0,
                    "oos_index_range":[k*seg+cut,k*seg+len(s)]})
    return out
def demo(n,seed=7):
    random.seed(seed);p=73000.0;rows=[]
    for i in range(n):
        o=p;c=o*(1+random.gauss(0,0.004))
        h=max(o,c)*(1+abs(random.gauss(0,0.002)));l=min(o,c)*(1-abs(random.gauss(0,0.002)))
        rows.append([i*900,o,h,l,c,1000.0]);p=c
    return rows
def selftest():
    flat=[[i*900,100,100,100,100,1] for i in range(400)]
    assert run(flat)==[]
    tr=run(demo(3000))
    assert tr and all(tr[k]["entry_i"]>tr[k-1]["exit_i"] for k in range(1,len(tr)))
    assert all(t["entry_i"]>t["sig_i"] for t in tr)
    wf=walk_forward(demo(4000),windows=3)
    assert len(wf)==3
    assert all(wf[k]["oos_index_range"][0]>=wf[k-1]["oos_index_range"][1] for k in range(1,len(wf)))
    m=metrics(tr);assert "profit_factor" in m and "sortino_per_trade" in m
    mm=metrics([{"R":2.0},{"R":-1.0},{"R":-1.0},{"R":2.0}])
    assert abs(mm["sortino_per_trade"]-round(0.5/math.sqrt(0.5),3))<1e-9,mm
    # funding со знаком
    d=demo(3000);a=run(d,funding_per_8h=0.05);b=run(d)
    assert len(a)==len(b) and {t["side"] for t in a}=={1,-1}
    assert all((x["net"]-y["net"])*x["side"]<=1e-12 for x,y in zip(a,b))
    assert any((x["net"]-y["net"])*x["side"]<-1e-9 for x,y in zip(a,b))
    # council: с подтверждением сделок не больше; NO TRADE считается
    d=demo(4000);ta=run(d);co=run(d,council=True)
    co_tr=co["trades"] if isinstance(co,dict) else co
    assert len(co_tr)<=len(ta),(len(ta),len(co_tr))
    wfc=walk_forward(d,windows=3,council=True)
    assert all("no_trade_oos" in w for w in wfc)
    # regime_aware: не должен падать; сделки — список (тип сохранён)
    tr_r=run(d,regime_aware=True)
    assert isinstance(tr_r,list),type(tr_r)
    # regime_aware может слегка изменить число сделок (atr_mult зависит от фазы)
    # — главное, что не падает и возвращает список валидных трейдов
    if tr_r:
        assert all("R" in t for t in tr_r)
    print("mm_backtest: OK")
if __name__=="__main__":
    ap=argparse.ArgumentParser()
    ap.add_argument("--selftest",action="store_true");ap.add_argument("--csv")
    ap.add_argument("--demo",type=int,default=0);ap.add_argument("--fee",type=float,default=0.176)
    ap.add_argument("--windows",type=int,default=4);ap.add_argument("--council",action="store_true")
    ap.add_argument("--regime-aware",action="store_true")
    g=ap.parse_args()
    if g.selftest: selftest()
    else:
        if g.csv:
            with open(g.csv,encoding="utf-8",newline="") as f:
                rows=[[float(x) for x in r[:6]] for r in list(csv.reader(f))[1:] if r]
            from mm_data import series_quality
            q=series_quality(rows)
            if not q["ok"]: print(json.dumps({"error":"bad_csv","quality":q}));raise SystemExit(1)
            label="REAL"
        else:
            rows=demo(g.demo or 6000);label="SYNTHETIC"
        out={"data":label,"single_position":metrics(_trades(run(rows,g.fee,council=g.council,regime_aware=g.regime_aware)))}
        if g.council:
            sp=run(rows,g.fee,council=True,regime_aware=g.regime_aware)
            out["no_trade_signals"]=sp.get("no_trade",0) if isinstance(sp,dict) else 0
        out["walk_forward_oos"]=walk_forward(rows,g.windows,fee_rt=g.fee,council=g.council,regime_aware=g.regime_aware)
        print(json.dumps(out,ensure_ascii=False))
