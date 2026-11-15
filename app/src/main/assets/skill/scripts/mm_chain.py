#!/usr/bin/env python3
"""Оркестратор цепочки анализа ENA-MIND v6.0 (точка А -> точка Б).

Прогоняет ВСЮ нейронную цепочку и возвращает состояния узлов для визуализации:

  DATA INGESTION -> DATA CHECK -> REGIME -> TA / FLOW / DERIV / SENTIMENT
  -> COUNCIL -> FORECAST -> RISK GATE -> FINAL OUTPUT

Каждый узел: name / status (PENDING|RUNNING|OK|STOPPED|SKIPPED) / summary / reason /
elapsed_ms. Статусы отражают РЕАЛЬНОЕ выполнение (прогресс-колбэк вызывается при
каждом изменении — не декоративная анимация). Цепочка останавливается с reason при
критическом провале данных (grade D / свечи недоступны) — Forecast в этом случае
честно возвращает INSUFFICIENT без направления.

run_chain — мост для Android (Chaquopy): вызывается из Kotlin, возвращает единый JSON.
Параметры snap/klines позволяют прогнать цепочку на готовых данных (тесты, повтор).
"""
import datetime,json,time
import mm_config,mm_data,mm_klines,mm_live,mm_regime,mm_analyst,mm_council,mm_forecast,mm_risk
NODE_ORDER=["DATA INGESTION","DATA CHECK","REGIME","TA ANALYST","FLOW ANALYST",
            "DERIV ANALYST","SENTIMENT ANALYST","COUNCIL","FORECAST","RISK GATE","FINAL OUTPUT"]
def _nodes_init():
    return {n:{"name":n,"status":"PENDING","summary":"","reason":"","elapsed_ms":0} for n in NODE_ORDER}
def _norm_news(news):
    """Валидация новостного фона: список dict, дедуп по заголовку, свежесть не проверяем
    здесь (её обеспечивает источник), сортировка не нужна. Возвращает (items, dropped)."""
    if not news: return [],0
    seen=set();out=[];drop=0
    for n in news:
        if not isinstance(n,dict) or not n.get("title"): drop+=1;continue
        key=str(n.get("title","")).strip().lower()
        if key in seen: drop+=1;continue
        seen.add(key)
        out.append({"source":str(n.get("source","?")),"title":str(n.get("title"))[:200],
                    "importance":str(n.get("importance","medium")),
                    "impact":n.get("impact",0.0),
                    "published_ts":n.get("published_ts"),"fetched_ts":n.get("fetched_ts"),
                    "assets":n.get("assets",[]),"url":n.get("url","")})
    return out,drop
def run_chain(symbol="BTCUSDT",interval="60",budget=30.0,news=None,plan=None,
              horizon_h=None,cfg=None,snap=None,klines=None,extra_parts=None,
              progress=None,store_forecast=True,fetch_news=False):
    """Полный прогон цепочки. Возвращает dict с nodes/snap/regime/analysts/decision/
    forecast/gate/output. progress(node_dict) вызывается при каждой смене статуса.
    fetch_news: если True и новостей не передали — попробовать живой RSS-сбор (mm_news);
    приложение обычно передаёт свои новости, CLI/скилл собирает сам. Ошибка сети — не стоп:
    цепочка продолжается без новостного фона."""
    c=cfg or mm_config.load()
    nodes=_nodes_init()
    t_start=time.time()
    def _emit(name):
        if progress:
            try: progress(dict(nodes[name]))
            except Exception: pass
    def _run(name,summary=""):
        nodes[name]["status"]="RUNNING";nodes[name]["summary"]=summary;_emit(name)
    def _done(name,summary,elapsed=True):
        nodes[name]["status"]="OK";nodes[name]["summary"]=summary
        nodes[name]["elapsed_ms"]=int((time.time()-t_start)*1000);_emit(name)
    def _stop(name,reason,summary=""):
        nodes[name]["status"]="STOPPED";nodes[name]["reason"]=reason;nodes[name]["summary"]=summary or reason
        nodes[name]["elapsed_ms"]=int((time.time()-t_start)*1000);_emit(name)
    def _skip(name,reason):
        nodes[name]["status"]="SKIPPED";nodes[name]["reason"]=reason;_emit(name)
    # ---------- Узел 1: DATA INGESTION ----------
    _run("DATA INGESTION")
    try:
        if snap is None:
            snap=mm_live.snapshot(symbol,budget_sec=budget,cfg=c,extra_parts=extra_parts)
        grade=snap.get("grade")
        srcs=snap.get("sources",{})
        n_avail=sum(1 for v in srcs.values() if v=="AVAILABLE")
        if grade in ("A","C"):
            _done("DATA INGESTION","grade %s: цена %s, источников AVAILABLE %d/%d"%(
                grade,snap.get("price"),n_avail,len(srcs)))
        else:
            _stop("DATA INGESTION","grade D: обязательные источники недоступны (%s)"%(
                ", ".join("%s=%s"%(k,v) for k,v in srcs.items() if v!="AVAILABLE") or "нет данных"))
    except Exception as e:
        snap={"grade":"D","parts":{},"sources":{}}
        _stop("DATA INGESTION","ошибка получения данных: %s"%str(e)[:160])
    # v6.1: честные лоты инструмента и плечо из instruments-info/risk-limit — теперь,
    # когда снимок получен (переопределяют общие пороги Risk Gate: min_notional 100 ->
    # реальные 5 USDT, max_lev 10 -> реальное плечо инструмента)
    try:
        _ins=(snap or {}).get("parts",{}).get("instruments",{})
        if _ins.get("ok"):
            for src,dst in (("qty_step","qty_step"),("min_qty","min_qty"),("min_notional_value","min_notional")):
                v=_ins.get(src)
                if v is not None and v>0: c[dst]=v
        _rl=(snap or {}).get("parts",{}).get("risk_limits",{})
        if _rl.get("ok") and _rl.get("max_leverage"):
            c["max_lev"]=min(float(c.get("max_lev",10.0)),float(_rl["max_leverage"]))
    except (TypeError,ValueError): pass
    # ---------- Узел 2: проверка данных (DATA CHECK) ----------
    if nodes["DATA INGESTION"]["status"]=="OK":
        _run("DATA CHECK")
        try:
            if klines is None:
                klines,src,q=mm_klines.fetch(symbol,interval,300,cfg=c)
            else:
                src="injected";q=mm_data.series_quality(klines)
            if not klines or not q.get("ok"):
                issues=(q.get("issues") or ["нет свечей"]) if not klines else ["dupes=%s,unordered=%s,bad_ohlc=%s"%(q.get("dupes"),q.get("unordered"),q.get("bad_ohlc"))]
                _stop("DATA CHECK","свечной ряд не прошёл проверку качества (%s, источник=%s)"%("; ".join(map(str,issues)),src))
            else:
                # v6.1: новостей не передали — пробуем живой RSS-сбор (только по явному запросу,
                # тесты всегда offline: fetch_news=False по умолчанию). Ошибка сети — не стоп.
                news_src="переданы"
                if not news and fetch_news and c.get("news_enabled"):
                    try:
                        import mm_news
                        news,nmeta=mm_news.fetch_news(cfg=c)
                        src_ok=sum(1 for v in (nmeta.get("sources") or {}).values() if v=="AVAILABLE")
                        news_src=("RSS %d/%d лент"%(src_ok,nmeta.get("n_feeds",0))) if news else "RSS недоступен"
                    except Exception:
                        news=[];news_src="RSS недоступен"
                n_news,drop=_norm_news(news)
                _done("DATA CHECK","свечи %d шт (источник=%s), новости %d (%s)%s"%(
                    len(klines),src,len(n_news),news_src,
                    (", дубликатов удалено %d"%drop) if drop else ""))
                news=n_news
        except Exception as e:
            _stop("DATA CHECK","ошибка проверки данных: %s"%str(e)[:160])
    else:
        _skip("DATA CHECK","остановлено на DATA INGESTION")
    # ---------- Узел 3: REGIME ----------
    regime=None;reg=None
    if nodes["DATA CHECK"]["status"]=="OK":
        _run("REGIME")
        try:
            reg=mm_regime.classify(klines)
            regime=reg["kind"]
            _done("REGIME","%s (conf=%.2f, %s)"%(regime,reg.get("confidence",0),reg.get("note","")[:100]))
        except Exception as e:
            regime="unknown";reg={"kind":"unknown","note":"ошибка: %s"%e}
            _done("REGIME","unknown (ошибка классификации: %s)"%str(e)[:120])
    else:
        _skip("REGIME","остановлено на DATA CHECK")
    # ---------- Узлы 4-7: независимые аналитики ----------
    analysts={}
    if regime is not None:
        try:
            analysts=mm_analyst.analyze_all(snap=snap,klines=klines,regime=regime,cfg=c,news=news)
        except Exception as e:
            analysts={}
        for node_name,mod in (("TA ANALYST","ta"),("FLOW ANALYST","flow"),
                              ("DERIV ANALYST","deriv"),("SENTIMENT ANALYST","sentiment")):
            v=analysts.get(mod)
            if v is None:
                _stop(node_name,"аналитик не выполнен (внутренняя ошибка)")
            elif v.get("ok"):
                _done(node_name,"%s conf=%.2f: %s"%(str(v.get("side","flat")).upper(),float(v.get("confidence") or 0),(v.get("note") or "")[:90]))
            else:
                _stop(node_name,"INSUFFICIENT: %s"%(v.get("note") or "нет данных")[:140])
    else:
        for n in ("TA ANALYST","FLOW ANALYST","DERIV ANALYST","SENTIMENT ANALYST"):
            _skip(n,"нет данных для анализа")
    # ---------- Узел 8: COUNCIL ----------
    decision=None
    if regime is not None and analysts:
        _run("COUNCIL")
        try:
            decision=mm_council.resolve(snap=snap,klines=klines,cfg=c,regime=regime)
            _done("COUNCIL","%s — %s"%(decision["status"],(decision.get("note") or "")[:120]))
        except Exception as e:
            decision={"status":"HALT","side":"flat","note":"ошибка совета: %s"%str(e)[:140],
                      "modules":analysts,"regime":regime,"conflicts":[],"audit":[],"p":0.5,
                      "confidence":0.0,"p_hat_from_feedback":None}
            _done("COUNCIL","HALT (ошибка: %s)"%str(e)[:120])
    else:
        _skip("COUNCIL","аналитики не выполнены")
    # ---------- Узел 9: FORECAST (независим от Risk Gate) ----------
    fc=None
    if decision is not None:
        _run("FORECAST")
        try:
            fc=mm_forecast.forecast(decision,snap=snap,regime=regime,cfg=c,news=news,
                                    horizon_h=horizon_h,pair=symbol,interval=interval)
            if store_forecast:
                mm_forecast.store({"pair":symbol,"interval":interval,"regime":regime or "unknown",
                    "side":fc["side"],"confidence":fc["confidence"],"p":fc.get("p"),
                    "horizon_h":fc["horizon_h"],"entry_price":(snap or {}).get("price"),
                    "council_status":fc["council_status"],"gate_status":fc["gate_status"],
                    "data_grade":(snap or {}).get("grade"),
                    "arguments":fc["arguments"],"contradictions":fc["contradictions"],
                    "invalidate":fc["invalidate"],"note":fc["note"]})
            if fc["status"]=="INSUFFICIENT":
                _stop("FORECAST","прогноз недоступен: %s"%(fc.get("note") or "")[:140],
                      "INSUFFICIENT — направления нет")
            else:
                _done("FORECAST","%s conf=%d%% (%s)"%(str(fc["side"]).upper(),round(fc["confidence"]*100),fc["band"]))
        except Exception as e:
            fc=mm_forecast.forecast(decision,snap=None,regime=regime,cfg=c)
            fc["status"]="INSUFFICIENT";fc["note"]="ошибка прогноза: %s"%str(e)[:140]
            _stop("FORECAST","ошибка прогноза: %s"%str(e)[:140])
    else:
        _skip("FORECAST","цепочка остановлена ранее")
    # ---------- Узел 10: RISK GATE (право окончательного вето) ----------
    gate=None
    if decision is not None:
        _run("RISK GATE")
        try:
            if decision["status"] in ("NO_TRADE","HALT"):
                gate={"date":datetime.date.today().isoformat(),"pass":0,"block":1,
                      "results":[{"verdict":"BLOCK","reasons":["council: %s — %s"%(decision["status"],(decision.get("note") or "")[:160])]}]}
                _stop("RISK GATE","BLOCK: council %s — вето ворота (прогноз это НЕ торговое решение)"%decision["status"])
            elif plan:
                try:
                    plans=plan if isinstance(plan,list) else [plan]
                    gate=mm_risk.check(plans,cfg=c,decision=decision,data_grade=snap.get("grade"))
                    if gate.get("pass"): _done("RISK GATE","PASS: %d план(ов) прошёл(ли) ворота"%gate["pass"])
                    else: _stop("RISK GATE","BLOCK: %s"%"; ".join(r.get("reasons",["причины недоступны"])[0] for r in gate.get("results",[]) if r.get("verdict")=="BLOCK")[:160])
                except Exception as e:
                    gate=None;_stop("RISK GATE","ошибка проверки плана: %s"%str(e)[:140])
            else:
                gate=None
                _skip("RISK GATE","нет торгового плана — сделка не запрашивалась")
        except Exception as e:
            gate=None;_stop("RISK GATE","ошибка: %s"%str(e)[:140])
        # обновляем trade-статус прогноза реальным вердиктом ворот
        if fc is not None:
            if gate is None:
                if decision["status"] in ("NO_TRADE","HALT"): fc["gate_status"]="BLOCK"
                else: fc["gate_status"]="NOT_CHECKED"
            else:
                fc["gate_status"]="PASS" if gate.get("pass") else "BLOCK"
            fc["trade_allowed"]=bool(decision["status"]=="AGREE" and fc["gate_status"]=="PASS")
    else:
        _skip("RISK GATE","цепочка остановлена ранее")
    # ---------- Узел 11: FINAL OUTPUT ----------
    _run("FINAL OUTPUT")
    output=synthesize(symbol,interval,snap,reg,analysts,decision,fc,gate,klines)
    _done("FINAL OUTPUT","готово: %s"%output.get("headline","")[:120])
    return {"symbol":symbol,"interval":interval,"ts":time.time(),
            "elapsed_ms":int((time.time()-t_start)*1000),
            "nodes":[nodes[n] for n in NODE_ORDER],"node_map":nodes,
            "snap":snap,"regime":reg,"analysts":analysts,"decision":decision,
            "forecast":fc,"gate":gate,"output":output}
def synthesize(symbol,interval,snap,reg,analysts,decision,fc,gate,klines):
    """Узел 7 — финальный синтез: компактный, но содержательный итог для пользователя."""
    d=snap or {}
    market={"price":d.get("price"),"funding_pct":d.get("funding_pct"),"oi":d.get("oi"),
            "oi_pct_change":d.get("oi_pct_change"),"lsr":d.get("lsr"),"fng":d.get("fng"),
            "fng_delta3":d.get("fng_delta3"),"fng_delta7":d.get("fng_delta7"),
            "grade":d.get("grade"),"sources":d.get("sources",{}),"ts":d.get("ts")}
    an={}
    for m,v in (analysts or {}).items():
        an[m]={"ok":v.get("ok"),"side":v.get("side"),"p":v.get("p"),
               "confidence":v.get("confidence"),"note":v.get("note"),
               "evidence":(v.get("evidence") or [])[:3],"invalidate":(v.get("invalidate") or [])[:2]}
    if decision:
        council={"status":decision.get("status"),"side":decision.get("side"),"p":decision.get("p"),
                 "note":decision.get("note"),"regime":decision.get("regime"),
                 "conflicts":decision.get("conflicts"),"p_hat_from_feedback":decision.get("p_hat_from_feedback")}
    else:
        council={"status":"NOT_RUN","note":"цепочка остановлена"}
    if fc:
        forecast={"side":fc.get("side"),"confidence":fc.get("confidence"),"band":fc.get("band"),
                  "status":fc.get("status"),"horizon_h":fc.get("horizon_h"),
                  "arguments":(fc.get("arguments") or [])[:5],
                  "contradictions":(fc.get("contradictions") or [])[:5],
                  "alt_scenario":fc.get("alt_scenario"),"invalidate":(fc.get("invalidate") or [])[:3],
                  "council_status":fc.get("council_status"),"gate_status":fc.get("gate_status"),
                  "trade_allowed":fc.get("trade_allowed"),"note":fc.get("note")}
    else:
        forecast={"status":"NOT_RUN"}
    if gate:
        g={"verdict":"PASS" if gate.get("pass") else "BLOCK","pass":gate.get("pass"),
           "block":gate.get("block"),
           "reasons":[r for x in gate.get("results",[]) if x.get("verdict")=="BLOCK" for r in x.get("reasons",[])][:6]}
    elif decision and decision.get("status") in ("NO_TRADE","HALT"):
        g={"verdict":"BLOCK","reasons":["council: %s"%decision.get("status")]}
    else:
        g={"verdict":"NOT_CHECKED","reasons":[]}
    # headline — одна строка сути
    if fc and fc.get("status")=="OK":
        head="FORECAST %s (%d%%, %s) | сделка: %s"%(str(fc["side"]).upper(),round(fc["confidence"]*100),fc["band"],fc.get("gate_status","NOT_CHECKED"))
    elif fc:
        head="FORECAST недоступен (%s)"%fc.get("status")
    else:
        head="анализ остановлен"
    return {"headline":head,"market":market,
            "regime":{"kind":(reg or {}).get("kind"),"confidence":(reg or {}).get("confidence"),
                      "note":(reg or {}).get("note")},
            "analysts":an,"council":council,"forecast":forecast,"gate":g,
            "klines_n":len(klines) if klines else 0}
def explain(res):
    """Компактный текстовый отчёт цепочки для чата (Узел 7 по ТЗ, 10 пунктов)."""
    o=res.get("output") or {}
    m=o.get("market",{});L=[]
    L.append("1. Состояние рынка: %s — цена %s, funding %s%%, OI %s (24ч %s%%), LSR %s, F&G %s (d3 %s), качество данных: %s"%(
        res.get("symbol"),m.get("price"),m.get("funding_pct"),m.get("oi"),m.get("oi_pct_change"),
        m.get("lsr"),m.get("fng"),m.get("fng_delta3"),m.get("grade")))
    rg=o.get("regime",{})
    L.append("2. Режим рынка: %s (conf %s)"%(rg.get("kind"),rg.get("confidence")))
    for i,(mod,ru) in enumerate((("ta","3. TA"),("flow","4. FLOW"),("deriv","5. DERIV"),("sentiment","6. SENTIMENT")),start=0):
        a=(o.get("analysts") or {}).get(mod) or {}
        if a.get("ok"):
            L.append("%s: %s (conf %.0f%%) — %s"%(ru,str(a.get("side","flat")).upper(),float(a.get("confidence") or 0)*100,(a.get("note") or "")[:110]))
        else:
            L.append("%s: INSUFFICIENT — %s"%(ru,(a.get("note") or "нет данных")[:110]))
    c=o.get("council",{})
    L.append("7. Council: %s — %s"%(c.get("status"),(c.get("note") or "")[:130]))
    f=o.get("forecast",{})
    if f.get("status")=="OK":
        L.append("8. Прогноз: %s, уверенность %d%% (%s band). Альтернатива: %s"%(
            str(f.get("side")).upper(),round(float(f.get("confidence") or 0)*100),f.get("band"),(f.get("alt_scenario") or "")[:130]))
        if f.get("contradictions"): L.append("   Противоречия: %s"%"; ".join(f["contradictions"][:3])[:200])
        if f.get("invalidate"): L.append("   Отмена: %s"%"; ".join(f["invalidate"][:2])[:160])
    else:
        L.append("8. Прогноз: %s — %s"%(f.get("status","NOT_RUN"),(f.get("note") or "")[:120]))
    g=o.get("gate",{})
    L.append("9. Risk Gate: %s%s"%((g.get("verdict") or "NOT_CHECKED"),(": "+"; ".join(g.get("reasons",[]))[:160]) if g.get("reasons") else ""))
    L.append("10. Итог: %s"%o.get("headline",""))
    return "\n".join(L)
def selftest():
    # offline-прогон на готовом снимке и свечах
    up=[[i,100+i*0.1,100.5+i*0.1,99.5+i*0.1,100+i*0.1,1] for i in range(220)]
    up[-1]=[219,120.9,122.5,120.8,122.0,1];up[-2]=[218,120.8,121.0,119.0,120.9,1]
    agree={"parts":{"tape":{"ok":True,"cvd":5.0,"delta_pct":4.0,"price_change_pct":0.4,"divergence":False,"n":500,"age_sec":2.0},
                    "book":{"ok":True,"imbalance_pct":10.0},"deriv":{"ok":True,"funding_pct":0.01,"oi":68000.0},
                    "fng":{"ok":True,"value":50,"delta3":0,"delta7":0,"classification":"Neutral"},
                    "lsr":{"ok":True,"ratio":1.0,"long_pct":50.0,"short_pct":50.0}},"grade":"A",
           "price":122.0,"sources":{"tape":"AVAILABLE","book":"AVAILABLE","deriv":"AVAILABLE",
                                     "lsr":"AVAILABLE","fng":"AVAILABLE"}}
    events=[]
    res=run_chain(symbol="BTCUSDT",interval="60",snap=agree,klines=up,
                  news=[{"title":"ETF inflows","impact":0.8,"importance":"high","source":"t"},
                        {"title":"ETF inflows","impact":0.8,"importance":"high","source":"t"}],  # дубликат
                  progress=lambda n: events.append((n["name"],n["status"])),
                  store_forecast=False)
    nm=res["node_map"]
    assert nm["DATA INGESTION"]["status"]=="OK"
    assert nm["DATA CHECK"]["status"]=="OK" and "дубликатов удалено 1" in nm["DATA CHECK"]["summary"]
    assert nm["REGIME"]["status"]=="OK"
    for n in ("TA ANALYST","FLOW ANALYST","DERIV ANALYST","SENTIMENT ANALYST","COUNCIL","FORECAST","FINAL OUTPUT"):
        assert nm[n]["status"]=="OK",(n,nm[n])
    assert res["decision"]["status"]=="AGREE"
    assert res["forecast"]["side"]=="long" and res["forecast"]["status"]=="OK"
    # без плана гейт пропущен, сделка не разрешена
    assert nm["RISK GATE"]["status"]=="SKIPPED"
    assert res["forecast"]["trade_allowed"] is False
    assert res["forecast"]["gate_status"]=="NOT_CHECKED"
    # прогресс-колбэк реально вызывался и статусы менялись
    assert events and ("DATA INGESTION","RUNNING") in events and ("COUNCIL","OK") in events
    # output содержит все блоки
    o=res["output"]
    for k in ("market","regime","analysts","council","forecast","gate"): assert k in o
    txt=explain(res)
    assert "10. Итог" in txt and "FORECAST LONG" in txt
    # с планом: согласие + валидный план -> PASS
    plan={"id":"C1","module":"chain","pair":"BTCUSDT","side":"long","entry":122.0,
          "stop":121.0,"tp1":124.7,"p":0.55,"lev":3,"risk_usd":1.5}
    res2=run_chain(symbol="BTCUSDT",interval="60",snap=agree,klines=up,plan=plan,store_forecast=False)
    nm2=res2["node_map"]
    # стоп 121 при депозите 100: min_notional 100 -> риск 1.5$ не проходит min lot? проверяем честный исход
    assert nm2["RISK GATE"]["status"] in ("OK","STOPPED")
    if res2["gate"] and res2["gate"].get("pass"):
        assert res2["forecast"]["trade_allowed"] is True and res2["forecast"]["gate_status"]=="PASS"
    else:
        assert res2["forecast"]["gate_status"]=="BLOCK" and res2["forecast"]["trade_allowed"] is False
    # конфликт: council NO_TRADE -> гейт BLOCK, но прогноз всё равно выдан
    war={"parts":{"tape":{"ok":True,"cvd":-6.0,"delta_pct":-5.0,"price_change_pct":-0.5,"divergence":False,"n":500,"age_sec":2.0},
                  "book":{"ok":False},"deriv":{"ok":True,"funding_pct":0.01,"oi":68000.0},
                  "fng":{"ok":True,"value":50,"delta3":0,"delta7":0,"classification":"Neutral"},
                  "lsr":{"ok":True,"ratio":1.0,"long_pct":50.0,"short_pct":50.0}},"grade":"C",
         "price":121.0,"sources":{"tape":"AVAILABLE","deriv":"AVAILABLE"}}
    res3=run_chain(symbol="BTCUSDT",interval="60",snap=war,klines=up,plan=plan,store_forecast=False)
    nm3=res3["node_map"]
    assert res3["decision"]["status"]=="NO_TRADE"
    assert nm3["RISK GATE"]["status"]=="STOPPED" and nm3["RISK GATE"]["reason"].startswith("BLOCK")
    assert res3["forecast"]["status"]=="OK" and res3["forecast"]["gate_status"]=="BLOCK"
    assert res3["forecast"]["trade_allowed"] is False
    assert res3["forecast"]["side"] in ("long","short")  # направление есть, сделки нет
    # критический провал данных: цепочка останавливается, прогноз INSUFFICIENT
    bad={"grade":"D","parts":{},"sources":{"tape":"UNAVAILABLE"},"price":None}
    res4=run_chain(symbol="BTCUSDT",interval="60",snap=bad,klines=None,store_forecast=False)
    nm4=res4["node_map"]
    assert nm4["DATA INGESTION"]["status"]=="STOPPED"
    assert nm4["COUNCIL"]["status"]=="SKIPPED" and nm4["FORECAST"]["status"]=="SKIPPED"
    assert res4["forecast"] is None and "анализ остановлен" in res4["output"]["headline"]
    print("mm_chain: OK")
if __name__=="__main__":
    import argparse
    ap=argparse.ArgumentParser()
    ap.add_argument("--selftest",action="store_true")
    ap.add_argument("--symbol",default="BTCUSDT")
    ap.add_argument("--interval",default="60")
    ap.add_argument("--budget",type=float,default=30.0)
    ap.add_argument("--plan",help="JSON-план для Risk Gate (иначе гейт пропускается)")
    ap.add_argument("--news",action="store_true",help="живой RSS-сбор новостного фона (mm_news)")
    ap.add_argument("--json",action="store_true")
    g=ap.parse_args()
    if g.selftest: selftest()
    else:
        plan=json.loads(g.plan) if g.plan else None
        res=run_chain(g.symbol,g.interval,g.budget,plan=plan,fetch_news=g.news)
        print(json.dumps(res["output"],ensure_ascii=False,indent=1) if g.json else explain(res))
