"""OCIE v8.1 — mm_onchain_engine: оркестрация второго контура (ТЗ §4.3).

Поток: PUBLIC BLOCKCHAIN DATA -> adapters (sources) -> normalize/dedup
-> 10 агентов OCIE -> результат контура (сводка + статусы источников).

Интеграция с ENA-MIND (§5): OCIE НЕ перезаписывает прогноз Market Engine.
Его вывод уходит отдельным этапом ON-CHAIN REVIEW в Meta-Council:
- подтверждает/противоречит рыночному прогнозу;
- при недоступности — Market Engine работает с пометкой ONCHAIN_UNAVAILABLE;
- при конфликте — снижает confidence, а не «побеждает» рынок.
"""
import time
import mm_onchain_sources as src
import mm_onchain_agents as ag

_ENGINE=None

class OnchainEngine:
    def __init__(self,cfg=None):
        self.cfg=cfg
        self.ctx={}          # asset -> последний контекст
        self.ctx_ts={}       # asset -> время обновления
        self.fee_history=[]  # история комиссий BTC для anomaly baseline
        self.statuses={}     # asset -> статусы источников
        self.last_agents={}  # asset -> последние результаты агентов
        self.n_runs=0

    # ---------------- сбор данных (с кэшем по возрасту) ----------------
    def refresh(self,asset="BTC",max_age_sec=120.0,include_txs=True):
        """Собрать контекст по активу (не чаще max_age_sec). Возвращает
        (context, statuses). Один недоступный источник не ломает остальные."""
        now=time.time()
        if now-self.ctx_ts.get(asset,0.0)<max_age_sec:
            return self.ctx.get(asset,{}),self.statuses.get(asset,{})
        ctx,statuses=src.collect_context(asset,self.cfg,include_btc_txs=include_txs)
        # история комиссий для baseline аномалий (только BTC)
        mp=ctx.get("mempool") or {}
        if mp.get("ok") and mp.get("fee_fast") is not None:
            self.fee_history.append(float(mp["fee_fast"]))
            if len(self.fee_history)>200:
                self.fee_history=self.fee_history[-200:]
        self.ctx[asset]=ctx
        self.statuses[asset]=statuses
        self.ctx_ts[asset]=now
        return ctx,statuses

    # ---------------- прогон агентов ----------------
    def analyze(self,asset="BTC",refresh=True,max_age_sec=120.0):
        """Полный прогон OCIE: сбор + агенты + сводка контура.
        Возвращает dict для Meta-Council (контракт §4.3/§5)."""
        t0=time.time()
        if refresh:
            ctx,statuses=self.refresh(asset,max_age_sec=max_age_sec)
        else:
            ctx=self.ctx.get(asset,{});statuses=self.statuses.get(asset,{})
        agents=ag.run_all(ctx,statuses=statuses,fee_history=self.fee_history)
        self.last_agents[asset]=agents
        self.n_runs+=1
        summary=self.summarize(agents,statuses)
        summary["asset"]=asset
        summary["engine"]="ocie_v1"
        summary["elapsed_ms"]=int((time.time()-t0)*1000)
        summary["sources"]=statuses
        summary["agents"]=agents
        return summary

    # ---------------- сводка контура (вход ON-CHAIN REVIEW) ----------------
    def summarize(self,agents,statuses):
        """Свести агентов контура. НЕ простая сумма голосов (§4.3):
        - голосуют только OK-агенты с direction != NEUTRAL;
        - correlation_group ограничивает совокупный вклад группы;
        - качество источников модулирует уверенность."""
        ok=[a for a in agents if a["status"]=="OK"]
        voting=[a for a in ok if a["direction"] in ("LONG","SHORT")]
        # группы корреляции: одна группа = один голос (нет двойного учёта)
        groups={}
        for a in voting:
            g=a.get("correlation_group") or "onchain"
            if g not in groups or a["confidence"]>groups[g]["confidence"]:
                groups[g]=a
        longs=[g for g in groups.values() if g["direction"]=="LONG"]
        shorts=[g for g in groups.values() if g["direction"]=="SHORT"]
        avail=sum(1 for v in statuses.values() if v=="AVAILABLE")
        total=max(1,len(statuses))
        quality=round(avail/float(total),3)
        # итог контура
        if avail==0:
            state="ONCHAIN_UNAVAILABLE"
            note="ончейн-источники недоступны — Market Engine продолжает работу без второго контура"
            direction="NEUTRAL";confidence=0.0
        elif longs and shorts:
            state="CONFLICT"
            note="конфликт внутри OCIE: %s LONG против %s SHORT"%(
                ",".join(a["agent"] for a in longs),",".join(a["agent"] for a in shorts))
            direction="NEUTRAL";confidence=0.15
        elif longs:
            state="CONFIRMED";direction="LONG"
            confidence=min(0.45,max(a["confidence"] for a in longs)*quality)
            note="ончейн-контур: %s"%(longs[0]["evidence"][0] if longs[0]["evidence"] else "")
        elif shorts:
            state="CONFIRMED";direction="SHORT"
            confidence=min(0.45,max(a["confidence"] for a in shorts)*quality)
            note="ончейн-контур: %s"%(shorts[0]["evidence"][0] if shorts[0]["evidence"] else "")
        else:
            state="NO_SIGNAL";direction="NEUTRAL";confidence=0.10
            n_ins=sum(1 for a in agents if a["status"]=="INSUFFICIENT")
            note="ончейн-контур не имеет направленного вывода (INSUFFICIENT у %d/%d агентов)"%(
                n_ins,len(agents))
        return {"state":state,"direction":direction,"confidence":round(confidence,4),
                "note":note,"quality":quality,"n_ok_agents":len(ok),
                "n_voting_groups":len(groups),
                "limitations":["ончейн-сигналы: горизонт 1-4ч/1-7д, на 15м прогноз влияют "
                               "только через быструю ветку (§5)"]}

    # ---------------- API для моста ----------------
    def status(self):
        return {"engine":"ocie_v1","n_runs":self.n_runs,
                "fee_history_points":len(self.fee_history),
                "assets_cached":list(self.ctx.keys())}


def get_engine(cfg=None):
    global _ENGINE
    if _ENGINE is None: _ENGINE=OnchainEngine(cfg)
    return _ENGINE


def selftest():
    import tempfile,os
    d=tempfile.mkdtemp();os.environ["MM5_DATA_DIR"]=d
    try:
        eng=get_engine()
        # анализ без сети (адаптеры вернут UNAVAILABLE/INSUFFICIENT честно)
        # подменяем collect_context детерминированной фикстурой:
        def fake_collect(asset="BTC",cfg=None,include_btc_txs=True):
            from mm_onchain_schema import new_event
            evs=[new_event("bitcoin","txA",0,"exchange_inflow","BTC",120.0,8,
                           block_ts=int(time.time())-600,confirmations=2,
                           label_to="Binance",label_confidence="verified",
                           provider="fixture"),
                 new_event("bitcoin","txB",0,"transfer","BTC",60.0,8,
                           block_ts=int(time.time())-300,confirmations=1,
                           provider="fixture")]
            ctx={"asset":asset,
                 "mempool":{"ok":True,"status":"AVAILABLE","fee_fast":42,"mempool_tx":90000},
                 "blocks":{"ok":True,"status":"AVAILABLE",
                           "blocks":[{"height":800000,"tx_count":2500,"ts":int(time.time())}],
                           "median_block_sec":595},
                 "large_transfers":{"ok":True,"status":"AVAILABLE","events":evs},
                 "defi":{"ok":True,"status":"AVAILABLE","total_tvl":1.7e11}}
            statuses={"btc_mempool":"AVAILABLE","btc_blocks":"AVAILABLE",
                      "btc_large_transfers":"AVAILABLE","defillama":"AVAILABLE"}
            return ctx,statuses
        orig=src.collect_context
        src.collect_context=fake_collect
        try:
            s=eng.analyze("BTC")
            assert s["state"] in ("CONFIRMED","NO_SIGNAL","CONFLICT"),s
            assert len(s["agents"])==10
            # нетвлоу: inflow 120 без outflow -> потенциальный SHORT-контекст
            net=[a for a in s["agents"] if a["agent"]=="EXCHANGE_NETFLOW_AGENT"][0]
            assert net["status"]=="OK" and net["direction"]=="SHORT",net
            # уверенность контура ограничена 0.45 и умножена на quality
            assert 0.0<=s["confidence"]<=0.45
            # summarize без источников -> ONCHAIN_UNAVAILABLE (§5)
            summ=eng.summarize(eng.last_agents["BTC"],{})
            assert summ["state"]=="ONCHAIN_UNAVAILABLE"
            # конфликт внутри контура
            conflicting=[{"agent":"A","status":"OK","direction":"LONG","confidence":0.4,
                           "evidence":["x"],"correlation_group":"g1"},
                          {"agent":"B","status":"OK","direction":"SHORT","confidence":0.4,
                           "evidence":["y"],"correlation_group":"g2"}]
            summ=eng.summarize(conflicting,{"s1":"AVAILABLE"})
            assert summ["state"]=="CONFLICT"
            # одна correlation group не голосует дважды
            dup=[{"agent":"A1","status":"OK","direction":"LONG","confidence":0.3,
                  "evidence":["x"],"correlation_group":"g1"},
                 {"agent":"A2","status":"OK","direction":"LONG","confidence":0.5,
                  "evidence":["y"],"correlation_group":"g1"}]
            summ=eng.summarize(dup,{"s1":"AVAILABLE"})
            assert summ["n_voting_groups"]==1
            assert summ["state"]=="CONFIRMED" and summ["direction"]=="LONG"
            # статус движка
            st=eng.status();assert st["engine"]=="ocie_v1" and st["n_runs"]>=1
        finally:
            src.collect_context=orig
    finally:
        del os.environ["MM5_DATA_DIR"]
        global _ENGINE;_ENGINE=None
    print("mm_onchain_engine: OK")
