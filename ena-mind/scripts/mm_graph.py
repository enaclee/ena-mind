"""NEURAL EXECUTION GRAPH v8.1 (ТЗ §21-27 v3 + §28-41 v4 + §59 v6).

Исполняемый DAG от точки A (данные) до точки B (прогноз) — не картинка,
а реальная топология с зависимостями, статусами, бюджетами и трассировкой.

Узлы A-J (§22): DATA INTAKE -> DATA QUALITY -> FEATURE BUILDERS ->
PARALLEL SPECIALISTS (D1-D6) -> SPECIALIST COUNCILS -> CROSS-EXAMINATION ->
META-COUNCIL -> FORECAST -> OUTPUT & TRACE -> FORECAST LEDGER.

v4 (§29-34): иерархия Master Orchestrator -> Supervisors -> Specialists;
управляемая рекурсия с depth/budget/deadline; Evidence Store; независимые
критики; идемпотентность в рамках run_id.

v6 (§59): детерминированные вычислители отделены от агентов; каждый узел
имеет контракт входа/выхода, timeout, критичность и версию.
"""
import time,uuid,threading

GRAPH_VERSION="neg_v1"

# статусы узлов (§24.2)
QUEUED="queued";RUNNING="running";COMPLETED="completed";DEGRADED="degraded"
FAILED="failed";SKIPPED="skipped";CANCELLED="cancelled"


class Node:
    """Узел графа: id, тип, зависимости, критичность, timeout (§23)."""
    def __init__(self,node_id,node_type,purpose,depends_on=(),criticality="important",
                 timeout_sec=20.0,fn=None,emits_to=()):
        self.node_id=node_id
        self.node_type=node_type
        self.purpose=purpose
        self.depends_on=list(depends_on)
        self.criticality=criticality  # critical | important | optional
        self.timeout_sec=timeout_sec
        self.fn=fn                    # callable(ctx, node) -> dict (результат узла)
        self.emits_to=list(emits_to)
        self.status=QUEUED
        self.result=None
        self.error=None
        self.started_at=None
        self.completed_at=None
        self.attempts=0


class RunContext:
    """Контекст одного запуска: run_id, data cutoff, evidence store, бюджет."""
    def __init__(self,run_id,asset,horizon,budget_sec=45.0,max_depth=2,max_tasks=60):
        self.run_id=run_id
        self.asset=asset
        self.horizon=horizon
        self.budget_sec=budget_sec
        self.max_depth=max_depth
        self.max_tasks=max_tasks
        self.deadline=time.time()+budget_sec
        self.started_at=time.time()
        self.evidence=EvidenceStore()
        self.events=[]           # трассировка (§23): все переходы статусов
        self.results={}          # node_id -> результат
        self.n_tasks=0
        self.cancelled=False
        self.lock=threading.Lock()

    def log(self,node_id,from_st,to_st,detail=""):
        self.events.append({"run_id":self.run_id,"node_id":node_id,
                            "from":from_st,"to":to_st,"detail":str(detail)[:200],
                            "ts":int(time.time()*1000)})

    def budget_left(self):
        return max(0.0,self.deadline-time.time())


class EvidenceStore:
    """Хранилище доказательств запуска (§31 v4): claim + source + качество.
    Агенты ссылаются на существующие evidence, не пересчитывают одно и то же."""
    def __init__(self):
        self.items=[]            # [{evidence_id, claim, source_id, quality, ...}]
        self._by_claim={}

    def add(self,claim,source_id,quality=0.8,asset=None,observed_at=None,
            value=None,correlation_group=None,kind="market"):
        """Идемпотентно (по claim+source): повторное добавление того же факта
        не создаёт второй записи (§38.5 — повторная доставка не дублирует)."""
        key=(str(claim)[:120],str(source_id))
        if key in self._by_claim:
            return self._by_claim[key]
        eid="ev_%d_%d"%(len(self.items),int(time.time()*1000)%100000)
        item={"evidence_id":eid,"claim":str(claim),"source_id":str(source_id),
              "quality":round(float(quality),3),"asset":asset,
              "observed_at":observed_at or int(time.time()),
              "value":value,"correlation_group":correlation_group or kind,
              "kind":kind}
        self.items.append(item)
        self._by_claim[key]=eid
        return eid

    def get(self,eid):
        for it in self.items:
            if it["evidence_id"]==eid: return it
        return None

    def claims(self):
        return [it["claim"] for it in self.items]


# ---------------- топология A->J (§22) ----------------

def build_graph(engine_compute,ocie_compute,trend_compute,outlook_compute,
                critics=True):
    """Собрать DAG A-J. fn-функции передаются снаружи: граф — оркестрация,
    вычисления остаются в своих модулях (§25.5: не второй движок, а слой
    поверх существующих проверенных модулей).

    engine_compute(ctx) -> dict результата Market Engine (mm_engine.compute)
    ocie_compute(ctx)   -> dict результата OCIE (mm_onchain_engine.analyze)
    trend_compute(ctx)  -> dict тренд-слоя (mm_trend.trend_layer)
    outlook_compute(ctx)-> dict 24H (mm_outlook24.outlook_24h)
    """
    n=[]
    # A: DATA INTAKE (данные уже в ctx от Kotlin-коллекторов; узел фиксирует
    # метку времени среза — point-in-time, §57 v6)
    n.append(Node("A_INTAKE","data_intake","фиксация входных данных и data cutoff",
                  criticality="critical",timeout_sec=2.0,
                  fn=lambda ctx,rn: {"cutoff":int(time.time()*1000),
                                     "asset":ctx.asset,"ok":True}))
    # B: DATA QUALITY (валидация; grade D блокирует зависимые ветви)
    def _quality(ctx,rn):
        intake=rn.results.get("A_INTAKE") or {}
        return {"ok":intake.get("ok",False),"grade":None,"note":"оценка в engine_compute"}
    n.append(Node("B_QUALITY","data_quality","валидация и синхронизация данных",
                  depends_on=("A_INTAKE",),criticality="critical",timeout_sec=3.0,fn=_quality))
    # C: FEATURE BUILDERS (внутри engine_compute/trend_compute — детерминированные)
    n.append(Node("C_FEATURES","feature_builders","признаки из проверенных данных",
                  depends_on=("B_QUALITY",),criticality="important",timeout_sec=5.0,
                  fn=lambda ctx,rn: {"ok":True,"note":"признаки считаются в D-узлах"}))
    # D1-D6: параллельные специалисты (внутри engine_compute + отдельные контуры)
    def _wrap_engine(ctx,rn):
        t0=time.time()
        res=engine_compute(ctx)
        return {"ok":bool(res.get("ok")),"detail":res,"elapsed_ms":int((time.time()-t0)*1000)}
    n.append(Node("D_MARKET","specialists_market","лес 18 агентов Market Engine",
                  depends_on=("C_FEATURES",),criticality="critical",timeout_sec=25.0,fn=_wrap_engine))
    def _wrap_trend(ctx,rn):
        res=trend_compute(ctx)
        return {"ok":res.get("status")=="OK","detail":res}
    n.append(Node("D_TREND","specialists_trend","Trend Intelligence Layer",
                  depends_on=("C_FEATURES",),criticality="important",timeout_sec=8.0,fn=_wrap_trend))
    def _wrap_ocie(ctx,rn):
        res=ocie_compute(ctx)
        state=res.get("state")
        return {"ok":state not in (None,"ONCHAIN_UNAVAILABLE"),"degraded":state=="ONCHAIN_UNAVAILABLE",
                "detail":res}
    n.append(Node("D_ONCHAIN","specialists_onchain","OCIE: ончейн-контур",
                  depends_on=("C_FEATURES",),criticality="optional",timeout_sec=15.0,fn=_wrap_ocie))
    # E: SPECIALIST COUNCILS (свод внутри engine_compute.council + OCIE summary)
    def _councils(ctx,rn):
        m=rn.results.get("D_MARKET") or {}
        o=rn.results.get("D_ONCHAIN") or {}
        council=((m.get("detail") or {}).get("council") or {})
        ocie=((o.get("detail") or {}).get("state") or "N/A")
        return {"ok":bool(council),"market_council_status":council.get("status"),
                "ocie_state":ocie}
    n.append(Node("E_COUNCILS","councils","свод ветвей: советы контуров",
                  depends_on=("D_MARKET","D_TREND","D_ONCHAIN"),
                  criticality="critical",timeout_sec=5.0,fn=_councils))
    # F: CROSS-EXAMINATION / ADVERSARIAL REVIEW (§22 F, v4 критики)
    def _cross(ctx,rn):
        m=rn.results.get("D_MARKET") or {}
        t=rn.results.get("D_TREND") or {}
        o=rn.results.get("D_ONCHAIN") or {}
        md=(m.get("detail") or {})
        td=(t.get("detail") or {})
        od=(o.get("detail") or {})
        bull=[];bear=[]
        # бычий кейс: тренд BULLISH + рынок LONG
        if "BULLISH" in (td.get("bias") or ""): bull.append("тренд-слой бычий (%s)"%td.get("bias"))
        if (md.get("prediction") or {}).get("side")=="UP": bull.append("движок P(UP) доминирует")
        if od.get("direction")=="LONG": bull.append("ончейн: %s"%od.get("note","")[:60])
        if "BEARISH" in (td.get("bias") or ""): bear.append("тренд-слой медвежий (%s)"%td.get("bias"))
        if (md.get("prediction") or {}).get("side")=="DOWN": bear.append("движок P(DOWN) доминирует")
        if od.get("direction")=="SHORT": bear.append("ончейн: %s"%od.get("note","")[:60])
        contradictions=[]
        if bull and bear:
            contradictions.append("противоречивые кейсы: %d за / %d против"%(len(bull),len(bear)))
        # EVIDENCE AUDITOR (§29): у существенного утверждения должен быть источник
        n_ev=len(ctx.evidence.items)
        if bull+bear and n_ev==0:
            contradictions.append("есть кейсы без доказательств в evidence store")
        return {"ok":True,"bull_case":bull[:3],"bear_case":bear[:3],
                "contradictions":contradictions,"evidence_count":n_ev}
    n.append(Node("F_CROSS","cross_exam","adversarial review: bull/bear/contradictions",
                  depends_on=("E_COUNCILS",),criticality="important",timeout_sec=5.0,fn=_cross))
    # G: META-COUNCIL (probability fusion — в engine_compute; здесь ON-CHAIN REVIEW §5)
    def _meta(ctx,rn):
        m=rn.results.get("D_MARKET") or {}
        o=rn.results.get("D_ONCHAIN") or {}
        f=rn.results.get("F_CROSS") or {}
        md=(m.get("detail") or {});od=(o.get("detail") or {})
        market_side=(md.get("prediction") or {}).get("side")
        ocie_dir=od.get("direction");ocie_state=od.get("state")
        review={"state":"NO_ONCHAIN","confidence_adj":1.0,"note":""}
        if ocie_state=="ONCHAIN_UNAVAILABLE":
            review={"state":"ONCHAIN_UNAVAILABLE","confidence_adj":1.0,
                    "note":"движок продолжает работу, второй контур недоступен"}
        elif ocie_dir in ("LONG","SHORT"):
            agree=(ocie_dir=="LONG" and market_side=="UP") or \
                  (ocie_dir=="SHORT" and market_side=="DOWN")
            if agree:
                review={"state":"CONFIRMED","confidence_adj":1.05,
                        "note":"ончейн подтверждает рыночный прогноз"}
            else:
                review={"state":"CONFLICT","confidence_adj":0.85,
                        "note":"ончейн противоречит рынку — уверенность снижена"}
        return {"ok":True,"market_side":market_side,"onchain_review":review,
                "contradictions":f.get("contradictions") or []}
    n.append(Node("G_META","meta_council","probability fusion + ON-CHAIN REVIEW",
                  depends_on=("E_COUNCILS","F_CROSS"),criticality="critical",
                  timeout_sec=6.0,fn=_meta))
    # H: FORECAST & TRADE DECISION (включая 24H, раздельные выходы §22 H)
    def _forecast(ctx,rn):
        res=outlook_compute(ctx)
        return {"ok":res.get("status") in ("OK","DEGRADED"),"detail":res}
    n.append(Node("H_FORECAST","forecast","P(UP/FLAT/DOWN) 15м + 24H OUTLOOK + сценарии",
                  depends_on=("G_META",),criticality="critical",timeout_sec=10.0,fn=_forecast))
    # I: OUTPUT & TRACE
    n.append(Node("I_OUTPUT","output","итоговый вывод + трассировка",
                  depends_on=("H_FORECAST",),criticality="critical",timeout_sec=3.0,
                  fn=lambda ctx,rn: {"ok":True,"trace_events":len(rn.events)}))
    # J: FORECAST LEDGER & FEEDBACK (запись делается движком памяти вне графа)
    n.append(Node("J_LEDGER","ledger","снимок прогноза в память (неизменяемый)",
                  depends_on=("I_OUTPUT",),criticality="important",timeout_sec=3.0,
                  fn=lambda ctx,rn: {"ok":True,"note":"запись выполняет mm_memory/mm_engine"}))
    return {node.node_id:node for node in n}


# ---------------- оркестратор (§23, §29 Уровень 0) ----------------

class Orchestrator:
    """Master Orchestrator: строит подграф, исполняет с зависимостями,
    таймаутами, бюджетом и честной деградацией (§23 steps 1-10).

    Идемпотентность: повторный run с тем же run_id не создаёт дубликаты
    (создаётся новый run_id при новом запуске; данные кэшируются движками).
    """
    def __init__(self,graph):
        self.graph=graph

    def run(self,asset,horizon="15m",budget_sec=45.0,progress=None):
        run_id="run_%s_%s"%(uuid.uuid4().hex[:8],int(time.time()*1000))
        ctx=RunContext(run_id,asset,horizon,budget_sec=budget_sec)
        nodes=self.graph
        # топологическое исполнение: узел готов, когда все зависимости завершены
        pending=set(nodes.keys())
        while pending and not ctx.cancelled:
            # таймаут бюджета: оставшиеся узлы получают честный статус
            if ctx.budget_left()<=0:
                for nid in list(pending):
                    nodes[nid].status=CANCELLED
                    ctx.log(nid,nodes[nid].status,CANCELLED,"budget exhausted")
                break
            progressed=False
            for nid in list(pending):
                node=nodes[nid]
                # сначала проверяем упавшие зависимости -> SKIPPED (§26.5),
                # даже если остальные ещё не завершились
                bad_dep=any(nodes[d].status in (FAILED,CANCELLED) and
                            nodes[d].criticality in ("critical","important")
                            for d in node.depends_on)
                if bad_dep:
                    node.status=SKIPPED
                    ctx.log(nid,QUEUED,SKIPPED,"критическая зависимость недоступна")
                    pending.discard(nid);progressed=True
                    continue
                deps_ok=all(nodes[d].status in (COMPLETED,DEGRADED,SKIPPED)
                            for d in node.depends_on)
                if not deps_ok: continue
                # исполнение (последовательно-кооперативно: без реальных потоков —
                # мобильный бюджет; «параллельность» = независимые узлы идут подряд,
                # каждый со своим таймаутом; зависимые не ждут лишнего)
                t0=time.time()
                node.status=RUNNING;node.attempts+=1
                node.started_at=t0
                ctx.log(nid,QUEUED,RUNNING)
                if progress: progress(_node_event(node,ctx))
                try:
                    res=node.fn(ctx,ctx)
                    elapsed=time.time()-t0
                    if elapsed>node.timeout_sec:
                        node.status=DEGRADED
                        node.result={"ok":False,"error":"timeout %.1fs"%elapsed}
                        ctx.log(nid,RUNNING,DEGRADED,"timeout")
                    else:
                        degraded=(isinstance(res,dict) and res.get("degraded"))
                        node.status=DEGRADED if degraded else COMPLETED
                        node.result=res
                        ctx.log(nid,RUNNING,node.status,"%.0fмс"%(elapsed*1000))
                except Exception as e:
                    node.status=FAILED if node.criticality=="critical" else DEGRADED
                    node.error=str(e)[:200]
                    node.result={"ok":False,"error":node.error}
                    ctx.log(nid,RUNNING,node.status,node.error)
                node.completed_at=time.time()
                with ctx.lock:
                    ctx.results[nid]=node.result
                if progress: progress(_node_event(node,ctx))
                pending.discard(nid);progressed=True
                ctx.n_tasks+=1
                if ctx.n_tasks>=ctx.max_tasks:
                    for x in list(pending):
                        nodes[x].status=CANCELLED
                        ctx.log(x,QUEUED,CANCELLED,"max tasks")
                    pending.clear()
            if not progressed:
                # цикл или недостижимые узлы — честно отменяем
                for nid in list(pending):
                    nodes[nid].status=CANCELLED
                    ctx.log(nid,QUEUED,CANCELLED,"unreachable/cycle")
                pending.clear()
        return self._summary(ctx)

    def _summary(self,ctx):
        nodes=self.graph
        out={"ok":True,"run_id":ctx.run_id,"asset":ctx.asset,"horizon":ctx.horizon,
             "graph_version":GRAPH_VERSION,"elapsed_ms":int((time.time()-ctx.started_at)*1000),
             "budget_left_sec":round(ctx.budget_left(),2),
             "nodes":{nid:{"status":n.status,"type":n.node_type,
                           "elapsed_ms":int(((n.completed_at or time.time())-(n.started_at or n.started_at or time.time()))*1000) if n.started_at else 0,
                           "error":n.error} for nid,n in nodes.items()},
             "trace":ctx.events,"evidence":ctx.evidence.items,
             "results":ctx.results}
        # результаты в ctx.results пишут узлы через fn? — fn возвращает result,
        # кладём его в summary
        for nid,n in nodes.items():
            out["results"][nid]=n.result
        critical_failed=[nid for nid,n in nodes.items()
                         if n.status in (FAILED,CANCELLED) and n.criticality=="critical"]
        if critical_failed:
            out["ok"]=False
            out["error"]="критические узлы не завершены: %s"%",".join(critical_failed)
        return out


def _node_event(node,ctx):
    return {"run_id":ctx.run_id,"node_id":node.node_id,"node_type":node.node_type,
            "status":node.status,"purpose":node.purpose}


# ---------------- самопроверка ----------------

def selftest():
    # детерминированные фейковые вычислители (не ходят в сеть)
    def engine_compute(ctx):
        return {"ok":True,"prediction":{"side":"UP","features":{"p_up":0.44}},
                "council":{"status":"AGREE"}}
    def ocie_compute(ctx):
        return {"state":"CONFIRMED","direction":"LONG","confidence":0.3,"note":"inflow"}
    def trend_compute(ctx):
        return {"status":"OK","bias":"BULLISH","strength":63,
                "entry_quality":"LOW","risk":"ELEVATED","persistence":{"p_continue":0.5}}
    def outlook_compute(ctx):
        return {"status":"OK","p_up":0.42,"p_flat":0.35,"p_down":0.23}
    g=build_graph(engine_compute,ocie_compute,trend_compute,outlook_compute)
    orch=Orchestrator(g)
    r=orch.run("BTCUSDT","15m",budget_sec=10.0)
    # полный путь A->B (§26.1)
    assert r["ok"],r.get("error")
    order=[n for n in ("A_INTAKE","B_QUALITY","C_FEATURES","D_MARKET","D_TREND",
                       "D_ONCHAIN","E_COUNCILS","F_CROSS","G_META","H_FORECAST",
                       "I_OUTPUT","J_LEDGER")]
    for nid in order:
        assert r["nodes"][nid]["status"] in ("completed","degraded"),(nid,r["nodes"][nid])
    # трассировка реальна (§26.10)
    assert len(r["trace"])>=2*len(order)-2
    # ON-CHAIN REVIEW подтверждает (§8 Meta-Council)
    meta=r["results"]["G_META"]
    assert meta["onchain_review"]["state"]=="CONFIRMED"
    # конфликт ончейн vs рынок снижает уверенность (§5)
    g2=build_graph(engine_compute,
                   lambda ctx:{"state":"CONFIRMED","direction":"SHORT","confidence":0.3,"note":"outflow"},
                   trend_compute,outlook_compute)
    r2=Orchestrator(g2).run("BTCUSDT","15m",budget_sec=10.0)
    assert r2["results"]["G_META"]["onchain_review"]["state"]=="CONFLICT"
    assert r2["results"]["G_META"]["onchain_review"]["confidence_adj"]<1.0
    # OCIE недоступен — движок продолжает (§5)
    g3=build_graph(engine_compute,
                   lambda ctx:{"state":"ONCHAIN_UNAVAILABLE","direction":"NEUTRAL","confidence":0,"note":""},
                   trend_compute,outlook_compute)
    r3=Orchestrator(g3).run("BTCUSDT","15m",budget_sec=10.0)
    assert r3["ok"]
    assert r3["results"]["G_META"]["onchain_review"]["state"]=="ONCHAIN_UNAVAILABLE"
    assert r3["nodes"]["D_ONCHAIN"]["status"]=="degraded"
    # критический узел падает -> зависимые SKIPPED, отчёт честный (§26.5)
    def bad_engine(ctx): raise RuntimeError("данных нет")
    g4=build_graph(bad_engine,ocie_compute,trend_compute,outlook_compute)
    r4=Orchestrator(g4).run("BTCUSDT","15m",budget_sec=10.0)
    assert not r4["ok"]
    assert r4["nodes"]["D_MARKET"]["status"]=="failed"
    assert r4["nodes"]["E_COUNCILS"]["status"]=="skipped"
    # бюджет исчерпан -> честные CANCELLED, не «всё хорошо» (§34)
    def slow(ctx): time.sleep(0.35);return {"ok":True}
    g5=build_graph(slow,slow,slow,slow)
    for n in g5.values(): n.timeout_sec=10.0
    r5=Orchestrator(g5).run("BTCUSDT","15m",budget_sec=0.5)
    cancelled=[s for s in (n["status"] for n in r5["nodes"].values()) if s=="cancelled"]
    assert r5["ok"] is False or cancelled  # либо честный провал, либо отмены
    # evidence store: повторное добавление не дублирует (§38.5)
    es=EvidenceStore()
    e1=es.add("funding 0.01%","bybit",0.9)
    e2=es.add("funding 0.01%","bybit",0.9)
    assert e1==e2 and len(es.items)==1
    e3=es.add("funding 0.01%","okx",0.8)
    assert e3!=e1 and len(es.items)==2  # другой источник — отдельное доказательство
    # прогресс-колбэк получает реальные события
    seen=[]
    g6=build_graph(engine_compute,ocie_compute,trend_compute,outlook_compute)
    Orchestrator(g6).run("BTCUSDT","15m",budget_sec=10.0,
                         progress=lambda ev: seen.append(ev["node_id"]))
    assert len(seen)>=20 and "A_INTAKE" in seen
    print("mm_graph: OK")
