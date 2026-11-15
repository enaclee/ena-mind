Рабочие данные ENA-MIND (создаются автоматически; каталог — data/ рядом со scripts/,
на Android переопределяется env MM5_DATA_DIR на filesDir приложения):
  cache/           кэш ответов API (mm_data.py)
  errors.log       журнал ошибок запросов (mm_data.py)
  live/            снимки Bybit V5: лента/книга/funding/OI(+история)/LSR/F&G (mm_live.py)
  journal.csv      журнал сделок (mm_journal.py); council_status/side/note + regime + p_hat
  forecasts.csv    память прогнозов (mm_forecast.py): store -> verify -> stats
  hypotheses.csv   журнал гипотез (mm_journal.py)
  klines.csv       свечи для бэктеста и ta (ts_ms,open,high,low,close,volume; заголовок обязателен)
  config.json      лимиты ворот, комиссии, приоры по режимам, источники (необязательный)
  alerts.json      правила алертов, образец alerts.example.json (mm_alerts.py)
  state/           risk.json (вердикты дня), alerts.json (что уже сработало)

Иерархия конфигурации: DEFAULTS (mm_config.DEFAULTS) <- data/config.json <- env MM5_*.
Префикс MM5_ сохранён из v5 для совместимости с существующими конфигурациями.
Комиссии настраиваемые (mm_config.fees): Bybit derivatives VIP0 (maker 0.02/taker 0.055),
по умолчанию консервативный taker 0.10 — эталон издержек C=0.176% (см. config.example.json).
Источники: только Bybit V5 + alternative.me F&G (Binance полностью удалён в v6.0).
