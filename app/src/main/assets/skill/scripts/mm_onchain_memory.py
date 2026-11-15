"""OCIE v8.1 — mm_onchain_memory: история ончейн-прогнозов и проверка (ТЗ §4.3).

Отдельная память второго контура: события (ограниченный буфер), выводы
контура и (в будущем) сверка с фактом. Не вмешивается в память Market Engine
(mm_memory) — только читаемая история для UI ON-CHAIN и аудита.
"""
import csv,json,os,time

_PATH_EVENTS="onchain_events.csv"
_PATH_VIEWS="onchain_views.csv"
MAX_EVENTS=2000

def _dir():
    import mm_config
    return mm_config.data_dir()

def _f(name): return os.path.join(_dir(),name)

def record_events(events,asset="BTC"):
    """Сохранить ончейн-события (после дедупа) для UI/аудита. Буфер ограничен."""
    if not events: return 0
    new_file=not os.path.exists(_f(_PATH_EVENTS))
    n=0
    try:
        with open(_f(_PATH_EVENTS),"a",encoding="utf-8",newline="") as f:
            w=csv.writer(f)
            if new_file:
                w.writerow(["ts","asset","chain_id","tx_hash","event_type","amount",
                            "confirmations","label","label_confidence","provider"])
            for ev in events[-200:]:
                w.writerow([int(time.time()),asset,ev.get("chain_id"),ev.get("tx_hash"),
                            ev.get("event_type"),ev.get("amount"),ev.get("confirmations"),
                            ev.get("label_to") or ev.get("label_from") or "",
                            ev.get("label_confidence"),ev.get("provider")])
                n+=1
    except Exception:
        pass
    _trim(_PATH_EVENTS,MAX_EVENTS)
    return n

def record_view(summary):
    """Сохранить вывод контура OCIE (state/direction/confidence/quality)."""
    new_file=not os.path.exists(_f(_PATH_VIEWS))
    try:
        with open(_f(_PATH_VIEWS),"a",encoding="utf-8",newline="") as f:
            w=csv.writer(f)
            if new_file:
                w.writerow(["ts","asset","state","direction","confidence","quality","note"])
            w.writerow([int(time.time()),summary.get("asset"),
                        summary.get("state"),summary.get("direction"),
                        summary.get("confidence"),summary.get("quality"),
                        (summary.get("note") or "")[:200]])
    except Exception:
        pass
    _trim(_PATH_VIEWS,1000)

def recent_events(n=50,asset=None):
    """Последние события для UI ON-CHAIN (§6): сеть, сумма, время, тип, метка."""
    path=_f(_PATH_EVENTS)
    if not os.path.exists(path): return []
    out=[]
    try:
        with open(path,encoding="utf-8") as f:
            rows=list(csv.reader(f))
        hdr=rows[0] if rows and rows[0] and rows[0][0]=="ts" else None
        data=rows[1:] if hdr else rows
        for r in reversed(data):
            if len(r)<10: continue
            if asset and r[1]!=asset: continue
            out.append({"ts":int(r[0]),"asset":r[1],"chain_id":r[2],"tx_hash":r[3],
                        "event_type":r[4],"amount":float(r[5] or 0),
                        "confirmations":r[6] or None,"label":r[7],
                        "label_confidence":r[8],"provider":r[9]})
            if len(out)>=n: break
    except Exception:
        pass
    return out

def recent_views(n=30,asset=None):
    path=_f(_PATH_VIEWS)
    if not os.path.exists(path): return []
    out=[]
    try:
        with open(path,encoding="utf-8") as f:
            rows=list(csv.reader(f))
        data=rows[1:] if rows and rows[0] and rows[0][0]=="ts" else rows
        for r in reversed(data):
            if len(r)<7: continue
            if asset and r[1]!=asset: continue
            out.append({"ts":int(r[0]),"asset":r[1],"state":r[2],"direction":r[3],
                        "confidence":float(r[4] or 0),"quality":float(r[5] or 0),
                        "note":r[6]})
            if len(out)>=n: break
    except Exception:
        pass
    return out

def _trim(name,max_rows):
    path=_f(name)
    try:
        if not os.path.exists(path): return
        with open(path,encoding="utf-8") as f:
            rows=list(csv.reader(f))
        if len(rows)<=max_rows: return
        hdr=rows[0] if rows[0] and rows[0][0]=="ts" else None
        keep=rows[-max_rows:]
        with open(path,"w",encoding="utf-8",newline="") as f:
            w=csv.writer(f)
            if hdr: w.writerow(hdr)
            w.writerows(keep)
    except Exception:
        pass


def selftest():
    import tempfile
    d=tempfile.mkdtemp();os.environ["MM5_DATA_DIR"]=d
    try:
        from mm_onchain_schema import new_event
        evs=[new_event("bitcoin","tx1",0,"exchange_inflow","BTC",75.0,8,
                       block_ts=int(time.time())-60,confirmations=2,
                       label_to="Binance",label_confidence="verified",provider="t"),
             new_event("bitcoin","tx2",0,"transfer","BTC",52.0,8,
                       block_ts=int(time.time())-30,confirmations=1,provider="t")]
        n=record_events(evs,"BTC");assert n==2
        got=recent_events(10)
        assert len(got)==2 and got[0]["tx_hash"]=="tx2"  # последние сверху
        assert got[1]["event_type"]=="exchange_inflow"
        record_view({"asset":"BTC","state":"CONFIRMED","direction":"SHORT",
                     "confidence":0.3,"quality":1.0,"note":"inflow dominates"})
        views=recent_views(5)
        assert len(views)==1 and views[0]["state"]=="CONFIRMED"
        # фильтр по активу
        assert recent_events(10,asset="ETH")==[]
    finally:
        del os.environ["MM5_DATA_DIR"]
    print("mm_onchain_memory: OK")
