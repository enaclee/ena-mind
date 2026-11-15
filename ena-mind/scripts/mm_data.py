#!/usr/bin/env python3
"""Сетевой клиент ENA-MIND v6.0: общий бюджет времени, кэш, повтор, честный грейд,
статус каждого источника AVAILABLE / UNAVAILABLE / STALE / INSUFFICIENT.

Контракт: fetch_json возвращает (payload, meta). meta всегда содержит source/attempts/error;
payload может быть None при полной неудаче. Кэш используется как fallback, но помечается.

v6: добавлен source_status() — единый статус источника для всего конвейера:
- UNAVAILABLE — источник не ответил (payload None);
- STALE — ответ из кэша или слишком старый для задачи (max_age);
- INSUFFICIENT — источник ответил, но данных меньше, чем требует задача (min_n);
- AVAILABLE — свежие полные данные.
User-Agent переименован в ena-mind/6 (Binance удалён из всего проекта).
"""
import hashlib,json,os,time,datetime,urllib.request,urllib.error
import mm_config
def _paths():
    BASE=mm_config.data_dir()
    return BASE,os.path.join(BASE,"cache"),os.path.join(BASE,"errors.log")
def log_err(m):
    BASE,_,LOG=_paths()
    os.makedirs(BASE,exist_ok=True)
    with open(LOG,"a",encoding="utf-8") as f:
        f.write(json.dumps({"ts":datetime.datetime.now(datetime.timezone.utc).isoformat(),"m":m},ensure_ascii=False)+"\n")
def _fetch_once(url,timeout,user_agent):
    """Один запрос в рабочем потоке-демоне + жёсткий watchdog-дедлайн.
    Сокет-таймаут urllib НЕ покрывает зависший DNS (getaddrinfo может висеть
    бесконечно — проверено живой сетью). Watchdog даёт timeout+3с и возвращает
    честную ошибку; брошенный поток-демон умирает вместе с процессом и не
    блокирует конвейер (важно для 5с-цикла движка на реальном устройстве)."""
    import threading
    box={}
    def work():
        try:
            req=urllib.request.Request(url,headers={"User-Agent":user_agent})
            with urllib.request.urlopen(req,timeout=timeout) as r:
                box["payload"]=json.loads(r.read().decode("utf-8"))
        except Exception as e:
            box["err"]=str(e)[:200]
    th=threading.Thread(target=work,daemon=True)
    th.start()
    th.join(timeout+3.0)
    if th.is_alive():
        return None,"timeout watchdog %.0fs (DNS/сокет не ответили)"%timeout
    return box.get("payload"),box.get("err")

def fetch_json(url,timeout=8,retries=2,user_agent="ena-mind/6"):
    """Живой запрос с кэш-fallback и watchdog-дедлайном. Никогда не бросает —
    возвращает (None, meta) при провале."""
    BASE,CACHE,_=_paths()
    os.makedirs(CACHE,exist_ok=True)
    path=os.path.join(CACHE,hashlib.sha256(url.encode()).hexdigest()[:24]+".json")
    meta={"url":url,"fetched_at":None,"source":"live","attempts":0,"error":None}
    for a in range(retries+1):
        meta["attempts"]=a+1
        pay,err=_fetch_once(url,timeout,user_agent)
        if pay is not None:
            meta["fetched_at"]=time.time()
            try:
                with open(path,"w",encoding="utf-8") as f: json.dump({"meta":meta,"payload":pay},f)
            except Exception: pass
            return pay,meta
        meta["error"]=err
        log_err({"url":url,"a":a+1,"e":err})
        if a<retries: time.sleep(min(1.5**a,3.0))
    # кэш-fallback с честной пометкой
    if os.path.exists(path):
        try:
            with open(path,encoding="utf-8") as f: c=json.load(f)
            c["meta"]["source"]="cache"
            c["meta"]["age_sec"]=round(time.time()-(c["meta"]["fetched_at"] or 0),1) if c["meta"]["fetched_at"] else None
            c["meta"]["error"]=meta["error"]
            return c["payload"],c["meta"]
        except Exception: pass
    return None,meta
def age_fresh(meta,max_age):
    """Честная свежесть: None fetched_at -> (False, None) (упал = не свежий).
    Всегда возвращает кортеж (is_fresh, age_sec_or_None)."""
    if not meta or not meta.get("fetched_at"): return False,None
    age=time.time()-meta["fetched_at"]
    return (age<=max_age),(round(age,1) if age>=0 else None)
def source_status(meta,max_age=120.0,payload_ok=None,min_n=None,n=None):
    """Единый статус источника: AVAILABLE / UNAVAILABLE / STALE / INSUFFICIENT.

    meta: словарь от fetch_json (или None — тогда UNAVAILABLE).
    payload_ok: ответила ли структура (retCode==0 и т.п.); None -> не проверяем.
    min_n/n: достаточность объёма данных (напр. свечей или истории funding)."""
    if meta is None or (payload_ok is False): return "UNAVAILABLE"
    if meta.get("source")=="cache": return "STALE"
    if payload_ok is None or payload_ok:
        fresh,age=age_fresh(meta,max_age)
        if not fresh: return "STALE"
        if min_n is not None and (n is None or n<min_n): return "INSUFFICIENT"
        return "AVAILABLE"
    return "UNAVAILABLE"
def series_quality(rows):
    """Качество свечного ряда: нет дублей, упорядочен по ts, OHLC-инварианты, объём >=0."""
    if not rows: return {"n":0,"ok":False,"issues":["empty"]}
    ts=[r[0] for r in rows]
    dup=len(ts)-len(set(ts));ordered=all(ts[i]<ts[i+1] for i in range(len(ts)-1))
    diffs=[ts[i+1]-ts[i] for i in range(len(ts)-1)]
    import statistics
    step=statistics.median(diffs) if diffs else 0
    gaps=sum(1 for d in diffs if step and d>1.5*step)
    bad=sum(1 for r in rows if not (r[3]<=min(r[1],r[4]) and r[2]>=max(r[1],r[4]) and r[2]>=r[3] and r[5]>=0 and min(r[1:5])>0))
    return {"n":len(rows),"ok":dup==0 and ordered and bad==0,"dupes":dup,"unordered":not ordered,
            "gaps":gaps,"bad_ohlc":bad,"step":step}
def selftest():
    # возраст: None fetched_at -> не свежий (главный фикс v5, сохранён)
    assert age_fresh({"fetched_at":None},60)[0] is False
    assert age_fresh({"fetched_at":time.time()-1},60)[0] is True
    assert age_fresh({"fetched_at":time.time()-120},60)[0] is False
    # v6: статусы источников
    live={"fetched_at":time.time()-1,"source":"live"}
    assert source_status(live,120)=="AVAILABLE"
    assert source_status(live,0.0001)=="STALE"                      # свежесть истекла
    assert source_status(None)=="UNAVAILABLE"
    assert source_status({"fetched_at":time.time(),"source":"cache"},120)=="STALE"
    assert source_status(live,120,payload_ok=False)=="UNAVAILABLE"
    assert source_status(live,120,min_n=201,n=150)=="INSUFFICIENT"  # мало истории
    assert source_status(live,120,min_n=201,n=300)=="AVAILABLE"
    # качество ряда
    assert series_quality([[i,100,101,99,100,1] for i in range(10)])["ok"]
    q=series_quality([[0,100,101,99,100,1],[2,100,101,99,100,1],[2,100,101,99,100,1]])
    assert not q["ok"] and q["dupes"]==1
    # OHLC-инвариант: low>high невозможен
    bad=[[0,100,99,105,100,1]]  # high<low
    assert not series_quality(bad)["ok"]
    print("mm_data: OK")
if __name__=="__main__":
    import argparse
    ap=argparse.ArgumentParser()
    ap.add_argument("--selftest",action="store_true")
    g=ap.parse_args()
    if g.selftest: selftest()
    else: print("use --selftest")
