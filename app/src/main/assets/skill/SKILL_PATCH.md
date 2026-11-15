# SKILL PATCH v7.0 (ENA-MIND)

## Новое в v7.0: NEURAL FORECAST ENGINE (поверх патча v6.1 — он сохранён ниже)

- Прогноз 15 минут: P(UP)+P(FLAT)+P(DOWN)=100% строго; μ=k×edge×σ15, порог FLAT
  max(0.5×σ15, издержки round-trip 0.11%); диапазон 80% = μ±1.2816σ; сила сигнала —
  нормированный взвешенный edge; неопределённость — энтропия трёхклассового
  распределения / ln(3). mm_agents.agent_prediction.
- Контракт агента: {agent,status(OK|INSUFFICIENT|DISABLED),timestamp,side
  (LONG|SHORT|NEUTRAL),confidence,features,evidence,contradictions,invalidate}.
  mm_agents.AGENTS, CLUSTERS.
- Binance aggTrades: m=true (buyer is maker) = агрессивная ПРОДАЖА. OKX candles —
  новые сверху (reverse). mm_sources.
- Walk-forward без lookahead: признаки features_at(rows,i) только по rows[:i+1];
  стандартизация mean/std на train-окне внутри модели; метка — ret(t+15);
  модель ready только при hit_rate > max(0.52, baseline_hit). mm_stats.
- Память v7: forecast_memory_v7.jsonl (append-only); verify() закрывает записи
  с истёкшим горизонтом по 1m-свечам (ближайшая к target_ts, свечей нет —
  остаётся на следующий вызов); веса агентов: target=1+(hit-0.5)×2 в [0.5,1.5],
  обновление EMA α=0.25 при n>=20. mm_memory.
- Сессии: bucket=ts//15мин; новая корзина = новая сессия (initial в память);
  пересчёты в окне — версии (MAX_VERSIONS=600); initial не перезаписывается.
  mm_engine._forecast_session.
- Подача данных: set_klines(symbol,tf,rows) редко + feed(symbol,fast) часто,
  MERGE поверх прошлого среза (тяжёлые части не теряются); deriv_extra/okx_extra
  вливаются в deriv/cross в _fb. mm_engine.

## Патч v6.1 (сохранён без изменений)

Архитектура: Рынок (Bybit V5) -> Ingestion -> Data Check -> Regime -> Analysis (4)
-> Cross-Validation -> Decision -> Forecast -> Risk Gate -> Output.
Контуры обратной связи: Journal -> Feedback p_hat -> Council (приоры);
Forecast memory (store -> verify -> stats) -> поправки уверенности прогноза (n>=10).

1. p = regime-приор (mm_config.PRIORS_BY_REGIME) + 0.025×сила сигнала; зажат в [0.5, 0.8].
   При `use_feedback_prior=True` и n_compliant >= 20 приор аккуратно корректируется через
   p_hat (±0.05). При n_compliant < 20 — LOW CONFIDENCE, вердикта об эдже нет.
   Статус: regime-приоры в mm_config.PRIORS_BY_REGIME, корректировка в mm_council._adjust_prior.
2. Пороги динамические по режимам:
   - funding: перцентиль за 30д из Bybit /v5/market/funding/history
     (deriv_analyze: snap.parts.funding_hist.history или явный funding_history) — 95/5
     перцентиль; без истории — fallback на порог ±funding_extreme_pct (0.05);
   - F&G: контекст (Extreme Greed/Fear) + дельта 3/7д (mm_live.fng_calc);
   - LSR: Bybit /v5/market/account-ratio, пороги lsr_extreme=1.4 / lsr_low=0.7;
   - regime: classify по DI-proxy + ATR%/BB-ширина (mm_regime).
   beta и корреляции rolling 30/60/90д — НЕ реализованы.
3. Контракт документация-код: метрика заявляется только если есть функция.
   winrate/PF/expectancy/maxDD/sharpe/sortino/recovery -> mm_backtest.metrics
   Brier/calibration -> mm_journal.stats; p_hat/regime_p_hat -> mm_feedback
   quality/grade/source_status(AVAILABLE/UNAVAILABLE/STALE/INSUFFICIENT) -> mm_data/mm_live
   costs/risk/liq -> mm_core (с настраиваемыми комиссиями)
   regime/adx/atr_pct/bb_width_pct -> mm_regime
   tape/CVD/book/funding/OI(+oi_pct_change)/LSR/F&G(дельты 3/7д)/funding_hist -> mm_live
   вердикты модулей ta/flow/deriv/sentiment (+news-поправка) -> mm_analyst
   AGREE/NO_TRADE/HALT/REVISIT, конфликты, audit, regime, p_hat_from_feedback -> mm_council
   FORECAST (side/confidence/band/arguments/contradictions/alt_scenario/invalidate/
   gate_status/trade_allowed) -> mm_forecast
   store/verify/stats прогнозов -> mm_forecast; узлы цепочки + итог из 10 пунктов -> mm_chain
   gates PASS/BLOCK, отпечаток плана -> mm_risk; алерты цена/funding -> mm_alerts
   edge_by_regime -> mm_journal.stats; klines REST Bybit V5 -> mm_klines
4. Forecast (Узел 5) НЕ является торговым решением: работает при NO_TRADE/HALT, но
   trade_allowed только при council AGREE И gate PASS. Уверенность — относительная
   (band LOW/MEDIUM/HIGH), НЕ калиброванная вероятность; потолок 0.60 при конфликтах
   узлов и в volatile; при grade D или <forecast_min_modules (2) живых аналитиков —
   INSUFFICIENT без направления. Memory-поправка ±0.05 при n>=10 проверенных прогнозов
   в режиме; правила Risk Gate памятью не меняются.
5. Риск-менеджмент аналитический: не открывает и не закрывает реальные сделки.
6. Издержки: слиппедж по сторонам, funding со знаком, риск включает издержки.
   C — ожидаемые (funding со знаком), C_worst — худший случай.
   Эталон: стоп 0.8%, цель 2.2%, C=0.176% -> проходит фильтры.
   Регрессия: стоп 0.7%, цель 2.0% -> обязана отклоняться по лимиту издержек.
   Оба случая в mm_core.selftest; регрессия дополнительно в mm_risk.selftest.
   Комиссии настраиваемые (mm_config.fees, data/config.json, env MM5_*); биржа — Bybit.
7. Ворота перед сделкой (mm_risk): формат, экономика (4 фильтра mm_core), размер с
   минимальным лотом, риск на сделку, маржа, буфер ликвидации, плечо, дубли, число
   открытых позиций, сделок и убыток за день. Право вето: decision NO_TRADE/HALT ->
   BLOCK; сторона плана != стороне совета -> BLOCK; data_grade D -> BLOCK; grade C
   требует p >= min_p_grade_c (0.6). BLOCK окончателен.
   mm_journal pre --require-gate принимает только план с PASS за сегодня.
   Лимиты по умолчанию: депозит 100 USD, риск 2%, маржа 50%, буфер >=3 стопов,
   1 открытая позиция, 3 сделки и убыток 3% в день (config.json или env MM5_*).
8. Алерты (mm_alerts, 24/7): above/below/stop/target, pct_move к цене 24ч назад
   (Bybit prevPrice24h), funding_above/below. Котировки: Bybit /v5/market/tickers.
9. Cross-Validation (mm_council): конфликт long/short при обеих conf >= 0.6 ->
   REVISIT слабейшего (conservative: conf x0.7, p к нейтрали); повторный конфликт ->
   NO_TRADE (CONFLICT). Мягкий конфликт: слабая сторона отброшена с записью в audit.
   Все flat -> NO TRADE. Обязательный модуль INSUFFICIENT -> HALT.
   p_agg взвешенная по confidence, 0.5-0.8. Параметры: min_conf 0.5,
   conflict_min_conf 0.6, max_revisits 1 (config.json).
10. Regime (mm_regime) — классифицирует фазу trend_up/trend_down/range/volatile/unknown
    по свечному ряду. Влияет на приоры через mm_config.prior_for(module, regime).
11. Sentiment (mm_analyst.sentiment_analyze) — толпа = F&G + LSR (+ мягкий новостной фон
    в v6, вес <= news_max_weight, никогда не HALT и не решает сторону единолично).
    Extreme Greed (>=75) или LSR >= 1.4 -> short (контр-тренд). Extreme Fear (<=25)
    или LSR <= 0.7 -> long (контр-тренд). F&G дельта 3д |Δ| >= 15 усиливает разворот.
12. Live klines REST (mm_klines): только Bybit /v5/market/kline (interval в минутах,
    ts УЖЕ в ms — фикс бага v5), fallback на CSV.
13. Bybit V5 — единственная биржа (v6.0): recent-trade (tape), orderbook (книга),
    tickers (funding/OI/mark/last), account-ratio (LSR), open-interest (oi_pct_change),
    funding/history (перцентиль 30д). Ликвидации: WS-only allLiquidation — в REST-снимке
    статус UNAVAILABLE, инъекция WS-данных через extra_parts (source="app").
    Статусы источников: mm_data.source_status (AVAILABLE/UNAVAILABLE/STALE/INSUFFICIENT).
14. Цепочка (mm_chain.run_chain): 11 узлов DATA INGESTION -> ... -> FINAL OUTPUT,
    статусы PENDING/RUNNING/OK/STOPPED/SKIPPED отражают реальное выполнение,
    прогресс-колбэк для UI; итог mm_chain.explain — 10 пунктов (состояние, режим,
    4 аналитика, Council, прогноз + альтернатива, противоречия, отмена, гейт, итог).

## PATCH v6.1 (починка офлайн-агентов по журналу реального использования)

Аудит 30 реальных прогнозов пользователя (2026-11-12/13) выявил три систематических
смещения. Все исправлены с сохранением архитектуры v6.0; тесты расширены до
17 selftest + 15 интеграций (ALL PASS).

15. ta-тренд (мм_analyst.ta_analyze): при ta_trend_mode=True (по умолчанию) ta голосует
    и в УСТАНОВИВШЕМСЯ тренде — цена и MA28 по одну сторону MA200, откат к MA28 не
    глубже 0.5 ATR. Голос слабее пробойного: conf 0.53-0.68 (пробой 0.55-0.75), p =
    prior + 0.02×dist. Причина: v6.0 требовал свежего пересечения MA28 -> в журнале
    «нейтральны: ta» во всех 30 прогнозах, тренд не голосовал никогда. Инвалидация:
    возврат close за MA28, пересечение MA28×MA200.
16. LSR-перцентиль (mm_live.lsr_calc): account-ratio запрашивается с limit=168 (7 дней
    почасовой истории, реально проверено: BTC 2.23..2.57, медиана 2.39). pct_rank =
    доля истории <= текущего LSR. Аномалия толпы: pct >= lsr_pctl_high (90) -> контр-тренд
    short; pct <= lsr_pctl_low (10) -> long. При < lsr_hist_min_n (24) записей —
    fallback на фиксированные lsr_extreme/lsr_low с пометкой в evidence. Причина:
    база Bybit ~2.5 -> порог 1.4 срабатывал почти всегда -> вечный контр-тренд short.
17. Funding-пол (mm_analyst.deriv_analyze): экстремум перцентиля действует только при
    |funding| >= funding_abs_floor (0.05%). Ниже пола — deriv нейтрален, в evidence
    пометка «базовый уровень, НЕ экстремум». Причина: funding +0.01% (обычная выплата)
    на перцентиле 100 давал deriv short в каждом снимке.
18. mm_news (новый модуль): RSS без ключей (Cointelegraph/Decrypt/NewsBTC/Bitcoin.com),
    RSS 2.0 + Atom, дедуп по нормализованному заголовку, свежесть <= news_max_age_h (48ч),
    тональность по ключевым словам (impact -1..+1, high/medium/low), активы из заголовка.
    fetch_news(budget_sec) — общий бюджет, упавшая лента не валит сбор. Роль НЕ изменилась:
    только мягкий фон sentiment (вес <= news_max_weight 0.10), никогда не сторона сама.
    mm_chain.run_chain(fetch_news=True) собирает новости сам, если их не передали
    (приложение передаёт свои; тесты offline: fetch_news=False по умолчанию).
19. Спецификации контракта (mm_chain): при наличии snap.parts.instruments (instruments-info)
    конфиг Risk Gate обогащается реальными qty_step/min_qty/min_notional_value инструмента
    (вместо общих 0.001/0.001/100); risk-limit капает max_lev до максимального плеча
    инструмента. historical-volatility и mark-price-idx — option-only в V5, НЕ применяются.
