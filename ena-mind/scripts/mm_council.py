#!/usr/bin/env python3
"""Совет (Cross-Validation + Feedback) — ядро ENA-MIND. Логика v5.0 сохранена.

Отличия от v4:
- 4 модуля (ta/flow/deriv/sentiment) вместо 3 — sentiment как отдельный голос толпы;
- приоры аналитиков берутся из mm_config.prior_for(module, regime) — «психология фазы»;
- если в журнале >= p_hat_min_n compliant-сделок, приоры дополнительно корректируются
  через p_hat (фактическая winrate) — это и есть обратная связь, замыкающая контур;
- конфликт long/short при обеих conf>=conflict_min_conf -> REVISIT слабейшего (conservative);
  после пересмотра остаточный конфликт -> NO TRADE (CONFLICT);
- мягкий конфликт: слабая сторона отбрасывается с пометкой в audit;
- обязательный модуль INSUFFICIENT -> HALT (право остановить анализ);
- p_agg взвешенная по confidence, в [p_floor, p_cap].
"""
import json,os,sys
import mm_analyst,mm_schema,mm_config,mm_regime,mm_journal
CFG_DEFAULT=mm_config.DEFAULTS
def _audit(a,stage,check,result): a.append({"stage":stage,"check":check,"result":result})
def _halt(missing,audit):
    return {"status":"HALT","side":"flat","p":0.5,"confidence":0.0,"conflicts":[],
            "audit":audit,"modules":{},"regime":"unknown","p_hat_from_feedback":None,
            "note":"STOP: INSUFFICIENT у обязательных модулей: %s"%", ".join(missing)}
def _p_hat_from_journal(cfg):
    """Если в журнале >= p_hat_min_n compliant-сделок — вернуть средний observed hit-rate
    по buckets p, иначе None. Это обратная связь: prior <- prior * (obs/expected)."""
    if not cfg.get("use_feedback_prior"): return None
    try:
        stats=mm_journal.stats()
        comp=stats.get("EDGE_compliant_only",{})
        n=comp.get("n",0)
        if n<cfg.get("p_hat_min_n",20): return None
        cal=stats.get("calibration",{})
        if not cal: return None
        # взвешенный observed по buckets
        tot_n=0;obs_sum=0.0
        for b,v in cal.items():
            tot_n+=v["n"];obs_sum+=v["observed"]*v["n"]
        if tot_n==0: return None
        return round(obs_sum/tot_n,4)
    except Exception:
        return None
def _adjust_prior(module,p,regime,p_hat,cfg):
    """Если p_hat из журнала есть, аккуратно подвинуть p в его сторону.
    Без фанатизма: корректировка <= 0.05, чтобы один плохой период не убил систему."""
    if p_hat is None: return p
    base=mm_config.prior_for(module,regime,cfg)
    # если p у модуля выше базы, а p_hat ниже базы — слегка опустить
    shift=_clamp_signed((p_hat-base)*0.3,-0.05,0.05)
    return round(_clamp_num(p+shift,0.5,0.8),4)
def _clamp_num(x,a,b): return max(a,min(b,x))
def _clamp_signed(x,a,b): return max(a,min(b,x))
def decide(signals,cfg=None,recheck=False,regime=None,p_hat=None):
    """Чистая функция. signals: {module: ANALYST-вердикт}. Может вернуть REVISIT.
    recheck=True — повторный проход после REVISIT: голосуют все ok-модули с ненейтральной
    стороной (без порога min_conf)."""
    c=dict(CFG_DEFAULT);c.update(cfg or {});audit=[]
    _audit(audit,"regime","фаза рынка",regime or "unknown")
    _audit(audit,"feedback","p_hat из журнала",p_hat)
    _audit(audit,"data","обязательные модули",list(signals))
    missing=[m for m in mm_schema.MODULES_REQUIRED if m not in signals]
    if missing: return _halt(missing,audit)
    for m in mm_schema.MODULES_REQUIRED:
        _audit(audit,"data","%s.ok"%m,signals[m]["ok"])
        if not signals[m]["ok"]:
            return _halt([m+" (%s)"%(signals[m].get("note") or "no data")],audit)
    # feedback-корректировка p у каждого модуля
    if p_hat is not None:
        for m,s in signals.items():
            s=dict(s)
            if s["side"]!="flat" and s.get("ok"):
                s["p"]=_adjust_prior(m,s["p"],regime,p_hat,c)
                s["note"]=(s.get("note","")+" [feedback p_hat=%.3f]"%p_hat).strip()
            signals[m]=s
    thr=0.0 if recheck else float(c["min_conf"])
    voters={m:s for m,s in signals.items() if s["side"]!="flat" and s["confidence"]>=thr}
    _audit(audit,"analysis","голосуют модули conf>=%.2f"%float(c["min_conf"]),list(voters))
    longs={m:s for m,s in voters.items() if s["side"]=="long"}
    shorts={m:s for m,s in voters.items() if s["side"]=="short"}
    conflicts=[]
    for ml,sl in longs.items():
        for ms,ss in shorts.items():
            conflicts.append({"a":ml,"b":ms,"a_side":"long","b_side":"short",
                              "a_conf":sl["confidence"],"b_conf":ss["confidence"]})
    _audit(audit,"cross-validation","конфликтов long/short",len(conflicts))
    if conflicts:
        if recheck:
            _audit(audit,"cross-validation","пересмотр не снял конфликт","NO TRADE")
            return {"status":"NO_TRADE","side":"flat","p":0.5,"confidence":0.0,"conflicts":conflicts,
                    "audit":audit,"modules":signals,"regime":regime or "unknown",
                    "p_hat_from_feedback":p_hat,
                    "note":"CONFLICT: после пересмотра модули по-прежнему противоречат — NO TRADE"}
        hard=[k for k in conflicts if k["a_conf"]>=float(c["conflict_min_conf"]) and k["b_conf"]>=float(c["conflict_min_conf"])]
        _audit(audit,"cross-validation","жёстких конфликтов (обе conf>=%.2f)"%float(c["conflict_min_conf"]),len(hard))
        if hard:
            cand=[(s["confidence"],m) for m,s in voters.items()]
            weakest=min(cand)[1]
            _audit(audit,"cross-validation","слабейший модуль",weakest)
            return {"status":"REVISIT","side":"flat","p":0.5,"confidence":0.0,"conflicts":conflicts,
                    "audit":audit,"modules":signals,"regime":regime or "unknown","p_hat_from_feedback":p_hat,
                    "note":"возврат к Analysis: пересмотр %s в conservative-режиме"%weakest,
                    "revisit":{"modules":[weakest],"mode":"conservative"}}
        # мягкий конфликт: слабая сторона отбрасывается
        wsum_long=sum(s["confidence"] for s in longs.values());wsum_short=sum(s["confidence"] for s in shorts.values())
        drop="short" if wsum_long>=wsum_short else "long"
        _audit(audit,"cross-validation","мягкий конфликт: сторона отброшена",drop)
        voters={m:s for m,s in voters.items() if s["side"]!=drop}
        longs={m:s for m,s in voters.items() if s["side"]=="long"}
        shorts={m:s for m,s in voters.items() if s["side"]=="short"}
    if not longs and not shorts:
        _audit(audit,"decision","перевеса нет","NO TRADE")
        return {"status":"NO_TRADE","side":"flat","p":0.5,"confidence":0.0,"conflicts":conflicts,
                "audit":audit,"modules":signals,"regime":regime or "unknown","p_hat_from_feedback":p_hat,
                "note":"нет перевеса: все модули flat или ниже порога conf"}
    side="long" if longs else "short";grp=longs if longs else shorts
    w={m:s["confidence"] for m,s in grp.items()};W=sum(w.values())
    p=sum(s["p"]*w[m] for m,s in grp.items())/W
    p=round(max(float(c["p_floor"]),min(float(c["p_cap"]),p)),4)
    conf=round(sum(s["confidence"]*w[m] for m,s in grp.items())/W,4)
    ev=[e for s in grp.values() for e in s.get("evidence",[])]
    _audit(audit,"decision","сторона %s, p=%.3f, conf=%.2f"%(side,p,conf),list(grp))
    return {"status":"AGREE","side":side,"p":p,"confidence":conf,"conflicts":conflicts,
            "audit":audit,"modules":signals,"regime":regime or "unknown","p_hat_from_feedback":p_hat,
            "note":"модули согласны: %s"%", ".join(grp)}
def resolve(snap=None,klines=None,cfg=None,max_revisits=None,regime=None,funding_history=None,p_hat=None):
    """Полный цикл: Regime -> Analysis -> Cross-Validation -> (REVISIT)* -> Decision.
    regime: если None, классифицируется по klines. p_hat: если None, тянется из журнала."""
    c=dict(CFG_DEFAULT);c.update(cfg or {})
    mr=max_revisits if max_revisits is not None else int(c["max_revisits"])
    if regime is None and klines:
        r=mm_regime.classify(klines)
        regime=r["kind"] if r["kind"]!="unknown" else "unknown"
    if p_hat is None:
        p_hat=_p_hat_from_journal(c)
    signals=mm_analyst.analyze_all(snap,klines,regime=regime,cfg=c,funding_history=funding_history)
    seen=[];d=decide(signals,c,regime=regime,p_hat=p_hat)
    while d["status"]=="REVISIT":
        mods=tuple(d["revisit"]["modules"])
        if mods in seen or len(seen)>=mr:
            d["status"]="NO_TRADE"
            d["note"]="CONFLICT: после %d пересмотра(-ов) конфликт не разрешён — NO TRADE"%len(seen)
            d["audit"].append({"stage":"decision","check":"финальный конфликт","result":"NO TRADE"})
            return d
        seen.append(mods)
        signals=mm_analyst.analyze_all(snap,klines,conservative=set(mods),regime=regime,cfg=c,funding_history=funding_history)
        d=decide(signals,c,recheck=True,regime=regime,p_hat=p_hat)
    if d["status"]=="AGREE" and d.get("conflicts"):
        d["note"]+=" | противоречие зафиксировано после пересмотра"
    return d
def explain(d):
    L=["СОВЕТ: %s — %s"%(d["status"],d["note"])]
    L.append("  регис: %s | p_hat=%s"%(d.get("regime"),d.get("p_hat_from_feedback")))
    for a in d.get("audit",[]):
        r=a["result"];r=json.dumps(r,ensure_ascii=False) if isinstance(r,(list,dict)) else str(r)
        L.append("  [%s] %s -> %s"%(a["stage"],a["check"],r[:200]))
    if d.get("conflicts"):
        L.append("Конфликты:")
        for k in d["conflicts"]: L.append("  %s(long,conf=%.2f) vs %s(short,conf=%.2f)"%(k["a"],k["a_conf"],k["b"],k["b_conf"]))
    return "\n".join(L)
def selftest():
    up=[[i,100+i*0.1,100.5+i*0.1,99.5+i*0.1,100+i*0.1,1] for i in range(210)]
    up[-1]=[209,120.9,122.5,120.8,122.0,1];up[-2]=[208,120.8,121.0,119.0,120.9,1]
    snap_agree={"parts":{"tape":{"ok":True,"cvd":5.0,"delta_pct":4.0,"price_change_pct":0.4,"divergence":False,"n":500,"age_sec":2.0},
                         "book":{"ok":True,"imbalance_pct":10.0},
                         "deriv":{"ok":True,"funding_pct":0.01,"oi":68000.0},
                         "fng":{"ok":True,"value":50,"delta3":0,"delta7":0,"classification":"Neutral"},
                         "lsr":{"ok":True,"ratio":1.0,"long_pct":50.0,"short_pct":50.0}}}
    # 1) согласие ta+flow -> AGREE long (regime trend_up)
    d=resolve(snap_agree,up,regime="trend_up")
    assert d["status"]=="AGREE" and d["side"]=="long" and d["p"]>0.5,(d["status"],d["note"])
    assert d["regime"]=="trend_up"
    # 2) конфликт ta LONG vs flow SHORT (оба сильные) -> NO TRADE (CONFLICT)
    snap_war={"parts":{"tape":{"ok":True,"cvd":-5.0,"delta_pct":-4.0,"price_change_pct":-0.4,"divergence":False,"n":500,"age_sec":2.0},
                       "book":{"ok":False},"deriv":{"ok":True,"funding_pct":0.01,"oi":68000.0},
                       "fng":{"ok":True,"value":50,"delta3":0,"delta7":0,"classification":"Neutral"},
                       "lsr":{"ok":True,"ratio":1.0,"long_pct":50.0,"short_pct":50.0}}}
    d=resolve(snap_war,up,regime="trend_up")
    assert d["status"]=="NO_TRADE" and "CONFLICT" in d["note"],(d["status"],d["note"])
    # 3) HALT: обязательный модуль INSUFFICIENT (flow)
    d=resolve({"parts":{"tape":{"ok":False},"deriv":{"ok":True,"funding_pct":0.01},
                        "fng":{"ok":False},"lsr":{"ok":False}}},up,regime="trend_up")
    assert d["status"]=="HALT",(d["status"],d["note"])
    # 4) HALT: sentiment INSUFFICIENT (главное в v5 — теперь обязательный)
    d=resolve({"parts":{"tape":{"ok":True,"cvd":5.0,"delta_pct":4.0,"price_change_pct":0.4,"divergence":False,"n":500,"age_sec":2.0},
                        "deriv":{"ok":True,"funding_pct":0.01,"oi":68000.0},
                        "fng":{"ok":False},"lsr":{"ok":False}}},up,regime="trend_up")
    assert d["status"]=="HALT",(d["status"],d["note"])
    # 5) deriv экстремум против ta (funding>=0.05) -> жёсткий конфликт -> NO TRADE
    snap_fund={"parts":{"tape":{"ok":True,"cvd":5.0,"delta_pct":4.0,"price_change_pct":0.4,"divergence":False,"n":500,"age_sec":2.0},
                        "book":{"ok":False},"deriv":{"ok":True,"funding_pct":0.09,"oi":68000.0},
                        "fng":{"ok":True,"value":50,"delta3":0,"delta7":0,"classification":"Neutral"},
                        "lsr":{"ok":True,"ratio":1.0,"long_pct":50.0,"short_pct":50.0}}}
    d=resolve(snap_fund,up,regime="trend_up",max_revisits=0)
    assert d["status"]=="NO_TRADE",d["status"]
    # 6) мягкий конфликт: flow conf < conflict_min -> отброшен, решает ta
    snap_soft={"parts":{"tape":{"ok":True,"cvd":-0.2,"delta_pct":-1.9,"price_change_pct":0.01,"divergence":False,"n":10,"age_sec":2.0},
                        "book":{"ok":False},"deriv":{"ok":True,"funding_pct":0.01,"oi":68000.0},
                        "fng":{"ok":True,"value":50,"delta3":0,"delta7":0,"classification":"Neutral"},
                        "lsr":{"ok":True,"ratio":1.0,"long_pct":50.0,"short_pct":50.0}}}
    d=resolve(snap_soft,up,regime="trend_up")
    assert d["status"]=="AGREE" and d["side"]=="long",(d["status"],d["note"])
    # 7) sentiment корректирует сторону в range (толпа перегрета Greed -> short, deriv нейтрален)
    snap_greed={"parts":{"tape":{"ok":True,"cvd":0.2,"delta_pct":0.5,"price_change_pct":0.1,"divergence":False,"n":500,"age_sec":2.0},
                         "book":{"ok":False},"deriv":{"ok":True,"funding_pct":0.01,"oi":68000.0},
                         "fng":{"ok":True,"value":82,"delta3":10,"delta7":15,"classification":"Extreme Greed"},
                         "lsr":{"ok":True,"ratio":1.6,"long_pct":61.5,"short_pct":38.5}}}
    flat_kl=[[i,100,100.5,99.5,100,1] for i in range(220)]  # range по klines
    d=resolve(snap_greed,flat_kl,regime="range")
    # ta flat, flow flat (слабая дельта), deriv flat (funding нейтрален), sentiment short -> AGREE short
    assert d["status"]=="AGREE" and d["side"]=="short",(d["status"],d["note"],d.get("modules"))
    print("mm_council: OK")
if __name__=="__main__":
    import argparse
    ap=argparse.ArgumentParser()
    ap.add_argument("--selftest",action="store_true")
    ap.add_argument("--decide",action="store_true")
    ap.add_argument("--snap");ap.add_argument("--klines")
    ap.add_argument("--regime",default=None)
    ap.add_argument("--json",action="store_true");ap.add_argument("--max-revisits",type=int,default=None)
    g=ap.parse_args()
    if g.selftest: selftest()
    elif g.decide:
        snap=json.load(open(g.snap)) if g.snap else None
        kl=None
        if g.klines:
            import csv
            with open(g.klines,encoding="utf-8",newline="") as fh:
                kl=[[float(x) for x in r[:6]] for r in list(csv.reader(fh))[1:] if r]
        d=resolve(snap,kl,regime=g.regime,max_revisits=g.max_revisits)
        print(json.dumps(d,ensure_ascii=False,indent=1) if g.json else explain(d))
        sys.exit(0 if d["status"]=="AGREE" else 1)
    else: print("use --selftest or --decide [--snap file] [--klines csv]")
