"""OUTLOOK ENGINE 6H & 24H v9.0 (ENA).

Независимые горизонты прогноза на 6 часов и 24 часа: P(UP)/P(FLAT)/P(DOWN)
с фиксированными порогами, сценариями (base/bull/bear), драйверами,
новостным давлением, деривативным давлением и условиями отмены.

Пороги фиксируются ДО тестирования:
- 6ч:  UP/DOWN = max(0.35%, 1.5σ6h)
- 24ч: UP/DOWN = max(0.75%, 1.5σ24h)
"""
import math,time

ENGINE_VERSION="outlook_v9"
CALC_VERSION="outlook_calc_v2"

# Фиксированные пороги (§16.3): не меняются задним числом
FEE_RT_PCT=0.11       # taker round-trip Bybit
UPDOWN_MIN_PCT=0.75   # 24ч минимум: ниже — движение неотличимо от шума
UPDOWN_MIN_6H_PCT=0.35  # 6ч минимум: ниже — шум внутридневного диапазона

# Типы событий классификатора (§16.2)
EVENT_TYPES=("regulation","etf","listing","delisting","exploit","outage",
             "macro","rates","inflation","employment","court","unlock",
             "partnership","upgrade","insolvency","security","rumor")

def threshold_pct(sigma_24h_pct=None):
    """Порог UP/DOWN/FLAT 24ч: max(UPDOWN_MIN_PCT, fees) и >= 1.5σ."""
    base=max(UPDOWN_MIN_PCT,FEE_RT_PCT)
    if sigma_24h_pct and sigma_24h_pct>0:
        return round(max(base,1.5*sigma_24h_pct),4)
    return round(base,4)

def threshold_pct_6h(sigma_6h_pct=None):
    """Порог UP/DOWN/FLAT 6ч: max(UPDOWN_MIN_6H_PCT, fees) и >= 1.5σ6h."""
    base=max(UPDOWN_MIN_6H_PCT,FEE_RT_PCT)
    if sigma_6h_pct and sigma_6h_pct>0:
        return round(max(base,1.5*sigma_6h_pct),4)
    return round(base,4)


# ---------------- новость: факт vs интерпретация (§16.1) ----------------

def classify_news(items,now=None):
    """Классификация новостей. items: [{title,source,url,published_ts,impact}].
    Возвращает события с типом, направлением, надёжностью, новизной.
    Дубликаты по нормализованному заголовку схлопываются: 10 перепечаток
    НЕ дают 10 подтверждений (§16.1)."""
    now=now or int(time.time())
    events=[]
    seen_titles={}
    for it in items or []:
        title=(it.get("title") or "").strip()
        if not title: continue
        # нормализация: нижний регистр, без пунктуации — ловим синдицированные копии
        norm="".join(ch for ch in title.lower() if ch.isalnum())[:80]
        if norm in seen_titles:
            seen_titles[norm]["n_copies"]+=1
            continue
        ev={"title":title,"source":it.get("source") or "unknown",
            "url":it.get("url"),"published_ts":it.get("published_ts"),
            "impact_raw":it.get("impact",0.0),
            "type":"macro","direction":"neutral","reliability":"medium",
            "novelty":"new","n_copies":1,
            "is_rumor":False}
        t=title.lower()
        # детерминированный классификатор ключевых слов (не LLM — проверяемо)
        if any(k in t for k in ("sec ","lawsuit","court","regulat","ban ","запрет","суд")):
            ev["type"]="regulation"
        elif "etf" in t: ev["type"]="etf"
        elif "listing" in t or "листинг" in t: ev["type"]="listing"
        elif "delist" in t: ev["type"]="delisting"
        elif any(k in t for k in ("hack","exploit","breach","взлом")): ev["type"]="exploit"
        elif any(k in t for k in ("outage","down","недоступ")): ev["type"]="outage"
        elif any(k in t for k in ("fed ","fomc","rate","ставк","cpi","inflation","инфляц")):
            ev["type"]="macro"
        elif "unlock" in t or "разблокир" in t: ev["type"]="unlock"
        elif "partnership" in t or "партнёр" in t or "партнер" in t: ev["type"]="partnership"
        elif any(k in t for k in ("rumor","слух","reported","по сообщениям","unconfirmed")):
            ev["type"]="rumor";ev["is_rumor"]=True;ev["reliability"]="low"
        # направление по impact из NewsRepo (словарь ключевых слов) + тип
        imp=float(ev["impact_raw"] or 0.0)
        if ev["is_rumor"]:
            ev["direction"]="uncertain"
        elif imp>0.15: ev["direction"]="bullish"
        elif imp<-0.15: ev["direction"]="bearish"
        else: ev["direction"]="neutral"
        # новизна: старше 48ч — уже учтено в цене, вес ниже (§16.3)
        ts=ev.get("published_ts")
        if ts and isinstance(ts,(int,float)):
            if ts>1e12: ts=ts/1000.0
            if now-ts>48*3600:
                ev["novelty"]="old"
        seen_titles[norm]=ev
        events.append(ev)
    return events


def news_pressure(events):
    """Новостное давление: только подтверждённые, новые, ненейтральные события.
    Слухи дают вклад x0.3; старые новости x0.5 (уже в цене); копии не считаются."""
    score=0.0;drivers=[]
    for ev in events:
        if ev["direction"] not in ("bullish","bearish"): continue
        w=1.0
        if ev["is_rumor"]: w*=0.3
        if ev["novelty"]=="old": w*=0.5
        s=1.0 if ev["direction"]=="bullish" else -1.0
        score+=s*w
        drivers.append({"title":ev["title"][:90],"type":ev["type"],
                        "direction":ev["direction"],"weight":w,
                        "n_copies":ev["n_copies"],"is_rumor":ev["is_rumor"]})
    return round(score,3),drivers


# ---------------- единый свежий расчёт 6H / 24H ----------------

def _build_outlook(horizon_h=24,klines=None,trend=None,news_items=None,
                   memory_stats=None,deriv=None,now=None):
    """Свежий расчёт прогноза на заданный горизонт (6ч или 24ч) без устаревшего кэша."""
    h=6 if int(horizon_h)<=6 else 24
    now=now or int(time.time())
    t0=time.time()
    trend_missing=trend is None or trend.get("status")!="OK"
    if trend_missing and klines is None:
        trend={"status":"INSUFFICIENT","reason":"нет свечей для тренд-слоя"}
    elif trend_missing and klines:
        try:
            import mm_trend
            trend=mm_trend.trend_layer(klines)
        except Exception:
            trend={"status":"INSUFFICIENT","reason":"тренд-слой недоступен"}
    else:
        trend=trend or {}

    sigma=None
    if h==6:
        rows15=(klines or {}).get("15") or []
        rows60=(klines or {}).get("60") or []
        if len(rows15)>=28:
            closes=[r[4] for r in rows15[-96:]]
            rets=[math.log(closes[i+1]/closes[i]) for i in range(len(closes)-1) if closes[i]>0 and closes[i+1]>0]
            if rets:
                mean=sum(rets)/len(rets)
                var=sum((r-mean)**2 for r in rets)/len(rets)
                sigma=math.sqrt(var)*100.0*math.sqrt(24.0)  # 24 бара по 15м = 6 часов
        elif len(rows60)>=12:
            closes=[r[4] for r in rows60[-48:]]
            rets=[math.log(closes[i+1]/closes[i]) for i in range(len(closes)-1) if closes[i]>0 and closes[i+1]>0]
            if rets:
                mean=sum(rets)/len(rets)
                var=sum((r-mean)**2 for r in rets)/len(rets)
                sigma=math.sqrt(var)*100.0*math.sqrt(6.0)
        thr=threshold_pct_6h(sigma)
        s=sigma if (sigma and sigma>0) else 1.4
    else:
        rows=(klines or {}).get("60") or []
        if len(rows)>=48:
            closes=[r[4] for r in rows[-48:]]
            rets=[math.log(closes[i+1]/closes[i]) for i in range(len(closes)-1) if closes[i]>0 and closes[i+1]>0]
            if rets:
                mean=sum(rets)/len(rets)
                var=sum((r-mean)**2 for r in rets)/len(rets)
                sigma=math.sqrt(var)*100.0*math.sqrt(24.0)
        thr=threshold_pct(sigma)
        s=sigma if (sigma and sigma>0) else 3.0

    events=classify_news(news_items,now)
    np,drivers=news_pressure(events)

    bias_score=0.0
    bias=trend.get("bias","NEUTRAL / RANGE")
    if "STRONG BULLISH" in bias: bias_score=1.0
    elif "BULLISH" in bias: bias_score=0.6
    elif "WEAK BULLISH" in bias: bias_score=0.3
    elif "STRONG BEARISH" in bias: bias_score=-1.0
    elif "BEARISH" in bias: bias_score=-0.6
    elif "WEAK BEARISH" in bias: bias_score=-0.3
    p_cont=(trend.get("persistence") or {}).get("p_continue",0.4)

    # мягкий вклад деривативов (funding/LSR перегрев)
    deriv_bias=0.0
    if isinstance(deriv,dict) and deriv.get("ok"):
        fp=deriv.get("funding_pct")
        if fp is not None:
            if fp>=0.04: deriv_bias-=0.2
            elif fp<=-0.02: deriv_bias+=0.2
        lsr_p=deriv.get("lsr_pct_rank")
        if lsr_p is not None:
            if lsr_p>=90: deriv_bias-=0.15
            elif lsr_p<=10: deriv_bias+=0.15

    edge=0.62*bias_score*p_cont/max(p_cont,0.34)+0.28*math.tanh(np/2.0)+0.10*max(-1.0,min(1.0,deriv_bias*2.5))
    mu=round(edge*thr,4) if thr else 0.0

    def phi(x): return 0.5*(1.0+math.erf(x/math.sqrt(2.0)))
    z_up=(thr-mu)/s
    z_dn=(-thr-mu)/s
    p_down=phi(z_dn)
    p_up=1.0-phi(z_up)
    p_flat=max(0.0,1.0-p_up-p_down)
    p_up=round(p_up,4);p_flat=round(p_flat,4);p_down=round(p_down,4)
    resid=round(1.0-(p_up+p_flat+p_down),10)
    if resid:
        if p_flat>=p_up and p_flat>=p_down: p_flat=round(p_flat+resid,4)
        elif p_up>=p_down: p_up=round(p_up+resid,4)
        else: p_down=round(p_down+resid,4)

    n_checked=(memory_stats or {}).get("n",0)
    calibrated=n_checked>=30
    if trend.get("status")=="INSUFFICIENT" and not events and sigma is None:
        status="INSUFFICIENT"
    elif trend.get("status")=="OK":
        status="OK"
    else:
        status="DEGRADED"

    base={"move_pct":mu,"prob":round(max(p_up,p_flat,p_down),3),
          "label":"UP" if p_up>=max(p_flat,p_down) else ("DOWN" if p_down>=p_flat else "FLAT")}
    bull={"move_pct":round(mu+1.2816*s,3),"prob":round(p_up,3),"label":"BULL"}
    bear={"move_pct":round(mu-1.2816*s,3),"prob":round(p_down,3),"label":"BEAR"}
    half_h=3 if h==6 else 12
    out={
        "engine":ENGINE_VERSION,"calc_version":CALC_VERSION,
        "status":status,"ts":int(time.time()*1000),
        "horizon_h":h,"computed_fresh":True,
        "threshold_pct":thr,
        "sigma_pct":round(s,4) if s else None,
        "p_up":p_up,"p_flat":p_flat,"p_down":p_down,
        "expected_move_pct":mu,
        "range_low_pct":round(mu-1.2816*s,3),"range_high_pct":round(mu+1.2816*s,3),
        "scenarios":{"base":base,"bull":bull,"bear":bear},
        "trend_bias":bias,"trend_strength":trend.get("strength"),
        "news_pressure":np,"news_events":len(events),
        "drivers":drivers[:5],
        "calibration":{"calibrated":calibrated,"n_checked":n_checked,
                       "label":"CALIBRATED" if calibrated else "EXPERIMENTAL / NOT CALIBRATED"},
        "invalidate":["P(сторона) падает ниже 35% при обновлении",
                      "новое событие типа exploit/regulation перевешивает тренд",
                      "порог %.2f%% пробит раньше %dч с ускорением"%(thr,half_h)],
        "next_update":"свежий пересчёт при каждом запросе и обновлении данных",
        "elapsed_ms":int((time.time()-t0)*1000),
    }
    if h==6:
        out["sigma_6h_pct"]=out["sigma_pct"]
    else:
        out["sigma_24h_pct"]=out["sigma_pct"]
    return out

def outlook_6h(klines=None,trend=None,news_items=None,memory_stats=None,
               deriv=None,now=None):
    """Актуальный 6H OUTLOOK: свежий расчёт на 6 часов с текущими данными и калибровкой."""
    return _build_outlook(6,klines,trend,news_items,memory_stats,deriv,now)

def outlook_24h(klines=None,trend=None,news_items=None,memory_stats=None,
                deriv=None,now=None):
    """24H OUTLOOK: тренд + моментум + волатильность + новости + калибровка."""
    return _build_outlook(24,klines,trend,news_items,memory_stats,deriv,now)


def brier_score(records):
    """Brier score для мультикласса (UP/FLAT/DOWN): среднее по прогнозам
    sum((p_i - y_i)^2). records: [{p_up,p_flat,p_down,outcome}] где outcome
    in ('UP','FLAT','DOWN'). Используется только по ЗАВЕРШЁННЫМ горизонтам."""
    if not records: return None
    tot=0.0
    for r in records:
        y={"UP":0.0,"FLAT":0.0,"DOWN":0.0}
        oc=r.get("outcome")
        if oc not in y: continue
        y[oc]=1.0
        tot+=(r["p_up"]-y["UP"])**2+(r["p_flat"]-y["FLAT"])**2+(r["p_down"]-y["DOWN"])**2
    return round(tot/len(records),4)


def _mk(n=300,drift=0.0015,seed=7):
    import random
    random.seed(seed)
    rows=[];p=100.0;t0=(time.time()-(n+2)*3600)*1000
    for i in range(n):
        o=p;p=max(0.01,p*(1+drift+random.gauss(0,0.004)))
        rows.append([int(t0)+(i+1)*3600000,round(o,6),round(max(o,p)*1.001,6),
                     round(min(o,p)*0.999,6),round(p,6),1.0])
    return rows

def selftest():
    # 1) тренд вверх + позитивные новости -> p_up > p_down; сумма = 1
    kl={"60":_mk(300,0.002,seed=1)}
    import mm_trend
    trend=mm_trend.trend_layer(kl)
    news=[{"title":"Bitcoin ETF sees record inflows","source":"reuters","url":"u1",
           "published_ts":int(time.time())-3600,"impact":0.5},
          {"title":"Bitcoin ETF sees record inflows","source":"coindesk","url":"u2",
           "published_ts":int(time.time())-3000,"impact":0.5}]  # синдицированная копия
    r=outlook_24h(kl,trend,news)
    assert r["p_up"]+r["p_flat"]+r["p_down"]>0.99999
    assert r["p_up"]>r["p_down"],(r["p_up"],r["p_down"])
    # дубликат не удваивает давление
    evs=classify_news(news)
    assert len(evs)==1 and evs[0]["n_copies"]==2
    # 2) слух: вклад ослаблен, маркирован
    rumor=[{"title":"Rumor: company may add BTC to balance sheet","source":"x",
            "url":"u","published_ts":int(time.time()),"impact":0.4}]
    evs=classify_news(rumor)
    assert evs[0]["is_rumor"] and evs[0]["direction"]=="uncertain"
    np_,_=news_pressure(evs)
    assert abs(np_)<0.001,"слух uncertain не даёт давления"
    # 3) старая новость — вес ниже
    old=[{"title":"Fed cuts rates by 50bp","source":"reuters","url":"u",
          "published_ts":int(time.time())-3*24*3600,"impact":0.6}]
    fresh=[{"title":"Fed cuts rates by 50bp","source":"reuters","url":"u",
            "published_ts":int(time.time())-3600,"impact":0.6}]
    n_old,_=news_pressure(classify_news(old))
    n_fresh,_=news_pressure(classify_news(fresh))
    assert n_old<n_fresh
    # 4) флэт-тренд без новостей -> FLAT-тяжёлое распределение
    fl=mm_trend._mk(300,0.0,0.0008,seed=5)
    tflat=mm_trend.trend_layer({"60":fl})
    r=outlook_24h({"60":fl},tflat,[])
    assert r["p_flat"]>=max(r["p_up"],r["p_down"])-1e-9
    # 5) порог фиксирован и согласован
    assert threshold_pct(None)==0.75
    assert threshold_pct(2.0)==3.0  # 1.5σ
    # 6) маркировка experimental при малой выборке
    assert "NOT CALIBRATED" in r["calibration"]["label"]
    r2=outlook_24h({"60":fl},tflat,[],memory_stats={"n":50})
    assert r2["calibration"]["label"]=="CALIBRATED"
    # 7) Brier: идеальный прогноз = 0, худший ~2
    assert brier_score([{"p_up":1.0,"p_flat":0,"p_down":0,"outcome":"UP"}])==0.0
    b=brier_score([{"p_up":0.0,"p_flat":0.0,"p_down":1.0,"outcome":"UP"}])
    assert b==2.0
    # 8) без тренда и без новостей — честный INSUFFICIENT
    r3=outlook_24h(None,None,[])
    assert r3["status"]=="INSUFFICIENT"
    # 9) горизонт 24ч и новый 6ч работают независимо и пересчитываются свежо
    assert r["horizon_h"]==24
    r6=outlook_6h({"60":_mk(300,0.002,seed=1),"15":_mk(120,0.0012,seed=2)},trend,news)
    assert r6["horizon_h"]==6 and r6["computed_fresh"] is True
    assert abs(r6["p_up"]+r6["p_flat"]+r6["p_down"]-1.0)<1e-6
    assert r6["p_up"]>r6["p_down"],r6
    assert threshold_pct_6h(None)==0.35
    print("mm_outlook24: OK")
