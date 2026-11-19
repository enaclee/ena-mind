"""ENA-MIND Android-мост (Chaquopy). Вызывается из Kotlin (PythonBridge.kt).

Инициализация: init(data_dir) — задаёт MM5_DATA_DIR в filesDir приложения и импортирует
модули скилла. Все функции возвращают JSON-строки (Chaquopy не гоняет произвольные
dict-и между языками надёжно, JSON — честный контракт).

run_chain принимает Java-колбэк cb с методом onNode(String) — прогресс узлов приходит
в UI в реальном времени (статусы RUNNING/OK/STOPPED/SKIPPED, не декорация).
"""
import json,math,os,re,sys,traceback

_SKILL_VERSION="9.0"

def _clean_json(v):
    """Рекурсивно очищает NaN/Inf -> None, чтобы Kotlin org.json никогда не падал."""
    if isinstance(v,float):
        return v if math.isfinite(v) else None
    if isinstance(v,dict):
        return {str(k):_clean_json(val) for k,val in v.items()}
    if isinstance(v,(list,tuple)):
        return [_clean_json(x) for x in v]
    return v

def _dumps(obj):
    return json.dumps(_clean_json(obj),ensure_ascii=False,allow_nan=False)

def _ver_tuple(v):
    """'7.2.1' -> (7,2,1); мусор -> None."""
    try:
        t=tuple(int(x) for x in str(v).split(".") if x.isdigit())
        return t if t else None
    except Exception:
        return None

def _skill_version_of_file(path):
    try:
        with open(path,encoding="utf-8") as f: txt=f.read(4096)
        m=re.search(r"(?m)^#\s+ENA-MIND\s+v([\d.]+)",txt)
        if m: return _ver_tuple(m.group(1))
    except Exception:
        pass
    return None

def init(data_dir,app_skill_version=None):
    """Инициализация окружения. data_dir — filesDir/enamind приложения.
    Если в data_dir/scripts лежат .py (импорт обновления скилла), каталог встаёт
    первым в sys.path — модули обновления имеют приоритет над упакованными в APK.

    v7.2 ВЕРСИОННЫЙ СТРАЖ (конфликт старых скриптов с новым APK): если версия
    override-скилла (SKILL.md в data_dir/scripts) СТАРШЕ версии APK, override
    ИГНОРИРУЕТСЯ — устаревшие mm_* не затеняют новые модули. Версия неизвестна
    (старый ZIP без SKILL.md) — используется, но с пометкой в note."""
    global _ready
    try:
        os.environ["MM5_DATA_DIR"]=data_dir
        os.makedirs(data_dir,exist_ok=True)
        override=os.path.join(data_dir,"scripts")
        override_used=False;note=""
        if os.path.isdir(override) and any(f.endswith(".py") for f in os.listdir(override)):
            ov_ver=_skill_version_of_file(os.path.join(override,"SKILL.md"))
            app_ver=_ver_tuple(app_skill_version) if app_skill_version else None
            if ov_ver is not None and app_ver is not None and ov_ver<app_ver:
                note="override-скилл v%s устарел относительно APK v%s и ПРОИГНОРИРОВАН (переимпортируйте ZIP новой версии)"%(
                    ".".join(str(x) for x in ov_ver),".".join(str(x) for x in app_ver))
            else:
                if ov_ver is None: note="версия override-скилла неизвестна (нет SKILL.md)"
                sys.path.insert(0,override)
                # форсируем переимпорт mm_* из override (модули могли быть загружены раньше)
                for m in [k for k in list(sys.modules) if k.startswith("mm_")]:
                    del sys.modules[m]
                override_used=True
        sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
        import mm_chain,mm_forecast,mm_journal,mm_feedback,mm_live,mm_klines  # noqa: F401
        import mm_sources,mm_agents,mm_stats,mm_memory,mm_engine             # v7.0
        import mm_trend,mm_outlook24,mm_graph,mm_discovery,mm_registry       # v8.1 (ТЗ v2-v6)
        import mm_onchain_schema,mm_onchain_sources,mm_onchain_agents        # v8.1 OCIE
        import mm_onchain_engine,mm_onchain_memory                           # v8.1 OCIE
        _ready=True
        return json.dumps({"ok":True,"data_dir":data_dir,"skill_version":_SKILL_VERSION,
                           "override_used":override_used,"note":note})
    except Exception as e:
        return json.dumps({"ok":False,"error":str(e),"trace":traceback.format_exc()[-400:]})

def _check():
    if not _ready: raise RuntimeError("bridge not initialised: call init(data_dir) first")

def run_chain(symbol,interval="60",news_json="[]",plan_json="null",liq_json="null",
              budget=40.0,cb=None,store_forecast=True):
    """Полная цепочка A->Б. cb.onNode(json) вызывается при каждой смене статуса узла."""
    _check()
    import mm_chain
    news=json.loads(news_json) if news_json else []
    plan=json.loads(plan_json) if plan_json else None
    extra=None
    if liq_json and liq_json!="null":
        liq=json.loads(liq_json)
        if isinstance(liq,dict) and liq:
            extra={"liquidations":liq}
    progress=None
    if cb is not None:
        progress=lambda nd: cb.onNode(json.dumps(nd,ensure_ascii=False))
    res=mm_chain.run_chain(symbol=symbol,interval=interval,budget=float(budget),
                           news=news,plan=plan,extra_parts=extra,
                           progress=progress,store_forecast=bool(store_forecast),
                           fetch_news=True)  # v6.1: пустые новости -> живой RSS-сбор в Python
    return _dumps(res)

def explain(json_res):
    """Компактный отчёт из 10 пунктов по готовому результату цепочки."""
    import mm_chain
    return mm_chain.explain(json.loads(json_res))

def verify_forecasts():
    """Проверка старых прогнозов по фактическому рынку (klines Bybit)."""
    _check()
    import mm_forecast
    try:
        n,_=mm_forecast.verify()
        return json.dumps({"ok":True,"checked":n},ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok":False,"error":str(e)},ensure_ascii=False)

def forecast_stats():
    """Память прогнозов: hit-rate по режимам, завышение уверенности, ошибки."""
    _check()
    import mm_forecast
    return json.dumps(mm_forecast.stats(),ensure_ascii=False)

def forecast_records():
    """Все записи памяти прогнозов (для синхронизации с Room в приложении)."""
    _check()
    import mm_forecast,mm_config,os,csv
    p=os.path.join(mm_config.data_dir(),"forecasts.csv")
    rows=[]
    if os.path.exists(p):
        with open(p,encoding="utf-8",newline="") as f:
            rows=list(csv.DictReader(f))
    return json.dumps({"rows":rows},ensure_ascii=False)

def journal_stats():
    """Статистика журнала сделок: edge по режимам, calibration, Brier."""
    _check()
    import mm_journal
    return json.dumps(mm_journal.stats(),ensure_ascii=False)

def feedback_report():
    """p_hat + edge по режимам (контур обратной связи)."""
    _check()
    import mm_feedback
    return json.dumps(mm_feedback.report(),ensure_ascii=False)

def memory_summary():
    """Сводная память для экспорта ZIP и экрана MEMORY."""
    _check()
    import mm_forecast,mm_journal,mm_feedback
    out={"skill_version":_SKILL_VERSION}
    try: out["forecast_stats"]=mm_forecast.stats()
    except Exception as e: out["forecast_stats"]={"error":str(e)}
    try: out["journal_stats"]=mm_journal.stats()
    except Exception as e: out["journal_stats"]={"error":str(e)}
    try: out["feedback"]=mm_feedback.report()
    except Exception as e: out["feedback"]={"error":str(e)}
    return json.dumps(out,ensure_ascii=False)

def snapshot(symbol="BTCUSDT",budget=30.0,liq_json="null"):
    """Живой снимок Bybit V5 (без цепочки) — для MARKET MONITOR."""
    _check()
    import mm_live
    extra=None
    if liq_json and liq_json!="null":
        liq=json.loads(liq_json)
        if isinstance(liq,dict) and liq: extra={"liquidations":liq}
    try:
        snap=mm_live.snapshot(symbol,budget_sec=float(budget),extra_parts=extra)
        return json.dumps(snap,ensure_ascii=False)
    except Exception as e:
        return json.dumps({"grade":"D","error":str(e)},ensure_ascii=False)

def klines(symbol="BTCUSDT",interval="60",limit=300):
    """Свечи для графика (JSON [ts,o,h,l,c,v])."""
    _check()
    import mm_klines
    rows,src,q=mm_klines.fetch(symbol,interval,int(limit))
    return json.dumps({"rows":rows or [],"source":src,"quality":q},ensure_ascii=False)

def run_selftests():
    """Проверка работоспособности скилла: selftest всех модулей (offline)."""
    import importlib
    mods=["mm_schema","mm_config","mm_data","mm_klines","mm_live","mm_regime","mm_analyst",
          "mm_council","mm_forecast","mm_chain","mm_core","mm_risk","mm_journal",
          "mm_feedback","mm_backtest","mm_alerts","mm_news",
          "mm_sources","mm_agents","mm_stats","mm_memory","mm_engine"]
    results={};fail=0
    for m in mods:
        try:
            importlib.import_module(m).selftest();results[m]="OK"
        except Exception as e:
            fail+=1;results[m]="FAIL: %s"%str(e)[:120]
    return json.dumps({"ok":fail==0,"pass":len(mods)-fail,"fail":fail,"results":results},ensure_ascii=False)

def skill_files():
    """Список файлов скилла (для Settings > Skills). Путь — каталог скилла в APK."""
    _check()
    import mm_chain
    skill_dir=os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(mm_chain.__file__)),".."))
    out=[]
    for root,_,files in os.walk(skill_dir):
        for f in files:
            if f.endswith((".py",".md",".json",".txt")) and "__pycache__" not in root:
                p=os.path.join(root,f)
                out.append({"path":os.path.relpath(p,skill_dir),"size":os.path.getsize(p)})
    out.sort(key=lambda x:x["path"])
    return json.dumps({"skill_dir":skill_dir,"files":out,"version":_SKILL_VERSION},ensure_ascii=False)

_DATA_FILES=(
    "journal.csv","forecasts.csv","hypotheses.csv","config.json","alerts.json",
    "forecast_memory_v7.jsonl","agent_weights_v7.json","model_v7.json",
    "onchain_events.csv","onchain_views.csv","dominance_history.json","defillama_history.json",
)

def export_data_files():
    """Пути runtime-данных для экспорта ZIP: все рабочие файлы ядра, памяти,
    весов, модели, ончейн-истории и журнал ошибок."""
    _check()
    import mm_config
    base=mm_config.data_dir()
    out=[]
    for name in _DATA_FILES+("errors.log",):
        p=os.path.join(base,name)
        if os.path.exists(p): out.append({"path":name,"file":p})
    return json.dumps({"data_dir":base,"files":out},ensure_ascii=False)

def engine_diagnostics():
    """Единый диагностический снимок для экспорта ZIP: журнал движка, статус,
    память, веса — если что-то идёт не так, в экспорте будет видно что именно."""
    _check()
    import mm_engine,time
    eng=mm_engine.get_engine()
    out={"ts":int(time.time()*1000),"engine_version":mm_engine.ENGINE_VERSION,
         "log":eng.log_tail(80)}
    try: out["status"]=eng.status()
    except Exception as e: out["status"]={"error":str(e)}
    try: out["memory"]=eng.memory_view()
    except Exception as e: out["memory"]={"error":str(e)}
    return json.dumps(out,ensure_ascii=False)

def import_data_file(name,content):
    """Импорт runtime-файла из ZIP (замена) с перечиткой весов/модели при необходимости."""
    _check()
    import mm_config
    if name not in _DATA_FILES:
        return json.dumps({"ok":False,"error":"forbidden name: %s"%name})
    p=os.path.join(mm_config.data_dir(),name)
    os.makedirs(os.path.dirname(p),exist_ok=True)
    with open(p,"w",encoding="utf-8") as f: f.write(content)
    if name in ("agent_weights_v7.json","model_v7.json"):
        try:
            import mm_engine
            mm_engine.get_engine().reload_artifacts()
        except Exception:
            pass
    return json.dumps({"ok":True,"path":name})

_ready=False

# ================= NEURAL FORECAST ENGINE v7.0 =================

import threading
from functools import wraps
_instrument_lock = threading.RLock()
_instrument_generation = -1

def _generation_safe(index):
    """Serialize legacy-engine access and reject late work after a market switch."""
    def decorate(fn):
        @wraps(fn)
        def guarded(*args, **kwargs):
            global _instrument_generation
            generation = int(args[index] if len(args) > index else kwargs.get("generation", -1))
            with _instrument_lock:
                if generation >= 0:
                    if generation < _instrument_generation:
                        return json.dumps({"ok": False, "discarded": True, "error": "instrument changed"})
                    if generation != _instrument_generation:
                        _check()
                        import mm_engine
                        eng = mm_engine.get_engine()
                        for name in ("sessions", "klines", "fast", "prev_book"):
                            getattr(eng, name).clear()
                        _instrument_generation = generation
                return fn(*args, **kwargs)
        return guarded
    return decorate

@_generation_safe(3)
def engine_set_klines(symbol,tf,rows_json,generation=-1):
    """Тяжёлый канал данных: свечи по таймфрейму от Kotlin (REST+WS, раз в ~60с)."""
    _check()
    import mm_engine
    try:
        rows=json.loads(rows_json) if isinstance(rows_json,str) else rows_json
        return json.dumps({"ok":mm_engine.get_engine().set_klines(symbol,str(tf),rows)},ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok":False,"error":str(e)},ensure_ascii=False)

@_generation_safe(2)
def engine_feed(symbol,fast_json="{}",generation=-1):
    """Лёгкий канал + полный прогон движка. fast_json — живой срез
    рынка от Kotlin (цена/потоки/книга/деривативы/кросс/мульти-биржи).
    Возвращает JSON результата конвейера (агенты/совет/прогноз 6ч и 24ч/сессия)."""
    _check()
    import mm_engine
    try:
        fast=json.loads(fast_json) if fast_json and fast_json!="{}" else {}
        mm_engine.get_engine().feed(symbol,fast)
        res=mm_engine.get_engine().compute(symbol)
        return _dumps(res)
    except Exception as e:
        return json.dumps({"ok":False,"error":str(e),"trace":traceback.format_exc()[-300:]},ensure_ascii=False)

@_generation_safe(3)
def engine_outlook(symbol="BTCUSDT",horizon_h=6,fast_json="{}",generation=-1):
    """Свежий пересчёт прогноза на 6ч или 24ч без кэша на каждый запрос пользователя."""
    _check()
    import mm_engine
    try:
        fast=json.loads(fast_json) if fast_json and fast_json!="{}" else {}
        eng=mm_engine.get_engine()
        if fast: eng.feed(symbol,fast)
        res=eng.compute_outlook(symbol,int(horizon_h))
        return _dumps(res)
    except Exception as e:
        return json.dumps({"ok":False,"status":"INSUFFICIENT","error":str(e)},ensure_ascii=False)

def engine_status():
    """Статус движка: сессии, модель, веса агентов, кэш свечей."""
    _check()
    import mm_engine
    return json.dumps(mm_engine.get_engine().status(),ensure_ascii=False)

def engine_memory():
    """Память: статистика прогнозов, точность агентов, калибровка, дрейф, веса."""
    _check()
    import mm_engine
    return json.dumps(mm_engine.get_engine().memory_view(),ensure_ascii=False)

def engine_log(n=50):
    """Журнал вычислений (последние n записей)."""
    _check()
    import mm_engine
    return json.dumps({"entries":mm_engine.get_engine().log_tail(int(n))},ensure_ascii=False)

def engine_refresh_slow():
    """Обновить медленный контекст (DefiLlama + mempool.space). Не блокирует конвейер."""
    _check()
    import mm_engine
    try:
        mm_engine.get_engine().refresh_slow()
        return json.dumps({"ok":True},ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok":False,"error":str(e)},ensure_ascii=False)

def engine_fetch_deriv(symbol="BTCUSDT"):
    """Редкий деривативный бэкфилл (раз в ~30 мин): история funding, LSR+перцентиль,
    OI 24ч. Ответ кладётся в fast-срез движка (merge) —deriv-агент видит перцентили."""
    _check()
    import mm_sources,mm_engine
    try:
        d=mm_sources.deriv_backfill(symbol)
        fh=d.get("funding_hist",{});lsr=d.get("lsr",{});oih=d.get("oi_hist",{})
        part={"deriv_extra":{"ok":bool(fh.get("ok") or lsr.get("ok")),
                "funding_hist":fh.get("history") if fh.get("ok") else [],
                "lsr":lsr.get("ratio") if lsr.get("ok") else None,
                "lsr_pct_rank":lsr.get("pct_rank"),
                "oi_pct_change":oih.get("oi_pct_change") if oih.get("ok") else None,
                "status":{"funding_hist":fh.get("status"),"lsr":lsr.get("status"),"oi_hist":oih.get("status")}}}
        mm_engine.get_engine().feed(symbol,part)
        return json.dumps({"ok":True,"part":part["deriv_extra"]},ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok":False,"error":str(e)},ensure_ascii=False)

def engine_fetch_aux(symbol="BTCUSDT"):
    """Редкий кросс-биржевой бэкфилл: funding/OI OKX (для агента CROSS-EXCHANGE)."""
    _check()
    import mm_sources,mm_engine
    try:
        of=mm_sources.okx_funding(symbol);oo=mm_sources.okx_oi(symbol)
        part={"okx_extra":{"ok":bool(of.get("ok")),
                           "funding_pct":of.get("funding_pct"),
                           "oi":oo.get("oi") if oo.get("ok") else None}}
        mm_engine.get_engine().feed(symbol,part)
        return json.dumps({"ok":True,"part":part["okx_extra"]},ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok":False,"error":str(e)},ensure_ascii=False)

def engine_fetch_options(symbol="BTCUSDT"):
    """v7.2: опционный бэкфилл Deribit (агент 13 OPTIONS_ANALYST): DVOL + книга
    опционов ближайшей экспирации. Внутри mm_sources кэш 10 минут — вызывать
    можно хоть каждые 5с, сеть дёргается редко."""
    _check()
    import mm_sources,mm_engine
    try:
        o=mm_sources.deribit_options(symbol)
        part={"options":o}
        mm_engine.get_engine().feed(symbol,part)
        return json.dumps({"ok":bool(o.get("ok")),"part":o},ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok":False,"error":str(e)},ensure_ascii=False)

def engine_fetch_web(symbol="BTCUSDT"):
    """v8.0 ПАУТИНА ДАННЫХ: киты Binance (aggTrades >= $100K, кэш 60с) +
    макро CoinGecko (доминирование BTC с дельтой по локальной истории, кэш 10м).
    Merge-подача: части не теряются между 5с-циклами конвейера."""
    _check()
    import mm_sources,mm_engine
    try:
        w=mm_sources.binance_whales(symbol)
        g=mm_sources.coingecko_global()
        part={"whales":w,"macro":g}
        mm_engine.get_engine().feed(symbol,part)
        return json.dumps({"ok":bool(w.get("ok") or g.get("ok")),"part":part},ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok":False,"error":str(e)},ensure_ascii=False)

def engine_verify_expired():
    """Закрыть истёкшие 15-минутные прогнозы по фактическому рынку."""
    _check()
    import mm_memory
    try:
        n,e=mm_memory.verify()
        return json.dumps({"ok":True,"checked":n,"errors":e},ensure_ascii=False)
    except Exception as ex:
        return json.dumps({"ok":False,"error":str(ex)},ensure_ascii=False)

def engine_forecast_history(n=50):
    """Последние записи памяти прогнозов (для экрана истории приложения)."""
    _check()
    import mm_memory
    try:
        return json.dumps({"rows":mm_memory.history(int(n))},ensure_ascii=False)
    except Exception as e:
        return json.dumps({"rows":[],"error":str(e)},ensure_ascii=False)

def engine_train_model(symbol="BTCUSDT"):
    """Фоновое walk-forward обучение статистической модели (~2000 свечей 1м).
    Модель включится только если честно бьёт базовую линию на walk-forward."""
    _check()
    import mm_stats,mm_sources,mm_engine,time
    try:
        r=mm_sources.bybit_klines(symbol,"1",1000)
        if not r.get("ok"):
            return json.dumps({"ok":False,"error":"свечи недоступны"},ensure_ascii=False)
        rows=r["rows"]
        r2=mm_sources.bybit_klines(symbol,"1",1000,end_ts=rows[0][0]-1)
        if r2.get("ok"):
            seen={x[0] for x in rows}
            rows=sorted(rows+[x for x in r2["rows"] if x[0] not in seen],key=lambda x:x[0])
        metrics,model=mm_stats.walk_forward(rows)
        if model is None:
            return json.dumps({"ok":False,"error":"недостаточно истории","metrics":metrics},ensure_ascii=False)
        model["symbol"]=symbol;model["trained_at"]=int(time.time())
        saved=mm_stats.save_model(model)
        mm_engine.get_engine().refresh_model()
        return json.dumps({"ok":saved,"ready":model.get("ready"),"metrics":metrics,
                           "model_version":model.get("version")},ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok":False,"error":str(e)},ensure_ascii=False)

# ================= v8.1: НОВЫЕ КОНТУРЫ (ТЗ v2-v6) =================

def engine_refresh_onchain(symbol="BTCUSDT"):
    """Обновить ончейн-контекст OCIE (mempool/blocks/large transfers/DefiLlama).
    Вызывается из редкого бэкфилла; при недоступности источники честно
    возвращают UNAVAILABLE — конвейер не блокируется."""
    _check()
    import mm_onchain_engine,mm_onchain_memory
    try:
        eng=mm_onchain_engine.get_engine()
        ctx,statuses=eng.refresh(symbol,max_age_sec=0.1)
        # события и вывод контура — в отдельную память для UI ON-CHAIN
        lt=ctx.get("large_transfers") or {}
        n_ev=mm_onchain_memory.record_events(lt.get("events") or [],symbol)
        s=eng.analyze(symbol,refresh=False)
        mm_onchain_memory.record_view(s)
        return json.dumps({"ok":True,"sources":statuses,"n_events_stored":n_ev,
                           "state":s.get("state")},ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok":False,"error":str(e)},ensure_ascii=False)

def engine_onchain_events(n=30,symbol=None):
    """Последние ончейн-события для UI (§6): сеть, сумма, тип, метка, провайдер."""
    _check()
    import mm_onchain_memory
    try:
        rows=mm_onchain_memory.recent_events(int(n),symbol)
        return json.dumps({"rows":rows},ensure_ascii=False)
    except Exception as e:
        return json.dumps({"rows":[],"error":str(e)},ensure_ascii=False)

def engine_run_graph(symbol="BTCUSDT",budget=45.0):
    """NEURAL EXECUTION GRAPH (ТЗ v3/v4): полный путь A->J с трассировкой.
    Переиспользует движки (не второй конвейер); результат — для экрана
    NEURAL PATH: статусы узлов, trace-события, evidence."""
    _check()
    import mm_engine,mm_graph,mm_trend,mm_outlook24
    try:
        def engine_compute(ctx):
            return mm_engine.get_engine().compute(ctx.asset,store=False)
        def ocie_compute(ctx):
            import mm_onchain_engine
            return mm_onchain_engine.get_engine().analyze(ctx.asset,refresh=False)
        def trend_compute(ctx):
            fb=mm_engine.get_engine()._fb(ctx.asset)
            return mm_trend.trend_layer(fb.get("klines") or {})
        def outlook_compute(ctx):
            fb=mm_engine.get_engine()._fb(ctx.asset)
            return mm_outlook24.outlook_6h(fb.get("klines") or {},None,fb.get("news") or [],deriv=fb.get("deriv"))
        g=mm_graph.build_graph(engine_compute,ocie_compute,trend_compute,outlook_compute)
        r=mm_graph.Orchestrator(g).run(symbol,"6h",budget_sec=float(budget))
        return _dumps(r)
    except Exception as e:
        return json.dumps({"ok":False,"error":str(e),
                           "trace":traceback.format_exc()[-300:]},ensure_ascii=False)

def engine_source_registry():
    """SOURCE REGISTRY (ТЗ §54): все источники с честными статусами."""
    _check()
    import mm_registry
    try:
        return json.dumps({"ok":True,"sources":mm_registry.source_registry()},
                          ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok":False,"error":str(e)},ensure_ascii=False)

def engine_calc_registry():
    """CALCULATION REGISTRY (ТЗ §56): вычислители с версиями и golden-тестами."""
    _check()
    import mm_registry
    try:
        return json.dumps({"ok":True,"calculators":mm_registry.calculation_registry()},
                          ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok":False,"error":str(e)},ensure_ascii=False)

def engine_discovery(symbol="BTCUSDT"):
    """MARKET DISCOVERY (ТЗ §42): скан формаций по 60m/15m свечам символа."""
    _check()
    import mm_engine,mm_discovery
    try:
        fb=mm_engine.get_engine()._fb(symbol)
        kl=fb.get("klines") or {}
        rows=kl.get("60") or kl.get("15") or []
        scan=mm_discovery.scan_symbol(rows)
        cands=[]
        if scan.get("patterns"):
            c=mm_discovery.make_candidate(symbol,scan["patterns"],
                                          scan["priority_score"],
                                          data_quality=(fb.get("weights") and "A" or "C"))
            cands=[mm_discovery.rank_candidates([c])[0]]
        return json.dumps({"ok":True,"scan":scan,"candidates":cands},ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok":False,"error":str(e)},ensure_ascii=False)
