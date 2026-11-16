package com.enamind.ai.bybit

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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Binance public market WebSocket + Diff Depth синхронизация стакана:
 * Combined stream: @aggTrade, @kline_{tf}, @depth@100ms (последовательные дельты), @ticker.
 *
 * Восстановление локального стакана:
 *  1) Буферизация событий @depth@100ms (с полями U, u, E, b, a).
 *  2) Запрос REST /api/v3/depth?limit=500 -> lastUpdateId + snapshot.
 *  3) Отбрасывание событий с u <= lastUpdateId и проверка первого моста
 *     (U <= lastUpdateId + 1 && u >= lastUpdateId + 1).
 *  4) Проверка непрерывности U/u -> при потере пакетов честный DESYNC и ресинк.
 */
class BinanceWs(
    private val symbol: String,
    private val listener: Listener,
    private val klineInterval: String = "60",
    private val clockOffsetMsProvider: () -> Long = { 0L }
) {
    interface Listener {
        fun onFlow(flow: FlowStats)
        fun onPrice(price: Double)
        fun onDepth(bids: List<Pair<Double, Double>>, asks: List<Pair<Double, Double>>)
        fun onStatus(connected: Boolean, note: String)
        fun onKline(bar: BybitWs.Kline) {}
        fun onTicker24h(price: Double, change24hPct: Double, volume24h: Double, quoteVolume24h: Double) {}
        fun onTrade(price: Double, qty: Double, isAggressiveBuy: Boolean, exchangeTsMs: Long, lagMs: Long) {}
        fun onBookDeltas(deltas: List<BookState.LevelDelta>, synced: Boolean, lagMs: Long) {}
        fun onBookDesync(reason: String) {}
    }

    data class FlowStats(
        val n: Int, val buyQty: Double, val sellQty: Double,
        val cvd: Double, val deltaPct: Double, val ageSec: Double,
        val priceChangePct: Double = 0.0,
        val divergence: Boolean = false
    )

    @Volatile private var ws: WebSocket? = null
    @Volatile private var reconnectMs = 2000L
    @Volatile private var closed = false
    private val generation = AtomicLong(0L)
    @Volatile private var pendingReconnect: ScheduledFuture<*>? = null
    private val trades = ArrayDeque<Trade>()
    @Volatile private var lastTradeTs = 0L

    val diffBook = BinanceDiffBookSync(maxLevels = 120)
    private val snapshotFetching = AtomicBoolean(false)
    private val lastSnapshotAttemptMs = AtomicLong(0L)

    private data class Trade(val ts: Long, val buy: Boolean, val qty: Double, val price: Double)

    private val http: OkHttpClient = NetworkHub.wsClient(pingIntervalSec = 20L, connectTimeoutSec = 12L)
    private val restHttp: OkHttpClient = NetworkHub.restClient(connectTimeoutSec = 6L, readTimeoutSec = 8L, writeTimeoutSec = 8L)

    private val bnSym = BybitRest.toBinanceSymbol(symbol)
    private val lower = bnSym.lowercase()
    private val bnIv = BybitRest.toBinanceInterval(klineInterval)
    private val streams = "$lower@aggTrade/$lower@kline_$bnIv/$lower@depth@100ms/$lower@ticker"

    fun connect() {
        pendingReconnect?.cancel(false)
        pendingReconnect = null
        closed = false
        val gen = generation.incrementAndGet()
        diffBook.reset()
        val req = Request.Builder()
            .url("wss://data-stream.binance.vision/stream?streams=$streams")
            .build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation.get() || closed) {
                    webSocket.close(1000, "stale")
                    return
                }
                reconnectMs = 2000L
                listener.onStatus(true, "Binance WS: поток подключён")
                triggerRestSnapshotAsync()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation.get() || closed) return
                try {
                    handle(text)
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation.get() || closed) return
                listener.onStatus(false, "Binance WS: ${t.message ?: "обрыв"}")
                diffBook.reset()
                scheduleReconnect(gen)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation.get()) return
                listener.onStatus(false, "Binance WS: закрыт")
                diffBook.reset()
                if (!closed) scheduleReconnect(gen)
            }
        })
    }

    private fun computeLagMs(exchangeTsMs: Long): Long {
        if (exchangeTsMs <= 1_600_000_000_000L) return 0L
        val syncedNow = System.currentTimeMillis() + clockOffsetMsProvider()
        return (syncedNow - exchangeTsMs).coerceIn(0L, 600_000L)
    }

    private fun handle(text: String) {
        val root = JSONObject(text)
        val data = root.optJSONObject("data") ?: return
        val stream = root.optString("stream")
        when {
            stream.endsWith("@aggTrade") -> {
                val px = data.optString("p").toDoubleOrNull() ?: data.optDouble("p", 0.0)
                val qty = data.optString("q").toDoubleOrNull() ?: data.optDouble("q", 0.0)
                val isBuyerMaker = data.optBoolean("m", false)
                val ts = data.optLong("T")
                if (!px.isFinite() || !qty.isFinite() || px <= 0.0 || qty <= 0.0) return
                lastTradeTs = ts
                val lagMs = computeLagMs(ts)
                synchronized(trades) {
                    trades.addLast(Trade(ts, !isBuyerMaker, qty, px))
                    while (trades.size > 600) trades.removeFirst()
                }
                listener.onTrade(px, qty, !isBuyerMaker, ts, lagMs)
                listener.onFlow(stats())
                listener.onPrice(px)
            }
            stream.contains("@kline_") -> {
                val k = data.optJSONObject("k") ?: return
                val ts = k.optLong("t")
                val o = k.optString("o").toDoubleOrNull() ?: k.optDouble("o", 0.0)
                val h = k.optString("h").toDoubleOrNull() ?: k.optDouble("h", 0.0)
                val l = k.optString("l").toDoubleOrNull() ?: k.optDouble("l", 0.0)
                val c = k.optString("c").toDoubleOrNull() ?: k.optDouble("c", 0.0)
                val v = k.optString("v").toDoubleOrNull() ?: k.optDouble("v", 0.0)
                val closedBar = k.optBoolean("x", false)
                if (ts > 0 && o > 0 && c > 0 && listOf(o, h, l, c, v).all { it.isFinite() }) {
                    listener.onKline(BybitWs.Kline(ts, o, h, l, c, v, closedBar))
                }
            }
            stream.endsWith("@ticker") -> {
                val c = data.optString("c").toDoubleOrNull() ?: data.optDouble("c", 0.0)
                val p = data.optString("P").toDoubleOrNull() ?: data.optDouble("P", Double.NaN)
                val v = data.optString("v").toDoubleOrNull() ?: data.optDouble("v", 0.0)
                val q = data.optString("q").toDoubleOrNull() ?: data.optDouble("q", 0.0)
                if (c > 0 && c.isFinite() && p.isFinite()) {
                    listener.onTicker24h(c, p, v, q)
                }
            }
            stream.contains("@depth") -> {
                val eventTs = data.optLong("E", 0L).takeIf { it > 0L } ?: System.currentTimeMillis()
                val lagMs = computeLagMs(eventTs)
                val firstU = data.optLong("U", -1L)
                val finalU = data.optLong("u", -1L)
                val bids = parsePairs(data.optJSONArray("b") ?: data.optJSONArray("bids"))
                val asks = parsePairs(data.optJSONArray("a") ?: data.optJSONArray("asks"))

                if (firstU > 0L && finalU >= firstU) {
                    val outcome = diffBook.onDiffEvent(firstU, finalU, bids, asks, eventTs)
                    when (outcome.status) {
                        BinanceDiffBookSync.Status.SYNCED -> {
                            if (outcome.deltas.isNotEmpty()) {
                                listener.onBookDeltas(outcome.deltas, true, lagMs)
                            }
                            listener.onDepth(outcome.bids, outcome.asks)
                        }
                        BinanceDiffBookSync.Status.NEED_SNAPSHOT -> {
                            listener.onBookDeltas(emptyList(), false, lagMs)
                            if (outcome.desyncReason != null) {
                                listener.onBookDesync(outcome.desyncReason)
                            }
                            triggerRestSnapshotAsync()
                        }
                        BinanceDiffBookSync.Status.BUFFERING -> {
                            // ждём REST snapshot
                        }
                    }
                }
            }
        }
    }

    private fun triggerRestSnapshotAsync() {
        if (closed) return
        val now = System.currentTimeMillis()
        if (now - lastSnapshotAttemptMs.get() < 2500L) return
        if (!snapshotFetching.compareAndSet(false, true)) return
        lastSnapshotAttemptMs.set(now)
        val gen = generation.get()
        ioExec.execute {
            try {
                val urls = listOf(
                    "https://data-api.binance.vision/api/v3/depth?symbol=$bnSym&limit=500",
                    "https://api1.binance.com/api/v3/depth?symbol=$bnSym&limit=500",
                    "https://api3.binance.com/api/v3/depth?symbol=$bnSym&limit=500"
                )
                for (url in urls) {
                    if (closed || gen != generation.get()) break
                    val req = Request.Builder().url(url).header("Accept", "application/json").build()
                    val body = try {
                        restHttp.newCall(req).execute().use { r ->
                            if (r.isSuccessful) r.body?.string() else null
                        }
                    } catch (_: Exception) {
                        null
                    }
                    if (!body.isNullOrBlank()) {
                        val obj = JSONObject(body)
                        val lastId = obj.optLong("lastUpdateId", -1L)
                        val bids = parsePairs(obj.optJSONArray("bids"))
                        val asks = parsePairs(obj.optJSONArray("asks"))
                        if (lastId > 0L && bids.isNotEmpty() && asks.isNotEmpty()) {
                            val tsNow = System.currentTimeMillis() + clockOffsetMsProvider()
                            val res = diffBook.applyRestSnapshot(lastId, bids, asks, tsNow)
                            if (res.status == BinanceDiffBookSync.Status.SYNCED) {
                                listener.onDepth(res.bids, res.asks)
                                if (res.deltas.isNotEmpty()) {
                                    listener.onBookDeltas(res.deltas, true, 0L)
                                }
                            } else if (res.desyncReason != null) {
                                listener.onBookDesync(res.desyncReason)
                            }
                            break
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                snapshotFetching.set(false)
            }
        }
    }

    private fun parsePairs(arr: JSONArray?): List<Pair<Double, Double>> {
        if (arr == null) return emptyList()
        val out = ArrayList<Pair<Double, Double>>(arr.length())
        for (i in 0 until arr.length()) {
            val lvl = arr.optJSONArray(i) ?: continue
            val p = lvl.optString(0).toDoubleOrNull() ?: lvl.optDouble(0, Double.NaN)
            val q = lvl.optString(1).toDoubleOrNull() ?: lvl.optDouble(1, Double.NaN)
            if (p.isFinite() && q.isFinite() && p > 0.0 && q >= 0.0) {
                out.add(p to q)
            }
        }
        return out
    }

    /** Статистика потока за окно (последние 600 агрегированных сделок / 120с). */
    private fun stats(): FlowStats {
        val now = System.currentTimeMillis()
        val snap = synchronized(trades) { trades.filter { now - it.ts < 120_000 } }
        val buy = snap.filter { it.buy }.sumOf { it.qty }
        val sell = snap.filter { !it.buy }.sumOf { it.qty }
        val tot = buy + sell
        val cvd = buy - sell
        val pFirst = snap.firstOrNull { it.price > 0 }?.price
        val pLast = snap.lastOrNull { it.price > 0 }?.price
        val pchg = if (pFirst != null && pFirst > 0 && pLast != null) 100.0 * (pLast - pFirst) / pFirst else 0.0
        val div = (cvd > 0 && pchg < 0) || (cvd < 0 && pchg > 0)
        return FlowStats(
            n = snap.size,
            buyQty = buy, sellQty = sell,
            cvd = cvd,
            deltaPct = if (tot > 0) 100.0 * cvd / tot else 0.0,
            ageSec = if (lastTradeTs > 0) (now - lastTradeTs) / 1000.0 else 999.0,
            priceChangePct = pchg,
            divergence = div
        )
    }

    private fun scheduleReconnect(gen: Long) {
        if (closed || gen != generation.get()) return
        val delay = reconnectMs
        reconnectMs = (reconnectMs * 2).coerceAtMost(60_000)
        pendingReconnect?.cancel(false)
        pendingReconnect = ioExec.schedule({
            if (!closed && gen == generation.get()) connect()
        }, delay, TimeUnit.MILLISECONDS)
    }

    fun close() {
        closed = true
        generation.incrementAndGet()
        pendingReconnect?.cancel(false)
        pendingReconnect = null
        diffBook.reset()
        ws?.close(1000, "bye")
        ws = null
    }

    companion object {
        private val ioExec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "BinanceWs-Worker").apply { isDaemon = true }
        }
    }
}

/**
 * Потокобезопасный синхронизатор Binance Spot Diff Depth (`@depth@100ms` + REST `/api/v3/depth`).
 * Проверяет sequence ID (`U`, `u`, `lastUpdateId`), буферизует дельты и инициирует ресинк при разрыве.
 */
class BinanceDiffBookSync(private val maxLevels: Int = 120) {
    enum class Status { BUFFERING, SYNCED, NEED_SNAPSHOT }

    data class DiffEvent(
        val firstU: Long,
        val finalU: Long,
        val bids: List<Pair<Double, Double>>,
        val asks: List<Pair<Double, Double>>,
        val tsMs: Long
    )

    data class SyncResult(
        val status: Status,
        val bids: List<Pair<Double, Double>> = emptyList(),
        val asks: List<Pair<Double, Double>> = emptyList(),
        val deltas: List<BookState.LevelDelta> = emptyList(),
        val lastUpdateId: Long = -1L,
        val desyncReason: String? = null
    )

    private val book = BookState(maxLevels)
    private val buffer = ArrayList<DiffEvent>()
    private var synced = false
    private var awaitingFirstBridge = false
    private var lastAppliedU = -1L
    var desyncCount: Long = 0L
        private set

    @Synchronized
    fun reset() {
        buffer.clear()
        book.desync()
        synced = false
        awaitingFirstBridge = false
        lastAppliedU = -1L
    }

    @Synchronized
    fun isSynced(): Boolean = synced

    @Synchronized
    fun applyRestSnapshot(
        lastUpdateId: Long,
        bids: List<Pair<Double, Double>>,
        asks: List<Pair<Double, Double>>,
        tsMs: Long
    ): SyncResult {
        book.snapshotWithDeltas(bids, asks, lastUpdateId, tsMs)
        lastAppliedU = lastUpdateId
        synced = true
        awaitingFirstBridge = true

        val replayDeltas = ArrayList<BookState.LevelDelta>()
        val queued = buffer.toList()
        buffer.clear()

        for (ev in queued) {
            if (ev.finalU <= lastAppliedU) continue
            if (awaitingFirstBridge) {
                if (ev.firstU <= lastAppliedU + 1L && ev.finalU >= lastAppliedU + 1L) {
                    val (_, ch) = book.deltaWithChanges(ev.bids, ev.asks, ev.finalU, ev.finalU, ev.tsMs)
                    replayDeltas.addAll(ch)
                    lastAppliedU = ev.finalU
                    awaitingFirstBridge = false
                } else if (ev.firstU > lastAppliedU + 1L) {
                    // Snapshot устарел или потерян пакет моста — нужен более свежий snapshot
                    synced = false
                    awaitingFirstBridge = false
                    desyncCount++
                    book.desync()
                    return SyncResult(
                        status = Status.NEED_SNAPSHOT,
                        desyncReason = "разрыв моста Binance depth (snap=$lastUpdateId, U=${ev.firstU})"
                    )
                }
            } else {
                if (ev.firstU != lastAppliedU + 1L) {
                    synced = false
                    desyncCount++
                    book.desync()
                    return SyncResult(
                        status = Status.NEED_SNAPSHOT,
                        desyncReason = "разрыв очереди Binance depth (wait=${lastAppliedU + 1}, got U=${ev.firstU})"
                    )
                }
                val (_, ch) = book.deltaWithChanges(ev.bids, ev.asks, ev.finalU, ev.finalU, ev.tsMs)
                replayDeltas.addAll(ch)
                lastAppliedU = ev.finalU
            }
        }

        val snap = book.snapshot()
        return SyncResult(
            status = Status.SYNCED,
            bids = snap.bids,
            asks = snap.asks,
            deltas = replayDeltas,
            lastUpdateId = lastAppliedU
        )
    }

    @Synchronized
    fun onDiffEvent(
        firstU: Long,
        finalU: Long,
        bids: List<Pair<Double, Double>>,
        asks: List<Pair<Double, Double>>,
        tsMs: Long
    ): SyncResult {
        if (!synced) {
            if (buffer.size > 400) buffer.removeAt(0)
            buffer.add(DiffEvent(firstU, finalU, bids, asks, tsMs))
            return SyncResult(status = Status.BUFFERING)
        }
        if (finalU <= lastAppliedU) {
            val snap = book.snapshot()
            return SyncResult(Status.SYNCED, snap.bids, snap.asks, emptyList(), lastAppliedU)
        }
        if (awaitingFirstBridge) {
            if (firstU <= lastAppliedU + 1L && finalU >= lastAppliedU + 1L) {
                val (_, deltas) = book.deltaWithChanges(bids, asks, finalU, finalU, tsMs)
                lastAppliedU = finalU
                awaitingFirstBridge = false
                val snap = book.snapshot()
                return SyncResult(Status.SYNCED, snap.bids, snap.asks, deltas, lastAppliedU)
            } else if (firstU > lastAppliedU + 1L) {
                synced = false
                awaitingFirstBridge = false
                desyncCount++
                book.desync()
                buffer.clear()
                buffer.add(DiffEvent(firstU, finalU, bids, asks, tsMs))
                return SyncResult(
                    status = Status.NEED_SNAPSHOT,
                    desyncReason = "поток Binance обогнал REST snapshot (snap=$lastAppliedU, U=$firstU)"
                )
            }
            val snap = book.snapshot()
            return SyncResult(Status.SYNCED, snap.bids, snap.asks, emptyList(), lastAppliedU)
        }

        // Основная проверка непрерывности: U должен быть <= lastAppliedU + 1
        if (firstU > lastAppliedU + 1L) {
            synced = false
            desyncCount++
            book.desync()
            buffer.clear()
            buffer.add(DiffEvent(firstU, finalU, bids, asks, tsMs))
            return SyncResult(
                status = Status.NEED_SNAPSHOT,
                desyncReason = "потеря пакетов Binance depth (ожидался ${lastAppliedU + 1}, пришёл $firstU)"
            )
        }

        val (_, deltas) = book.deltaWithChanges(bids, asks, finalU, finalU, tsMs)
        lastAppliedU = finalU
        val snap = book.snapshot()
        return SyncResult(Status.SYNCED, snap.bids, snap.asks, deltas, lastAppliedU)
    }
}
