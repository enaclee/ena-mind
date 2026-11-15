package com.enamind.ai.bybit

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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

    private var ws: WebSocket? = null
    private var reconnectMs = 2000L
    private var closed = false
    private val trades = ArrayDeque<Trade>()
    private var lastTradeTs = 0L

    private data class Trade(val ts: Long, val buy: Boolean, val qty: Double)

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private fun instId(): String {
        val base = symbol.removeSuffix("USDT").removeSuffix("USDC")
        return "$base-USDT-SWAP"
    }

    fun connect() {
        closed = false
        val req = Request.Builder().url("wss://ws.okx.com:8443/ws/v5/public").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
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
                try {
                    handle(text)
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onStatus(false, "OKX WS: ${t.message ?: "обрыв"}")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onStatus(false, "OKX WS: закрыт")
                scheduleReconnect()
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
