---


## v8.1 (ТЗ v2–v6): новые контуры

- **TREND INTELLIGENCE LAYER** (mm_trend): 6 компонентов — multi-timeframe trend,
  market structure (BOS/CHoCH без look-ahead), momentum/acceleration (t-статистика
  наклона), persistence/reversal, regime detection, калибровка. Три раздельных
  вопроса: TREND OUTLOOK ≠ TRADE SETUP ≠ RISK STATUS. Градации bias: STRONG/PLAIN/
  WEAK BULLISH/BEARISH, NEUTRAL/RANGE.
- **ON-CHAIN INTELLIGENCE ENGINE (OCIE)** (mm_onchain_*): второй контур — 10 агентов
  (mempool, exchange netflow, whale transfers, DEX, stablecoins, unlock, bridges,
  activity, anomaly robust z-score, data quality). Дедуп chain_id+tx_hash+log_index;
  биржевые потоки только с provenance-метками; pending ≠ confirmed; перевод ≠ сделка.
- **24H OUTLOOK ENGINE** (mm_outlook24): P(UP/FLAT/DOWN) с фиксированным порогом
  max(0.75%, 1.5σ), сценарии base/bull/bear, новостной классификатор с дедупом
  синдицированных копий, слухи ×0.3, честная маркировка EXPERIMENTAL/NOT CALIBRATED
  до 30 проверенных прогнозов; Brier score.
- **NEURAL EXECUTION GRAPH** (mm_graph): исполняемый DAG A→J (intake → quality →
  features → параллельные специалисты → советы → adversarial review → meta-council
  c ON-CHAIN REVIEW → forecast → output → ledger), бюджеты/таймауты/деградация,
  Evidence Store, trace для UI NEURAL PATH.
- **MARKET DISCOVERY** (mm_discovery): детерминированные детекторы формаций
  (breakout/breakdown, false breakout, retest, compression→expansion, volume-
  confirmed, price-volume divergence) + candidate routing + ранжирование.
- **REGISTRY** (mm_registry): source registry (13 источников с разделением
  configured/reachable/data_received), calculation registry (18 вычислителей с
  версиями и golden-тестами), lineage tracker с point-in-time snapshot.
name: ena-mind
description: >-
  ENA-MIND v8.0 «ЛЕС И ПАУТИНА» — NEURAL FORECAST ENGINE: вероятностный прогноз
  крипто-рынка на 15 минут по 18 аналитическим агентам-ветвям (цена/EMA-RSI-MACD-BB-ATR,
  order flow/CVD трёх бирж, ликвидность стакана/микропрайс, деривативы/funding-OI-LSR-
  ликвидации, кросс-биржа Bybit+Binance+OKX, корреляции с BTC/beta/lead-lag,
  волатильность/режимы, сентимент F&G+новости, статистический AI/логрег-walk-forward,
  каскады ликвидаций, аномалии z-score, опционы Deribit, ончейн mempool, потоки DeFi
  TVL, базис спот/перп+carry, киты Binance >= $100K, суточная структура по часам UTC,
  макро-ротация/доминирование BTC), связанным ПАУТИНОЙ связей (WEB_LINKS, web_coherence
  модулирует уверенность) с Neural Council (кластеры коррелированных сигналов,
  противоречия, NO_SIGNAL) и PREDICTION ENGINE (P(UP)+P(FLAT)+P(DOWN)=100%,
  ожидаемое движение, диапазон, условия отмены). Источники: Bybit V5 (мульти-ТФ
  1/3/5/15/60м) + Binance data-api.binance.vision + OKX + Deribit + DefiLlama TVL +
  mempool.space + CoinGecko global — все публичные, со статусами AVAILABLE/UNAVAILABLE/
  STALE/INSUFFICIENT. Forecast memory 15м: store -> verify по факту -> точность по
  активам/режимам/часам/волатильности, точность и веса агентов (EMA), дрейф, калибровка.
  Сессии прогноза: начальная версия неизменна, обновления каждые ~5с — версии. Режим
  SHADOW: ордера не открываются, приватных ключей бирж нет. Сохранена цепочка v6.1
  (ta/flow/deriv/sentiment -> Council -> Forecast -> Risk Gate, журнал, p_hat).
  Используй при «прогноз на 15 минут», «что покажет BTC/ETH/SOL/XRP/SUI/AVAX/DOGE/OP»,
  «агенты/совет/движок/лес/паутина», «P(UP)», «точность прогнозов», «что на рынке»,
  «проверь сделку», «режим рынка», «журнал», «бэктест», «алерт».
---

# ENA-MIND v8.1 «ЛЕС И ПАУТИНА + ГРАФ»

Аналитическая экосистема с NEURAL FORECAST ENGINE (прогноз на 15 минут), нейронной
цепочкой А→Б (v6.1 сохранена) и обратной связью. Реальные ордера не открывает, не
закрывает и не отправляет — режим SHADOW. Любое решение о сделке остаётся за
пользователем. `${CLAUDE_SKILL_DIR}` — папка, где лежит этот SKILL.md.

Основной источник данных — **Bybit V5**; независимые подтверждения — **Binance**
(data-api.binance.vision) и **OKX**; контекст — **Deribit** (опционы), **DefiLlama**
(TVL), **mempool.space** (ончейн BTC) и **CoinGecko** (макро/доминирование).
Все эндпоинты публичные, API-ключи не нужны.

## Архитектура v8.0 «ЛЕС, ОБМОТАННЫЙ ПАУТИНОЙ»

```
ДАННЫЕ (Bybit WS/REST + Binance WS/aggTrades + OKX WS + Deribit + DefiLlama
         + mempool.space + CoinGecko + alternative.me + RSS)
   -> VALIDATION (grade A/C/D, статусы источников)
   -> FEATURE ENGINEERING (свечи 1/3/5/15/60м, потоки, книга, деривативы, опционы)
   -> REGIME (TREND UP/DOWN, RANGE, HIGH/LOW VOLATILITY, BREAKOUT, UNCERTAIN)
   -> ЛЕС: 18 АГЕНТОВ-ВЕТВЕЙ (PRICE, ORDER FLOW, LIQUIDITY, DERIVATIVES,
      CROSS-EXCHANGE, BTC-CORRELATION, VOLATILITY, SENTIMENT, STATISTICAL AI,
      LIQUIDATION CASCADE, ANOMALY DETECTOR, OPTIONS, ONCHAIN, DEFI FLOW,
      BASIS, WHALE WATCH, SESSION, DOMINANCE)
   -> ПАУТИНА: WEB_LINKS — явные связи агентов; web_analysis() считает
      AGREE/CONFLICT/WEAK связи и web_coherence (согласованность леса)
   -> NEURAL COUNCIL (кластеры сигналов + паутина, противоречия, веса из памяти)
   -> PREDICTION ENGINE (P(UP)/P(FLAT)/P(DOWN)=100%, μ, σ, диапазон 80%;
      web_coherence модулирует уверенность: конфликт паутины гасит, согласие усиливает)
   -> FORECAST 15М (сессии: initial + версии каждые ~5с)
   -> FORECAST MEMORY (store -> verify по факту -> stats/веса/дрейф)
   -> FEEDBACK LOOP (веса агентов корректируются медленно, EMA)
```

- **`mm_sources`** — мультибиржевые источники: Bybit kline мульти-ТФ + резолвер
  инструмента (USDT→USDC честно, без подмены), Binance klines/aggTrades (m=true —
  агрессивная ПРОДАЖА)/depth/ticker/киты (>= $100K, кэш 60с), OKX candles (новые
  сверху — reverse)/trades/funding/OI, Deribit DVOL+книга опционов (кэш 10м),
  DefiLlama chains TVL + дельта по локальной истории, mempool.space (fees/mempool/
  blocks), CoinGecko global (доминирование BTC + дельта по локальной истории, кэш 10м).
  Rate-limit на хост, кэш mm_data, размыкатель 418/429 (кулдаун 5 мин) —
  недоступный источник не ломает систему.
- **`mm_agents`** — 19 агентов (18 ветвей + PREDICTION, v8.0: +ончейн, DeFi-потоки,
  базис, киты, суточная структура, доминирование), контракт
  `{agent,status,timestamp,side,confidence,features,evidence,contradictions,invalidate}`.
  SENTIMENT/ONCHAIN не определяют сторону сами; STATISTICAL_AI = DISABLED, пока модель
  не бьёт базовую линию на walk-forward. Кластеры коррелированных сигналов
  (microstructure/price_based/positioning/external/onchain/statistical) не считаются
  дважды. WEB_LINKS — паутина из 18 связей между агентами; web_coherence в [0,1]
  показывает согласованность леса.
- **`mm_stats`** — логистическая регрессия (чистый Python, L2), z-score, EWMA,
  байесовское обновление, walk-forward без lookahead (признаки только по закрытым
  свечам, стандартизация на train-окне, сравнение с базовой линией). Модель
  включается только при hit-rate > max(0.52, baseline).
- **`mm_engine`** — оркестратор: двухканальная подача (тяжёлые свечи редко +
  лёгкий срез каждые ~5с, merge), сессии 15-минутных прогнозов с версионированием
  (initial неизменна, обновления — версии), Neural Council v2 (кластеры,
  противоречия, NO_SIGNAL при <2 кластерах, паутина web_coherence), автопроверка
  истёкших прогнозов, журнал вычислений, режим SHADOW.
- **`mm_memory`** — память v7 (forecast_memory_v7.jsonl): asset/ts/entry/horizon/
  initial probabilities/model version/agent results/regime/data quality/actual/
  result/error; точность по активам, режимам, часам, полосам волатильности;
  agent accuracy; Brier/калибровка; дрейф (последние 50 против всех); веса агентов
  EMA α=0.25 в [0.5, 1.5] при n≥20.

## Архитектура v6.1 (нейронная цепочка А→Б, сохранена)

```
Рынок (Bybit V5) → Ingestion → Data Check → Regime → 4 Аналитика → Council → Forecast
                                  |                       |            (независим от
                                  v                       v             Risk Gate)
                          статусы источников       Decision (AGREE/NO_TRADE/HALT)
                                                           |
                                  Journal ← Feedback p_hat ← Risk Gate (вето)
                                      |                       |
                                      v                       v
                                 Forecast memory         Output (audit)
                                 (store→verify→stats)
```

- **Ingestion** (`mm_live`, `mm_klines`, `mm_data`, `mm_news`): только Bybit V5 — лента/CVD/дельта
  (`recent-trade`), книга (`orderbook`), funding+OI (`tickers`), LSR-толпа
  (`account-ratio` + 7-дневная история 168ч для перцентиля), история OI за 24ч
  (`open-interest`), история funding за 30д (`funding/history`), спецификации контракта
  (`instruments-info`: лоты/тик/плечо — обогащают Risk Gate честными порогами),
  лимиты риска (`risk-limit`: максимальное плечо инструмента), F&G с дельтами 3/7д
  (alternative.me), новости RSS без ключей (`mm_news`: Cointelegraph/Decrypt/NewsBTC/
  Bitcoin.com; дедуп, свежесть <=48ч, тональность по ключевым словам — только мягкий
  фон). Каждый источник несёт статус AVAILABLE / UNAVAILABLE / STALE / INSUFFICIENT.
  Ликвидации — только WS-топик `allLiquidation` (REST-эндпоинта нет): в снимке статус
  UNAVAILABLE, приложение может передать WS-статистику через `extra_parts`.
  `historical-volatility` и `mark-price-idx` в V5 живут только для категории option —
  для linear не используются (проверено живым запросом 2026-11-12). `--budget` сек.
- **Data Check** (`mm_chain`, узел 2): полнота, актуальность, противоречия, ошибки API,
  достаточность истории (>=201 свечи), дедупликация новостей. Некачественные данные
  не проходят дальше незаметно: grade D останавливает цепочку с указанием причины.
- **Regime** (`mm_regime`): trend_up / trend_down / range / volatile / unknown —
  «психология фазы», влияет на приоры всех аналитиков.
- **Analysis** (`mm_analyst`, 4 НЕЗАВИСИМЫХ модуля):
  - `ta` — тренд/пробой MA28/MA200 с подтверждением импульсом; v6.1: плюс голос
    УСТАНОВИВШЕГОСЯ тренда (цена и MA28 по одну сторону MA200 без свежего пробоя;
    голос слабее пробойного: 0.53-0.68 против 0.55-0.75; фикс «ta молчал весь тренд»);
  - `flow` — лента/CVD/дельта/книга (книга — вторичный, спуфабельный признак);
  - `deriv` — перцентиль funding за 30д (история из Bybit) с АБСОЛЮТНЫМ ПОЛОМ
    |funding|>=0.05% (v6.1: перцентиль 100 при базовой ставке 0.01% — не экстремум),
    изменение OI за 24ч;
  - `sentiment` — толпа: F&G (Extreme >=75/<=25), дельты F&G 3/7д, LSR-ПЕРЦЕНТИЛЬ
    7-дневной истории Bybit (v6.1: аномалия при >=90/<=10; фикс — база Bybit ~2.5,
    фиксированный порог 1.4 срабатывал почти всегда; при <24 набл. — fallback-пороги
    1.4/0.7 с честной пометкой) + мягкий новостной фон (вес <=0.10, новости никогда
    не вызывают HALT и не решают сторону единолично: заголовок ≠ фактическое влияние).
  Каждый выдаёт side ∈ {long, short, flat}, p, confidence, evidence[], invalidate[].
  ok=false → INSUFFICIENT, оценок «на глаз» нет.
- **Council** (`mm_council`, логика v5 сохранена): cross-validation аналитиков,
  конфликт long/short при conf>=0.6 → REVISIT слабейшего, остаточный конфликт →
  NO_TRADE (CONFLICT); мягкий конфликт — слабая сторона отбрасывается с пометкой в
  audit; обязательный модуль INSUFFICIENT → HALT. Регime-приоры + feedback p_hat
  (n>=20 compliant-сделок, корректировка ±0.05). Разногласия не скрываются.
- **Forecast** (`mm_forecast`, узел 5 — ГЛАВНОЕ в v6): прогноз направления LONG/SHORT/
  NEUTRAL, независимый от разрешения Risk Gate. Работает даже при NO_TRADE/BLOCK —
  информация о вероятном направлении не теряется. Уверенность — ОТНОСИТЕЛЬНАЯ сила
  совпадения узлов (band LOW/MEDIUM/HIGH), НЕ калиброванная вероятность. При grade D
  или <2 живых аналитиков — INSUFFICIENT без направления. Всегда: аргументы,
  противоречия, альтернативный сценарий, условия отмены, статус сделки. Прогноз
  никогда не обходит Risk Gate.
- **Risk Gate** (`mm_risk`, логика v5 сохранена): право окончательного вето.
  Council NO_TRADE/HALT → BLOCK; сторона плана ≠ стороне совета → BLOCK; grade D →
  BLOCK; grade C требует p >= 0.6. Экономика: 4 фильтра (target >= 4C, RR >= 2,
  E > 0, C <= 25% стопа), лот, маржа, буфер ликвидации, дневные лимиты, комиссии
  Bybit (настраиваемые). BLOCK окончателен.
- **Output** (`mm_chain.synthesize/explain`): итог из 10 пунктов — состояние рынка,
  режим, 4 аналитика, Council, основной и альтернативный прогноз, противоречия,
  уровни отмены, вердикт ворот, итоговое объяснение. Audit trail доступен полностью.
- **Memory** (`mm_journal`, `mm_feedback`, `mm_forecast`): журнал сделок (edge по
  режимам, calibration, Brier) + p_hat в приоры Council; память прогнозов: store →
  verify (сверка с фактическим движением по klines после горизонта) → stats
  (hit-rate по режимам, повторяющиеся ошибки, систематическое завышение уверенности).
  Единичный успех не считается закономерностью: поправки только при n>=10-20.
  Правила Risk Gate памятью не меняются НИКОГДА.

## Команды

```bash
python3 "${CLAUDE_SKILL_DIR}/tests/run_tests.py"                       # 22 selftest + 19 интеграций, ждём ALL PASS
python3 "${CLAUDE_SKILL_DIR}/tests/run_tests.py" --live               # + живые источники/8 активов/движок/обучение
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_engine.py" --run BTCUSDT      # ПРОГОН NEURAL ENGINE (живой прогноз 15м)
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_engine.py" --selftest         # selftest движка (offline)
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_stats.py" --train --symbol BTCUSDT  # walk-forward обучение модели
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_sources.py" --cross BTCUSDT   # кросс-биржевая сводка (3 биржи)
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_sources.py" --slow            # DefiLlama TVL + mempool.space
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_memory.py" --stats            # память v7: точности/дрейф
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_memory.py" --verify           # закрыть истёкшие 15-м прогнозы
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_chain.py" --symbol BTCUSDT     # ПОЛНАЯ ЦЕПОЧКА v6.1 A->Б
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_chain.py" --symbol BTCUSDT --news  # ...с живым RSS-фоном (mm_news)
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_live.py" --snapshot [--symbol ETHUSDT] [--budget 45]
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_klines.py" --symbol BTCUSDT --interval 60 --limit 300
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_regime.py" --klines data/klines.csv
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_analyst.py" --snap snap.json --klines klines.csv --regime trend_up
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_council.py" --decide --snap snap.json --klines k.csv [--regime trend_up] [--json]
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_forecast.py" --verify          # проверка старых прогнозов по рынку
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_forecast.py" --stats           # память: hit-rate по режимам, ошибки
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_core.py" --price 73700 --stop 0.8 --target 2.2 --risk 1.5 --lev 3
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_risk.py" check '<план JSON>' [--decision decision.json] [--grade A|C|D]
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_journal.py" pre '<план>' --require-gate
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_journal.py" post '<итог>'
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_journal.py" stats              # edge + edge_by_regime + calibration + Brier
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_feedback.py" --report          # p_hat + edge по режимам
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_backtest.py" --csv data/klines.csv [--council] [--regime-aware]
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_alerts.py" add --symbol BTCUSDT --stop 73000 --side long
python3 "${CLAUDE_SKILL_DIR}/scripts/mm_alerts.py" check
```

План для `mm_risk` и `mm_journal pre` — один и тот же JSON:
`{"id","module","pair","side":"long|short","entry","stop","tp1","p","lev","risk_usd"}`
(необязательно: `funding_pct`, `n_funding`, `tp2`, `regime`, `horizon_h`,
`council_status`, `council_side`, `council_note`, `p_hat`).

## Шаги (цепочка от А до Б)

1. **Полный прогон**: `mm_chain --symbol --interval` (в приложении — кнопка анализа и
   вызов Z.ai function calling). Прогресс узлов приходит колбэком, статусы реальные.
2. **Чтение итога**: отчёт из 10 пунктов; при NO_TRADE/HALT — причина и audit, не
   «дожимать» сценарий. Forecast показывает направление даже когда сделка запрещена.
3. **Экономика**: `mm_core` (издержки по сторонам, чистый RR, лот, буфер ликвидации).
   Эталон: стоп 0.8%, цель 2.2%, C=0.176% проходит; стоп 0.7%, цель 2.0% отклоняется.
4. **Risk Gate**: `mm_risk check --decision <совет> --grade <грейд>`; BLOCK окончателен.
5. **Журнал**: только после PASS — `mm_journal pre --require-gate`; после выхода —
   `mm_journal post` (exit/result_usd/scenario_hit/compliant/regime).
6. **Проверка прогнозов**: `mm_forecast --verify` (по истечении горизонтов) и `--stats`.
7. **Обратная связь**: после >= 20 compliant-сделок `mm_feedback --report` — p_hat
   корректирует приоры Council (±0.05). Прогнозная память даёт поправки при n>=10.
8. **Наблюдение**: `mm_alerts` — уровни, стопы с учётом стороны, % за 24ч, funding.

## Правила

- Рабочая вероятность p = regime-приор + 0.025×сила сигнала; при n>=20 compliant-сделок
  и use_feedback_prior=True приор корректируется через p_hat (±0.05). n < 20 — LOW
  CONFIDENCE, вердикта об эдже нет.
- Метрика заявляется только если за ней есть функция (контракт в `SKILL_PATCH.md`).
  Нет данных — INSUFFICIENT, не оценка «на глаз».
- Прогноз (Forecast) не является торговым решением и не обходит Risk Gate.
  Уверенность прогноза — относительная, не калиброванная вероятность; точную
  вероятность не называть.
- Ликвидации: только WS `allLiquidation` (линейные); в REST-снимке статус UNAVAILABLE
  — это честное «нет данных», не «рынок спокоен».
- Дисбаланс книги спуфится: вторичный признак, подтверждать лентой и CVD.
- Бэктест на синтетике (`--demo`) эдж не доказывает. Реальные выводы — по `--csv`
  и OOS-окнам. `--council` показывает цену NO TRADE, `--regime-aware` адаптирует стоп.
- Live-запросы имеют общий бюджет времени (`--budget`): при недоступных источниках
  модули возвращают структуру с ошибками и grade D, а не виснут.
- Новостной фон: заголовок ≠ фактическое влияние на цену. Новости — мягкая поправка
  к sentiment (<=0.10), никогда не основание для сделки и не причина HALT.
- Платные API не подключать без проверки стоимости и лимитов. API-ключи биржи не
  требуются: все биржевые источники публичные (Bybit V5 public, alternative.me F&G).
- Конфликт модулей разрешается только через REVISIT; остаточное противоречие — NO
  TRADE. Система не выдаёт сигнал, противоречащий собственному анализу.
- Каталог данных: по умолчанию `../data`; env `MM5_DATA_DIR` переопределяет (Android).
  Префикс env `MM5_*` сохранён из v5 для совместимости.

## Чего не делает

Не отправляет и не отменяет ордера, не подключает биржевые ключи, не обещает результат,
не подменяет решение пользователя. Это аналитика и учёт, не инвестиционная рекомендация.

## Реализовано в v6.0 (чего не было в v5.0)

- Полное удаление Binance; Bybit V5 — единственная биржа (аудит 2026-11-12);
- Фикс двух багов v5: поля `recent-trade` (price/size/time вместо p/v/T) и таймстампы
  kline (API отдаёт ms, v5 умножала на 1000);
- История funding за 30д (перцентиль теперь на реальных данных Bybit funding/history);
- История OI за 24ч -> oi_pct_change (поле схемы SNAPSHOT наконец заполняется);
- LSR из Bybit account-ratio (раньше источник был только у Binance);
- Статусы источников AVAILABLE/UNAVAILABLE/STALE/INSUFFICIENT (mm_data.source_status);
- Узел Forecast — прогноз направления, независимый от Risk Gate (mm_forecast);
- Память прогнозов: store -> verify по фактическому рынку -> stats по режимам;
- Оркестратор цепочки mm_chain: 11 узлов с реальными статусами, прогресс-колбэк,
  финальный синтез из 10 пунктов;
- Новостной фон как мягкий вход sentiment (вес <=0.10, дедупликация);
- MM5_DATA_DIR — переопределяемый каталог данных (Android/Chaquopy).

## Реализовано в v6.1 (починка офлайн-агентов по журналу реального использования)

- `ta` видит УСТАНОВИВШИЙСЯ тренд: раньше сигнал требовал свежего пробоя MA28, и ta
  молчал весь тренд (журнал: 30 прогнозов подряд «нейтральны: ta»); теперь структура
  тренда (цена и MA28 по одну сторону MA200) — голос слабее пробойного;
- LSR-перцентиль за 7д истории Bybit account-ratio (168 набл.): база Bybit ~2.5,
  фиксированный порог 1.4 означал «толпа лонгует» почти всегда -> вечный контр-тренд
  short; аномалия теперь >=90/<=10 перцентиля, fallback 1.4/0.7 только при <24 набл.;
- Абсолютный пол funding |f|>=0.05%: перцентиль 100 при базовой ставке 0.01% больше
  не считается экстремумом (реальный кейс: deriv голосовал short на каждом снимке);
- Модуль `mm_news`: RSS без ключей (4 ленты), дедуп, свежесть <=48ч, тональность —
  для sentiment-фона; цепочка собирает новости сама при `--news` / fetch_news;
- `instruments-info` + `risk-limit`: честные лоты (min_qty/qty_step/min_notional) и
  максимальное плечо инструмента обогащают Risk Gate вместо общих порогов;
- Тесты: 17 selftest + 15 интеграций (новые: ta-видит-тренд, lsr-перцентиль,
  funding-пол, news-flow, память назад store->verify->stats->поправка уверенности).

## Реализовано в v7.0 (NEURAL FORECAST ENGINE)

- **Прогноз на 15 минут** — главная функция: P(UP)+P(FLAT)+P(DOWN)=100% строго,
  ожидаемое движение μ, σ15м, диапазон 80%, сила сигнала, неопределённость
  (энтропия), аргументы, альтернативный сценарий, условия отмены. Порог FLAT
  учитывает шум (σ) и издержки (комиссии round-trip + проскальзывание).
- **10 независимых агентов** с единым JSON-контрактом; SENTIMENT никогда не
  определяет направление; STATISTICAL_AI отключён до честного walk-forward
  превосходства над базовой линией; кластеры коррелированных сигналов
  (микроструктура/ценовые/позиционирование) не учитываются дважды.
- **Neural Council v2**: кластеры, противоречия, подавленные агенты, свежесть, паутина (web_coherence)
  данных, NO_SIGNAL при <2 подтверждающих кластерах.
- **5 источников**: Bybit V5 (мульти-ТФ), Binance vision, OKX, DefiLlama,
  mempool.space; rate-limit, кэш, размыкатель 418/429, честные статусы.
- **Forecast memory v7**: полные записи прогнозов с агентами/режимом/качеством,
  автопроверка через 15 минут по факту, точность по активам/режимам/часам/
  волатильности, agent accuracy, Brier/калибровка, дрейф, веса агентов (EMA).
- **Сессии прогнозов**: 15-минутное окно = сессия; начальная версия пишется в
  память и не перезаписывается; пересчёты каждые ~5с — версии той же сессии.
- **Walk-forward обучение** (mm_stats --train): признаки только по закрытым
  свечам, стандартизация на train-окне, сравнение с базовой линией; модель
  включается только при реальном превосходстве.
- **Watchlist 8 активов**: BTC, ETH, SOL, XRP, SUI, AVAX, DOGE, OP — инструмент
  резолвится честно (USDT, при отсутствии USDC — с пометкой, без подмены).
- Цепочка v6.1 (4 аналитика → Council → Forecast → Risk Gate, журнал, p_hat,
  бэктест, алерты) сохранена полностью — регрессий нет (17 старых selftest'ов
  проходят вместе с 5 новыми).

## Пока не реализовано (не заявлять как метрики)

Rolling beta и корреляции BTC/altcoin, объёмные кластеры/POC, отдельная функция p_hat
как самостоятельный API (доступна через `mm_feedback.p_hat()`), генератор отчёта из
20 пунктов ТЗ (есть отчёт из 10 пунктов в mm_chain.explain). Ставить INSUFFICIENT
или считать вручную с явной пометкой.
