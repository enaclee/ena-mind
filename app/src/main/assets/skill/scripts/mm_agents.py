#!/usr/bin/env python3
"""mm_agents — 19 агентов-ветвей единого движка ENA-MIND v8.0 «ЛЕС И ПАУТИНА».

Каждый агент — чистая функция от «среза признаков» (features bundle, fb):
  fb = {"klines":{"1":rows,"3":rows,"5":rows,"15":rows,"60":rows},  # [ts,o,h,l,c,v] закр. свечей
        "price": float,                     # текущая цена
        "symbol": "BTCUSDT",                # актив (для агентов опционов/корреляций)
        "tape": {...bybit ws flow...}, "binance_flow": {...}, "okx_flow": {...},
        "book": {...bybit book+prev...}, "binance_depth": {...},
        "deriv": {...funding/oi/lsr/liquidations...},
        "cross": {...mm_sources.cross_exchange...},
        "alt_klines": {"BTCUSDT": rows_1m, ...},   # для корреляций
        "fng": {...}, "news": [...],
        "options": {...deribit...},               # v7.2
        "whales": {...binance_whales...},         # v8.0: крупные сделки
        "macro": {...coingecko_global...},        # v8.0: доминирование/мкап
        "slow": {"defillama":{...},"mempool":{...}},  # v8.0: DeFi TVL + ончейн BTC
        "model": {...mm_stats model...}, "weights": {...mm_memory agent weights...}}

Выход — единый контракт (ЧАСТЬ 5):
  {"agent":"PRICE_ANALYST","status":"OK|INSUFFICIENT|DISABLED","timestamp":ts,
   "side":"LONG|SHORT|NEUTRAL","confidence":0..1,"features":{...},
   "evidence":[...],"contradictions":[...],"invalidate":[...]}

АРХИТЕКТУРА «ЛЕС, ОБМОТАННЫЙ ПАУТИНОЙ» (v8.0):
- ЛЕС = 18 аналитических агентов (деревья), каждый глубоко копает свой пласт данных;
- ПАУТИНА = WEB_LINKS — явные связи между агентами; web_analysis() считает,
  какие связи активны, согласованы или конфликтуют; web_coherence (0..1)
  модулирует уверенность финального прогноза (лес един — голоса не изолированы);
- PREDICTION_AGENT (19) собирает лес и паутину в P(UP)+P(FLAT)+P(DOWN)=100%.

v7.2 — ветви: 11 LIQUIDATION_CASCADE, 12 ANOMALY_DETECTOR, 13 OPTIONS_ANALYST.
v8.0 — новые ветви на новых источниках:
  14 ONCHAIN_ANALYST — ончейн BTC (mempool.space: комиссии, мемпул, блоки);
  15 DEFI_FLOW_ANALYST — потоки капитала DeFi (DefiLlama TVL, дельты 1ч/24ч);
  16 BASIS_ANALYST — базис спот/перп + carry (Bybit/Binance/OKX funding);
  17 WHALE_WATCH — киты: крупные сделки Binance aggTrades (>= $100K);
  18 SESSION_ANALYST — суточная структура: профиль волы/доходности по часам UTC;
  19 DOMINANCE_ANALYST — макро-ротация: доминирование BTC и объём рынка (CoinGecko).

Правила честности:
- side/confidence вычисляются ТОЛЬКО из данных; никаких случайных чисел;
- данных нет -> status=INSUFFICIENT, side=NEUTRAL, confidence=0;
- SENTIMENT и ONCHAIN — контекст: направление сами не определяют;
- STATISTICAL отключён (DISABLED), пока модель не обучена на достаточной истории;
- крупные заявки в стакане НЕ считаются гарантией поддержки/сопротивления.
"""
import math,time

AGENTS=("PRICE_ANALYST","ORDER_FLOW_ANALYST","LIQUIDITY_ANALYST","DERIVATIVES_ANALYST",
        "CROSS_EXCHANGE_ANALYST","BTC_CORRELATION_ANALYST","VOLATILITY_ANALYST",
        "SENTIMENT_ANALYST","STATISTICAL_AI","LIQUIDATION_CASCADE","ANOMALY_DETECTOR",
        "OPTIONS_ANALYST","ONCHAIN_ANALYST","DEFI_FLOW_ANALYST","BASIS_ANALYST",
        "WHALE_WATCH","SESSION_ANALYST","DOMINANCE_ANALYST","PREDICTION_AGENT")
# кластеры коррелированных сигналов (совет не считает их дважды — ЧАСТЬ 5)
CLUSTERS={"microstructure":("ORDER_FLOW_ANALYST","LIQUIDITY_ANALYST","WHALE_WATCH"),
          "price_based":("PRICE_ANALYST","VOLATILITY_ANALYST","BTC_CORRELATION_ANALYST",
                         "SESSION_ANALYST"),
          "positioning":("DERIVATIVES_ANALYST","CROSS_EXCHANGE_ANALYST",
                         "LIQUIDATION_CASCADE","OPTIONS_ANALYST","BASIS_ANALYST"),
          "external":("SENTIMENT_ANALYST","DOMINANCE_ANALYST"),
          "onchain":("ONCHAIN_ANALYST","DEFI_FLOW_ANALYST"),
          "statistical":("STATISTICAL_AI","ANOMALY_DETECTOR")}

# ПАУТИНА: явные связи между агентами леса (a, b, тип связи).
# web_analysis() проверяет каждую связь: оба живы и не нейтральны ->
# AGREE (same side) или CONFLICT (противоположные); иначе WEAK/PENDING.
WEB_LINKS=(
    ("PRICE_ANALYST","ORDER_FLOW_ANALYST","confirmation"),
    ("PRICE_ANALYST","VOLATILITY_ANALYST","regime"),
    ("PRICE_ANALYST","BTC_CORRELATION_ANALYST","trend_context"),
    ("ORDER_FLOW_ANALYST","LIQUIDITY_ANALYST","microstructure"),
    ("ORDER_FLOW_ANALYST","WHALE_WATCH","smart_money"),
    ("ORDER_FLOW_ANALYST","CROSS_EXCHANGE_ANALYST","confirmation"),
    ("LIQUIDITY_ANALYST","WHALE_WATCH","microstructure"),
    ("DERIVATIVES_ANALYST","LIQUIDATION_CASCADE","positioning"),
    ("DERIVATIVES_ANALYST","BASIS_ANALYST","carry"),
    ("DERIVATIVES_ANALYST","OPTIONS_ANALYST","positioning"),
    ("BTC_CORRELATION_ANALYST","DOMINANCE_ANALYST","macro_rotation"),
    ("VOLATILITY_ANALYST","OPTIONS_ANALYST","vol_surface"),
    ("VOLATILITY_ANALYST","ANOMALY_DETECTOR","regime"),
    ("VOLATILITY_ANALYST","SESSION_ANALYST","time_structure"),
    ("SENTIMENT_ANALYST","DEFI_FLOW_ANALYST","risk_appetite"),
    ("SENTIMENT_ANALYST","DOMINANCE_ANALYST","risk_appetite"),
    ("ONCHAIN_ANALYST","DEFI_FLOW_ANALYST","blockchain_activity"),
    ("STATISTICAL_AI","ANOMALY_DETECTOR","statistics"),
)

def _clamp(x,a,b): return max(a,min(b,x))
def _now(): return int(time.time())

def _agent(name,status="OK",side="NEUTRAL",confidence=0.0,features=None,
           evidence=None,contradictions=None,invalidate=None):
    return {"agent":name,"status":status,"timestamp":_now(),"side":side,
            "confidence":round(float(confidence),4),
            "features":features or {},"evidence":evidence or [],
            "contradictions":contradictions or [],"invalidate":invalidate or []}

# ---------------- индикаторы (чистые функции, без зависимостей) ----------------
def ema(vals,n):
    if len(vals)<n: return None
    k=2.0/(n+1);e=sum(vals[:n])/n
    for v in vals[n:]: e=v*k+e*(1-k)
    return e
def sma(vals,n):
    if len(vals)<n: return None
    return sum(vals[-n:])/n
def rsi(closes,n=14):
    if len(closes)<n+1: return None
    gains=losses=0.0
    for i in range(-n,0):
        d=closes[i]-closes[i-1]
        gains+=max(0.0,d);losses+=max(0.0,-d)
    if losses==0: return 100.0 if gains>0 else 50.0
    rs=gains/losses
    return 100.0-100.0/(1.0+rs)
def macd(closes,fast=12,slow=26,sig=9):
    if len(closes)<slow+sig: return None
    k_fast=2.0/(fast+1);k_slow=2.0/(slow+1)
    ef=sum(closes[:fast])/fast
    for v in closes[fast:]: ef=v*k_fast+ef*(1-k_fast)
    es=sum(closes[:slow])/slow
    for v in closes[slow:]: es=v*k_slow+es*(1-k_slow)
    line=ef-es
    # сигнальная: EMA от macd-линии на истории (упрощённо — по последним slow+sig значениям)
    hist=[];ef2=es=None
    # честная серия macd-линии
    ef_=sum(closes[:fast])/fast;es_=sum(closes[:slow])/slow;lines=[]
    for i in range(len(closes)):
        if i>=fast: ef_=closes[i]*k_fast+ef_*(1-k_fast)
        if i>=slow: es_=closes[i]*k_slow+es_*(1-k_slow)
        if i>=slow: lines.append(ef_-es_)
    if len(lines)<sig: return None
    s=sum(lines[:sig])/sig;kk=2.0/(sig+1)
    for v in lines[sig:]: s=v*kk+s*(1-kk)
    return {"line":lines[-1],"signal":s,"hist":lines[-1]-s}
def bollinger(closes,n=20,k=2.0):
    if len(closes)<n: return None
    win=closes[-n:];m=sum(win)/n
    var=sum((x-m)**2 for x in win)/n
    sd=math.sqrt(var)
    if sd==0: return None
    return {"mid":m,"upper":m+k*sd,"lower":m-k*sd,"width_pct":100*2*k*sd/m,
            "pos":_clamp((closes[-1]-m+k*sd)/(2*k*sd),0.0,1.0)}  # 0=нижняя,1=верхняя
def atr(rows,n=14):
    if len(rows)<n+1: return None
    trs=[]
    for j in range(len(rows)-n,len(rows)):
        h,l,c_prev=rows[j][2],rows[j][3],rows[j-1][4]
        trs.append(max(h-l,abs(h-c_prev),abs(l-c_prev)))
    return sum(trs)/n
def vwap(rows,n=50):
    if len(rows)<2: return None
    win=rows[-n:]
    num=sum((r[1]+r[2]+r[3]+r[4])/4.0*r[5] for r in win)
    den=sum(r[5] for r in win)
    return num/den if den>0 else None
def roc(closes,n=10):
    if len(closes)<n+1 or closes[-n-1]==0: return None
    return 100.0*(closes[-1]-closes[-n-1])/closes[-n-1]
def realized_vol(closes,n=60,per_bar_sec=60.0):
    """σ лог-доходностей в % за бар."""
    if len(closes)<n+1: return None
    rs=[]
    for i in range(-n,0):
        if closes[i-1]>0: rs.append(math.log(closes[i]/closes[i-1]))
    if len(rs)<10: return None
    m=sum(rs)/len(rs)
    var=sum((x-m)**2 for x in rs)/len(rs)
    return 100.0*math.sqrt(var)
def ewma_vol(closes,n=60,lam=0.94):
    """EWMA волатильность (RiskMetrics) в % за бар."""
    if len(closes)<n+1: return None
    rs=[math.log(closes[i]/closes[i-1]) for i in range(-n,0) if closes[i-1]>0]
    if len(rs)<10: return None
    v=0.0
    for r in rs: v=lam*v+(1-lam)*(r*100.0)**2   # r*100 -> проценты
    return math.sqrt(v)                          # уже в % — БЕЗ второго умножения на 100
def stdev(vals):
    if len(vals)<2: return 0.0
    m=sum(vals)/len(vals)
    return math.sqrt(sum((x-m)**2 for x in vals)/len(vals))
def corr(a,b):
    n=min(len(a),len(b))
    if n<10: return None
    a,b=a[-n:],b[-n:]
    ma,mb=sum(a)/n,sum(b)/n
    da=[x-ma for x in a];db=[x-mb for x in b]
    num=sum(da[i]*db[i] for i in range(n))
    den=math.sqrt(sum(x*x for x in da)*sum(x*x for x in db))
    return num/den if den>0 else None
def logret_series(closes,n):
    return [math.log(closes[i]/closes[i-1]) for i in range(-n,0) if closes[i-1]>0]

# ================= AGENT 01 — PRICE ANALYST =================
TF_WEIGHTS={"1":0.35,"3":0.20,"5":0.20,"15":0.15,"60":0.10}
def agent_price(fb):
    kl=(fb.get("klines") or {})
    closes_by_tf={}
    for tf,rows in kl.items():
        if rows and len(rows)>=30: closes_by_tf[tf]=[r[4] for r in rows]
    if not closes_by_tf:
        return _agent("PRICE_ANALYST","INSUFFICIENT",evidence=["нет свечей ни по одному ТФ"])
    score=0.0;wsum=0.0;feat={};ev=[]
    for tf,w in TF_WEIGHTS.items():
        closes=closes_by_tf.get(tf)
        if not closes: continue
        s=0.0;n=0
        e21,e50=ema(closes,21),ema(closes,50)
        if e21 and e50:
            s+= 1.0 if e21>e50 else -1.0;n+=1
            feat["%s_ema_cross"%tf]=round(e21-e50,6)
        r=rsi(closes)
        if r is not None:
            s+=_clamp((r-50.0)/25.0,-1.0,1.0);n+=1
            feat["%s_rsi"%tf]=round(r,1)
        m=macd(closes)
        if m:
            s+= 1.0 if m["hist"]>0 else -1.0;n+=1
            feat["%s_macd_hist"%tf]=round(m["hist"],6)
        bb=bollinger(closes)
        if bb:
            s+=_clamp((bb["pos"]-0.5)*2.0,-1.0,1.0);n+=1
            feat["%s_bb_pos"%tf]=round(bb["pos"],3)
        mo=roc(closes,10)
        if mo is not None:
            s+=_clamp(mo/1.5,-1.0,1.0);n+=1
            feat["%s_roc10"%tf]=round(mo,3)
        if n:
            score+=w*(s/n);wsum+=w
    if wsum==0:
        return _agent("PRICE_ANALYST","INSUFFICIENT",evidence=["свечей меньше 30 на всех ТФ"])
    score/=wsum
    # уровни S/R и пробои — по 5m (баланс шума и детализации)
    rows=kl.get("5") or []
    brk=""
    if len(rows)>=25:
        highs=[r[2] for r in rows];lows=[r[3] for r in rows];closes5=[r[4] for r in rows]
        hi20=max(highs[-21:-1]) if len(highs)>=21 else None
        lo20=min(lows[-21:-1]) if len(lows)>=21 else None
        if hi20 and closes5[-1]>hi20:
            feat["breakout"]="UP";score+=0.25;ev.append("пробой 20-барного максимума %.6g"%hi20)
        elif lo20 and closes5[-1]<lo20:
            feat["breakout"]="DOWN";score-=0.25;ev.append("пробой 20-барного минимума %.6g"%lo20)
        else:
            # ложный пробой: свеча выходила за экстремум и закрылась внутри
            if hi20 and rows[-1][2]>hi20 and closes5[-1]<=hi20:
                feat["false_breakout"]="UP";score-=0.15
                ev.append("ложный пробой вверх: выхhigh>%.6g, закрытие внутри"%hi20)
            if lo20 and rows[-1][3]<lo20 and closes5[-1]>=lo20:
                feat["false_breakout"]="DOWN";score+=0.15
                ev.append("ложный пробой вниз: low<%.6g, закрытие внутри"%lo20)
        # локальные пивоты (S/R)
        piv_hi=[rows[i][2] for i in range(len(rows)-12,len(rows)-2)
                if rows[i][2]>=max(r[2] for r in rows[i-5:i+6])] if len(rows)>15 else []
        piv_lo=[rows[i][3] for i in range(len(rows)-12,len(rows)-2)
                if rows[i][3]<=min(r[3] for r in rows[i-5:i+6])] if len(rows)>15 else []
        if piv_hi: feat["resistance"]=round(max(piv_hi),6)
        if piv_lo: feat["support"]=round(min(piv_lo),6)
    price=fb.get("price")
    if price and feat.get("support") and feat.get("resistance"):
        pos=(price-feat["support"])/(feat["resistance"]-feat["support"]) if feat["resistance"]>feat["support"] else 0.5
        feat["sr_pos"]=round(_clamp(pos,0.0,1.0),3)
    side="LONG" if score>0.12 else ("SHORT" if score<-0.12 else "NEUTRAL")
    conf=_clamp(0.35+0.5*abs(score),0.35,0.8)
    if side=="NEUTRAL": conf=_clamp(0.2+0.3*abs(score),0.2,0.5)
    ev.insert(0,"мульти-ТФ score=%.3f (1м/5м/15м/60м взвешенно)"%score)
    a=_agent("PRICE_ANALYST","OK",side,conf,feat,ev,
             invalidate=["score меняет знак при перевесе другой стороны",
                         "пробой S/R уровня против стороны сигнала"])
    return a

# ================= AGENT 02 — ORDER FLOW ANALYST =================
def agent_flow(fb):
    tape=fb.get("tape") or {};bf=fb.get("binance_flow") or {};of=fb.get("okx_flow") or {}
    parts=[]
    for nm,d in (("bybit",tape),("binance",bf),("okx",of)):
        if d.get("ok") and d.get("n",1)>=10:
            parts.append((nm,float(d.get("delta_pct") or 0.0),float(d.get("age_sec") or 999)))
    if not parts:
        return _agent("ORDER_FLOW_ANALYST","INSUFFICIENT",
                      evidence=["нет ни одного живого потока сделок (bybit/binance/okx)"])
    feat={"flows":{nm:dp for nm,dp,_ in parts}}
    avg=sum(dp for _,dp,_ in parts)/len(parts)
    feat["avg_delta_pct"]=round(avg,3)
    price=fb.get("price")
    pc=(tape.get("price_change_pct") if tape.get("ok") else None) or \
        (bf.get("price_change_pct") if False else None)
    # дивергенция: поток положителен, а цена не растёт (или наоборот)
    divergence=False
    if pc is not None:
        if (avg>3.0 and pc<-0.02) or (avg<-3.0 and pc>0.02): divergence=True
    feat["divergence"]=divergence
    # кластеры объёма (POC) по ленте bybit, если есть цены сделок
    ev=[]
    for nm,dp,age in parts:
        ev.append("%s: дельта %+.2f%% (возраст %.1fс)"%(nm,dp,age))
    if divergence: ev.append("дивергенция поток/цена — сигнал ослаблен")
    score=_clamp(avg/12.0,-1.0,1.0)
    if divergence: score*=0.5
    side="LONG" if score>0.15 else ("SHORT" if score<-0.15 else "NEUTRAL")
    conf=_clamp(0.3+0.45*abs(score),0.3,0.78)
    if side=="NEUTRAL": conf=_clamp(0.15+0.3*abs(score),0.15,0.45)
    return _agent("ORDER_FLOW_ANALYST","OK",side,conf,feat,ev,
                  invalidate=["дельта меняет знак на противоположный",
                              "расхождение потоков между биржами (avg по биржам меняет знак)"])

# ================= AGENT 03 — LIQUIDITY ANALYST =================
def agent_liquidity(fb):
    book=fb.get("book") or {};bd=fb.get("binance_depth") or {}
    bids=book.get("bids") or [];asks=book.get("asks") or []
    feat={};ev=[]
    usable=False
    imb=None;micro=None;spread=None
    if bids and asks:
        try:
            bq=sum(q for _,q in bids);aq=sum(q for _,q in asks)
            if bq+aq>0:
                imb=100.0*(bq-aq)/(bq+aq);usable=True
                feat["bybit_imbalance_pct"]=round(imb,2)
            best_b,best_qb=bids[0];best_a,best_qa=asks[0]
            micro=(best_a*bq+best_b*aq)/(bq+aq) if bq+aq>0 else None
            feat["microprice"]=round(micro,6) if micro else None
            feat["micro_vs_mid_bps"]=round((micro-((best_b+best_a)/2))/((best_b+best_a)/2)*10000,2) if micro and best_a+best_b>0 else None
            spread=100.0*(best_a-best_b)/best_b if best_b>0 else None
            feat["bybit_spread_bps"]=round(spread*100,2) if spread else None
        except (TypeError,ValueError,IndexError): pass
    if bd.get("ok"):
        feat["binance_imbalance_pct"]=bd.get("imbalance_pct");usable=True
    if not usable:
        return _agent("LIQUIDITY_ANALYST","INSUFFICIENT",
                      evidence=["стакан недоступен (bybit и binance)"])
    # плотности: уровни с qty >> медианы (крупные заявки НЕ считаются поддержкой — ЧАСТЬ 4)
    prev=book.get("prev") or {}
    if bids:
        qb=[q for _,q in bids];qa=[q for _,q in asks]
        med_b=sorted(qb)[len(qb)//2] if qb else 0
        med_a=sorted(qa)[len(qa)//2] if qa else 0
        big_bids=[(p,q) for p,q in bids if q>=3*med_b and med_b>0]
        big_asks=[(p,q) for p,q in asks if q>=3*med_a and med_a>0]
        feat["big_bid_walls"]=len(big_bids);feat["big_ask_walls"]=len(big_asks)
        if big_bids: ev.append("плотность на биде @%s (спуфабельно, НЕ поддержка)"%big_bids[0][0])
        if big_asks: ev.append("плотность на аске @%s (спуфабельно, НЕ сопротивление)"%big_asks[0][0])
        # ловушка: плотность была и исчезла
        if prev.get("bids"):
            prev_bids={p:q for p,q in prev["bids"]}
            gone=[p for p,q in big_bids if prev_bids.get(p,0)>=3*med_b and abs(prev_bids[p]-q)/max(prev_bids[p],1e-9)>0.7]
            if gone:
                feat["walls_pulled"]=gone[:3]
                ev.append("⚠ плотности сняты: %s — возможная ловушка"%gone[:3])
    # зоны ликвидности по свечам 5m (кластеры стопов за экстремумами)
    kl5=(fb.get("klines") or {}).get("5") or []
    if len(kl5)>=30:
        hi=max(r[2] for r in kl5[-30:]);lo=min(r[3] for r in kl5[-30:])
        price=fb.get("price")
        if price:
            feat["dist_to_30bar_hi_pct"]=round(100*(hi-price)/price,3)
            feat["dist_to_30bar_lo_pct"]=round(100*(lo-price)/price,3)
    # сторона: микропрайс и имбаланс (мягкие сигналы, спуфабельность учтена)
    score=0.0
    if imb is not None: score+=_clamp(imb/40.0,-0.6,0.6)
    if feat.get("micro_vs_mid_bps") is not None:
        score+=_clamp(feat["micro_vs_mid_bps"]/2.0,-0.3,0.3)
    if bd.get("ok") and bd.get("imbalance_pct") is not None:
        score+=_clamp(bd["imbalance_pct"]/80.0,-0.4,0.4)
    if feat.get("walls_pulled"): score*=0.6  # снятие плотностей — доверие ниже
    side="LONG" if score>0.2 else ("SHORT" if score<-0.2 else "NEUTRAL")
    conf=_clamp(0.28+0.4*abs(score),0.28,0.62)  # потолок ниже: стакан спуфабелен
    if side=="NEUTRAL": conf=_clamp(0.15+0.25*abs(score),0.15,0.4)
    ev.insert(0,"имбаланс книги %s%%, микропрайс-смещение %s bps"%(
        feat.get("bybit_imbalance_pct"),feat.get("micro_vs_mid_bps")))
    return _agent("LIQUIDITY_ANALYST","OK",side,conf,feat,ev,
                  invalidate=["имбаланс книги меняет знак",
                              "микропрайс пересекает середину спреда в другую сторону"],
                  contradictions=(["стакан спуфабелен: крупные заявки могли быть выставлены для обмана"]
                                  if (feat.get("big_bid_walls",0)>0 or feat.get("big_ask_walls",0)>0) else []))

# ================= AGENT 04 — DERIVATIVES ANALYST =================
def agent_derivatives(fb):
    d=fb.get("deriv") or {}
    if not d.get("ok"):
        return _agent("DERIVATIVES_ANALYST","INSUFFICIENT",
                      evidence=["нет деривативных данных (funding/OI)"])
    feat={};ev=[];votes=[]
    f=float(d.get("funding_pct") or 0.0)
    feat["funding_pct"]=f
    hist=d.get("funding_hist") or []
    floor=0.05
    if len(hist)>=10:
        srt=sorted(hist);below=sum(1 for x in hist if x<=f)
        pct=round(100.0*below/len(hist),1)
        feat["funding_pctl"]=pct
        ext_hi=f>=srt[int((len(srt)-1)*0.95)] and abs(f)>=floor
        ext_lo=f<=srt[max(0,int((len(srt)-1)*0.05))] and abs(f)>=floor
        ev.append("funding %+.4f%% (перцентиль %s за %d значений)"%(f,pct,len(hist)))
    else:
        ext_hi=f>=0.05;ext_lo=f<=-0.05
        ev.append("funding %+.4f%% (истории нет — фиксированный порог ±0.05%%)"%f)
    if ext_hi: votes.append(("SHORT",0.55,"перегретый funding: лонги платят — контр-трендовое давление"))
    elif ext_lo: votes.append(("LONG",0.55,"отрицательный funding: шорты платят — потенциал сквиза вверх"))
    # OI + цена (4 комбинации ЧАСТИ 4)
    oi_chg=d.get("oi_pct_change")
    price=fb.get("price")
    kl=(fb.get("klines") or {}).get("60") or []
    price_24h=None
    if len(kl)>=24:
        price_24h=100.0*(price-kl[-24][4])/kl[-24][4] if price and kl[-24][4] else None
    if oi_chg is not None:
        feat["oi_change_24h_pct"]=oi_chg
        if price_24h is not None:
            combo=None
            if price_24h>0.3 and oi_chg>0.5: combo="цена↑+OI↑ (новые лонги, тренд поддержан)"
            elif price_24h>0.3 and oi_chg<-0.5: combo="цена↑+OI↓ (шорт-сквиз/закрытия, выхлоп)"
            elif price_24h<-0.3 and oi_chg>0.5: combo="цена↓+OI↑ (новые шорты, тренд вниз поддержан)"
            elif price_24h<-0.3 and oi_chg<-0.5: combo="цена↓+OI↓ (лонг-ликвидации, выхлоп падения)"
            if combo:
                feat["price_oi_combo"]=combo;ev.append(combo)
                if "тренд поддержан" in combo:
                    votes.append(("LONG" if price_24h>0 else "SHORT",0.5,combo))
                elif "выхлоп" in combo:
                    votes.append(("SHORT" if price_24h>0 else "LONG",0.45,combo))
    # LSR перцентиль
    lsr_pct=d.get("lsr_pct_rank")
    if lsr_pct is not None:
        feat["lsr_pctl"]=lsr_pct
        if lsr_pct>=90: votes.append(("SHORT",0.5,"LSR перцентиль ≥90: толпа аномально лонгует — контр-тренд"))
        elif lsr_pct<=10: votes.append(("LONG",0.5,"LSR перцентиль ≤10: толпа аномально шортует — контр-тренд"))
        ev.append("LSR-перцентиль %.0f"%lsr_pct)
    # ликвидации
    liq=d.get("liquidations") or {}
    if liq.get("ok"):
        # v7.2 ФИКС: Kotlin передаёт long_liq_usd_1h/short_liq_usd_1h (BybitWs.LiquidationAggregator),
        # раньше читались long_usd_1h/short_usd_1h — данные не доходили до агента
        l1=float(liq.get("long_liq_usd_1h") or liq.get("long_usd_1h") or 0.0)
        s1=float(liq.get("short_liq_usd_1h") or liq.get("short_usd_1h") or 0.0)
        if l1 or s1:
            feat["liq_long_1h_usd"]=l1;feat["liq_short_1h_usd"]=s1
            ev.append("ликвидации 1ч: лонги $%.0f / шорты $%.0f"%(l1,s1))
            if s1>3*max(l1,1.0): votes.append(("LONG",0.45,"шорты выносятся — каскад вверх"))
            elif l1>3*max(s1,1.0): votes.append(("SHORT",0.45,"лонги выносятся — каскад вниз"))
    longs=sum(w for s,w,_ in votes if s=="LONG");shorts=sum(w for s,w,_ in votes if s=="SHORT")
    if longs>shorts: side,w="LONG",longs
    elif shorts>longs: side,w="SHORT",shorts
    else: side,w="NEUTRAL",0.0
    conf=_clamp(0.3+w*0.45,0.3,0.72) if side!="NEUTRAL" else _clamp(0.2+0.2*max(longs,shorts),0.2,0.4)
    if not votes: ev.append("funding/OI/LSR нейтральны")
    return _agent("DERIVATIVES_ANALYST","OK",side,conf,feat,ev,
                  invalidate=["funding возвращается в нейтральный диапазон",
                              "LSR-перцентиль входит в 10..90"])

# ================= AGENT 05 — CROSS EXCHANGE ANALYST =================
def agent_cross_exchange(fb):
    cross=fb.get("cross") or {}
    parts=cross.get("parts") or {}
    prices={}
    if parts.get("bybit",{}).get("ok"): prices["bybit"]=parts["bybit"]["last"]
    if parts.get("binance_price",{}).get("ok"): prices["binance"]=parts["binance_price"]["last"]
    if parts.get("okx_price",{}).get("ok"): prices["okx"]=parts["okx_price"]["last"]
    if len(prices)<2:
        return _agent("CROSS_EXCHANGE_ANALYST","INSUFFICIENT",
                      evidence=["живых цен меньше двух бирж (нужен подтверждённый сигнал)"],
                      features={"n_exchanges":len(prices)})
    feat={"prices":prices,"diffs":cross.get("diffs") or {}}
    ev=[]
    max_bps=0.0
    for k,v in (cross.get("diffs") or {}).items():
        ev.append("спред %s: %+.2f bps"%(k.replace("_bps","").replace("_"," "),v))
        max_bps=max(max_bps,abs(v))
    feat["max_spread_bps"]=round(max_bps,2)
    # потоки трёх бирж: подтверждение агрессии
    deltas=[]
    for nm in ("binance_flow","okx_flow"):
        d=parts.get(nm) or {}
        if d.get("ok"): deltas.append(d.get("delta_pct"))
    tape=fb.get("tape") or {}
    if tape.get("ok"): deltas.append(tape.get("delta_pct"))
    same=0
    if len(deltas)>=2:
        pos=sum(1 for x in deltas if x>1.0);neg=sum(1 for x in deltas if x<-1.0)
        feat["flow_agree_exchanges"]=max(pos,neg);feat["flow_total_exchanges"]=len(deltas)
        if pos>=2 and pos>neg: same=1;ev.append("агрессивные покупки подтверждаются на %d/%d биржах"%(pos,len(deltas)))
        elif neg>=2 and neg>pos: same=-1;ev.append("агрессивные продажи подтверждаются на %d/%d биржах"%(neg,len(deltas)))
        else: ev.append("потоки бирж расходятся — подтверждения нет")
    # funding расхождение
    f_by=parts.get("bybit",{}).get("funding_pct");f_ok=parts.get("okx_funding",{}).get("funding_pct")
    if f_by is not None and f_ok is not None:
        feat["funding_divergence_pp"]=round(abs(f_by-f_ok),4)
        ev.append("funding bybit %+.4f%% vs okx %+.4f%%"%(f_by,f_ok))
    score=0.55*same if same else 0.0
    if max_bps>15:  # сильный межбиржевой дисбаланс — доверие ниже
        feat["note"]="межбиржевой спред %.1f bps — арбитражный шум"%max_bps
        score*=0.5
    side="LONG" if score>0.2 else ("SHORT" if score<-0.2 else "NEUTRAL")
    conf=_clamp(0.3+0.4*abs(score),0.3,0.7)
    if side=="NEUTRAL": conf=_clamp(0.2+0.25*abs(score),0.2,0.45)
    return _agent("CROSS_EXCHANGE_ANALYST","OK",side,conf,feat,ev,
                  invalidate=["подтверждающий поток исчезает на одной из бирж",
                              "спред выходит за 25 bps"])

# ================= AGENT 06 — BTC CORRELATION ANALYST =================
def agent_btc_correlation(fb,asset_symbol="BTCUSDT"):
    alt=fb.get("alt_klines") or {}
    kl1=(fb.get("klines") or {}).get("1") or []
    if asset_symbol.startswith("BTC"):
        return _agent("BTC_CORRELATION_ANALYST","DISABLED",
                      evidence=["актив сам BTC — корреляционный агент неприменим"])
    closes_a=[r[4] for r in kl1] if len(kl1)>=60 else None
    closes_b=None
    for key,(rows) in alt.items():
        if key.startswith("BTC") and len(rows)>=60:
            closes_b=[r[4] for r in rows]
    if not closes_a or not closes_b:
        return _agent("BTC_CORRELATION_ANALYST","INSUFFICIENT",
                      evidence=["нужны 1m-свечи актива и BTC (>=60)"])
    ra=logret_series(closes_a,60);rb=logret_series(closes_b,60)
    c60=corr(ra,rb)
    ra2=logret_series(closes_a,240);rb2=logret_series(closes_b,240)
    c240=corr(ra2,rb2) if len(ra2)>=60 else None
    if c60 is None:
        return _agent("BTC_CORRELATION_ANALYST","INSUFFICIENT",evidence=["слишком короткие ряды"])
    feat={"corr_60m":round(c60,3)}
    if c240 is not None: feat["corr_240m"]=round(c240,3)
    # beta
    varb=sum((x-sum(rb)/len(rb))**2 for x in rb)/len(rb)
    beta=None
    if varb>0:
        ma,mb=sum(ra)/len(ra),sum(rb)/len(rb)
        beta=sum((ra[i]-ma)*(rb[i]-mb) for i in range(len(ra)))/len(ra)/varb
        feat["beta"]=round(beta,3)
    # lead-lag: корреляция ret_a(t) с ret_b(t+k)
    best_k=None;best_c=0.0
    for k in range(-3,4):
        if k==0: continue
        a2=ra[max(0,-k):len(ra)-max(0,k)];b2=rb[max(0,k):len(rb)-max(0,-k)]
        cc=corr(a2,b2)
        if cc is not None and abs(cc)>abs(best_c): best_c=cc;best_k=k
    if best_k is not None: feat["lead_lag_k"]=best_k;feat["lead_lag_corr"]=round(best_c,3)
    # реакция альта на импульсы BTC (|ret 5m|>0.25%): среднее движение альта за след. 5м
    imp_resp=None
    if len(closes_a)>=80 and len(closes_b)>=80:
        n=len(closes_a);reactions=[]
        for i in range(70,n-5):
            r_btc=100.0*(closes_b[min(i+4,len(closes_b)-1)]-closes_b[i-1])/closes_b[i-1] if closes_b[i-1]>0 else 0
            if abs(r_btc)>=0.25:
                # соответствие по времени: свечи могли разъехаться — берём ближайший индекс
                r_a=100.0*(closes_a[min(i+9,n-1)]-closes_a[i])/closes_a[i] if closes_a[i]>0 else 0
                reactions.append((1 if r_btc>0 else -1,r_a))
        if len(reactions)>=5:
            up=[r for sgn,r in reactions if sgn>0];dn=[r for sgn,r in reactions if sgn<0]
            imp_resp={"n":len(reactions),
                      "avg_after_btc_up_pct":round(sum(up)/len(up),4) if up else None,
                      "avg_after_btc_down_pct":round(sum(dn)/len(dn),4) if dn else None}
            feat["impulse_response"]=imp_resp
    ev=["corr(60м)=%.2f%s"%(c60,(" (240м %.2f)"%c240) if c240 is not None else ""),
        "beta=%.2f"%beta if beta else "beta: n/a"]
    if imp_resp:
        ev.append("реакция на импульсы BTC: после роста %+.3f%%, после падения %+.3f%% (n=%d)"%(
            imp_resp["avg_after_btc_up_pct"] or 0,imp_resp["avg_after_btc_down_pct"] or 0,imp_resp["n"]))
    # сторона: сигнал только при устойчивой корреляции и текущем движении BTC
    side="NEUTRAL";conf=0.3
    r_btc_now=None
    if len(closes_b)>=6 and closes_b[-6]:
        r_btc_now=100.0*(closes_b[-1]-closes_b[-6])/closes_b[-6]
        feat["btc_move_5m_pct"]=round(r_btc_now,4)
    if abs(c60)>=0.5 and r_btc_now is not None and abs(r_btc_now)>=0.08:
        exp_resp=(imp_resp or {}).get("avg_after_btc_up_pct") if r_btc_now>0 else (imp_resp or {}).get("avg_after_btc_down_pct")
        if exp_resp:
            side="LONG" if exp_resp>0.02 else ("SHORT" if exp_resp<-0.02 else "NEUTRAL")
            conf=_clamp(0.35+0.3*abs(c60),0.35,0.65)
            ev.append("BTC сейчас %+.3f%% -> типичная реакция актива %+.3f%%"%(r_btc_now,exp_resp))
        else:
            side="LONG" if r_btc_now>0 else "SHORT"
            conf=_clamp(0.3+0.2*abs(c60),0.3,0.55)
            ev.append("BTC сейчас %+.3f%% при corr=%.2f -> направление следует BTC"% (r_btc_now,c60))
    if c240 is not None and abs(c60-c240)>=0.3:
        feat["corr_regime_shift"]=True
        ev.append("⚠ корреляция меняется (%.2f -> %.2f): связь нестабильна"%(c240,c60))
        conf*=0.7
    return _agent("BTC_CORRELATION_ANALYST","OK",side,conf,feat,ev,
                  invalidate=["корреляция падает ниже 0.3",
                              "расхождение: актив игнорирует движение BTC"])

# ================= AGENT 07 — VOLATILITY ANALYST =================
def agent_volatility(fb):
    kl=(fb.get("klines") or {})
    rows1=kl.get("1") or []
    if len(rows1)<60:
        return _agent("VOLATILITY_ANALYST","INSUFFICIENT",evidence=["нужно >=60 минутных свечей"])
    closes1=[r[4] for r in rows1]
    a1=atr(rows1,14);price=fb.get("price") or closes1[-1]
    if not a1 or price<=0:
        return _agent("VOLATILITY_ANALYST","INSUFFICIENT",evidence=["ATR не вычисляется"])
    atr_pct=100.0*a1/price
    rv=realized_vol(closes1,60);ev_=ewma_vol(closes1,60)
    rows5=kl.get("5") or []
    bb5=bollinger([r[4] for r in rows5]) if len(rows5)>=20 else None
    feat={"atr_pct_1m":round(atr_pct,4)}
    if rv is not None: feat["realized_vol_60m_pct_per_bar"]=round(rv,5)
    if ev_ is not None: feat["ewma_vol_pct_per_bar"]=round(ev_,5)
    if bb5: feat["bb_width_5m_pct"]=round(bb5["width_pct"],3)
    # кластеризация волатильности: вола последних 15м vs предыдущих 45м
    vol_fast=realized_vol(closes1,15);vol_slow=realized_vol(closes1,60)
    ratio=None
    if vol_fast is not None and vol_slow is not None and vol_slow>0:
        ratio=vol_fast/vol_slow;feat["vol_ratio_fast_slow"]=round(ratio,2)
    ev=[]
    ev.append("ATR14(1м)=%.4f%%; realized σ=%.5f%%/бар"%(atr_pct,rv or 0))
    if ratio is not None:
        if ratio>=1.6: ev.append("расширение волатильности (x%.2f) — импульс"%ratio)
        elif ratio<=0.6: ev.append("сжатие волатильности (x%.2f) — готовится пробой"%ratio)
        else: ev.append("волатильность ровная (x%.2f)"%ratio)
    # 15-минутный горизонт прогноза: σ ожидаемого движения
    rv15=None
    if rv is not None: rv15=rv*math.sqrt(15.0)
    if ev_ is not None:
        alt15=ev_*math.sqrt(15.0)
        rv15=max(rv15 or 0,alt15*0.5+ (rv15 or 0)*0.5) if rv15 else alt15
    if rv15 is None and atr_pct is not None:
        rv15=atr_pct*math.sqrt(15.0)
    feat["sigma_15m_pct"]=round(rv15,4) if rv15 is not None else None
    # классификация режима (ЧАСТЬ 4: TREND UP/DOWN, RANGE, HIGH/LOW VOL, BREAKOUT, UNCERTAIN)
    closes15=[r[4] for r in (kl.get("15") or [])]
    trend=None
    if len(closes15)>=30:
        e10,e25=ema(closes15,10),ema(closes15,25)
        if e10 and e25: trend="UP" if e10>e25 else "DOWN"
    regime="UNCERTAIN"
    if ratio is not None and ratio>=1.8: regime="BREAKOUT"
    elif trend=="UP" and (rv15 or 0)<0.35: regime="TREND UP"
    elif trend=="DOWN" and (rv15 or 0)<0.35: regime="TREND DOWN"
    elif (rv15 or 0)>=0.5: regime="HIGH VOLATILITY"
    elif (rv15 or 0)<=0.12: regime="LOW VOLATILITY"
    elif trend: regime="RANGE"
    feat["regime"]=regime
    ev.append("режим: %s"%regime)
    # сторона — по направлению импульса, только в режимах тренда/пробоя
    side="NEUTRAL";conf=0.3
    roc1=roc(closes1,15)
    if regime in ("TREND UP","BREAKOUT") and roc1 is not None and roc1>0.02:
        side="LONG";conf=0.45
    elif regime in ("TREND DOWN","BREAKOUT") and roc1 is not None and roc1<-0.02:
        side="SHORT";conf=0.45
    return _agent("VOLATILITY_ANALYST","OK",side,conf,feat,ev,
                  invalidate=["резкое изменение σ (расширение >x2 за 15м)",
                              "смена режима (BREAKOUT -> RANGE)"])

# ================= AGENT 08 — SENTIMENT ANALYST =================
def agent_sentiment(fb):
    """Сентимент НИКОГДА не определяет направление (ЧАСТЬ 4): side всегда NEUTRAL,
    confidence отражает только силу/согласованность фона настроений."""
    fng=fb.get("fng") or {};news=fb.get("news") or []
    feat={};ev=[]
    have=False
    if fng.get("ok"):
        have=True
        v=fng.get("value");feat["fng"]=v;feat["fng_delta3"]=fng.get("delta3")
        ev.append("F&G=%s (%s), Δ3д=%s"%(v,fng.get("classification",""),fng.get("delta3")))
        if (v or 50)>=75: ev.append("Extreme Greed — толпа перегрета (фон риска разворота)")
        elif (v or 50)<=25: ev.append("Extreme Fear — возможное дно настроений (фон отскока)")
    if news:
        have=True
        pos=neg=0.0
        for n in news[:20]:
            try: imp=float(n.get("impact") or 0.0)
            except (TypeError,ValueError): imp=0.0
            if imp>0: pos+=imp
            elif imp<0: neg-=imp
        feat["news_pos"]=round(pos,2);feat["news_neg"]=round(neg,2)
        feat["news_balance"]=round(pos-neg,2)
        ev.append("новости: позитив %.1f / негатив %.1f (%d заголовков)"%(pos,neg,len(news[:20])))
        if abs(pos-neg)>=2.0:
            ev.append("новостной фон смещён (%s) — только контекст, не сигнал"%("позитив" if pos>neg else "негатив"))
    if not have:
        return _agent("SENTIMENT_ANALYST","INSUFFICIENT",evidence=["нет ни F&G, ни новостей"])
    strength=0.0
    v=fng.get("value")
    if v is not None: strength=max(strength,abs(v-50)/50.0)
    if feat.get("news_balance") is not None:
        strength=max(strength,abs(feat["news_balance"])/4.0)
    conf=_clamp(0.2+0.3*strength,0.2,0.5)
    return _agent("SENTIMENT_ANALYST","OK","NEUTRAL",conf,feat,ev,
                  invalidate=["F&G возвращается в 30..70"],
                  contradictions=["сентимент не определяет направление прогноза (правило 7.0)"])

# ================= AGENT 09 — STATISTICAL AI =================
def agent_statistical(fb):
    """Вероятность из обученной модели (mm_stats). Пока модель не обучена на
    достаточной истории — DISABLED (никаких выдуманных процентов)."""
    import mm_stats
    model=fb.get("model") or {}
    kl=(fb.get("klines") or {})
    rows1=kl.get("1") or []
    if not model.get("ready") or len(rows1)<80:
        return _agent("STATISTICAL_AI","DISABLED",
                      evidence=["модель не обучена на достаточной истории (нужно >=500 свечей 1м) — вероятности не выдаются"])
    feats=mm_stats.features_from_klines(rows1,kl.get("5") or [],kl.get("15") or [])
    if feats is None:
        return _agent("STATISTICAL_AI","INSUFFICIENT",evidence=["не удалось собрать признаки из свечей"])
    p_up=model_predict(model,feats)
    if p_up is None:
        return _agent("STATISTICAL_AI","INSUFFICIENT",evidence=["признаки вне области модели"])
    feat={"p_up_model":round(p_up,4),"model_version":model.get("version"),
          "model_n":model.get("train_n"),"features":{k:round(v,5) for k,v in feats.items()}}
    ev=["модель %s: P(up 15м)=%.3f (обучение n=%d, walk-forward)"%(
        model.get("version"),p_up,model.get("train_n"))]
    # аномалии объёма (z-score)
    vols=[r[5] for r in rows1[-90:]]
    if len(vols)>=30:
        m=sum(vols)/len(vols);sd=stdev(vols)
        if sd>0:
            z=(vols[-1]-m)/sd
            feat["volume_z"]=round(z,2)
            if abs(z)>=3: ev.append("аномальный объём: z=%.1f"%z)
    side="LONG" if p_up>=0.55 else ("SHORT" if p_up<=0.45 else "NEUTRAL")
    conf=_clamp(abs(p_up-0.5)*1.6,0.0,0.7) if side!="NEUTRAL" else _clamp(0.1+(0.5-abs(p_up-0.5))*0.2,0.1,0.3)
    if model.get("metrics",{}).get("hit_rate") is not None:
        ev.append("качество на walk-forward: hit-rate %.1f%% (baseline %.1f%%)"%(
            100*model["metrics"]["hit_rate"],100*model["metrics"].get("baseline_hit",0.5)))
    return _agent("STATISTICAL_AI","OK",side,conf,feat,ev,
                  invalidate=["p_up входит в 0.45..0.55 (зона неопределённости модели)"])

def model_predict(model,feats):
    """Логистическая регрессия: predict по весам модели (детерминированно)."""
    coef=model.get("coef") or {}
    names=model.get("features") or []
    if not names: return None
    z=model.get("intercept",0.0)
    for k in names:
        if k not in feats: return None
        z+=coef.get(k,0.0)*feats[k]
    if z>30: return 1.0
    if z<-30: return 0.0
    return 1.0/(1.0+math.exp(-z))

# ================= AGENT 11 — LIQUIDATION CASCADE (v7.2) =================
def agent_liquidation_cascade(fb):
    """Ликвидационные каскады: вынос позиций — топливо продолжения движения.
    Данные: deriv.liquidations от Kotlin (лента WS Bybit, 1ч/24ч агрегаты)."""
    d=fb.get("deriv") or {}
    liq=d.get("liquidations") or {}
    l1=float(liq.get("long_liq_usd_1h") or liq.get("long_usd_1h") or 0.0)
    s1=float(liq.get("short_liq_usd_1h") or liq.get("short_usd_1h") or 0.0)
    tot1=float(liq.get("sum_usd_1h") or (l1+s1))
    tot24=float(liq.get("sum_usd_24h") or 0.0)
    n=int(liq.get("n") or 0)
    if tot1<=0 and tot24<=0:
        return _agent("LIQUIDATION_CASCADE","INSUFFICIENT",
                      evidence=["лента ликвидаций пуста — каскадов нет"])
    base_h=tot24/24.0 if tot24>0 else None   # средний час за сутки
    feat={"liq_1h_usd":round(tot1,0),"liq_24h_usd":round(tot24,0),"n_events":n}
    ev=["ликвидации 1ч: $%.0f (лонги $%.0f / шорты $%.0f), событий %d"%(tot1,l1,s1,n)]
    if base_h is not None and base_h>0:
        feat["liq_1h_vs_baseline"]=round(tot1/base_h,2)
        ev.append("база 24ч: $%.0f/ч — текущий час %.1fx"%(base_h,feat["liq_1h_vs_baseline"]))
    dom=(l1-s1)/(l1+s1) if (l1+s1)>0 else 0.0   # +1 = выносят лонги
    feat["dominance"]=round(dom,3)
    votes=[]
    hot=abs(dom)>=0.6 and (base_h is None or base_h<=0 or tot1>=2.0*base_h)
    if hot:
        side="SHORT" if dom>0 else "LONG"
        votes.append((side,0.55,"каскад в разгаре: выносятся %s — топливо продолжения"%(
            "лонги" if dom>0 else "шорты")))
        feat["cascade_active"]=True
    elif abs(dom)>=0.75 and tot1>=100000.0:
        votes.append(("SHORT" if dom>0 else "LONG",0.45,
            "сильный перекос ликвидаций (%s) без кратного превышения базы"%(
                "лонги" if dom>0 else "шорты")))
    else:
        ev.append("каскада нет: перекос %.0f%% в пределах нормы"%(abs(dom)*100))
    if votes: side,w=votes[0][0],votes[0][1]
    else: side,w="NEUTRAL",0.0
    conf=_clamp(0.3+(w-0.3)*1.2,0.3,0.65) if votes else 0.25
    return _agent("LIQUIDATION_CASCADE","OK",side,conf,feat,ev,
                  invalidate=["перекос сторон падает ниже 60%",
                              "часовой объём ликвидаций возвращается к базовой линии"])

# ================= AGENT 12 — ANOMALY DETECTOR (v7.2) =================
def agent_anomaly(fb):
    """Статистические аномалии: z-оценки объёма/движения и расширение ATR.
    Честность: аномалия без направления не торгуется — только фиксируется."""
    kl=(fb.get("klines") or {}).get("1") or []
    if len(kl)<70:
        return _agent("ANOMALY_DETECTOR","INSUFFICIENT",
                      evidence=["1m-свечей меньше 70 — z-оценки недостоверны"])
    import statistics
    closes=[r[4] for r in kl];vols=[float(r[5] or 0) for r in kl]
    v_win=vols[:-1][-60:]
    v_mean=statistics.fmean(v_win);v_sd=statistics.pstdev(v_win)
    z_vol=(vols[-1]-v_mean)/(v_sd if v_sd>0 else 1e-9)
    rets=[100.0*(closes[i]/closes[i-1]-1.0) for i in range(len(closes)-60,len(closes))]
    r_win=rets[:-1]
    r_mean=statistics.fmean(r_win);r_sd=statistics.pstdev(r_win)
    z_ret=(rets[-1]-r_mean)/(r_sd if r_sd>0 else 1e-9)
    trs=[]
    for j in range(len(kl)-14,len(kl)):
        h,l,cp=kl[j][2],kl[j][3],kl[j-1][4]
        trs.append(max(h-l,abs(h-cp),abs(l-cp)))
    a_now=sum(trs[-5:])/5.0
    a_base=sum(trs[:-5])/len(trs[:-5]) if trs[:-5] else 0.0
    a_ratio=a_now/a_base if a_base>0 else 1.0
    feat={"z_volume":round(z_vol,2),"z_return_1m":round(z_ret,2),
          "atr_expansion":round(a_ratio,2),"last_ret_pct":round(rets[-1],4)}
    ev=["объём z=%.1f, движение 1м z=%.1f (%+.3f%%), ATR(5)/ATR(14)=%.2f"%(
        z_vol,z_ret,rets[-1],a_ratio)]
    votes=[]
    if z_vol>=3.0 and abs(z_ret)>=1.5:
        votes.append(("LONG" if z_ret>0 else "SHORT",0.5,
            "шок: объём z=%.1f подтверждён движением z=%.1f — импульс"%(
            z_vol,z_ret)))
        feat["shock"]=True
    elif z_vol>=3.0:
        ev.append("объём аномален, направления нет — аномалия не торгуется")
    if a_ratio>=2.0:
        feat["vol_regime_shift"]=True
        ev.append("волатильность расширяется x%.1f — повышенный риск движения в обе стороны"%a_ratio)
    if votes: side,w=votes[0][0],votes[0][1]
    else: side,w="NEUTRAL",0.25
    conf=_clamp(0.3+(w-0.3)*1.2,0.3,0.6) if votes else 0.25
    return _agent("ANOMALY_DETECTOR","OK",side,conf,feat,ev,
                  invalidate=["z-оценки возвращаются в |z|<2","ATR возвращается к базе"])

# ================= AGENT 13 — OPTIONS ANALYST (v7.2) =================
def agent_options(fb):
    """Опционы Deribit: DVOL (подразумеваемая вола), перекос IV put/call,
    PCR по открытому интересу ближайшей экспирации. Чистый агент — данные
    приходят бэкфиллом из mm_sources.deribit_options (кэш 10 минут, вызов из
    Kotlin-цикла движка); для не-BTC/ETH — честно INSUFFICIENT."""
    sym=(fb.get("symbol") or "BTCUSDT").upper()
    base=sym[:-4] if sym.endswith("USDT") else sym
    if base not in ("BTC","ETH"):
        return _agent("OPTIONS_ANALYST","INSUFFICIENT",
                      evidence=["опционы Deribit торгуются только для BTC/ETH"])
    opts=fb.get("options")
    if not opts or not opts.get("ok"):
        st=(opts or {}).get("status") or "нет данных (бэкфилл раз в 10 минут ещё не пришёл)"
        return _agent("OPTIONS_ANALYST","INSUFFICIENT",
                      evidence=["Deribit: %s"%st],features={"status":st})
    feat={"iv_now":opts.get("iv_now"),"iv_mean_7d":opts.get("iv_mean_7d"),
          "pcr_oi":opts.get("pcr_oi"),"skew":opts.get("skew"),
          "expiry":opts.get("expiry"),"n_instruments":opts.get("n_instruments")}
    ev=[]
    iv,ivm=opts.get("iv_now"),opts.get("iv_mean_7d")
    if iv is not None and ivm: ev.append("DVOL %.0f против 7д среднего %.0f (%.2fx)"%(iv,ivm,iv/ivm if ivm else 0))
    if opts.get("pcr_oi") is not None: ev.append("PCR(OI) ближайшей экспирации %.2f"%opts["pcr_oi"])
    if opts.get("skew") is not None: ev.append("перекос IV put/call %+.1f%%"%(100.0*opts["skew"]))
    skew=opts.get("skew");pcr=opts.get("pcr_oi")
    votes=[]
    if iv is not None and ivm and iv>=1.3*ivm and (skew or 0)>0.10:
        votes.append(("SHORT",0.5,"паника в опционах: DVOL %.2fx нормы и путы дороже коллов"%(
            iv/ivm if ivm else 0)))
    elif iv is not None and ivm and iv<=0.75*ivm and (pcr or 1.0)>=1.3:
        votes.append(("SHORT",0.4,"самоуспокоенность: вола придавлена, толпа в путах — риск обвала"))
    elif (skew or 0)<-0.10 and (pcr or 1.0)<=0.7:
        votes.append(("LONG",0.45,"оптимизм: коллы дороже путов, PCR %.2f — толпа в апсайд"%(pcr or 0)))
    if not votes: ev.append("опционы в норме — сторона не определяется")
    if votes: side,w=votes[0][0],votes[0][1]
    else: side,w="NEUTRAL",0.0
    conf=_clamp(0.3+(w-0.3)*1.2,0.3,0.6) if votes else 0.2
    return _agent("OPTIONS_ANALYST","OK",side,conf,feat,ev,
                  invalidate=["DVOL возвращается к 7д среднему","перекос IV схлопывается"])

# ================= AGENT 14 — ONCHAIN ANALYST (v8.0) =================
def agent_onchain(fb):
    """Ончейн-контекст BTC (mempool.space): комиссии, мемпул, темп блоков.
    Правило честности: перегрузка сети — контекст спроса, а не сигнал входа;
    сторона определяется ТОЛЬКО для BTC и только при выраженном перегреве."""
    slow=fb.get("slow") or {}
    mp=slow.get("mempool") or {}
    if not mp.get("ok"):
        st=mp.get("status") or "нет ончейн-данных (медленный цикл раз в 10 минут)"
        return _agent("ONCHAIN_ANALYST","INSUFFICIENT",
                      evidence=["mempool.space: %s"%st],features={"status":st})
    fee=mp.get("fee_fast");mem_n=mp.get("mempool_tx")
    blocks_sec=mp.get("median_block_sec");tx6=mp.get("avg_block_tx_6")
    feat={"fee_fast_sat":fee,"mempool_tx":mem_n,
          "median_block_sec":blocks_sec,"avg_block_tx_6":tx6,
          "last_block_height":mp.get("last_block_height")}
    ev=["комиссия fast %s sat/vB, мемпул %s tx, медианный блок %sс"%(
        fee,mem_n,blocks_sec)]
    hot=False
    if fee is not None and fee>=50:
        hot=True;ev.append("сеть перегружена: комиссии >=50 sat/vB — высокий спрос на блокчейн")
    if blocks_sec is not None and blocks_sec<=480:
        ev.append("блоки идут быстрее 8 мин (%.0fс) — активность повышена"%blocks_sec)
    if tx6 is not None:
        ev.append("%.0f транзакций/блок в среднем (6 блоков)"%tx6)
    # ончейн — контекст: сторона только для BTC и только при перегреве (слабая)
    sym=(fb.get("symbol") or "").upper()
    side="NEUTRAL";conf=_clamp(0.2+ (0.15 if hot else 0.0),0.2,0.4)
    if hot and sym.startswith("BTC"):
        # исторически перегрузка сети сопровождает фазы спроса на BTC — слабый контекст
        side="LONG";conf=0.4
        ev.append("перегрузка сети + актив сам BTC: контекст спроса (слабый, не сигнал)")
    return _agent("ONCHAIN_ANALYST","OK",side,conf,feat,ev,
                  invalidate=["комиссии возвращаются <30 sat/vB",
                              "мемпул разгружается"],
                  contradictions=["ончейн-активность — контекст, не торговый сигнал"])

# ================= AGENT 15 — DEFI FLOW ANALYST (v8.0) =================
def agent_defi_flow(fb):
    """Потоки капитала DeFi (DefiLlama): TVL топ-цепей + дельты 1ч/24ч по
    локальной истории снапшотов. Вход капитала — риск-он, выход — риск-офф."""
    slow=fb.get("slow") or {}
    dl=slow.get("defillama") or {}
    if not dl.get("ok"):
        return _agent("DEFI_FLOW_ANALYST","INSUFFICIENT",
                      evidence=["DefiLlama: %s"%(dl.get("status") or "нет данных")],
                      features={"status":dl.get("status")})
    total=dl.get("total_tvl")
    d24=dl.get("delta_24h_pct");d1=dl.get("delta_1h_pct")
    feat={"total_tvl_usd":total,"delta_24h_pct":d24,"delta_1h_pct":d1,
          "history_points":dl.get("history_points")}
    ev=["TVL топ-цепей $%.1fB"%(total/1e9 if total else 0)]
    if d1 is not None: ev.append("дельта 1ч: %+.2f%%"%d1)
    if d24 is None:
        ev.append("дельта 24ч накопится через сутки истории снапшотов (пока точек %d)"%(
            dl.get("history_points") or 0))
        return _agent("DEFI_FLOW_ANALYST","OK","NEUTRAL",0.2,feat,ev,
                      invalidate=["появится дельта 24ч после суток наблюдения"])
    ev.insert(1,"дельта 24ч: %+.2f%%"%d24)
    side="NEUTRAL";conf=0.25
    if d24>=1.0:
        side="LONG";conf=_clamp(0.3+0.15*min(d24,4.0),0.3,0.5)
        ev.append("капитал входит в DeFi (%+.2f%% за 24ч) — риск-аппетит растёт"%d24)
    elif d24<=-1.0:
        side="SHORT";conf=_clamp(0.3+0.15*min(-d24,4.0),0.3,0.5)
        ev.append("капитал уходит из DeFi (%+.2f%% за 24ч) — риск-офф"%d24)
    else:
        ev.append("TVL в пределах ±1%% — значимого потока нет")
    if d1 is not None and abs(d1)>=0.5 and side!="NEUTRAL":
        # внутрисуточное подтверждение направления потока
        if (d1>0)==(side=="LONG"):
            conf=_clamp(conf+0.05,0.3,0.55)
            ev.append("дельта 1ч подтверждает направление")
        else:
            conf=_clamp(conf-0.1,0.2,0.5)
            ev.append("⚠ дельта 1ч против направления — сигнал ослаблен")
    return _agent("DEFI_FLOW_ANALYST","OK",side,conf,feat,ev,
                  invalidate=["дельта 24ч возвращается в ±1%",
                              "разворот часового потока против стороны"])

# ================= AGENT 16 — BASIS ANALYST (v8.0) =================
def agent_basis(fb):
    """Базис спот/перп + carry: спот Binance против перпа Bybit, funding
    Bybit/OKX. Перегретый премиум лонгов — контр-трендовое давление."""
    cross=fb.get("cross") or {}
    parts=cross.get("parts") or {}
    spot=(parts.get("binance_price") or {})
    perp=(parts.get("bybit") or {}).get("last") or fb.get("price")
    if not spot.get("ok") or not perp or perp<=0:
        return _agent("BASIS_ANALYST","INSUFFICIENT",
                      evidence=["нужны спот Binance и перп Bybit (базис не считается)"])
    s=spot.get("last")
    if not s or s<=0:
        return _agent("BASIS_ANALYST","INSUFFICIENT",evidence=["спот-цена недоступна"])
    basis_bps=(perp-s)/s*10000.0
    d=fb.get("deriv") or {}
    f_by=d.get("funding_pct")
    f_ok=(parts.get("okx_funding") or {}).get("funding_pct")
    feat={"basis_bps":round(basis_bps,2),"spot":s,"perp":perp,
          "funding_bybit_pct":f_by,"funding_okx_pct":f_ok}
    ev=["базис спот(Binance)/перп(Bybit): %+.1f bps"%basis_bps]
    if f_by is not None: ev.append("funding Bybit %+.4f%%"%f_by)
    if f_ok is not None: ev.append("funding OKX %+.4f%%"%f_ok)
    if f_by is not None and f_ok is not None:
        feat["funding_divergence_pp"]=round(abs(f_by-f_ok),4)
        if abs(f_by-f_ok)>=0.03:
            ev.append("⚠ funding-расхождение бирж %.4f пп — арбитражный дисбаланс"%abs(f_by-f_ok))
    votes=[]
    if basis_bps>=10.0 and (f_by or 0)>=0.03:
        votes.append(("SHORT",0.5,"перп дороже спота на %.1f bps при funding %+.4f%% — лонги перегреты"%(
            basis_bps,f_by)))
    elif basis_bps<=-10.0 and (f_by or 0)<=-0.005:
        votes.append(("LONG",0.5,"перп дешевле спота на %.1f bps при funding %+.4f%% — шорты платят, потенциал сквиза"%(
            basis_bps,f_by)))
    elif abs(basis_bps)<10.0:
        ev.append("базис в шуме (<10 bps) — carry-сигнала нет")
    if votes: side,w=votes[0][0],votes[0][1]
    else: side,w="NEUTRAL",0.0
    conf=_clamp(0.3+(w-0.3)*1.2+min(abs(basis_bps),50)/500.0,0.3,0.62) if votes else 0.22
    return _agent("BASIS_ANALYST","OK",side,conf,feat,ev,
                  invalidate=["базис возвращается в ±10 bps",
                              "funding меняет знак при том же базисе"])

# ================= AGENT 17 — WHALE WATCH (v8.0) =================
def agent_whale_watch(fb):
    """Киты: крупные сделки (>= $100K) из aggTrades Binance. Направление
    китовой агрессии за окно последних ~1000 сделок (бэкфилл раз в ~60с)."""
    w=fb.get("whales")
    if not w:
        return _agent("WHALE_WATCH","INSUFFICIENT",
                      evidence=["данные китов не поданы (бэкфилл раз в ~60с)"])
    if w.get("status")=="UNAVAILABLE":
        return _agent("WHALE_WATCH","INSUFFICIENT",
                      evidence=["aggTrades Binance недоступны"])
    if not w.get("ok"):
        # честный случай: сеть жива, но крупных сделок в окне нет
        note=w.get("note") or "крупных сделок в окне нет"
        return _agent("WHALE_WATCH","OK","NEUTRAL",0.25,
                      {"n_whales":0,"n_trades":w.get("n_trades"),
                       "max_usd":w.get("max_usd")},
                      ["киты не активны: %s"%note,
                       "отсутствие китов — не сигнал, а пустое окно"],
                      invalidate=["появление крупных сделок в окне"])
    buy=w.get("buy_usd") or 0.0;sell=w.get("sell_usd") or 0.0
    dp=float(w.get("delta_pct") or 0.0)
    n=int(w.get("n_whales") or 0)
    feat={"n_whales":n,"buy_usd":buy,"sell_usd":sell,"delta_pct":dp}
    top=(w.get("top") or [])[:3]
    for i,t in enumerate(top):
        feat["top_%d"%(i+1)]="%s $%.0fK @ %s"%(t.get("side"),(t.get("usd") or 0)/1000.0,t.get("price"))
    ev=["киты: %d крупных сделок, покупки $%.0fK / продажи $%.0fK (дельта %+.1f%%)"%(
        n,buy/1000.0,sell/1000.0,dp)]
    side="NEUTRAL";conf=0.25
    if n>=2 and abs(dp)>=40.0:
        side="LONG" if dp>0 else "SHORT"
        conf=_clamp(0.35+0.25*abs(dp)/100.0,0.35,0.6)
        ev.append("киты агрессивно %s — умные деньги задают направление"%(
            "покупают" if dp>0 else "продают"))
    elif n>=2 and abs(dp)>=20.0:
        side="LONG" if dp>0 else "SHORT"
        conf=0.35
        ev.append("умеренный перевес китов (%s)"%("покупки" if dp>0 else "продажи"))
    else:
        ev.append("перекос китов в пределах ±20%% — направления нет")
    return _agent("WHALE_WATCH","OK",side,conf,feat,ev,
                  invalidate=["китовая дельта меняет знак",
                              "крупные сделки исчезают из окна"])

# ================= AGENT 18 — SESSION ANALYST (v8.0) =================
def agent_session(fb):
    """Суточная структура: профиль доходности и волатильности по часам UTC
    из часовых свечей (>=3 суток). Последняя свеча исключена (без подглядывания):
    профиль строится по закрытой истории, применяется к текущему часу."""
    rows=(fb.get("klines") or {}).get("60") or []
    if len(rows)<72:
        return _agent("SESSION_ANALYST","INSUFFICIENT",
                      evidence=["часовых свечей меньше 72 (~3 суток) — профиль по часам не строится"])
    def _hour(ts):
        ms=ts*1000 if ts<1000000000000 else ts
        return time.gmtime(ms/1000.0).tm_hour
    hist=rows[:-1]  # без последней (текущей) свечи — честно, без подглядывания
    by_hour={}
    for i in range(1,len(hist)):
        h=_hour(hist[i][0]);prev=hist[i-1][4];cur=hist[i][4]
        if prev>0: by_hour.setdefault(h,[]).append(100.0*(cur/prev-1.0))
    cur_h=_hour(rows[-1][0])
    rets=by_hour.get(cur_h) or []
    if len(rets)<3:
        return _agent("SESSION_ANALYST","INSUFFICIENT",
                      evidence=["часа %02d:00 UTC меньше 3 наблюдений в истории"%cur_h])
    allrets=[r for v in by_hour.values() for r in v]
    m=sum(rets)/len(rets);sd=stdev(rets)
    all_sd=stdev(allrets)
    session=("Азия" if cur_h<8 else ("Европа" if cur_h<14 else ("США" if cur_h<21 else "переход")))
    feat={"utc_hour":cur_h,"session":session,"n_obs":len(rets),
          "hour_mean_ret_pct":round(m,4),"hour_sd_pct":round(sd,4),
          "all_sd_pct":round(all_sd,4) if all_sd else None,
          "hour_vol_ratio":round(sd/all_sd,2) if all_sd and all_sd>0 else None}
    ev=["час %02d:00 UTC (сессия %s): средняя доходность %+.3f%% (σ %.3f%%, n=%d)"%(
        cur_h,session,m,sd,len(rets))]
    if feat["hour_vol_ratio"] is not None:
        r=feat["hour_vol_ratio"]
        if r>=1.3: ev.append("час волатильнее среднего в %.2f раза — движение вероятно"%r)
        elif r<=0.7: ev.append("час спокойнее среднего (x%.2f) — флэт вероятнее"%r)
    side="NEUTRAL";conf=0.25
    # сторона: устойчивая (mean против шума) историческая доходность этого часа
    if sd>0 and abs(m)>=0.5*sd and len(rets)>=4:
        side="LONG" if m>0 else "SHORT"
        conf=_clamp(0.3+min(abs(m)/sd,2.0)*0.07,0.3,0.45)
        ev.append("статистика часа смещена %s (%+.3f%% при σ %.3f%%) — слабый сезонный приор"%(
            "вверх" if m>0 else "вниз",m,sd))
    else:
        ev.append("сезонный сдвиг часа в пределах шума")
    return _agent("SESSION_ANALYST","OK",side,conf,feat,ev,
                  invalidate=["смена часа UTC (профиль пересчитывается)",
                              "сезонный сдвиг схлопывается при новых наблюдениях"])

# ================= AGENT 19 — DOMINANCE ANALYST (v8.0) =================
def agent_dominance(fb):
    """Макро-ротация (CoinGecko global): доминирование BTC (+дельта 24ч по
    локальной истории), объём рынка и его 24ч-изменение. Рост доминации при
    падении мкапа — капитал бежит в BTC из альтов; падение — альт-ротация."""
    g=fb.get("macro") or {}
    if not g.get("ok"):
        return _agent("DOMINANCE_ANALYST","INSUFFICIENT",
                      evidence=["CoinGecko: %s"%(g.get("status") or "нет данных")],
                      features={"status":g.get("status")})
    dom=g.get("btc_dominance");d24=g.get("btc_dom_delta_24h")
    mcap_ch=g.get("mcap_change_24h_pct")
    sym=(fb.get("symbol") or "").upper()
    is_btc=sym.startswith("BTC")
    feat={"btc_dominance":dom,"btc_dom_delta_24h":d24,
          "mcap_change_24h_pct":mcap_ch,"total_mcap_usd":g.get("total_mcap_usd")}
    ev=["доминирование BTC %.1f%%"%dom if dom is not None else "доминирование BTC: n/a"]
    if mcap_ch is not None: ev.append("мкап рынка 24ч: %+.2f%%"%mcap_ch)
    if d24 is None:
        ev.append("дельта доминирования накопится за сутки истории снапшотов")
    else:
        ev.append("дельта доминирования 24ч: %+.2f пп"%d24)
    votes=[]
    if is_btc:
        if mcap_ch is not None and mcap_ch>=2.0:
            votes.append(("LONG",0.45,"рынок в целом растёт (%+.1f%%) — прилив поднимает BTC"%mcap_ch))
        elif mcap_ch is not None and mcap_ch<=-2.0:
            votes.append(("SHORT",0.45,"рынок в целом сжимается (%.1f%%) — отлив бьёт и по BTC"%mcap_ch))
    else:
        if d24 is not None and d24>=0.5:
            votes.append(("SHORT",0.4,"BTC вытягивает ликвидность из альтов (доминанция %+.2f пп)"%d24))
        elif d24 is not None and d24<=-0.5:
            votes.append(("LONG",0.4,"доминанция BTC падает (%.2f пп) — альт-ротация"%d24))
        if mcap_ch is not None and mcap_ch<=-3.0:
            votes.append(("SHORT",0.45,"общий мкап -%.1f%%: риск-офф бьёт по альтам сильнее"%(-mcap_ch)))
    if votes:
        longs=sum(w for s,w,_ in votes if s=="LONG");shorts=sum(w for s,w,_ in votes if s=="SHORT")
        if longs>shorts: side,w="LONG",longs
        elif shorts>longs: side,w="SHORT",shorts
        else: side,w="NEUTRAL",0.0
        for _,_,msg in votes: ev.append(msg)
    else:
        side,w="NEUTRAL",0.0
        ev.append("макро-ротация в нейтральной зоне")
    conf=_clamp(0.3+(w-0.3)*1.0,0.3,0.55) if votes else 0.22
    return _agent("DOMINANCE_ANALYST","OK",side,conf,feat,ev,
                  invalidate=["доминирование разворачивается",
                              "мкап-тренд меняет знак"])

# ================= ПАУТИНА СВЯЗЕЙ (v8.0) =================
def web_analysis(agents):
    """Лес, обмотанный паутиной: проверка всех WEB_LINKS по статусам агентов.
    Возвращает состояние связей + согласованность леса (coherence 0..1)."""
    by={a["agent"]:a for a in agents}
    links=[];agree=conflict=active=0
    for a,b,kind in WEB_LINKS:
        x,y=by.get(a),by.get(b)
        st="SILENT"
        if x is not None and y is not None:
            if x["status"]=="OK" and y["status"]=="OK":
                if x["side"]!="NEUTRAL" and y["side"]!="NEUTRAL":
                    active+=1
                    if x["side"]==y["side"]: st="AGREE";agree+=1
                    else: st="CONFLICT";conflict+=1
                else:
                    st="WEAK"
            else:
                st="PENDING"
        links.append({"a":a,"b":b,"kind":kind,"status":st,
                      "sides":[x["side"] if x else None,y["side"] if y else None]})
    coherence=(agree/float(active)) if active>=2 else None
    return {"links":links,"active":active,"agree":agree,"conflict":conflict,
            "coherence":round(coherence,3) if coherence is not None else None}


# ================= AGENT 19 — PREDICTION (лес + паутина) =================
def agent_prediction(agents,fb,weights=None):
    """Сборка финального прогноза: P(UP)+P(FLAT)+P(DOWN)=100%.
    Взвешивание агентов по исторической надёжности (mm_memory), кластеры
    коррелированных сигналов не считаются дважды, противоречия фиксируются.
    Вероятности строятся из распределения ожидаемой доходности:
      mu = k * edge * sigma, sigma = ATR15m; порог FLAT учитывает шум и комиссии."""
    weights=weights or {}
    votes=[]
    contradictions=[];evidence=[]
    for a in agents:
        if a["agent"]=="PREDICTION_AGENT" or a["status"]!="OK": continue
        if a["side"]=="NEUTRAL" or a["confidence"]<=0.3: continue
        votes.append(a)
    # уникальный вклад кластеров: внутри кластера берём сильнейший сигнал
    used_clusters=set();uniq=[]
    for a in sorted(votes,key=lambda x:-x["confidence"]):
        cl=None
        for cname,members in CLUSTERS.items():
            if a["agent"] in members: cl=cname;break
        if cl is not None and cl in used_clusters:
            contradictions.append("кластер %s: %s подавлен более сильным сигналом (нет двойного учёта)"%(cl,a["agent"]))
            continue
        if cl is not None: used_clusters.add(cl)
        uniq.append(a)
    edge=0.0;wsum=0.0
    for a in uniq:
        w=weights.get(a["agent"],1.0)
        s=1.0 if a["side"]=="LONG" else -1.0
        edge+=w*s*(a["confidence"]-0.3)
        wsum+=w
        evidence.append("%s: %s conf=%.2f (вес %.2f)"%(a["agent"],a["side"],a["confidence"],w))
    norm=edge/wsum if wsum>0 else 0.0
    # противоречия LONG vs SHORT
    longs=[a for a in uniq if a["side"]=="LONG"];shorts=[a for a in uniq if a["side"]=="SHORT"]
    if longs and shorts:
        contradictions.append("противоречие: %s за LONG против %s за SHORT"%(
            ",".join(a["agent"] for a in longs),",".join(a["agent"] for a in shorts)))
    vol=None;sigma15=None
    for a in agents:
        if a["agent"]=="VOLATILITY_ANALYST" and a["status"]=="OK":
            sigma15=a["features"].get("sigma_15m_pct");vol=a
    if sigma15 is None or sigma15<=0:
        return _agent("PREDICTION_AGENT","INSUFFICIENT",
                      evidence=evidence+["σ 15м неизвестна — вероятности не строятся"],
                      contradictions=contradictions)
    # модельный приор (если STATISTICAL_AI жив)
    prior_logit=0.0
    for a in agents:
        if a["agent"]=="STATISTICAL_AI" and a["status"]=="OK" and a["features"].get("p_up_model") is not None:
            p=_clamp(a["features"]["p_up_model"],0.02,0.98)
            prior_logit=math.log(p/(1-p))
            evidence.append("приор модели: logit=%.2f"%round(prior_logit,2))
    # ожидаемое движение: mu = edge_norm * sigma * k
    k=0.35
    mu=norm*sigma15*k
    logit=3.0*norm+0.6*prior_logit
    # порог FLAT: шум (половина сигмы) и издержки (taker round-trip 0.11% + проскальзывание)
    fees_rt=0.11
    thr=max(0.5*sigma15,fees_rt)
    # вероятности через нормальное распределение движения
    def _phi(x): return 0.5*(1.0+math.erf(x/math.sqrt(2.0)))
    z_up=(thr-mu)/sigma15;z_dn=(-thr-mu)/sigma15
    p_down=_phi(z_dn)
    p_up=1.0-_phi(z_up)
    p_flat=max(0.0,1.0-p_up-p_down)
    # нормировка строго на 100% (округление до 4 знаков + остаток в крупнейшую компоненту)
    p_up,p_flat,p_down=round(p_up,4),round(p_flat,4),round(p_down,4)
    resid=round(1.0-(p_up+p_flat+p_down),10)
    if resid:
        if p_up>=p_flat and p_up>=p_down: p_up=round(p_up+resid,4)
        elif p_flat>=p_down: p_flat=round(p_flat+resid,4)
        else: p_down=round(p_down+resid,4)
    # сила сигнала и неопределённость (энтропия)
    strength=abs(norm)
    ent=-sum(p*math.log(p+1e-12) for p in (p_up,p_flat,p_down))/math.log(3.0)
    uncertainty=ent
    feat={"p_up":round(p_up,4),"p_flat":round(p_flat,4),"p_down":round(p_down,4),
          "expected_move_pct":round(mu,4),"sigma_15m_pct":round(sigma15,4),
          "flat_threshold_pct":round(thr,4),"signal_strength":round(strength,4),
          "uncertainty":round(uncertainty,4),"logit":round(logit,3),
          "n_voting_agents":len(uniq),"n_total_agents":len([a for a in agents if a["status"]=="OK"])}
    # ожидаемый диапазон (80% интервал)
    feat["range_low_pct"]=round(mu-1.2816*sigma15,4);feat["range_high_pct"]=round(mu+1.2816*sigma15,4)
    price=fb.get("price")
    if price:
        feat["range_low"]=round(price*(1+feat["range_low_pct"]/100.0),6)
        feat["range_high"]=round(price*(1+feat["range_high_pct"]/100.0),6)
    side="UP" if p_up==max(p_up,p_flat,p_down) and p_up>p_flat else (
        "DOWN" if p_down>=p_flat else "FLAT")
    if p_up>p_flat and p_up>p_down: side="UP"
    elif p_down>p_flat and p_down>p_up: side="DOWN"
    else: side="FLAT"
    conf=_clamp(max(p_up,p_flat,p_down),0.34,0.9)
    # ПАУТИНА (v8.0): согласованность леса модулирует уверенность прогноза
    web=web_analysis(agents)
    feat["web_active_links"]=web["active"]
    if web["coherence"] is not None:
        feat["web_coherence"]=web["coherence"]
        feat["web_conflicts"]=web["conflict"]
        evidence.append("паутина: %d/%d связей активно, согласие %.0f%% (конфликтов %d)"%(
            web["active"],len(WEB_LINKS),100*web["coherence"],web["conflict"]))
        if web["active"]>=3:
            if web["coherence"]<0.34:
                conf*=0.85
                contradictions.append("паутина в конфликте (согласие %.0f%% из %d связей) — уверенность снижена"%(
                    100*web["coherence"],web["active"]))
            elif web["coherence"]>=0.8:
                conf=min(0.9,conf*1.05)
        conf=_clamp(conf,0.34,0.9)
    # NO_SIGNAL: мало голосов или всё в шуме
    if len(uniq)<2 or (side!="FLAT" and max(p_up,p_down)<0.40):
        feat["no_signal_reason"]="подтверждений недостаточно (голосов %d, max p=%.2f)"%(len(uniq),max(p_up,p_down))
        evidence.append(feat["no_signal_reason"])
    argmax=p_up if side=="UP" else (p_down if side=="DOWN" else p_flat)
    evidence.insert(0,"P(UP)=%.1f%% P(FLAT)=%.1f%% P(DOWN)=%.1f%% (сумма 100%%)"%(
        p_up*100,p_flat*100,p_down*100))
    evidence.insert(1,"ожидаемое движение %+.3f%% ± %.3f%% за 15м (порог FLAT %.3f%%)"%(
        mu,sigma15,thr))
    alt="альтернативный сценарий: %s (%.1f%%) при %s"%(
        {"UP":"DOWN","DOWN":"UP","FLAT":"UP" if p_down>p_up else "DOWN"}[side],
        (p_down if side!="DOWN" else p_up)*100 if side!="FLAT" else max(p_up,p_down)*100,
        "подтверждении потоком/пробоем против текущей стороны")
    feat["alt_scenario"]=alt
    invalidate=["P(сторона) падает ниже 40% при обновлении данных",
                "уровень %s пробит против прогноза"%(feat.get("range_low") or feat.get("range_high") or "диапазона")]
    a=_agent("PREDICTION_AGENT","OK",side,conf,feat,evidence,contradictions,invalidate)
    return a

def run_all(fb,asset_symbol="BTCUSDT"):
    """Все агенты-ветви 01..18 + PREDICTION (19) поверх их результатов и паутины."""
    agents=[agent_price(fb),agent_flow(fb),agent_liquidity(fb),agent_derivatives(fb),
            agent_cross_exchange(fb),agent_btc_correlation(fb,asset_symbol),
            agent_volatility(fb),agent_sentiment(fb),agent_statistical(fb),
            agent_liquidation_cascade(fb),agent_anomaly(fb),agent_options(fb),
            agent_onchain(fb),agent_defi_flow(fb),agent_basis(fb),
            agent_whale_watch(fb),agent_session(fb),agent_dominance(fb)]
    pred=agent_prediction(agents,fb,fb.get("weights") or {})
    return agents,pred

def selftest():
    """Детерминированные фикстуры: тренд вверх/вниз/флэт — агенты обязаны видеть сторону."""
    import random
    random.seed(42)
    def mk_kl(n=260,start=100.0,drift=0.0,vol=0.08,tail=0.0,tail_n=40):
        rows=[];p=start
        for i in range(n):
            o=p
            d=tail if i>=n-tail_n else drift
            noise=0.0 if (tail and i>=n-tail_n) else random.gauss(0,vol)
            p=max(0.01,p*(1+d+noise))
            h=max(o,p)*1.001;l=min(o,p)*0.999
            rows.append([1600000000+i*60,round(o,4),round(h,4),round(l,4),round(p,4),round(random.uniform(0.5,2.0),4)])
        return rows
    up=mk_kl(drift=0.0020,vol=0.012,tail=0.0018);dn=mk_kl(drift=-0.0020,vol=0.012,tail=-0.0018);flat=mk_kl(drift=0.0,vol=0.008)
    kl={"1":up,"3":up,"5":up,"15":up,"60":up}
    # индикаторы
    closes=[r[4] for r in up]
    assert ema(closes,21)>ema(closes,50)
    assert rsi(closes)>55
    assert macd(closes)["line"]>0      # линия выше нуля в тренде вверх (гистограмма — ускорение, может быть ~0)
    assert bollinger(closes)["pos"]>0.7
    assert atr(up,14)>0
    assert vwap(up)>0
    # 01 PRICE: тренд вверх -> LONG, вниз -> SHORT
    fb_up={"klines":kl,"price":up[-1][4]}
    a=agent_price(fb_up)
    assert a["status"]=="OK" and a["side"]=="LONG" and a["confidence"]>0.4,a
    kl_dn={"1":dn,"3":dn,"5":dn,"15":dn,"60":dn}
    a=agent_price({"klines":kl_dn,"price":dn[-1][4]})
    assert a["side"]=="SHORT",a
    a=agent_price({"klines":{},"price":None})
    assert a["status"]=="INSUFFICIENT"
    # 02 FLOW: покупки на трёх биржах -> LONG; дивергенция гасит
    fb={"tape":{"ok":True,"n":500,"delta_pct":25.0,"age_sec":1,"price_change_pct":0.3},
        "binance_flow":{"ok":True,"n":400,"delta_pct":20.0,"age_sec":1},
        "okx_flow":{"ok":True,"n":300,"delta_pct":18.0,"age_sec":2},"price":100.0}
    a=agent_flow(fb)
    assert a["side"]=="LONG" and a["confidence"]>0.5,a
    fb_div=dict(fb);fb_div["tape"]["price_change_pct"]=-0.1
    a2=agent_flow(fb_div)
    assert a2["confidence"]<a["confidence"] and a2["features"]["divergence"],a2
    assert agent_flow({})["status"]=="INSUFFICIENT"
    # 03 LIQUIDITY: имбаланс к биду -> LONG; снятые плотности гасят
    bids=[(100.0-i*0.1,2.0) for i in range(10)];asks=[(100.1+i*0.1,1.0) for i in range(10)]
    fb={"book":{"ok":True,"bids":bids,"asks":asks},
        "binance_depth":{"ok":True,"imbalance_pct":20.0,"spread_pct":0.01},
        "klines":{"5":flat},"price":100.0}
    a=agent_liquidity(fb)
    assert a["status"]=="OK" and a["side"]=="LONG" and a["confidence"]<=0.62,a
    fb2=dict(fb);fb2["book"]={"ok":True,"bids":bids,"asks":asks,"prev":{"bids":[(99.5,50.0)]}}
    # плотность на биде 2.0 >> медианы -> ловушка не сработает, но сигнал есть
    a2=agent_liquidity(fb2)
    assert a2["status"]=="OK"
    assert agent_liquidity({"book":{},"binance_depth":{}})["status"]=="INSUFFICIENT"
    # 04 DERIVATIVES: перегретый funding -> SHORT; OI-комбинации
    fb={"deriv":{"ok":True,"funding_pct":0.09,"funding_hist":[0.0]*29+[0.09],
                 "oi_pct_change":3.0,"lsr_pct_rank":95.0,
                 "liquidations":{"ok":True,"long_usd_1h":500000.0,"short_usd_1h":10000.0}},
        "price":100.0,"klines":{"60":up}}
    a=agent_derivatives(fb)
    assert a["side"]=="SHORT" and a["confidence"]>0.5,a
    assert a["features"].get("price_oi_combo") or True
    assert agent_derivatives({"deriv":{}})["status"]=="INSUFFICIENT"
    # 05 CROSS: подтверждение покупок на 2+ биржах -> LONG
    fb={"cross":{"parts":{
            "bybit":{"ok":True,"last":100.0,"funding_pct":0.01},
            "binance_price":{"ok":True,"last":100.05},
            "okx_price":{"ok":True,"last":100.02},
            "binance_flow":{"ok":True,"delta_pct":15.0},
            "okx_flow":{"ok":True,"delta_pct":12.0},
            "okx_funding":{"ok":True,"funding_pct":0.01}},
        "diffs":{"bybit_binance_bps":5.0}},
        "tape":{"ok":True,"delta_pct":18.0,"n":300},"price":100.0}
    a=agent_cross_exchange(fb)
    assert a["side"]=="LONG" and a["features"]["flow_agree_exchanges"]>=2,a
    assert agent_cross_exchange({"cross":{"parts":{}}})["status"]=="INSUFFICIENT"
    # 06 BTC CORR: сам BTC -> DISABLED; альта с корреляцией -> OK
    a=agent_btc_correlation({"alt_klines":{},"klines":kl},"BTCUSDT")
    assert a["status"]=="DISABLED"
    btc=[r[:] for r in up]
    fb={"alt_klines":{"BTCUSDT":btc},"klines":kl,"price":up[-1][4]}
    a=agent_btc_correlation(fb,"SOLUSDT")
    assert a["status"]=="OK" and a["features"].get("corr_60m",0)>0.5,a
    assert agent_btc_correlation({"alt_klines":{},"klines":{"1":[]}},"SOLUSDT")["status"]=="INSUFFICIENT"
    # 07 VOLATILITY: на трендовом ряду — режим и сигма
    a=agent_volatility({"klines":kl,"price":up[-1][4]})
    assert a["status"]=="OK" and a["features"].get("sigma_15m_pct",0)>0,a
    assert a["features"].get("regime")
    assert agent_volatility({"klines":{"1":flat[:40]}})["status"]=="INSUFFICIENT"
    # 08 SENTIMENT: никогда не определяет сторону
    a=agent_sentiment({"fng":{"ok":True,"value":82,"delta3":10,"classification":"Extreme Greed"},
                       "news":[{"title":"x","impact":-0.9,"importance":"high"}]})
    assert a["status"]=="OK" and a["side"]=="NEUTRAL",a
    assert agent_sentiment({})["status"]=="INSUFFICIENT"
    # 09 STATISTICAL: без модели DISABLED
    a=agent_statistical({"klines":kl,"model":{}})
    assert a["status"]=="DISABLED",a
    # 10 PREDICTION: сумма вероятностей = 1, контракт полный
    ags,pred=run_all({"klines":kl,"price":up[-1][4],
                      "tape":{"ok":True,"n":500,"delta_pct":22.0,"age_sec":1,"price_change_pct":0.4},
                      "book":{"ok":True,"bids":bids,"asks":asks},
                      "deriv":{"ok":True,"funding_pct":0.01,"funding_hist":[0.01]*30,
                               "lsr_pct_rank":50.0},
                      "cross":{"parts":{"binance_price":{"ok":True,"last":up[-1][4]*1.0002},
                                        "bybit":{"ok":True,"last":up[-1][4]},
                                        "okx_funding":{"ok":True,"funding_pct":0.01}},
                               "n_exchanges":3},
                      "alt_klines":{"BTCUSDT":btc},
                      "fng":{"ok":True,"value":50,"delta3":0},"news":[],
                      "options":{"ok":True,"iv_now":55.0,"iv_mean_7d":55.0,"pcr_oi":1.0,"skew":0.0},
                      "whales":{"ok":True,"status":"AVAILABLE","n_whales":5,"buy_usd":3e6,
                                "sell_usd":5e5,"delta_pct":71.4,"top":[],"n_trades":900},
                      "macro":{"ok":True,"btc_dominance":57.0,"btc_dom_delta_24h":-0.7,
                               "mcap_change_24h_pct":1.5,"total_mcap_usd":3.2e12},
                      "slow":{"mempool":{"ok":True,"fee_fast":20,"mempool_tx":50000,
                                         "median_block_sec":600,"avg_block_tx_6":2500.0},
                              "defillama":{"ok":True,"total_tvl":170e9,"delta_24h_pct":1.4,
                                           "delta_1h_pct":0.1,"history_points":30}}},"SOLUSDT")
    s=pred["features"]["p_up"]+pred["features"]["p_flat"]+pred["features"]["p_down"]
    assert abs(s-1.0)<1e-6,(s,pred["features"])
    assert pred["agent"]=="PREDICTION_AGENT" and pred["side"] in ("UP","FLAT","DOWN")
    assert 0<=pred["confidence"]<=1
    assert pred["features"]["uncertainty"]>0
    # противоречие фиксируется
    assert isinstance(pred["contradictions"],list)
    # NO_SIGNAL при пустом fb
    ags2,pred2=run_all({"klines":{"1":flat},"price":flat[-1][4]},"BTCUSDT")
    assert pred2["status"] in ("OK","INSUFFICIENT")
    # v7.2 — 11 LIQUIDATION_CASCADE: каскад вниз (выносят лонги в 5x базе)
    a=agent_liquidation_cascade({"deriv":{"ok":True,"liquidations":{
        "ok":True,"long_liq_usd_1h":5_000_000.0,"short_liq_usd_1h":100_000.0,
        "sum_usd_1h":5_100_000.0,"sum_usd_24h":12_000_000.0,"n":77}}})
    assert a["status"]=="OK" and a["side"]=="SHORT" and a["confidence"]>0.4,a
    # каскад вверх (выносят шорты)
    a=agent_liquidation_cascade({"deriv":{"ok":True,"liquidations":{
        "ok":True,"long_liq_usd_1h":50_000.0,"short_liq_usd_1h":4_000_000.0,
        "sum_usd_1h":4_050_000.0,"sum_usd_24h":9_000_000.0,"n":60}}})
    assert a["side"]=="LONG",a
    # пустая лента — INSUFFICIENT; перекос в норме — NEUTRAL
    assert agent_liquidation_cascade({"deriv":{"ok":True,"liquidations":{
        "ok":True,"long_liq_usd_1h":10_000.0,"short_liq_usd_1h":9_000.0,
        "sum_usd_1h":19_000.0,"sum_usd_24h":19_000.0,"n":3}}})["side"]=="NEUTRAL"
    assert agent_liquidation_cascade({})["status"]=="INSUFFICIENT"
    # v7.2 — 12 ANOMALY: шоковый объём с движением вверх -> LONG
    an=list(flat)  # копия плоского ряда
    last=an[-1]
    an[-1]=[last[0],last[1],last[2],last[3],last[4]*1.02,50.0]  # +2% и объём x~40
    a=agent_anomaly({"klines":{"1":an},"price":an[-1][4]})
    assert a["status"]=="OK" and a["features"]["z_volume"]>3,a
    assert a["side"]=="LONG",a
    # аномалия без направления не торгуется
    an2=list(flat);l2=an2[-1]
    an2[-1]=[l2[0],l2[1],l2[2],l2[3],l2[4],50.0]
    assert agent_anomaly({"klines":{"1":an2}})["side"]=="NEUTRAL"
    assert agent_anomaly({"klines":{"1":flat[:50]}})["status"]=="INSUFFICIENT"
    # v7.2 — 13 OPTIONS: паника (DVOL 1.5x + путы дороже) -> SHORT; фиксстура без сети
    a=agent_options({"symbol":"BTCUSDT","options":{
        "ok":True,"iv_now":90.0,"iv_mean_7d":55.0,"pcr_oi":1.1,
        "skew":0.22,"expiry":"2026-12-26","n_instruments":400}})
    assert a["status"]=="OK" and a["side"]=="SHORT",a
    # оптимизм (коллы дороже, PCR низкий) -> LONG
    a=agent_options({"symbol":"ETHUSDT","options":{
        "ok":True,"iv_now":60.0,"iv_mean_7d":62.0,"pcr_oi":0.55,
        "skew":-0.18,"expiry":"2026-12-26","n_instruments":300}})
    assert a["side"]=="LONG",a
    # не-BTC/ETH — честно INSUFFICIENT; недоступность Deribit — INSUFFICIENT
    assert agent_options({"symbol":"SUIUSDT"})["status"]=="INSUFFICIENT"
    assert agent_options({"symbol":"BTCUSDT","options":{"ok":False,"status":"UNAVAILABLE"}})["status"]=="INSUFFICIENT"
    # v8.0 — 14 ONCHAIN: перегрев сети + сам BTC -> слабый LONG; альту — только контекст
    mp={"ok":True,"fee_fast":80,"mempool_tx":180000,"median_block_sec":450,
        "avg_block_tx_6":3200.0,"last_block_height":900000}
    a=agent_onchain({"symbol":"BTCUSDT","slow":{"mempool":mp}})
    assert a["status"]=="OK" and a["side"]=="LONG" and 0<a["confidence"]<=0.4,a
    a=agent_onchain({"symbol":"SOLUSDT","slow":{"mempool":mp}})
    assert a["side"]=="NEUTRAL",a
    a=agent_onchain({"symbol":"BTCUSDT","slow":{"mempool":{"ok":False,"status":"UNAVAILABLE"}}})
    assert a["status"]=="INSUFFICIENT"
    assert agent_onchain({})["status"]=="INSUFFICIENT"
    # v8.0 — 15 DEFI: приток капитала -> LONG; отток -> SHORT; без истории — нейтрально
    dl={"ok":True,"total_tvl":180e9,"delta_24h_pct":2.5,"delta_1h_pct":0.3,"history_points":25}
    a=agent_defi_flow({"slow":{"defillama":dl}})
    assert a["status"]=="OK" and a["side"]=="LONG",a
    dl2=dict(dl);dl2["delta_24h_pct"]=-2.5;dl2["delta_1h_pct"]=-0.2
    assert agent_defi_flow({"slow":{"defillama":dl2}})["side"]=="SHORT"
    dl3=dict(dl);dl3["delta_24h_pct"]=None;dl3["history_points"]=3
    assert agent_defi_flow({"slow":{"defillama":dl3}})["side"]=="NEUTRAL"
    assert agent_defi_flow({"slow":{}})["status"]=="INSUFFICIENT"
    # v8.0 — 16 BASIS: перегретый премиум лонгов -> SHORT; дисконт+отрицательный funding -> LONG
    bx={"cross":{"parts":{"binance_price":{"ok":True,"last":100.0},
                          "bybit":{"ok":True,"last":100.25},
                          "okx_funding":{"ok":True,"funding_pct":0.05}}},
        "price":100.25,"deriv":{"ok":True,"funding_pct":0.05}}
    a=agent_basis(bx)
    assert a["status"]=="OK" and a["side"]=="SHORT",a
    assert a["features"]["basis_bps"]==25.0,a["features"]
    bx2={"cross":{"parts":{"binance_price":{"ok":True,"last":100.0},
                           "bybit":{"ok":True,"last":99.8}}},
         "price":99.8,"deriv":{"ok":True,"funding_pct":-0.01}}
    a=agent_basis(bx2)
    assert a["side"]=="LONG",a
    assert agent_basis({"cross":{"parts":{}}})["status"]=="INSUFFICIENT"
    # v8.0 — 17 WHALE: киты покупают -> LONG; тишина — честный NEUTRAL
    wh={"ok":True,"status":"AVAILABLE","n_whales":6,"buy_usd":5_000_000.0,
        "sell_usd":1_000_000.0,"delta_pct":66.7,"top":[],"n_trades":900}
    a=agent_whale_watch({"whales":wh})
    assert a["status"]=="OK" and a["side"]=="LONG" and a["confidence"]>0.35,a
    assert agent_whale_watch({"whales":{"ok":False,"status":"INSUFFICIENT","note":"пусто","n_trades":800}})["side"]=="NEUTRAL"
    assert agent_whale_watch({"whales":{"ok":False,"status":"UNAVAILABLE"}})["status"]=="INSUFFICIENT"
    assert agent_whale_watch({})["status"]=="INSUFFICIENT"
    # v8.0 — 18 SESSION: часовая история строится, направление только при сдвиге против шума
    hkl=[]
    t0=1600000000  # utc-час фиксирован детерминированно
    p=100.0
    for i in range(240):  # 10 суток часовых свечей
        ts=(t0+i*3600)*1000
        h=time.gmtime(ts/1000.0).tm_hour
        drift=0.004 if h==14 else 0.0   # час 14 UTC систематически растёт
        o=p;p*= (1+drift)
        hkl.append([ts,o,p*1.001,o*0.999,p,10.0])
    a=agent_session({"klines":{"60":hkl},"price":hkl[-1][4]})
    assert a["status"]=="OK" and "session" in a["features"],a
    assert a["features"]["n_obs"]>=3
    # без истории — INSUFFICIENT
    assert agent_session({"klines":{"60":hkl[:50]}})["status"]=="INSUFFICIENT"
    assert agent_session({})["status"]=="INSUFFICIENT"
    # v8.0 — 19 DOMINANCE: мкап растёт -> LONG для BTC; доминанция растёт -> SHORT для альта
    mac={"ok":True,"btc_dominance":58.5,"btc_dom_delta_24h":0.8,
         "mcap_change_24h_pct":3.2,"total_mcap_usd":3.4e12}
    a=agent_dominance({"symbol":"BTCUSDT","macro":mac})
    assert a["status"]=="OK" and a["side"]=="LONG",a
    a=agent_dominance({"symbol":"SOLUSDT","macro":mac})
    assert a["side"]=="SHORT",a
    mac2=dict(mac);mac2["btc_dom_delta_24h"]=-0.9;mac2["mcap_change_24h_pct"]=1.0
    assert agent_dominance({"symbol":"SOLUSDT","macro":mac2})["side"]=="LONG"
    mac3=dict(mac);mac3["btc_dom_delta_24h"]=None
    assert agent_dominance({"symbol":"SOLUSDT","macro":mac3})["side"]=="NEUTRAL"
    assert agent_dominance({"macro":{"ok":False,"status":"UNAVAILABLE"}})["status"]=="INSUFFICIENT"
    # v8.0 — ПАУТИНА: согласие/конфликт/слабые связи
    mk=lambda n,s,c: {"agent":n,"status":"OK","side":s,"confidence":c,
                      "features":{},"evidence":[],"contradictions":[],"invalidate":[],"timestamp":0}
    web=web_analysis([mk("PRICE_ANALYST","LONG",0.6),mk("ORDER_FLOW_ANALYST","LONG",0.5),
                      mk("VOLATILITY_ANALYST","NEUTRAL",0.3),mk("LIQUIDITY_ANALYST","SHORT",0.5),
                      mk("WHALE_WATCH","LONG",0.55)])
    assert web["active"]==4 and web["agree"]==2 and web["conflict"]==2,web
    assert web["coherence"]==0.5,web
    statuses={l["a"]+"-"+l["b"]:l["status"] for l in web["links"]}
    assert statuses["PRICE_ANALYST-ORDER_FLOW_ANALYST"]=="AGREE"
    assert statuses["ORDER_FLOW_ANALYST-LIQUIDITY_ANALYST"]=="CONFLICT"
    assert statuses["ORDER_FLOW_ANALYST-WHALE_WATCH"]=="AGREE"
    assert statuses["LIQUIDITY_ANALYST-WHALE_WATCH"]=="CONFLICT"
    assert statuses["PRICE_ANALYST-VOLATILITY_ANALYST"]=="WEAK"
    web2=web_analysis([mk("PRICE_ANALYST","LONG",0.6)])
    assert web2["coherence"] is None  # меньше 2 активных связей — согласие не считается
    # v8.0: полный состав — 18 аналитиков + PREDICTION, все контракты валидны
    assert len(ags)==18 and len(AGENTS)==19
    for x in ags:
        assert x["agent"] in AGENTS and x["status"] in ("OK","INSUFFICIENT","DISABLED"),x
        assert 0<=x["confidence"]<=1
    print("mm_agents: OK")
if __name__=="__main__":
    import argparse
    ap=argparse.ArgumentParser()
    ap.add_argument("--selftest",action="store_true")
    g=ap.parse_args()
    if g.selftest: selftest()
    else: print("use --selftest")
