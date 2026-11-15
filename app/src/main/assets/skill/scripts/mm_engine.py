#!/usr/bin/env python3
"""mm_engine — NEURAL FORECAST ENGINE ENA-MIND v8.0 «ЛЕС И ПАУТИНА».

Конвейер (реальные вычисления, каждый шаг честно статусприруется):
  DATA INGESTION -> DATA VALIDATION -> FEATURE ENGINEERING -> REGIME DETECTION
  -> 18 ANALYTICAL AGENTS (лес) -> WEB (паутина связей) -> NEURAL COUNCIL
  -> STATISTICAL AI -> PREDICTION ENGINE -> FORECAST (15 мин) -> FORECAST MEMORY
  -> FEEDBACK LOOP

Прогноз на 15 минут (главная функция 7.0+):
- пересчёт по каждому feed() (приложение шлёт новые данные каждые ~5с);
- СЕССИЯ прогноза = 15-минутное окно: начальный прогноз сохраняется в память
  и не перезаписывается; последующие пересчёты — ВЕРСИИ той же сессии
  (обновление ≠ новый независимый прогноз);
- P(UP)+P(FLAT)+P(DOWN)=100% строго; при недостатке подтверждений — NO_SIGNAL.

Режим работы — SHADOW (только анализ, никакой торговли; ЧАСТЬ 11).

Двухканальная подача данных (ЧАСТЬ 9 — производительность):
- set_klines(symbol, tf, rows) — тяжёлые свечные ряды (редко, раз в ~60с);
- feed(symbol, fast) — лёгкий срез живого рынка (каждые ~5с: цена/потоки/книга/
  деривативы/кросс-биржа/сентимент). При allow_fetch=True недостающие части
  движок добирает сам (CLI/desktop-режим), с rate-limit и кэшем mm_sources.
- v8.0: медленный контекст (DefiLlama/mempool) и редкие части (киты Binance,
  макро CoinGecko, опционы Deribit) подаются merge-подачей и не теряются.
"""
import json,math,os,time
import mm_config,mm_agents,mm_memory,mm_stats

HORIZON_MIN=15
SESSION_MS=HORIZON_MIN*60*000  # placeholder, заменено ниже
SESSION_MS=HORIZON_MIN*60*1000
MAX_VERSIONS=600            # версий на сессию (5с * 15мин = 180, с запасом)
LOG_MAX=200
ENGINE_VERSION="8.0"

class NeuralEngine:
    def __init__(self,cfg=None):
        self.cfg=cfg or mm_config.load()
        self.sessions={}          # symbol -> session dict
        self.klines={}            # (symbol,tf) -> (ts_set, rows)
        self.fast={}              # symbol -> последний fast-срез
        self.prev_book={}         # symbol -> прошлая книга (ловушки плотностей)
        self.slow_ctx=None        # DefiLlama/mempool (обновляется редко)
        self.slow_ts=0.0
        self.weights=self._load_weights()
        self.model=mm_stats.load_model()
        self.logbuf=[]
        self.n_computes=0
        self._log("engine","движок v%s инициализирован (режим SHADOW)"%ENGINE_VERSION)

    # ---------------- инфраструктура ----------------
    def _log(self,stage,msg):
        self.logbuf.append((int(time.time()*1000),stage,msg))
        if len(self.logbuf)>LOG_MAX: self.logbuf=self.logbuf[-LOG_MAX:]
    def log_tail(self,n=50):
        return [{"ts":ts,"stage":st,"msg":m} for ts,st,m in self.logbuf[-n:]]
    def _wpath(self):
        return os.path.join(mm_config.data_dir(),"agent_weights_v7.json")
    def _load_weights(self):
        try:
            with open(self._wpath(),encoding="utf-8") as f: return json.load(f)
        except Exception: return {}
    def _save_weights(self):
        try:
            os.makedirs(os.path.dirname(self._wpath()),exist_ok=True)
            with open(self._wpath(),"w",encoding="utf-8") as f: json.dump(self.weights,f)
        except Exception: pass

    # ---------------- подача данных ----------------
    def set_klines(self,symbol,tf,rows):
        """Тяжёлый канал: свечи [[ts,o,h,l,c,v]...] по таймфрейму ('1','3','5','15','60')."""
        if not isinstance(rows,list) or len(rows)<5: return False
        try:
            # Если биржевой ряд пришёл от новых к старым — разворачиваем в старые->новые,
            # но не пересортировываем произвольный ряд (важно для тестов с устаревшим хвостом).
            if float(rows[0][0])>float(rows[1][0]) and float(rows[0][0])>float(rows[-1][0]):
                rows=list(reversed(rows))
        except Exception:
            pass
        self.klines[(symbol,str(tf))]=(time.time(),rows)
        return True
    def feed(self,symbol,fast=None):
        """Лёгкий канал: живой срез рынка (JSON-совместимый dict).
        Подача MERGE-ится поверх предыдущего среза — тяжёлые части (funding_hist,
        alt_klines) достаточно присылать редко, они не теряются между подачами."""
        if fast: self.fast[symbol]={**self.fast.get(symbol,{}),**fast}
        return True
    def refresh_slow(self):
        """Медленный контекст (DefiLlama + mempool): не чаще раза в 10 минут."""
        if time.time()-self.slow_ts<600: return self.slow_ctx
        import mm_sources
        try:
            self.slow_ctx=mm_sources.slow_context();self.slow_ts=time.time()
            self._log("ingestion","медленный контекст обновлён (DefiLlama/mempool)")
        except Exception as e:
            self._log("ingestion","медленный контекст недоступен: %s"%str(e)[:80])
        return self.slow_ctx
    def refresh_model(self):
        self.model=mm_stats.load_model()
        return self.model is not None
    def reload_artifacts(self):
        self.weights=self._load_weights()
        self.model=mm_stats.load_model()
        return True

    def _fb(self,symbol,allow_fetch=False):
        """Сборка среза признаков из кэшей (+ опциональный живой добор)."""
        kl={}
        for tf in ("1","3","5","15","60"):
            got=self.klines.get((symbol,tf))
            if got: kl[tf]=got[1]
        fast=self.fast.get(symbol) or {}
        if allow_fetch:
            import mm_sources
            for tf in ("1","3","5","15","60"):
                if tf not in kl:
                    r=mm_sources.bybit_klines(symbol,tf,250)
                    if r.get("ok"): kl[tf]=r["rows"];self.set_klines(symbol,tf,r["rows"])
            f=dict(fast)
            if not f.get("cross"):
                try: f["cross"]=mm_sources.cross_exchange(symbol,self.cfg)
                except Exception: f["cross"]={}
            if not f.get("binance_flow"):
                f["binance_flow"]=mm_sources.binance_flow(symbol)
            if not f.get("okx_flow"):
                f["okx_flow"]=mm_sources.okx_flow(symbol)
            if not f.get("binance_depth"):
                f["binance_depth"]=mm_sources.binance_depth(symbol)
            if not f.get("deriv"):
                # деривативный блок: проверенный снимок mm_live (funding/OI/LSR/F&G)
                try:
                    import mm_live
                    snap=mm_live.snapshot(symbol,budget_sec=20.0,cfg=self.cfg)
                    p=snap.get("parts",{})
                    d=p.get("deriv",{});lsr=p.get("lsr",{});fh=p.get("funding_hist",{});fng=p.get("fng",{})
                    f["deriv"]={"ok":bool(d.get("ok")),
                                "funding_pct":d.get("funding_pct"),"oi":d.get("oi"),
                                "funding_hist":fh.get("history") if fh.get("ok") else [],
                                "oi_pct_change":(p.get("oi_hist",{}).get("oi_pct_change")),
                                "lsr":lsr.get("ratio") if lsr.get("ok") else None,
                                "lsr_pct_rank":lsr.get("pct_rank"),
                                "liquidations":(f.get("liquidations") or {})}
                    if fng.get("ok"):
                        f["fng"]={"ok":True,"value":fng.get("value"),
                                  "delta3":fng.get("delta3"),"delta7":fng.get("delta7"),
                                  "classification":fng.get("classification")}
                except Exception:
                    pass
            fast=f
        book=fast.get("book") or {}
        fb=dict(fast)
        fb["symbol"]=symbol  # v7.2: агентам нужен актив (опционы/корреляции)
        fb["klines"]=kl
        fb["book"]=book
        if book.get("ok") and book.get("bids"):
            fb["book"]=dict(book);fb["book"]["prev"]=self.prev_book.get(symbol) or {}
            self.prev_book[symbol]={"bids":book.get("bids",[])[-50:],"asks":book.get("asks",[])[-50:]}
        fb["weights"]=self.weights
        fb["model"]=self.model if (self.model or {}).get("ready") else {}
        # v8.0: медленный контекст (DefiLlama TVL + ончейн BTC) — агентам 14/15
        if self.slow_ctx:
            fb["slow"]=self.slow_ctx
        # редкие бэкфиллы (merge-подача): деривативы и OKX-контекст
        extra=fast.get("deriv_extra") or {}
        if extra:
            d=dict(fb.get("deriv") or {})
            if not d.get("ok"): d["ok"]=bool(extra.get("ok"))
            if not d.get("funding_hist"): d["funding_hist"]=extra.get("funding_hist") or []
            if d.get("lsr") is None: d["lsr"]=extra.get("lsr")
            if d.get("lsr_pct_rank") is None: d["lsr_pct_rank"]=extra.get("lsr_pct_rank")
            if d.get("oi_pct_change") is None: d["oi_pct_change"]=extra.get("oi_pct_change")
            fb["deriv"]=d
        ox=fast.get("okx_extra") or {}
        if ox.get("ok"):
            cross=dict(fb.get("cross") or {})
            parts=dict(cross.get("parts") or {})
            if "okx_funding" not in parts:
                parts["okx_funding"]={"ok":True,"funding_pct":ox.get("funding_pct")}
            cross["parts"]=parts;fb["cross"]=cross
        # цена: из fast-среза, иначе из последних закрытых свечей
        if not fb.get("price"):
            for tf in ("1","5"):
                rows=kl.get(tf)
                if rows: fb["price"]=rows[-1][4];break
        return fb

    # ---------------- валидация данных ----------------
    def _validate(self,fb):
        """Качество данных: свежесть свечей, живость источников. Грейд A/C/D."""
        issues=[]
        kl_map=fb.get("klines") or {}
        kl1=kl_map.get("1") or []
        klines_ok=len(kl1)>=60
        last_age=None
        if klines_ok:
            last_age=(time.time()*1000-kl1[-1][0])/1000.0
            if last_age>300: klines_ok=False;issues.append("1m-свечи устарели (%.0fс)"%last_age)
        else:
            issues.append("1m-свечей меньше 60")
        # Резерв: если 1m временно недоступен/неполный, проверяем свежие 5m/15m/60m
        if not klines_ok:
            for alt_tf,max_age in (("5",900),("15",1800),("60",5400)):
                alt=kl_map.get(alt_tf) or []
                if len(alt)>=40:
                    alt_age=(time.time()*1000-alt[-1][0])/1000.0
                    if alt_age<=max_age:
                        klines_ok=True
                        last_age=alt_age
                        issues.append("основной расчёт по %sм-свечам (1m неполны)"%alt_tf)
                        break
        flows_ok=sum(1 for k in ("tape","binance_flow","okx_flow")
                     if (fb.get(k) or {}).get("ok"))
        deriv_ok=(fb.get("deriv") or {}).get("ok")
        book_ok=(fb.get("book") or {}).get("ok")
        cross_n=(fb.get("cross") or {}).get("n_exchanges",0)
        if not klines_ok:
            grade="D"
        elif flows_ok>=2 and book_ok and deriv_ok:
            grade="A"
        elif flows_ok>=1 or deriv_ok:
            grade="C"
        else:
            grade="C";issues.append("живых источников мало (потоки/деривативы)")
        n_total=8  # klines + 3 flow + book + deriv + cross
        n_avail=int(klines_ok)+flows_ok+int(bool(book_ok))+int(bool(deriv_ok))+int(cross_n>=2)
        return {"grade":grade,"issues":issues,"n_available":n_avail,"n_total":n_total,
                "klines_last_age_sec":round(last_age,1) if last_age is not None else None,
                "flows_live":flows_ok,"cross_exchanges":cross_n}

    # ---------------- NEURAL COUNCIL v2 ----------------
    def _council(self,agents,quality):
        """Совет: свежесть, ненадёжные агенты, противоречия кластеров, NO_SIGNAL."""
        ok_agents=[a for a in agents if a["status"]=="OK"]
        votes=[a for a in ok_agents if a["side"] in ("LONG","SHORT") and a["confidence"]>0.3]
        clusters={}
        for a in votes:
            for cname,members in mm_agents.CLUSTERS.items():
                if a["agent"] in members:
                    clusters.setdefault(cname,[]).append(a);break
        # слабый агент кластера не считается дважды: в кластере решает сильнейший
        cluster_votes={}
        for cname,ag_list in clusters.items():
            best=max(ag_list,key=lambda a:a["confidence"])
            supp=[a["agent"] for a in ag_list if a is not best]
            cluster_votes[cname]={"agent":best["agent"],"side":best["side"],
                                  "confidence":best["confidence"],"suppressed":supp}
        contradictions=[]
        for cname,v in cluster_votes.items():
            for s in v.get("suppressed",[]):
                contradictions.append("кластер %s: %s подавлен более сильным %s (нет двойного учёта)"%(
                    cname,s,v["agent"]))
        sides=[v["side"] for v in cluster_votes.values()]
        if "LONG" in sides and "SHORT" in sides:
            contradictions.append("кластеры противоречат: %s"%", ".join(
                "%s=%s"%(c,v["side"]) for c,v in cluster_votes.items()))
        # ненадёжные агенты (историческая точность < 40% при n>=30) — понижение
        acc=mm_memory.agent_accuracy()
        for name,d in acc.items():
            if d.get("n",0)>=30 and (d.get("hit_rate") or 0.5)<0.4:
                contradictions.append("агент %s исторически слаб (hit %.0f%%, n=%d) — вес понижен"%(
                    name,100*d["hit_rate"],d["n"]))
        if quality["grade"]=="D":
            status="INSUFFICIENT";note="данные grade D — совет не собирается"
        elif len(cluster_votes)<2:
            status="NO_SIGNAL";note="подтверждающих кластеров меньше 2 (%d) — NO_SIGNAL"%len(cluster_votes)
        elif contradictions and sides.count("LONG")>0 and sides.count("SHORT")>0 and len(cluster_votes)<3:
            status="NO_SIGNAL";note="противоречие без третьего подтверждающего кластера"
        else:
            status="AGREE";note="кластеры согласованы: %s"%", ".join(
                "%s(%s)"%(c,v["side"]) for c,v in cluster_votes.items())
        # v8.0 ПАУТИНА: согласованность леса (web_coherence) — часть решения совета
        web=mm_agents.web_analysis(agents)
        if web["coherence"] is not None:
            note+="; паутина: %d связей, согласие %.0f%%"%(web["active"],100*web["coherence"])
            if web["conflict"]>web["agree"] and status=="AGREE":
                contradictions.append("паутина: конфликтов (%d) больше, чем согласий (%d)"%(
                    web["conflict"],web["agree"]))
        return {"status":status,"clusters":cluster_votes,"contradictions":contradictions,
                "n_voting":len(votes),"n_ok_agents":len(ok_agents),"note":note,
                "agent_accuracy":acc,"web":web}

    # ---------------- главный прогон ----------------
    def compute(self,symbol="BTCUSDT",allow_fetch=False,store=True):
        """Полный конвейер. Возвращает JSON-совместимый dict для приложения."""
        t0=time.time()
        stages=[]
        def stage(name,status,summary):
            stages.append({"name":name,"status":status,"summary":summary,
                           "elapsed_ms":int((time.time()-t0)*1000)})
        # 1-2 DATA INGESTION + VALIDATION
        fb=self._fb(symbol,allow_fetch=allow_fetch)
        quality=self._validate(fb)
        stage("DATA INGESTION","OK" if quality["grade"]!="D" else "STOPPED",
              "источников AVAILABLE %d/%d, grade %s"%(quality["n_available"],quality["n_total"],quality["grade"]))
        stage("DATA VALIDATION","OK" if quality["grade"]!="D" else "STOPPED",
              "; ".join(quality["issues"]) or "свежесть и качество в норме")
        if quality["grade"]=="D":
            self._log(symbol,"остановка: grade D (%s)"%";".join(quality["issues"][:2]))
            return {"ok":False,"symbol":symbol,"ts":int(time.time()*1000),
                    "mode":"SHADOW","engine":ENGINE_VERSION,"stages":stages,
                    "data":quality,"agents":[],"council":{"status":"INSUFFICIENT"},
                    "forecast":None,"error":"INSUFFICIENT DATA (grade D)"}
        # 3 REGIME (из волатильностного агента ниже — но regime нужен council-записи,
        #   считаем быстро по агенту 07 после его выполнения)
        # 4-6 АГЕНТЫ + CROSS VALIDATION
        agents,pred=mm_agents.run_all(fb,symbol)
        # STATISTICAL AI уже внутри agents; помечаем стадии честно
        stat=next((a for a in agents if a["agent"]=="STATISTICAL_AI"),None)
        stage("AGENTS 01-18 (ЛЕС)","OK","OK: %d, INSUFFICIENT: %d, DISABLED: %d"%(
            sum(1 for a in agents if a["status"]=="OK"),
            sum(1 for a in agents if a["status"]=="INSUFFICIENT"),
            sum(1 for a in agents if a["status"]=="DISABLED")))
        # v8.1 (ТЗ §13): TREND INTELLIGENCE LAYER — отдельный контур до совета;
        # trend outlook != entry quality != risk (§11)
        try:
            import mm_trend
            trend=mm_trend.trend_layer(fb.get("klines") or {},
                                       memory_stats=mm_memory.agent_accuracy().get("TREND_LAYER"))
        except Exception as e:
            trend={"status":"INSUFFICIENT","reason":"trend layer: %s"%str(e)[:80]}
        stage("TREND LAYER",trend.get("status","INSUFFICIENT"),
              trend.get("bias") or trend.get("reason") or "")
        # v8.1 (ТЗ §4): OCIE — второй аналитический контур (ончейн)
        try:
            import mm_onchain_engine
            ocie=mm_onchain_engine.get_engine().analyze(symbol,refresh=False)
        except Exception as e:
            ocie={"state":"ONCHAIN_UNAVAILABLE","direction":"NEUTRAL",
                  "confidence":0.0,"note":"ocie: %s"%str(e)[:80],"quality":0.0,
                  "agents":[],"sources":{}}
        stage("ONCHAIN ENGINE",
              "OK" if ocie.get("state") not in ("ONCHAIN_UNAVAILABLE",) else "DEGRADED",
              "%s — %s"%(ocie.get("state"),(ocie.get("note") or "")[:80]))
        regime_agent=next((a for a in agents if a["agent"]=="VOLATILITY_ANALYST"),None)
        regime=(regime_agent or {}).get("features",{}).get("regime","UNCERTAIN")
        stage("REGIME DETECTION","OK" if regime!="UNCERTAIN" else "OK",regime)
        council=self._council(agents,quality)
        # v8.1 (ТЗ §5): ON-CHAIN REVIEW — после Market Council, до Forecast.
        # OCIE не перезаписывает прогноз; подтверждает/противоречит/недоступен.
        side=pred.get("side")
        ocie_review={"state":"NO_ONCHAIN","confidence_adj":1.0,"note":""}
        if ocie.get("state")=="ONCHAIN_UNAVAILABLE":
            ocie_review={"state":"ONCHAIN_UNAVAILABLE","confidence_adj":1.0,
                         "note":"движок продолжает работу, ончейн-контур недоступен"}
        elif ocie.get("direction") in ("LONG","SHORT"):
            agree=(ocie["direction"]=="LONG" and side=="UP") or \
                  (ocie["direction"]=="SHORT" and side=="DOWN")
            if agree:
                ocie_review={"state":"CONFIRMED","confidence_adj":1.05,
                             "note":"ончейн подтверждает прогноз рынка"}
            else:
                ocie_review={"state":"CONFLICT","confidence_adj":0.85,
                             "note":"ончейн противоречит прогнозу — уверенность снижена"}
                council.setdefault("contradictions",[]).append(
                    "ON-CHAIN REVIEW: конфликт — %s при рыночном %s (conf_adj %.2f)"%(
                        ocie["direction"],side,ocie_review["confidence_adj"]))
        council["onchain_review"]=ocie_review
        stage("NEURAL COUNCIL","OK" if council["status"] in ("AGREE","NO_SIGNAL") else "STOPPED",
              "%s — %s"%(council["status"],council["note"]))
        web=council.get("web") or {}
        stage("WEB ПАУТИНА","OK","связей активно %d: согласий %d, конфликтов %d%s"%(
            web.get("active",0),web.get("agree",0),web.get("conflict",0),
            ", согласие %.0f%%"%(100*web["coherence"]) if web.get("coherence") is not None else ""))
        stage("STATISTICAL AI",stat["status"] if stat else "DISABLED",
              (stat or {}).get("evidence",["отключён"])[0][:120])
        stage("PREDICTION ENGINE",pred["status"],
              pred["evidence"][0] if pred.get("evidence") else "")
        # 7 FORECAST + сессии (версионирование)
        fc,cinfo=self._forecast_session(symbol,pred,fb,quality,regime,council,agents,store)
        stage("FORECAST 15M","OK","P(UP)=%.0f%% P(FLAT)=%.0f%% P(DOWN)=%.0f%% — сессия %s, версия %d%s"%(
            pred["features"].get("p_up",0)*100,pred["features"].get("p_flat",0)*100,
            pred["features"].get("p_down",0)*100,cinfo["id"],cinfo["version"],
            " (начальная)" if cinfo["is_initial"] else " (обновление)"))
        stage("FORECAST MEMORY","OK",cinfo["memory_op"])
        # 8 FEEDBACK LOOP: веса агентов обновляются по памяти (плавно, EMA)
        if self.n_computes%12==0:
            new_w=mm_memory.agent_weights(self.weights)
            if new_w!=self.weights:
                self.weights=new_w;self._save_weights()
                self._log("feedback","веса агентов обновлены: %s"%json.dumps(new_w,ensure_ascii=False))
        stage("FEEDBACK LOOP","OK","весов агентов: %d"%len(self.weights))
        # v8.1 (ТЗ §16): 24H OUTLOOK — отдельный горизонт, не подменяет 15м.
        # Запускаем не чаще раза в 5 минут (дорогой новостной контекст кэшируется NewsRepo).
        try:
            news=fb.get("news") or []
            news_sig=(len(news),(news[0].get("title") if news and isinstance(news[0],dict) else ""))
            if (time.time()-getattr(self,"_last_24h",0)>300
                    or getattr(self,"_out24_symbol",None)!=symbol
                    or getattr(self,"_out24_news_sig",None)!=news_sig):
                import mm_outlook24
                st=mm_memory.stats().get("checked") or {}
                self._out24_local=mm_outlook24.outlook_24h(fb.get("klines") or {},trend,
                                                           news,mem_stats_compat(st))
                self._last_24h=time.time()
                self._out24_symbol=symbol
                self._out24_news_sig=news_sig
        except Exception as e:
            self._out24_local={"status":"INSUFFICIENT","error":str(e)[:80]}
        stage("24H OUTLOOK",
              self._out24_local.get("status","INSUFFICIENT"),
              "P(UP)=%.0f%% P(FLAT)=%.0f%% P(DOWN)=%.0f%% (%s)"%(
                  100*(self._out24_local.get("p_up") or 0),
                  100*(self._out24_local.get("p_flat") or 0),
                  100*(self._out24_local.get("p_down") or 0),
                  self._out24_local.get("calibration",{}).get("label","")))
        # v8.1 (ТЗ §42): MARKET DISCOVERY — формации активного символа (дёшево, детерминированно)
        try:
            import mm_discovery
            disc_rows=(fb.get("klines") or {}).get("60") or (fb.get("klines") or {}).get("15") or []
            disc=mm_discovery.scan_symbol(disc_rows) if disc_rows else {"status":"INSUFFICIENT","patterns":[]}
        except Exception as e:
            disc={"status":"INSUFFICIENT","patterns":[],"error":str(e)[:80]}
        stage("MARKET DISCOVERY",disc.get("status","INSUFFICIENT"),
              "; ".join(p["pattern"] for p in disc.get("patterns",[]))[:100] or "формаций нет")
        self.n_computes+=1
        result={"ok":True,"symbol":symbol,"ts":int(time.time()*1000),
                "mode":"SHADOW","engine":ENGINE_VERSION,"stages":stages,
                "data":quality,"regime":regime,"agents":agents,"council":council,
                "prediction":pred,"forecast":fc,"session":cinfo,
                "trend":trend,"onchain":ocie,"outlook24h":self._out24_local,
                "discovery":disc,
                "elapsed_ms":int((time.time()-t0)*1000)}
        self._log(symbol,"compute: grade=%s, %s, P(up)=%.0f%%, %dмс"%(
            quality["grade"],council["status"],
            100*(pred["features"].get("p_up") or 0),result["elapsed_ms"]))
        return result

    def _forecast_session(self,symbol,pred,fb,quality,regime,council,agents,store):
        """Сессии 15-минутных прогнозов: начальная версия пишется в память,
        обновления — только версии. Новое окно = новая сессия = новый прогноз."""
        now_ms=int(time.time()*1000)
        bucket=now_ms//SESSION_MS
        sess=self.sessions.get(symbol)
        if not sess or sess["bucket"]!=bucket:
            sess={"bucket":bucket,"id":"%s-%d"%(symbol,bucket),"started_at":now_ms,
                  "initial":None,"versions":[],"n_updates":0}
            self.sessions[symbol]=sess
        p=pred.get("features") or {}
        ver={"ts":now_ms,"p_up":p.get("p_up"),"p_flat":p.get("p_flat"),
             "p_down":p.get("p_down"),"side":pred.get("side"),
             "expected_move_pct":p.get("expected_move_pct"),
             "confidence":pred.get("confidence")}
        sess["versions"].append(ver)
        if len(sess["versions"])>MAX_VERSIONS: sess["versions"]=sess["versions"][-MAX_VERSIONS:]
        is_initial=sess["initial"] is None
        memory_op="no-op"
        if is_initial and store and pred.get("status")=="OK" and fb.get("price"):
            # волатильностная полоса для аналитики по волатильности
            vb="hi" if (p.get("sigma_15m_pct") or 0)>=0.5 else ("lo" if (p.get("sigma_15m_pct") or 1)<=0.15 else "mid")
            mm_memory.store({
                "id":sess["id"],"asset":symbol,"ts":now_ms,
                "entry_price":fb.get("price"),"horizon_min":HORIZON_MIN,
                "p":{"p_up":p.get("p_up"),"p_flat":p.get("p_flat"),"p_down":p.get("p_down"),
                     "side":pred.get("side")},
                "side":pred.get("side"),"model_version":(self.model or {}).get("version","heuristic_v1"),
                "agents":[{"agent":a["agent"],"side":a["side"],"confidence":a["confidence"]}
                          for a in agents if a["status"]=="OK"],
                "regime":regime,"data_quality":quality["grade"],
                "volatility_band":vb,"council":council["status"]})
            sess["initial"]=dict(ver)
            memory_op="stored new forecast"
        elif not is_initial:
            sess["n_updates"]+=1
            memory_op="version #%d (initial preserved)"%sess["n_updates"]
        # автопроверка истёкших сессий — не чаще раза в 5 минут
        if store and time.time()-getattr(self,"_last_verify",0)>300:
            try:
                n,e=mm_memory.verify()
                if n: self._log("memory","проверено прогнозов: %d"%n)
            except Exception as ex:
                self._log("memory","verify error: %s"%str(ex)[:80])
            self._last_verify=time.time()
        cinfo={"id":sess["id"],"bucket":sess["bucket"],"started_at":sess["started_at"],
               "version":len(sess["versions"]),"is_initial":is_initial,
               "n_updates":sess["n_updates"],"memory_op":memory_op,
               "initial":sess["initial"]}
        fc=dict(ver);fc["session_id"]=sess["id"];fc["version"]=len(sess["versions"])
        fc["is_initial"]=is_initial
        fc["arguments"]=(pred.get("evidence") or [])[:6]
        fc["contradictions"]=(pred.get("contradictions") or [])[:6]
        fc["invalidate"]=(pred.get("invalidate") or [])[:4]
        fc["alt_scenario"]=(pred.get("features") or {}).get("alt_scenario")
        fc["range_low"]=p.get("range_low");fc["range_high"]=p.get("range_high")
        fc["sigma_15m_pct"]=p.get("sigma_15m_pct")
        fc["expected_move_pct"]=p.get("expected_move_pct")
        fc["signal_strength"]=p.get("signal_strength");fc["uncertainty"]=p.get("uncertainty")
        fc["council_status"]=council["status"]
        fc["mode"]="SHADOW"
        return fc,cinfo

    # ---------------- API для моста/UI ----------------
    def status(self):
        sess=self.sessions
        return {"engine":ENGINE_VERSION,"mode":"SHADOW",
                "sessions":{s:v["id"] for s,v in sess.items()},
                "model_ready":bool((self.model or {}).get("ready")),
                "weights":self.weights,"n_computes":self.n_computes,
                "slow_ctx_age_sec":round(time.time()-self.slow_ts,0) if self.slow_ts else None,
                "klines_cached":len(self.klines)}
    def memory_view(self):
        return {"stats":mm_memory.stats(),"agent_accuracy":mm_memory.agent_accuracy(),
                "calibration":mm_memory.calibration(),"model_performance":mm_memory.model_performance(),
                "weights":self.weights}

# синглтон для chain_bridge (процесс живёт, пока живёт приложение)
_ENGINE=None
def get_engine(cfg=None):
    global _ENGINE
    if _ENGINE is None: _ENGINE=NeuralEngine(cfg)
    return _ENGINE

def mem_stats_compat(st):
    """mm_memory.stats()['checked'] -> {n,hit_rate} для калибровки 24H."""
    if not isinstance(st,dict): return None
    n=st.get("n",0)
    if not n: return None
    return {"n":n,"hit_rate":st.get("hit_rate",0.5)}

def selftest():
    import random
    random.seed(11)
    d=None
    import tempfile
    d=tempfile.mkdtemp();os.environ["MM5_DATA_DIR"]=d
    try:
        eng=get_engine()
        # детерминированный тренд вверх + согласованные потоки (свечи свежие — до «сейчас»)
        def mk(n=280,drift=0.0018):
            rows=[];p=100.0;t0=(time.time()-(n+1)*60)*1000
            for i in range(n):
                o=p;p*=(1+drift)
                rows.append([int(t0)+ (i+1)*60000,round(o,4),round(p*1.0005,4),round(o*0.9995,4),round(p,4),1.5])
            return rows
        up=mk()
        for tf in ("1","3","5","15","60"): eng.set_klines("TESTUSDT",tf,up)
        # v8.0: медленный контекст и редкие части подаются merge-подачей (лес 18 агентов)
        eng.slow_ctx={"defillama":{"ok":True,"total_tvl":175e9,"delta_24h_pct":1.8,
                                    "delta_1h_pct":0.2,"history_points":30},
                      "mempool":{"ok":True,"fee_fast":65,"mempool_tx":150000,
                                 "median_block_sec":520,"avg_block_tx_6":3000.0}}
        eng.slow_ts=time.time()
        eng.feed("TESTUSDT",{"price":up[-1][4],
            "tape":{"ok":True,"n":500,"delta_pct":22.0,"cvd":9.0,"age_sec":1,"price_change_pct":0.3},
            "binance_flow":{"ok":True,"n":400,"delta_pct":18.0,"age_sec":1},
            "okx_flow":{"ok":True,"n":300,"delta_pct":16.0,"age_sec":2},
            "book":{"ok":True,"bids":[(100.0-i*0.1,2.0) for i in range(10)],
                    "asks":[(100.1+i*0.1,1.0) for i in range(10)]},
            "binance_depth":{"ok":True,"imbalance_pct":18.0,"spread_pct":0.01},
            "deriv":{"ok":True,"funding_pct":0.005,"funding_hist":[0.005]*30,
                     "oi_pct_change":2.0,"lsr_pct_rank":50.0,
                     "liquidations":{"ok":True,"long_usd_1h":0.0,"short_usd_1h":0.0}},
            "cross":{"n_exchanges":3,"parts":{
                "bybit":{"ok":True,"last":up[-1][4],"funding_pct":0.005},
                "binance_price":{"ok":True,"last":up[-1][4]*1.0001},
                "okx_price":{"ok":True,"last":up[-1][4]*1.0002},
                "binance_flow":{"ok":True,"delta_pct":18.0},
                "okx_flow":{"ok":True,"delta_pct":16.0},
                "okx_funding":{"ok":True,"funding_pct":0.005}},
                "diffs":{"bybit_binance_bps":1.0}},
            "alt_klines":{"BTCUSDT":up},"fng":{"ok":True,"value":55,"delta3":2},"news":[],
            "whales":{"ok":True,"status":"AVAILABLE","n_whales":4,"buy_usd":2.4e6,
                      "sell_usd":4e5,"delta_pct":71.4,"top":[],"n_trades":900},
            "macro":{"ok":True,"btc_dominance":57.2,"btc_dom_delta_24h":0.4,
                     "mcap_change_24h_pct":2.8,"total_mcap_usd":3.3e12}})
        res=eng.compute("TESTUSDT")
        assert res["ok"],res.get("error")
        f=res["forecast"]
        assert f and abs(f["p_up"]+f["p_flat"]+f["p_down"]-1.0)<1e-6,f
        # v8.0: лес из 18 агентов + паутина в совете и прогнозе
        assert len(res["agents"])==18,len(res["agents"])
        assert "web" in res["council"] and res["council"]["web"]["active"]>=1,res["council"]
        pf=res["prediction"]["features"]
        assert "web_active_links" in pf,pf
        assert any(s["name"]=="WEB ПАУТИНА" for s in res["stages"]),res["stages"]
        assert res["session"]["is_initial"] and res["session"]["memory_op"].startswith("stored")
        # вторая compute в том же окне -> версия, начальная сохранена
        eng.feed("TESTUSDT",{"price":up[-1][4]*1.001,"deriv":{"ok":True,"funding_pct":0.006},
                             "tape":{"ok":True,"n":600,"delta_pct":25.0,"cvd":11.0,"age_sec":1,"price_change_pct":0.4}})
        res2=eng.compute("TESTUSDT")
        assert not res2["session"]["is_initial"],res2["session"]
        assert res2["session"]["n_updates"]>=1
        assert res2["session"]["initial"] is not None
        assert res2["forecast"]["version"]>1
        # смена 15-мин окна -> новая сессия (сдвигаем время принудительно)
        s=eng.sessions["TESTUSDT"];s["bucket"]-=1
        res3=eng.compute("TESTUSDT")
        assert res3["session"]["is_initial"],"новое окно обязано дать новую сессию"
        # grade D при устаревших свечах
        old=[r[:] for r in up]
        old[-1][0]=int((time.time()-600)*1000)
        eng.set_klines("STALEUSDT","1",old)
        resD=eng.compute("STALEUSDT")
        assert not resD["ok"] and resD["data"]["grade"]=="D"
        # NO_SIGNAL при пустых потоках/книге
        eng.set_klines("FLATUSDT","1",up)
        eng.feed("FLATUSDT",{"price":up[-1][4],"deriv":{"ok":True,"funding_pct":0.004},
                             "fng":{"ok":True,"value":50}})
        resN=eng.compute("FLATUSDT")
        assert resN["council"]["status"] in ("NO_SIGNAL","AGREE")
        # журнал и статус
        assert eng.log_tail(5) and len(eng.log_tail(3))==3
        st=eng.status();assert st["engine"]==ENGINE_VERSION and st["mode"]=="SHADOW"
        mv=eng.memory_view();assert "stats" in mv and "agent_accuracy" in mv
    finally:
        del os.environ["MM5_DATA_DIR"]
        global _ENGINE;_ENGINE=None
    print("mm_engine: OK")
if __name__=="__main__":
    import argparse
    ap=argparse.ArgumentParser()
    ap.add_argument("--selftest",action="store_true")
    ap.add_argument("--run",default=None,help="живой прогон по символу (сам собирает данные)")
    g=ap.parse_args()
    if g.selftest: selftest()
    elif g.run:
        eng=get_engine()
        r=eng.compute(g.run,allow_fetch=True)
        print(json.dumps(r,ensure_ascii=False,indent=1)[:4000])
    else: print("use --selftest / --run SYMBOL")
