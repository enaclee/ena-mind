#!/usr/bin/env python3
"""mm_sources — мультибиржевые и внешние источники данных ENA-MIND v7.0.

Основной источник — Bybit V5 (снимок mm_live сохранён). Здесь — НЕЗАВИСИМЫЕ
источники для кросс-проверки и контекста:
- Binance public market data: data-api.binance.vision (klines/aggTrades/depth/ticker);
- OKX public REST: www.okx.com/api/v5 (ticker/candles/trades/funding/open-interest);
- DefiLlama: api.llama.fi/v2/chains (TVL по цепям + дельта по локальной истории);
- mempool.space: комиссии, размер мемпула, активность блоков (ончейн-контекст BTC).

Правила (ЧАСТЬ 3 промпта 7.0):
- только публичные эндпоинты, без ключей;
- каждый источник: таймауты, повтор с ограничением, кэш (mm_data), проверка
  временных меток и качества, статус AVAILABLE/STALE/UNAVAILABLE/INSUFFICIENT;
- недоступный источник НЕ ломает систему.

Особенности форматов (проверено живыми запросами 2026-11-13):
- Binance aggTrades: m=true (isBuyerMaker) -> сделка инициирована ПРОДАВЦОМ
  (агрессивная продажа); m=false -> агрессивная покупка;
- OKX candles/trades отдают НОВЫЕ СВЕРХУ (нужен reverse);
- mempool.space /api/mempool может отвечать >20с -> длинный таймаут, кэш 10 мин,
  вызывается только из медленного фонового цикла;
- DefiLlama chains отдаёт 400+ цепей (~58КБ) -> кэш 1ч.

Резолвер инструмента (ЧАСТЬ 8): инструмент ищется честно через Bybit
instruments-info: сначала <BASE>USDT, затем <BASE>USDC; найденный тикер
возвращается вместе с тем, какая пара реально торгуется (никакой подмены).
"""
import json,os,time
import mm_data,mm_config

# ---------------- rate limiter (минимальный интервал на хост) ----------------
_LAST_REQ={}
_MIN_INTERVAL={
    "api.bybit.com":0.10,"data-api.binance.vision":0.50,"www.okx.com":0.15,
    "api.llama.fi":0.5,"mempool.space":0.5,"api.alternative.me":0.3,
    "api.coingecko.com":2.0,
}
_BAN={}   # host -> ban до ts (мягкий размыкатель после 418/429)
_BAN_SEC=300.0
def _host(url):
    return url.split("//",1)[-1].split("/",1)[0]
def _rate_wait(url):
    """Честный контроль частоты: не чаще 1 запроса в _MIN_INTERVAL на хост."""
    host=_host(url)
    iv=_MIN_INTERVAL.get(host,0.25)
    now=time.monotonic()
    last=_LAST_REQ.get(host,0.0)
    wait=last+iv-now
    if wait>0: time.sleep(min(wait,2.0))
    _LAST_REQ[host]=time.monotonic()
def fetch(url,timeout=8,retries=1):
    """GET с rate-limit, кэшем, повторами и размыкателем (418/429 -> кулдаун 5 мин).
    (payload, meta) — как mm_data.fetch_json. Недоступный источник не ломает систему."""
    host=_host(url)
    if time.time()<_BAN.get(host,0.0):
        return None,{"url":url,"fetched_at":None,"source":"banned",
                     "attempts":0,"error":"rate-limit cooldown (%s)"%host}
    _rate_wait(url)
    p,m=mm_data.fetch_json(url,timeout=timeout,retries=retries,user_agent="ena-mind/7")
    err=(m or {}).get("error") or ""
    if "418" in err or " 429" in err or " 403" in err or err.startswith("HTTP Error 429") or err.startswith("HTTP Error 403"):
        _BAN[host]=time.time()+_BAN_SEC
    return p,m

def _st(ok,payload_ok,n=None,min_n=None,meta=None,max_age=60.0):
    """Статус части по правилам mm_data.source_status."""
    return mm_data.source_status(meta,max_age=max_age,payload_ok=payload_ok if ok else False,
                                 min_n=min_n,n=n)

# ---------------- конвертация тикеров между биржами ----------------
def split_symbol(symbol):
    """BTCUSDT -> ("BTC","USDT"); BTC-USDT-SWAP -> ("BTC","USDT")."""
    s=(symbol or "").upper()
    if s.endswith("-SWAP"):
        base,quote=s[:-5].rsplit("-",1);return base,quote
    for q in ("USDT","USDC","USD"):
        if s.endswith(q) and len(s)>len(q): return s[:-len(q)],q
    return s,""
def to_binance(symbol): b,_=split_symbol(symbol);return b+"USDT"
def to_okx(symbol): b,_=split_symbol(symbol);return b+"-USDT-SWAP"

# ---------------- Bybit: расширенный набор (мульти-таймфрейм) ----------------
def _byb(cfg): return cfg.get("sources",{}).get("bybit","https://api.bybit.com")
_BYBIT_TF={"1":"1","3":"3","5":"5","15":"15","30":"30","60":"60","240":"240","D":"D"}
_BN_TF={"1":"1m","3":"3m","5":"5m","15":"15m","30":"30m","60":"1h","240":"4h","D":"1d"}
_OKX_TF={"1":"1m","3":"3m","5":"5m","15":"15m","30":"30m","60":"1H","240":"4H","D":"1D"}

def bybit_klines(symbol="BTCUSDT",interval="1",limit=200,cfg=None,end_ts=None):
    """Свечи Bybit V5 (+ fallback Binance/OKX при блокировке): [[ts,o,h,l,c,v]...] старые->новые."""
    c=cfg or mm_config.load()
    iv_key=str(interval)
    iv=_BYBIT_TF.get(iv_key,"1")
    q="category=linear&symbol=%s&interval=%s&limit=%d"%(symbol,iv,min(int(limit),1000))
    if end_ts: q+="&end=%d"%int(end_ts)
    p,m=fetch("%s/v5/market/kline?%s"%(_byb(c),q))
    if isinstance(p,dict) and p.get("retCode")==0:
        rows=[]
        for r in ((p.get("result") or {}).get("list") or []):
            try: rows.append([int(r[0]),float(r[1]),float(r[2]),float(r[3]),float(r[4]),float(r[5])])
            except (TypeError,ValueError,IndexError): continue
        rows.reverse()  # Bybit отдаёт новые сверху
        if len(rows)>=2:
            return {"ok":True,"status":_st(True,True,n=len(rows),min_n=2,meta=m),"rows":rows,
                    "source":"bybit"}
    # Fallback 1: Binance Vision
    bn_url="https://data-api.binance.vision/api/v3/klines?symbol=%s&interval=%s&limit=%d"%(
        to_binance(symbol),_BN_TF.get(iv_key,"1m"),min(int(limit),1000))
    if end_ts: bn_url+="&endTime=%d"%int(end_ts)
    pb,mb=fetch(bn_url)
    if isinstance(pb,list) and pb:
        rows=[]
        for r in pb:
            try: rows.append([int(r[0]),float(r[1]),float(r[2]),float(r[3]),float(r[4]),float(r[5])])
            except (TypeError,ValueError,IndexError): continue
        if len(rows)>=2:
            return {"ok":True,"status":_st(True,True,n=len(rows),min_n=2,meta=mb),"rows":rows,
                    "source":"binance"}
    # Fallback 2: OKX Public
    if not end_ts:
        okx=okx_candles(symbol,bar=_OKX_TF.get(iv_key,"1m"),limit=min(int(limit),300))
        if okx.get("ok"): return okx
    return {"ok":False,"status":"UNAVAILABLE","rows":[]}

def resolve_instrument(base,cfg=None):
    """Честный резолвер инструмента (ЧАСТЬ 8): <BASE>USDT, затем <BASE>USDC.
    Возвращает реально торгующийся тикер + метаданные; is_fallback помечает,
    что основная пара не найдена и используется альтернативная (не подмена)."""
    c=cfg or mm_config.load()
    tried=[]
    for quote in ("USDT","USDC"):
        sym=(base+quote).upper()
        p,m=fetch("%s/v5/market/instruments-info?category=linear&symbol=%s"%(_byb(c),sym))
        tried.append(sym)
        if isinstance(p,dict) and p.get("retCode")==0:
            lst=(p.get("result") or {}).get("list") or []
            if lst:
                i=lst[0]
                try:
                    lot=i.get("lotSizeFilter") or {};price=i.get("priceFilter") or {}
                    return {"ok":True,"status":"AVAILABLE","symbol":i.get("symbol"),
                            "quote":quote,"is_fallback":quote!="USDT","tried":tried,
                            "qty_step":float(lot.get("qtyStep") or 0.0),
                            "min_qty":float(lot.get("minOrderQty") or 0.0),
                            "tick_size":float(price.get("tickSize") or 0.0)}
                except (TypeError,ValueError): pass
    return {"ok":False,"status":"UNAVAILABLE","tried":tried}

# ---------------- Binance public market data ----------------
def binance_klines(symbol="BTCUSDT",interval="1m",limit=200):
    """Свечи Binance (data-api.binance.vision): [openTime,o,h,l,c,v,...] -> унифицированные."""
    p,m=fetch("https://data-api.binance.vision/api/v3/klines?symbol=%s&interval=%s&limit=%d"%(
        to_binance(symbol),interval,limit))
    if not isinstance(p,list) or not p:
        return {"ok":False,"status":"UNAVAILABLE","rows":[]}
    rows=[]
    for r in p:
        try: rows.append([int(r[0]),float(r[1]),float(r[2]),float(r[3]),float(r[4]),float(r[5])])
        except (TypeError,ValueError,IndexError): continue
    if len(rows)<2: return {"ok":False,"status":"INSUFFICIENT","rows":rows}
    return {"ok":True,"status":_st(True,True,n=len(rows),min_n=2,meta=m),"rows":rows,
            "source":"binance"}

def binance_flow(symbol="BTCUSDT",limit=500):
    """Агрессивный поток из aggTrades. m=true (buyer is maker) -> агрессивная ПРОДАЖА."""
    p,m=fetch("https://data-api.binance.vision/api/v3/aggTrades?symbol=%s&limit=%d"%(
        to_binance(symbol),limit))
    if not isinstance(p,list) or not p:
        return {"ok":False,"status":"UNAVAILABLE"}
    buy=sell=0.0;last_ts=0
    for t in p:
        try:
            q=float(t.get("q") or 0);ts=int(t.get("T") or 0)
            if t.get("m"): sell+=q     # покупатель = maker -> продавец агрессор
            else: buy+=q
            last_ts=max(last_ts,ts)
        except (TypeError,ValueError): continue
    tot=buy+sell
    if tot<=0 or not p: return {"ok":False,"status":"INSUFFICIENT"}
    cvd=buy-sell
    return {"ok":True,"status":_st(True,True,n=len(p),min_n=10,meta=m),
            "n":len(p),"buy_qty":round(buy,4),"sell_qty":round(sell,4),"cvd":round(cvd,4),
            "delta_pct":round(100*cvd/tot,3),"age_sec":round(max(0.0,time.time()-last_ts/1000.0),1),
            "source":"binance"}

def binance_depth(symbol="BTCUSDT",limit=100):
    """Стакан Binance: bids/asks=[[price,qty]...] -> имбаланс/спред."""
    p,m=fetch("https://data-api.binance.vision/api/v3/depth?symbol=%s&limit=%d"%(
        to_binance(symbol),limit))
    if not isinstance(p,dict) or not p.get("bids"):
        return {"ok":False,"status":"UNAVAILABLE"}
    try:
        bids=[(float(x[0]),float(x[1])) for x in p["bids"]]
        asks=[(float(x[0]),float(x[1])) for x in p["asks"]]
        b=sum(q for _,q in bids);a=sum(q for _,q in asks)
        best_bid=bids[0][0] if bids else 0.0;best_ask=asks[0][0] if asks else 0.0
        spread_pct=round(100*(best_ask-best_bid)/best_bid,4) if best_bid>0 else None
        return {"ok":True,"status":_st(True,True,meta=m),
                "bid_qty":round(b,3),"ask_qty":round(a,3),
                "imbalance_pct":round(100*(b-a)/(b+a),3) if b+a>0 else 0.0,
                "best_bid":best_bid,"best_ask":best_ask,"spread_pct":spread_pct,
                "source":"binance"}
    except (TypeError,ValueError,IndexError):
        return {"ok":False,"status":"UNAVAILABLE"}

def binance_ticker(symbol="BTCUSDT"):
    """24ч тикер Binance: последняя цена и объём (для кросс-биржевого сравнения)."""
    p,m=fetch("https://data-api.binance.vision/api/v3/ticker/24hr?symbol=%s"%to_binance(symbol))
    if not isinstance(p,dict) or not p.get("lastPrice"):
        return {"ok":False,"status":"UNAVAILABLE"}
    try:
        return {"ok":True,"status":_st(True,True,meta=m),
                "last":float(p["lastPrice"]),
                "volume":float(p.get("volume") or 0.0),
                "quote_volume":float(p.get("quoteVolume") or 0.0),
                "price_change_pct":float(p.get("priceChangePercent") or 0.0),
                "source":"binance"}
    except (TypeError,ValueError):
        return {"ok":False,"status":"UNAVAILABLE"}

_WHALE_CACHE={"ts":0.0,"by_sym":{}}
def binance_whales(symbol="BTCUSDT",limit=1000,min_usd=100000.0,cache_sec=60.0):
    """v8.0 КИТЫ: крупные сделки из aggTrades Binance (>= min_usd за сделку).
    Считаем направление китовой агрессии: buy_usd vs sell_usd и топ-сделки.
    Кэш 60с — вызывается из редкого цикла движка, не из 5с-конвейера."""
    now=time.time()
    c=_WHALE_CACHE["by_sym"].get(symbol)
    if c and now-_WHALE_CACHE["ts"]<cache_sec and now-c.get("ts",0)<cache_sec:
        return c
    p,m=fetch("https://data-api.binance.vision/api/v3/aggTrades?symbol=%s&limit=%d"%(
        to_binance(symbol),min(int(limit),1000)))
    if not isinstance(p,list) or not p:
        return {"ok":False,"status":"UNAVAILABLE"}
    buy=sell=0.0;top=[]
    max_usd=0.0
    for t in p:
        try:
            q=float(t.get("q") or 0);px=float(t.get("p") or 0);ts=int(t.get("T") or 0)
        except (TypeError,ValueError): continue
        usd=q*px
        max_usd=max(max_usd,usd)
        if usd<min_usd: continue
        if t.get("m"): sell+=usd   # покупатель = maker -> агрессивная ПРОДАЖА кита
        else: buy+=usd
        top.append({"usd":round(usd,0),"side":"SELL" if t.get("m") else "BUY",
                    "price":px,"ts":ts})
    tot=buy+sell
    top=sorted(top,key=lambda x:-x["usd"])[:10]
    if tot<=0:
        out={"ok":False,"status":"INSUFFICIENT",
             "note":"крупных сделок >= $%.0fK в последних %d нет (максимум $%.0fK)"%(
                 min_usd/1000,len(p),max_usd/1000),
             "max_usd":round(max_usd,0),"n_trades":len(p),"ts":now}
    else:
        out={"ok":True,"status":_st(True,True,meta=m,max_age=120.0),
             "n_whales":len(top),"buy_usd":round(buy,0),"sell_usd":round(sell,0),
             "delta_usd":round(buy-sell,0),
             "delta_pct":round(100*(buy-sell)/tot,2),
             "max_usd":round(max_usd,0),
             "top":top,"n_trades":len(p),"ts":now,"source":"binance_aggtrades"}
    _WHALE_CACHE["ts"]=now;_WHALE_CACHE["by_sym"][symbol]=out
    return out

# ---------------- CoinGecko: глобальный макро-контекст ----------------
_MACRO_CACHE={"ts":0.0,"data":None}
def coingecko_global(cache_sec=600.0):
    """v8.0 МАКРО: доминирование BTC (+дельта по локальной истории снапшотов),
    объём рынка и его 24ч-изменение (CoinGecko /global, публичный, без ключа).
    Кэш 10 минут — редкий фоновый вызов. Дельта доминирования честно считается
    по собственным точкам за последние сутки (пока истории нет — None)."""
    now=time.time()
    if _MACRO_CACHE["data"] and now-_MACRO_CACHE["ts"]<cache_sec:
        return _MACRO_CACHE["data"]
    p,m=fetch("https://api.coingecko.com/api/v3/global",timeout=10,retries=0)
    if not isinstance(p,dict) or not isinstance(p.get("data"),dict):
        return {"ok":False,"status":"UNAVAILABLE"}
    try:
        d=p["data"]
        mcap=d.get("total_market_cap") or {}
        dom=d.get("market_cap_percentage") or {}
        vol=d.get("total_volume") or {}
        btc_dom=round(float(dom.get("btc") or 0.0),3)
        eth_dom=round(float(dom.get("eth") or 0.0),3)
        out={"ok":True,"status":_st(True,True,meta=m,max_age=900.0),
             "total_mcap_usd":float(mcap.get("usd") or 0.0),
             "mcap_change_24h_pct":round(float(d.get("market_cap_change_percentage_24h_usd") or 0.0),2),
             "total_volume_usd":float(vol.get("usd") or 0.0),
             "btc_dominance":btc_dom,"eth_dominance":eth_dom,
             "ts":now,"source":"coingecko"}
        # локальная история доминирования (точка не чаще раза в час)
        hist_path=os.path.join(mm_config.data_dir(),"dominance_history.json")
        hist=[]
        try:
            if os.path.exists(hist_path):
                with open(hist_path,encoding="utf-8") as f: hist=json.load(f)
        except Exception: hist=[]
        if not hist or now-hist[-1].get("ts",0)>=3600.0:
            hist.append({"ts":now,"btc":btc_dom})
        hist=[h for h in hist if now-h.get("ts",0)<4*86400]
        try:
            os.makedirs(os.path.dirname(hist_path),exist_ok=True)
            with open(hist_path,"w",encoding="utf-8") as f: json.dump(hist,f)
        except Exception: pass
        dom_d24=None;dom_d1=None
        for h in hist:
            age=now-h.get("ts",0)
            if 82800<=age<=90000 and h.get("btc") is not None:
                dom_d24=round(btc_dom-h["btc"],3)
            if 3300<=age<=3900 and h.get("btc") is not None:
                dom_d1=round(btc_dom-h["btc"],3)
        out["btc_dom_delta_24h"]=dom_d24
        out["btc_dom_delta_1h"]=dom_d1
        out["dom_history_points"]=len(hist)
    except (TypeError,ValueError):
        return {"ok":False,"status":"UNAVAILABLE"}
    _MACRO_CACHE["ts"]=now;_MACRO_CACHE["data"]=out
    return out

# ---------------- OKX public ----------------
def okx_ticker(symbol="BTCUSDT"):
    p,m=fetch("https://www.okx.com/api/v5/market/ticker?instId=%s"%to_okx(symbol))
    if not isinstance(p,dict) or p.get("code")!="0" or not p.get("data"):
        return {"ok":False,"status":"UNAVAILABLE"}
    try:
        t=p["data"][0]
        return {"ok":True,"status":_st(True,True,meta=m),
                "last":float(t.get("last") or 0.0),
                "volume24h":float(t.get("vol24h") or 0.0),
                "vol_ccy24h":float(t.get("volCcy24h") or 0.0),
                "source":"okx"}
    except (TypeError,ValueError):
        return {"ok":False,"status":"UNAVAILABLE"}

def okx_candles(symbol="BTCUSDT",bar="1m",limit=200):
    """Свечи OKX — НОВЫЕ СВЕРХУ, разворачиваем. [ts,o,h,l,c,vol,...]"""
    p,m=fetch("https://www.okx.com/api/v5/market/candles?instId=%s&bar=%s&limit=%d"%(
        to_okx(symbol),bar,limit))
    if not isinstance(p,dict) or p.get("code")!="0" or not p.get("data"):
        return {"ok":False,"status":"UNAVAILABLE","rows":[]}
    rows=[]
    for r in p["data"]:
        try: rows.append([int(r[0]),float(r[1]),float(r[2]),float(r[3]),float(r[4]),float(r[5])])
        except (TypeError,ValueError,IndexError): continue
    rows.reverse()  # OKX отдаёт новые сверху
    if len(rows)<2: return {"ok":False,"status":"INSUFFICIENT","rows":rows}
    return {"ok":True,"status":_st(True,True,n=len(rows),min_n=2,meta=m),"rows":rows,
            "source":"okx"}

def okx_flow(symbol="BTCUSDT",limit=400):
    """Агрессивный поток OKX: side=buy/sell у сделки уже означает агрессора."""
    p,m=fetch("https://www.okx.com/api/v5/market/trades?instId=%s&limit=%d"%(
        to_okx(symbol),limit))
    if not isinstance(p,dict) or p.get("code")!="0" or not p.get("data"):
        return {"ok":False,"status":"UNAVAILABLE"}
    buy=sell=0.0;last_ts=0
    for t in p["data"]:
        try:
            sz=float(t.get("sz") or 0);ts=int(t.get("ts") or 0)
            if str(t.get("side")).lower()=="buy": buy+=sz
            else: sell+=sz
            last_ts=max(last_ts,ts)
        except (TypeError,ValueError): continue
    tot=buy+sell
    if tot<=0: return {"ok":False,"status":"INSUFFICIENT"}
    cvd=buy-sell
    return {"ok":True,"status":_st(True,True,n=len(p["data"]),min_n=10,meta=m),
            "buy_qty":round(buy,4),"sell_qty":round(sell,4),"cvd":round(cvd,4),
            "delta_pct":round(100*cvd/tot,3),"age_sec":round(max(0.0,time.time()-last_ts/1000.0),1),
            "source":"okx"}

def okx_funding(symbol="BTCUSDT"):
    p,m=fetch("https://www.okx.com/api/v5/public/funding-rate?instId=%s"%to_okx(symbol))
    if not isinstance(p,dict) or p.get("code")!="0" or not p.get("data"):
        return {"ok":False,"status":"UNAVAILABLE"}
    try:
        d=p["data"][0]
        return {"ok":True,"status":_st(True,True,meta=m,max_age=300.0),
                "funding_pct":round(float(d.get("fundingRate") or 0)*100,6),
                "next_funding_ts":int(d.get("fundingTime") or 0),"source":"okx"}
    except (TypeError,ValueError):
        return {"ok":False,"status":"UNAVAILABLE"}

def okx_oi(symbol="BTCUSDT"):
    p,m=fetch("https://www.okx.com/api/v5/public/open-interest?instId=%s"%to_okx(symbol))
    if not isinstance(p,dict) or p.get("code")!="0" or not p.get("data"):
        return {"ok":False,"status":"UNAVAILABLE"}
    try:
        d=p["data"][0]
        return {"ok":True,"status":_st(True,True,meta=m,max_age=120.0),
                "oi":float(d.get("oi") or 0.0),"oi_usd":float(d.get("oiCcy") or 0.0),
                "source":"okx"}
    except (TypeError,ValueError):
        return {"ok":False,"status":"UNAVAILABLE"}

# ---------------- Deribit: опционы (v7.2, агент 13) ----------------
_OPT_CACHE={"ts":0.0,"by_cur":{}}

def _parse_deribit_book(res,now_d):
    """Чистый парсер книги опционов (офлайн-тестируемый). res — список строк
    get_book_summary_by_currency. Возвращает агрегат ближайшей живой экспирации:
    PCR по OI, перекос IV put/call (OTM-крылья), спот, число инструментов."""
    import datetime
    exp_map={};spot=None
    for it in res:
        if not isinstance(it,dict): continue
        name=it.get("instrument_name") or ""
        parts=name.split("-")
        if len(parts)!=4: continue
        try: exp=datetime.datetime.strptime(parts[1],"%d%b%y")
        except ValueError: continue
        if exp<now_d+datetime.timedelta(days=1): continue  # ближайшая живая
        if spot is None:
            u=it.get("underlying_price")
            if isinstance(u,(int,float)) and u>0: spot=float(u)
        exp_map.setdefault(exp,[]).append(it)
    if not exp_map: return {"ok":False,"status":"INSUFFICIENT"}
    exp=min(exp_map);rows=exp_map[exp]
    put_oi=call_oi=0.0;put_iv=[];call_iv=[]
    for it in rows:
        name=it.get("instrument_name") or ""
        parts=name.split("-")
        if len(parts)!=4: continue
        try: strike=float(parts[2])
        except ValueError: continue
        typ=parts[3]
        try: oi=float(it.get("open_interest") or 0.0)
        except (TypeError,ValueError): oi=0.0
        iv=None
        for k in ("implied_vol","mark_iv"):
            v=it.get(k)
            if isinstance(v,(int,float)) and v>0: iv=float(v);break
        if typ=="P":
            put_oi+=oi
            if iv is not None and spot and strike<spot*0.97: put_iv.append(iv)
        elif typ=="C":
            call_oi+=oi
            if iv is not None and spot and strike>spot*1.03: call_iv.append(iv)
    pcr=put_oi/call_oi if call_oi>0 else None
    skew=None
    if put_iv and call_iv:
        pm=sum(put_iv)/len(put_iv);cm=sum(call_iv)/len(call_iv)
        if cm>0: skew=(pm-cm)/cm
    return {"ok":True,"status":"AVAILABLE","pcr_oi":round(pcr,3) if pcr is not None else None,
            "put_iv":round(sum(put_iv)/len(put_iv),2) if put_iv else None,
            "call_iv":round(sum(call_iv)/len(call_iv),2) if call_iv else None,
            "skew":round(skew,4) if skew is not None else None,
            "spot":spot,"expiry":exp.strftime("%Y-%m-%d"),"n_instruments":len(rows)}

def deribit_options(symbol="BTCUSDT"):
    """Опционный контекст Deribit (публичное): DVOL (IV-индекс) сейчас и среднее
    за 7 дней + сводка ближайшей экспирации (PCR по OI, перекос IV put/call).
    Кэш 10 минут — 5-секундный цикл движка сеть не дёргает."""
    base=symbol[:-4] if symbol.endswith("USDT") else symbol
    if base not in ("BTC","ETH"): return {"ok":False,"status":"INSUFFICIENT"}
    now=time.time()
    if now-_OPT_CACHE["ts"]<600.0 and _OPT_CACHE["by_cur"].get(base):
        d=dict(_OPT_CACHE["by_cur"][base]);d["cached"]=True;return d
    import datetime
    end=int(now*1000);start=end-7*24*3600*1000
    p,_=fetch("https://www.deribit.com/api/v2/public/get_volatility_index_data"
              "?currency=%s&start_timestamp=%d&end_timestamp=%d&resolution=3600"%(base,start,end))
    iv_now=iv_mean=None
    data=((p or {}).get("result") or {}).get("data") or []
    ivs=[float(r[4]) for r in data if isinstance(r,(list,tuple)) and len(r)>=5]
    if ivs: iv_now=round(ivs[-1],2);iv_mean=round(sum(ivs)/len(ivs),2)
    p2,_=fetch("https://www.deribit.com/api/v2/public/get_book_summary_by_currency"
               "?currency=%s&kind=option"%base)
    res=((p2 or {}).get("result") or [])
    agg=_parse_deribit_book(res,datetime.datetime.utcnow())
    if iv_now is None and not agg.get("ok"):
        return {"ok":False,"status":"UNAVAILABLE"}
    out={"ok":True,"status":"AVAILABLE","iv_now":iv_now,"iv_mean_7d":iv_mean,
         "source":"deribit"}
    for k in ("pcr_oi","put_iv","call_iv","skew","spot","expiry","n_instruments"):
        if agg.get(k) is not None: out[k]=agg[k]
    _OPT_CACHE["ts"]=now;_OPT_CACHE["by_cur"][base]=out
    return out

# ---------------- DefiLlama: TVL + дельта ликвидности ----------------
def defillama_tvl(top_n=12):
    """TVL по цепям + изменение ликвидности. Дельта считается по ЛОКАЛЬНОЙ истории
    снапшотов (data/defillama_history.json): каждый вызов (не чаще 1ч) дописывает
    точку. История не фальшивится: если точки нет — delta=None (INSUFFICIENT)."""
    p,m=fetch("https://api.llama.fi/v2/chains",timeout=15,retries=0)
    if not isinstance(p,list) or not p:
        return {"ok":False,"status":"UNAVAILABLE"}
    chains=sorted(({"name":str(c.get("name")),"tvl":float(c.get("tvl") or 0.0)} for c in p
                   if isinstance(c,dict) and (c.get("tvl") or 0)>0),
                  key=lambda x:-x["tvl"])[:top_n]
    total=sum(x["tvl"] for x in chains)
    # локальная история снапшотов (append не чаще раза в час)
    hist_path=os.path.join(mm_config.data_dir(),"defillama_history.json")
    now=time.time();delta_24h=None;delta_1h=None
    hist=[]
    try:
        if os.path.exists(hist_path):
            with open(hist_path,encoding="utf-8") as f: hist=json.load(f)
    except Exception: hist=[]
    if hist and now-hist[-1].get("ts",0)>=3600.0:
        hist.append({"ts":now,"total":total})
    elif not hist:
        hist=[{"ts":now,"total":total}]
    hist=[h for h in hist if now-h.get("ts",0)<8*86400]  # окно истории 8 дней
    try:
        with open(hist_path,"w",encoding="utf-8") as f: json.dump(hist,f)
    except Exception: pass
    for h in hist:
        age=now-h.get("ts",0)
        if 82800<=age<=90000 and h.get("total"): delta_24h=round(100*(total-h["total"])/h["total"],3)
        if 3300<=age<=3900 and h.get("total"): delta_1h=round(100*(total-h["total"])/h["total"],3)
    status=_st(True,True,meta=m,max_age=3600.0)
    if delta_24h is None and (not hist or len(hist)<2): status="INSUFFICIENT"
    return {"ok":True,"status":status,"total_tvl":round(total,0),"chains":chains,
            "delta_24h_pct":delta_24h,"delta_1h_pct":delta_1h,"history_points":len(hist),
            "source":"defillama"}

# ---------------- mempool.space: ончейн-контекст BTC ----------------
def mempool_stats():
    """Ончейн-контекст BTC: комиссии, мемпул, активность блоков.

    v8.2 (жallo-тест 2026-11-13): mempool.space из мобильных сетей регулярно
    недоступен/висит >90с. Основной источник теперь blockstream.info (быстрый,
    официальный эксплорер), mempool.space — только fallback для деталей.
    Все таймауты короткие (6-8с): движок не блокируется."""
    out={"ok":False,"status":"UNAVAILABLE","source":"blockstream"}
    # комиссии: blockstream fee-estimates {"3":sat/vB,...} -> fastestFee
    fees,fm=fetch("https://blockstream.info/api/fee-estimates",timeout=6,retries=0)
    f_ok=False
    if isinstance(fees,dict) and fees:
        try:
            out["fee_fast"]=int(fees.get("3") or fees.get("2") or 0)
            out["fee_30m"]=int(fees.get("6") or 0)
            out["fee_1h"]=int(fees.get("12") or fees.get("25") or 0)
            f_ok=out["fee_fast"]>0
        except (TypeError,ValueError): f_ok=False
    if not f_ok:
        # fallback: mempool.space fees (короткий таймаут)
        fees2,fm2=fetch("https://mempool.space/api/v1/fees/recommended",timeout=6,retries=0)
        if isinstance(fees2,dict) and fees2.get("fastestFee") is not None:
            out["fee_fast"]=fees2.get("fastestFee")
            out["fee_30m"]=fees2.get("halfHourFee")
            out["fee_1h"]=fees2.get("hourFee")
            f_ok=True;fm=fm2
    # блоки: blockstream (быстро), fallback mempool
    blocks,bm=fetch("https://blockstream.info/api/blocks",timeout=6,retries=0)
    if not (isinstance(blocks,list) and blocks):
        blocks,bm=fetch("https://mempool.space/api/v1/blocks",timeout=6,retries=0)
    b_ok=isinstance(blocks,list) and bool(blocks)
    # мемпул: только mempool.space и только с коротким таймаутом (опционально)
    mem,mm_=fetch("https://mempool.space/api/mempool",timeout=8,retries=0)
    if not (f_ok or b_ok): return out
    out["ok"]=True
    if f_ok:
        out["fee_fast"]=fees.get("fastestFee");out["fee_30m"]=fees.get("halfHourFee")
        out["fee_1h"]=fees.get("hourFee")
    if b_ok:
        try:
            last=blocks[0]
            tx_counts=[int(x.get("tx_count") or 0) for x in blocks[:6]]
            out["last_block_height"]=last.get("height")
            out["last_block_tx"]=int(last.get("tx_count") or 0)
            out["avg_block_tx_6"]=round(sum(tx_counts)/max(1,len(tx_counts)),1)
            # скорость блоков: медианный интервал из последних блоков
            ts=[int(x.get("timestamp") or 0) for x in blocks[:6] if x.get("timestamp")]
            if len(ts)>=3:
                import statistics
                gaps=[ts[i]-ts[i+1] for i in range(len(ts)-1)]
                out["median_block_sec"]=round(statistics.median(gaps),1)
        except (TypeError,ValueError): pass
    if isinstance(mem,dict) and mem.get("count") is not None:
        out["mempool_tx"]=int(mem.get("count") or 0)
        out["mempool_vsize"]=int(mem.get("vsize") or 0)
        out["status"]=_st(True,True,meta=mm_,max_age=600.0)
    else:
        out["mempool_tx"]=None;out["mempool_vsize"]=None
        out["status"]=_st(True,True,meta=fm,max_age=600.0)
    return out

# ---------------- кросс-биржевая сводка (вход агента CROSS-EXCHANGE) ----------------
def cross_exchange(symbol="BTCUSDT",cfg=None):
    """Независимое подтверждение движения: цены/потоки/funding Bybit vs Binance vs OKX.
    Быстрые запросы; один недоступный источник не ломает сводку (его часть UNAVAILABLE)."""
    parts={}
    c=cfg or mm_config.load()
    p,m=fetch("%s/v5/market/tickers?category=linear&symbol=%s"%(_byb(c),symbol))
    if isinstance(p,dict) and p.get("retCode")==0:
        try:
            t=(p.get("result") or {}).get("list")[0]
            parts["bybit"]={"ok":True,"status":_st(True,True,meta=m),
                            "last":float(t.get("lastPrice") or 0.0),
                            "funding_pct":round(float(t.get("fundingRate") or 0)*100,6),
                            "oi":float(t.get("openInterest") or 0.0),
                            "turnover24h":float(t.get("turnover24h") or 0.0)}
        except (TypeError,ValueError,IndexError):
            parts["bybit"]={"ok":False,"status":"UNAVAILABLE"}
    else:
        parts["bybit"]={"ok":False,"status":"UNAVAILABLE"}
    parts["binance_price"]=binance_ticker(symbol)
    parts["binance_flow"]=binance_flow(symbol)
    parts["okx_price"]=okx_ticker(symbol)
    parts["okx_flow"]=okx_flow(symbol)
    parts["okx_funding"]=okx_funding(symbol)
    parts["okx_oi"]=okx_oi(symbol)
    # спреды и подтверждения (считаем по честно доступным парам)
    diffs={}
    by=parts["bybit"].get("last") if parts["bybit"].get("ok") else None
    bn=parts["binance_price"].get("last") if parts["binance_price"].get("ok") else None
    ox=parts["okx_price"].get("last") if parts["okx_price"].get("ok") else None
    if by and bn:
        diffs["bybit_binance_bps"]=round((bn-by)/by*10000,2)
    if by and ox:
        diffs["bybit_okx_bps"]=round((ox-by)/by*10000,2)
    if bn and ox:
        diffs["binance_okx_bps"]=round((ox-bn)/bn*10000,2)
    n_avail=sum(1 for k in ("bybit","binance_price","okx_price") if parts.get(k,{}).get("ok"))
    return {"symbol":symbol,"parts":parts,"diffs":diffs,"n_exchanges":n_avail,
            "ts":time.time()}

# ---------------- медленный деривативный контекст для приложения ----------------
def deriv_backfill(symbol="BTCUSDT",cfg=None):
    """Тяжёлые редкие части деривативов (раз в ~30 мин из приложения):
    история funding (для перцентиля), LSR + 7д-перцентиль, OI-история 24ч.
    Реиспользует проверенные парсеры mm_live — сырые ответы те же эндпоинты."""
    import mm_live
    c=cfg or mm_config.load()
    byb=_byb(c)
    q="category=linear&symbol="+symbol
    fh_p,_=fetch(byb+"/v5/market/funding/history?"+q+"&limit=%d"%max(10,int(c.get("funding_hist_len",90))))
    lsr_p,_=fetch(byb+"/v5/market/account-ratio?"+q+"&period=1h&limit=%d"%max(8,int(c.get("lsr_api_len",168))))
    oi_p,_=fetch(byb+"/v5/market/open-interest?"+q+"&intervalTime=1h&limit=%d"%max(2,int(c.get("oi_hist_len",25))))
    fh=mm_live.funding_hist_calc(fh_p) if fh_p is not None else {"ok":False,"status":"UNAVAILABLE"}
    lsr=mm_live.lsr_calc(lsr_p,cfg=c) if lsr_p is not None else {"ok":False,"status":"UNAVAILABLE"}
    oi=mm_live.oi_hist_calc(oi_p) if oi_p is not None else {"ok":False,"status":"UNAVAILABLE"}
    return {"funding_hist":fh,"lsr":lsr,"oi_hist":oi,"ts":time.time()}

# ---------------- медленный контекст (DefiLlama + mempool, фон) ----------------
def slow_context():
    """ТВЛ DeFi + ончейн BTC. Вызывается редко (>=10 мин); любой отказ не критичен."""
    out={"ts":time.time()}
    try: out["defillama"]=defillama_tvl()
    except Exception as e: out["defillama"]={"ok":False,"status":"UNAVAILABLE","error":str(e)[:120]}
    try: out["mempool"]=mempool_stats()
    except Exception as e: out["mempool"]={"ok":False,"status":"UNAVAILABLE","error":str(e)[:120]}
    return out

def selftest():
    """Offline: конвертация тикеров + статусы на мок-ответах. Живые проверки —
    tests/integration (флаг --live), здесь сеть не трогаем."""
    assert split_symbol("BTCUSDT")==("BTC","USDT")
    assert split_symbol("BTCUSDC")==("BTC","USDC")
    assert split_symbol("BTC-USDT-SWAP")==("BTC","USDT")
    assert split_symbol("SOLUSDT")==("SOL","USDT")
    assert to_binance("BTCUSDC")=="BTCUSDT"
    assert to_okx("BTCUSDT")=="BTC-USDT-SWAP"
    assert to_okx("SOL-USDT-SWAP")=="SOL-USDT-SWAP"
    # rate limiter: два вызова подряд на один хост дают интервал (мокаем сеть в selftest)
    import unittest.mock as _mock
    with _mock.patch("mm_data.fetch_json",return_value=(None,{"url":"mock","fetched_at":None,"source":"mock","attempts":1,"error":"offline"})):
        _LAST_REQ.clear();_BAN.clear()
        t0=time.monotonic()
        fetch("https://api.llama.fi/v2/chains"); fetch("https://api.llama.fi/v2/chains")
        assert time.monotonic()-t0>=0.4,"rate limiter не выдержал интервал"
    # кэш mm_data отдаёт STALE-метку (fetch без сети -> cache/None) — контракт сохранён
    # v7.2: парсер книги опционов Deribit (офлайн, фикстура)
    import datetime
    now_d=datetime.datetime(2026,11,13)
    book=[
        {"instrument_name":"BTC-20NOV26-60000-P","open_interest":120.0,"implied_vol":95.0,"underlying_price":69000.0},
        {"instrument_name":"BTC-20NOV26-55000-P","open_interest":80.0,"implied_vol":110.0},
        {"instrument_name":"BTC-20NOV26-75000-C","open_interest":60.0,"implied_vol":70.0},
        {"instrument_name":"BTC-20NOV26-80000-C","open_interest":40.0,"implied_vol":75.0},
        {"instrument_name":"BTC-27MAR26-70000-P","open_interest":999.0,"implied_vol":50.0},  # дальняя — не считается
    ]
    agg=_parse_deribit_book(book,now_d)
    assert agg["ok"] and agg["expiry"]=="2026-11-20",agg
    assert agg["pcr_oi"]==round(200.0/100.0,3),agg   # put OI 200 / call OI 100
    assert agg["put_iv"]>agg["call_iv"] and agg["skew"]>0,agg  # путы дороже
    assert agg["n_instruments"]==4
    # не-BTC/ETH -> INSUFFICIENT без сети
    assert deribit_options("SUIUSDT")["status"]=="INSUFFICIENT"
    # пустая книга — INSUFFICIENT
    assert not _parse_deribit_book([],now_d)["ok"]
    # v9.0: кэш китов и макро офлайн (с моком fetch — ноль сетевых запросов)
    with _mock.patch("mm_data.fetch_json",return_value=(None,{"url":"mock","fetched_at":None,"source":"mock","attempts":0,"error":"offline"})):
        _WHALE_CACHE["ts"]=0.0;_WHALE_CACHE["by_sym"].clear()
        _MACRO_CACHE["ts"]=0.0;_MACRO_CACHE["data"]=None
        _BAN.clear()
        assert binance_whales("TESTUSDT",limit=10)["status"] in ("UNAVAILABLE","INSUFFICIENT")
        assert coingecko_global()["status"] in ("UNAVAILABLE","INSUFFICIENT")
    print("mm_sources: OK")
if __name__=="__main__":
    import argparse
    ap=argparse.ArgumentParser()
    ap.add_argument("--selftest",action="store_true")
    ap.add_argument("--cross",default=None,help="живая кросс-биржевая сводка по символу")
    ap.add_argument("--slow",action="store_true",help="живой DefiLlama+mempool")
    g=ap.parse_args()
    if g.selftest: selftest()
    elif g.cross: print(json.dumps(cross_exchange(g.cross),ensure_ascii=False,indent=1))
    elif g.slow: print(json.dumps(slow_context(),ensure_ascii=False,indent=1))
    else: print("use --selftest / --cross SYMBOL / --slow")
