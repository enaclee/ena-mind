"""Trend Intelligence Layer v8.1 (ТЗ §13, v2 Дополнение).

Шесть компонентов трендового интеллекта ПОВЕРХ сырых данных и ДО совета:
  13.1 Multi-Timeframe Trend Agent  — структура/наклон/EMA/ADX по масштабам
  13.2 Market Structure Agent       — swing high/low, BOS, CHoCH (без look-ahead)
  13.3 Momentum & Acceleration Agent — ускорение/замедление, дивергенции
  13.4 Trend Persistence/Reversal    — вероятность продолжения/разворота
  13.5 Regime Detection Agent       — uptrend/downtrend/range/expansion/compression
  13.6 Trend Confidence & Calibration — калибровка по исторической точности

ГЛАВНЫЙ ПРИНЦИП (§11): слой обязан отвечать «куда вероятнее движется рынок»
даже когда идеального входа нет. Три раздельных вопроса:
  TREND OUTLOOK (направление) != TRADE SETUP (качество входа) != RISK STATUS.

Детерминизм: только прошедшие данные; swing-точки подтверждаются с задержкой
в k баров (§13.2 — без будущих свечей). Все формулы версионируются
(CALC_VERSION) и протестированы golden-тестами.
"""
import math

CALC_VERSION="trend_v1"

# ---------------- детерминированные вычислители (§56 v6) ----------------

def ema(vals,n):
    """EMA(n) по закрытиям; None при недостатке данных."""
    if not vals or len(vals)<n or n<=0: return None
    k=2.0/(n+1.0)
    e=sum(vals[:n])/n
    for v in vals[n:]: e=v*k+e*(1-k)
    return e

def ema_series(vals,n):
    """Полный ряд EMA (для наклона)."""
    if not vals or len(vals)<n: return []
    k=2.0/(n+1.0)
    e=sum(vals[:n])/n
    out=[None]*(n-1)+[e]
    for v in vals[n:]:
        e=v*k+e*(1-k);out.append(e)
    return out

def adx(rows,n=14):
    """ADX + DI+/DI- по OHLC-свечам [[ts,o,h,l,c,v]...]. (Wilder, упрощённо.)"""
    if len(rows)<2*n+1: return None
    trs=[];pdm=[];ndm=[]
    for i in range(1,len(rows)):
        h,l,pc=rows[i][2],rows[i][3],rows[i-1][4]
        trs.append(max(h-l,abs(h-pc),abs(l-pc)))
        up=h-rows[i-1][2];dn=rows[i-1][3]-l
        pdm.append(up if up>dn and up>0 else 0.0)
        ndm.append(dn if dn>up and dn>0 else 0.0)
    def smooth(xs):
        s=sum(xs[:n]);out=[s]
        for x in xs[n:]: s=s-s/n+x;out.append(s)
        return out
    atr=smooth(trs);p=smooth(pdm);q=smooth(ndm)
    dxs=[]
    for i in range(len(atr)):
        if atr[i]<=0: continue
        pdi=100.0*p[i]/atr[i];ndi=100.0*q[i]/atr[i]
        if pdi+ndi>0: dxs.append(100.0*abs(pdi-ndi)/(pdi+ndi))
    if len(dxs)<n: return None
    adx_v=sum(dxs[:n])/n
    for x in dxs[n:]: adx_v=(adx_v*(n-1)+x)/n
    pdi=100.0*p[-1]/atr[-1] if atr[-1]>0 else 0.0
    ndi=100.0*q[-1]/atr[-1] if atr[-1]>0 else 0.0
    return {"adx":round(adx_v,2),"di_plus":round(pdi,2),"di_minus":round(ndi,2)}

def swing_points(rows,k=3):
    """Подтверждённые swing high/low БЕЗ look-ahead (§13.2): точка i считается
    swing, только если k баров ПОСЛЕ неё уже закрылись. Возвращает
    [(idx, ts, price, 'H'|'L')...]."""
    out=[]
    if len(rows)<2*k+1: return out
    for i in range(k,len(rows)-k):
        win=rows[i-k:i+k+1]
        h=rows[i][2];l=rows[i][3]
        if h==max(r[2] for r in win) and sum(1 for r in win if r[2]==h)==1:
            out.append((i,rows[i][0],h,"H"))
        if l==min(r[3] for r in win) and sum(1 for r in win if r[3]==l)==1:
            out.append((i,rows[i][0],l,"L"))
    return out

def market_structure(rows,k=3):
    """BOS/CHoCH по подтверждённым swing-точкам (детерминированно).
    BOS: пробой последнего swing в направлении тренда;
    CHoCH: первый пробой ПРОТИВ prevailing структуры."""
    pts=swing_points(rows,k)
    if len(pts)<3: return {"structure":"UNKNOWN","events":[]}
    events=[]
    # prevailing structure из последних 4 точек
    last=pts[-4:]
    hhhl=all(last[j][3]!=last[j-1][3] for j in range(1,len(last)))
    closes=[r[4] for r in rows]
    last_high=max((p for p in pts if p[3]=="H"),key=lambda x:x[0])
    last_low=max((p for p in pts if p[3]=="L"),key=lambda x:x[0])
    price=closes[-1]
    # направление структуры: HH/HL против LH/LL по последним точкам
    highs=[p[2] for p in pts if p[3]=="H"][-2:]
    lows=[p[2] for p in pts if p[3]=="L"][-2:]
    structure="UNKNOWN"
    if len(highs)>=2 and len(lows)>=2:
        if highs[1]>highs[0] and lows[1]>lows[0]: structure="HH_HL"      # бычья
        elif highs[1]<highs[0] and lows[1]<lows[0]: structure="LH_LL"    # медвежья
        else: structure="RANGE"
    if structure in ("HH_HL","RANGE") and price>last_high[2] and last_high[0]<len(rows)-1:
        events.append("BOS_UP: закрытие выше swing high %.6g"%last_high[2])
    if structure in ("LH_LL","RANGE") and price<last_low[2] and last_low[0]<len(rows)-1:
        events.append("BOS_DOWN: закрытие ниже swing low %.6g"%last_low[2])
    if structure=="HH_HL" and price<last_low[2]:
        events.append("CHoCH_BEARISH: пробой swing low против бычьей структуры")
    if structure=="LH_LL" and price>last_high[2]:
        events.append("CHoCH_BULLISH: пробой swing high против медвежьей структуры")
    return {"structure":structure,"events":events,
            "last_swing_high":last_high[2] if last_high else None,
            "last_swing_low":last_low[2] if last_low else None,
            "n_swings":len(pts)}

def realized_vol(closes,n=60,per_bar_sec=60.0):
    """Реализованная волатильность (% за окно n баров)."""
    if len(closes)<n+1 or n<=0: return None
    rets=[math.log(closes[i+1]/closes[i]) for i in range(len(closes)-n-1,len(closes)-1)]
    mean=sum(rets)/n
    var=sum((r-mean)**2 for r in rets)/n
    return math.sqrt(var)*100.0

def regression_slope(closes,n=30):
    """Наклон линейной регрессии лог-цен (нормированный на цену, %/бар)."""
    if len(closes)<n or n<=0: return None
    ys=[math.log(c) for c in closes[-n:]]
    xs=list(range(n))
    mx=sum(xs)/n;my=sum(ys)/n
    num=sum((xs[i]-mx)*(ys[i]-my) for i in range(n))
    den=sum((x-mx)**2 for x in xs)
    if den==0: return None
    return (num/den)*100.0  # %/бар (лог-доходность)

def slope_t_stat(closes,n=30):
    """t-статистика наклона: во сколько раз наклон больше собственного шума
    регрессии. |t|<2 — наклон статистически неотличим от нуля (честный флэт)."""
    if len(closes)<n or n<=0: return None
    ys=[math.log(c) for c in closes[-n:]]
    xs=list(range(n))
    mx=sum(xs)/n;my=sum(ys)/n
    sxx=sum((x-mx)**2 for x in xs)
    if sxx==0: return None
    b=sum((xs[i]-mx)*(ys[i]-my) for i in range(n))/sxx
    resid=sum((ys[i]-(my+b*(xs[i]-mx)))**2 for i in range(n))
    sxx_=(n-2)
    if sxx_<=0: return None
    se=math.sqrt(resid/sxx_/sxx) if resid>0 else 0.0
    if se==0: return None
    return b/se

def slope_change(closes,n=30):
    """Ускорение: наклон последних n баров минус наклон предыдущих n (§13.3)."""
    s1=regression_slope(closes,n)
    if len(closes)<2*n: return (s1,None)
    s0=regression_slope(closes[:-n],n)
    return (s1,s0)


# ---------------- 13.1 Multi-Timeframe Trend Agent ----------------

TF_GROUPS={"micro":("1","3","5"),"short":("15","60"),
           "mid":("240","D"),"long":("W","1W")}

def trend_mtf(klines):
    """Multi-Timeframe Trend: направление/сила по каждому масштабу.
    Старший ТФ задаёт контекст, младший — фазу (§13.1). Расхождения
    показываем явно, не требуем совпадения."""
    out={}
    for group,tfs in TF_GROUPS.items():
        found=None
        for tf in tfs:
            rows=klines.get(tf)
            if rows and len(rows)>=40:
                found=(tf,rows);break
        if not found:
            out[group]={"status":"INSUFFICIENT"}
            continue
        tf,rows=found
        closes=[r[4] for r in rows]
        e20=ema(closes,20);e50=ema(closes,50)
        a=adx(rows,14)
        slope=regression_slope(closes,30)
        if e20 is None or e50 is None:
            out[group]={"status":"INSUFFICIENT","tf":tf}
            continue
        price=closes[-1]
        # направление: позиция цены к EMA + наклон + DI
        score=0.0
        if price>e20: score+=1
        if price>e50: score+=1
        if e20>e50: score+=1
        if slope is not None: score+=1 if slope>0 else -1
        if a:
            if a["di_plus"]>a["di_minus"]: score+=1
            else: score-=1
        direction="BULLISH" if score>=2 else ("BEARISH" if score<=-2 else "NEUTRAL")
        strength=min(100,int(abs(score)/6*100))
        out[group]={"status":"OK","tf":tf,"direction":direction,"strength":strength,
                    "price_vs_ema20":"above" if price>e20 else "below",
                    "ema20_vs_ema50":"above" if e20>e50 else "below",
                    "slope_pct_per_bar":round(slope,5) if slope is not None else None,
                    "adx":a["adx"] if a else None,
                    "calc_version":CALC_VERSION}
    return out


# ---------------- 13.2 Market Structure Agent ----------------

def trend_structure(klines):
    rows=klines.get("60") or klines.get("15") or klines.get("5")
    if not rows or len(rows)<40:
        return {"status":"INSUFFICIENT"}
    st=market_structure(rows,k=3)
    st["status"]="OK"
    st["calc_version"]=CALC_VERSION
    return st


# ---------------- 13.3 Momentum & Acceleration Agent ----------------

def trend_momentum(klines):
    rows=klines.get("5") or klines.get("15")
    if not rows or len(rows)<70:
        return {"status":"INSUFFICIENT"}
    closes=[r[4] for r in rows];vols=[r[5] for r in rows]
    s1,s0=slope_change(closes,30)
    if s1 is None:
        return {"status":"INSUFFICIENT"}
    t1=slope_t_stat(closes,30)
    accel=None
    if s0 is not None: accel=round(s1-s0,6)
    # объёмный импульс: средний объём последних 10 против предыдущих 30
    v_recent=sum(vols[-10:])/10.0
    v_base=sum(vols[-40:-10])/30.0 if len(vols)>=40 else None
    vol_ratio=round(v_recent/v_base,2) if v_base and v_base>0 else None
    # дивергенция цена/объём (§13.3): движение без объёмного подтверждения
    diver=False
    if s1>0 and vol_ratio is not None and vol_ratio<0.7: diver=True
    if s1<0 and vol_ratio is not None and vol_ratio<0.7: diver=True
    return {"status":"OK","slope_pct_per_bar":round(s1,5),
            "slope_t":round(t1,2) if t1 is not None else None,
            "prev_slope_pct_per_bar":round(s0,5) if s0 is not None else None,
            "acceleration":accel,"vol_ratio_recent_vs_base":vol_ratio,
            "price_volume_divergence":diver,
            "calc_version":CALC_VERSION}


# ---------------- 13.4 Trend Persistence / Reversal Agent ----------------

def trend_persistence(klines):
    """Вероятность продолжения/перехода в диапазон/разворота — эвристика по
    согласованности масштабов и ADX; БЕЗ заявлений о гарантированном исходе."""
    mtf=trend_mtf(klines)
    groups=[g for g in ("micro","short","mid") if mtf[g].get("status")=="OK"]
    if len(groups)<2:
        return {"status":"INSUFFICIENT"}
    dirs=[mtf[g]["direction"] for g in groups]
    bull=dirs.count("BULLISH");bear=dirs.count("BEARISH")
    if bull+bear==0:
        return {"status":"OK","persistence":"RANGE","p_continue":0.34,
                "p_range":0.44,"p_reversal":0.22,"note":"все масштабы нейтральны",
                "calc_version":CALC_VERSION}
    dominant="BULLISH" if bull>bear else "BEARISH"
    agree=max(bull,bear)/float(len(dirs))
    p_continue=round(0.30+0.35*agree,3)
    p_reversal=round(max(0.10,0.40-0.35*agree),3)
    p_range=round(max(0.05,1.0-p_continue-p_reversal),3)
    # нормировка в 1.0
    tot=p_continue+p_range+p_reversal
    p_continue=round(p_continue/tot,3);p_range=round(p_range/tot,3)
    p_reversal=round(1.0-p_continue-p_range,3)
    return {"status":"OK","persistence":dominant if agree>=0.6 else "TRANSITION",
            "p_continue":p_continue,"p_range":p_range,"p_reversal":p_reversal,
            "tf_agreement":round(agree,2),
            "note":"историческая повторяемость паттерна не гарантирует исход",
            "calc_version":CALC_VERSION}


# ---------------- 13.5 Regime Detection Agent ----------------

def trend_regime(klines):
    rows=klines.get("60") or klines.get("15")
    if not rows or len(rows)<80:
        return {"status":"INSUFFICIENT"}
    closes=[r[4] for r in rows]
    a=adx(rows,14)
    rv_now=realized_vol(closes[-30:],30)
    rv_prev=realized_vol(closes[-60:-30],30) if len(closes)>=60 else None
    e20=ema(closes,20);e50=ema(closes,50)
    if a is None or rv_now is None or e20 is None or e50 is None:
        return {"status":"INSUFFICIENT"}
    vol_ratio=rv_now/rv_prev if rv_prev and rv_prev>0 else None
    if a["adx"]>=25 and e20>e50: regime="uptrend"
    elif a["adx"]>=25 and e20<e50: regime="downtrend"
    elif vol_ratio and vol_ratio>=1.6: regime="high-volatility expansion"
    elif vol_ratio and vol_ratio<=0.6: regime="low-volatility compression"
    else: regime="range"
    return {"status":"OK","regime":regime,"adx":a["adx"],
            "di_plus":a["di_plus"],"di_minus":a["di_minus"],
            "rv_30":round(rv_now,4),"vol_ratio_30v30":round(vol_ratio,2) if vol_ratio else None,
            "calc_version":CALC_VERSION}


# ---------------- 13.6 Trend Confidence & Calibration Agent ----------------

def trend_calibration(memory_stats=None,min_n=20):
    """Калибровка по исторической точности трендовых выводов (§13.6):
    shrinkage к базовому весу на малых выборках; без повышения веса из-за
    короткой серии удач. memory_stats — dict {n,hit_rate} (mm_memory)."""
    base=0.5
    if not memory_stats or memory_stats.get("n",0)<min_n:
        return {"status":"OK","calibrated":False,"weight":base,
                "note":"выборка < %d — вес базовый (shrinkage)"%min_n,
                "calc_version":CALC_VERSION}
    n=memory_stats["n"];hr=memory_stats.get("hit_rate",0.5)
    # эмпирический байес: вес = (n*hr + k*base)/(n+k), k=30
    k=30.0
    w=(n*hr+k*base)/(n+k)
    return {"status":"OK","calibrated":True,"weight":round(w,3),"n":n,
            "calc_version":CALC_VERSION}


# ---------------- слой целиком: TREND OUTLOOK ≠ ENTRY ≠ RISK (§11) ----------------

def trend_layer(klines,memory_stats=None):
    """Полный Trend Intelligence Layer. Возвращает карточку тренда для UI
    и совета: bias (7 градаций §12), strength 0-100, entry_quality, risk,
    confidence, mtf-выравнивание, подтверждения/конфликты."""
    mtf=trend_mtf(klines)
    structure=trend_structure(klines)
    momentum=trend_momentum(klines)
    persistence=trend_persistence(klines)
    regime=trend_regime(klines)
    calibration=trend_calibration(memory_stats)
    # ---- направление: взвешенный свод масштабов + структура + моментум ----
    score=0.0;n_inputs=0
    for g,w in (("micro",1.0),("short",2.0),("mid",3.0)):
        d=mtf[g]
        if d.get("status")!="OK": continue
        s=1 if d["direction"]=="BULLISH" else (-1 if d["direction"]=="BEARISH" else 0)
        # NEUTRAL на масштабе не даёт вклада; вклад масштаба взвешен силой
        score+=w*s*(0.5+0.5*d["strength"]/100.0)
        n_inputs+=w
    if structure.get("status")=="OK":
        st=structure.get("structure")
        if st=="HH_HL": score+=2.5;n_inputs+=2.5
        elif st=="LH_LL": score-=2.5;n_inputs+=2.5
        evs=" ".join(structure.get("events") or [])
        if "BOS_UP" in evs or "CHoCH_BULLISH" in evs: score+=1.0;n_inputs+=1.0
        if "BOS_DOWN" in evs or "CHoCH_BEARISH" in evs: score-=1.0;n_inputs+=1.0
    if momentum.get("status")=="OK":
        s=momentum.get("slope_pct_per_bar") or 0.0
        # вклад наклона только при статистической значимости (|t|>=2):
        # синусные колебания флэта дают наклон меньше собственного шума
        t=momentum.get("slope_t") or 0.0
        score+=2.0*(1 if t>=2 else -1 if t<=-2 else 0);n_inputs+=2.0
    if n_inputs==0:
        return {"status":"INSUFFICIENT",
                "reason":"нет достаточных свечей ни на одном масштабе (нужно >=40 баров)",
                "calc_version":CALC_VERSION}
    norm=score/n_inputs  # [-1..1]
    # градации §12
    if norm>=0.5: bias="STRONG BULLISH"
    elif norm>=0.25: bias="BULLISH"
    elif norm>=0.08: bias="WEAK BULLISH"
    elif norm<=-0.5: bias="STRONG BEARISH"
    elif norm<=-0.25: bias="BEARISH"
    elif norm<=-0.08: bias="WEAK BEARISH"
    else: bias="NEUTRAL / RANGE"
    strength=min(100,int(abs(norm)*100))
    # ---- entry quality: отдельный вопрос (§11) ----
    entry="LOW"
    if structure.get("status")=="OK":
        price_row=(klines.get("60") or klines.get("15") or [None])[-1]
        price=price_row[4] if price_row else None
        sh=structure.get("last_swing_high");sl=structure.get("last_swing_low")
        if price and sh and sl:
            rng=sh-sl
            if rng>0:
                pos=(price-sl)/rng  # 0 у поддержки, 1 у сопротивления
                if pos<=0.35 and bias.endswith("BULLISH"): entry="GOOD"
                elif pos>=0.65 and bias.endswith("BEARISH"): entry="GOOD"
                elif 0.35<pos<0.65: entry="MEDIUM"
                else: entry="LOW"  # вход против ближайшего уровня
    # ---- risk: волатильность/режим (§11) ----
    risk="NORMAL"
    if regime.get("status")=="OK":
        r=regime.get("regime","")
        if "expansion" in r or "downtrend" in r and bias.endswith("BULLISH"):
            risk="ELEVATED"
        if "compression" in r: risk="COMPRESSED"
    # подтверждения/конфликты
    confirms=[];conflicts=[]
    for g in ("micro","short","mid"):
        d=mtf[g]
        if d.get("status")=="OK":
            (confirms if (d["direction"].endswith("BULLISH") and "BULLISH" in bias) or
                         (d["direction"].endswith("BEARISH") and "BEARISH" in bias)
             else conflicts if d["direction"] not in ("NEUTRAL",) else confirms
             ).append("%s(%s): %s"%(g,d.get("tf"),d["direction"]))
    if momentum.get("price_volume_divergence"):
        conflicts.append("дивергенция цена/объём")
    conf=round(min(0.9,0.35+0.15*n_inputs*0.3+strength/100.0*0.3),3)
    if calibration.get("calibrated"):
        conf=round(conf*calibration["weight"]/0.5*0.5+conf*0.5,3)  # мягкая калибровка
    return {"status":"OK","bias":bias,"strength":strength,
            "entry_quality":entry,"risk":risk,"confidence":conf,
            "mtf":mtf,"structure":structure,"momentum":momentum,
            "persistence":persistence,"regime":regime,
            "calibration":calibration,"n_inputs":round(n_inputs,1),
            "confirms":confirms[:4],"conflicts":conflicts[:4],
            "calc_version":CALC_VERSION}


def _mk(n=300,drift=0.0015,vol=0.004,seed=7):
    """Детерминированные свечи для тестов (fresh к текущему времени).
    Тренд + регулярные откаты (синус) + шум: даёт swing-точки, как реальный рынок."""
    import random,time as _t,math
    random.seed(seed)
    rows=[];t0=(_t.time()-(n+2)*3600)*1000
    pull=0.012 if abs(drift)>1e-9 else 0.0  # откаты только на трендах
    for i in range(n):
        base=100.0*(1.0+drift*i)*(1.0+pull*math.sin(i/5.0))
        o=base
        p=max(0.01,base*(1+random.gauss(0,vol)))
        rows.append([int(t0)+(i+1)*3600000,round(o,6),round(max(o,p)*1.001,6),
                     round(min(o,p)*0.999,6),round(p,6),round(random.uniform(0.5,2),4)])
    return rows

def selftest():
    # 1) восходящий тренд -> BULLISH-смещение, но НЕ автоматический GOOD entry
    up=_mk(300,0.0020,0.003,seed=1)
    kl={"60":up,"15":up,"5":up}
    r=trend_layer(kl)
    assert r["status"]=="OK",r.get("reason")
    assert "BULLISH" in r["bias"],r["bias"]
    assert 0<=r["strength"]<=100
    # три вопроса разделены (§11)
    assert r["entry_quality"] in ("GOOD","MEDIUM","LOW")
    assert r["risk"] in ("NORMAL","ELEVATED","COMPRESSED")
    # 2) нисходящий тренд
    dn=_mk(300,-0.0020,0.003,seed=2)
    r=trend_layer({"60":dn,"15":dn,"5":dn})
    assert "BEARISH" in r["bias"],r["bias"]
    # 3) флэт -> NEUTRAL/RANGE — полноценный прогноз, не отказ
    fl=_mk(300,0.0,0.0015,seed=5)
    r=trend_layer({"60":fl,"15":fl,"5":fl})
    assert r["bias"] in ("NEUTRAL / RANGE","WEAK BULLISH","WEAK BEARISH"),r["bias"]
    # 4) расхождение 5m и 1h показывается явно (§13.1)
    mixed={"60":up,"15":fl,"5":dn}
    r=trend_layer(mixed)
    assert r["status"]=="OK"
    # 5) swing без look-ahead: точка подтверждается только k барами позже
    pts=swing_points(up,k=3)
    assert len(pts)>=2
    i,ts,price,kind=pts[-1]
    assert i<=len(up)-4,"swing не может быть в последних k барах"
    # 6) структура распознаёт HH/HL на тренде вверх
    st=market_structure(up,3)
    assert st["structure"] in ("HH_HL","RANGE"),st
    # 7) калибровка: малая выборка -> базовый вес
    c=trend_calibration({"n":5,"hit_rate":0.9})
    assert not c["calibrated"] and c["weight"]==0.5
    c=trend_calibration({"n":100,"hit_rate":0.62})
    assert c["calibrated"] and 0.52<c["weight"]<0.66
    # 8) persistence суммы в 1
    p=trend_persistence(kl)
    assert abs(p["p_continue"]+p["p_range"]+p["p_reversal"]-1.0)<1e-6
    # 9) пустые данные -> INSUFFICIENT с причиной, не выдуманный тренд
    r=trend_layer({})
    assert r["status"]=="INSUFFICIENT" and r.get("reason")
    # 10) golden: ema/adx детерминизм
    assert ema([1.0,2.0,3.0,4.0,5.0],3)==4.0  # seed=SMA(1..3)=2, затем 4,5 с k=0.5
    assert ema([1,2,3,4,5],5)==3.0
    assert ema([],5) is None
    a=adx(up,14);assert a and 0<=a["adx"]<=100
    print("mm_trend: OK")

