package com.enamind.ai.bybit

import android.util.Log
import com.enamind.ai.core.FaultMonitor
import com.enamind.ai.core.FeedStatus
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Bybit V5 public WebSocket: tickers / publicTrade / orderbook.50 / allLiquidation /
 * kline.{interval} (v6.1: живой стриминг свечей на график).
 * Ликвидации существуют ТОЛЬКО здесь (REST-эндпоинта нет — аудит 2026-11-12),
 * поэтому статистика копится фоном и передаётся в анализ через extra_parts.
 *
 * v8.1 (ТЗ §1.3): раздельные честные статусы по ТЕМАМ через FeedStatus.Tracker —
 * LIVE только после валидного payload по конкретной теме, плюс счётчики событий,
 * возраст данных и текст ошибки. onStatus остаётся для совместимости (сводный статус).
 * v8.1 (ТЗ §14): orderbook собирается snapshot+delta через BookState c sequence
 * guard — дельты после разрыва sequence отбрасываются до нового snapshot.
 */
class BybitWs(
    private val symbol: String,
    private val listener: Listener,
    private val klineInterval: String = "60"
) {
    interface Listener {
        fun onTicker(t: Ticker)
        fun onTrade(tr: Trade)
        fun onBook(bids: List<BookLevel>, asks: List<BookLevel>)
        fun onLiquidation(l: Liquidation)
        /** v6.1: свеча из топика kline.{interval}.{symbol}. confirm=true — свеча закрылась. */
        fun onKline(bar: Kline)
        fun onStatus(connected: Boolean, note: String)
        /** v8.1: книга рассинхронизирована — нужен новый snapshot (UI/движок узнают честно). */
        fun onBookDesync(reason: String) {}
    }

    data class Ticker(
        val symbol: String, val last: Double, val mark: Double,
        val fundingRate: Double, val openInterest: Double,
        val prevPrice24h: Double, val price24hPcnt: Double,
        val turnover24h: Double, val volume24h: Double,
        val nextFundingTs: Long
    )

    data class Trade(val price: Double, val size: Double, val side: String, val tsMs: Long)

    data class BookLevel(val price: Double, val qty: Double)

    data class Liquidation(
        val symbol: String, val side: String, val price: Double,
        val size: Double, val tsMs: Long
    )

    /** [tsMs, open, high, low, close, volume, confirm] — формат совместим с графиком. */
    data class Kline(
        val tsMs: Long, val open: Double, val high: Double, val low: Double,
        val close: Double, val volume: Double, val confirm: Boolean
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WS живёт без таймаута чтения
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var closedByUser = false
    private var reconnectDelayMs = 1000L

    /** v8.1: честные статусы по темам (§1.3) + книга snapshot/delta (§14). */
    val feedTracker = FeedStatus.Tracker(
        setOf(
            FeedStatus.Kind.TICKER, FeedStatus.Kind.TRADES, FeedStatus.Kind.BOOK,
            FeedStatus.Kind.KLINE, FeedStatus.Kind.LIQUIDATION
        )
    )
    private val bookState = BookState(maxLevels = 50)

    companion object {
        const val URL = "wss://stream.bybit.com/v5/public/linear"
        private const val TAG = "BybitWs"
    }

    fun connect() {
        closedByUser = false
        feedTracker.onConnecting()
        val req = Request.Builder().url(URL).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelayMs = 1000L
                Log.i(TAG, "WS open $symbol")
                feedTracker.onOpen()
                val args = JSONArray().apply {
                    put("tickers.$symbol")
                    put("publicTrade.$symbol")
                    put("orderbook.50.$symbol")
                    put("allLiquidation.$symbol")
                    put("kline.$klineInterval.$symbol") // v6.1: живые свечи для графика
                }
                webSocket.send(JSONObject().put("op", "subscribe").put("args", args).toString())
                listener.onStatus(true, "подключено (ждём данные)")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS fail: ${t.message}")
                feedTracker.onFailure(t.message)
                FaultMonitor.recordFault(
                    component = "Bybit WebSocket",
                    kind = FaultMonitor.Kind.WEBSOCKET,
                    severity = FaultMonitor.Severity.WARNING,
                    message = t.message ?: "Разрыв WebSocket соединения",
                    recovery = "Авто-переподключение и резервный поток Binance/OKX"
                )
                listener.onStatus(false, t.message ?: "ошибка сети")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                feedTracker.onClosed(reason)
                listener.onStatus(false, "закрыто: $reason")
                if (!closedByUser) scheduleReconnect()
            }
        })
    }

    fun close() {
        closedByUser = true
        ws?.close(1000, "bye")
        ws = null
    }

    private fun scheduleReconnect() {
        if (closedByUser) return
        val d = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(15000L)
        Thread {
            try {
                Thread.sleep(d)
                if (!closedByUser) connect()
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handle(text: String) {
        try {
            val msg = JSONObject(text)
            val topic = msg.optString("topic", "")
            // ВАЖНО: data бывает объектом (tickers/orderbook) и массивом (publicTrade/
            // kline/allLiquidation) — ранний return по optJSONObject глотал массивы
            // (v6.0-баг: лента сделок в UI не обновлялась).
            when {
                topic.startsWith("tickers") -> {
                    val data = msg.optJSONObject("data") ?: return
                    val t = parseTicker(data)
                    if (t.last > 0 && t.last.isFinite()) { // валидный payload -> LIVE (§1.3)
                        feedTracker.onValidMessage(FeedStatus.Kind.TICKER)
                        FaultMonitor.markHealthy("Bybit WebSocket")
                    }
                    listener.onTicker(t)
                }
                topic.startsWith("publicTrade") -> {
                    val arr = msg.optJSONArray("data") ?: return
                    if (arr.length() > 0) feedTracker.onValidMessage(FeedStatus.Kind.TRADES)
                    for (i in 0 until arr.length()) listener.onTrade(parseTrade(arr.getJSONObject(i)))
                }
                topic.startsWith("orderbook") -> {
                    val data = msg.optJSONObject("data") ?: return
                    handleBook(msg, data)
                }
                topic.startsWith("kline") -> {
                    // v6.1: [{start(ms), open, high, low, close, volume, confirm, ...}]
                    val arr = msg.optJSONArray("data") ?: return
                    if (arr.length() > 0) feedTracker.onValidMessage(FeedStatus.Kind.KLINE)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        listener.onKline(
                            Kline(
                                tsMs = o.optLong("start"),
                                open = o.optDouble("open"),
                                high = o.optDouble("high"),
                                low = o.optDouble("low"),
                                close = o.optDouble("close"),
                                volume = o.optDouble("volume"),
                                confirm = o.optBoolean("confirm", false)
                            )
                        )
                    }
                }
                topic.startsWith("allLiquidation") -> {
                    // формат v5: T (ts ms), s (symbol), S (side Buy/Sell), v (size), p (price)
                    // Side=Sell — ликвидирован лонг, Side=Buy — ликвидирован шорт
                    val arr = msg.optJSONArray("data") ?: return
                    if (arr.length() > 0) feedTracker.onValidMessage(FeedStatus.Kind.LIQUIDATION)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        listener.onLiquidation(
                            Liquidation(
                                o.optString("s", o.optString("symbol")),
                                o.optString("S", o.optString("side")),
                                o.optDouble("p", o.optDouble("price")),
                                o.optDouble("v", o.optDouble("size")),
                                o.optLong("T")
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WS parse: ${e.message}")
            FaultMonitor.recordFault(
                component = "Bybit WS Parser",
                kind = FaultMonitor.Kind.WEBSOCKET,
                severity = FaultMonitor.Severity.WARNING,
                message = (e.message ?: "ошибка разбора WS пакета").take(140),
                recovery = "Пакет пропущен без остановки потока"
            )
        }
    }

    private fun optFiniteDouble(d: JSONObject, key: String): Double {
        if (!d.has(key) || d.isNull(key)) return Double.NaN
        val raw = d.optString(key, "").trim()
        if (raw.isNotEmpty()) {
            val parsed = raw.toDoubleOrNull()
            if (parsed != null) return if (parsed.isFinite()) parsed else Double.NaN
        }
        val v = d.optDouble(key, Double.NaN)
        return if (v.isFinite()) v else Double.NaN
    }

    private fun parseTicker(d: JSONObject): Ticker {
        val rawPcnt = optFiniteDouble(d, "price24hPcnt")
        return Ticker(
            symbol = d.optString("symbol"),
            last = optFiniteDouble(d, "lastPrice"),
            mark = optFiniteDouble(d, "markPrice"),
            fundingRate = optFiniteDouble(d, "fundingRate"),
            openInterest = optFiniteDouble(d, "openInterest"),
            prevPrice24h = optFiniteDouble(d, "prevPrice24h"),
            price24hPcnt = if (rawPcnt.isFinite()) rawPcnt * 100.0 else Double.NaN,
            turnover24h = optFiniteDouble(d, "turnover24h"),
            volume24h = optFiniteDouble(d, "volume24h"),
            nextFundingTs = d.optLong("nextFundingTime")
        )
    }

    private fun parseTrade(d: JSONObject): Trade = Trade(
        price = d.optDouble("p"),
        size = d.optDouble("v"),
        side = d.optString("S"),
        tsMs = d.optLong("T")
    )

    private fun handleBook(msg: JSONObject, data: JSONObject) {
        // В Bybit V5 поле "type" ("snapshot"/"delta") находится на верхнем уровне msg!
        val type = msg.optString("type").ifEmpty { data.optString("type") }
        // Bybit V5: snapshot = {u, seq, b, a}; delta = {u, seq, b, a}. Книга собирается
        // через BookState: разрыв u -> DESYNC и ожидание нового snapshot.
        fun pairs(arr: JSONArray?): List<Pair<Double, Double>> =
            parseLevels(arr).map { it.price to it.qty }
        if (type == "snapshot") {
            bookState.snapshot(pairs(data.optJSONArray("b")), pairs(data.optJSONArray("a")), data.optLong("u"))
            feedTracker.onValidMessage(FeedStatus.Kind.BOOK)
            emitBook()
        } else if (type == "delta") {
            val applied = bookState.delta(
                pairs(data.optJSONArray("b")),
                pairs(data.optJSONArray("a")),
                data.optLong("u"),
                data.optLong("seq")
            )
            if (applied) {
                feedTracker.onValidMessage(FeedStatus.Kind.BOOK)
                emitBook()
            } else if (!bookState.snapshot().synced) {
                listener.onBookDesync("sequence gap (u=${data.optLong("u")}) — ждём snapshot")
            }
        }
    }

    private fun emitBook() {
        val snap = bookState.snapshot()
        if (snap.synced) {
            listener.onBook(
                snap.bids.map { BookLevel(it.first, it.second) },
                snap.asks.map { BookLevel(it.first, it.second) }
            )
        }
    }

    /** v8.1: доступ к честному состоянию книги (движок/аналитика). */
    fun bookSnapshot(): BookState.Book = bookState.snapshot()

    private fun parseLevels(arr: JSONArray?): List<BookLevel> {
        if (arr == null) return emptyList()
        val out = ArrayList<BookLevel>(arr.length())
        for (i in 0 until arr.length()) {
            val l = arr.getJSONArray(i)
            out.add(BookLevel(l.optDouble(0), l.optDouble(1)))
        }
        return out
    }
}

/**
 * Rolling-агрегатор ленты сделок: CVD, дельта, агрессия — Order Flow в реальном
 * времени из WS publicTrade (аналог tape_calc из mm_live, для UI).
 */
class TapeAggregator(private val window: Int = 500) {
    private val trades = ArrayDeque<BybitWs.Trade>(window)

    @Synchronized
    fun add(t: BybitWs.Trade) {
        if (trades.size >= window) trades.removeFirst()
        trades.addLast(t)
    }

    @Synchronized
    fun stats(): TapeStats {
        var buy = 0.0
        var sell = 0.0
        var pFirst: Double? = null
        var pLast: Double? = null
        for (t in trades) {
            if (t.side == "Buy") buy += t.size else sell += t.size
            if (pFirst == null) pFirst = t.price
            pLast = t.price
        }
        val tot = buy + sell
        val cvd = buy - sell
        val priceChangePct = if (pFirst != null && pFirst > 0 && pLast != null)
            100.0 * (pLast - pFirst) / pFirst else 0.0
        return TapeStats(
            n = trades.size, buyQty = buy, sellQty = sell, cvd = cvd,
            deltaPct = if (tot > 0) 100.0 * cvd / tot else 0.0,
            priceChangePct = priceChangePct,
            divergence = (cvd > 0 && priceChangePct < 0) || (cvd < 0 && priceChangePct > 0)
        )
    }

    data class TapeStats(
        val n: Int, val buyQty: Double, val sellQty: Double, val cvd: Double,
        val deltaPct: Double, val priceChangePct: Double, val divergence: Boolean
    )
}

/**
 * Агрегатор ликвидаций allLiquidation: суммы по символу за скользящие окна.
 */
class LiquidationAggregator {
    private val items = ArrayList<BybitWs.Liquidation>()
    private val hourMs = 3600_000L

    @Synchronized
    fun add(l: BybitWs.Liquidation) {
        items.add(l)
        if (items.size > 5000) items.subList(0, items.size - 4000).clear()
    }

    @Synchronized
    fun stats(symbol: String, nowMs: Long): JSONObject {
        var sum1h = 0.0
        var sum24h = 0.0
        var long1h = 0.0
        var short1h = 0.0
        for (l in items) {
            if (l.symbol != symbol) continue
            val usd = abs(l.price * l.size)
            if (nowMs - l.tsMs <= hourMs) {
                sum1h += usd
                if (l.side == "Sell") long1h += usd else short1h += usd
            }
            if (nowMs - l.tsMs <= 24 * hourMs) sum24h += usd
        }
        return JSONObject()
            .put("ok", true)
            .put("sum_usd_1h", round2(sum1h))
            .put("sum_usd_24h", round2(sum24h))
            .put("long_liq_usd_1h", round2(long1h))   // ликвидации лонгов (Sell)
            .put("short_liq_usd_1h", round2(short1h)) // ликвидации шортов (Buy)
            .put("n", items.size)
    }

    private fun round2(x: Double) = Math.round(x * 100.0) / 100.0
}
