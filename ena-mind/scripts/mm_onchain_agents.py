"""OCIE v8.1 — mm_onchain_agents: 10 агентов второго контура (ТЗ §4.2).

Единый контракт результата (§4.2):
  agent, status, direction, confidence, evidence, source_ids, observed_at,
  age_sec, limitations, correlation_group

direction in (LONG, SHORT, NEUTRAL) — но ончейн-контур по умолчанию
КОНТЕКСТНЫЙ: большинство агентов осознанно возвращают NEUTRAL с описанием
наблюдаемого факта, потому что блокчейн-транзакция сама по себе не доказывает
направление рыночной сделки (§4.1).

Маршрутизация по сетям (§4.2): агенты запускаются только на поддерживаемых
сетях; для актива без провайдера — статус INSUFFICIENT, а не подстановка
BTC-метрик.
"""
import time
from mm_onchain_schema import event_is_confirmed, netflow_sums

def _now(): return int(time.time())

def _agent(name,status="OK",direction="NEUTRAL",confidence=0.0,evidence=None,
           source_ids=None,limitations=None,correlation_group="onchain"):
    """Контракт §4.2 — жёсткий набор полей, единый для всех OCIE-агентов."""
    return {"agent":name,"status":status,"direction":direction,
            "confidence":round(float(confidence),4),
            "evidence":evidence or [],"source_ids":source_ids or [],
            "observed_at":_now(),"age_sec":0,"limitations":limitations or [],
            "correlation_group":correlation_group}

# Горизонты влияния (§5): агент обязан помнить, на какой срок смотрит его сигнал
HORIZONS={"fast":"15m","mid":"1-4h","slow":"1-7d"}


def agent_btc_mempool(ctx):
    """1. BTC_MEMPOOL_AGENT: нагрузка mempool, комиссии, скорость подтверждения.
    Контекст, а не прямой сигнал цены: дорогие комиссии говорят о нагрузке,
    не о направлении (§4.2, горизонт mid)."""
    mp=(ctx or {}).get("mempool") or {}
    if not mp.get("ok"):
        return _agent("BTC_MEMPOOL_AGENT","INSUFFICIENT",
                      evidence=["mempool-данные недоступны (провайдер недоступен или timeout)"],
                      source_ids=["btc_mempool"])
    feat=[]
    fee=mp.get("fee_fast")
    tx=mp.get("mempool_tx")
    if fee is not None:
        feat.append("комиссия fast %d sat/vB"%fee)
        if fee>=100: feat.append("комиссии аномально высокие — сеть перегружена")
        elif fee<=10: feat.append("комиссии низкие — сеть свободна")
    if tx is not None:
        feat.append("mемпул: %d транзакций"%tx)
    return _agent("BTC_MEMPOOL_AGENT","OK","NEUTRAL",0.15,feat,
                  source_ids=["btc_mempool"],limitations=["mempool — контекст нагрузки, не ценовой сигнал"],
                  correlation_group="onchain_btc_load")


def agent_exchange_netflow(ctx,window_sec=3600):
    """2. EXCHANGE_NETFLOW_AGENT: подтверждённые потоки на биржи/с бирж.
    inflow/outflow раздельно, в единицах актива, за явный период (§6 UI)."""
    lt=(ctx or {}).get("large_transfers") or {}
    evm=(ctx or {}).get("evm_flows") or {}
    events=(lt.get("events") or [])+(evm.get("events") or [])
    if not events:
        note=(lt.get("note") or evm.get("note") or "")
        return _agent("EXCHANGE_NETFLOW_AGENT","INSUFFICIENT",
                      evidence=["нет подтверждённых биржевых потоков за окно %dс"%window_sec
                                +(" — "+note if note else "")],
                      source_ids=["btc_large_transfers","evm_flows"])
    sums=netflow_sums(events,window_sec=window_sec)
    inflow=sums.get("exchange_inflow",0.0)
    outflow=sums.get("exchange_outflow",0.0)
    ev=[]
    ev.append("inflow %.4f / outflow %.4f (подтверждённые, окно %dс, нв=%d, pending=%d)"%(
        inflow,outflow,window_sec,sums["n_confirmed"],sums["n_pending"]))
    # Интерпретация сознательно осторожная: netflow — контекст, не автосигнал (§4.2)
    direction="NEUTRAL";conf=0.15
    if inflow>2*max(outflow,1e-9) and inflow>0:
        ev.append("превышение депозитов над выводами — потенциальное давление предложения (контекст, не сигнал продажи)")
        direction="SHORT";conf=0.30
    elif outflow>2*max(inflow,1e-9) and outflow>0:
        ev.append("превышение выводов над депозитами — потенциальное накопление (контекст)")
        direction="LONG";conf=0.30
    return _agent("EXCHANGE_NETFLOW_AGENT","OK",direction,conf,ev,
                  source_ids=["btc_large_transfers","evm_flows"],
                  limitations=["поток в биржу ≠ обязательная продажа; метки адресов heuristic",
                               "горизонт %s"%HORIZONS["mid"]],
                  correlation_group="onchain_flows")


def agent_whale_transfer(ctx,threshold_btc=50.0):
    """3. WHALE_TRANSFER_AGENT: крупные перемещения с проверкой меток.
    Размер НЕ трактуется как сигнал автоматически (§4.2)."""
    lt=(ctx or {}).get("large_transfers") or {}
    events=lt.get("events") or []
    whales=[e for e in events if e.get("amount",0.0)>=threshold_btc]
    if not whales:
        return _agent("WHALE_TRANSFER_AGENT","INSUFFICIENT" if lt.get("status")=="UNAVAILABLE" else "OK",
                      evidence=["крупных переводов ≥%g BTC в окне не наблюдается"%threshold_btc
                                if lt.get("ok") else "лента крупных переводов недоступна"],
                      source_ids=["btc_large_transfers"])
    ev=[]
    for w in whales[:5]:
        lbl=w.get("label_to") or w.get("label_from")
        typ=w.get("event_type")
        ev.append("%.1f BTC %s%s%s"%(
            w.get("amount",0.0),typ,
            " → %s"%lbl if lbl else "",
            " (подтв. %s)"%w.get("confirmations") if w.get("confirmations") is not None else " (pending)"))
    ev.append("размер сам по себе не сигнал: перевод между холодными кошельками частое явление")
    return _agent("WHALE_TRANSFER_AGENT","OK","NEUTRAL",0.10,ev,
                  source_ids=["btc_large_transfers"],
                  limitations=["без контекста кластера перевод может быть внутренним перемещением",
                               "горизонт %s"%HORIZONS["mid"]],
                  correlation_group="onchain_flows")


def agent_dex_flow(ctx):
    """4. DEX_FLOW_AGENT: подтверждённые swap/объёмы/дисбаланс покупок-продаж.
    Требует DEX-данные (события пулов) — в текущих источниках их нет: честный
    INSUFFICIENT, никакой подмены (§4.2 «маршрутизация по сети»)."""
    return _agent("DEX_FLOW_AGENT","INSUFFICIENT",
                  evidence=["DEX-события (swap/pool) не собираются текущими адаптерами — "
                            "требуется индексатор с событиями контрактов"],
                  source_ids=[],
                  limitations=["горизонт %s"%HORIZONS["fast"]],
                  correlation_group="onchain_dex")


def agent_stablecoin_flow(ctx):
    """5. STABLECOIN_FLOW_AGENT: mint/burn стейблкоинов.
    НЕ приравнивает mint к немедленной покупке BTC (§4.2)."""
    # DefiLlama TVL — прокси-контекст; mint/burn события требуют EVM-индексатор
    dl=(ctx or {}).get("defi") or {}
    if dl.get("ok"):
        ev=["TVL DeFi: $%.1fB (контекст ликвидности)"%((dl.get("total_tvl") or 0.0)/1e9)]
        return _agent("STABLECOIN_FLOW_AGENT","OK","NEUTRAL",0.10,ev,
                      source_ids=["defillama"],
                      limitations=["mint/burn стейблкоинов требуют EVM-индексатора (события контрактов)",
                                   "mint ≠ немедленная покупка",
                                   "горизонт %s"%HORIZONS["slow"]],
                      correlation_group="onchain_stables")
    return _agent("STABLECOIN_FLOW_AGENT","INSUFFICIENT",
                  evidence=["DefiLlama недоступен; mint/burn события без индексатора"],
                  source_ids=["defillama"],
                  limitations=["горизонт %s"%HORIZONS["slow"]],
                  correlation_group="onchain_stables")


def agent_token_unlock(ctx):
    """6. TOKEN_UNLOCK_AGENT: календарь unlock/vesting. Требует источник
    календаря (tokenunlocks.app и аналоги — платные/скрейпинг недопустим).
    Честный INSUFFICIENT без данных (§7: не обходить условия использования)."""
    return _agent("TOKEN_UNLOCK_AGENT","INSUFFICIENT",
                  evidence=["календарь unlock требует лицензированного провайдера — "
                            "скрейпинг violates ToS, публичного API нет"],
                  source_ids=[],
                  limitations=["горизонт %s"%HORIZONS["slow"]],
                  correlation_group="onchain_supply")


def agent_bridge_flow(ctx):
    """7. BRIDGE_FLOW_AGENT: потоки через мосты. Без двойного учёта
    (bridge_out + mint на другой стороне = один факт, §4.4)."""
    return _agent("BRIDGE_FLOW_AGENT","INSUFFICIENT",
                  evidence=["bridge-события требуют индексатора контрактов обеих сетей; "
                            "риск двойного учёта high — без данных не интерпретируем"],
                  source_ids=[],
                  limitations=["горизонт %s"%HORIZONS["slow"]],
                  correlation_group="onchain_bridges")


def agent_onchain_activity(ctx):
    """8. ONCHAIN_ACTIVITY_AGENT: активные адреса/транзакции/комиссии сети —
    ТОЛЬКО контекст, не прямой ценовой сигнал (§4.2)."""
    bl=(ctx or {}).get("blocks") or {}
    mp=(ctx or {}).get("mempool") or {}
    if not (bl.get("ok") or mp.get("ok")):
        return _agent("ONCHAIN_ACTIVITY_AGENT","INSUFFICIENT",
                      evidence=["данные активности сети недоступны"],
                      source_ids=["btc_blocks","btc_mempool"])
    ev=[]
    if bl.get("ok"):
        bs=bl.get("blocks") or []
        if bs:
            ev.append("последний блок %s: %d tx"%(bs[0].get("height"),bs[0].get("tx_count")))
        if bl.get("median_block_sec"):
            ev.append("медианный интервал блоков %.0fс"%bl["median_block_sec"])
    if mp.get("ok") and mp.get("fee_fast") is not None:
        ev.append("комиссия fast %d sat/vB"%mp["fee_fast"])
    ev.append("активность сети — контекст; корреляция с ценой не доказана")
    return _agent("ONCHAIN_ACTIVITY_AGENT","OK","NEUTRAL",0.10,ev,
                  source_ids=["btc_blocks","btc_mempool"],
                  limitations=["только контекст, не сигнал цены",
                               "горизонт %s"%HORIZONS["slow"]],
                  correlation_group="onchain_btc_load")


def agent_onchain_anomaly(ctx,history=None,min_history=20):
    """9. ONCHAIN_ANOMALY_AGENT: аномалии против исторической базы.
    robust z-score (median/MAD); без min_history — не аномалия (§4.4)."""
    mp=(ctx or {}).get("mempool") or {}
    if not mp.get("ok") or mp.get("fee_fast") is None:
        return _agent("ONCHAIN_ANOMALY_AGENT","INSUFFICIENT",
                      evidence=["нет текущих данных для сравнения"],
                      source_ids=["btc_mempool"])
    fee=float(mp.get("fee_fast"))
    hist=history or []
    if len(hist)<min_history:
        return _agent("ONCHAIN_ANOMALY_AGENT","INSUFFICIENT",
                      evidence=["истории меньше %d точек — baseline не строится (анти-ложная-аномалия)"%min_history],
                      source_ids=["btc_mempool"],
                      limitations=["robust z-score требует истории"],
                      correlation_group="onchain_btc_load")
    srt=sorted(hist)
    n=len(srt)
    med=srt[n//2]
    mad=sorted(abs(x-med) for x in hist)[n//2] or 1e-9
    z=0.6745*(fee-med)/mad
    ev=["fee fast %g sat/vB, robust z=%.2f (median %g, MAD %g, n=%d)"%(fee,z,med,mad,n)]
    if abs(z)>=3.5:
        ev.append("аномалия комиссий относительно baseline — наблюдаем, не трактуем автоматически")
        return _agent("ONCHAIN_ANOMALY_AGENT","OK","NEUTRAL",0.25,ev,
                      source_ids=["btc_mempool"],
                      limitations=["аномалия ≠ сигнал; требуется подтверждение потоками"],
                      correlation_group="onchain_btc_load")
    ev.append("комиссии в пределах исторической нормы")
    return _agent("ONCHAIN_ANOMALY_AGENT","OK","NEUTRAL",0.10,ev,
                  source_ids=["btc_mempool"],
                  correlation_group="onchain_btc_load")


def agent_onchain_data_quality(ctx,statuses=None,min_ratio=0.5):
    """10. ONCHAIN_DATA_QUALITY_AGENT: свежесть, полнота, покрытие, лимиты."""
    st=statuses or {}
    if not st:
        return _agent("ONCHAIN_DATA_QUALITY_AGENT","INSUFFICIENT",
                      evidence=["статусы источников не переданы"])
    total=len(st);avail=sum(1 for v in st.values() if v=="AVAILABLE")
    ev=["источников AVAILABLE %d/%d: %s"%(avail,total,
        ", ".join("%s=%s"%(k,v) for k,v in st.items()))]
    ratio=avail/float(total) if total else 0.0
    status="OK" if ratio>=min_ratio else "INSUFFICIENT"
    if ratio<1.0:
        ev.append("часть ончейн-источников недоступна — контур деградирован, это учитывается в совете")
    return _agent("ONCHAIN_DATA_QUALITY_AGENT",status,"NEUTRAL",
                  round(ratio,3),ev,source_ids=list(st.keys()),
                  limitations=["качество проксируется долей живых источников"],
                  correlation_group="onchain_quality")


AGENTS=("BTC_MEMPOOL_AGENT","EXCHANGE_NETFLOW_AGENT","WHALE_TRANSFER_AGENT",
        "DEX_FLOW_AGENT","STABLECOIN_FLOW_AGENT","TOKEN_UNLOCK_AGENT",
        "BRIDGE_FLOW_AGENT","ONCHAIN_ACTIVITY_AGENT","ONCHAIN_ANOMALY_AGENT",
        "ONCHAIN_DATA_QUALITY_AGENT")


def run_all(ctx,statuses=None,fee_history=None):
    """Запуск всех агентов OCIE по контексту. Возвращает список контрактов §4.2."""
    return [agent_btc_mempool(ctx),
            agent_exchange_netflow(ctx),
            agent_whale_transfer(ctx),
            agent_dex_flow(ctx),
            agent_stablecoin_flow(ctx),
            agent_token_unlock(ctx),
            agent_bridge_flow(ctx),
            agent_onchain_activity(ctx),
            agent_onchain_anomaly(ctx,history=fee_history),
            agent_onchain_data_quality(ctx,statuses=statuses)]


def selftest():
    # пустой контекст -> INSUFFICIENT везде, никаких выдуманных значений
    agents=run_all({},statuses={})
    assert len(agents)==10
    by={a["agent"]:a for a in agents}
    assert by["BTC_MEMPOOL_AGENT"]["status"]=="INSUFFICIENT"
    assert by["DEX_FLOW_AGENT"]["status"]=="INSUFFICIENT"
    assert by["TOKEN_UNLOCK_AGENT"]["status"]=="INSUFFICIENT"
    # контракт полон у каждого агента
    for a in agents:
        for k in ("agent","status","direction","confidence","evidence","source_ids",
                  "observed_at","age_sec","limitations","correlation_group"):
            assert k in a,(a["agent"],k)
    # mempool-контекст: агент активен, но направление NEUTRAL (не сигнал)
    ctx={"mempool":{"ok":True,"fee_fast":55,"mempool_tx":120000,"status":"AVAILABLE"},
         "blocks":{"ok":True,"status":"AVAILABLE","blocks":[{"height":800000,"tx_count":2800,"ts":1700000000}],"median_block_sec":590},
         "defi":{"ok":True,"status":"AVAILABLE","total_tvl":1.7e11},
         "large_transfers":{"ok":True,"status":"AVAILABLE","events":[]}}
    agents=run_all(ctx,statuses={"btc_mempool":"AVAILABLE","btc_blocks":"AVAILABLE",
                                 "defillama":"AVAILABLE","btc_large_transfers":"AVAILABLE"})
    by={a["agent"]:a for a in agents}
    assert by["BTC_MEMPOOL_AGENT"]["status"]=="OK"
    assert by["BTC_MEMPOOL_AGENT"]["direction"]=="NEUTRAL"
    assert by["ONCHAIN_DATA_QUALITY_AGENT"]["confidence"]==1.0
    # аномалия без истории не детектируется
    assert by["ONCHAIN_ANOMALY_AGENT"]["status"]=="INSUFFICIENT"
    # аномалия с историей
    agents=run_all(ctx,statuses={},fee_history=[10.0]*30)
    an=[a for a in agents if a["agent"]=="ONCHAIN_ANOMALY_AGENT"][0]
    assert an["status"]=="OK" and "robust z" in an["evidence"][0]
    print("mm_onchain_agents: OK")
