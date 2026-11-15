#!/usr/bin/env python3
"""Новости ENA-MIND v6.1: RSS-ленты без ключей — фон для sentiment-аналитика.

Порт идеи из параллельной реализации v6.0 (mm_news), адаптированный под архитектуру
ENA-MIND: новости НИКОГДА не являются стороной сами по себе — они только мягкая
поправка к голосу толпы (вес ограничен cfg.news_max_weight = 0.10 в mm_analyst).
Этот модуль — ИСТОЧНИК данных (Ingestion), не аналитик: цепочка не меняется.

Источники (публичные RSS, без ключей):
- Cointelegraph https://cointelegraph.com/rss
- Decrypt       https://decrypt.co/feed
- NewsBTC       https://www.newsbtc.com/feed/
- Bitcoin.com   https://news.bitcoin.com/feed/
Опционально: cfg.sources.cryptopanic / newsapi — если пользователь пропишет ключи.

Контроль честности: дедупликация нечётких дублей (нормализованный заголовок),
отсечение устаревших (> cfg.news_max_age_h, по умолчанию 48ч), грубая оценка
тональности по ключевым словам (impact -1..+1 — заголовок ≠ фактическое влияние,
поэтому вес зажат в аналитике), релевантность активов из заголовка.

Все тесты offline (фикстуры), сеть нужна только в fetch_news().
"""
import datetime,re,time,urllib.request,urllib.error
import email.utils
import xml.etree.ElementTree as ET
import mm_config

FEEDS_DEFAULT=[
    ("Cointelegraph","https://cointelegraph.com/rss"),
    ("Decrypt","https://decrypt.co/feed"),
    ("NewsBTC","https://www.newsbtc.com/feed/"),
    ("Bitcoin.com","https://news.bitcoin.com/feed/"),
]
# Ключевые слова тональности (латиница — заголовки англоязычных лент).
BULL_KW=("surge","rally","soar","bullish","inflow","inflows","adoption","approval","approved",
         "record high","all-time high","breakout","upgrade","partnership","accumulation",
         "buy the dip","institutional","etf approval","short squeeze")
BEAR_KW=("crash","plunge","dump","bearish","outflow","outflows","hack","exploit","lawsuit",
         "sell-off","selloff","liquidation","liquidations","fear","decline","drop",
         "warning","deleveraging","bankrupt","fraud")
ASSET_KW={"BTC":("btc","bitcoin"),"ETH":("eth","ethereum"),"SOL":("sol","solana"),
          "XRP":("xrp","ripple"),"DOGE":("doge","dogecoin"),"TON":("toncoin","ton ")}

def _norm_title(t):
    """Ключ дедупликации: нижний регистр, только буквы/цифры."""
    return re.sub(r"[^a-z0-9а-яё]+"," ",str(t or "").lower()).strip()

def _clamp(x,a,b): return max(a,min(b,x))

def _parse_ts(s):
    """RFC822 (RSS pubDate) или ISO (Atom updated/published) -> epoch sec; None если не разобралось."""
    if not s: return None
    s=str(s).strip()
    try: return int(email.utils.parsedate_to_datetime(s).timestamp())
    except (TypeError,ValueError): pass
    try:
        d=datetime.datetime.fromisoformat(s.replace("Z","+00:00"))
        if d.tzinfo is None: d=d.replace(tzinfo=datetime.timezone.utc)
        return int(d.timestamp())
    except (TypeError,ValueError): return None

def _strip_html(s):
    """Сырые <![CDATA[...]]> и теги из summary — в заголовок анализа не должны попадать."""
    return re.sub(r"<[^>]+>"," ",str(s or "")).replace("]]>"," ").replace("<![CDATA["," ").strip()

def parse_feed(xml_text,source,now_ts=None,max_age_h=48,limit=15):
    """Разбор RSS 2.0 / Atom. Валидация и дедуп внутри одной ленты. Offline-функция.
    Возвращает список карточек {source,title,url,published_ts,fetched_ts,importance,impact,assets}."""
    now_ts=now_ts if now_ts is not None else time.time()
    if not xml_text: return []
    try: root=ET.fromstring(xml_text)
    except ET.ParseError: return []
    out=[];seen=set()
    def _walk(el):
        for it in el.iter():
            tag=it.tag.split("}")[-1]
            if tag in ("item","entry"):
                title="";link="";ts=None
                for ch in it:
                    ctag=ch.tag.split("}")[-1]
                    if ctag=="title": title=_strip_html(ch.text)
                    elif ctag=="link":
                        link=ch.get("href") or _strip_html(ch.text) or link
                    elif ctag in ("pubDate","published","updated"): ts=_parse_ts(ch.text) or ts
                key=_norm_title(title)
                if title and key and key not in seen:
                    seen.add(key)
                    age_ok=(ts is None) or (now_ts-ts<=max_age_h*3600)
                    if age_ok:
                        imp,importance,assets=score(title)
                        out.append({"source":source,"title":title[:200],"url":link,
                                    "published_ts":ts,"fetched_ts":int(now_ts),
                                    "importance":importance,"impact":imp,"assets":assets})
                if len(out)>=limit: return
    _walk(root)
    return out

def score(title):
    """Грубая тональность заголовка по ключевым словам. impact -1..+1, importance, assets.
    Заголовок ≠ фактическое влияние на цену: оценка только сырье для мягкого фона."""
    t=str(title or "").lower()
    bull=sum(1 for k in BULL_KW if k in t)
    bear=sum(1 for k in BEAR_KW if k in t)
    imp=_clamp((bull-bear)/3.0,-1.0,1.0)
    importance="high" if abs(imp)>=0.6 else ("medium" if abs(imp)>=0.3 else "low")
    assets=[a for a,kws in ASSET_KW.items() if any(k in t for k in kws)]
    return round(imp,2),importance,assets

def _fetch(url,timeout=5.0):
    """GET текста ленты. Бросает исключение при сетевой неудаче (ловит вызывающий)."""
    req=urllib.request.Request(url,headers={"User-Agent":"ena-mind/6"})
    with urllib.request.urlopen(req,timeout=timeout) as r:
        return r.read().decode("utf-8","replace")

def fetch_news(limit=15,feeds=None,cfg=None,budget_sec=6.0,timeout=4.0):
    """Собрать новости из RSS-лент с общим бюджетом времени. Никогда не бросает:
    упавшая лента пропускается (статус в meta.sources). Возвращает (items, meta)."""
    c=cfg or mm_config.load()
    fs=feeds or FEEDS_DEFAULT
    deadline=time.monotonic()+budget_sec
    items=[];sources={}
    for name,url in fs:
        left=deadline-time.monotonic()
        if left<=0.3: sources[name]="SKIPPED"; continue
        try:
            xml=_fetch(url,timeout=min(timeout,max(0.5,left)))
            got=parse_feed(xml,name,max_age_h=int(c.get("news_max_age_h",48)),limit=limit)
            sources[name]=("AVAILABLE" if got else "INSUFFICIENT")
            items.extend(got)
        except (urllib.error.URLError,OSError,TimeoutError,ValueError):
            sources[name]="UNAVAILABLE"
    # межленточная дедупликация + сортировка: сначала важное/свежее
    seen=set();uniq=[]
    for n in items:
        k=_norm_title(n["title"])
        if k in seen: continue
        seen.add(k);uniq.append(n)
    rank={"high":0,"medium":1,"low":2}
    uniq.sort(key=lambda n:(rank.get(n["importance"],3),-(n["published_ts"] or 0)))
    return uniq[:limit],{"sources":sources,"fetched_at":time.time(),"n_feeds":len(fs)}

def selftest():
    now=int(time.time())
    # RSS 2.0 фикстура: дубликат внутри ленты + старьё + CDATA
    fresh_ts=email.utils.formatdate(now-3600,usegmt=True)
    old_ts=email.utils.formatdate(now-72*3600,usegmt=True)
    rss="""<?xml version="1.0"?><rss version="2.0"><channel><title>t</title>
    <item><title><![CDATA[Bitcoin ETF inflows surge to record high]]></title><link>u1</link><pubDate>%s</pubDate></item>
    <item><title>Bitcoin ETF inflows SURGE to record-high!</title><link>u1b</link><pubDate>%s</pubDate></item>
    <item><title>Crypto exchange hack wipes millions</title><link>u2</link><pubDate>%s</pubDate></item>
    <item><title>Old regulation news</title><link>u3</link><pubDate>%s</pubDate></item>
    </channel></rss>"""%(fresh_ts,fresh_ts,fresh_ts,old_ts)
    items=parse_feed(rss,"test",now_ts=now,max_age_h=48)
    titles=[i["title"] for i in items]
    assert len(items)==2,(len(items),titles)  # дубликат удалён, старьё отброшено
    assert any("ETF" in t for t in titles) and any("hack" in t.lower() for t in titles)
    assert items[0]["source"]=="test" and items[0]["url"] in ("u1","u1b")
    # тональность
    imp,imp_rank,assets=score("Bitcoin ETF inflows surge to record high")
    assert imp>0.3 and imp_rank in ("medium","high") and "BTC" in assets,(imp,imp_rank,assets)
    imp2,_,assets2=score("Ethereum exchange hack and liquidations")
    assert imp2<0 and "ETH" in assets2,(imp2,assets2)
    imp3,r3,a3=score("Weekly newsletter digest")
    assert imp3==0.0 and r3=="low" and a3==[]
    # битый XML -> пусто, не падает
    assert parse_feed("<not-xml","test")==[] and parse_feed("","test")==[]
    # Atom фикстура
    atom="""<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom">
    <entry><title>Solana breakout on volume</title><link href="s1"/><updated>%s</updated></entry>
    </feed>"""%datetime.datetime.fromtimestamp(now-100,datetime.timezone.utc).isoformat()
    at=parse_feed(atom,"atom-test",now_ts=now)
    assert len(at)==1 and at[0]["url"]=="s1" and "SOL" in at[0]["assets"],at
    # _parse_ts: ISO и RFC822
    assert _parse_ts("Mon, 01 Jan 2024 00:00:00 GMT")==1704067200
    assert _parse_ts("2024-01-01T00:00:00Z")==1704067200
    assert _parse_ts(None) is None and _parse_ts("мусор") is None
    # fetch_news с инъекцией лент через monkeypatch сети (offline)
    import unittest.mock as mock
    with mock.patch("mm_news._fetch",side_effect=lambda url,timeout=4.0: rss):
        items2,meta=fetch_news(feeds=[("f1","http://x")],budget_sec=5.0)
        assert items2 and meta["sources"]["f1"]=="AVAILABLE"
    with mock.patch("mm_news._fetch",side_effect=urllib.error.URLError("offline")):
        items3,meta3=fetch_news(feeds=[("bad","http://x")],budget_sec=0.5)
        assert items3==[] and meta3["sources"]["bad"]=="UNAVAILABLE"
    print("mm_news: OK")

if __name__=="__main__":
    import argparse,json
    ap=argparse.ArgumentParser()
    ap.add_argument("--selftest",action="store_true")
    ap.add_argument("--fetch",action="store_true",help="живой сбор RSS (сеть)")
    ap.add_argument("--limit",type=int,default=15)
    g=ap.parse_args()
    if g.selftest: selftest()
    elif g.fetch:
        items,meta=fetch_news(limit=g.limit)
        print(json.dumps({"meta":meta,"items":items},ensure_ascii=False,indent=1))
    else: print("use --selftest | --fetch")
