# Живые источники ENA-MIND v6.1: только Bybit V5 + публичные индексы

Все эндпоинты проверены живыми запросами 2026-11-12 (день миграции; допроверка новых
эндпоинтов v6.1 — тем же днём). Binance полностью удалён из рабочих источников,
конфигураций, тестов и сообщений об ошибках.

## REST (публичные, БЕЗ API-ключей), реально используется в коде

- `mm_klines.py` — свечи:
  - Bybit V5: `GET /v5/market/kline?category=linear&symbol=BTCUSDT&interval=60&limit=300`
    (interval в минутах; ts в ответе УЖЕ в миллисекундах; fallback на CSV)
- `mm_live.py` — снимок рынка (Bybit V5):
  - `GET /v5/market/recent-trade?category=linear&symbol=...&limit=500` — лента/CVD/дельта
    (поля `price`,`size`,`side` Buy/Sell,`time` ms)
  - `GET /v5/market/orderbook?category=linear&symbol=...&limit=100` — стакан (result.b/a)
  - `GET /v5/market/tickers?category=linear&symbol=...` — funding, OI, mark, last,
    prevPrice24h (одним вызовом)
  - `GET /v5/market/account-ratio?category=linear&symbol=...&period=1h&limit=168` — LSR
    (buyRatio/sellRatio — доля счетов в лонге/шорте; v6.1: limit 168 = 7 дней почасовой
    истории -> перцентиль текущего LSR; аномалия >=90/<=10 перцентиля, fallback 1.4/0.7
    только при <24 записях)
  - `GET /v5/market/open-interest?category=linear&symbol=...&intervalTime=1h&limit=25` —
    история OI, oi_pct_change за ~24ч
  - `GET /v5/market/funding/history?category=linear&symbol=...&limit=90` — история
    funding за 30д (перцентиль 95/5 для deriv; v6.1: абсолютный пол |f|>=0.05%)
  - `GET /v5/market/instruments-info?category=linear&symbol=...` — спецификации контракта
    (v6.1: lotSizeFilter minOrderQty/qtyStep/minNotionalValue/maxMktOrderQty,
    priceFilter.tickSize, leverageFilter.maxLeverage -> честные лоты Risk Gate)
  - `GET /v5/market/risk-limit?category=linear&symbol=...` — лимиты риска
    (v6.1: нижний тир isLowestRisk=1 -> максимальное плечо инструмента, капает max_lev)
  - F&G: `api.alternative.me/fng/?limit=7` — Fear&Greed с дельтами 3/7д (не биржевой)
- `mm_news.py` (v6.1) — новости RSS без ключей: Cointelegraph `/rss`, Decrypt `/feed`,
  NewsBTC `/feed/`, Bitcoin.com `news.bitcoin.com/feed/` (дедуп, свежесть <=48ч,
  тональность по ключевым словам — только мягкий фон sentiment <=0.10)
- `mm_alerts.py` — котировки для алертов: `GET /v5/market/tickers` (lastPrice,
  prevPrice24h — точная база «% за 24ч», fundingRate)
- `mm_forecast.py` — проверка старых прогнозов: klines Bybit (цена закрытия на горизонте)
- `mm_data.py` — кэш/ретраи/статусы через те же эндпоинты

### Проверено и НЕ используется (честная пометка, живой запрос 2026-11-12)

- `GET /v5/market/historical-volatility` — в V5 отвечает ТОЛЬКО для category=option
  (для linear возвращает пустой result.list); волатильность считаем сами (ATR% в mm_regime)
- `GET /v5/market/mark-price-idx` — аналогично option-only; mark уже есть в `tickers`

## WebSocket (wss://stream.bybit.com/v5/public/linear)

- `tickers.BTCUSDT` — цена/funding/OI тиками
- `publicTrade.BTCUSDT` — лента сделок (поля те же: p/v/side/T в WS-формате)
- `orderbook.50.BTCUSDT` — стакан 50 уровней
- `kline.{interval}.{symbol}` — v6.1 (приложение): стриминг свечей для живого графика
  (start/open/high/low/close/volume/confirm; новая свеча при confirm=1)
- `allLiquidation` — ликвидации по всем линейным инструментам (пуш 500 мс).
  ВАЖНО: REST-эндпоинта ликвидаций в V5 НЕТ (`/v5/market/liquidation` — 404);
  топик `liquidation.<symbol>` из старых документов устарел.
  Приложение ENA-MIND собирает ликвидации фоном через `allLiquidation` и передаёт
  статистику в анализ через `extra_parts` (источник помечается `app`).

## Статусы источников (v6)

Каждый источник снимка несёт статус: AVAILABLE (свежие полные данные) /
UNAVAILABLE (не ответил) / STALE (кэш или просроченная свежесть) /
INSUFFICIENT (ответил, но данных меньше, чем требует задача). Грейд A/C/D
(обязательные tape+deriv) сохранён из v5. Грейд D = «нет данных», НЕ «рынок спокоен».

## Ограничения, которые нельзя игнорировать

1. Ликвидации — только WS `allLiquidation`; в REST-снимке статус UNAVAILABLE.
2. Дисбаланс книги спуфится: вторичный признак, подтверждать лентой и CVD.
3. Снапшоты дискретны: время получения фиксируется всегда.
4. Платные API не подключать без проверки стоимости и лимитов. Биржевые API-ключи
   НЕ требуются. (Ключи Z.ai / News API — только в Android-приложении, хранит их
   пользователь сам; в экспортируемые ZIP они не попадают.)
5. Общий бюджет времени `--budget`: при недоступности источников — структура с
   ошибками и grade D, не зависание.
6. `recent-trade` ограничен limit<=1000 на страницу (снимку хватает 500);
   `funding/history` — limit<=200 (берём 90 = 30д×3); `open-interest` — limit<=200
   (берём 25 = ~24ч); `account-ratio` — limit<=500 (берём 168 = 7д перцентиль, v6.1).
7. Инструменты Bybit — USDT-линейные перпы (category=linear). Спот/inverse — вне скоупа.

## Ориентир актуальности (живая проверка при сборке v6.1, 2026-11-12/13, grade=A)

- kline BTCUSDT 60: 300 свечей, ts в ms, 7 полей (…volume, turnover)
- recent-trade: price/size/side/time — CVD считается корректно
- tickers: fundingRate 0.0001 (+0.01% — БАЗА, не экстремум: |f| < пола 0.05%),
  openInterest ~72900, price24hPcnt ≈ -3.5%
- account-ratio (1h, limit=168): 7 дней истории, LSR 2.23..2.57 (медиана 2.39);
  текущий 2.5575 -> перцентиль 98.8 (реальная аномалия лонгования, не «порог 1.4»)
- instruments-info BTCUSDT: minOrderQty 0.001, qtyStep 0.001, minNotionalValue 5,
  maxMktOrderQty 150, tickSize 0.10, maxLeverage 150
- risk-limit BTCUSDT: нижний тир riskLimitValue 300000 USDT, maxLeverage 150.00,
  maintenanceMargin 0.0033
- funding/history: fundingRate 0.00005822, ts в ms
- mm_news: 4 RSS-ленты живые, заголовки с CDATA, даты RFC822
- Живой прогон цепочки v6.1: grade A, режим trend_down, TA SHORT 61% (установившийся
  тренд — v6.0 молчал), DERIV FLAT (funding-пол), SENTIMENT SHORT 61% (LSR-перцентиль
  98.8), Council AGREE short, FORECAST SHORT 75% (HIGH).
Значения дискретны и быстро устаревают — всегда брать свежий снимок.

## Что НЕ требует API-ключей (важно для пользователя)

- Все биржевые данные Bybit V5 public REST/WS: kline, recent-trade, orderbook,
  tickers, account-ratio, open-interest, funding/history — публичные.
- alternative.me/fng — публичный.
- Скилл биржевые ключи не запрашивает и не хранит. Приватные эндпоинты (кошелёк,
  ордера, торговля) — вне области скилла.
