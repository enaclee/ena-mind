package com.enamind.ai.bot

import android.util.Log
import com.enamind.ai.core.NetworkHub
import com.enamind.ai.intelligence.ExchangeClockSync
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor

/**
 * Bybit V5 Private REST + Private WebSocket клиент для Demo, Testnet и Mainnet.
 *
 * Особенности надёжности и безопасности:
 *  - Использует общий NetworkHub (единый ConnectionPool и Dispatcher, защита от HTTP 429);
 *  - Подпись HMAC-SHA256(secret, timestamp + apiKey + recvWindow + payload), без логирования секретов;
 *  - Автоматический resync времени через ExchangeClockSync и повтор 1 раз при retCode == 10002;
 *  - Корректная обработка UTA vs Classic аккаунтов при переключении ISOLATED/CROSS маржи
 *    (GET /v5/account/info -> unifiedMarginStatus >= 3 -> /v5/account/set-margin-mode;
 *     не блокирует вход, если режим уже совпадает);
 *  - Серверный /v5/position/trading-stop для дублирования защитного безубыточного/трейлинг-стопа;
 *  - Точное округление qty по qtyStep и цены по tickSize через BigDecimal.
 */
class BybitV5TradingClient(
    private val clockSync: ExchangeClockSync = ExchangeClockSync()
) {
    data class ApiResult<T>(
        val ok: Boolean,
        val data: T? = null,
        val retCode: Int = -1,
        val message: String = ""
    )

    data class AccountProbe(
        val connected: Boolean,
        val isUta: Boolean,
        val unifiedMarginStatus: Int,
        val walletUsdt: Double?,
        val feeRateSidePct: Double,
        val note: String
    )

    data class ExchangePositionInfo(
        val symbol: String,
        val side: TradeSide?,
        val size: Double,
        val avgPrice: Double,
        val markPrice: Double,
        val unrealisedPnl: Double,
        val liqPrice: Double?,
        val leverage: Int,
        val stopLoss: Double?
    )

    private val http = NetworkHub.restClient(connectTimeoutSec = 7L, readTimeoutSec = 10L, writeTimeoutSec = 12L)
    private val wsHttp = NetworkHub.wsClient(pingIntervalSec = 20L, connectTimeoutSec = 12L)

    fun probeAccountAndBalance(
        mode: BotMode,
        slot: BybitCredentialSlot,
        symbol: String
    ): ApiResult<AccountProbe> {
        if (!mode.isLiveExchange) {
            return ApiResult(
                ok = true,
                retCode = 0,
                data = AccountProbe(true, isUta = false, unifiedMarginStatus = 0, walletUsdt = null, feeRateSidePct = 0.055, note = "Paper")
            )
        }
        if (!slot.hasKeys) {
            return ApiResult(ok = false, message = "API-ключи для ${mode.labelRu} не заданы")
        }

        ensureClockReady(mode)
        val infoRes = fetchAccountInfo(mode, slot)
        val isUta = infoRes.data?.first ?: false
        val umiStatus = infoRes.data?.second ?: 0

        val balRes = fetchWalletBalanceUsdt(mode, slot, isUta)
        if (!balRes.ok) {
            return ApiResult(ok = false, retCode = balRes.retCode, message = balRes.message)
        }

        val feeRes = fetchTakerFeeRatePct(mode, slot, symbol)
        val feePct = feeRes.data ?: 0.055

        val note = buildString {
            append(mode.shortLabel)
            append(if (isUta) " · UTA($umiStatus)" else " · Classic")
            balRes.data?.let { append(" · %.2f USDT".format(java.util.Locale.US, it)) }
        }
        return ApiResult(
            ok = true,
            retCode = 0,
            data = AccountProbe(
                connected = true,
                isUta = isUta,
                unifiedMarginStatus = umiStatus,
                walletUsdt = balRes.data,
                feeRateSidePct = feePct,
                note = note
            ),
            message = note
        )
    }

    /** GET /v5/account/info -> (isUta, unifiedMarginStatus). */
    fun fetchAccountInfo(mode: BotMode, slot: BybitCredentialSlot): ApiResult<Pair<Boolean, Int>> {
        val resp = signedGet(mode, slot, "/v5/account/info", "")
        if (!resp.ok || resp.data == null) {
            return ApiResult(ok = false, retCode = resp.retCode, message = resp.message)
        }
        val res = resp.data.optJSONObject("result")
        val status = res?.optInt("unifiedMarginStatus", 0) ?: 0
        // unifiedMarginStatus >= 3 (3 = UTA 1.0, 4 = UTA 1.0 Pro, 5 = UTA 2.0, 6 = UTA 2.0 Pro)
        val isUta = status >= 3 || mode == BotMode.BYBIT_DEMO
        return ApiResult(ok = true, retCode = 0, data = isUta to status)
    }

    /** GET /v5/account/wallet-balance (UNIFIED с резервом CONTRACT). */
    fun fetchWalletBalanceUsdt(
        mode: BotMode,
        slot: BybitCredentialSlot,
        preferUnified: Boolean = true
    ): ApiResult<Double> {
        val firstType = if (preferUnified) "UNIFIED" else "CONTRACT"
        val r1 = parseWalletUsdt(signedGet(mode, slot, "/v5/account/wallet-balance", "accountType=$firstType"))
        if (r1.ok && r1.data != null) return r1

        val secondType = if (preferUnified) "CONTRACT" else "UNIFIED"
        val r2 = parseWalletUsdt(signedGet(mode, slot, "/v5/account/wallet-balance", "accountType=$secondType"))
        return if (r2.ok && r2.data != null) r2 else r1
    }

    private fun parseWalletUsdt(resp: ApiResult<JSONObject>): ApiResult<Double> {
        if (!resp.ok || resp.data == null) return ApiResult(ok = false, retCode = resp.retCode, message = resp.message)
        val list = resp.data.optJSONObject("result")?.optJSONArray("list")
            ?: return ApiResult(ok = false, message = "Пустой список кошелька")
        val acct = list.optJSONObject(0) ?: return ApiResult(ok = false, message = "Кошелёк не найден")
        val coins = acct.optJSONArray("coin")
        if (coins != null) {
            for (i in 0 until coins.length()) {
                val c = coins.optJSONObject(i) ?: continue
                if (c.optString("coin").equals("USDT", ignoreCase = true)) {
                    val avail = c.optString("availableToWithdraw").toDoubleOrNull()
                        ?: c.optString("walletBalance").toDoubleOrNull()
                        ?: c.optString("equity").toDoubleOrNull()
                    if (avail != null && avail.isFinite()) {
                        return ApiResult(ok = true, retCode = 0, data = avail)
                    }
                }
            }
        }
        val totalAvail = acct.optString("totalAvailableBalance").toDoubleOrNull()
            ?: acct.optString("totalEquity").toDoubleOrNull()
            ?: acct.optString("totalWalletBalance").toDoubleOrNull()
        return if (totalAvail != null && totalAvail.isFinite()) {
            ApiResult(ok = true, retCode = 0, data = totalAvail)
        } else {
            ApiResult(ok = false, message = "Баланс USDT недоступен")
        }
    }

    fun fetchTakerFeeRatePct(
        mode: BotMode,
        slot: BybitCredentialSlot,
        symbol: String
    ): ApiResult<Double> {
        val resp = signedGet(mode, slot, "/v5/account/fee-rate", "category=linear&symbol=${symbol.uppercase()}")
        if (!resp.ok || resp.data == null) return ApiResult(ok = false, data = 0.055, retCode = resp.retCode, message = resp.message)
        val first = resp.data.optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0)
        val takerFrac = first?.optString("takerFeeRate")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            ?: return ApiResult(ok = true, data = 0.055)
        return ApiResult(ok = true, data = takerFrac * 100.0)
    }

    /**
     * Уточнение #1: переключение маржи с учётом UTA vs Classic и толерантностью к уже совпадающему режиму.
     */
    fun ensureMarginAndLeverage(
        mode: BotMode,
        slot: BybitCredentialSlot,
        symbol: String,
        marginMode: MarginMode,
        leverage: Int
    ): ApiResult<Boolean> {
        val infoRes = fetchAccountInfo(mode, slot)
        val isUta = infoRes.data?.first ?: (mode == BotMode.BYBIT_DEMO)

        val marginRes = if (isUta) {
            val body = JSONObject().put("setMarginMode", marginMode.bybitUtaValue)
            signedPost(mode, slot, "/v5/account/set-margin-mode", body)
        } else {
            val levStr = leverage.coerceIn(1, 125).toString()
            val body = JSONObject()
                .put("category", "linear")
                .put("symbol", symbol.uppercase())
                .put("tradeMode", marginMode.classicTradeMode)
                .put("buyLeverage", levStr)
                .put("sellLeverage", levStr)
            signedPost(mode, slot, "/v5/position/switch-isolated", body)
        }

        val marginOk = marginRes.ok || isAlreadyMatchedRet(marginRes.retCode, marginRes.message)
        if (!marginOk) {
            return ApiResult(ok = false, retCode = marginRes.retCode, message = "Маржа (${marginMode.labelRu}): ${marginRes.message}")
        }

        val levStr = leverage.coerceIn(1, 125).toString()
        val levBody = JSONObject()
            .put("category", "linear")
            .put("symbol", symbol.uppercase())
            .put("buyLeverage", levStr)
            .put("sellLeverage", levStr)
        val levRes = signedPost(mode, slot, "/v5/position/set-leverage", levBody)
        val levOk = levRes.ok || isAlreadyMatchedRet(levRes.retCode, levRes.message)
        if (!levOk) {
            return ApiResult(ok = false, retCode = levRes.retCode, message = "Плечо (${levStr}x): ${levRes.message}")
        }
        return ApiResult(ok = true, retCode = 0, data = isUta, message = "OK")
    }

    fun fetchOpenPosition(
        mode: BotMode,
        slot: BybitCredentialSlot,
        symbol: String
    ): ApiResult<ExchangePositionInfo?> {
        val resp = signedGet(mode, slot, "/v5/position/list", "category=linear&symbol=${symbol.uppercase()}")
        if (!resp.ok || resp.data == null) {
            return ApiResult(ok = false, retCode = resp.retCode, message = resp.message)
        }
        val list = resp.data.optJSONObject("result")?.optJSONArray("list")
        if (list == null || list.length() == 0) {
            return ApiResult(ok = true, retCode = 0, data = null)
        }
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val size = item.optString("size").toDoubleOrNull() ?: 0.0
            if (size <= 0.0) continue
            val sideStr = item.optString("side")
            val side = if (sideStr.equals("Sell", ignoreCase = true)) TradeSide.SELL else TradeSide.BUY
            val avg = item.optString("avgPrice").toDoubleOrNull() ?: 0.0
            val mark = item.optString("markPrice").toDoubleOrNull() ?: avg
            val upnl = item.optString("unrealisedPnl").toDoubleOrNull() ?: 0.0
            val liq = item.optString("liqPrice").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            val lev = item.optString("leverage").toDoubleOrNull()?.toInt()?.coerceAtLeast(1) ?: 1
            val sl = item.optString("stopLoss").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            return ApiResult(
                ok = true,
                retCode = 0,
                data = ExchangePositionInfo(
                    symbol = symbol.uppercase(),
                    side = side,
                    size = size,
                    avgPrice = avg,
                    markPrice = mark,
                    unrealisedPnl = upnl,
                    liqPrice = liq,
                    leverage = lev,
                    stopLoss = sl
                )
            )
        }
        return ApiResult(ok = true, retCode = 0, data = null)
    }

    fun placeMarketOrder(
        mode: BotMode,
        slot: BybitCredentialSlot,
        symbol: String,
        side: TradeSide,
        qtyFormatted: String,
        reduceOnly: Boolean,
        stopLossFormatted: String? = null
    ): ApiResult<String> {
        val body = JSONObject()
            .put("category", "linear")
            .put("symbol", symbol.uppercase())
            .put("side", side.bybitSide)
            .put("orderType", "Market")
            .put("qty", qtyFormatted)
            .put("reduceOnly", reduceOnly)
        if (!reduceOnly && !stopLossFormatted.isNullOrBlank()) {
            body.put("stopLoss", stopLossFormatted)
            body.put("slTriggerBy", "MarkPrice")
        }
        val resp = signedPost(mode, slot, "/v5/order/create", body)
        if (!resp.ok || resp.data == null) {
            return ApiResult(ok = false, retCode = resp.retCode, message = resp.message)
        }
        val orderId = resp.data.optJSONObject("result")?.optString("orderId").orEmpty()
        return ApiResult(ok = true, retCode = 0, data = orderId, message = "OK")
    }

    /**
     * Уточнение #6: дублирование защитного безубыточного/трейлинг-стопа на сервере Bybit.
     */
    fun setTradingStop(
        mode: BotMode,
        slot: BybitCredentialSlot,
        symbol: String,
        stopLossPrice: Double,
        tickSize: Double?
    ): ApiResult<Boolean> {
        val formattedSl = formatPriceToTick(stopLossPrice, tickSize) ?: return ApiResult(ok = false, message = "Некорректный SL")
        val body = JSONObject()
            .put("category", "linear")
            .put("symbol", symbol.uppercase())
            .put("positionIdx", 0)
            .put("tpslMode", "Full")
            .put("slTriggerBy", "MarkPrice")
            .put("stopLoss", formattedSl)
        val resp = signedPost(mode, slot, "/v5/position/trading-stop", body)
        val ok = resp.ok || isAlreadyMatchedRet(resp.retCode, resp.message)
        return ApiResult(ok = ok, retCode = resp.retCode, data = ok, message = if (ok) "OK" else resp.message)
    }

    fun cancelAllOrders(mode: BotMode, slot: BybitCredentialSlot, symbol: String): ApiResult<Boolean> {
        val body = JSONObject()
            .put("category", "linear")
            .put("symbol", symbol.uppercase())
        val resp = signedPost(mode, slot, "/v5/order/cancel-all", body)
        return ApiResult(ok = resp.ok, retCode = resp.retCode, data = resp.ok, message = resp.message)
    }

    // =========================================================================
    // Private WebSocket (/v5/private) с авторизацией и heartbeat ping (20s)
    // =========================================================================

    inner class PrivateWsHandle(
        private val mode: BotMode,
        private val slot: BybitCredentialSlot,
        private val onOrderOrPositionEvent: (topic: String, payload: JSONObject) -> Unit,
        private val onState: (connected: Boolean, note: String) -> Unit
    ) {
        private val closed = AtomicBoolean(false)
        private val gen = AtomicInteger(0)
        private var ws: WebSocket? = null
        private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "bybit-private-ws").apply { isDaemon = true }
        }

        fun connect() {
            if (closed.get() || !mode.isLiveExchange || !slot.hasKeys) return
            val myGen = gen.incrementAndGet()
            val req = Request.Builder().url(mode.wsPrivateUrl).build()
            ws = wsHttp.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (closed.get() || gen.get() != myGen) {
                        webSocket.close(1000, "stale")
                        return
                    }
                    val expires = clockSync.exchangeNowMs() + 10_000L
                    val sig = signPayload(slot.apiSecret, "GET/realtime$expires")
                    val authJson = JSONObject()
                        .put("op", "auth")
                        .put("args", JSONArray().put(slot.apiKey).put(expires).put(sig))
                    webSocket.send(authJson.toString())
                    scheduler.scheduleAtFixedRate({
                        if (!closed.get() && gen.get() == myGen) {
                            runCatching { webSocket.send("""{"op":"ping"}""") }
                        }
                    }, 20L, 20L, TimeUnit.SECONDS)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (closed.get() || gen.get() != myGen) return
                    val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                    val op = obj.optString("op")
                    if (op == "auth") {
                        val success = obj.optBoolean("success", false)
                        if (success) {
                            onState(true, "${mode.shortLabel} Private WS активен")
                            val sub = JSONObject()
                                .put("op", "subscribe")
                                .put("args", JSONArray().put("position").put("execution").put("order"))
                            webSocket.send(sub.toString())
                        } else {
                            onState(false, "WS auth ошибка: ${obj.optString("ret_msg")}")
                        }
                        return
                    }
                    if (op == "pong" || op == "subscribe") return
                    val topic = obj.optString("topic")
                    if (topic.isNotBlank()) {
                        onOrderOrPositionEvent(topic, obj)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (closed.get() || gen.get() != myGen) return
                    onState(false, "WS переподключение…")
                    scheduleReconnect(myGen)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (closed.get() || gen.get() != myGen) return
                    onState(false, "WS закрыт")
                    scheduleReconnect(myGen)
                }
            })
        }

        private fun scheduleReconnect(expectedGen: Int) {
            if (closed.get()) return
            scheduler.schedule({
                if (!closed.get() && gen.get() == expectedGen) {
                    connect()
                }
            }, 4L, TimeUnit.SECONDS)
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            gen.incrementAndGet()
            runCatching { ws?.close(1000, "bot stopped") }
            ws = null
            scheduler.shutdownNow()
        }
    }

    // =========================================================================
    // Подпись, время и исполнение REST
    // =========================================================================

    private fun ensureClockReady(mode: BotMode) {
        if (!clockSync.status().synced && mode.restBaseUrl.isNotBlank()) {
            clockSync.syncIfNeeded(http, bybitCooldown = false, force = true, bybitBaseUrl = mode.restBaseUrl)
        }
    }

    private fun signedGet(
        mode: BotMode,
        slot: BybitCredentialSlot,
        path: String,
        query: String,
        allowRetryOnClock: Boolean = true
    ): ApiResult<JSONObject> {
        val host = NetworkHub.extractHost(mode.restBaseUrl)
        if (NetworkHub.isHostRateLimited(host)) {
            return ApiResult(ok = false, retCode = 429, message = "Лимит запросов к $host (ожидание cooldown)")
        }
        val ts = clockSync.exchangeNowMs()
        val payloadToSign = "$ts${slot.apiKey}$RECV_WINDOW$query"
        val sig = signPayload(slot.apiSecret, payloadToSign)
        val fullUrl = if (query.isBlank()) "${mode.restBaseUrl}$path" else "${mode.restBaseUrl}$path?$query"
        val req = Request.Builder()
            .url(fullUrl)
            .header("X-BAPI-API-KEY", slot.apiKey)
            .header("X-BAPI-TIMESTAMP", ts.toString())
            .header("X-BAPI-RECV-WINDOW", RECV_WINDOW)
            .header("X-BAPI-SIGN", sig)
            .get()
            .build()

        return executeSigned(mode, req, allowRetryOnClock) {
            signedGet(mode, slot, path, query, allowRetryOnClock = false)
        }
    }

    private fun signedPost(
        mode: BotMode,
        slot: BybitCredentialSlot,
        path: String,
        body: JSONObject,
        allowRetryOnClock: Boolean = true
    ): ApiResult<JSONObject> {
        val host = NetworkHub.extractHost(mode.restBaseUrl)
        if (NetworkHub.isHostRateLimited(host)) {
            return ApiResult(ok = false, retCode = 429, message = "Лимит запросов к $host (ожидание cooldown)")
        }
        val rawBody = body.toString()
        val ts = clockSync.exchangeNowMs()
        val payloadToSign = "$ts${slot.apiKey}$RECV_WINDOW$rawBody"
        val sig = signPayload(slot.apiSecret, payloadToSign)
        val req = Request.Builder()
            .url("${mode.restBaseUrl}$path")
            .header("X-BAPI-API-KEY", slot.apiKey)
            .header("X-BAPI-TIMESTAMP", ts.toString())
            .header("X-BAPI-RECV-WINDOW", RECV_WINDOW)
            .header("X-BAPI-SIGN", sig)
            .header("Content-Type", "application/json")
            .post(rawBody.toRequestBody(JSON_MEDIA))
            .build()

        return executeSigned(mode, req, allowRetryOnClock) {
            signedPost(mode, slot, path, body, allowRetryOnClock = false)
        }
    }

    private inline fun executeSigned(
        mode: BotMode,
        req: Request,
        allowRetryOnClock: Boolean,
        retryBlock: () -> ApiResult<JSONObject>
    ): ApiResult<JSONObject> {
        val host = NetworkHub.extractHost(mode.restBaseUrl)
        return try {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 429) {
                    NetworkHub.recordRateLimit(host, cooldownSec = 20L)
                    return ApiResult(ok = false, retCode = 429, message = "HTTP 429 Rate Limit")
                }
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful && raw.isBlank()) {
                    return ApiResult(ok = false, retCode = resp.code, message = "HTTP ${resp.code}")
                }
                val json = JSONObject(raw)
                val retCode = json.optInt("retCode", -1)
                val retMsg = json.optString("retMsg", "ошибка")
                if (retCode == 10006) {
                    NetworkHub.recordRateLimit(host, cooldownSec = 20L)
                }
                if (retCode == 10002 && allowRetryOnClock) {
                    clockSync.syncIfNeeded(http, bybitCooldown = false, force = true, bybitBaseUrl = mode.restBaseUrl)
                    return retryBlock()
                }
                if (retCode == 0) {
                    ApiResult(ok = true, retCode = 0, data = json, message = retMsg)
                } else {
                    ApiResult(ok = false, retCode = retCode, data = json, message = "[$retCode] $retMsg")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "${mode.shortLabel} REST error: ${e.message}")
            ApiResult(ok = false, retCode = -1, message = e.message ?: "ошибка сети")
        }
    }

    companion object {
        private const val TAG = "BybitV5Client"
        private const val RECV_WINDOW = "5000"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun buildSignPayload(
            timestampMs: Long,
            apiKey: String,
            recvWindow: String = RECV_WINDOW,
            queryOrBody: String = ""
        ): String = "$timestampMs$apiKey$recvWindow$queryOrBody"

        fun signPayload(secret: String, payload: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        fun isAlreadyMatchedRet(retCode: Int, message: String): Boolean {
            if (retCode in setOf(110026, 100028, 34036)) return true
            val low = message.lowercase()
            return low.contains("same") ||
                low.contains("unchanged") ||
                low.contains("not modified") ||
                low.contains("already")
        }

        fun roundQtyToStep(rawQty: Double, qtyStep: Double?, minQty: Double?): String? {
            if (!rawQty.isFinite() || rawQty <= 0.0) return null
            val step = qtyStep?.takeIf { it.isFinite() && it > 0.0 } ?: 0.001
            val min = minQty?.takeIf { it.isFinite() && it > 0.0 } ?: step
            val stepsCount = floor(rawQty / step + 1e-12)
            val rounded = BigDecimal.valueOf(stepsCount).multiply(BigDecimal.valueOf(step))
            val minBd = BigDecimal.valueOf(min)
            if (rounded.compareTo(minBd) < 0) return null
            val stepBd = BigDecimal.valueOf(step).stripTrailingZeros()
            val scale = stepBd.scale().coerceAtLeast(0)
            return rounded.setScale(scale, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
        }

        fun formatPriceToTick(price: Double, tickSize: Double?): String? {
            if (!price.isFinite() || price <= 0.0) return null
            val tick = tickSize?.takeIf { it.isFinite() && it > 0.0 }
            if (tick == null) {
                return BigDecimal.valueOf(price).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            }
            val tickBd = BigDecimal.valueOf(tick).stripTrailingZeros()
            val steps = BigDecimal.valueOf(price).divide(tickBd, 0, RoundingMode.HALF_UP)
            val scale = tickBd.scale().coerceAtLeast(0)
            return steps.multiply(tickBd).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        }
    }
}
