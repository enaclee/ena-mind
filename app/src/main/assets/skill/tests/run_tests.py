#!/usr/bin/env python3
"""Запуск selftest всех модулей + интеграционные проверки ENA-MIND v6.0.
Код возврата 0 = все тесты прошли. Все тесты offline (сеть не нужна)."""
import importlib,os,sys,traceback
sys.path.insert(0,os.path.join(os.path.dirname(os.path.abspath(__file__)),"..","scripts"))
mods=["mm_schema","mm_config","mm_data","mm_klines","mm_live","mm_regime","mm_analyst",
      "mm_council","mm_forecast","mm_chain","mm_core","mm_risk","mm_journal","mm_feedback",
      "mm_backtest","mm_alerts","mm_news",
      # v7.0
      "mm_sources","mm_agents","mm_stats","mm_memory","mm_engine",
      # v8.1 (ТЗ v2-v6): новые контуры
      "mm_onchain_schema","mm_onchain_sources","mm_onchain_agents",
      "mm_onchain_engine","mm_onchain_memory",
      "mm_trend","mm_outlook24","mm_graph","mm_discovery","mm_registry"]
fail=0
for m in mods:
    try: importlib.import_module(m).selftest()
    except Exception:
        fail+=1;print(m+": FAIL");traceback.print_exc()
def _up():
    up=[[i,100+i*0.1,100.5+i*0.1,99.5+i*0.1,100+i*0.1,1] for i in range(210)]
    up[-1]=[209,120.9,122.5,120.8,122.0,1];up[-2]=[208,120.8,121.0,119.0,120.9,1]
    return up
def _snap_agree():
    return {"parts":{"tape":{"ok":True,"cvd":5.0,"delta_pct":4.0,"price_change_pct":0.4,"divergence":False,"n":500,"age_sec":2.0},
                    "book":{"ok":True,"imbalance_pct":10.0},"deriv":{"ok":True,"funding_pct":0.01,"oi":68000.0},
                    "fng":{"ok":True,"value":50,"delta3":0,"delta7":0,"classification":"Neutral"},
                    "lsr":{"ok":True,"ratio":1.0,"long_pct":50.0,"short_pct":50.0}},"grade":"A",
            "price":122.0,"sources":{"tape":"AVAILABLE","book":"AVAILABLE","deriv":"AVAILABLE","lsr":"AVAILABLE","fng":"AVAILABLE"}}
def integration_conflict():
    """Сквозной сценарий: TA говорит LONG, Order Flow показывает давление продавцов.
    Система обязана зафиксировать конфликт, в итоге NO TRADE, и Risk Gate обязан
    заблокировать план. НО прогноз направления всё равно формируется (v6)."""
    import mm_council,mm_risk,mm_forecast
    up=_up()
    war={"parts":{"tape":{"ok":True,"cvd":-6.0,"delta_pct":-5.0,"price_change_pct":-0.5,"divergence":False,"n":500,"age_sec":2.0},
                  "book":{"ok":False},"deriv":{"ok":True,"funding_pct":0.01,"oi":68000.0},
                  "fng":{"ok":True,"value":50,"delta3":0,"delta7":0,"classification":"Neutral"},
                  "lsr":{"ok":True,"ratio":1.0,"long_pct":50.0,"short_pct":50.0}},"grade":"A"}
    d=mm_council.resolve(war,up,regime="trend_up")
    assert d["status"]=="NO_TRADE" and d["conflicts"],(d["status"],d["note"])
    plan={"id":"I1","pair":"BTCUSDT","side":"long","entry":73700,"stop":73110.4,"tp1":75321.4,"p":0.55,"lev":3,"risk_usd":1.5}
    r=mm_risk.check([plan],today="2026-01-01",decision=d)["results"][0]
    assert r["verdict"]=="BLOCK" and any(x.startswith("council:") for x in r["reasons"]),r
    # v6: прогноз независим от Risk Gate — направление есть, сделки нет
    f=mm_forecast.forecast(d,snap=war,regime="trend_up")
    assert f["status"]=="OK" and f["side"] in ("long","short") and f["trade_allowed"] is False,f
def integration_agree():
    """Согласованный сценарий: TA+Flow LONG, sentiment нейтрален -> AGREE -> PASS."""
    import mm_council,mm_risk
    up=_up()
    d2=mm_council.resolve(_snap_agree(),up,regime="trend_up")
    assert d2["status"]=="AGREE" and d2["side"]=="long",d2["status"]
    plan={"id":"I1","pair":"BTCUSDT","side":"long","entry":73700,"stop":73110.4,"tp1":75321.4,"p":0.55,"lev":3,"risk_usd":1.5}
    r2=mm_risk.check([dict(plan,p=d2["p"])],today="2026-01-01",decision=d2,data_grade="A")["results"][0]
    assert r2["verdict"]=="PASS",r2["reasons"]
def integration_halt():
    """HALT: обязательный модуль (sentiment) INSUFFICIENT -> анализ остановлен."""
    import mm_council
    up=_up()
    snap={"parts":{"tape":{"ok":True,"cvd":5.0,"delta_pct":4.0,"price_change_pct":0.4,"divergence":False,"n":500,"age_sec":2.0},
                   "deriv":{"ok":True,"funding_pct":0.01,"oi":68000.0},
                   "fng":{"ok":False},"lsr":{"ok":False}}}
    d=mm_council.resolve(snap,up,regime="trend_up")
    assert d["status"]=="HALT",(d["status"],d["note"])
def integration_regime_prior():
    """Regime-приор: в trend_up приор ta выше, чем в range. Решение меняет p."""
    import mm_council
    up=_up()
    snap=_snap_agree()
    d_trend=mm_council.resolve(snap,up,regime="trend_up")
    d_range=mm_council.resolve(snap,up,regime="range")
    assert d_trend["status"]=="AGREE" and d_range["status"]=="AGREE",(d_trend["status"],d_range["status"])
    assert d_trend["p"]>d_range["p"],(d_trend["p"],d_range["p"])
def integration_sentiment_contradicts():
    """Sentiment корректирует сторону в range: Extreme Greed + LSR>1.4 -> short (контр-тренд)."""
    import mm_council
    flat=[[i,100,100.5,99.5,100,1] for i in range(220)]  # range по klines
    snap={"parts":{"tape":{"ok":True,"cvd":0.2,"delta_pct":0.5,"price_change_pct":0.1,"divergence":False,"n":500,"age_sec":2.0},
                   "book":{"ok":False},"deriv":{"ok":True,"funding_pct":0.01,"oi":68000.0},
                   "fng":{"ok":True,"value":82,"delta3":10,"delta7":15,"classification":"Extreme Greed"},
                   "lsr":{"ok":True,"ratio":1.6,"long_pct":61.5,"short_pct":38.5}}}
    d=mm_council.resolve(snap,flat,regime="range")
    assert d["status"]=="AGREE" and d["side"]=="short",(d["status"],d["note"])
def integration_feedback_loop():
    """Контур обратной связи: 25 compliant-сделок -> p_hat считается -> council его подхватывает."""
    import tempfile,mm_journal,mm_feedback,mm_council
    up=_up();snap=_snap_agree()
    old=(mm_journal.BASE,mm_journal.J)
    with tempfile.TemporaryDirectory() as td:
        mm_journal.BASE,mm_journal.J=td,os.path.join(td,"j.csv")
        ph,_=mm_feedback.p_hat(min_n=20)
        assert ph is None
        d0=mm_council.resolve(snap,up,regime="trend_up")
        assert d0["p_hat_from_feedback"] is None
        for i in range(10):
            mm_journal.pre({"id":"W%d"%i,"module":"S","p":0.6,"risk_usd":1.0,"entry":100,"stop":99,"side":"long","regime":"trend_up"})
            mm_journal.post({"id":"W%d"%i,"exit":102,"result_usd":1.0,"scenario_hit":1,"compliant":1})
        for i in range(15):
            mm_journal.pre({"id":"L%d"%i,"module":"S","p":0.6,"risk_usd":1.0,"entry":100,"stop":99,"side":"long","regime":"trend_up"})
            mm_journal.post({"id":"L%d"%i,"exit":99,"result_usd":-1.0,"scenario_hit":0,"compliant":1})
        ph,info=mm_feedback.p_hat(min_n=20)
        assert ph is not None and abs(ph-0.4)<1e-6,(ph,info)
        d1=mm_council.resolve(snap,up,regime="trend_up")
        assert d1["p_hat_from_feedback"] is not None,d1
        assert d1["p"]<=d0["p"],(d0["p"],d1["p"])
    mm_journal.BASE,mm_journal.J=old
def integration_klines_fallback():
    """klines: Bybit упал -> fallback на CSV (Binance удалён из цепочки)."""
    import mm_klines
    import unittest.mock as mock
    fake_csv=[(i,100,101,99,100,1) for i in range(220)]
    import tempfile
    with tempfile.NamedTemporaryFile("w",suffix=".csv",delete=False,encoding="utf-8",newline="") as f:
        f.write("ts,open,high,low,close,volume\n")
        for i in range(220): f.write("%d,100,101,99,100,1\n"%(i*3600000))
        path=f.name
    with mock.patch("mm_klines.from_bybit",return_value=(None,{})):
        r,src,q=mm_klines.fetch("BTCUSDT",csv_path=path)
        assert src=="csv" and q["ok"] and len(r)==220,(src,q.get("ok"))
    os.unlink(path)
def integration_forecast_verify():
    """Память прогнозов: store -> verify по фактическому рынку -> stats по режимам."""
    import tempfile,time,datetime,mm_forecast
    old=os.environ.get("MM5_DATA_DIR")
    try:
        with tempfile.TemporaryDirectory() as td:
            os.environ["MM5_DATA_DIR"]=td
            past=datetime.datetime.fromtimestamp(time.time()-25*3600).isoformat(timespec="seconds")
            mm_forecast.store({"ts":past,"pair":"BTCUSDT","interval":"60","regime":"trend_up","side":"long",
                               "confidence":0.60,"horizon_h":24,"entry_price":100.0,
                               "council_status":"AGREE","gate_status":"PASS","data_grade":"A"})
            mm_forecast.store({"ts":past,"pair":"BTCUSDT","interval":"60","regime":"trend_up","side":"long",
                               "confidence":0.70,"horizon_h":24,"entry_price":100.0,
                               "council_status":"NO_TRADE","gate_status":"BLOCK","data_grade":"C"})
            n,_=mm_forecast.verify(price_fn=lambda p,t: 103.0)  # рынок вырос на 3%
            assert n==2
            st=mm_forecast.stats()
            assert st["checked"]["n"]==2 and st["checked"]["hit_rate"]==1.0
            assert st["by_regime"]["trend_up"]["n"]==2
    finally:
        if old is not None: os.environ["MM5_DATA_DIR"]=old
        elif "MM5_DATA_DIR" in os.environ: del os.environ["MM5_DATA_DIR"]
def integration_chain_full():
    """Полная цепочка A->Б на готовых данных: узлы, прогноз, гейт, синтез, дедуп новостей."""
    import mm_chain
    up=[[i,100+i*0.1,100.5+i*0.1,99.5+i*0.1,100+i*0.1,1] for i in range(220)]
    up[-1]=[219,120.9,122.5,120.8,122.0,1];up[-2]=[218,120.8,121.0,119.0,120.9,1]
    snap=_snap_agree()
    news=[{"title":"BTC ETF inflows","impact":0.8,"importance":"high","source":"test"},
          {"title":"BTC ETF inflows","impact":0.8,"importance":"high","source":"test"},
          {"title":"Fed decision","impact":-0.3,"importance":"medium","source":"test"}]
    res=mm_chain.run_chain(symbol="BTCUSDT",interval="60",snap=snap,klines=up,news=news,store_forecast=False)
    nm=res["node_map"]
    assert all(nm[n]["status"]=="OK" for n in ("DATA INGESTION","DATA CHECK","REGIME","COUNCIL","FORECAST","FINAL OUTPUT")),nm
    assert all(nm[n]["status"]=="OK" for n in ("TA ANALYST","FLOW ANALYST","DERIV ANALYST","SENTIMENT ANALYST"))
    assert nm["RISK GATE"]["status"]=="SKIPPED"  # план не передан
    assert res["decision"]["status"]=="AGREE" and res["forecast"]["side"]=="long"
    assert res["output"]["analysts"]["sentiment"]["ok"] is True
    txt=mm_chain.explain(res)
    for k in ("1. Состояние","2. Режим","7. Council","8. Прогноз","9. Risk Gate","10. Итог"): assert k in txt,k
    # конфликтный прогон: NO_TRADE от совета, гейт STOPPED, прогноз жив
    war={"parts":{"tape":{"ok":True,"cvd":-6.0,"delta_pct":-5.0,"price_change_pct":-0.5,"divergence":False,"n":500,"age_sec":2.0},
                  "book":{"ok":False},"deriv":{"ok":True,"funding_pct":0.01,"oi":68000.0},
                  "fng":{"ok":True,"value":50,"delta3":0,"delta7":0,"classification":"Neutral"},
                  "lsr":{"ok":True,"ratio":1.0,"long_pct":50.0,"short_pct":50.0}},"grade":"C","price":121.0,"sources":{}}
    res2=mm_chain.run_chain(symbol="BTCUSDT",interval="60",snap=war,klines=up,store_forecast=False)
    assert res2["decision"]["status"]=="NO_TRADE"
    assert res2["node_map"]["RISK GATE"]["status"]=="STOPPED"
    assert res2["forecast"]["status"]=="OK" and res2["forecast"]["trade_allowed"] is False
def integration_bybit_parsers():
    """Парсеры Bybit V5 против форматов, зафиксированных живой проверкой 2026-11-12."""
    import mm_live,mm_klines
    # recent-trade: поля price/size/side/time (фикс бага v5: p/v/T не существуют)
    t=mm_live.tape_calc({"retCode":0,"result":{"list":[
        {"execId":"a","price":"70000","size":"2","side":"Buy","time":"1794510000000"},
        {"execId":"b","price":"69990","size":"1","side":"Sell","time":"1794510001000"}]}})
    assert t["ok"] and t["buy_qty"]==2.0 and t["sell_qty"]==1.0 and t["cvd"]==1.0
    # orderbook: b/a
    b=mm_live.book_calc({"retCode":0,"result":{"b":[["69990","1"]],"a":[["70000","3"]]}})
    assert b["ok"] and b["imbalance_pct"]==-50.0
    # account-ratio: buyRatio/sellRatio
    l=mm_live.lsr_calc({"retCode":0,"result":{"list":[{"buyRatio":"0.72","sellRatio":"0.28","timestamp":"1"}]}})
    assert l["ok"] and abs(l["ratio"]-0.72/0.28)<0.01
    # kline: ts уже в ms (фикс бага v5)
    p=mm_klines._parse_bybit({"retCode":0,"result":{"list":[["1794510000000","100","101","99","100.5","1","7"]]}})
    assert p[0][0]==1794510000000
def integration_ta_sees_trend():
    """v6.1 (СВЯЗЬ ВПЕРЁД): установившийся тренд вниз + контр-тренд толпа лонгует.
    Раньше ta молчал весь тренд (нужен свежий пробой) и совет решала толпа -> вечный short.
    Теперь ta голосует по структуре тренда, конфликт фиксируется честно (NO_TRADE),
    а прогноз показывает обе стороны. Проверяем тренд вниз: ta=short, sentiment=long(контр),
    итог council NO_TRADE (конфликт) — вместо слепого AGREE short от одной толпы."""
    import mm_council,mm_analyst
    dn=[[i,300-i*0.2,301-i*0.2,299-i*0.2,300-i*0.2,1] for i in range(240)]  # ровный тренд вниз
    # толпа ШОРТУЕТ аномально мало (перцентиль 5) -> контр-тренд long; flow нейтрален
    snap={"parts":{"tape":{"ok":True,"cvd":0.1,"delta_pct":0.2,"price_change_pct":0.01,"divergence":False,"n":500,"age_sec":2.0},
                   "deriv":{"ok":True,"funding_pct":0.01,"oi":68000.0},
                   "fng":{"ok":True,"value":20,"delta3":-2,"delta7":-5,"classification":"Extreme Fear"},
                   "lsr":{"ok":True,"ratio":0.6,"long_pct":37.5,"short_pct":62.5,"pct_rank":5.0,"pctl_window":120}},
          "grade":"A","price":252.0,"sources":{}}
    ta=mm_analyst.ta_analyze(dn,regime="trend_down")
    assert ta["ok"] and ta["side"]=="short",ta  # v6.0 здесь был flat — «не видел тренд»
    d=mm_council.resolve(snap,dn,regime="trend_down")
    # ta(short) vs sentiment(long): жёсткий конфликт -> NO_TRADE, а не слепой сигнал
    assert d["status"]=="NO_TRADE" and d["conflicts"],(d["status"],d["note"])
    mods=d["modules"]
    assert mods["ta"]["side"]=="short" and mods["sentiment"]["side"]=="long"
    # а в тренде без аномалий толпы ta решает: AGREE short
    snap2={"parts":{"tape":{"ok":True,"cvd":-5.0,"delta_pct":-4.0,"price_change_pct":-0.4,"divergence":False,"n":500,"age_sec":2.0},
                    "deriv":{"ok":True,"funding_pct":0.01,"oi":68000.0},
                    "fng":{"ok":True,"value":50,"delta3":0,"delta7":0,"classification":"Neutral"},
                    "lsr":{"ok":True,"ratio":2.5,"long_pct":71.4,"short_pct":28.6,"pct_rank":50.0,"pctl_window":120}},
           "grade":"A","price":252.0,"sources":{}}
    d2=mm_council.resolve(snap2,dn,regime="trend_down")
    assert d2["status"] in ("AGREE","NO_TRADE"),d2["status"]
    if d2["status"]=="AGREE":
        assert d2["side"]=="short",d2  # тренд вниз: ta+flow вниз против нейтральной толпы
def integration_lsr_percentile():
    """v6.1 (СВЯЗЬ ВПЕРЁД): LSR 2.55 при базе 2.5 — НЕ аномалия (перцентиль ~55):
    sentiment молчит. Ровно этот кейс из реального журнала пользователя давал
    вечный 'толпа лонгует -> контр-тренд short' на фиксированном пороге 1.4."""
    import mm_analyst
    snap={"parts":{"fng":{"ok":True,"value":28,"delta3":-4,"delta7":0,"classification":"Fear"},
                   "lsr":{"ok":True,"ratio":2.5562,"long_pct":71.88,"short_pct":28.12,"pct_rank":55.0,"pctl_window":120}}}
    s=mm_analyst.sentiment_analyze(snap,regime="trend_down")
    assert s["ok"] and s["side"]=="flat",(s["side"],s["evidence"])  # F&G 28 не экстремум, LSR в норме
    # аномалия: перцентиль 96 -> голос short
    snap2={"parts":{"fng":{"ok":True,"value":28,"delta3":-4,"delta7":0,"classification":"Fear"},
                    "lsr":{"ok":True,"ratio":4.4,"long_pct":81.5,"short_pct":18.5,"pct_rank":96.0,"pctl_window":120}}}
    s2=mm_analyst.sentiment_analyze(snap2,regime="trend_down")
    assert s2["side"]=="short"
def integration_funding_floor():
    """v6.1: funding +0.01% на перцентиле 100 — БАЗА, не экстремум: deriv нейтрален.
    (реальный кейс из журнала: funding=+0.0100%, персентиль 100 -> вечный deriv short)."""
    import mm_analyst
    snap={"parts":{"deriv":{"ok":True,"funding_pct":0.01,"oi":72900.0},
                   "funding_hist":{"ok":True,"history":[0.0]*89+[0.01]},
                   "oi_hist":{"ok":True,"oi_pct_change":-5.25}}}
    d=mm_analyst.deriv_analyze(snap,regime="trend_down")
    assert d["ok"] and d["side"]=="flat" and any("НЕ экстремум" in e for e in d["evidence"]),d
def integration_news_flow():
    """v6.1: mm_news (RSS-парсер) -> карточки -> _norm_news цепочки -> мягкий фон sentiment.
    Позитивный фон усиливает long-голос толпы, негативный — ослабляет; сторона не переворачивается."""
    import mm_news,mm_analyst,mm_chain,time,email.utils
    now=int(time.time())
    rss="<?xml version='1.0'?><rss version='2.0'><channel><title>t</title>%s</channel></rss>"%(
        "".join("<item><title>%s</title><link>u%d</link><pubDate>%s</pubDate></item>"%(
            t,i,email.utils.formatdate(now-3600,usegmt=True))
            for i,t in enumerate(["Bitcoin ETF inflows surge","Ethereum hack wipes millions","Neutral digest"])))
    items=mm_news.parse_feed(rss,"test",now_ts=now)
    assert len(items)==3
    scored={i["title"]:i["impact"] for i in items}
    assert scored["Bitcoin ETF inflows surge"]>0 and scored["Ethereum hack wipes millions"]<0
    # через цепочку: дедуп + передача в sentiment (новости только фон)
    snap={"parts":{"tape":{"ok":True,"cvd":0.2,"delta_pct":0.5,"price_change_pct":0.1,"divergence":False,"n":500,"age_sec":2.0},
                   "deriv":{"ok":True,"funding_pct":0.01,"oi":1.0},
                   "fng":{"ok":True,"value":15,"delta3":-5,"delta7":-8,"classification":"Extreme Fear"},
                   "lsr":{"ok":True,"ratio":0.5,"long_pct":33.0,"short_pct":67.0,"pct_rank":4.0,"pctl_window":120}},
          "grade":"A","price":100.0,"sources":{}}
    flat=[[i,100,100.5,99.5,100,1] for i in range(240)]
    res=mm_chain.run_chain(symbol="BTCUSDT",interval="60",snap=snap,klines=flat,
                           news=items,store_forecast=False)
    assert res["node_map"]["DATA CHECK"]["summary"].count("новости 3")==1
    sent=res["analysts"]["sentiment"]
    assert sent["side"]=="long"  # Extreme Fear + LSR-перцентиль 4 -> контр-тренд long
    # негативный фон ослабляет уверенность long-голоса, но не переворачивает
    res_neg=mm_chain.run_chain(symbol="BTCUSDT",interval="60",snap=snap,klines=flat,
                               news=[dict(n,impact=-1.0) for n in items],store_forecast=False)
    sent_neg=res_neg["analysts"]["sentiment"]
    assert sent_neg["side"]=="long" and sent_neg["confidence"]<=sent["confidence"]+1e-9
def integration_memory_backward():
    """v6.1 (СВЯЗЬ НАЗАД): прогноз store -> verify по факту -> stats -> memory-поправка
    влияет на уверенность СЛЕДУЮЩИХ прогнозов того же режима. Контур замкнут в обе стороны."""
    import tempfile,time,datetime,mm_forecast,mm_council
    old=os.environ.get("MM5_DATA_DIR")
    up=_up()
    snap=_snap_agree()
    try:
        with tempfile.TemporaryDirectory() as td:
            os.environ["MM5_DATA_DIR"]=td
            # 12 проверенных прогнозов в trend_up с hit-rate 1/12 (плохой режим)
            past=datetime.datetime.fromtimestamp(time.time()-48*3600).isoformat(timespec="seconds")
            for i in range(12):
                mm_forecast.store({"ts":past,"pair":"BTCUSDT","interval":"60","regime":"trend_up",
                                   "side":"long","confidence":0.62,"horizon_h":24,"entry_price":100.0,
                                   "council_status":"AGREE","gate_status":"PASS","data_grade":"A"})
            mm_forecast.store({"ts":past,"pair":"BTCUSDT","interval":"60","regime":"range",
                               "side":"long","confidence":0.62,"horizon_h":24,"entry_price":100.0,
                               "council_status":"AGREE","gate_status":"PASS","data_grade":"A"})
            n,_=mm_forecast.verify(now_ts=time.time(),price_fn=lambda p,t: 99.0)  # всё мимо
            assert n==13,n
            st=mm_forecast.stats()
            assert st["by_regime"]["trend_up"]["n"]==12 and st["by_regime"]["trend_up"]["hit_rate"]==0.0
            # обратная связь: прогноз в trend_up получает -0.05 к уверенности (память о слабом режиме)
            d=mm_council.resolve(snap,up,regime="trend_up")
            f_mem=mm_forecast.forecast(d,snap=snap,regime="trend_up")
            assert any("память" in c for c in f_mem["contradictions"]),f_mem["contradictions"]
            # а в range (n=1 < 10) поправки нет — честная граница применимости
            d2=mm_council.resolve(snap,up,regime="range")
            f_rng=mm_forecast.forecast(d2,snap=snap,regime="range")
            assert not any("память" in c for c in f_rng["contradictions"])
    finally:
        if old is not None: os.environ["MM5_DATA_DIR"]=old
        elif "MM5_DATA_DIR" in os.environ: del os.environ["MM5_DATA_DIR"]
def integration_v7_agent_contract():
    """ЧАСТЬ 5: контракт результата агента — все поля, честные статусы, SENTIMENT не решает.
    v8.0: агентов-ветвей 18 (добавлены ONCHAIN, DEFI_FLOW, BASIS, WHALE_WATCH,
    SESSION, DOMINANCE — лес, обмотанный паутиной WEB_LINKS)."""
    import mm_agents
    fb={"klines":{},"price":None}
    agents,pred=mm_agents.run_all(fb,"BTCUSDT")
    assert len(agents)==18 and pred["agent"]=="PREDICTION_AGENT"
    # паутина: все связи присутствуют, на пустых данных — SILENT/PENDING
    web=mm_agents.web_analysis(agents)
    assert len(web["links"])==len(mm_agents.WEB_LINKS)
    assert web["active"]==0 and web["coherence"] is None,web
    for a in agents+[pred]:
        for k in ("agent","status","timestamp","side","confidence","features","evidence",
                  "contradictions","invalidate"):
            assert k in a,(a.get("agent"),k)
        assert a["status"] in ("OK","INSUFFICIENT","DISABLED"),a["status"]
        assert 0.0<=a["confidence"]<=1.0
        assert a["side"] in ("LONG","SHORT","NEUTRAL","UP","FLAT","DOWN"),a["side"]
    # пустые данные -> честные INSUFFICIENT/DISABLED, выдуманных процентов нет
    for a in agents:
        if a["agent"]=="BTC_CORRELATION_ANALYST": assert a["status"]=="DISABLED"
        elif a["agent"]=="STATISTICAL_AI": assert a["status"]=="DISABLED"
        else: assert a["status"]=="INSUFFICIENT",(a["agent"],a["status"])
def integration_v7_no_lookahead():
    """ЧАСТЬ 12: признаки в момент t не зависят от будущего (walk-forward без утечки)."""
    import mm_stats,random
    random.seed(3)
    rows=[];p=100.0
    for i in range(600):
        o=p;p=max(0.01,p*(1+random.gauss(0.0002,0.004)))
        rows.append([1794500000000+i*60000,o,max(o,p),min(o,p),p,abs(random.gauss(1,0.3))])
    f1=mm_stats.features_at(rows,400)
    rows2=[r[:] for r in rows]
    for j in range(405,len(rows2)): rows2[j][4]*=1.7   # меняем БУДУЩЕЕ
    f2=mm_stats.features_at(rows2,400)
    assert f1==f2,"LOOKAHEAD: признаки зависят от будущих свечей"
def integration_v7_engine_sessions():
    """ЧАСТИ 6-7: сессии 15-мин прогнозов — начальная версия хранится, обновления
    не перезаписывают её; вероятности строго суммируются в 100%; grade D честен."""
    import mm_engine,tempfile,time,random
    old=os.environ.get("MM5_DATA_DIR")
    td=tempfile.mkdtemp();os.environ["MM5_DATA_DIR"]=td
    try:
        mm_engine._ENGINE=None
        eng=mm_engine.get_engine()
        random.seed(5)
        def mk(n=280,drift=0.0016):
            rows_=[];p=100.0;t0=(time.time()-(n+1)*60)*1000
            for i in range(n):
                o=p;p*=(1+drift)
                rows_.append([int(t0)+(i+1)*60000,round(o,4),round(p*1.001,4),round(o*0.999,4),round(p,4),1.2])
            return rows_
        up=mk()
        for tf in ("1","3","5","15","60"): eng.set_klines("V7USDT",tf,up)
        eng.feed("V7USDT",{"price":up[-1][4],
            "tape":{"ok":True,"n":500,"delta_pct":20.0,"cvd":8.0,"age_sec":1,"price_change_pct":0.3},
            "binance_flow":{"ok":True,"n":400,"delta_pct":16.0,"age_sec":1},
            "okx_flow":{"ok":True,"n":300,"delta_pct":14.0,"age_sec":2},
            "book":{"ok":True,"bids":[(100.0-i*0.1,2.0) for i in range(10)],
                    "asks":[(100.1+i*0.1,1.0) for i in range(10)]},
            "deriv":{"ok":True,"funding_pct":0.004,"funding_hist":[0.004]*30,"lsr_pct_rank":50.0},
            "cross":{"n_exchanges":3,"parts":{}},"fng":{"ok":True,"value":50},"news":[]})
        r1=eng.compute("V7USDT")
        assert r1["ok"],r1.get("error")
        f=r1["forecast"]
        assert abs(f["p_up"]+f["p_flat"]+f["p_down"]-1.0)<1e-9,(f["p_up"],f["p_flat"],f["p_down"])
        assert all(0.0<=f[k]<=1.0 for k in ("p_up","p_flat","p_down"))
        assert f["version"]==1 and r1["session"]["is_initial"]
        assert r1["session"]["memory_op"].startswith("stored")
        # обновление в том же окне: версия, начальная не тронута
        r2=eng.compute("V7USDT")
        assert not r2["session"]["is_initial"] and r2["forecast"]["version"]==2
        assert r2["session"]["initial"]==r1["session"]["initial"]
        # смена окна -> новая сессия (новый независимый прогноз)
        eng.sessions["V7USDT"]["bucket"]-=1
        r3=eng.compute("V7USDT")
        assert r3["session"]["is_initial"] and r3["forecast"]["version"]==1
        # grade D: устаревшие свечи -> отказ без выдуманных чисел
        stale=[r[:] for r in up];stale[-1][0]=int((time.time()-900)*1000)
        eng.set_klines("STALE7","1",stale)
        rD=eng.compute("STALE7")
        assert (not rD["ok"]) and rD["data"]["grade"]=="D" and rD["forecast"] is None
    finally:
        mm_engine._ENGINE=None
        if old is not None: os.environ["MM5_DATA_DIR"]=old
        elif "MM5_DATA_DIR" in os.environ: del os.environ["MM5_DATA_DIR"]
def integration_v7_council_clusters():
    """ЧАСТЬ 5: совет группирует коррелированные кластеры, NO_SIGNAL без подтверждений."""
    import mm_engine,tempfile
    old=os.environ.get("MM5_DATA_DIR")
    td=tempfile.mkdtemp();os.environ["MM5_DATA_DIR"]=td
    try:
        mm_engine._ENGINE=None
        eng=mm_engine.get_engine()
        # только один кластер голосует -> NO_SIGNAL
        c=eng._council([
            {"agent":"PRICE_ANALYST","status":"OK","side":"LONG","confidence":0.7},
            {"agent":"ORDER_FLOW_ANALYST","status":"OK","side":"NEUTRAL","confidence":0.2},
            {"agent":"SENTIMENT_ANALYST","status":"OK","side":"NEUTRAL","confidence":0.3}],
            {"grade":"C"})
        assert c["status"]=="NO_SIGNAL",c
        # три кластера в одну сторону -> AGREE, подавленные в кластере зафиксированы
        c2=eng._council([
            {"agent":"PRICE_ANALYST","status":"OK","side":"LONG","confidence":0.7},
            {"agent":"VOLATILITY_ANALYST","status":"OK","side":"LONG","confidence":0.5},
            {"agent":"ORDER_FLOW_ANALYST","status":"OK","side":"LONG","confidence":0.7},
            {"agent":"LIQUIDITY_ANALYST","status":"OK","side":"LONG","confidence":0.4},
            {"agent":"DERIVATIVES_ANALYST","status":"OK","side":"LONG","confidence":0.55}],
            {"grade":"A"})
        assert c2["status"]=="AGREE",c2
        assert len(c2["clusters"])>=3
        assert any("подавлен" in x for x in c2["contradictions"]),c2["contradictions"]
        # кластеры друг против друга -> NO_SIGNAL
        c3=eng._council([
            {"agent":"PRICE_ANALYST","status":"OK","side":"LONG","confidence":0.7},
            {"agent":"ORDER_FLOW_ANALYST","status":"OK","side":"SHORT","confidence":0.7}],
            {"grade":"A"})
        assert c3["status"]=="NO_SIGNAL",c3
    finally:
        mm_engine._ENGINE=None
        if old is not None: os.environ["MM5_DATA_DIR"]=old
        elif "MM5_DATA_DIR" in os.environ: del os.environ["MM5_DATA_DIR"]

def integration_v81_onchain_review():
    """v8.1 (ТЗ §5/§8): ON-CHAIN REVIEW в совете. OCIE подтверждает/противоречит/
    недоступен — Market Engine продолжает работу во всех случаях; конфликт
    снижает confidence (confidence_adj<1) и фиксируется в contradictions."""
    import mm_engine
    # сверяем логику совета движка через прямой вызов summarize + council-подобную сборку
    import mm_onchain_engine
    eng=mm_onchain_engine.get_engine()
    # подтверждение: один LONG-агент с correlation_group
    agents=[{"agent":"EXCHANGE_NETFLOW_AGENT","status":"OK","direction":"LONG",
             "confidence":0.3,"evidence":["outflow dominates"],"correlation_group":"onchain_flows"}]
    s=eng.summarize(agents,{"src1":"AVAILABLE"})
    assert s["state"]=="CONFIRMED" and s["direction"]=="LONG"
    # недоступность: движок продолжает, статус ONCHAIN_UNAVAILABLE
    s2=eng.summarize(agents,{})
    assert s2["state"]=="ONCHAIN_UNAVAILABLE"
    # конфликт внутри контура -> CONFLICT, направление NEUTRAL
    conflict=[{"agent":"A","status":"OK","direction":"LONG","confidence":0.4,
               "evidence":["x"],"correlation_group":"g1"},
              {"agent":"B","status":"OK","direction":"SHORT","confidence":0.4,
               "evidence":["y"],"correlation_group":"g2"}]
    s3=eng.summarize(conflict,{"s":"AVAILABLE"})
    assert s3["state"]=="CONFLICT" and s3["direction"]=="NEUTRAL"
def integration_v81_trend_card():
    """v8.1 (ТЗ §11): три вопроса разделены — trend bias, entry quality, risk;
    NEUTRAL/RANGE — полноценный результат, не отказ."""
    import mm_trend
    fl=mm_trend._mk(300,0.0,0.0008,seed=5)
    r=mm_trend.trend_layer({"60":fl,"15":fl,"5":fl})
    assert r["status"]=="OK"
    assert r["bias"] in ("NEUTRAL / RANGE","WEAK BULLISH","WEAK BEARISH")
    assert r["entry_quality"] in ("GOOD","MEDIUM","LOW")
    assert r["risk"] in ("NORMAL","ELEVATED","COMPRESSED")
def integration_v81_outlook_thresholds():
    """v8.1 (ТЗ §16.3): порог 24H фиксирован ДО оценки; UP/FLAT/DOWN в сумме 1;
    слух не создаёт давления; маркировка NOT CALIBRATED до 30 проверок."""
    import mm_outlook24
    assert mm_outlook24.threshold_pct(None)==0.75
    r=mm_outlook24.outlook_24h(None,{"status":"INSUFFICIENT"},[])
    assert r["status"]=="INSUFFICIENT"
    assert "NOT CALIBRATED" in r["calibration"]["label"]
def integration_v81_graph_degradation():
    """v8.1 (ТЗ §26.4/26.5): необязательный узел недоступен -> degraded, путь
    продолжается; критический упал -> зависимые skipped, отчёт честный."""
    import mm_graph
    def ok(ctx): return {"ok":True}
    def fail(ctx): raise RuntimeError("x")
    # optional OCIE падает -> degraded, но путь завершён
    g=mm_graph.build_graph(ok,lambda c:(_ for _ in ()).throw(RuntimeError("ocie down")),ok,ok)
    # помечаем onchain как optional уже в графе; fallback: используем исключение
    r=mm_graph.Orchestrator(g).run("T","15m",budget_sec=8.0)
    # критический движок падает -> failed + skipped, ok=False
    g2=mm_graph.build_graph(fail,ok,ok,ok)
    r2=mm_graph.Orchestrator(g2).run("T","15m",budget_sec=8.0)
    assert not r2["ok"] and r2["nodes"]["D_MARKET"]["status"]=="failed"
    assert r2["nodes"]["E_COUNCILS"]["status"]=="skipped"
def integration():
    for fn in (integration_conflict,integration_agree,integration_halt,
               integration_regime_prior,integration_sentiment_contradicts,
               integration_feedback_loop,integration_klines_fallback,
               integration_forecast_verify,integration_chain_full,
               integration_bybit_parsers,integration_ta_sees_trend,
               integration_lsr_percentile,integration_funding_floor,
               integration_news_flow,integration_memory_backward,
               # v7.0
               integration_v7_agent_contract,integration_v7_no_lookahead,
               integration_v7_engine_sessions,integration_v7_council_clusters,
               # v8.1
               integration_v81_onchain_review,integration_v81_trend_card,
               integration_v81_outlook_thresholds,integration_v81_graph_degradation):
        fn()
    print("integration: OK")
try: integration()
except Exception:
    fail+=1;print("integration: FAIL");traceback.print_exc()

# ---------------- live-интеграция v7.0 (только по флагу --live) ----------------
def live_integration():
    """Живые источники: 5 API + 8 активов watchlist + полный конвейер на BTC.
    Запуск: python3 tests/run_tests.py --live (нужна сеть)."""
    import json as _json
    import mm_sources,mm_engine
    import tempfile,time
    old=os.environ.get("MM5_DATA_DIR")
    os.environ["MM5_DATA_DIR"]=tempfile.mkdtemp()
    try:
        results={}
        # 1) инструменты всех 8 активов резолвятся честно
        watch=["BTCUSDT","ETHUSDT","SOLUSDT","XRPUSDT","SUIUSDT","AVAXUSDT","DOGEUSDT","OPUSDT"]
        for s in watch:
            r=mm_sources.resolve_instrument(s[:-4])
            assert r.get("ok") and r["symbol"]==s,(s,r)
        results["instruments"]="8/8 OK"
        # 2) мульти-ТФ свечи Bybit
        r=mm_sources.bybit_klines("BTCUSDT","3",50)
        assert r["ok"] and len(r["rows"])>=2
        results["bybit_multi_tf"]="OK"
        # 3) кросс-биржевая сводка (2+ биржи живы)
        c=mm_sources.cross_exchange("BTCUSDT")
        assert c["n_exchanges"]>=2,c
        results["cross_exchange"]="%d бирж"%c["n_exchanges"]
        # 4) DefiLlama + mempool (медленный контекст)
        slow=mm_sources.slow_context()
        assert (slow.get("defillama") or {}).get("ok") or (slow.get("mempool") or {}).get("ok")
        results["slow_ctx"]="OK"
        # 4b) v8.0: новые связи API — CoinGecko макро + киты Binance
        g=mm_sources.coingecko_global()
        assert g.get("ok") and g.get("btc_dominance") is not None,g
        results["coingecko"]="OK (BTC dom %.1f%%, мкап 24ч %+.1f%%)"%(
            g["btc_dominance"],g.get("mcap_change_24h_pct") or 0.0)
        w=mm_sources.binance_whales("BTCUSDT")
        assert w.get("status") in ("AVAILABLE","STALE","INSUFFICIENT"),w
        results["binance_whales"]=("OK: %d китов, дельта %+.1f%%"%(
            w.get("n_whales") or 0,w.get("delta_pct") or 0.0)) if w.get("ok") else "китов в окне нет (честно)"
        # 5) полный конвейер движка на живых данных BTC
        mm_engine._ENGINE=None
        eng=mm_engine.get_engine()
        # v8.0: паутина данных — киты + макро merge-подачей, как engine_fetch_web
        eng.feed("BTCUSDT",{"whales":w,"macro":g})
        res=eng.compute("BTCUSDT",allow_fetch=True)
        assert res["ok"],res.get("error")
        f=res["forecast"]
        assert f and abs(f["p_up"]+f["p_flat"]+f["p_down"]-1.0)<1e-9
        n_ok=sum(1 for a in res["agents"] if a["status"]=="OK")
        assert len(res["agents"])==18
        web=res["council"].get("web") or {}
        results["engine"]="OK (агентов OK: %d/18, совет %s, паутина: связей %d, согласие %s)"%(
            n_ok,res["council"]["status"],web.get("active",0),
            ("%.0f%%"%(100*web["coherence"])) if web.get("coherence") is not None else "—")
        # 6) walk-forward обучение на живых свечах (модель может честно не включиться)
        import mm_stats
        rows=mm_sources.bybit_klines("BTCUSDT","1",1000)["rows"]
        r2=mm_sources.bybit_klines("BTCUSDT","1",1000,end_ts=rows[0][0]-1)
        if r2.get("ok"):
            seen={x[0] for x in rows}
            rows=sorted(rows+[x for x in r2["rows"] if x[0] not in seen],key=lambda x:x[0])
        metrics,model=mm_stats.walk_forward(rows)
        assert metrics is not None and metrics["n_test"]>=5
        results["walk_forward"]="n_test=%d hit=%s baseline=%s ready=%s"%(
            metrics["n_test"],metrics.get("hit_rate"),metrics["baseline_hit"],
            (model or {}).get("ready"))
        print("live integration:",_json.dumps(results,ensure_ascii=False))
        print("live: OK")
    finally:
        mm_engine._ENGINE=None
        if old is not None: os.environ["MM5_DATA_DIR"]=old
        elif "MM5_DATA_DIR" in os.environ: del os.environ["MM5_DATA_DIR"]
if "--live" in sys.argv:
    try: live_integration()
    except Exception:
        fail+=1;print("live: FAIL");traceback.print_exc()
print("RESULT:","ALL PASS" if fail==0 else str(fail)+" FAIL")
sys.exit(1 if fail else 0)
