#!/usr/bin/env python3
"""Живой снимок рынка ENA-MIND v6.0: лента/CVD/книга/funding/OI(+история)/LSR + F&G.

Единственная биржа — Bybit V5 (Binance полностью удалён, аудит 2026-11-12).
Все эндпоинты публичные, без API-ключей:
- лента:   GET /v5/market/recent-trade   (поля price/size/side/time — фикс бага v5)
- книга:   GET /v5/market/orderbook      (result.b/result.a)
- дерив.:  GET /v5/market/tickers        (fundingRate, openInterest, markPrice, nextFundingTime)
- LSR:     GET /v5/market/account-ratio  (buyRatio/sellRatio — эквивалент Binance LSR)
- OI ист.: GET /v5/market/open-interest  (intervalTime=1h, oi_pct_change за ~24ч — новое в v6)
- fund. ист.: GET /v5/market/funding/history (перцентиль funding 30д для deriv — новое в v6)
- F&G:     api.alternative.me/fng/?limit=7 (не биржевой, публичный)
- контракты: GET /v5/market/instruments-info (лоты/тик/плечо — Risk Gate и трейд-панель, v6.1)
- лимиты:  GET /v5/market/risk-limit (макс. плечо нижнего тира, v6.1)
Проверено живыми запросами: historical-volatility и mark-price-idx в V5 отдают данные
только для категории option — для linear perpetuals НЕ применяются (честно не используем).
LSR: наблюдения копятся в data/lsr_history.csv; перцентиль своей истории заменяет
фиксированный порог 1.4 (база Bybit ~2.5-3.3 — порог срабатывал почти всегда, v6.1).
Ликвидации: REST-эндпоинта в V5 НЕТ — только WebSocket allLiquidation (500мс).
В REST-снимке помечаются UNAVAILABLE; приложение может передать WS-статистику
через extra_parts (тогда она используется с честной пометкой источника).

Каждая часть снимка получает статус AVAILABLE/UNAVAILABLE/STALE/INSUFFICIENT (mm_data).
Грейд A/C/D сохранён из v4/v5 (обязательные: tape+deriv; LSR/F&G — желательные).
"""
import argparse,json,os,time
import mm_data,mm_config
def _byb(cfg): return cfg.get("sources",{}).get("bybit","https://api.bybit.com")
def _live_dir(): return os.path.join(mm_config.data_dir(),"live")
def _get_simple(url,timeout=8):
    """Простой GET для внешних модулей (mm_alerts). Возвращает (payload, error_str)."""
    pay,_=mm_data.fetch_json(url,timeout=timeout,retries=1)
    return pay,(None if pay is not None else "fetch_failed")
def _st(part):
    """Статус части снимка (для UI и audit)."""
    return part.get("status") if isinstance(part,dict) else "UNAVAILABLE"
def tape_calc(d):
    """Агрессоры, CVD, дельта, дивергенция. d — ответ /v5/market/recent-trade (Bybit V5):
    result.list[] с полями price, size, side(Buy/Sell), time(ms).
    Фикс v6: v5-код читал несуществующие поля p/v/T и падал с KeyError."""
    if not isinstance(d,dict) or d.get("retCode")!=0: return {"ok":False,"status":"UNAVAILABLE","error":"bad_trades"}
    trades=(d.get("result") or {}).get("list") or []
    if not trades: return {"ok":False,"status":"UNAVAILABLE","error":"empty_tape"}
    try:
        buy=sum(float(t["size"]) for t in trades if t.get("side")=="Buy")
        sell=sum(float(t["size"]) for t in trades if t.get("side")=="Sell")
        p0=float(trades[0]["price"]);p1=float(trades[-1]["price"])
        ts_ms=int(trades[-1].get("time") or 0)
    except (TypeError,ValueError,KeyError): return {"ok":False,"status":"UNAVAILABLE","error":"bad_fields"}
    tot=buy+sell;cvd=buy-sell
    return {"ok":True,"status":"AVAILABLE","n":len(trades),"buy_qty":round(buy,4),"sell_qty":round(sell,4),"cvd":round(cvd,4),
            "delta_pct":round(100*cvd/tot,3) if tot else 0.0,
            "price_change_pct":round(100*(p1-p0)/p0,4) if p0 else 0.0,
            "divergence":bool((cvd>0 and p1<p0) or (cvd<0 and p1>p0)),
            "age_sec":round(max(0.0,time.time()-ts_ms/1000.0),1)}
def book_calc(d):
    """Дисбаланс книги. d — ответ /v5/market/orderbook: result.b/result.a = [[price,qty]…].
    Спуфабельно — вторичный признак (правило сохранено из v4/v5)."""
    if not isinstance(d,dict) or d.get("retCode")!=0: return {"ok":False,"status":"UNAVAILABLE","error":"bad_book"}
    r=d.get("result") or {}
    bids=r.get("b") or [];asks=r.get("a") or []
    if not bids and not asks: return {"ok":False,"status":"UNAVAILABLE","error":"empty_book"}
    try:
        b=sum(float(x[1]) for x in bids);a=sum(float(x[1]) for x in asks)
    except (TypeError,ValueError,IndexError): return {"ok":False,"status":"UNAVAILABLE","error":"bad_fields"}
    return {"ok":True,"status":"AVAILABLE","bid_qty":round(b,3),"ask_qty":round(a,3),
            "imbalance_pct":round(100*(b-a)/(b+a),3) if b+a else 0.0,
            "caution":"book imbalance is spoofable; confirm with tape/CVD"}
def deriv_calc(p):
    """funding/mark/OI из /v5/market/tickers?category=linear (Bybit V5)."""
    if not isinstance(p,dict) or p.get("retCode")!=0: return {"ok":False,"status":"UNAVAILABLE","error":"bad_tickers"}
    lst=(p.get("result") or {}).get("list") or []
    if not lst: return {"ok":False,"status":"UNAVAILABLE","error":"empty_list"}
    t=lst[0]
    try:
        fr=float(t.get("fundingRate") or 0)*100  # доля -> %
        oi=float(t.get("openInterest") or 0)     # сумма обеих сторон, в базовой монете
        return {"ok":True,"status":"AVAILABLE","funding_pct":round(fr,5),"oi":oi,
                "mark":float(t.get("markPrice") or 0),"next_funding_ts":t.get("nextFundingTime"),
                "last":float(t.get("lastPrice") or 0)}
    except (TypeError,ValueError): return {"ok":False,"status":"UNAVAILABLE","error":"bad_fields"}
def lsr_calc(p,min_n=None,cfg=None):
    """Long/Short Account Ratio из /v5/market/account-ratio (Bybit V5):
    result.list[] = {buyRatio, sellRatio, timestamp}. Новые сверху, берём последнюю.
    Контракт совпадает с v5 (ratio/long_pct/short_pct/ts) — аналитик не меняется.
    v6.1: pct_rank — перцентиль текущего LSR в 7-дневной почасовой истории САМОГО API
    (limit=168): база LSR у Bybit ~2.5, фиксированный порог 1.4 срабатывал почти всегда
    (вечный контр-тренд short). Перцентиль честно отделяет аномалию от нормы."""
    if not isinstance(p,dict) or p.get("retCode")!=0: return {"ok":False,"status":"UNAVAILABLE","error":"bad_lsr"}
    lst=(p.get("result") or {}).get("list") or []
    if not lst: return {"ok":False,"status":"UNAVAILABLE","error":"empty_lsr"}
    try:
        ratios=[]
        for x in lst:
            b=float(x.get("buyRatio") or 0);s=float(x.get("sellRatio") or 0)
            ratios.append(b/s if s>0 else 0.0)
        last=lst[0]  # актуальная запись (новые сверху)
        ratio=ratios[0]
        longs=float(last.get("buyRatio") or 0)*100
        shorts=float(last.get("sellRatio") or 0)*100
    except (TypeError,ValueError,ZeroDivisionError): return {"ok":False,"status":"UNAVAILABLE","error":"bad_lsr"}
    out={"ok":True,"status":"AVAILABLE","ratio":round(ratio,4),"long_pct":round(longs,2),"short_pct":round(shorts,2),
         "ts":last.get("timestamp")}
    c=cfg or {}
    min_n=int(min_n if min_n is not None else c.get("lsr_hist_min_n",24))
    if len(ratios)>=min_n:
        below=sum(1 for r in ratios if r<=ratio)
        out["pct_rank"]=round(below/len(ratios)*100,1)
        out["pctl_window"]=len(ratios)
    else:
        out["pctl_window"]=len(ratios)
    return out
def instruments_calc(p):
    """Спецификации контракта из /v5/market/instruments-info?category=linear (Bybit V5):
    lotSizeFilter (minOrderQty/qtyStep/minNotionalValue/maxMktOrderQty), priceFilter.tickSize,
    leverageFilter.maxLeverage. v6.1: используется трейд-панелью приложения и обогащает
    конфиг Risk Gate честными лотами инструмента (вместо общих 0.001/100)."""
    if not isinstance(p,dict) or p.get("retCode")!=0: return {"ok":False,"status":"UNAVAILABLE","error":"bad_instruments"}
    lst=(p.get("result") or {}).get("list") or []
    if not lst: return {"ok":False,"status":"UNAVAILABLE","error":"empty_instruments"}
    i=lst[0]
    try:
        lot=i.get("lotSizeFilter") or {};price=i.get("priceFilter") or {};lev=i.get("leverageFilter") or {}
        return {"ok":True,"status":"AVAILABLE","symbol":i.get("symbol"),
                "qty_step":float(lot.get("qtyStep") or 0.001),
                "min_qty":float(lot.get("minOrderQty") or 0.001),
                "min_notional_value":float(lot.get("minNotionalValue") or 5.0),
                "max_mkt_order_qty":float(lot.get("maxMktOrderQty") or 0.0),
                "tick_size":float(price.get("tickSize") or 0.0),
                "max_leverage":float(lev.get("maxLeverage") or 0.0)}
    except (TypeError,ValueError): return {"ok":False,"status":"UNAVAILABLE","error":"bad_fields"}
def risk_limit_calc(p):
    """Лимиты риска из /v5/market/risk-limit?category=linear: нижний тир (isLowestRisk=1)
    задаёт максимальное плечо инструмента. v6.1: капает max_lev в Risk Gate."""
    if not isinstance(p,dict) or p.get("retCode")!=0: return {"ok":False,"status":"UNAVAILABLE","error":"bad_risk_limit"}
    lst=(p.get("result") or {}).get("list") or []
    if not lst: return {"ok":False,"status":"UNAVAILABLE","error":"empty_risk_limit"}
    try:
        tier=next((x for x in lst if str(x.get("isLowestRisk"))=="1"),lst[0])
        return {"ok":True,"status":"AVAILABLE","max_leverage":float(tier.get("maxLeverage") or 0.0),
                "risk_limit_value":float(tier.get("riskLimitValue") or 0.0),
                "maintenance_margin":float(tier.get("maintenanceMargin") or 0.0),"tiers":len(lst)}
    except (TypeError,ValueError,StopIteration): return {"ok":False,"status":"UNAVAILABLE","error":"bad_fields"}
def oi_hist_calc(p,lookback=24):
    """История OI из /v5/market/open-interest (intervalTime=1h): oi_pct_change за ~24ч.
    Новое в v6: поле oi_pct_change схемы SNAPSHOT теперь реально заполняется.
    Изменение отрицательное = OI падает (позиции закрываются)."""
    if not isinstance(p,dict) or p.get("retCode")!=0: return {"ok":False,"status":"UNAVAILABLE","error":"bad_oi_hist"}
    lst=(p.get("result") or {}).get("list") or []
    if len(lst)<2: return {"ok":False,"status":"INSUFFICIENT","error":"short_oi_hist"}
    try:
        ois=[float(x["openInterest"]) for x in lst if x.get("openInterest")]
    except (TypeError,ValueError): return {"ok":False,"status":"UNAVAILABLE","error":"bad_fields"}
    if len(ois)<2 or not ois[-1]: return {"ok":False,"status":"INSUFFICIENT","error":"short_oi_hist"}
    # lst от старых к новым (проверено живым API); окно = последние lookback точек
    win=ois[-lookback:] if len(ois)>=lookback else ois
    prev=win[0];cur=win[-1]
    chg=100.0*(cur-prev)/prev if prev>0 else None
    return {"ok":True,"status":"AVAILABLE","n":len(ois),"oi_now":cur,
            "oi_pct_change":round(chg,3) if chg is not None else None}
def funding_hist_calc(p,min_n=10):
    """История funding из /v5/market/funding/history: список funding_pct (старые->новые)
    для перцентиля в deriv_analyze. Новое в v6: источник подключён (в v5 был «планируемый»)."""
    if not isinstance(p,dict) or p.get("retCode")!=0: return {"ok":False,"status":"UNAVAILABLE","error":"bad_funding_hist"}
    lst=(p.get("result") or {}).get("list") or []
    if len(lst)<min_n: return {"ok":False,"status":"INSUFFICIENT","error":"short_funding_hist","n":len(lst)}
    try:
        hist=sorted([(int(x.get("fundingRateTimestamp") or 0),float(x["fundingRate"])*100) for x in lst])
    except (TypeError,ValueError,KeyError): return {"ok":False,"status":"UNAVAILABLE","error":"bad_fields"}
    return {"ok":True,"status":"AVAILABLE","n":len(hist),"history":[round(v,6) for _,v in hist]}
def fng_calc(p):
    """Fear & Greed с дельтами 3/7д. p — api.alternative.me/fng/?limit=7, данные новые сверху."""
    if not isinstance(p,dict) or not p.get("data"): return {"ok":False,"status":"UNAVAILABLE","error":"empty_fng"}
    data=p["data"]
    try:
        cur=int(data[0]["value"]);ts0=int(data[0]["timestamp"])
        d3=int(data[2]["value"]) if len(data)>2 else cur
        d7=int(data[6]["value"]) if len(data)>6 else cur
        return {"ok":True,"status":"AVAILABLE","value":cur,"ts":ts0,"delta3":cur-d3,"delta7":cur-d7,
                "classification":data[0].get("value_classification","")}
    except (TypeError,ValueError,IndexError,KeyError): return {"ok":False,"status":"UNAVAILABLE","error":"bad_fng"}
def grade_live(parts,cfg=None):
    """Грейд A/C/D. Обязательные: tape+deriv (как в v4/v5). LSR/F&G — желательные."""
    c=cfg or mm_config.load()
    req=[parts.get("tape",{}),parts.get("deriv",{})]
    if any(not x.get("ok") for x in req): return "D"
    tape_age=parts["tape"].get("age_sec",1e9)
    book_ok=parts.get("book",{}).get("ok")
    # sentiment OK — добавляет «зрелости»: A если оба желательных на месте, иначе C
    sent_ok=parts.get("lsr",{}).get("ok") and parts.get("fng",{}).get("ok")
    if tape_age<=120 and book_ok and sent_ok: return "A"
    if tape_age<=120 and book_ok: return "A"
    return "C"
def snapshot(symbol="BTCUSDT",budget_sec=30.0,cfg=None,extra_parts=None):
    """Живой снимок Bybit V5. extra_parts: словарь дополнительных частей из приложения
    (например, liquidations из WebSocket allLiquidation) — помечаются источником 'app'."""
    c=cfg or mm_config.load()
    deadline=time.monotonic()+budget_sec
    def left(): return deadline-time.monotonic()
    def _get(url,pause=0.0):
        if left()<=0.5: return None
        if pause>0 and left()>1.0: time.sleep(min(pause,max(0.0,left()-1.0)))
        pay,_=mm_data.fetch_json(url,timeout=min(8.0,max(0.6,left())),retries=0)
        return pay
    byb=_byb(c)
    q="category=linear&symbol="+symbol
    # Ликвидации: REST-эндпоинта нет — WS-only allLiquidation; статус честно UNAVAILABLE,
    # если приложение не передало WS-статистику через extra_parts.
    t=_get(byb+"/v5/market/recent-trade?"+q+"&limit=500",pause=0.0)
    b=_get(byb+"/v5/market/orderbook?"+q+"&limit=100",pause=0.2)
    p=_get(byb+"/v5/market/tickers?"+q,pause=0.2)
    lsr=_get(byb+"/v5/market/account-ratio?"+q+"&period=1h&limit=%d"%max(8,int(c.get("lsr_api_len",168))),pause=0.2)
    oi_hist=_get(byb+"/v5/market/open-interest?"+q+"&intervalTime=1h&limit=%d"%max(2,int(c.get("oi_hist_len",25))),pause=0.2)
    f_hist=_get(byb+"/v5/market/funding/history?"+q+"&limit=%d"%max(10,int(c.get("funding_hist_len",90))),pause=0.2)
    fng=_get(c.get("sources",{}).get("fng","https://api.alternative.me/fng")+"/?limit=7",pause=0.2)
    # v6.1: спецификации контракта (лоты Risk Gate / трейд-панель) + лимиты риска (плечо)
    instr=_get(byb+"/v5/market/instruments-info?"+q,pause=0.2)
    rlim=_get(byb+"/v5/market/risk-limit?"+q,pause=0.2)
    parts={"tape":tape_calc(t) if t is not None else {"ok":False,"status":"UNAVAILABLE","error":"no_tape"},
           "book":book_calc(b) if b is not None else {"ok":False,"status":"UNAVAILABLE","error":"no_book"},
           "deriv":deriv_calc(p) if p is not None else {"ok":False,"status":"UNAVAILABLE","error":"no_deriv"},
           "lsr":lsr_calc(lsr,cfg=c) if lsr is not None else {"ok":False,"status":"UNAVAILABLE","error":"no_lsr"},
           "oi_hist":oi_hist_calc(oi_hist,int(c.get("oi_hist_len",25))) if oi_hist is not None else {"ok":False,"status":"UNAVAILABLE","error":"no_oi_hist"},
           "funding_hist":funding_hist_calc(f_hist) if f_hist is not None else {"ok":False,"status":"UNAVAILABLE","error":"no_funding_hist"},
           "fng":fng_calc(fng) if fng is not None else {"ok":False,"status":"UNAVAILABLE","error":"no_fng"},
           "instruments":instruments_calc(instr) if instr is not None else {"ok":False,"status":"UNAVAILABLE","error":"no_instruments"},
           "risk_limits":risk_limit_calc(rlim) if rlim is not None else {"ok":False,"status":"UNAVAILABLE","error":"no_risk_limits"},
           "liquidations":{"ok":False,"status":"UNAVAILABLE","note":"WS-only: allLiquidation (REST-эндпоинта в Bybit V5 нет)"}}
    # инъекция WS-частей из приложения (реальные данные, собранные в фоне)
    for k,v in (extra_parts or {}).items():
        if isinstance(v,dict):
            v=dict(v);v["source"]="app"
            parts[k]=v
    # цена: fresh last из tickers, fallback mark, fallback tape
    price=None
    if parts["deriv"].get("ok"):
        price=parts["deriv"].get("last") or parts["deriv"].get("mark")
    if not price and parts["tape"].get("ok") and t:
        try:
            lst=(t.get("result") or {}).get("list") or []
            price=float(lst[-1].get("price") or 0) or None
        except (TypeError,ValueError,IndexError): price=None
    snap={"ts":time.time(),"symbol":symbol,"price":price,
          "funding_pct":parts["deriv"].get("funding_pct") if parts["deriv"].get("ok") else None,
          "oi":parts["deriv"].get("oi") if parts["deriv"].get("ok") else None,
          "oi_pct_change":parts["oi_hist"].get("oi_pct_change") if parts["oi_hist"].get("ok") else None,
          "lsr":parts["lsr"].get("ratio") if parts["lsr"].get("ok") else None,
          "fng":parts["fng"].get("value") if parts["fng"].get("ok") else None,
          "fng_delta3":parts["fng"].get("delta3") if parts["fng"].get("ok") else None,
          "fng_delta7":parts["fng"].get("delta7") if parts["fng"].get("ok") else None,
          "grade":grade_live(parts,c),
          "sources":{k:_st(v) for k,v in parts.items()},
          "parts":parts,"elapsed_sec":round(budget_sec-max(0.0,left()),2)}
    d=mm_config.data_dir();os.makedirs(os.path.join(d,"live"),exist_ok=True)
    with open(os.path.join(d,"live",symbol+"_"+str(int(snap["ts"]))+".json"),"w",encoding="utf-8") as f:
        json.dump(snap,f,ensure_ascii=False)
    return snap
def selftest():
    now_ms=int(time.time()*1000)
    # tape: продавцы давят (фикс полей price/size/side/time)
    bt={"retCode":0,"result":{"list":[{"price":"100","size":"1","side":"Buy","time":str(now_ms-2000)},
                                      {"price":"99","size":"3","side":"Sell","time":str(now_ms-1000)}]}}
    tc=tape_calc(bt)
    assert tc["ok"] and tc["cvd"]==-2.0 and tc["status"]=="AVAILABLE",tc
    assert tc["divergence"] is False
    bt2={"retCode":0,"result":{"list":[{"price":"100","size":"3","side":"Buy","time":str(now_ms-2000)},
                                       {"price":"99","size":"1","side":"Sell","time":str(now_ms-1000)}]}}
    assert tape_calc(bt2)["divergence"] is True
    assert tape_calc([])["ok"] is False           # не ответ биржи
    assert tape_calc({"retCode":1})["ok"] is False
    # v5-баг: старый формат p/v/T больше не используется; новые поля обязательны
    bad={"retCode":0,"result":{"list":[{"p":"100","v":"1","side":"Buy","T":"1"}]}}
    assert tape_calc(bad)["ok"] is False
    # book (b/a)
    bc=book_calc({"retCode":0,"result":{"b":[["1","2"],["2","3"]],"a":[["3","5"]]}})
    assert bc["ok"] and bc["imbalance_pct"]==0.0
    assert book_calc({})["ok"] is False and book_calc({"retCode":0,"result":{}})["ok"] is False
    # deriv (tickers)
    dt={"retCode":0,"result":{"list":[{"fundingRate":"0.0001","openInterest":"100","markPrice":"100.0","lastPrice":"100.1","nextFundingTime":"1794528000000"}]}}
    dc=deriv_calc(dt)
    assert dc["ok"] and abs(dc["funding_pct"]-0.01)<1e-9 and dc["oi"]==100.0 and dc["last"]==100.1
    assert deriv_calc({"retCode":1,"result":{}})["ok"] is False
    # lsr (account-ratio: buyRatio/sellRatio, новые сверху)
    lc=lsr_calc({"retCode":0,"result":{"list":[{"buyRatio":"0.6","sellRatio":"0.4","timestamp":"1700000000000"}]}})
    assert lc["ok"] and lc["ratio"]==1.5 and abs(lc["long_pct"]-60.0)<1e-6,lc
    assert lsr_calc({"retCode":0,"result":{"list":[]}})["ok"] is False
    # v6.1: перцентиль LSR по 7-дневной истории самого API (list из 168 записей;
    # current — list[0]). База ~2.5: ratio 2.5 среди 2.23..2.57 — перцентиль ~90+,
    # а ratio 2.3 — низ перцентиля. Порог lsr_hist_min_n=24: 8 записей -> fallback.
    def _lsr_api(ratios):
        return {"retCode":0,"result":{"list":[
            {"buyRatio":"%.4f"%(r/(1.0+r)),"sellRatio":"%.4f"%(1.0/(1.0+r)),"timestamp":str(i)}
            for i,r in enumerate(ratios)]}}
    # детерминированная решётка 2.23..2.48 (167 точек) — типичная база BTC
    base=[2.23+0.25*(i/166.0) for i in range(167)]
    hi=lsr_calc(_lsr_api([2.55]+base),cfg={"lsr_hist_min_n":24})
    assert hi["ok"] and hi["ratio"]>2.5 and hi.get("pct_rank",0)>=95 and hi["pctl_window"]==168,(hi.get("pct_rank"),hi["pctl_window"])
    lo=lsr_calc(_lsr_api([2.24]+base),cfg={"lsr_hist_min_n":24})
    assert lo["ok"] and lo.get("pct_rank",100)<=10,lo.get("pct_rank")
    mid=lsr_calc(_lsr_api([2.355]+base),cfg={"lsr_hist_min_n":24})
    assert 20.0<=mid.get("pct_rank",-1)<=80.0,mid.get("pct_rank")
    # записей меньше порога -> перцентиля нет (фиксированные пороги в аналитике)
    short8=lsr_calc(_lsr_api([2.5]+base[:7]),cfg={"lsr_hist_min_n":24})
    assert "pct_rank" not in short8 and short8["pctl_window"]==8,short8
    # v6.1: instruments-info (лоты/тик/плечо)
    ic=instruments_calc({"retCode":0,"result":{"list":[{"symbol":"BTCUSDT",
        "lotSizeFilter":{"minOrderQty":"0.001","qtyStep":"0.001","minNotionalValue":"5","maxMktOrderQty":"150"},
        "priceFilter":{"tickSize":"0.10"},
        "leverageFilter":{"maxLeverage":"150.00"}}]}})
    assert ic["ok"] and ic["qty_step"]==0.001 and ic["min_qty"]==0.001 and ic["min_notional_value"]==5.0
    assert ic["tick_size"]==0.1 and ic["max_leverage"]==150.0,ic
    assert instruments_calc({"retCode":1})["ok"] is False
    assert instruments_calc({"retCode":0,"result":{"list":[]}})["ok"] is False
    # v6.1: risk-limit (нижний тир -> максимальное плечо)
    rc=risk_limit_calc({"retCode":0,"result":{"list":[
        {"id":1,"symbol":"BTCUSDT","riskLimitValue":"300000","maxLeverage":"150.00","isLowestRisk":1},
        {"id":2,"symbol":"BTCUSDT","riskLimitValue":"2000000","maxLeverage":"100.00","isLowestRisk":0}]}})
    assert rc["ok"] and rc["max_leverage"]==150.0 and rc["tiers"]==2,rc
    assert risk_limit_calc({"retCode":0,"result":{"list":[]}})["ok"] is False
    # oi_hist: рост/падение OI
    up={"retCode":0,"result":{"list":[{"openInterest":"100","timestamp":"1"},{"openInterest":"110","timestamp":"2"}]}}
    oc=oi_hist_calc(up)
    assert oc["ok"] and oc["oi_pct_change"]==10.0,oc
    dn={"retCode":0,"result":{"list":[{"openInterest":"110","timestamp":"1"},{"openInterest":"100","timestamp":"2"}]}}
    assert oi_hist_calc(dn)["oi_pct_change"]==round(-100.0/11.0,3)
    assert oi_hist_calc({"retCode":0,"result":{"list":[{"openInterest":"100"}]}})["status"]=="INSUFFICIENT"
    # funding_hist
    fh={"retCode":0,"result":{"list":[{"fundingRate":"0.0001","fundingRateTimestamp":"200"},{"fundingRate":"0.0002","fundingRateTimestamp":"100"}]}}
    fc=funding_hist_calc(fh,min_n=2)
    assert fc["ok"] and fc["history"]==[0.02,0.01],fc  # сортировка по ts: старые -> новые
    assert funding_hist_calc({"retCode":0,"result":{"list":[]}})["status"] in ("INSUFFICIENT","UNAVAILABLE")
    # fng: дельты 3/7д
    fng={"data":[{"value":"30","timestamp":"1700000000","value_classification":"Fear"},
                 {"value":"28","timestamp":"1699900000"},
                 {"value":"40","timestamp":"1699800000"},  # 3 дня назад -> d3=30-40=-10
                 {"value":"25","timestamp":"1699700000"},{"value":"22","timestamp":"1699600000"},
                 {"value":"20","timestamp":"1699500000"},
                 {"value":"15","timestamp":"1699400000"}]}  # 7 дней назад -> d7=30-15=15
    f=fng_calc(fng)
    assert f["ok"] and f["value"]==30 and f["delta3"]==-10 and f["delta7"]==15,f
    assert fng_calc({"data":[]})["ok"] is False
    # grade
    full={"tape":{"ok":True,"age_sec":3},"deriv":{"ok":True},"book":{"ok":True},
          "lsr":{"ok":True},"fng":{"ok":True}}
    assert grade_live(full)=="A"
    no_sent={"tape":{"ok":True,"age_sec":3},"deriv":{"ok":True},"book":{"ok":True},
             "lsr":{"ok":False},"fng":{"ok":False}}
    assert grade_live(no_sent)=="A"  # без sentiment всё ещё A (он желательный)
    bad_tape={"tape":{"ok":False},"deriv":{"ok":True}}
    assert grade_live(bad_tape)=="D"
    old_tape={"tape":{"ok":True,"age_sec":200},"deriv":{"ok":True},"book":{"ok":True}}
    assert grade_live(old_tape)=="C"
    # бюджет: при нулевом остатке честно D, не падает
    s=snapshot("BTCUSDT",budget_sec=0)
    assert s["grade"]=="D" and not s["parts"]["tape"]["ok"]
    assert s["sources"]["liquidations"]=="UNAVAILABLE"
    # extra_parts: инъекция WS-ликвидаций из приложения
    s2=snapshot("BTCUSDT",budget_sec=0,extra_parts={"liquidations":{"ok":True,"sum_usd_1h":12345.0,"n_1h":10}})
    assert s2["parts"]["liquidations"].get("source")=="app" and s2["parts"]["liquidations"]["ok"]
    print("mm_live: OK")
if __name__=="__main__":
    ap=argparse.ArgumentParser()
    ap.add_argument("--snapshot",action="store_true");ap.add_argument("--selftest",action="store_true")
    ap.add_argument("--symbol",default="BTCUSDT");ap.add_argument("--budget",type=float,default=30.0)
    g=ap.parse_args()
    if g.selftest: selftest()
    elif g.snapshot: print(json.dumps(snapshot(g.symbol,g.budget),ensure_ascii=False,indent=1))
    else: print("use --snapshot or --selftest")
