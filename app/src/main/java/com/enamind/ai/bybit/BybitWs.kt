package com.enamind.ai.bybit

import android.util.Log
import com.enamind.ai.core.FaultMonitor
import com.enamind.ai.core.FeedStatus
import com.enamind.ai.core.NetworkHub
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Bybit V5 public WebSocket: tickers / publicTrade / orderbook.50 / allLiquidation / kline.{interval}.
 * Передаёт исходные биржевые временные метки, задержку доставки и последовательные дельты стакана.
 */
class BybitWs(
    private val symbol: String,
    private val listener: Listener,
    private val klineInterval: String = "60",
    private val clockOffsetMsProvider: () -> Long = { 0L }
) {
    interface Listener {
        fun onTicker(t: Ticker)
        fun onTrade(tr: Trade)
        fun onBook(bids: List<BookLevel>, asks: List<BookLevel>)
        fun onLiquidation(l: Liquidation)
        fun onKline(bar: Kline)
        fun onStatus(connected: Boolean, note: String)
        fun onBookDesync(reason: String) {}
        fun onBookDeltas(deltas: List<BookState.LevelDelta>, synced: Boolean, lagMs: Long) {}
        fun onLatency(lagMs: Long) {}
    }

    data class Ticker(
        val symbol: String, val last: Double, val mark: Double,
        val fundingRate: Double, val openInterest: Double,
        val prevPrice24h: Double, val price24hPcnt: Double,
        val turnover24h: Double, val volume24h: Double,
        val nextFundingTs: Long
    )

    data class Trade(val price: Double, val size: Double, val side: String, val tsMs: Long, val lagMs: Long = 0L)

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

    private val client: OkHttpClient = NetworkHub.wsClient(pingIntervalSec = 20L, connectTimeoutSec = 10L)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var closedByUser = false
    @Volatile private var reconnectDelayMs = 1000L
    private val generation = AtomicLong(0L)
    @Volatile private var pendingReconnect: ScheduledFuture<*>? = null

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
        private val reconnectExec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "BybitWs-Reconnect").apply { isDaemon = true }
        }
    }

    fun connect() {
        pendingReconnect?.cancel(false)
        pendingReconnect = null
        closedByUser = false
        val gen = generation.incrementAndGet()
        feedTracker.onConnecting()
        val req = Request.Builder().url(URL).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation.get() || closedByUser) {
                    webSocket.close(1000, "stale")
                    return
                }
                reconnectDelayMs = 1000L
                Log.i(TAG, "WS open $symbol")
                feedTracker.onOpen()
                val args = JSONArray().apply {
                    put("tickers.$symbol")
                    put("publicTrade.$symbol")
                    put("orderbook.50.$symbol")
                    put("allLiquidation.$symbol")
                    put("kline.$klineInterval.$symbol")
                }
                webSocket.send(JSONObject().put("op", "subscribe").put("args", args).toString())
                listener.onStatus(true, "подключено (ждём данные)")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen == generation.get() && !closedByUser) handle(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation.get() || closedByUser) return
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
                scheduleReconnect(gen)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation.get()) return
                feedTracker.onClosed(reason)
                listener.onStatus(false, "закрыто: $reason")
                if (!closedByUser) scheduleReconnect(gen)
            }
        })
    }

    fun close() {
        closedByUser = true
        generation.incrementAndGet()
        pendingReconnect?.cancel(false)
        pendingReconnect = null
        ws?.close(1000, "bye")
        ws = null
    }

    private fun scheduleReconnect(gen: Long) {
        if (closedByUser || gen != generation.get()) return
        val d = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(15000L)
        pendingReconnect?.cancel(false)
        pendingReconnect = reconnectExec.schedule({
            if (!closedByUser && gen == generation.get()) connect()
        }, d, TimeUnit.MILLISECONDS)
    }

    private fun computeLagMs(exchangeTsMs: Long): Long {
        if (exchangeTsMs <= 1_600_000_000_000L) return 0L
        val syncedNow = System.currentTimeMillis() + clockOffsetMsProvider()
        return (syncedNow - exchangeTsMs).coerceIn(0L, 600_000L)
    }

    private fun handle(text: String) {
        try {
            val msg = JSONObject(text)
            val topic = msg.optString("topic", "")
            val msgTs = msg.optLong("ts", 0L)
            if (msgTs > 0L) {
                listener.onLatency(computeLagMs(msgTs))
            }
            when {
                topic.startsWith("tickers") -> {
                    val data = msg.optJSONObject("data") ?: return
                    val t = parseTicker(data)
                    if (t.last > 0 && t.last.isFinite()) {
                        feedTracker.onValidMessage(FeedStatus.Kind.TICKER)
                        FaultMonitor.markHealthy("Bybit WebSocket")
                    }
                    listener.onTicker(t)
                }
                topic.startsWith("publicTrade") -> {
                    val arr = msg.optJSONArray("data") ?: return
                    if (arr.length() > 0) feedTracker.onValidMessage(FeedStatus.Kind.TRADES)
                    for (i in 0 until arr.length()) {
                        val tr = parseTrade(arr.getJSONObject(i))
                        if (tr.price > 0 && tr.size > 0 && tr.price.isFinite() && tr.size.isFinite()) {
                            listener.onTrade(tr)
                        }
                    }
                }
                topic.startsWith("orderbook") -> {
                    val data = msg.optJSONObject("data") ?: return
                    handleBook(msg, data, msgTs)
                }
                topic.startsWith("kline") -> {
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

    private fun parseTrade(d: JSONObject): Trade {
        val ts = d.optLong("T")
        return Trade(
            price = d.optDouble("p"),
            size = d.optDouble("v"),
            side = d.optString("S"),
            tsMs = ts,
            lagMs = computeLagMs(ts)
        )
    }

    private fun handleBook(msg: JSONObject, data: JSONObject, msgTs: Long) {
        val type = msg.optString("type").ifEmpty { data.optString("type") }
        val eventTs = if (msgTs > 0L) msgTs else System.currentTimeMillis()
        val lagMs = computeLagMs(eventTs)
        fun pairs(arr: JSONArray?): List<Pair<Double, Double>> =
            parseLevels(arr).map { it.price to it.qty }
        if (type == "snapshot") {
            val deltas = bookState.snapshotWithDeltas(
                pairs(data.optJSONArray("b")),
                pairs(data.optJSONArray("a")),
                data.optLong("u"),
                eventTs
            )
            feedTracker.onValidMessage(FeedStatus.Kind.BOOK)
            listener.onBookDeltas(deltas, true, lagMs)
            emitBook()
        } else if (type == "delta") {
            val (applied, deltas) = bookState.deltaWithChanges(
                pairs(data.optJSONArray("b")),
                pairs(data.optJSONArray("a")),
                data.optLong("u"),
                data.optLong("seq"),
                eventTs
            )
            if (applied) {
                feedTracker.onValidMessage(FeedStatus.Kind.BOOK)
                if (deltas.isNotEmpty()) {
                    listener.onBookDeltas(deltas, true, lagMs)
                }
                emitBook()
            } else if (!bookState.snapshot().synced) {
                listener.onBookDeltas(emptyList(), false, lagMs)
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
 * времени из WS publicTrade.
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
            .put("long_liq_usd_1h", round2(long1h))
            .put("short_liq_usd_1h", round2(short1h))
            .put("n", items.size)
    }

    private fun round2(x: Double) = Math.round(x * 100.0) / 100.0
}
