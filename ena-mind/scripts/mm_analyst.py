#!/usr/bin/env python3
"""Аналитики ENA-MIND v6.0: ta / flow / deriv / sentiment. Каждый НЕЗАВИСИМО выдаёт
side ∈ {long, short, flat}, p, confidence, evidence[], invalidate[].

Логика v5.0 сохранена. Отличия v6.0:
- deriv: история funding приходит из снимка (Bybit /v5/market/funding/history), если не
  передана явно; в evidence добавлено изменение OI за ~24ч (Bybit /v5/market/open-interest);
- sentiment: необязательный новостной фон (news) — влияет мягко и сам по себе никогда не
  вызывает HALT; вес ограничен cfg.news_max_weight (0.10). Заголовок новости и фактическое
  влияние на цену не всегда совпадают — поэтому новости только корректируют уверенность
  голоса толпы и фиксируются в evidence, а не решают сторону единолично.
"""
import mm_config,mm_regime,mm_schema
SIDES=mm_schema.SIDES
def _out(module):
    return {"module":module,"ok":False,"side":"flat","p":0.5,"confidence":0.0,
            "evidence":[],"invalidate":[],"note":""}
def _clamp(x,a,b): return max(a,min(b,x))
def _conserv(s):
    s=dict(s);s["confidence"]=round(s["confidence"]*0.7,4);s["p"]=round(0.5+(s["p"]-0.5)*0.7,4)
    s["note"]=(s["note"]+" " if s["note"] else "")+"[conservative revisit]"
    return s
# ---------- TA: тренд/пробой MA28/MA200 с подтверждением импульсом ----------
def ta_analyze(klines=None,conservative=False,regime=None,cfg=None):
    c=cfg or mm_config.load()
    out=_out("ta")
    rows=[r for r in (klines or []) if isinstance(r,(list,tuple)) and len(r)>=5 and all(x>0 for x in (r[1],r[2],r[3],r[4]))]
    if len(rows)<c["min_klines"]:
        out["note"]="INSUFFICIENT: для ta нужно >=%d свечей; получено %d"%(c["min_klines"],len(rows))
        return out
    closes=[r[4] for r in rows];cx=closes[-1];prev=closes[-2]
    fast=sum(closes[-28:])/28;slow=sum(closes[-200:])/200
    trs=[max(rows[j][2]-rows[j][3],abs(rows[j][2]-closes[j-1]),abs(rows[j][3]-closes[j-1])) for j in range(len(rows)-14,len(rows))]
    atr=sum(trs)/14
    prev_low=rows[-2][3];prev_high=rows[-2][2]
    long_sig=cx>slow and prev_low<=fast and cx>prev and cx>fast
    short_sig=cx<slow and prev_high>=fast and cx<prev and cx<fast
    if not (long_sig or short_sig) and c.get("ta_trend_mode",True):
        # v6.1: УСТАНОВИВШИЙСЯ тренд без свежего пробоя. Раньше ta молчал весь тренд
        # (сигнал требовал свежего пересечения MA28) — совет оставался на голосах
        # контр-трендовой толпы. Теперь структура тренда сама является голосом:
        # цена и MA28 по одну сторону MA200, откат к MA28 не глубже 0.5 ATR.
        tol=0.5*atr
        trend_long=cx>slow and fast>slow and cx>fast-tol
        trend_short=cx<slow and fast<slow and cx<fast+tol
        if trend_long or trend_short:
            side="long" if trend_long else "short"
            dist=abs(cx-slow)/atr if atr>0 else 0.0
            prior=mm_config.prior_for("ta",regime,c)
            conf=_clamp(0.53+0.04*min(dist,2.0),0.53,0.68)  # слабее свежего пробоя
            p=round(_clamp(prior+0.02*min(dist,2.0),0.5,0.78),4)
            out.update(ok=True,side=side,p=p,confidence=round(conf,4))
            out["evidence"]=[f"тренд: close {cx:.1f} {'выше' if side=='long' else 'ниже'} MA200 ({slow:.1f}) на {dist:.2f} ATR (установившийся тренд)",
                             f"MA28={fast:.1f} по ту же сторону MA200 — структура тренда цела; свежего пробоя нет",
                             f"голос слабее пробойного; prior={prior:.2f} (regime={regime or 'unknown'})"]
            out["invalidate"]=[f"close возвращается {'ниже' if side=='long' else 'выше'} MA28 ({fast:.1f})",
                               f"MA28 пересекает MA200 — структура тренда ломается"]
            return _conserv(out) if conservative else out
    if not (long_sig or short_sig):
        out.update(ok=True,note="нет сигнала: цена вне условий пробоя MA28 при тренде по MA200")
        out["evidence"]=[f"close={cx:.1f} vs MA28={fast:.1f}, MA200={slow:.1f}"]
        return out
    side="long" if long_sig else "short"
    dist=abs(cx-slow)/atr if atr>0 else 0.0
    prior=mm_config.prior_for("ta",regime,c)  # приор зависит от режима
    conf=_clamp(0.55+0.05*min(dist,2.0),0.55,0.75)
    p=round(_clamp(prior+0.025*min(dist,2.0),0.5,0.8),4)
    out.update(ok=True,side=side,p=p,confidence=round(conf,4))
    out["evidence"]=[f"тренд: close {cx:.1f} {'выше' if side=='long' else 'ниже'} MA200 ({slow:.1f}) на {dist:.2f} ATR",
                     f"пробой MA28={fast:.1f}: close {'>' if side=='long' else '<'} MA28",
                     f"импульс: close {prev:.1f} -> {cx:.1f}; prior={prior:.2f} (regime={regime or 'unknown'})"]
    out["invalidate"]=[f"close возвращается {'ниже' if side=='long' else 'выше'} MA28 ({fast:.1f})",
                       f"ATR14={atr:.4f}: волатильность <0.5x — сигнал стареет"]
    return _conserv(out) if conservative else out
# ---------- FLOW: лента/CVD/дельта/книга (микроструктура) ----------
def flow_analyze(snap=None,conservative=False,regime=None,cfg=None):
    c=cfg or mm_config.load()
    out=_out("flow")
    tape=(snap or {}).get("parts",{}).get("tape",{})
    if not tape.get("ok"): out["note"]="INSUFFICIENT: нет ленты сделок (snap.parts.tape.ok=false)";return out
    cvd=tape.get("cvd",0.0);dp=tape.get("delta_pct",0.0);pc=tape.get("price_change_pct",0.0);div=tape.get("divergence",False);n=tape.get("n",0)
    book=(snap or {}).get("parts",{}).get("book",{});imb=book.get("imbalance_pct") if book.get("ok") else None
    side="flat";conf=0.0
    # в volatile порог перевеса выше — меньше ложных сигналов
    thr=2.0 if regime!="volatile" else 3.0
    if cvd>0 and dp>=thr and pc>0 and not div: side="long"
    elif cvd<0 and dp<=-thr and pc<0 and not div: side="short"
    if side=="flat":
        out.update(ok=True,note="нет перевеса агрессоров"+("; дивергенция цена/CVD" if div else ""))
        out["evidence"]=[f"CVD={cvd} ({dp:+.2f}% от объёма ленты), цена {pc:+.3f}%"+(", дивергенция" if div else "")]
        if div: out["invalidate"]=["дивергенция: поток и цена разошлись — доверие к потоку ниже"]
        return _conserv(out) if conservative else out
    strength=min(abs(dp)/10.0,1.0)
    prior=mm_config.prior_for("flow",regime,c)
    conf=_clamp(0.55+0.25*strength,0.55,0.9)
    if div: conf=round(conf*0.7,4)
    p=round(_clamp(prior+0.05*strength,0.5,0.8),4)
    out.update(ok=True,side=side,p=p,confidence=round(conf,4))
    out["evidence"]=[f"CVD={cvd:+.4f}, дельта {dp:+.2f}% по {n} сделкам ленты",
                     f"цена {pc:+.3f}% за окно ленты, возраст ленты {tape.get('age_sec')}с"]
    if imb is not None and abs(imb)>=20.0:
        agree=(imb>0)==(side=="long")
        out["evidence"].append(f"книга: дисбаланс {imb:+.1f}% {'в сторону' if agree else 'ПРОТИВ'} сигнала (спуфабельно)")
        if not agree: out["confidence"]=round(out["confidence"]*0.85,4)
    if div: out["evidence"].append("дивергенция цена/CVD — сигнал ослаблен")
    out["invalidate"]=["CVD меняет знак на противоположный","дельта падает по модулю ниже 1%"]
    return _conserv(out) if conservative else out
# ---------- DERIV: funding/OI + ПЕРЦЕНТИЛЬ funding 30д ----------
def deriv_analyze(snap=None,conservative=False,regime=None,cfg=None,funding_history=None):
    """funding сравнивается не с фиксированным порогом, а с перцентилем за 30 дней
    (v5). v6: funding_history берётся из snap.parts.funding_hist.history (Bybit
    /v5/market/funding/history), если не передана явно; в evidence — изменение OI."""
    c=cfg or mm_config.load()
    out=_out("deriv")
    parts=(snap or {}).get("parts",{})
    d=parts.get("deriv",{})
    if not d.get("ok"): out["note"]="INSUFFICIENT: нет деривативных данных (funding/mark)";return out
    f=d.get("funding_pct",0.0);oi=d.get("oi")
    if funding_history is None:
        fh=parts.get("funding_hist",{})
        if fh.get("ok") and fh.get("history"): funding_history=fh["history"]
    # перцентиль funding за историю, если есть
    pct_rank=None
    floor=float(c.get("funding_abs_floor",0.05))
    if funding_history and len(funding_history)>=10:
        sorted_h=sorted(funding_history)
        k=int((len(sorted_h)-1)*0.95)  # 95-й перцентиль для экстремума
        extreme_hi=sorted_h[k];extreme_lo=sorted_h[max(0,int((len(sorted_h)-1)*0.05))]
        # ранг текущего funding в истории
        below=sum(1 for x in funding_history if x<=f)
        pct_rank=round(below/len(funding_history)*100,1)
        # v6.1: абсолютный пол. Перцентиль 100 при |funding| ~0.01% — это БАЗОВЫЙ уровень
        # (обычная выплата), а не экстремум: без пола deriv голосовал short на каждом снимке.
        ext_hi_ok=f>=extreme_hi and abs(f)>=floor
        ext_lo_ok=f<=extreme_lo and abs(f)>=floor
        out["evidence"]=[f"funding={f:+.4f}% (персентиль {pct_rank} из 100 за 30д, окно={len(funding_history)})"]
        if (f>=extreme_hi or f<=extreme_lo) and abs(f)<floor:
            out["evidence"].append(f"|funding| {abs(f):.4f}% < пола {floor:.2f}% — базовый уровень, НЕ экстремум (голос deriv нейтрален)")
    else:
        ext_hi_ok=f>=c["funding_extreme_pct"];ext_lo_ok=f<=-c["funding_extreme_pct"]
        out["evidence"]=[f"funding={f:+.4f}% (без истории: порог ±{c['funding_extreme_pct']}%)"]
    oih=parts.get("oi_hist",{})
    oi_chg=oih.get("oi_pct_change") if oih.get("ok") else None
    out["evidence"].append(f"OI={oi} BTC" if oi else "OI нет")
    if oi_chg is not None:
        out["evidence"].append(f"OI за ~24ч: {oi_chg:+.2f}% ({'позиции открываются' if oi_chg>0 else 'позиции закрываются'})")
    side="flat";conf=0.0
    if ext_hi_ok: side,conf="short",0.6   # лонги перегреты и платят — контр-трендовое давление
    elif ext_lo_ok: side,conf="long",0.6  # шорты перегреты — потенциал сквиза вверх
    prior=mm_config.prior_for("deriv",regime,c)
    p=round(_clamp(prior if side!="flat" else 0.5,0.5,0.8),4)
    out.update(ok=True,side=side,p=p,confidence=round(conf,4))
    if side=="short": out["note"]="перегретый funding: экстремум давления лонгов — контр-тренд"
    elif side=="long": out["note"]="отрицательный funding: давление шортов — потенциал сквиза вверх"
    else: out["note"]="funding нейтрален: режим не даёт стороны"
    if oi: out["invalidate"]=["funding возвращается в нейтральный диапазон"]
    return _conserv(out) if conservative else out
# ---------- SENTIMENT: толпа F&G + LSR + новостной фон (мягкий вход v6) ----------
def _news_weight(news,cfg):
    """Суммарный вес новостного фона в [-news_max_weight..+news_max_weight].
    impact: -1..+1, importance: low|medium|high. Заголовок ≠ фактическое влияние,
    поэтому вес зажат и является только поправкой к голосу толпы, не стороной."""
    c=cfg or mm_config.load()
    if not news: return 0.0,[]
    imp_k={"high":1.0,"medium":0.6,"low":0.3}
    s=0.0;ev=[]
    for n in news[:20]:  # не более 20 свежих
        try: imp=float(n.get("impact") or 0.0)
        except (TypeError,ValueError): imp=0.0
        imp=max(-1.0,min(1.0,imp))
        k=imp_k.get(str(n.get("importance") or "medium").lower(),0.6)
        s+=imp*k
        if abs(imp)>=0.5 and n.get("title"):
            ev.append(f"новость[{n.get('source','?')}]: {str(n.get('title'))[:80]} ({'позитив' if imp>0 else 'негатив'}, {n.get('importance','medium')})")
    cap=float(c.get("news_max_weight",0.10))
    return max(-cap,min(cap,s/10.0)),ev[:5]
def sentiment_analyze(snap=None,conservative=False,regime=None,cfg=None,news=None):
    """Толпа = психология рынка. Контр-трендовая логика (v5 сохранена):
    - Extreme Greed (F&G>=75) или толпа лонгует (LSR>=1.4) -> short (контр-тренд);
    - Extreme Fear (F&G<=25) или толпа шортует (LSR<=0.7) -> long (контр-тренд);
    - F&G дельта 3/7д ускоряет разворот.
    v6: news — необязательный фон; корректирует confidence голоса толпы (±0.5×news_w),
    сам сторону не выбирает; без F&G и LSR остаётся INSUFFICIENT (новости alone не голос)."""
    c=cfg or mm_config.load()
    out=_out("sentiment")
    parts=(snap or {}).get("parts",{})
    fng=parts.get("fng",{});lsr=parts.get("lsr",{})
    if not fng.get("ok") and not lsr.get("ok"):
        out["note"]="INSUFFICIENT: нет ни F&G, ни LSR";return out
    votes=[];ev=[]
    if fng.get("ok"):
        v=fng["value"];d3=fng.get("delta3",0);d7=fng.get("delta7",0)
        ev.append(f"F&G={v} ({fng.get('classification','')}), дельта 3д={d3:+d}, 7д={d7:+d}")
        if v>=c["fng_extreme_high"]: votes.append(("short",0.55));ev.append("Extreme Greed -> контр-тренд short")
        elif v<=c["fng_extreme_low"]: votes.append(("long",0.55));ev.append("Extreme Fear -> контр-тренд long")
        # ускорение: резкий рост Greed за 3д (>15 пунктов) усиливает short
        if d3>=15: votes.append(("short",0.6));ev.append("F&G рост +15 за 3д -> перегрев усиливается")
        elif d3<=-15: votes.append(("long",0.6));ev.append("F&G падение -15 за 3д -> паника усиливается")
    if lsr.get("ok"):
        r=lsr["ratio"];ev.append(f"LSR={r} (long {lsr.get('long_pct')}% / short {lsr.get('short_pct')}%)")
        pct=lsr.get("pct_rank")
        if pct is not None:
            # v6.1: перцентиль 7-дневной почасовой истории LSR (Bybit account-ratio, 168 набл.).
            # База Bybit ~2.5-3.3, поэтому фиксированный порог 1.4 срабатывал почти всегда
            # -> вечный контр-тренд short. Перцентиль отделяет аномалию от нормы.
            ev.append(f"LSR-перцентиль {pct:.0f} за {lsr.get('pctl_window','?')}ч истории Bybit")
            if pct>=c["lsr_pctl_high"]:
                votes.append(("short",0.55));ev.append("LSR перцентиль >=%.0f -> толпа лонгует аномально сильнее обычного, контр-тренд short"%c["lsr_pctl_high"])
            elif pct<=c["lsr_pctl_low"]:
                votes.append(("long",0.55));ev.append("LSR перцентиль <=%.0f -> толпа шортует аномально сильнее обычного, контр-тренд long"%c["lsr_pctl_low"])
        else:
            # истории мало — фиксированные пороги (fallback), честно помечаем
            ev.append(f"LSR: истории мало ({lsr.get('pctl_window',0)}/{c['lsr_hist_min_n']} набл.) — фиксированные пороги")
            if r>=c["lsr_extreme"]: votes.append(("short",0.55));ev.append("LSR>=%.1f -> толпа лонгует, контр-тренд short"%c["lsr_extreme"])
            elif r<=c["lsr_low"]: votes.append(("long",0.55));ev.append("LSR<=%.2f -> толпа шортует, контр-тренд long"%c["lsr_low"])
    # новостной фон: мягкая поправка, не сторона
    nw,nw_ev=_news_weight(news,c)
    if nw_ev: ev.extend(nw_ev)
    if not votes:
        if abs(nw)>=0.05:
            side="long" if nw>0 else "short"
            out.update(ok=True,side=side,p=round(_clamp(mm_config.prior_for("sentiment",regime,c)*0.98,0.5,0.55),4),
                       confidence=0.5,note="толпа нейтральна, но новостной фон слегка смещён (мягкий сигнал; новость ≠ влияние на цену)")
            out["evidence"]=ev
            out["invalidate"]=["F&G и LSR остаются нейтральными, новости теряют актуальность"]
            return _conserv(out) if conservative else out
        out.update(ok=True,note="толпа нейтральна: F&G и LSR в средних диапазонах")
        out["evidence"]=ev;return _conserv(out) if conservative else out
    # суммируем голоса по сторонам
    long_w=sum(w for s,w in votes if s=="long")
    short_w=sum(w for s,w in votes if s=="short")
    if long_w>short_w: side="long";w=long_w
    elif short_w>long_w: side="short";w=short_w
    else: side="flat";w=0
    if side=="flat":
        out.update(ok=True,note="голоса толпы уравновешены")
        out["evidence"]=ev;return _conserv(out) if conservative else out
    # новости ЗА сторону чуть усиливают уверенность, ПРОТИВ — ослабляют (не переворачивают)
    news_adj=+nw if side=="long" else -nw
    prior=mm_config.prior_for("sentiment",regime,c)
    p=round(_clamp(prior+0.02*w+0.5*news_adj,0.5,0.72),4)
    conf=round(_clamp(0.55+0.05*w+0.5*news_adj,0.55,0.7),4)
    out.update(ok=True,side=side,p=p,confidence=conf)
    if nw_ev and news_adj<0: ev.append("новостной фон против стороны толпы — уверенность снижена")
    out["evidence"]=ev
    lsr_inv="LSR возвращается в свой обычный диапазон (перцентиль 10..90)" if (parts.get("lsr") or {}).get("pct_rank") is not None else "LSR входит в 0.7..1.4"
    out["invalidate"]=["F&G возвращается в 30..70",lsr_inv]
    return _conserv(out) if conservative else out
# ---------- сборка ----------
def analyze_all(snap=None,klines=None,conservative=None,regime=None,cfg=None,funding_history=None,news=None):
    """conservative: None | множество имён модулей для пересмотра.
    regime: режим рынка (mm_regime.classify) — влияет на приоры.
    funding_history: для deriv (перцентиль 30д); если None — из snap.parts.funding_hist.
    news: новостной фон для sentiment (необязательный, мягкое влияние).
    Возвращает {module: verdict}."""
    cons=conservative or set()
    kw=dict(regime=regime,cfg=cfg)
    return {"ta":ta_analyze(klines,conservative="ta" in cons,**kw),
            "flow":flow_analyze(snap,conservative="flow" in cons,**kw),
            "deriv":deriv_analyze(snap,conservative="deriv" in cons,funding_history=funding_history,**kw),
            "sentiment":sentiment_analyze(snap,conservative="sentiment" in cons,news=news,**kw)}
def selftest():
    up=[[i,100+i*0.1,100.5+i*0.1,99.5+i*0.1,100+i*0.1,1] for i in range(210)]
    up[-1]=[209,120.9,122.5,120.8,122.0,1];up[-2]=[208,120.8,121.0,119.0,120.9,1]
    # ta: тренд вверх -> long
    a=ta_analyze(up,regime="trend_up")
    assert a["ok"] and a["side"]=="long" and a["confidence"]>0.55,a
    # regime-приор: в trend_up приор ta выше, чем в range
    a_range=ta_analyze(up,regime="range")
    assert a["p"]>a_range["p"],(a["p"],a_range["p"])
    # без свечей — INSUFFICIENT
    assert not ta_analyze([])["ok"]
    # conservative ослабляет
    ac=ta_analyze(up,conservative=True,regime="trend_up")
    assert ac["confidence"]<a["confidence"] and ac["p"]<a["p"]
    # flow: продавцы давят -> short
    snap={"parts":{"tape":{"ok":True,"cvd":-5.0,"delta_pct":-4.0,"price_change_pct":-0.5,"divergence":False,"n":500,"age_sec":3.0},
                   "book":{"ok":True,"imbalance_pct":-30.0}}}
    f=flow_analyze(snap,regime="range");assert f["ok"] and f["side"]=="short"
    # дивергенция -> flat
    snap_div={"parts":{"tape":{"ok":True,"cvd":4.0,"delta_pct":3.0,"price_change_pct":-0.4,"divergence":True,"n":500,"age_sec":3.0},
                       "book":{"ok":False}}}
    fd=flow_analyze(snap_div);assert fd["side"]=="flat" and "дивергенция" in fd["note"]
    # deriv с перцентилем: история передана явно
    snap_d={"parts":{"deriv":{"ok":True,"funding_pct":0.08,"oi":68000.0}}}
    hist=[0.0]*29+[0.08]
    dd=deriv_analyze(snap_d,regime="range",funding_history=hist)
    assert dd["ok"] and dd["side"]=="short" and "персентиль" in dd["evidence"][0],dd
    # v6: история funding из снимка + OI-изменение в evidence
    snap_d2={"parts":{"deriv":{"ok":True,"funding_pct":0.08,"oi":68000.0},
                      "funding_hist":{"ok":True,"history":hist},
                      "oi_hist":{"ok":True,"oi_pct_change":-4.2}}}
    dd2=deriv_analyze(snap_d2,regime="range")
    assert dd2["side"]=="short" and any("OI за ~24ч" in e for e in dd2["evidence"]),dd2
    # funding 0.08 без истории -> тоже short, но по порогу
    dd3=deriv_analyze(snap_d,regime="range")
    assert dd3["side"]=="short"
    # sentiment: Extreme Greed + LSR>1.4 -> short
    snap_s={"parts":{"fng":{"ok":True,"value":80,"delta3":5,"delta7":10,"classification":"Extreme Greed"},
                     "lsr":{"ok":True,"ratio":1.5,"long_pct":60.0,"short_pct":40.0}}}
    s=sentiment_analyze(snap_s,regime="range")
    assert s["ok"] and s["side"]=="short",s
    # Extreme Fear -> long
    snap_f={"parts":{"fng":{"ok":True,"value":15,"delta3":-5,"delta7":-8,"classification":"Extreme Fear"},
                     "lsr":{"ok":True,"ratio":0.5,"long_pct":33.0,"short_pct":67.0}}}
    s2=sentiment_analyze(snap_f,regime="range")
    assert s2["ok"] and s2["side"]=="long",s2
    # нейтральная толпа -> flat
    snap_n={"parts":{"fng":{"ok":True,"value":50,"delta3":0,"delta7":0,"classification":"Neutral"},
                     "lsr":{"ok":True,"ratio":1.0,"long_pct":50.0,"short_pct":50.0}}}
    assert sentiment_analyze(snap_n)["side"]=="flat"
    # v6: новости. short-сигнал толпы + негативный фон (news_adj>0 для short) -> усиливается
    news_neg=[{"title":"regulator crackdown","impact":-1.0,"importance":"high","source":"test"},
              {"title":"ETF outflows","impact":-0.8,"importance":"medium","source":"test"}]
    s3=sentiment_analyze(snap_s,regime="range",news=news_neg)
    assert s3["side"]=="short" and s3["confidence"]>=s["confidence"],(s3["confidence"],s["confidence"])
    # short-сигнал толпы + позитивный фон -> ослабляется, но не переворачивается
    news_pos=[{"title":"ETF inflows massive","impact":1.0,"importance":"high","source":"test"}]
    s4=sentiment_analyze(snap_s,regime="range",news=news_pos)
    assert s4["side"]=="short" and s4["confidence"]<s["confidence"],(s4["confidence"],s["confidence"])
    # нейтральная толпа + слабый новостной фон -> flat (порог 0.05)
    s5=sentiment_analyze(snap_n,news=[{"title":"x","impact":0.3,"importance":"low","source":"t"}])
    assert s5["side"]=="flat"
    # без F&G и LSR — INSUFFICIENT (новости alone не голос)
    assert not sentiment_analyze({"parts":{}},news=news_pos)["ok"]
    # analyze_all + конфликт ta-long/flow-short
    allr=analyze_all(snap=snap,klines=up,regime="trend_up")
    assert allr["ta"]["side"]=="long" and allr["flow"]["side"]=="short"
    rv=analyze_all(snap=snap,klines=up,regime="trend_up",conservative={"flow"})
    assert rv["flow"]["confidence"]<allr["flow"]["confidence"]
    # v6.1: установившийся тренд ВНИЗ без свежего пробоя -> ta голосует short
    dn=[[i,300-i*0.2,301-i*0.2,299-i*0.2,300-i*0.2,1] for i in range(240)]
    dnt=ta_analyze(dn,regime="trend_down")
    assert dnt["ok"] and dnt["side"]=="short" and dnt["confidence"]>=0.53,(dnt["side"],dnt["note"])
    assert any("установившийся тренд" in e for e in dnt["evidence"]),dnt["evidence"]
    # тот же тренд, но ta_trend_mode выключен -> нет сигнала (как в v6.0)
    c_off=mm_config.load();c_off["ta_trend_mode"]=False
    dnt2=ta_analyze(dn,regime="trend_down",cfg=c_off)
    assert dnt2["side"]=="flat" and "нет сигнала" in dnt2["note"]
    # пробойный сигнал сильнее трендового
    up=[[i,100+i*0.1,100.5+i*0.1,99.5+i*0.1,100+i*0.1,1] for i in range(210)]
    up[-1]=[209,120.9,122.5,120.8,122.0,1];up[-2]=[208,120.8,121.0,119.0,120.9,1]
    assert ta_analyze(up,regime="trend_up")["confidence"]>dnt["confidence"]
    # v6.1: funding 0.008% на вершине перцентиля (100/100) — НИЖЕ пола 0.05% -> deriv нейтрален
    snap_fl={"parts":{"deriv":{"ok":True,"funding_pct":0.008,"oi":68000.0},
                      "funding_hist":{"ok":True,"history":[0.0]*29+[0.008]}}}
    df=deriv_analyze(snap_fl,regime="range")
    assert df["ok"] and df["side"]=="flat" and any("НЕ экстремум" in e for e in df["evidence"]),df
    # тот же перцентиль, но funding 0.06% (выше пола) -> short
    snap_hi={"parts":{"deriv":{"ok":True,"funding_pct":0.06,"oi":68000.0},
                      "funding_hist":{"ok":True,"history":[0.0]*29+[0.06]}}}
    dh=deriv_analyze(snap_hi,regime="range")
    assert dh["side"]=="short",dh
    # v6.1: LSR 2.5 при базе истории ~2.5 (перцентиль ~50) -> толпа НЕ аномальна, голоса нет
    snap_lsr={"parts":{"fng":{"ok":True,"value":50,"delta3":0,"delta7":0,"classification":"Neutral"},
                       "lsr":{"ok":True,"ratio":2.5,"long_pct":71.4,"short_pct":28.6,"pct_rank":50.0,"pctl_window":120}}}
    sl=sentiment_analyze(snap_lsr,regime="trend_down")
    assert sl["ok"] and sl["side"]=="flat",sl
    # LSR 4.2 при той же истории (перцентиль 97) -> аномалия, контр-тренд short
    snap_lsr2={"parts":{"fng":{"ok":True,"value":50,"delta3":0,"delta7":0,"classification":"Neutral"},
                        "lsr":{"ok":True,"ratio":4.2,"long_pct":80.8,"short_pct":19.2,"pct_rank":97.0,"pctl_window":120}}}
    sl2=sentiment_analyze(snap_lsr2,regime="trend_down")
    assert sl2["ok"] and sl2["side"]=="short" and any("аномально" in e for e in sl2["evidence"]),sl2
    # перцентиля нет (мало истории) -> фиксированные пороги как fallback
    snap_no_hist={"parts":{"fng":{"ok":True,"value":50,"delta3":0,"delta7":0,"classification":"Neutral"},
                           "lsr":{"ok":True,"ratio":1.6,"long_pct":61.5,"short_pct":38.5,"pctl_window":3}}}
    snh=sentiment_analyze(snap_no_hist,regime="range")
    assert snh["side"]=="short" and any("фиксированные пороги" in e for e in snh["evidence"]),snh
    print("mm_analyst: OK")
if __name__=="__main__":
    import argparse
    ap=argparse.ArgumentParser()
    ap.add_argument("--selftest",action="store_true")
    ap.add_argument("--snap",help="JSON снапшота mm_live")
    ap.add_argument("--klines",help="CSV ts,o,h,l,c,v")
    ap.add_argument("--regime",default="unknown")
    g=ap.parse_args()
    if g.selftest: selftest()
    else:
        import json
        snap=json.load(open(g.snap)) if g.snap else None
        kl=None
        if g.klines:
            import csv
            with open(g.klines,encoding="utf-8",newline="") as fh:
                kl=[[float(x) for x in r[:6]] for r in list(csv.reader(fh))[1:] if r]
        print(json.dumps(analyze_all(snap,kl,regime=g.regime),ensure_ascii=False,indent=1))
