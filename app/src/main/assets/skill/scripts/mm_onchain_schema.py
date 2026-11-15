"""OCIE v8.1 — ON-CHAIN INTELLIGENCE ENGINE (ТЗ §4, Этап E).

mm_onchain_schema: единый контракт событий и метрик OCIE.
Никаких произвольных dict-ов между модулями — только события Event и
метрики Metric с явными полями, происхождением и подтверждениями.

Принципы (ТЗ §4.4):
- timestamps в UTC; храним и время блока, и время получения;
- подтверждения блока обязательны: pending != confirmed;
- дедупликация по (chain_id, tx_hash, log_index);
- перевод между кошельками НЕ считается покупкой/продажей.
"""
import time

# Типы ончейн-событий (§4.1): различаем природу, не выдумываем направление сделки
EVENT_TYPES=(
    "transfer",            # перевод между кошельками (НЕ сигнал покупки/продажи)
    "exchange_inflow",     # депозит на биржевой адрес (метка доказана провайдером)
    "exchange_outflow",    # вывод с биржевого адреса
    "cluster_internal",    # перевод внутри кластера одного владельца (если доказан)
    "dex_swap",            # swap, подтверждённый событиями/данными DEX
    "mint",                # выпуск токена
    "burn",                # сжигание токена
    "bridge_out",          # вывод через мост (одна сторона)
    "bridge_in",           # ввод через мост (другая сторона; не складываем с out)
    "staking",             # стейкинг
    "unlock",              # разблокировка/vesting
    "liquidation",         # ончейн-ликвидация (DeFi)
)

# Уровни уверенности меток адресов (§4.1: метка без доказательства — не метка)
LABEL_CONFIDENCE=(
    "verified",    # провайдер явно связал адрес с биржей (docs/heuristics published)
    "heuristic",   # кластеризация провайдера, публично описанная
    "unverified",  # слухи/форумы — НЕ используется для exchange_inflow/outflow
)

# Валидные статусы источника OCIE (совместимо с mm_data.source_status)
SOURCE_STATUSES=("AVAILABLE","STALE","INSUFFICIENT","UNAVAILABLE")


def new_event(chain_id,tx_hash,log_index,event_type,asset,amount,asset_decimals=18,
              from_addr=None,to_addr=None,block_ts=None,block_height=None,
              confirmations=None,label_from=None,label_to=None,
              label_confidence="unverified",provider=None,provider_event_id=None,
              received_at=None,dex_pool=None,counter_asset=None,counter_amount=None):
    """Каноническое ончейн-событие (§4.4: дедуп по chain_id+tx_hash+log_index).

    Обязательные поля для аудита: provider, block_ts, confirmations.
    exchange_inflow/outflow допускаются ТОЛЬКО при label_confidence in
    ("verified","heuristic") — иначе событие остаётся transfer.
    """
    if event_type not in EVENT_TYPES:
        raise ValueError("unknown event_type: %s"%event_type)
    if label_confidence not in LABEL_CONFIDENCE:
        raise ValueError("unknown label_confidence: %s"%label_confidence)
    # защита от ложных сигналов: биржевые потоки только с доказанной меткой
    if event_type in ("exchange_inflow","exchange_outflow") and label_confidence not in ("verified","heuristic"):
        event_type="transfer"
    return {
        "chain_id":str(chain_id),
        "tx_hash":str(tx_hash or ""),
        "log_index":int(log_index or 0),
        "event_id":"oc_%s_%s_%d"%(chain_id,str(tx_hash or "x")[:16],int(log_index or 0)),
        "event_type":event_type,
        "asset":str(asset or ""),
        "amount":float(amount or 0.0),
        "asset_decimals":int(asset_decimals or 18),
        "from_addr":from_addr,
        "to_addr":to_addr,
        "block_ts":block_ts,               # время блока (UTC, сек) — может быть None (pending)
        "block_height":block_height,
        "confirmations":confirmations,      # None -> pending/неизвестно
        "label_from":label_from,            # метка адреса-отправителя (провайдер)
        "label_to":label_to,
        "label_confidence":label_confidence,
        "provider":provider,
        "provider_event_id":provider_event_id,
        "received_at":received_at or int(time.time()),
        "dex_pool":dex_pool,
        "counter_asset":counter_asset,
        "counter_amount":counter_amount,
    }


def event_is_confirmed(ev,min_confirmations=1):
    """Событие подтверждено? pending-транзакция не равна подтверждённой (§4.4)."""
    c=ev.get("confirmations")
    ts=ev.get("block_ts")
    return (c is not None and int(c)>=min_confirmations
            and ts is not None and int(ts)>0)


def dedup_key(ev):
    """Дедупликация: chain_id + tx_hash + log_index (§4.4)."""
    return (ev.get("chain_id"),ev.get("tx_hash"),ev.get("log_index"))


def dedup_events(events):
    """Убирает дубли; один факт из двух провайдеров не удваивает вес (§8 OCIE).
    Возвращает (уникальные события, n_dropped). Провайдер-первоисточник
    сохраняется (первый пришёл), остальные фиксируются как подтверждения."""
    seen={}
    out=[];dropped=0
    for ev in events:
        k=dedup_key(ev)
        if k in seen:
            seen[k].setdefault("also_reported_by",[]).append(ev.get("provider"))
            dropped+=1
        else:
            out.append(ev)
            seen[k]=ev
    return out,dropped


def netflow_sums(events,window_sec=3600,now=None):
    """Суммы потоков по типам за окно. НЕ интерпретирует направление цены (§4.1):
    возвращает только наблюдаемые величины с единицами."""
    now=now or int(time.time())
    sums={"exchange_inflow":0.0,"exchange_outflow":0.0,"transfer":0.0,
          "dex_swap":0.0,"mint":0.0,"burn":0.0,"bridge_out":0.0,"bridge_in":0.0,
          "n_confirmed":0,"n_pending":0}
    for ev in events:
        if event_is_confirmed(ev):
            sums["n_confirmed"]+=1
            if ev.get("block_ts") and now-int(ev["block_ts"])<=window_sec:
                t=ev["event_type"]
                if t in sums: sums[t]+=float(ev.get("amount") or 0.0)
        else:
            sums["n_pending"]+=1
    return sums


def selftest():
    ev=new_event("bitcoin","abc123",0,"exchange_inflow","BTC",120.5,8,
                 to_addr="1Binance...",label_to="Binance-cold",
                 label_confidence="verified",provider="mempool.space",
                 block_ts=1700000000,confirmations=3)
    assert ev["event_type"]=="exchange_inflow"
    # биржевая метка без доказательства -> деградация до transfer (§4.1)
    bad=new_event("ethereum","0xdef",1,"exchange_inflow","ETH",900.0,18,
                  to_addr="0xunknown",label_to="Binance?",
                  label_confidence="unverified",provider="test")
    assert bad["event_type"]=="transfer","unverified label must degrade to transfer"
    # pending без подтверждений не считается подтверждённым
    pend=dict(ev);pend["confirmations"]=None
    assert not event_is_confirmed(pend)
    assert event_is_confirmed(ev)
    # дедуп: один факт из двух провайдеров не дублируется
    dup=dict(ev);dup["provider"]="other-provider"
    uniq,dropped=dedup_events([ev,dup])
    assert len(uniq)==1 and dropped==1
    assert uniq[0]["also_reported_by"]==["other-provider"]
    # нетвлоу только по подтверждённым в окне
    sums=netflow_sums([ev,pend],window_sec=int(time.time())-1699990000)
    assert sums["exchange_inflow"]==120.5 and sums["n_pending"]==1
    # валидация контракта
    try:
        new_event("x","y",0,"unknown_type","Z",1.0); assert False,"must raise"
    except ValueError: pass
    print("mm_onchain_schema: OK")
