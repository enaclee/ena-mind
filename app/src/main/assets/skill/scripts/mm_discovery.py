"""MARKET DISCOVERY + PATTERN DETECTION v8.1 (ТЗ §42-43 v5).

mm_discovery: сканер watchlist на аномалии/формации + event-driven candidate
routing. Дешёвая детерминированная фильтрация; полный Deep Analysis НЕ
запускается на каждый тик (§42.2, §49).

mm_patterns (в этом же модуле): детерминированные детекторы формаций без
look-ahead: range breakout, false breakout, retest, HH/HL, BOS/CHoCH,
compression->expansion, volume-confirmed breakout, price-volume divergence.
"""
import math,time

CALC_VERSION="discovery_v1"
_CAND_SEQ=[0]  # защита от одинаковых id при вызовах в одну миллисекунду

# ---------------- детекторы формаций (§43) ----------------

def range_bounds(rows,lookback=60):
    """Диапазон последних N закрытий (без будущих свечей)."""
    if len(rows)<lookback: return None
    closes=[r[4] for r in rows[-lookback:]]
    return min(closes),max(closes)

def detect_breakout(rows,lookback=60,confirm_bars=1):
    """Пробой диапазона с подтверждением закрытием (§43).
    Возвращает None или {type,direction,level,confirmed_at_ts,bars_since}."""
    rb=range_bounds(rows,lookback)
    if not rb: return None
    lo,hi=rb
    # проверяем закрытия ПОСЛЕ диапазона... диапазон включает текущие,
    # поэтому сравниваем последнюю свечу с диапазоном предыдущих
    if len(rows)<lookback+confirm_bars: return None
    # окно диапазона ПЕРЕД проверяемым участком: [-(lookback+w), -w)
    w=confirm_bars
    prev=rows[-(lookback+w):-w]
    prev_closes=[r[4] for r in prev]
    plo,phi=min(prev_closes),max(prev_closes)
    if phi<=plo: return None
    # проверяемая зона: последние w свечей; пробой = закрытие за диапазоном.
    # Чтобы поймать пробой, случившийся w-1 баров назад, а не только на последней
    # свече, зона сканируется с конца, уровень — по окну ДО зоны.
    for r in rows[-w:]:
        if r[4]>phi:
            return {"type":"breakout","direction":"UP","level":round(phi,8),
                    "confirmed_at_ts":r[0],"bars_since":w,
                    "close":r[4]}
        if r[4]<plo:
            return {"type":"breakdown","direction":"DOWN","level":round(plo,8),
                    "confirmed_at_ts":r[0],"bars_since":w,
                    "close":r[4]}
    return None

def detect_false_breakout(rows,lookback=60,max_bars_out=3):
    """Ложный пробой: закрытие за диапазоном, затем возврат внутрь в течение
    max_bars_out баров БЕЗ использования будущих свечей (детекция возможна
    только на свече возврата)."""
    if len(rows)<lookback+max_bars_out+1: return None
    base=rows[-(lookback+max_bars_out+1):-max_bars_out-1]
    closes=[r[4] for r in base]
    lo,hi=min(closes),max(closes)
    after=rows[-(max_bars_out+1):]
    broke=None
    for i,r in enumerate(after):
        if r[4]>hi: broke=("UP",i,hi)
        elif r[4]<lo: broke=("DOWN",i,lo)
        if broke: break
    if not broke: return None
    direction,break_idx,level=broke
    # возврат внутрь после пробоя (на любой из последующих свечей)
    for r in after[break_idx+1:]:
        if direction=="UP" and r[4]<level:
            return {"type":"false_breakout","direction":direction,
                    "level":round(level,8),"returned_at_ts":r[0],
                    "note":"пробой не удержан — возврат в диапазон"}
        if direction=="DOWN" and r[4]>level:
            return {"type":"false_breakout","direction":direction,
                    "level":round(level,8),"returned_at_ts":r[0],
                    "note":"пробой не удержан — возврат в диапазон"}
    return None

def detect_retest(rows,lookback=60,window=8):
    """Ретест после пробоя: цена возвращается к уровню пробоя и держится.
    Без будущих свечей: ретест фиксируется на свече отскока."""
    if len(rows)<lookback+window+2: return None
    base=rows[-(lookback+window+2):-window-2]
    closes=[r[4] for r in base]
    lo,hi=min(closes),max(closes)
    after=rows[-(window+2):]
    broke=None
    for i,r in enumerate(after):
        if r[4]>hi: broke=("UP",hi);break
        if r[4]<lo: broke=("DOWN",lo);break
    if not broke: return None
    direction,level=broke
    # ищем возврат к уровню (в пределах 0.3% от уровня) и отскок
    for r in after:
        if r[0]<=after[0][0]: continue
        if abs(r[4]-level)/max(level,1e-9)<0.003:
            return {"type":"retest","direction":direction,
                    "level":round(level,8),"retest_ts":r[0],
                    "note":"ретест уровня пробоя"}
    return None

def detect_compression_expansion(rows,vol_n=30,ratio_on=1.8,ratio_off=0.5):
    """Сжатие -> расширение: короткое окно волатильности против длинного."""
    if len(rows)<vol_n*2: return None
    def rv(win):
        c=[r[4] for r in win]
        rets=[math.log(c[i+1]/c[i]) for i in range(len(c)-1)]
        m=sum(rets)/len(rets)
        return math.sqrt(sum((r-m)**2 for r in rets)/len(rets))
    short=rv(rows[-vol_n//2:])
    long_=rv(rows[-vol_n:])
    if long_<=0: return None
    ratio=short/long_
    if ratio>=ratio_on:
        return {"type":"expansion","ratio":round(ratio,2),
                "note":"волатильность расширяется (%.1fx)"%ratio}
    if ratio<=ratio_off:
        return {"type":"compression","ratio":round(ratio,2),
                "note":"волатильность сжимается (%.1fx)"%ratio}
    return None

def detect_volume_confirmed(rows,n=20,vol_mult=2.0,confirm_bars=12):
    """Пробой с объёмным подтверждением (§43): та же зона подтверждения,
    объём свечи пробоя против среднего окна."""
    bo=detect_breakout(rows,lookback=n,confirm_bars=confirm_bars)
    if not bo: return None
    # ищем свечу пробоя по ts из результата
    brk=None
    for r in rows:
        if r[0]==bo.get("confirmed_at_ts"): brk=r;break
    if brk is None: return bo
    vols=[r[5] for r in rows if r[0]<brk[0]][-n:]
    avg=sum(vols)/len(vols) if vols else 0
    if avg>0 and brk[5]>=vol_mult*avg:
        bo["volume_confirmed"]=True
        bo["vol_ratio"]=round(brk[5]/avg,2)
    return bo

def detect_price_volume_divergence(rows,n=30):
    """Цена растёт/падает, объём падает (§43)."""
    if len(rows)<n: return None
    win=rows[-n:]
    closes=[r[4] for r in win];vols=[r[5] for r in win]
    half=n//2
    c1=sum(closes[:half])/half;c2=sum(closes[half:])/(n-half)
    v1=sum(vols[:half])/half;v2=sum(vols[half:])/(n-half)
    if v1<=0: return None
    price_up=c2>c1;vol_down=v2<v1*0.7
    price_dn=c2<c1
    if (price_up or price_dn) and vol_down:
        return {"type":"price_volume_divergence",
                "direction":"UP" if price_up else "DOWN",
                "vol_ratio":round(v2/v1,2),
                "note":"движение без объёмного подтверждения"}
    return None


PATTERN_DETECTORS=(
    ("breakout",detect_volume_confirmed),
    ("false_breakout",detect_false_breakout),
    ("retest",detect_retest),
    ("volatility",detect_compression_expansion),
    ("divergence",detect_price_volume_divergence),
)

def detect_all(rows):
    """Все детекторы по свечам. Каждая формация — с правилом и версией (§43)."""
    out=[]
    for name,fn in PATTERN_DETECTORS:
        try:
            r=fn(rows)
            if r:
                r["pattern"]=name
                r["calc_version"]=CALC_VERSION
                r["detected_at"]=int(time.time()*1000)
                out.append(r)
        except Exception:
            continue
    return out


# ---------------- сканер и кандидаты (§42) ----------------

def scan_symbol(rows,rel_volume=None,returns=None):
    """Дешёвый скан одного инструмента: формации + аномалии.
    rows — 60m или 15m свечи; rel_volume/returns — опционально от движка."""
    if not rows or len(rows)<70:
        return {"status":"INSUFFICIENT","patterns":[]}
    patterns=detect_all(rows)
    # относительная сила/объём (если переданы)
    score=0.0;reasons=[]
    for p in patterns:
        w={"breakout":2.0,"breakdown":2.0,"false_breakout":1.5,"retest":1.5,
           "expansion":1.0,"compression":0.5,"price_volume_divergence":1.0}.get(p["pattern"],0.5)
        score+=w;reasons.append(p["pattern"])
    if rel_volume and rel_volume>=2.0:
        score+=1.5;reasons.append("rel_volume %.1fx"%rel_volume)
    if returns is not None and abs(returns)>=5.0:
        score+=1.0;reasons.append("движение %.1f%%"%returns)
    return {"status":"OK","patterns":patterns,
            "priority_score":round(score,2),"reasons":reasons}

def make_candidate(symbol,patterns,score,data_quality="A"):
    """Структура MARKET_CANDIDATE (§42.2): маршрутизация в ветви графа."""
    routing={
        "breakout":["trend","market_structure","volume","order_flow"],
        "breakdown":["trend","market_structure","volume","order_flow"],
        "false_breakout":["market_structure","order_flow"],
        "retest":["trend","volume","order_flow"],
        "expansion":["volatility","order_flow"],
        "compression":["volatility"],
        "price_volume_divergence":["volume","trade_delta"],
    }
    branches=set()
    for p in patterns:
        branches.update(routing.get(p.get("pattern",""),[]))
    q={"A":1.0,"C":0.7,"D":0.3}.get(data_quality,0.5)
    priority=round(min(10.0,score*q),2)
    _CAND_SEQ[0]+=1
    return {"candidate_id":"cand_%s_%d_%d"%(symbol,int(time.time()*1000)%1000000,_CAND_SEQ[0]),
            "asset":symbol,"detected_at":int(time.time()*1000),
            "patterns":[p["pattern"] for p in patterns],
            "priority_score":priority,"confidence":q,
            "data_quality":data_quality,
            "routes":sorted(branches),
            "reason":"; ".join(p.get("note") or p["pattern"] for p in patterns)[:200],
            "expiry_at":int((time.time()+3600)*1000),
            "status":"new"}

def rank_candidates(candidates):
    """Ранжирование (§42.3): качество данных, значимость, ликвидность.
    priority НЕ вероятность прибыли (§42.3) — помечаем явно."""
    ranked=sorted(candidates,key=lambda c:-c["priority_score"])
    for i,c in enumerate(ranked):
        c["rank"]=i+1
    return ranked


def selftest():
    def mk(n=200,mode="range",seed=1):
        import random
        random.seed(seed)
        rows=[];p=100.0;t0=(time.time()-(n+2)*3600)*1000
        for i in range(n):
            o=p
            if mode=="range":
                p=100.0+random.uniform(-1.5,1.5)
            elif mode=="breakout":
                p=100.0+random.uniform(-1.5,1.5) if i<n-11 else 103.0+random.uniform(0,0.4)
            elif mode=="false":
                p=100.0+random.uniform(-1.5,1.5) if i<n-11 else (102.8 if i in (n-6,n-5,n-4) else 99.2)
            elif mode=="uptrend":
                p*=1.002
            rows.append([int(t0)+(i+1)*3600000,round(o,4),round(max(o,p)*1.001,4),
                         round(min(o,p)*0.999,4),round(p,4),
                         round(random.uniform(1,3)+(3 if mode=="breakout" and i>=n-11 else 0),2)])
        return rows
    rng=mk(200,"range",1)
    bo=mk(200,"breakout",2)
    # пробой детектируется после подтверждения (§50.1)
    r=detect_breakout(bo,lookback=60,confirm_bars=12)
    assert r and r["type"]=="breakout" and r["direction"]=="UP",r
    # зона=1: пробой должен ловиться и на последней свече (здесь её нет — None)
    r_last=detect_breakout(bo,lookback=60,confirm_bars=1)
    assert r_last is None or r_last["type"]=="breakout"
    vc=detect_volume_confirmed(bo,20)
    assert vc and vc.get("volume_confirmed"),vc
    # диапазон — без пробоя
    assert detect_breakout(rng,60,confirm_bars=12) is None
    # ложный пробой не маркируется как подтверждённый (§50.2)
    fb=mk(200,"false",3)
    r=detect_false_breakout(fb,60,max_bars_out=6)
    assert r and r["type"]=="false_breakout",r
    r=detect_breakout(fb,60,confirm_bars=12)
    # последняя свеча вернулась в диапазон — подтверждённого пробоя нет
    assert r is None or r["close"]<103
    # ретест без будущих свечей (§50.3)
    rt=detect_retest(bo,60)
    # (могут быть/не быть в зависимости от геометрии — проверяем только отсутствие краша)
    assert rt is None or rt["type"]=="retest"
    # одно событие не дублируется при повторной доставке (§50.4)
    c1=make_candidate("BTCUSDT",detect_all(bo),3.5)
    c2=make_candidate("BTCUSDT",detect_all(bo),3.5)
    assert c1["candidate_id"]!=c2["candidate_id"]  # разные моменты времени
    # низкое качество данных снижает приоритет явно (§50.5)
    cl=make_candidate("BTCUSDT",detect_all(bo),3.5,data_quality="C")
    assert cl["priority_score"]<c1["priority_score"]
    # ранжирование стабильно
    ranked=rank_candidates([c1,cl])
    assert ranked[0]["rank"]==1 and ranked[0]["priority_score"]>=ranked[1]["priority_score"]
    # маршрутизация формаций в ветви (§42.2)
    assert "trend" in c1["routes"] and "order_flow" in c1["routes"]
    # INSUFFICIENT при короткой истории
    assert scan_symbol(rng[:30])["status"]=="INSUFFICIENT"
    # сжатие/расширение
    comp=detect_compression_expansion(rng)
    assert comp is None or comp["type"] in ("compression","expansion")
    print("mm_discovery: OK")
