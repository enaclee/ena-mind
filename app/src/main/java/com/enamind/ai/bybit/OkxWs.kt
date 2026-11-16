package com.enamind.ai.bybit

import com.enamind.ai.core.NetworkHub
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * OKX public WebSocket (wss://ws.okx.com:8443/ws/v5/public, ЧАСТЬ 3C промпта 7.0).
 * Каналы: trades (агрессивный поток; side=buy/sell уже означает агрессора),
 * tickers (цена/объём). Публичные данные, без ключей. Переподключение с backoff.
 */
class OkxWs(
    private val symbol: String,          // BTCUSDT -> BTC-USDT-SWAP
    private val listener: Listener
) {
    interface Listener {
        fun onFlow(flow: FlowStats)
        fun onPrice(price: Double, vol24hCcy: Double)
        fun onStatus(connected: Boolean, note: String)
    }

    data class FlowStats(
        val n: Int, val buyQty: Double, val sellQty: Double,
        val cvd: Double, val deltaPct: Double, val ageSec: Double
    )

    @Volatile private var ws: WebSocket? = null
    @Volatile private var reconnectMs = 2000L
    @Volatile private var closed = false
    private val generation = AtomicLong(0L)
    @Volatile private var pendingReconnect: ScheduledFuture<*>? = null
    private val trades = ArrayDeque<Trade>()
    @Volatile private var lastTradeTs = 0L

    private data class Trade(val ts: Long, val buy: Boolean, val qty: Double)

    private val http: OkHttpClient = NetworkHub.wsClient(pingIntervalSec = 20L, connectTimeoutSec = 12L)

    private fun instId(): String {
        val base = symbol.removeSuffix("USDT").removeSuffix("USDC")
        return "$base-USDT-SWAP"
    }

    fun connect() {
        pendingReconnect?.cancel(false)
        pendingReconnect = null
        closed = false
        val gen = generation.incrementAndGet()
        val req = Request.Builder().url("wss://ws.okx.com:8443/ws/v5/public").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation.get() || closed) {
                    webSocket.close(1000, "stale")
                    return
                }
                // подписка: trades + tickers свопа
                val args = org.json.JSONArray().put(
                    JSONObject().put("channel", "trades").put("instId", instId())
                ).put(
                    JSONObject().put("channel", "tickers").put("instId", instId())
                )
                webSocket.send(JSONObject().put("op", "subscribe").put("args", args).toString())
                reconnectMs = 2000L
                listener.onStatus(true, "OKX WS: поток подключён")
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
                listener.onStatus(false, "OKX WS: ${t.message ?: "обрыв"}")
                scheduleReconnect(gen)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation.get()) return
                listener.onStatus(false, "OKX WS: закрыт")
                if (!closed) scheduleReconnect(gen)
            }
        })
    }

    private fun handle(text: String) {
        val root = JSONObject(text)
        if (root.has("event")) return // ack подписки
        val arg = root.optJSONObject("arg") ?: return
        when (arg.optString("channel")) {
            "trades" -> {
                val data = root.optJSONArray("data") ?: return
                for (i in 0 until data.length()) {
                    val t = data.getJSONObject(i)
                    val ts = t.optLong("ts")
                    lastTradeTs = ts
                    trades.addLast(
                        Trade(ts, t.optString("side").equals("buy", true), t.optDouble("sz", 0.0))
                    )
                }
                while (trades.size > 600) trades.removeFirst()
                listener.onFlow(stats())
            }
            "tickers" -> {
                val t = root.optJSONArray("data")?.optJSONObject(0) ?: return
                val last = t.optDouble("last", 0.0)
                if (last > 0) listener.onPrice(last, t.optDouble("volCcy24h", 0.0))
            }
        }
    }

    private fun stats(): FlowStats {
        val now = System.currentTimeMillis()
        val win = trades.filter { now - it.ts < 120_000 }
        val buy = win.filter { it.buy }.sumOf { it.qty }
        val sell = win.filter { !it.buy }.sumOf { it.qty }
        val tot = buy + sell
        return FlowStats(
            n = win.size,
            buyQty = buy, sellQty = sell,
            cvd = buy - sell,
            deltaPct = if (tot > 0) 100.0 * (buy - sell) / tot else 0.0,
            ageSec = if (lastTradeTs > 0) (now - lastTradeTs) / 1000.0 else 999.0
        )
    }

    private fun scheduleReconnect(gen: Long) {
        if (closed || gen != generation.get()) return
        val delay = reconnectMs
        reconnectMs = (reconnectMs * 2).coerceAtMost(60_000)
        pendingReconnect?.cancel(false)
        pendingReconnect = reconnectExec.schedule({
            if (!closed && gen == generation.get()) connect()
        }, delay, TimeUnit.MILLISECONDS)
    }

    fun close() {
        closed = true
        generation.incrementAndGet()
        pendingReconnect?.cancel(false)
        pendingReconnect = null
        ws?.close(1000, "bye")
        ws = null
    }

    companion object {
        private val reconnectExec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "OkxWs-Reconnect").apply { isDaemon = true }
        }
    }
}
