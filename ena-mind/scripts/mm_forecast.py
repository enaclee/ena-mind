#!/usr/bin/env python3
"""Узел 5 — Market Forecast (ENA-MIND v6.0). Прогноз направления рынка.

ГЛАВНЫЙ ПРИНЦИП: прогноз ≠ торговое решение. Council может выдать NO_TRADE, Risk Gate —
BLOCK, но прогноз всё равно оценивает относительное направление рынка. Информация о
вероятном движении не должна теряться из-за строгости ворот — строгость сохраняется
для сделок, честность — для анализа.

Правила честности (из ТЗ):
- уверенность — ОТНОСИТЕЛЬНАЯ сила совпадения аналитических узлов, НЕ калиброванная
  вероятность (нет калиброванной статистической модели — нет и точной вероятности);
- при критически плохих данных (grade D или < forecast_min_modules живых аналитиков)
  направленный прогноз не выдаётся: side=neutral, status=INSUFFICIENT;
- прогноз никогда не обходит Risk Gate: trade_allowed только при council AGREE И gate PASS;
- всегда показываются аргументы, противоречия, альтернативный сценарий и условия отмены.

Память: прогнозы пишутся в data/forecasts.csv; verify() сверяет старые прогнозы с
фактическим движением рынка по klines; stats() выявляет: повторяющиеся ошибки по режимам,
систематическое завышение уверенности, успешные комбинации. При n>=10 проверенных
прогнозов в режиме memory-поправка аккуратно сдвигает уверенность (±0.05) и НИКОГДА не
меняет правила Risk Gate.
"""
import csv,datetime,json,os,time
import mm_config,mm_schema
def _base():
    return mm_config.data_dir()
def _fpath():
    return os.path.join(_base(),"forecasts.csv")
FIELDS=mm_schema.FORECAST_RECORD
def _clamp(x,a,b): return max(a,min(b,x))
def _band(conf):
    return "LOW" if conf<0.50 else ("MEDIUM" if conf<0.65 else "HIGH")
def forecast(decision,snap=None,regime=None,cfg=None,news=None,gate=None,horizon_h=None,
             pair="BTCUSDT",interval="60"):
    """Прогноз направления по результатам всей цепочки (после Council).

    decision: DECISION совета (mm_council.resolve) — обязателен.
    snap: снимок mm_live (для grade/цены); gate: результат mm_risk.check или None.
    Возвращает контракт FORECAST (mm_schema)."""
    c=cfg or mm_config.load()
    horizon_h=horizon_h if horizon_h is not None else int(c.get("forecast_horizon_h",24))
    modules=(decision or {}).get("modules") or {}
    grade=(snap or {}).get("grade")
    n_ok=sum(1 for m in modules.values() if m.get("ok"))
    min_mod=int(c.get("forecast_min_modules",2))
    out={"side":"neutral","confidence":0.0,"band":"LOW","p":None,"horizon_h":horizon_h,
         "arguments":[],"contradictions":[],"alt_scenario":"","invalidate":[],
         "council_status":(decision or {}).get("status"),"gate_status":"NOT_CHECKED",
         "trade_allowed":False,"status":"OK","note":""}
    # 1) критически плохие данные -> направленного прогноза нет
    if grade=="D" or n_ok<min_mod:
        out.update(status="INSUFFICIENT",side="neutral",confidence=0.0,
                   note="данные критически плохие (grade=%s, живых аналитиков %d/%d) — направленный прогноз не выдаётся"%(
                       "None" if grade is None else grade,n_ok,len(mm_schema.MODULES_REQUIRED)))
        if grade=="D": out["contradictions"]=["качество данных grade D: источники недоступны или несвежи"]
        return out
    # 2) взвешенное мнение узлов (все ok-модули, без порога голосования —
    #    прогноз агрегирует информацию, а не принимает торговое решение)
    long_s=short_s=0.0;flats=[]
    for name,v in modules.items():
        if not v.get("ok"): continue
        if v["side"]=="long": long_s+=float(v.get("confidence") or 0)
        elif v["side"]=="short": short_s+=float(v.get("confidence") or 0)
        else: flats.append(name)
    denom=long_s+short_s
    if denom<=0:
        out.update(side="neutral",confidence=0.35,band="LOW",
                   note="все живые аналитики нейтральны — перевеса нет, прогноз NEUTRAL")
        out["arguments"]=[v.get("note","") for v in modules.values() if v.get("ok")][:3]
        return out
    agreement=(long_s-short_s)/denom
    side="long" if agreement>0 else "short"
    # 3) уверенность: относительная, честная, с потолками при конфликтах/шуме
    conf=0.35+0.30*abs(agreement)+0.05*max(0,n_ok-2)
    cstatus=(decision or {}).get("status")
    if cstatus=="AGREE" and (decision or {}).get("side")==side: conf+=0.05
    if (decision or {}).get("conflicts"): conf=min(conf,0.60)   # неразрешённые конфликты
    if regime=="volatile": conf=min(conf,0.60)                  # всё шум
    if regime in (None,"unknown"): conf=min(conf,0.55)          # фаза не определена
    # memory-поправка (проверенная статистика, n>=10; правила ворот не трогает)
    shift,memo=_memory_adjust(side,regime,c)
    conf+=shift
    conf=round(_clamp(conf,0.35,0.75),4)
    # 4) аргументы/противоречия/сценарии — из вердиктов узлов, ничего не выдумываем
    supporting=[v for v in modules.values() if v.get("ok") and v["side"]==side]
    opposing=[v for v in modules.values() if v.get("ok") and v["side"] not in (side,"flat")]
    out["side"]=side;out["confidence"]=conf;out["band"]=_band(conf)
    if cstatus=="AGREE": out["p"]=(decision or {}).get("p")  # p совета (не вероятность прогноза)
    out["arguments"]=[e for v in supporting for e in (v.get("evidence") or [])][:6]
    out["contradictions"]=[e for v in opposing for e in (v.get("evidence") or [])][:4]
    for k in ((decision or {}).get("conflicts") or []):
        out["contradictions"].append("конфликт узлов: %s (%s) vs %s (%s)"%(k.get("a"),k.get("a_side"),k.get("b"),k.get("b_side")))
    if flats: out["contradictions"].append("нейтральны: %s"%", ".join(flats))
    if memo: out["contradictions"].append(memo)
    alt_side="short" if side=="long" else "long"
    alt_supp=[v for v in modules.values() if v.get("ok") and v["side"]==alt_side]
    if alt_supp:
        out["alt_scenario"]="возврат к %s при подтверждении: %s"%(alt_side.upper(),
            "; ".join(alt_supp[0].get("evidence") or [])[:160])
    else:
        out["alt_scenario"]="возврат к %s при подтверждении объёмом/потоком (сейчас узлов-сторонников нет)"%alt_side.upper()
    out["invalidate"]=[e for v in supporting for e in (v.get("invalidate") or [])][:4]
    # 5) торговый статус: прогноз не обходит Risk Gate
    gate_status="NOT_CHECKED"
    if gate is not None:
        gate_status="PASS" if gate.get("pass") else "BLOCK"
    elif cstatus in ("NO_TRADE","HALT"):
        gate_status="BLOCK"  # право вето ворот: council NO_TRADE/HALT -> BLOCK
    out["gate_status"]=gate_status
    out["trade_allowed"]=bool(cstatus=="AGREE" and gate_status=="PASS")
    out["note"]="уверенность — относительная сила совпадения узлов, НЕ калиброванная вероятность"
    if (decision or {}).get("conflicts"):
        out["note"]+="; конфликты узлов зафиксированы, потолок уверенности 0.60"
    return out
# ---------------- память прогнозов: store / verify / stats ----------------
def store(rec,path=None):
    """Сохранить прогноз в data/forecasts.csv (только значимые поля, без мусора)."""
    p=path or _fpath()
    os.makedirs(os.path.dirname(p),exist_ok=True)
    rows=[]
    if os.path.exists(p):
        with open(p,encoding="utf-8",newline="") as f: rows=list(csv.DictReader(f))
    row={k:str(rec.get(k,"")) for k in FIELDS}
    if not row.get("id"):
        base="F"+str(int(time.time()*1000));row["id"]=base
        while any(r.get("id")==row["id"] for r in rows):  # защита от коллизий в одну миллисекунду
            row["id"]=row["id"]+"x"
    if not row.get("ts"): row["ts"]=datetime.datetime.now().isoformat(timespec="seconds")
    for k in ("arguments","contradictions","invalidate"):
        v=rec.get(k)
        if isinstance(v,list): row[k]="; ".join(str(x) for x in v)
    rows.append(row)
    with open(p,"w",encoding="utf-8",newline="") as f:
        w=csv.DictWriter(f,fieldnames=FIELDS);w.writeheader();w.writerows(rows)
    return row["id"]
def _load(path=None):
    p=path or _fpath()
    if not os.path.exists(p): return []
    with open(p,encoding="utf-8",newline="") as f: return list(csv.DictReader(f))
def _save(rows,path=None):
    p=path or _fpath()
    os.makedirs(os.path.dirname(p),exist_ok=True)
    with open(p,"w",encoding="utf-8",newline="") as f:
        w=csv.DictWriter(f,fieldnames=FIELDS);w.writeheader();w.writerows(rows)
def verify(now_ts=None,cfg=None,price_fn=None,path=None):
    """Проверка старых прогнозов по фактическому рынку.

    Для каждой непроверенной записи, у которой истёк горизонт (ts + horizon_h), берём
    фактическую цену закрытия на момент горизонта (price_fn(pair, ts_ms) — по умолчанию
    klines Bybit) и сравниваем движение с прогнозом:
    hit=1 — направление совпало; hit=0 — не совпало; neutral — |move| < 0.5%.
    Возвращает (n_checked, rows_updated)."""
    now_ts=now_ts if now_ts is not None else time.time()
    rows=_load(path);n=0
    for r in rows:
        if r.get("verify_ts"): continue
        try:
            ts=datetime.datetime.fromisoformat(r["ts"]).timestamp()
        except (ValueError,TypeError): continue
        horizon=float(r.get("horizon_h") or 24)
        if ts+horizon*3600.0>now_ts: continue  # горизонт ещё не истёк
        try: entry=float(r.get("entry_price") or 0)
        except ValueError: continue
        if entry<=0: continue
        if price_fn is None:
            price_fn=_bybit_price_at
        target_ms=int((ts+horizon*3600.0)*1000)
        try: exit_px=price_fn(str(r.get("pair") or "BTCUSDT"),target_ms)
        except Exception: exit_px=None
        if exit_px is None or exit_px<=0: continue  # данных нет — оставляем на следующую проверку
        move=100.0*(exit_px-entry)/entry
        side=str(r.get("side") or "neutral")
        if side=="long": hit=1 if move>0 else 0
        elif side=="short": hit=1 if move<0 else 0
        else: hit=1 if abs(move)<0.5 else 0
        r["verify_ts"]=datetime.datetime.fromtimestamp(now_ts).isoformat(timespec="seconds")
        r["exit_price"]=str(round(exit_px,8));r["move_pct"]=str(round(move,3));r["hit"]=str(hit)
        n+=1
    if n: _save(rows,path)
    return n,rows
def _bybit_price_at(pair,ts_ms):
    """Цена закрытия часовой свечи, ближайшей к ts_ms (Bybit с fallback на Binance/OKX)."""
    import mm_klines,mm_sources
    rows,_=mm_klines.from_bybit(pair,"60",200)
    if not rows:
        r=mm_sources.bybit_klines(pair,"60",200)
        rows=r.get("rows") or []
    if not rows: return None
    best=None
    for r in rows:
        if r[0]<=ts_ms: best=r
    if best is None: return rows[0][4]
    return best[4]
def stats(path=None,min_n=5):
    """Статистика проверенных прогнозов: hit-rate по режимам, завышение уверенности,
    повторяющиеся ошибки. Используется в памяти и экспорте ZIP."""
    rows=[r for r in _load(path) if r.get("hit") in ("0","1")]
    def _agg(sel):
        if not sel: return {"n":0}
        hits=[int(r["hit"]) for r in sel]
        confs=[float(r["confidence"]) for r in sel if r.get("confidence")]
        hr=round(sum(hits)/len(hits),3)
        out={"n":len(sel),"hit_rate":hr}
        if confs: out["mean_confidence"]=round(sum(confs)/len(confs),3)
        if confs: out["overconfidence"]=round(out["mean_confidence"]-hr,3)
        return out
    out={"checked":_agg(rows),"by_regime":{},"notes":[]}
    by={}
    for r in rows: by.setdefault(r.get("regime") or "unknown",[]).append(r)
    for rg,sel in sorted(by.items()):
        a=_agg(sel)
        if a.get("n",0)>=min_n:
            out["by_regime"][rg]=a
            if a["hit_rate"]<0.4:
                out["notes"].append("режим %s: hit-rate %.0f%% при n=%d — повторяющаяся ошибка, прогнозам в этой фазе доверяй меньше"%(
                    rg,a["hit_rate"]*100,a["n"]))
            if a.get("overconfidence",0)>0.15:
                out["notes"].append("режим %s: уверенность систематически завышена (+%.0fпп к hit-rate)"%(
                    rg,a["overconfidence"]*100))
        else:
            out["by_regime"][rg]=a
    un=[r for r in _load(path) if not r.get("verify_ts")]
    out["unchecked"]=len(un)
    return out
def _memory_adjust(side,regime,cfg):
    """Поправка уверенности из памяти прогнозов (n>=10 в режиме, только проверенные).
    Возвращает (shift, note). Правила Risk Gate не меняются никогда."""
    try:
        st=stats()
        rg=st.get("by_regime",{}).get(regime or "unknown",{})
        if rg.get("n",0)<10: return 0.0,None
        hr=rg.get("hit_rate")
        if hr is None: return 0.0,None
        if hr>=0.60: return +0.05,"память: прогнозы в режиме %s исторически точны (%.0f%%, n=%d) — уверенность слегка повышена"%(regime or "unknown",hr*100,rg["n"])
        if hr<=0.40: return -0.05,"память: прогнозы в режиме %s исторически слабы (%.0f%%, n=%d) — уверенность снижена"%(regime or "unknown",hr*100,rg["n"])
    except Exception:
        pass
    return 0.0,None
def explain(f):
    """Компактный текст прогноза для чата (честный формат из ТЗ)."""
    L=["FORECAST: %s"%(str(f.get("side","neutral")).upper()),
       "Уверенность: %d%% (%s band)"%(round(float(f.get("confidence") or 0)*100),f.get("band","LOW"))]
    if f.get("arguments"): L.append("Основные аргументы:\n- "+"\n- ".join(f["arguments"][:4]))
    if f.get("contradictions"): L.append("Противоречия:\n- "+"\n- ".join(f["contradictions"][:4]))
    if f.get("alt_scenario"): L.append("Альтернативный сценарий: %s"%f["alt_scenario"])
    if f.get("invalidate"): L.append("Условия отмены:\n- "+"\n- ".join(f["invalidate"][:3]))
    L.append("Статус сделки: %s"%f.get("gate_status","NOT_CHECKED"))
    if not f.get("trade_allowed") and f.get("council_status") in ("NO_TRADE","HALT"):
        L.append("Причина: Council: %s"%(f.get("council_status")))
    if f.get("status")=="INSUFFICIENT": L.insert(0,"ПРОГНОЗ НЕДОСТУПЕН: %s"%f.get("note",""))
    return "\n".join(L)
def selftest():
    import tempfile
    # --- генерация прогноза: конфликт ta long vs flow short -> NO_TRADE, но прогноз есть
    up=[[i,100+i*0.1,100.5+i*0.1,99.5+i*0.1,100+i*0.1,1] for i in range(210)]
    up[-1]=[209,120.9,122.5,120.8,122.0,1];up[-2]=[208,120.8,121.0,119.0,120.9,1]
    war={"parts":{"tape":{"ok":True,"cvd":-6.0,"delta_pct":-5.0,"price_change_pct":-0.5,"divergence":False,"n":500,"age_sec":2.0},
                  "book":{"ok":True,"imbalance_pct":-30.0},"deriv":{"ok":True,"funding_pct":0.01,"oi":68000.0},
                  "fng":{"ok":True,"value":50,"delta3":0,"delta7":0,"classification":"Neutral"},
                  "lsr":{"ok":True,"ratio":1.0,"long_pct":50.0,"short_pct":50.0}},"grade":"A"}
    import mm_council
    d=mm_council.resolve(war,up,regime="trend_up")
    assert d["status"]=="NO_TRADE",d["status"]
    f=forecast(d,snap=war,regime="trend_up",pair="BTCUSDT")
    # та-же история из интеграционных тестов: ta long (conf~0.65) vs flow short (conf~0.9)
    assert f["status"]=="OK" and f["side"] in ("long","short"),f
    assert f["council_status"]=="NO_TRADE" and f["gate_status"]=="BLOCK"
    assert f["trade_allowed"] is False           # прогноз не обходит Risk Gate
    assert f["confidence"]<=0.60+1e-9            # потолок при конфликтах
    assert f["contradictions"] and f["invalidate"]
    assert "НЕ калиброванная вероятность" in f["note"]
    # согласие: прогноз long,_gate PASS -> trade_allowed
    agree={"parts":{"tape":{"ok":True,"cvd":5.0,"delta_pct":4.0,"price_change_pct":0.4,"divergence":False,"n":500,"age_sec":2.0},
                    "book":{"ok":True,"imbalance_pct":10.0},"deriv":{"ok":True,"funding_pct":0.01,"oi":68000.0},
                    "fng":{"ok":True,"value":50,"delta3":0,"delta7":0,"classification":"Neutral"},
                    "lsr":{"ok":True,"ratio":1.0,"long_pct":50.0,"short_pct":50.0}},"grade":"A"}
    d2=mm_council.resolve(agree,up,regime="trend_up")
    assert d2["status"]=="AGREE"
    f2=forecast(d2,snap=agree,regime="trend_up",gate={"pass":1,"block":0})
    assert f2["side"]=="long" and f2["trade_allowed"] is True and f2["gate_status"]=="PASS"
    assert f2["confidence"]>f["confidence"] or f2["band"]!=f["band"]  # согласие сильнее конфликта
    # критически плохие данные -> INSUFFICIENT, направления нет
    d3=mm_council.resolve(agree,up,regime="trend_up")
    f3=forecast(d3,snap={"grade":"D"},regime="trend_up")
    assert f3["status"]=="INSUFFICIENT" and f3["side"]=="neutral" and f3["confidence"]==0.0
    # мало живых модулей -> INSUFFICIENT
    d4=dict(d2);d4["modules"]={k:dict(v,ok=False) for k,v in d2["modules"].items()}
    f4=forecast(d4,snap=agree,regime="trend_up")
    assert f4["status"]=="INSUFFICIENT" and f4["side"]=="neutral"
    # все нейтральны -> NEUTRAL с низкой уверенностью
    d5=dict(d2);d5["modules"]={k:dict(v,side="flat") for k,v in d2["modules"].items()}
    f5=forecast(d5,snap=agree,regime="range")
    assert f5["side"]=="neutral" and f5["confidence"]<=0.35+1e-9
    # --- store/verify/stats (временный каталог, без сети) ---
    old=_base()
    try:
        with tempfile.TemporaryDirectory() as td:
            os.environ["MM5_DATA_DIR"]=td
            past=datetime.datetime.fromtimestamp(time.time()-25*3600).isoformat(timespec="seconds")
            fid=store({"ts":past,"pair":"BTCUSDT","interval":"60","regime":"trend_up","side":"long",
                       "confidence":0.62,"p":0.58,"horizon_h":24,"entry_price":100.0,
                       "council_status":"AGREE","gate_status":"PASS","data_grade":"A",
                       "arguments":["a1","a2"],"contradictions":["c1"],"invalidate":["i1"]})
            assert store({"ts":past,"pair":"ETHUSDT","interval":"60","regime":"range","side":"short",
                          "confidence":0.55,"horizon_h":24,"entry_price":50.0,
                          "council_status":"NO_TRADE","gate_status":"BLOCK","data_grade":"C"},path=os.path.join(td,"forecasts.csv"))
            # цена выросла на 5%: long-прогноз hit, short-прогноз miss
            now=time.time()
            n,rows=verify(now_ts=now,price_fn=lambda pair,ts: {"BTCUSDT":105.0,"ETHUSDT":52.5}[pair])
            assert n==2,rows
            byid={r["id"]:r for r in rows}
            assert byid[fid]["hit"]=="1" and byid[fid]["move_pct"]=="5.0"
            eth=[r for r in rows if r["pair"]=="ETHUSDT"][0]
            assert eth["hit"]=="0" and eth["move_pct"]=="5.0"
            # повторная verify ничего не делает (уже проверено)
            n2,_=verify(now_ts=now,price_fn=lambda p,t: 1.0)
            assert n2==0
            st=stats()
            assert st["checked"]["n"]==2 and st["checked"]["hit_rate"]==0.5
            assert st["unchecked"]==0
            # горизонт не истёк -> не проверяется
            store({"pair":"BTCUSDT","regime":"range","side":"long","confidence":0.5,
                   "horizon_h":24,"entry_price":100.0})
            n3,_=verify(now_ts=now,price_fn=lambda p,t: 200.0)
            assert n3==0
            # explain
            txt=explain(f)
            assert "FORECAST:" in txt and "Статус сделки" in txt
    finally:
        os.environ["MM5_DATA_DIR"]=old if old else ""
        if not old: del os.environ["MM5_DATA_DIR"]
    print("mm_forecast: OK")
if __name__=="__main__":
    import argparse
    ap=argparse.ArgumentParser()
    ap.add_argument("--selftest",action="store_true")
    ap.add_argument("--verify",action="store_true",help="проверить старые прогнозы по рынку")
    ap.add_argument("--stats",action="store_true")
    g=ap.parse_args()
    if g.selftest: selftest()
    elif g.verify:
        n,_=verify();print(json.dumps({"checked":n},ensure_ascii=False))
    elif g.stats: print(json.dumps(stats(),ensure_ascii=False,indent=1))
    else: print("use --selftest | --verify | --stats")
