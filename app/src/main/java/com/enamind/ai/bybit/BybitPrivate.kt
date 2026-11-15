package com.enamind.ai.bybit

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Bybit V5 PRIVATE API (Этап 1: трейд-панель). Ключи вводит сам пользователь в
 * настройках; хранятся только на устройстве и уходят ИСКЛЮЧИТЕЛЬНО на api.bybit.com
 * в подписанных заголовках. Приложение отправляет ордера ТОЛЬКО по явному нажатию
 * с диалогом подтверждения — автономной торговли нет и не планируется.
 *
 * Подпись V5: X-BAPI-SIGN = HMAC_SHA256(secret, timestamp + apiKey + recvWindow +
 * (queryString для GET | rawBody для POST)), hex.
 */
class BybitPrivate(
    private val baseUrl: String,
    private val apiKey: String,
    private val apiSecret: String
) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        private val JSON_MEDIA: MediaType = "application/json; charset=utf-8".toMediaType()
        private const val RECV_WINDOW = "5000"

        fun sign(secret: String, payload: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }

    val hasKeys: Boolean get() = apiKey.isNotBlank() && apiSecret.isNotBlank()

    // ---------------- GET ----------------

    /** GET /v5/account/wallet-balance?accountType=UNIFIED — баланс USDT. */
    fun walletBalance(onDone: (JSONObject?) -> Unit) {
        signedGet("/v5/account/wallet-balance?accountType=UNIFIED") { root ->
            onDone(root?.optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0))
        }
    }

    /** GET /v5/position/list?category=linear — открытые позиции по символу. */
    fun positions(symbol: String, onDone: (JSONArray?) -> Unit) {
        signedGet("/v5/position/list?category=linear&symbol=$symbol") { root ->
            onDone(root?.optJSONObject("result")?.optJSONArray("list"))
        }
    }

    /** GET /v5/order/realtime — открытые ордера по символу. */
    fun openOrders(symbol: String, onDone: (JSONArray?) -> Unit) {
        signedGet("/v5/order/realtime?category=linear&symbol=$symbol") { root ->
            onDone(root?.optJSONObject("result")?.optJSONArray("list"))
        }
    }

    // ---------------- POST ----------------

    /**
     * POST /v5/order/create — рыночный ордер. side: "Buy" | "Sell".
     * reduceOnly=true — только закрытие существующей позиции (не открывает новую).
     */
    fun placeMarketOrder(
        symbol: String, side: String, qty: String, reduceOnly: Boolean,
        onDone: (JSONObject) -> Unit
    ) {
        val body = JSONObject()
            .put("category", "linear")
            .put("symbol", symbol)
            .put("side", side)
            .put("orderType", "Market")
            .put("qty", qty)
            .put("reduceOnly", reduceOnly)
        signedPost("/v5/order/create", body) { root -> onDone(root ?: JSONObject().put("retCode", -1).put("retMsg", "нет ответа")) }
    }

    /** Закрыть позицию рыночным ордером противоположной стороны (reduceOnly). */
    fun closePosition(symbol: String, positionSide: String, qty: String, onDone: (JSONObject) -> Unit) {
        // positionSide "Buy" (лонг) закрываем Sell, "Sell" (шорт) — Buy
        val closeSide = if (positionSide == "Buy") "Sell" else "Buy"
        placeMarketOrder(symbol, closeSide, qty, true, onDone)
    }

    // ---------------- подписанные запросы ----------------

    private fun signedGet(path: String, parse: (JSONObject?) -> Unit) {
        val ts = System.currentTimeMillis().toString()
        val query = path.substringAfter('?', "")
        val sign = sign(apiSecret, ts + apiKey + RECV_WINDOW + query)
        val req = Request.Builder()
            .url(baseUrl + path)
            .header("X-BAPI-API-KEY", apiKey)
            .header("X-BAPI-TIMESTAMP", ts)
            .header("X-BAPI-RECV-WINDOW", RECV_WINDOW)
            .header("X-BAPI-SIGN", sign)
            .get()
            .build()
        enqueue(req, parse)
    }

    private fun signedPost(path: String, body: JSONObject, parse: (JSONObject?) -> Unit) {
        val raw = body.toString()
        val ts = System.currentTimeMillis().toString()
        val sign = sign(apiSecret, ts + apiKey + RECV_WINDOW + raw)
        val req = Request.Builder()
            .url(baseUrl + path)
            .header("X-BAPI-API-KEY", apiKey)
            .header("X-BAPI-TIMESTAMP", ts)
            .header("X-BAPI-RECV-WINDOW", RECV_WINDOW)
            .header("X-BAPI-SIGN", sign)
            .header("Content-Type", "application/json")
            .post(raw.toRequestBody(JSON_MEDIA))
            .build()
        enqueue(req, parse)
    }

    private fun enqueue(req: Request, parse: (JSONObject?) -> Unit) {
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                parse(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val txt = it.body?.string() ?: return parse(null)
                        parse(JSONObject(txt))
                    } catch (_: Exception) {
                        parse(null)
                    }
                }
            }
        })
    }
}
