#!/usr/bin/env python3
"""Свечи ENA-MIND v6.0: REST-загрузка klines из Bybit V5 (публичный, без ключей),
с fallback на CSV. Binance полностью удалён из рабочих источников (аудит 2026-11-12).

Важно (фикс бага v5.0): /v5/market/kline возвращает ts УЖЕ в миллисекундах —
v5 умножала на 1000 и портила таймстампы. Теперь ts берётся как есть.

Формат строки свечи: [ts_ms, open, high, low, close, volume]. Элементы API: [start, open,
high, low, close, volume, turnover] — turnover отбрасывается, контракт v4/v5 сохранён.
"""
import argparse,csv,json,os,time
import mm_data,mm_config
def _byb(cfg): return cfg.get("sources",{}).get("bybit","https://api.bybit.com")
def from_bybit(symbol="BTCUSDT",interval="60",limit=300,timeout=8,cfg=None):
    """Bybit V5 klines: /v5/market/kline. interval в минутах: '60'='1h'.
    Возвращает [ts_ms,o,h,l,c,v] (ts уже в ms — фикс v6), новые свечи в конце."""
    c=cfg or mm_config.load()
    url="%s/v5/market/kline?category=linear&symbol=%s&interval=%s&limit=%d"%(_byb(c),symbol,interval,limit)
    pay,meta=mm_data.fetch_json(url,timeout=timeout)
    return _parse_bybit(pay),meta
def from_csv(path):
    """CSV: заголовок ts,open,high,low,close,volume; числа. Совместим с v4/v5."""
    if not os.path.exists(path): return None
    with open(path,encoding="utf-8",newline="") as f:
        rows=[[float(x) for x in r[:6]] for r in list(csv.reader(f))[1:] if r and r[0] and r[0][0]!="#"]
    return rows or None
def fetch(symbol="BTCUSDT",interval="60",limit=300,timeout=8,cfg=None,csv_path=None,prefer="bybit"):
    """Цепочка источников: Bybit V5 -> CSV (если указан). prefer оставлен для
    совместимости сигнатуры; Binance удалён, любая строка кроме 'csv' означает Bybit.
    Возвращает (rows, source_name, quality). rows=None -> все источники упали."""
    c=cfg or mm_config.load()
    iv=interval if str(interval).isdigit() else "60"
    rows,meta=from_bybit(symbol,iv,limit,timeout,c)
    if rows and len(rows)>=2:
        q=mm_data.series_quality(rows)
        if q["ok"]: return rows,"bybit",q
    if csv_path:
        rows=from_csv(csv_path)
        if rows:
            q=mm_data.series_quality(rows)
            return rows,"csv",q
    return None,"none",{"ok":False,"n":0,"issues":["all_sources_failed"]}
def selftest():
    # from_csv: нет файла -> None
    assert from_csv("/nonexistent.csv") is None
    # парсинг валидного CSV-содержимого (через временный файл)
    import tempfile
    with tempfile.NamedTemporaryFile("w",suffix=".csv",delete=False,encoding="utf-8",newline="") as f:
        f.write("ts,open,high,low,close,volume\n")
        for i in range(220):
            f.write("%d,%.4f,%.4f,%.4f,%.4f,1.0\n"%(i,100+i*0.01,101+i*0.01,99+i*0.01,100+i*0.01))
        path=f.name
    rows=from_csv(path)
    assert rows and len(rows)==220 and rows[0][1]==100.0
    # фикс бага v5: ts уже в ms, НЕ умножаем на 1000
    fake_bybit={"retCode":0,"result":{"list":[["1794510000000","100","101","99","100.5","1"],
                                             ["1794506400000","100","101","99","100","1"]]}}
    # прямая проверка парсинга тела ответа (без сети)
    parsed=_parse_bybit(fake_bybit)
    assert parsed[0][0]==1794506400000 and parsed[1][0]==1794510000000  # старые -> новые
    assert parsed[0][1]==100.0 and parsed[1][4]==100.5
    assert _parse_bybit({"retCode":1}) is None and _parse_bybit({"retCode":0,"result":{"list":[]}}) is None
    # fallback: Bybit упал -> CSV-файл
    import unittest.mock as mock
    with mock.patch("%s.from_bybit"%__name__,return_value=(None,{})):
        r,src,q=fetch("BTCUSDT",csv_path=path)
        assert src=="csv" and q["ok"] and len(r)==220,(src,q.get("ok"))
        # все источники упали -> none
        r,src,q=fetch("BTCUSDT",csv_path="/nonexistent.csv")
        assert src=="none" and r is None
    # основной путь: валидный Bybit-ответ
    ok_rows=[[i*3600000,100,101,99,100,1] for i in range(300)]
    with mock.patch("%s.from_bybit"%__name__,return_value=(ok_rows,{"source":"live","fetched_at":time.time()})):
        r,src,q=fetch("BTCUSDT")
        assert src=="bybit" and q["ok"] and len(r)==300
    print("mm_klines: OK")
def _parse_bybit(pay):
    """Разбор тела ответа /v5/market/kline -> [ts_ms,o,h,l,c,v] или None."""
    if not isinstance(pay,dict) or pay.get("retCode")!=0: return None
    lst=(pay.get("result") or {}).get("list") or []
    rows=[]
    for k in lst:
        try:
            rows.append([int(k[0]),float(k[1]),float(k[2]),float(k[3]),float(k[4]),float(k[5])])
        except (TypeError,ValueError,IndexError): continue
    rows.reverse()
    return rows or None
if __name__=="__main__":
    ap=argparse.ArgumentParser()
    ap.add_argument("--selftest",action="store_true")
    ap.add_argument("--symbol",default="BTCUSDT")
    ap.add_argument("--interval",default="60",help="минуты: 1,5,15,30,60,120,240,360,720,D,W,M")
    ap.add_argument("--limit",type=int,default=300)
    ap.add_argument("--csv",help="fallback CSV")
    ap.add_argument("--out",help="записать в JSON [ts_ms,o,h,l,c,v]")
    g=ap.parse_args()
    if g.selftest: selftest()
    else:
        rows,src,q=fetch(g.symbol,g.interval,g.limit,csv_path=g.csv)
        print(json.dumps({"source":src,"quality":q,"n":(len(rows) if rows else 0)},ensure_ascii=False,indent=1))
        if g.out and rows:
            with open(g.out,"w",encoding="utf-8") as f: json.dump(rows,f)
