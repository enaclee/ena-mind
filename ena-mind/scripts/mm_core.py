#!/usr/bin/env python3
"""Издержки, риск, ликвидация (ENA-MIND; логика v5.0 сохранена). Комиссии берутся из mm_config (Bybit).
Логика v4 сохранена: C — ожидаемые издержки (funding со знаком), C_worst — худший случай."""
import argparse,json,math
import mm_config
def cost_breakdown(market="fut",fin="maker",fout="taker",slip_in=0.02,slip_out=0.02,
                   funding_pct=0.0,n_funding=0,funding_sign=1,cfg=None):
    c=cfg or mm_config.load()
    fees=mm_config.fees(market,c)
    fi=fees[fin];fo=fees[fout]
    fund=funding_sign*abs(funding_pct)*n_funding
    return {"fee_in":fi,"fee_out":fo,"slip_in":slip_in,"slip_out":slip_out,"funding":fund,
            "total":fi+fo+slip_in+slip_out+fund,
            "total_worst":fi+fo+slip_in+slip_out+max(0.0,fund)}
def economics(p,target_pct,stop_pct,cb,rr_min=2.0):
    C=cb["total"];Cw=cb.get("total_worst",C);nw=target_pct-C;nl=stop_pct+C
    rr=nw/nl if nl>0 else 0.0
    E=p*nw-(1-p)*nl
    f={"target_ge_4C":target_pct>=4*C,"rr_ok":rr>=rr_min,"E_positive":E>0,"cost_le_25pct_stop":C<=0.25*stop_pct}
    return {"C_pct":round(C,6),"C_worst_pct":round(Cw,6),"net_win_pct":round(nw,6),
            "net_loss_pct":round(nl,6),"RR_net":round(rr,4),"min_target_pct":round(4*C,6),
            "E_pct":round(E,6),"filters":f,"pass":all(f.values())}
def round_step(q,step,mode="down"):
    if step<=0: raise ValueError("step>0")
    n=q/step
    n=math.floor(n+1e-9) if mode=="down" else math.ceil(n-1e-9)
    return round(n*step,12)
def size(risk_usd,stop_pct,price,lev=3.0,deposit=100.0,market="fut",fin="maker",fout="taker",
         funding_pct=0.0,n_funding=0,funding_sign=1,qty_step=0.001,min_qty=0.001,min_notional=100.0,cfg=None):
    if stop_pct<=0 or price<=0 or risk_usd<=0: return {"ok":False,"why":"bad_input"}
    cb=cost_breakdown(market,fin,fout,slip_in=0.02,slip_out=0.02,
                     funding_pct=funding_pct,n_funding=n_funding,funding_sign=funding_sign,cfg=cfg)
    loss_pct=stop_pct+cb["fee_in"]+cb["fee_out"]+cb["slip_in"]+cb["slip_out"]+max(0.0,cb["funding"])
    lot=round_step((risk_usd/(loss_pct/100.0))/price,qty_step,"down")
    if lot<min_qty or lot*price<min_notional:
        need=max(min_qty,round_step(min_notional/price,qty_step,"up"))
        return {"ok":False,"why":"min_lot_conflict","min_lot":need,"min_notional_usd":round(need*price,2),
                "risk_at_min_usd":round(need*price*loss_pct/100.0,4),"loss_pct_incl_costs":round(loss_pct,6)}
    notional=lot*price
    return {"ok":True,"lot":lot,"notional_usd":round(notional,4),"risk_usd":round(notional*loss_pct/100.0,4),
            "margin_usd":round(notional/lev,4),"loss_pct_incl_costs":round(loss_pct,6),"costs":cb,
            "warnings":(["margin_over_half_deposit"] if notional/lev>0.5*deposit else [])}
def liq_distance_pct(lev,mmr_pct=0.5,fee_buffer_pct=0.0):
    if lev<=0: raise ValueError("lev>0")
    return max(0.0,(1.0/lev-mmr_pct/100.0-fee_buffer_pct/100.0)*100.0)
def liq_buffer(lev,stop_pct,mmr_pct=0.5,fee_buffer_pct=0.0):
    d=liq_distance_pct(lev,mmr_pct,fee_buffer_pct)
    return {"liq_distance_pct":round(d,4),"buffer_x":round(d/stop_pct,3) if stop_pct>0 else None,
            "note":"isolated linear estimate; verify exact liq price in exchange app"}
def selftest():
    cb=cost_breakdown()
    assert abs(cb["total"]-0.176)<1e-9,cb  # maker 0.036 + taker 0.10 + 2×0.02
    # настраиваемые комиссии (главное в v5): поднимем maker до 0.10 -> total изменится
    cb2=cost_breakdown(cfg={"fees":{"fut":{"maker":0.10,"taker":0.10}}})
    assert abs(cb2["total"]-0.24)<1e-9,cb2
    assert abs(cost_breakdown("spot","taker","taker")["total"]-0.40)<1e-9
    assert abs(cost_breakdown(funding_pct=0.05,n_funding=2,funding_sign=1)["total_worst"]-0.276)<1e-9
    good=economics(0.55,2.2,0.8,cb)
    assert good["pass"],good
    assert not economics(0.55,1.6,0.7,cb)["filters"]["rr_ok"]
    assert not economics(0.55,2.0,0.7,cb)["filters"]["cost_le_25pct_stop"]
    rec=cost_breakdown(funding_pct=0.05,n_funding=1,funding_sign=-1)
    assert rec["total"]<0.176 and abs(rec["total_worst"]-0.176)<1e-9,rec
    c=size(1.0,0.8,73700)
    assert not c["ok"] and abs(c["risk_at_min_usd"]-1.44)<0.05,c
    s=size(1.5,0.8,73700)
    assert s["ok"] and s["lot"]==0.002 and s["risk_usd"]<=1.5+1e-9
    assert liq_buffer(3,0.8)["buffer_x"]>3
    assert liq_buffer(10,5.0)["buffer_x"]<3
    assert round_step(0.001999,0.001)==0.001 and round_step(0.001001,0.001,"up")==0.002
    print("mm_core: OK")
if __name__=="__main__":
    ap=argparse.ArgumentParser()
    ap.add_argument("--selftest",action="store_true")
    ap.add_argument("--price",type=float,default=73700);ap.add_argument("--stop",type=float,default=0.8)
    ap.add_argument("--target",type=float,default=2.2);ap.add_argument("--risk",type=float,default=1.5)
    ap.add_argument("--lev",type=float,default=3);ap.add_argument("--p",type=float,default=0.55)
    g=ap.parse_args()
    if g.selftest: selftest()
    else:
        cb=cost_breakdown()
        print(json.dumps({"economics":economics(g.p,g.target,g.stop,cb),
                          "sizing":size(g.risk,g.stop,g.price,g.lev),
                          "liquidation":liq_buffer(g.lev,g.stop)},ensure_ascii=False,indent=1))
