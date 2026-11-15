package com.enamind.ai.bybit

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Binance public market WebSocket (data-stream.binance.vision).
 * Combined stream: aggTrade (агрессивный поток), kline_{tf} (живые свечи для графика),
 * depth20@100ms (стакан), ticker (цена и 24ч изменение/объём).
 * m=true (buyer is maker) -> сделка инициирована ПРОДАВЦОМ (агрессивная продажа).
 */
class BinanceWs(
    private val symbol: String,          // BTCUSDT
    private val listener: Listener,
    private val klineInterval: String = "60"
) {
    interface Listener {
        fun onFlow(flow: FlowStats)
        fun onPrice(price: Double)
        fun onDepth(bids: List<Pair<Double, Double>>, asks: List<Pair<Double, Double>>)
        fun onStatus(connected: Boolean, note: String)
        fun onKline(bar: BybitWs.Kline) {}
        fun onTicker24h(price: Double, change24hPct: Double, volume24h: Double, quoteVolume24h: Double) {}
    }

    /** Агрегированный поток aggTrade: окно последних N сделок. */
    data class FlowStats(
        val n: Int, val buyQty: Double, val sellQty: Double,
        val cvd: Double, val deltaPct: Double, val ageSec: Double,
        val priceChangePct: Double = 0.0,
        val divergence: Boolean = false
    )

    private var ws: WebSocket? = null
    private var reconnectMs = 2000L
    private var closed = false
    private val trades = ArrayDeque<Trade>() // (tsMs, isAggressiveBuy, qty, price)
    private var lastTradeTs = 0L

    private data class Trade(val ts: Long, val buy: Boolean, val qty: Double, val price: Double)

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WS-стрим без read timeout
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val lower = BybitRest.toBinanceSymbol(symbol).lowercase()
    private val bnIv = BybitRest.toBinanceInterval(klineInterval)
    private val streams = "$lower@aggTrade/$lower@kline_$bnIv/$lower@depth20@100ms/$lower@ticker"

    fun connect() {
        closed = false
        val req = Request.Builder()
            .url("wss://data-stream.binance.vision/stream?streams=$streams")
            .build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectMs = 2000L
                listener.onStatus(true, "Binance WS: поток подключён")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handle(text)
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onStatus(false, "Binance WS: ${t.message ?: "обрыв"}")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onStatus(false, "Binance WS: закрыт")
                if (!closed) scheduleReconnect()
            }
        })
    }

    private fun handle(text: String) {
        val root = JSONObject(text)
        val data = root.optJSONObject("data") ?: return
        val stream = root.optString("stream")
        when {
            stream.endsWith("@aggTrade") -> {
                val px = data.optDouble("p", 0.0)
                val qty = data.optDouble("q", 0.0)
                val isBuyerMaker = data.optBoolean("m", false)
                val ts = data.optLong("T")
                lastTradeTs = ts
                synchronized(trades) {
                    trades.addLast(Trade(ts, !isBuyerMaker, qty, px))
                    while (trades.size > 600) trades.removeFirst()
                }
                listener.onFlow(stats())
                px.takeIf { it > 0 }?.let { listener.onPrice(it) }
            }
            stream.contains("@kline_") -> {
                val k = data.optJSONObject("k") ?: return
                val ts = k.optLong("t")
                val o = k.optDouble("o", 0.0)
                val h = k.optDouble("h", 0.0)
                val l = k.optDouble("l", 0.0)
                val c = k.optDouble("c", 0.0)
                val v = k.optDouble("v", 0.0)
                val closedBar = k.optBoolean("x", false)
                if (ts > 0 && o > 0 && c > 0) {
                    listener.onKline(
                        BybitWs.Kline(
                            tsMs = ts,
                            open = o,
                            high = h,
                            low = l,
                            close = c,
                            volume = v,
                            confirm = closedBar
                        )
                    )
                }
            }
            stream.endsWith("@ticker") -> {
                val c = data.optDouble("c", 0.0)
                val p = data.optDouble("P", Double.NaN)
                val v = data.optDouble("v", 0.0)
                val q = data.optDouble("q", 0.0)
                if (c > 0 && !p.isNaN()) {
                    listener.onTicker24h(c, p, v, q)
                }
            }
            stream.contains("@depth") -> {
                val bids = ArrayList<Pair<Double, Double>>()
                val asks = ArrayList<Pair<Double, Double>>()
                val bs = data.optJSONArray("bids") ?: data.optJSONArray("b")
                val asLevels = data.optJSONArray("asks") ?: data.optJSONArray("a")
                if (bs != null) for (i in 0 until bs.length()) {
                    val lvl = bs.getJSONArray(i)
                    bids.add(lvl.optDouble(0) to lvl.optDouble(1))
                }
                if (asLevels != null) for (i in 0 until asLevels.length()) {
                    val lvl = asLevels.getJSONArray(i)
                    asks.add(lvl.optDouble(0) to lvl.optDouble(1))
                }
                if (bids.isNotEmpty() || asks.isNotEmpty()) listener.onDepth(bids, asks)
            }
        }
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

    private fun scheduleReconnect() {
        if (closed) return
        val delay = reconnectMs
        reconnectMs = (reconnectMs * 2).coerceAtMost(60_000)
        Thread {
            try {
                Thread.sleep(delay)
                if (!closed) connect()
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true }.start()
    }

    fun close() {
        closed = true
        ws?.close(1000, "bye")
        ws = null
    }
}
