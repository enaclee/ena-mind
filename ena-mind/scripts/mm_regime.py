#!/usr/bin/env python3
"""Классификатор режима рынка (ENA-MIND, логика v5.0 сохранена без изменений).
Это «психология фазы», которой не было в v4.

В v4 приоры аналитиков были одинаковыми в любой фазе. Здесь — режим определяется по
свечному ряду (ADX-подобная сила тренда, ATR%/BB-ширина для волатильности) и влияет
на приоры аналитиков в совете (mm_config.PRIORS_BY_REGIME).

Режимы:
- trend_up / trend_down: цена выше/ниже MA200, трендовая сила (DI+/DI-) высока;
- range:  цена внутри полосы, низкая трендовая сила — тут доминируют deriv (контр-тренд funding);
- volatile: высокая ATR%/BB-ширина — приоры прижимаются к нейтрали (всё шум);
- unknown: данных мало.

Это быстрые(proxy) оценки на чистой стандартной библиотеке — без ta-lib.
"""
import statistics
SIDES=("long","short","flat")
def _sma(xs,n):
    if len(xs)<n: return None
    return sum(xs[-n:])/n
def _atr(rows,n=14):
    if len(rows)<n+1: return None
    trs=[]
    for j in range(len(rows)-n,len(rows)):
        h,l,c,pc=rows[j][2],rows[j][3],rows[j][4],rows[j-1][4]
        trs.append(max(h-l,abs(h-pc),abs(l-pc)))
    return sum(trs)/n if trs else None
def _bb_width(closes,n=20,k=2.0):
    """Ширина полос Боллинджера как % от средней. Proxy волатильности."""
    if len(closes)<n: return None
    win=closes[-n:];mu=sum(win)/n
    var=sum((x-mu)**2 for x in win)/n;sd=var**0.5
    return (2*k*sd/mu*100.0) if mu>0 else None
def _di_proxy(rows,n=14):
    """Proxy ADX: отношение |направленного движения| к ATR.
    Возвращает (up_strength, down_strength, adx_proxy) в [0,1].
    Не настоящий Wilder ADX (нет сглаживания), но достаточно для классификации фазы."""
    if len(rows)<n+1: return None,None,None
    up=0.0;down=0.0
    for j in range(len(rows)-n,len(rows)):
        dm_up=max(rows[j][2]-rows[j-1][2],0)
        dn_dn=max(rows[j-1][3]-rows[j][3],0)
        if dm_up>dn_dn: up+=dm_up
        elif dn_dn>dm_up: down+=dn_dn
    a=_atr(rows,n) or 1.0
    return min(up/(a*n+1e-9),1.0),min(down/(a*n+1e-9),1.0),abs(up-down)/(a*n+1e-9)
def classify(rows,min_rows=200):
    """rows: [ts_ms,o,h,l,c,v]. Возвращает REGIME-запись."""
    out={"kind":"unknown","confidence":0.0,"adx":None,"atr_pct":None,"bb_width_pct":None,"note":""}
    if not rows or len(rows)<min_rows:
        out["note"]="INSUFFICIENT: нужно >=%d свечей, получено %d"%(min_rows,len(rows) if rows else 0)
        return out
    closes=[r[4] for r in rows];c=closes[-1]
    ma200=_sma(closes,200);atr=_atr(rows,14);bbw=_bb_width(closes,20)
    up,dn,adi=_di_proxy(rows,14)
    out["atr_pct"]=round(atr/c*100.0,3) if atr and c else None
    out["bb_width_pct"]=round(bbw,3) if bbw is not None else None
    out["adx"]=round(adi,3) if adi is not None else None
    # 1) волатильность бьёт всё: ATR% > 3% или BB-ширина > 8% -> volatile
    if (out["atr_pct"] and out["atr_pct"]>=3.0) or (out["bb_width_pct"] and out["bb_width_pct"]>=8.0):
        out["kind"]="volatile";out["confidence"]=round(min(0.55+0.05*(out["atr_pct"]/3.0),0.85),3)
        out["note"]="ATR=%.2f%%, BB-ширина=%.2f%% — высокая волатильность, приоры к нейтрали"%(out["atr_pct"],out["bb_width_pct"] or 0)
        return out
    # 2) есть тренд: MA200 определена, цена снаружи, ADI заметный
    if ma200:
        side_up=c>ma200 and up is not None and up>dn and adi>0.05
        side_dn=c<ma200 and dn is not None and dn>up and adi>0.05
        if side_up:
            out["kind"]="trend_up"
            out["confidence"]=round(min(0.55+0.1*adi,0.85),3)
            out["note"]="close>MA200 (%.2f) > MA200, трендовая сила ADI=%.2f -> trend_up"%(c,adi)
            return out
        if side_dn:
            out["kind"]="trend_down"
            out["confidence"]=round(min(0.55+0.1*adi,0.85),3)
            out["note"]="close<MA200 (%.2f), трендовая сила ADI=%.2f -> trend_down"%(c,adi)
            return out
    # 3) иначе — range: цена внутри полосы, ADI низкий
    out["kind"]="range";out["confidence"]=round(0.55,3)
    out["note"]="низкая трендовая сила ADI=%.2f, цена ~MA200 -> range (контр-тренд доминирует)"%(adi or 0)
    return out
def selftest():
    # тренд вверх: монотонный рост + явная сила
    up=[[i,100+i*0.2,101+i*0.2,99+i*0.2,100+i*0.2,1] for i in range(220)]
    r=classify(up)
    assert r["kind"]=="trend_up" and r["confidence"]>0.55,(r["kind"],r["note"])
    # тренд вниз
    dn=[[i,300-i*0.2,301-i*0.2,299-i*0.2,300-i*0.2,1] for i in range(220)]
    r=classify(dn)
    assert r["kind"]=="trend_down",(r["kind"],r["note"])
    # флэт/range: горизонтальный ряд
    flat=[[i,100,100.1,99.9,100,1] for i in range(220)]
    r=classify(flat)
    assert r["kind"]=="range",(r["kind"],r["note"])
    # волатильность: большие swings
    import math
    vol=[[i,100+10*math.sin(i/3),100+10*math.sin(i/3)+3,100+10*math.sin(i/3)-3,100+10*math.sin(i/3),1] for i in range(220)]
    r=classify(vol)
    assert r["kind"] in ("volatile","range"),(r["kind"],r["note"])  # ATR большой -> volatile
    # мало данных
    assert classify([[i,100,101,99,100,1] for i in range(50)])["kind"]=="unknown"
    print("mm_regime: OK")
if __name__=="__main__":
    import argparse
    ap=argparse.ArgumentParser()
    ap.add_argument("--selftest",action="store_true")
    ap.add_argument("--klines",help="CSV ts,o,h,l,c,v")
    g=ap.parse_args()
    if g.selftest: selftest()
    elif g.klines:
        import csv
        with open(g.klines,encoding="utf-8",newline="") as f:
            rows=[[float(x) for x in r[:6]] for r in list(csv.reader(f))[1:] if r]
        import json; print(json.dumps(classify(rows),ensure_ascii=False,indent=1))
    else: print("use --selftest or --klines csv")
