# ENA-MIND v6.1 (Ecosystem with Neural Chain and Feedback Loops)

Нейронная цепочка анализа (А→Б):

  Рынок (Bybit V5) -> Ingestion -> Data Check -> Regime -> 4 Аналитика -> Council
                                        |                       |
                                        v                       v
                              статусы источников      Decision (AGREE/NO_TRADE/HALT)
                                        |                       |
                                        v                       v
                                    Forecast <------------- Risk Gate (вето)
                                 (прогноз ≠ сделка)            |
                                        |                      v
                                        v                   Output (10 пунктов)
        Journal -> Feedback p_hat -> Council (приоры)   Forecast memory
                                                          (store -> verify -> stats)

4 аналитика — НЕЗАВИСИМЫЕ голоса (ta/flow/deriv/sentiment). Council проверяет их выводы
друг против друга, конфликт -> пересмотр -> NO TRADE. Forecast оценивает относительное
направление рынка даже когда сделка запрещена — но никогда не обходит Risk Gate.
Regime (фаза) влияет на приоры. После 20 compliant-сделок p_hat из журнала корректирует
приоры; память прогнозов (verify/stats) выявляет повторяющиеся ошибки по режимам.

Единственная биржа — Bybit V5 (Binance полностью удалён в v6.0). Все биржевые
эндпоинты публичные, без ключей. Реальные ордера не открываются и не отправляются.

Запуск (только стандартная библиотека Python; проверено на Python 3.12):
  python tests/run_tests.py            -> ALL PASS (16 selftest + 10 интеграций)
  python scripts/mm_chain.py           -> ПОЛНАЯ ЦЕПОЧКА (отчёт из 10 пунктов)
  python scripts/mm_live.py --snapshot -> живой срез Bybit V5 (grade + статусы)
  python scripts/mm_klines.py --symbol BTCUSDT --interval 60 --limit 300
  python scripts/mm_forecast.py --verify / --stats   -> проверка и память прогнозов
  python scripts/mm_council.py --decide --snap <snap.json> --klines <k.csv> [--regime ...]
  python scripts/mm_core.py --price 73700 --stop 0.8 --target 2.2 --risk 1.5
  python scripts/mm_risk.py check '<план>' [--decision council.json] [--grade A|C|D]
  python scripts/mm_journal.py pre '<план>' --require-gate / post '<итог>' / stats
  python scripts/mm_feedback.py --report
  python scripts/mm_backtest.py --csv data/klines.csv [--council] [--regime-aware]
  python scripts/mm_alerts.py add --symbol BTCUSDT --stop 73000 --side long / check

Состав:
  scripts/  mm_schema, mm_config, mm_data, mm_klines, mm_live, mm_regime, mm_analyst,
            mm_council, mm_forecast (узел 5), mm_chain (оркестратор), mm_core, mm_risk,
            mm_journal, mm_feedback, mm_backtest, mm_alerts
  SKILL.md        описание навыка (триггеры, архитектура, поток, правила)
  SKILL_PATCH.md  правила и контракт документация-код (v6.0)
  SOURCES_LIVE.md источники живых данных Bybit V5 и их ограничения
  tests/          run_tests.py (16 selftest + 10 интеграций; полностью offline)
  data/           рабочие данные, создаются при запуске (см. data/README.txt);
                  MM5_DATA_DIR переопределяет каталог (Android/Chaquopy)

Каталог данных: env MM5_DATA_DIR -> ../data (по умолчанию). Префикс env MM5_*
сохранён из v5 ради совместимости конфигураций.

Пример цепочки (конфликт):
  TA-long + Flow-short + Sentiment-short -> Council: NO_TRADE (CONFLICT) ->
  Risk Gate: BLOCK -> но FORECAST: SHORT (направление сохранено, сделки нет).
Пример цепочки (согласие):
  TA-long + Flow-long + Deriv-flat + Sentiment-flat -> Council: AGREE long p~0.61 ->
  Forecast: LONG (MEDIUM) -> Risk Gate: PASS (лот/риск/маржа) -> журнал pre.
Пример цепочки (психология):
  F&G=82 Extreme Greed + LSR=1.6 (толпа лонгует) в range -> sentiment short ->
  Council: AGREE short (толпа перегрета -> контр-тренд). Ворота проверяют экономику.

Риск-модуль аналитический: реальные ордера не открывает и не закрывает.

## v7.1 (13.11.2026) — стабильность, фон, подпись (уровень Android-приложения)

Ядро Python (mm_*-модули) не менялось — исправления и новые возможности на стороне
Kotlin-приложения:

- **Фикс вылета при включении экрана движка** (реальные устройства, MIUI): все вызовы
  Python-ядра переведены с главного потока в фоновые (старт Chaquopy, журнал/память
  экрана движка, анализ, AI-чат). Ранее конвейер 10 агентов держал GIL, главный поток
  виснул → ANR → система убивала приложение («вылет при нажатии на движок»).
- **CrashGuard**: любое необработанное исключение любого потока пишется в
  `crash_report.log` (последние 3); просмотр в Настройках → Skill → Диагностика.
  Если приложение упадёт снова — точный стек будет доступен без logcat.
- **Фоновое накопление памяти**: foreground-сервис (уведомление «движок работает»)
  держит процесс живым в свёрнутом приложении — конвейер, автопроверка прогнозов и
  запись памяти v7 продолжаются в фоне. Переключатель в Настройках (вкл по умолчанию).
  MIUI: отключить «Оптимизацию батареи» для фоновой работы.
- **Подпись экспортных ZIP (ECDSA P-256, Android Keystore)**: манифест SHA-256 всех
  файлов + подпись устройства. Импорт проверяет подпись и целостность; чужой или
  неподписанный архив требует явного подтверждения с отпечатком ключа (раньше
  исполнялся любой ZIP молча).
- Ошибка инициализации Python теперь видна на экране движка (баннер с причиной),
  а не «тихий» пустой экран.

## v7.2 (13.11.2026) — новые ветви разума, работа над ошибками

- **13 агентов** (было 10): добавлены LIQUIDATION CASCADE (каскады ликвидаций —
  топливо движения, доминирование сторон и превышение базовой линии 24ч),
  ANOMALY DETECTOR (z-оценки объёма/движения, расширение ATR — аномалия без
  направления не торгуется), OPTIONS ANALYST (Deribit: DVOL против 7д среднего,
  перекос IV put/call, PCR по OI ближайшей экспирации; бэкфилл раз в 10 минут,
  только BTC/ETH).
- **Исправлен баг ликвидаций**: Kotlin передавал long_liq_usd_1h/short_liq_usd_1h,
  Python читал long_usd_1h/short_usd_1h — данные не доходили до деривативного
  агента. Теперь оба формата читаются, каскадный агент работает на реальной ленте.
- **Версионный страж override-скриптов**: импортированный старый скилл (ZIP v6/v7.0)
  больше не затеняет модули нового APK — при несовпадении версий override
  игнорируется с явной пометкой (конфликт файлов и скриптов исключён).
- **Экспорт-диагностика**: в ZIP теперь едет engine_diagnostics.json (журнал движка,
  статус, память) + crash_report.log + файлы движка (forecast_memory_v7, веса, модель).
- mm_sources: источник deribit_options с кэшем 10 минут; парсер книги опционов
  вынесен в чистую функцию и покрыт офлайн-тестом.
- Все тесты: ALL PASS (22 selftest + 19 интеграций + новые проверки трёх агентов).
