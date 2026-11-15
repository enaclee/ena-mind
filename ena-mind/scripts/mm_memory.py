#!/usr/bin/env python3
"""mm_memory — память прогнозов и самообучение ENA v9.0 (ЧАСТЬ 7 промпта).

Каждый прогноз (по умолчанию 6 часов = 360 мин) записывается с полным контекстом:
  asset, ts, entry_price, horizon_min, initial probabilities (P up/flat/down),
  model_version, agent_results, market_regime, data_quality,
  а после истечения горизонта — actual_price, actual_return, result, error.

Автопроверка: verify() находит записи с истёкшим горизонтом и закрывает их по
фактической цене (свечи Bybit) с нейтральным порогом FLAT_BAND_PCT. Никакая запись не проверяется дважды.

Аналитика: forecast history, accuracy по активам/режимам/времени суток/волатильности,
agent accuracy (голос агента против факта), model performance (Brier/калибровка),
model drift (скользящая точность против долгосрочной).

Веса агентов: постепенная корректировка по фактической точности (ЧАСТЬ 7):
- минимум AGENT_MIN_N проверенных прогнозов с голосом агента;
- обновление EMA (alpha=0.25) — одно удачное/неудачное окно не меняет систему;
- веса зажаты в [0.5, 1.5] — резких перекосов нет.

Хранение: data/forecast_memory_v7.jsonl (append-only, одна запись — строка JSON;
это надёжнее CSV при расширении схемы и переживает обрывы записи).
"""
import json,math,os,time
import mm_config

HORIZON_MIN=360
FLAT_BAND_PCT=0.15
AGENT_MIN_N=20
WEIGHT_MIN,WEIGHT_MAX=0.5,1.5
MEMORY_VERSION="9.0"

def _path():
    return os.path.join(mm_config.data_dir(),"forecast_memory_v7.jsonl")

def _load_all():
    out=[]
    try:
        with open(_path(),encoding="utf-8") as f:
            for line in f:
                line=line.strip()
                if not line: continue
                try: out.append(json.loads(line))
                except Exception: continue
    except FileNotFoundError:
        pass
    return out

MAX_RECORDS=2000

def store(rec):
    """Записать прогноз (начальная версия, до истечения горизонта)."""
    r={"v":MEMORY_VERSION}
    r.update(rec)
    r.setdefault("ts",int(time.time()*1000))
    r.setdefault("checked",False)
    os.makedirs(os.path.dirname(_path()),exist_ok=True)
    with open(_path(),"a",encoding="utf-8") as f:
        f.write(json.dumps(r,ensure_ascii=False)+"\n")
    # Периодическая ротация файла памяти, чтобы он не раздувал ОЗУ при суточной работе
    try:
        if os.path.getsize(_path())>1_500_000:
            recs=_load_all()
            if len(recs)>MAX_RECORDS:
                _save_all(recs)
    except Exception:
        pass
    return r

def pending():
    """Непроверенные записи с истёкшим горизонтом."""
    now=time.time()*1000
    out=[]
    for r in _load_all():
        if not r.get("checked"):
            horizon_ms=float(r.get("horizon_min") or HORIZON_MIN)*60_000.0
            if now-r.get("ts",0)>=horizon_ms: out.append(r)
    return out

def verify(fetch_klines=None):
    """Автопроверка истёкших прогнозов. fetch_klines(symbol, limit) -> rows
    (позволяет мокать в тестах). Выбирает ТФ ('15' или '60') и достаточный limit
    под horizon_min, использует порог FLAT_BAND_PCT. Возвращает (n_checked, n_errors)."""
    import mm_sources
    rows_cache={}
    def _kl(symbol,horizon_min):
        if fetch_klines is not None:
            lim=300 if horizon_min>=180 else 250
            return fetch_klines(symbol,lim)
        tf="15" if horizon_min>=180 else "1"
        lim=300 if horizon_min>=180 else 250
        key=(symbol,tf)
        if key not in rows_cache:
            r=mm_sources.bybit_klines(symbol,tf,lim)
            rows=r.get("rows") or []
            if not rows and tf!="60":
                r2=mm_sources.bybit_klines(symbol,"60",200)
                rows=r2.get("rows") or []
            rows_cache[key]=rows
        return rows_cache[key]
    pend=pending()
    if not pend: return 0,0
    recs=_load_all()
    n_ok=n_err=0
    for p in pend:
        try:
            hmin=float(p.get("horizon_min") or HORIZON_MIN)
            rows=_kl(p["asset"],hmin)
            entry_ts=p["ts"]/1000.0
            entry_price=p.get("entry_price")
            if not rows or not entry_price:
                _close(p,recs,None,None,"нет данных для проверки")
                n_err+=1;continue
            # ближайшая свеча к моменту истечения горизонта
            target_ts=entry_ts+hmin*60.0
            fut=[r for r in rows if r[0]/1000.0>=target_ts]
            if not fut:
                # горизонт ещё не закрыт свечой — оставляем до следующего вызова
                continue
            actual=fut[0][4]
            ret=100.0*(actual-entry_price)/entry_price if entry_price else None
            prob=p.get("p") or {}
            side=prob.get("side") or p.get("side") or "FLAT"
            band=float(p.get("flat_band_pct") or (FLAT_BAND_PCT if hmin>=60 else 0.08))
            if ret is None:
                actual_side="FLAT"
            elif ret>band:
                actual_side="UP"
            elif ret<-band:
                actual_side="DOWN"
            else:
                actual_side="FLAT"
            hit=1 if side==actual_side else 0
            _close(p,recs,actual,ret,"OK",hit=hit,actual_side=actual_side)
            n_ok+=1
        except Exception as e:
            _close(p,recs,None,None,"ошибка: %s"%str(e)[:120])
            n_err+=1
    if n_ok+n_err>0:
        _save_all(recs)
    return n_ok,n_err

def _close(p,recs,actual_price,ret,status,hit=None,actual_side=None):
    for r in recs:
        if r.get("id")==p.get("id"):
            r["checked"]=True
            r["checked_ts"]=int(time.time()*1000)
            r["actual_price"]=actual_price
            r["actual_return_pct"]=round(ret,4) if ret is not None else None
            r["verify_status"]=status
            if hit is not None: r["hit"]=hit
            r["actual_side"]=actual_side
            break

def _save_all(recs):
    if len(recs)>MAX_RECORDS:
        unchecked=[r for r in recs if not r.get("checked")]
        checked=[r for r in recs if r.get("checked")]
        keep_checked=max(0,MAX_RECORDS-len(unchecked))
        recs=(checked[-keep_checked:] if keep_checked>0 else [])+unchecked[-MAX_RECORDS:]
    tmp=_path()+".tmp"
    with open(tmp,"w",encoding="utf-8") as f:
        for r in recs: f.write(json.dumps(r,ensure_ascii=False)+"\n")
    os.replace(tmp,_path())

# ---------------- аналитика ----------------
def stats():
    """Сводка памяти: история, точности по срезам, дрейф, калибровка."""
    recs=_load_all()
    checked=[r for r in recs if r.get("checked") and r.get("verify_status")=="OK"]
    out={"version":MEMORY_VERSION,"total":len(recs),"unchecked":len(recs)-len(checked),
         "checked":{"n":len(checked)}}
    if not checked: return out
    hits=[r for r in checked if r.get("hit")==1]
    out["checked"]["hit_rate"]=round(len(hits)/len(checked),4)
    conf=[max((r.get("p") or {}).get("p_up",0),(r.get("p") or {}).get("p_down",0)) for r in checked]
    out["checked"]["mean_max_prob"]=round(sum(conf)/len(conf),4) if conf else None
    rets=[r.get("actual_return_pct") or 0.0 for r in checked]
    if rets:
        out["checked"]["mean_abs_move_pct"]=round(sum(abs(x) for x in rets)/len(rets),4)
    def _by(field,fmt=None):
        groups={}
        for r in checked:
            k=fmt(r.get(field)) if fmt else r.get(field)
            if k is None: continue
            groups.setdefault(k,[]).append(r)
        res={}
        for k,rs in groups.items():
            h=sum(1 for r in rs if r.get("hit")==1)
            res[str(k)]={"n":len(rs),"hit_rate":round(h/len(rs),4)}
        return res
    out["by_asset"]=_by("asset")
    out["by_regime"]=_by("regime")
    out["by_hour"]= _by("ts",lambda t:str(int((t/3600000.0)%24)).zfill(2)+"ч")
    out["by_volatility"]=_by("volatility_band")
    # дрейф модели: последние 50 против всех
    if len(checked)>=30:
        recent=checked[-50:]
        h_rec=sum(1 for r in recent if r.get("hit")==1)/len(recent)
        h_all=out["checked"]["hit_rate"]
        out["drift"]={"recent_50_hit":round(h_rec,4),"overall_hit":h_all,
                      "drift":round(h_rec-h_all,4),
                      "alert":abs(h_rec-h_all)>=0.25}
    return out

def agent_accuracy():
    """Точность каждого агента: голос (side) против факта. Только проверенные
    записи, где агент голосовал не в NEUTRAL."""
    recs=_load_all()
    checked=[r for r in recs if r.get("checked") and r.get("verify_status")=="OK"
             and r.get("actual_side") in ("UP","DOWN")]
    acc={}
    for r in checked:
        for a in (r.get("agents") or []):
            name=a.get("agent");side=a.get("side")
            if not name or side not in ("LONG","SHORT"): continue
            d=acc.setdefault(name,{"n":0,"hits":0})
            d["n"]+=1
            if (side=="LONG" and r["actual_side"]=="UP") or (side=="SHORT" and r["actual_side"]=="DOWN"):
                d["hits"]+=1
    for d in acc.values():
        d["hit_rate"]=round(d["hits"]/d["n"],4) if d["n"] else None
    return acc

def agent_weights(current=None):
    """Веса агентов по фактической точности (ЧАСТЬ 7). Обновление — EMA с малым
    alpha, окно AGENT_MIN_N, зажим [0.5, 1.5]. Точность 50% -> вес 1.0."""
    acc=agent_accuracy()
    w=dict(current or {})
    for name,d in acc.items():
        if d["n"]<AGENT_MIN_N: continue
        hr=d["hit_rate"]
        target=1.0+(hr-0.5)*2.0          # 50% -> 1.0; 65% -> 1.3; 35% -> 0.7
        target=max(WEIGHT_MIN,min(WEIGHT_MAX,target))
        old=w.get(name,1.0)
        w[name]=round(old+0.25*(target-old),4)   # EMA alpha=0.25 — плавно
    return w

def calibration():
    """Калибровка вероятностей: заявленный P(стороны) против частоты по бакетам."""
    recs=_load_all()
    checked=[r for r in recs if r.get("checked") and r.get("verify_status")=="OK"
             and r.get("actual_side") in ("UP","DOWN")]
    if len(checked)<30:
        return {"status":"INSUFFICIENT","n":len(checked),
                "msg":"для калибровки нужно >=30 проверенных прогнозов"}
    bins=[0.0]*5;obs=[0]*5;cnt=[0]*5
    for r in checked:
        p=r.get("p") or {}
        side=p.get("side") or "FLAT"
        if side=="FLAT": continue
        p_side=p.get("p_up") if side=="UP" else p.get("p_down")
        if p_side is None: continue
        b=min(4,int(p_side*5))
        bins[b]+=p_side;cnt[b]+=1
        if r.get("actual_side")==side: obs[b]+=1
    rows=[]
    for b in range(5):
        if cnt[b]>=3:
            rows.append({"bin":"%.1f-%.1f"%(b/5,(b+1)/5),"n":cnt[b],
                         "claimed":round(bins[b]/cnt[b],4),
                         "observed":round(obs[b]/cnt[b],4)})
    return {"status":"OK","n":len(checked),"bins":rows}

def model_performance():
    """Brier score и точность прогнозов (главная вероятностная метрика)."""
    recs=_load_all()
    checked=[r for r in recs if r.get("checked") and r.get("verify_status")=="OK"]
    if not checked: return {"n":0}
    brier=0.0;n=0
    for r in checked:
        p=r.get("p") or {}
        side=p.get("side") or "FLAT"
        if side=="FLAT" or r.get("actual_side") not in ("UP","DOWN"): continue
        p_side=p.get("p_up") if side=="UP" else p.get("p_down")
        if p_side is None: continue
        outcome=1 if r.get("actual_side")==side else 0
        brier+=(p_side-outcome)**2;n+=1
    hits=sum(1 for r in checked if r.get("hit")==1)
    return {"n":len(checked),"brier":round(brier/n,4) if n else None,
            "hit_rate":round(hits/len(checked),4),"n_prob":n}

def history(n=50):
    """Последние n записей памяти (для экрана истории приложения)."""
    recs=_load_all()
    out=list(reversed(recs[-int(n):]))
    return out

def selftest():
    import tempfile,os
    d=tempfile.mkdtemp()
    os.environ["MM5_DATA_DIR"]=d
    try:
        # store + verify на 6-часовом горизонте (без сети)
        for i,p_up in enumerate((0.62,0.58,0.71,0.55,0.65)):
            store({"id":"F%d"%i,"asset":"BTCUSDT","ts":(time.time()-(420+i*15)*60)*1000,
                   "entry_price":100.0,"horizon_min":360,
                   "p":{"p_up":p_up,"p_flat":0.2,"p_down":1-p_up-0.2,
                        "side":"UP" if p_up>0.5 else "DOWN"},
                   "side":"UP","model_version":"test","regime":"trend_up",
                   "data_quality":"A","volatility_band":"mid",
                   "agents":[{"agent":"PRICE_ANALYST","side":"LONG","confidence":0.6},
                             {"agent":"ORDER_FLOW_ANALYST","side":"SHORT","confidence":0.55}]})
        # тест deadband для FLAT: движение +0.05% (< 0.15%) засчитывается как FLAT
        store({"id":"FLAT0","asset":"FLATUSD","ts":(time.time()-400*60)*1000,
               "entry_price":100.0,"horizon_min":360,
               "p":{"p_up":0.2,"p_flat":0.6,"p_down":0.2,"side":"FLAT"},
               "side":"FLAT","model_version":"test","regime":"range"})
        # 15m-свечи покрывают последние 10 часов (40 баров)
        kl={
            "BTCUSDT":[[ (time.time()-k*900)*1000,100,101,99,100+(40-k)*0.4,1.0] for k in range(40,0,-1)],
            "FLATUSD":[[ (time.time()-k*900)*1000,100,100.1,99.9,100.05,1.0] for k in range(40,0,-1)],
        }
        def mock_fetch(symbol,limit=300): return kl[symbol]
        n,err=verify(fetch_klines=mock_fetch)
        assert n>=5 and err==0,(n,err)
        flat_rec=[r for r in _load_all() if r.get("id")=="FLAT0"][0]
        assert flat_rec["actual_side"]=="FLAT" and flat_rec["hit"]==1,flat_rec
        # повторная проверка ничего не закрывает
        n2,_=verify(fetch_klines=mock_fetch)
        assert n2==0
        s=stats()
        assert s["total"]>=6 and s["checked"]["n"]>=5,s
        assert "by_asset" in s and "by_hour" in s and "by_volatility" in s
        assert s["by_asset"].get("BTCUSDT",{}).get("n",0)>=4
        # точность агентов: PRICE LONG был прав (рынок рос), FLOW SHORT ошибался
        acc=agent_accuracy()
        assert acc["PRICE_ANALYST"]["n"]>=4 and acc["PRICE_ANALYST"]["hit_rate"]>=0.5,acc
        assert acc["ORDER_FLOW_ANALYST"]["hit_rate"]==0.0,acc
        # веса: мало наблюдений — не обновляются; после порога — обновляются плавно
        w0=agent_weights()
        assert w0=={} or all(v==1.0 for v in w0.values()),w0
        # докидываем записи до порога AGENT_MIN_N (по умолчанию 20)
        for i in range(25):
            store({"id":"G%d"%i,"asset":"BTCUSDT","ts":(time.time()-(380+i)*60)*1000,
                   "entry_price":100.0,"horizon_min":360,
                   "p":{"p_up":0.6,"p_flat":0.2,"p_down":0.2,"side":"UP"},
                   "side":"UP","model_version":"test","regime":"range",
                   "agents":[{"agent":"PRICE_ANALYST","side":"LONG","confidence":0.6}]})
        n3,_=verify(fetch_klines=mock_fetch)
        assert n3>=20,n3
        w=agent_weights()
        # рынок рос, LONG попадал >50% -> вес чуть выше 1.0, но в пределах [0.5,1.5]
        assert 1.0<=w.get("PRICE_ANALYST",1.0)<=1.5,w
        assert set(w)<=set(("PRICE_ANALYST","ORDER_FLOW_ANALYST"))
        mp=model_performance()
        assert mp["n"]>=24 and mp["hit_rate"]>=0.5,mp
        assert mp["brier"] is not None and 0<=mp["brier"]<=1
        c=calibration()
        assert c["status"] in ("OK","INSUFFICIENT")
    finally:
        del os.environ["MM5_DATA_DIR"]
    print("mm_memory: OK")
if __name__=="__main__":
    import argparse
    ap=argparse.ArgumentParser()
    ap.add_argument("--selftest",action="store_true")
    ap.add_argument("--stats",action="store_true")
    ap.add_argument("--verify",action="store_true",help="закрыть истёкшие прогнозы по рынку")
    g=ap.parse_args()
    if g.selftest: selftest()
    elif g.stats: print(json.dumps(stats(),ensure_ascii=False,indent=1))
    elif g.verify:
        n,e=verify()
        print("проверено: %d, ошибок: %d"%(n,e))
    else: print("use --selftest / --stats / --verify")
