package com.enamind.ai.bybit

import com.enamind.ai.core.FaultMonitor
import com.enamind.ai.core.NetworkHub
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Публичный REST-клиент рынка: Bybit V5 (основной) + автоматический fallback на
 * Binance (data-api.binance.vision) и OKX (www.okx.com), когда Bybit недоступен
 * (региональная блокировка, таймаут, WAF). Все эндпоинты публичные, без ключей.
 */
class BybitRest(private val baseUrl: String, private val category: String = "linear") {

    data class KlinesResult(
        val rows: List<List<Double>>,
        val source: String,
        val error: String? = null
    )

    data class FallbackStats(
        val price: Double? = null,
        val change24hPct: Double? = null,
        val volume24h: Double? = null,
        val turnover24h: Double? = null,
        val fundingPct: Double? = null,
        val openInterest: Double? = null,
        val lsr: Double? = null,
        val lsrPct: Double? = null,
        val source: String = ""
    )

    val http: OkHttpClient = NetworkHub.restClient(connectTimeoutSec = 5L, readTimeoutSec = 6L, writeTimeoutSec = 8L)

    private val bybitCooldownUntilMs = AtomicLong(0L)
    private val klineCache = java.util.concurrent.ConcurrentHashMap<String, CachedKlines>()

    private data class CachedKlines(val result: KlinesResult, val savedMs: Long)

    fun isBybitInCooldown(): Boolean = System.currentTimeMillis() < bybitCooldownUntilMs.get()

    private fun markBybitDown() {
        // Короткий circuit breaker (60с), чтобы при геоблоке Bybit свечи и чат
        // не ждали по 8 секунд на каждом запросе.
        bybitCooldownUntilMs.set(System.currentTimeMillis() + 60_000L)
    }

    private fun clearBybitCooldown() {
        bybitCooldownUntilMs.set(0L)
    }

    /** Последний кэш свечей (до maxAgeMs) для мгновенного разогрева движка/графика. */
    fun cachedKlines(symbol: String, interval: String, maxAgeMs: Long = 180_000L): KlinesResult? {
        val c = klineCache["$symbol:$interval"] ?: return null
        return if (System.currentTimeMillis() - c.savedMs <= maxAgeMs && c.result.rows.isNotEmpty()) c.result else null
    }

    fun putCachedKlines(symbol: String, interval: String, res: KlinesResult) {
        if (res.rows.size >= 2) {
            klineCache["$symbol:$interval"] = CachedKlines(res, System.currentTimeMillis())
        }
    }

    /** GET /v5/market/tickers?category=$category — цена/funding/OI/24ч одним вызовом. */
    fun tickers(symbol: String, onDone: (JSONObject?) -> Unit) {
        get("/v5/market/tickers?category=$category&symbol=$symbol") { body ->
            onDone(body?.optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0))
        }
    }

    /** GET /v5/market/kline — свечи [[tsMs,o,h,l,c,v,turnover],...] новые сверху. */
    fun klines(symbol: String, interval: String, limit: Int, onDone: (JSONArray?) -> Unit) {
        get("/v5/market/kline?category=$category&symbol=$symbol&interval=$interval&limit=$limit") { body ->
            onDone(body?.optJSONObject("result")?.optJSONArray("list"))
        }
    }

    /** Синхронные свечи в формате Bybit (новые сверху) — сохранено для совместимости. */
    fun klinesSync(symbol: String, interval: String, limit: Int): JSONArray? {
        val unified = klinesUnifiedSync(symbol, interval, limit)
        if (unified.rows.isEmpty()) return null
        val out = JSONArray()
        for (i in unified.rows.size - 1 downTo 0) {
            val r = unified.rows[i]
            out.put(
                JSONArray()
                    .put(r[0].toLong())
                    .put(r[1])
                    .put(r[2])
                    .put(r[3])
                    .put(r[4])
                    .put(r[5])
            )
        }
        return out
    }

    /**
     * Унифицированная синхронная загрузка свечей (строго старые -> новые):
     * Bybit V5 -> (Bytick зеркало) -> Binance Vision / api1 / api3 -> OKX public.
     */
    fun klinesUnifiedSync(symbol: String, interval: String, limit: Int): KlinesResult {
        cachedKlines(symbol, interval, maxAgeMs = 20_000L)?.let { c ->
            if (c.rows.size >= minOf(limit, 120)) return c
        }
        var lastErr: String? = null
        if (!isBybitInCooldown()) {
            val bybitUrls = buildList {
                add(baseUrl.trimEnd('/') + "/v5/market/kline?category=$category&symbol=$symbol&interval=$interval&limit=$limit")
                if (baseUrl.contains("api.bybit.com")) {
                    add("https://api.bytick.com/v5/market/kline?category=$category&symbol=$symbol&interval=$interval&limit=$limit")
                }
            }
            for ((idx, url) in bybitUrls.withIndex()) {
                val (json, err) = executeText(url)
                if (json != null) {
                    val parsed = parseBybitKlines(json)
                    if (parsed.size >= 2) {
                        clearBybitCooldown()
                        val src = if (idx == 0) "bybit" else "bytick"
                        return KlinesResult(parsed, src).also { putCachedKlines(symbol, interval, it) }
                    }
                    lastErr = "bybit: пустой ответ"
                } else {
                    lastErr = err
                }
            }
            markBybitDown()
        }

        if (category == "inverse") return KlinesResult(emptyList(), "bybit", lastErr ?: "Inverse candles unavailable")

        // Fallback 1: Binance Vision + публичные зеркала api1/api3
        val bnIv = toBinanceInterval(interval)
        val bnSym = toBinanceSymbol(symbol)
        val bnLim = limit.coerceIn(10, 1000)
        var bnErr: String? = null
        val bnMirrors = listOf(
            "https://data-api.binance.vision/api/v3/klines?symbol=$bnSym&interval=$bnIv&limit=$bnLim",
            "https://api1.binance.com/api/v3/klines?symbol=$bnSym&interval=$bnIv&limit=$bnLim",
            "https://api3.binance.com/api/v3/klines?symbol=$bnSym&interval=$bnIv&limit=$bnLim"
        )
        for (bnUrl in bnMirrors) {
            val (bnText, err) = executeText(bnUrl)
            if (bnText != null) {
                val parsed = parseBinanceKlines(bnText)
                if (parsed.size >= 2) {
                    return KlinesResult(parsed, "binance").also { putCachedKlines(symbol, interval, it) }
                }
            } else {
                bnErr = err
            }
        }

        // Fallback 2: OKX Public Candles API
        val okxIv = toOkxInterval(interval)
        val okxInst = toOkxInstId(symbol)
        val okxUrl = "https://www.okx.com/api/v5/market/candles?instId=$okxInst&bar=$okxIv&limit=${limit.coerceIn(10, 300)}"
        val (okxText, okxErr) = executeText(okxUrl)
        if (okxText != null) {
            val parsed = parseOkxKlines(okxText)
            if (parsed.size >= 2) {
                return KlinesResult(parsed, "okx").also { putCachedKlines(symbol, interval, it) }
            }
        }

        // Fallback 3: Bitget V2 Public Candles API
        val bgIv = toBitgetInterval(interval)
        val bgUrl = "https://api.bitget.com/api/v2/spot/market/candles?symbol=$bnSym&granularity=$bgIv&limit=${limit.coerceIn(10, 200)}"
        val (bgText, bgErr) = executeText(bgUrl)
        if (bgText != null) {
            val parsed = parseBitgetKlines(bgText)
            if (parsed.size >= 2) {
                return KlinesResult(parsed, "bitget").also { putCachedKlines(symbol, interval, it) }
            }
        }

        // Fallback 4: Gate.io V4 Public Candles API
        val gatePair = toGatePair(symbol)
        val gtIv = toGateInterval(interval)
        val gtUrl = "https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=$gatePair&interval=$gtIv&limit=${limit.coerceIn(10, 300)}"
        val (gtText, gtErr) = executeText(gtUrl)
        if (gtText != null) {
            val parsed = parseGateSpotKlines(gtText)
            if (parsed.size >= 2) {
                return KlinesResult(parsed, "gate").also { putCachedKlines(symbol, interval, it) }
            }
        }

        // Fallback 5: KuCoin Public Candles API
        val kcPair = toKucoinPair(symbol)
        val kcIv = toKucoinInterval(interval)
        val kcUrl = "https://api.kucoin.com/api/v1/market/candles?type=$kcIv&symbol=$kcPair"
        val (kcText, kcErr) = executeText(kcUrl)
        if (kcText != null) {
            val parsed = parseKucoinKlines(kcText)
            if (parsed.size >= 2) {
                return KlinesResult(parsed, "kucoin").also { putCachedKlines(symbol, interval, it) }
            }
        }

        // Последний шанс: устаревший, но непустой локальный кэш (до 15 минут)
        cachedKlines(symbol, interval, maxAgeMs = 900_000L)?.let { return it }

        val fullErr = listOfNotNull(lastErr, bnErr, okxErr, bgErr, gtErr, kcErr).joinToString(" | ").ifBlank { "нет ответа" }
        FaultMonitor.recordFault(
            component = "REST Свечи ($symbol)",
            kind = FaultMonitor.Kind.NETWORK,
            severity = FaultMonitor.Severity.ERROR,
            message = "Все биржи недоступны (Bybit/Binance/OKX/Bitget/Gate/KuCoin): $fullErr",
            recovery = "Повтор на следующем тике конвейера"
        )
        return KlinesResult(
            rows = emptyList(),
            source = "none",
            error = fullErr
        )
    }

    /** Асинхронная загрузка свечей с автоматическим fallback на Binance/OKX. */
    fun klinesWithFallback(
        symbol: String,
        interval: String,
        limit: Int,
        onDone: (KlinesResult) -> Unit
    ) {
        restExec.execute {
            val res = klinesUnifiedSync(symbol, interval, limit)
            onDone(res)
        }
    }

    /** Резервные рыночные метрики (24ч тикер Binance + funding/OI/LSR OKX) при недоступности Bybit. */
    fun fetchFallbackStatsSync(symbol: String): FallbackStats {
        var price: Double? = null
        var chg: Double? = null
        var vol: Double? = null
        var turn: Double? = null
        var funding: Double? = null
        var oi: Double? = null
        var lsr: Double? = null
        var lsrPct: Double? = null
        val sources = mutableListOf<String>()

        // 1) Binance 24hr ticker (с зеркалами)
        val bnSym = toBinanceSymbol(symbol)
        val bnTxt = listOf(
            "https://data-api.binance.vision/api/v3/ticker/24hr?symbol=$bnSym",
            "https://api1.binance.com/api/v3/ticker/24hr?symbol=$bnSym"
        ).firstNotNullOfOrNull { url -> executeText(url).first }
        if (bnTxt != null) {
            try {
                val obj = JSONObject(bnTxt)
                obj.optDouble("lastPrice", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.let { price = it }
                obj.optDouble("priceChangePercent", Double.NaN).takeIf { !it.isNaN() }?.let { chg = it }
                obj.optDouble("volume", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.let { vol = it }
                obj.optDouble("quoteVolume", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.let { turn = it }
                sources.add("binance")
            } catch (_: Exception) {
            }
        }

        // 2) OKX ticker (если Binance не дал цену) + funding + OI + LSR
        val inst = toOkxInstId(symbol)
        if (price == null) {
            val (tTxt, _) = executeText("https://www.okx.com/api/v5/market/ticker?instId=$inst")
            if (tTxt != null) {
                try {
                    val d = JSONObject(tTxt).optJSONArray("data")?.optJSONObject(0)
                    val last = d?.optDouble("last", 0.0) ?: 0.0
                    val open24 = d?.optDouble("open24h", 0.0) ?: 0.0
                    if (last > 0) price = last
                    if (last > 0 && open24 > 0) chg = (last - open24) / open24 * 100.0
                    d?.optDouble("volCcy24h", 0.0)?.takeIf { it > 0 }?.let { vol = it }
                    sources.add("okx")
                } catch (_: Exception) {
                }
            }
        }

        val (fTxt, _) = executeText("https://www.okx.com/api/v5/public/funding-rate?instId=$inst")
        if (fTxt != null) {
            try {
                val d = JSONObject(fTxt).optJSONArray("data")?.optJSONObject(0)
                val fr = d?.optString("fundingRate")?.toDoubleOrNull()
                if (fr != null) {
                    funding = fr * 100.0
                    if ("okx" !in sources) sources.add("okx")
                }
            } catch (_: Exception) {
            }
        }

        val (oiTxt, _) = executeText("https://www.okx.com/api/v5/public/open-interest?instId=$inst")
        if (oiTxt != null) {
            try {
                val d = JSONObject(oiTxt).optJSONArray("data")?.optJSONObject(0)
                val oiVal = d?.optString("oiCcy")?.toDoubleOrNull()
                    ?: d?.optString("oi")?.toDoubleOrNull()
                if (oiVal != null && oiVal > 0) oi = oiVal
            } catch (_: Exception) {
            }
        }

        val baseCcy = baseCurrency(symbol)
        val (lsrTxt, _) = executeText("https://www.okx.com/api/v5/rubik/stat/contracts/long-short-account-ratio?ccy=$baseCcy&period=1H")
        if (lsrTxt != null) {
            try {
                val arr = JSONObject(lsrTxt).optJSONArray("data")
                if (arr != null && arr.length() > 0) {
                    val ratios = (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONArray(i)?.optString(1)?.toDoubleOrNull()?.takeIf { it > 0 }
                    }
                    if (ratios.isNotEmpty()) {
                        val cur = ratios[0]
                        lsr = cur
                        lsrPct = 100.0 * ratios.count { it <= cur } / ratios.size
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3) Бесплатные публичные резервы тикера (Bitget / Gate.io / KuCoin) при отказе Bybit+Binance+OKX
        if (price == null) {
            val bgTxt = executeText("https://api.bitget.com/api/v2/spot/market/tickers?symbol=$bnSym").first
            if (bgTxt != null) {
                try {
                    val d = JSONObject(bgTxt).optJSONArray("data")?.optJSONObject(0)
                    val p = d?.optString("lastPr")?.toDoubleOrNull()?.takeIf { it > 0 }
                    val c = d?.optString("changeUtc24h")?.toDoubleOrNull()?.times(100.0)
                    val v = d?.optString("baseVolume")?.toDoubleOrNull()?.takeIf { it > 0 }
                    if (p != null) {
                        price = p
                        if (chg == null && c != null && c.isFinite()) chg = c
                        if (vol == null && v != null) vol = v
                        sources.add("bitget")
                    }
                } catch (_: Exception) {
                }
            }
        }
        if (price == null) {
            val gtPair = toGatePair(symbol)
            val gtTxt = executeText("https://api.gateio.ws/api/v4/spot/tickers?currency_pair=$gtPair").first
            if (gtTxt != null) {
                try {
                    val d = JSONArray(gtTxt).optJSONObject(0)
                    val p = d?.optString("last")?.toDoubleOrNull()?.takeIf { it > 0 }
                    val c = d?.optString("change_percentage")?.toDoubleOrNull()
                    val v = d?.optString("base_volume")?.toDoubleOrNull()?.takeIf { it > 0 }
                    if (p != null) {
                        price = p
                        if (chg == null && c != null && c.isFinite()) chg = c
                        if (vol == null && v != null) vol = v
                        sources.add("gate")
                    }
                } catch (_: Exception) {
                }
            }
        }
        if (price == null) {
            val kcPair = toKucoinPair(symbol)
            val kcTxt = executeText("https://api.kucoin.com/api/v1/market/stats?symbol=$kcPair").first
            if (kcTxt != null) {
                try {
                    val d = JSONObject(kcTxt).optJSONObject("data")
                    val p = d?.optString("last")?.toDoubleOrNull()?.takeIf { it > 0 }
                    val c = d?.optString("changeRate")?.toDoubleOrNull()?.times(100.0)
                    val v = d?.optString("vol")?.toDoubleOrNull()?.takeIf { it > 0 }
                    if (p != null) {
                        price = p
                        if (chg == null && c != null && c.isFinite()) chg = c
                        if (vol == null && v != null) vol = v
                        sources.add("kucoin")
                    }
                } catch (_: Exception) {
                }
            }
        }

        return FallbackStats(
            price = price?.takeIf { it.isFinite() && it > 0 },
            change24hPct = chg?.takeIf { it.isFinite() },
            volume24h = vol?.takeIf { it.isFinite() && it > 0 },
            turnover24h = turn?.takeIf { it.isFinite() && it > 0 },
            fundingPct = funding?.takeIf { it.isFinite() },
            openInterest = oi?.takeIf { it.isFinite() && it > 0 },
            lsr = lsr?.takeIf { it.isFinite() && it > 0 },
            lsrPct = lsrPct?.takeIf { it.isFinite() },
            source = sources.joinToString("+")
        )
    }

    /** GET /v5/market/orderbook — стакан: result.b / result.a = [[price, qty]...]. */
    fun orderbook(symbol: String, limit: Int, onDone: (JSONObject?) -> Unit) {
        get("/v5/market/orderbook?category=$category&symbol=$symbol&limit=$limit") { body ->
            onDone(body?.optJSONObject("result"))
        }
    }

    /** GET /v5/market/recent-trade — лента сделок (price/size/side/time). */
    fun recentTrades(symbol: String, limit: Int, onDone: (JSONArray?) -> Unit) {
        get("/v5/market/recent-trade?category=$category&symbol=$symbol&limit=$limit") { body ->
            onDone(body?.optJSONObject("result")?.optJSONArray("list"))
        }
    }

    /** GET /v5/market/account-ratio — LSR: buyRatio/sellRatio, новые сверху. */
    fun accountRatio(symbol: String, limit: Int = 8, onDone: (JSONArray?) -> Unit) {
        get("/v5/market/account-ratio?category=$category&symbol=$symbol&period=1h&limit=$limit") { body ->
            onDone(body?.optJSONObject("result")?.optJSONArray("list"))
        }
    }

    /** GET /v5/market/open-interest — история OI. */
    fun openInterest(symbol: String, limit: Int, onDone: (JSONArray?) -> Unit) {
        get("/v5/market/open-interest?category=$category&symbol=$symbol&intervalTime=1h&limit=$limit") { body ->
            onDone(body?.optJSONObject("result")?.optJSONArray("list"))
        }
    }

    /** GET /v5/market/funding/history — история funding. */
    fun fundingHistory(symbol: String, limit: Int, onDone: (JSONArray?) -> Unit) {
        get("/v5/market/funding/history?category=$category&symbol=$symbol&limit=$limit") { body ->
            onDone(body?.optJSONObject("result")?.optJSONArray("list"))
        }
    }

    /** GET /v5/market/instruments-info — спецификации контракта. */
    fun instrumentsInfo(symbol: String, onDone: (JSONObject?) -> Unit) {
        get("/v5/market/instruments-info?category=$category&symbol=$symbol") { body ->
            onDone(body?.optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0))
        }
    }

    /** GET /v5/market/risk-limit — лимиты риска. */
    fun riskLimit(symbol: String, onDone: (JSONObject?) -> Unit) {
        get("/v5/market/risk-limit?category=$category&symbol=$symbol") { body ->
            val list = body?.optJSONObject("result")?.optJSONArray("list")
            onDone(
                if (list == null || list.length() == 0) null
                else (0 until list.length())
                    .map { list.optJSONObject(it) }
                    .firstOrNull { it?.optString("isLowestRisk") == "1" }
                    ?: list.optJSONObject(0)
            )
        }
    }

    /** Проверка доступности Bybit V5 /v5/market/time (с разбором result.timeSecond и HTTP-кода). */
    fun ping(onDone: (Boolean, String) -> Unit) {
        restExec.execute {
            val t0 = System.currentTimeMillis()
            val url = baseUrl.trimEnd('/') + "/v5/market/time"
            val (txt, err) = executeText(url)
            val ms = System.currentTimeMillis() - t0
            if (txt != null) {
                try {
                    val body = JSONObject(txt)
                    val rc = body.optInt("retCode", -1)
                    if (rc == 0) {
                        clearBybitCooldown()
                        val resObj = body.optJSONObject("result")
                        val sec = resObj?.optString("timeSecond")?.takeIf { it.isNotBlank() }
                            ?: (body.optLong("time", 0L) / 1000L).takeIf { it > 0 }?.toString()
                            ?: "ok"
                        onDone(true, "HTTP 200, ${ms}мс (t=$sec)")
                        return@execute
                    }
                    onDone(false, "retCode=$rc: ${body.optString("retMsg")}")
                    return@execute
                } catch (_: Exception) {
                }
            }
            markBybitDown()
            onDone(false, err ?: "нет ответа (fallback: Binance/OKX)")
        }
    }

    private fun executeText(url: String): Pair<String?, String?> {
        val host = NetworkHub.extractHost(url)
        if (NetworkHub.isHostRateLimited(host)) {
            return null to "HTTP 429 cooldown ($host)"
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "ena-terminal/9.0")
            .header("Accept", "application/json")
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    val msg = if (code == 403) "HTTP 403 (блокировка региона/WAF)" else "HTTP $code"
                    if (code == 429) {
                        val retrySec = resp.header("Retry-After")?.toLongOrNull() ?: 20L
                        NetworkHub.recordRateLimit(host, retrySec)
                    }
                    if (code >= 500 || code == 429) {
                        FaultMonitor.recordFault(
                            component = "REST ($host)",
                            kind = FaultMonitor.Kind.NETWORK,
                            severity = FaultMonitor.Severity.WARNING,
                            message = "$msg на $host",
                            recovery = "Переключение на резервное зеркало"
                        )
                    }
                    return null to msg
                }
                val body = resp.body?.string()
                if (body.isNullOrBlank()) {
                    null to "пустой ответ"
                } else {
                    NetworkHub.clearRateLimit(host)
                    FaultMonitor.markHealthy("REST ($host)")
                    body to null
                }
            }
        } catch (e: Exception) {
            null to (e.message?.take(64) ?: e.javaClass.simpleName)
        }
    }

    private fun get(path: String, parse: (JSONObject?) -> Unit) {
        val req = Request.Builder().url(baseUrl.trimEnd('/') + path)
            .header("User-Agent", "ena-mind/8-android")
            .header("Accept", "application/json")
            .build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                markBybitDown()
                parse(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful) {
                            markBybitDown()
                            return parse(null)
                        }
                        val txt = it.body?.string() ?: return parse(null)
                        val json = JSONObject(txt)
                        if (json.optInt("retCode", -1) == 0) clearBybitCooldown()
                        parse(json)
                    } catch (_: Exception) {
                        parse(null)
                    }
                }
            }
        })
    }

    companion object {
        private val restExec = Executors.newFixedThreadPool(3) { r ->
            Thread(r, "BybitRest-Pool").apply { isDaemon = true }
        }

        fun baseCurrency(symbol: String): String =
            symbol.uppercase()
                .removeSuffix("-SWAP")
                .removeSuffix("-USDT")
                .removeSuffix("USDT")
                .removeSuffix("USDC")
                .removeSuffix("USD")
                .ifBlank { "BTC" }

        fun toBinanceSymbol(symbol: String): String = baseCurrency(symbol) + "USDT"

        fun toOkxInstId(symbol: String): String = baseCurrency(symbol) + "-USDT-SWAP"

        fun toBinanceInterval(iv: String): String = when (iv) {
            "1" -> "1m"
            "3" -> "3m"
            "5" -> "5m"
            "15" -> "15m"
            "30" -> "30m"
            "60" -> "1h"
            "120" -> "2h"
            "240" -> "4h"
            "D", "d" -> "1d"
            else -> "1h"
        }

        fun toOkxInterval(iv: String): String = when (iv) {
            "1" -> "1m"
            "3" -> "3m"
            "5" -> "5m"
            "15" -> "15m"
            "30" -> "30m"
            "60" -> "1H"
            "120" -> "2H"
            "240" -> "4H"
            "D", "d" -> "1D"
            else -> "1H"
        }

        fun toGatePair(symbol: String): String = baseCurrency(symbol) + "_USDT"

        fun toKucoinPair(symbol: String): String = baseCurrency(symbol) + "-USDT"

        fun toBitgetInterval(iv: String): String = when (iv) {
            "1" -> "1min"
            "3" -> "3min"
            "5" -> "5min"
            "15" -> "15min"
            "30" -> "30min"
            "60" -> "1h"
            "120" -> "2h"
            "240" -> "4h"
            "D", "d" -> "1day"
            else -> "1h"
        }

        fun toGateInterval(iv: String): String = when (iv) {
            "1" -> "1m"
            "3" -> "5m"
            "5" -> "5m"
            "15" -> "15m"
            "30" -> "30m"
            "60" -> "1h"
            "120" -> "2h"
            "240" -> "4h"
            "D", "d" -> "1d"
            else -> "1h"
        }

        fun toKucoinInterval(iv: String): String = when (iv) {
            "1" -> "1min"
            "3" -> "3min"
            "5" -> "5min"
            "15" -> "15min"
            "30" -> "30min"
            "60" -> "1hour"
            "120" -> "2hour"
            "240" -> "4hour"
            "D", "d" -> "1day"
            else -> "1hour"
        }

        /** Парсит ответ Bybit V5 `/v5/market/kline` (новые сверху -> старые к новым). */
        fun parseBybitKlines(rawJson: String): List<List<Double>> {
            return try {
                val root = JSONObject(rawJson)
                if (root.optInt("retCode", -1) != 0) return emptyList()
                val arr = root.optJSONObject("result")?.optJSONArray("list") ?: return emptyList()
                val out = ArrayList<List<Double>>(arr.length())
                for (i in arr.length() - 1 downTo 0) {
                    val k = arr.optJSONArray(i) ?: continue
                    val ts = k.optString(0).toDoubleOrNull() ?: k.optDouble(0, Double.NaN)
                    val o = k.optString(1).toDoubleOrNull() ?: k.optDouble(1, Double.NaN)
                    val h = k.optString(2).toDoubleOrNull() ?: k.optDouble(2, Double.NaN)
                    val l = k.optString(3).toDoubleOrNull() ?: k.optDouble(3, Double.NaN)
                    val c = k.optString(4).toDoubleOrNull() ?: k.optDouble(4, Double.NaN)
                    val v = k.optString(5).toDoubleOrNull() ?: k.optDouble(5, Double.NaN)
                    if (listOf(ts, o, h, l, c, v).all { it.isFinite() } && o > 0 && c > 0) {
                        out.add(listOf(ts, o, h, l, c, v))
                    }
                }
                out.distinctBy { it[0].toLong() }.sortedBy { it[0] }
            } catch (_: Exception) {
                emptyList()
            }
        }

        /** Парсит ответ Binance `/api/v3/klines` (уже старые -> новые). */
        fun parseBinanceKlines(rawJson: String): List<List<Double>> {
            return try {
                val arr = JSONArray(rawJson)
                val out = ArrayList<List<Double>>(arr.length())
                for (i in 0 until arr.length()) {
                    val k = arr.optJSONArray(i) ?: continue
                    val ts = k.optDouble(0, Double.NaN)
                    val o = k.optString(1).toDoubleOrNull() ?: k.optDouble(1, Double.NaN)
                    val h = k.optString(2).toDoubleOrNull() ?: k.optDouble(2, Double.NaN)
                    val l = k.optString(3).toDoubleOrNull() ?: k.optDouble(3, Double.NaN)
                    val c = k.optString(4).toDoubleOrNull() ?: k.optDouble(4, Double.NaN)
                    val v = k.optString(5).toDoubleOrNull() ?: k.optDouble(5, Double.NaN)
                    if (listOf(ts, o, h, l, c, v).all { it.isFinite() } && o > 0 && c > 0) {
                        out.add(listOf(ts, o, h, l, c, v))
                    }
                }
                out.distinctBy { it[0].toLong() }.sortedBy { it[0] }
            } catch (_: Exception) {
                emptyList()
            }
        }

        /** Парсит ответ OKX `/api/v5/market/candles`. */
        fun parseOkxKlines(rawJson: String): List<List<Double>> {
            return try {
                val root = JSONObject(rawJson)
                if (root.optString("code") != "0") return emptyList()
                val arr = root.optJSONArray("data") ?: return emptyList()
                val out = ArrayList<List<Double>>(arr.length())
                for (i in arr.length() - 1 downTo 0) {
                    val k = arr.optJSONArray(i) ?: continue
                    val ts = k.optString(0).toDoubleOrNull() ?: k.optDouble(0, Double.NaN)
                    val o = k.optString(1).toDoubleOrNull() ?: k.optDouble(1, Double.NaN)
                    val h = k.optString(2).toDoubleOrNull() ?: k.optDouble(2, Double.NaN)
                    val l = k.optString(3).toDoubleOrNull() ?: k.optDouble(3, Double.NaN)
                    val c = k.optString(4).toDoubleOrNull() ?: k.optDouble(4, Double.NaN)
                    val v = k.optString(5).toDoubleOrNull() ?: k.optDouble(5, Double.NaN)
                    if (listOf(ts, o, h, l, c, v).all { it.isFinite() } && o > 0 && c > 0) {
                        out.add(listOf(ts, o, h, l, c, v))
                    }
                }
                out.distinctBy { it[0].toLong() }.sortedBy { it[0] }
            } catch (_: Exception) {
                emptyList()
            }
        }

        /** Bitget V2 `/api/v2/spot/market/candles`: [tsMs, open, high, low, close, baseVol]. */
        fun parseBitgetKlines(rawJson: String): List<List<Double>> {
            return try {
                val root = JSONObject(rawJson)
                if (root.optString("code") != "00000") return emptyList()
                val arr = root.optJSONArray("data") ?: return emptyList()
                val out = ArrayList<List<Double>>(arr.length())
                for (i in 0 until arr.length()) {
                    val k = arr.optJSONArray(i) ?: continue
                    val ts = k.optString(0).toDoubleOrNull() ?: k.optDouble(0, Double.NaN)
                    val o = k.optString(1).toDoubleOrNull() ?: k.optDouble(1, Double.NaN)
                    val h = k.optString(2).toDoubleOrNull() ?: k.optDouble(2, Double.NaN)
                    val l = k.optString(3).toDoubleOrNull() ?: k.optDouble(3, Double.NaN)
                    val c = k.optString(4).toDoubleOrNull() ?: k.optDouble(4, Double.NaN)
                    val v = k.optString(5).toDoubleOrNull() ?: k.optDouble(5, Double.NaN)
                    if (listOf(ts, o, h, l, c, v).all { it.isFinite() } && o > 0 && c > 0) {
                        out.add(listOf(ts, o, h, l, c, v))
                    }
                }
                out.distinctBy { it[0].toLong() }.sortedBy { it[0] }
            } catch (_: Exception) {
                emptyList()
            }
        }

        /** Gate.io V4 spot candlesticks: [tsSec, quoteVol, close, high, low, open, baseVol]. */
        fun parseGateSpotKlines(rawJson: String): List<List<Double>> {
            return try {
                val arr = JSONArray(rawJson)
                val out = ArrayList<List<Double>>(arr.length())
                for (i in 0 until arr.length()) {
                    val k = arr.optJSONArray(i) ?: continue
                    val tsSec = k.optString(0).toDoubleOrNull() ?: k.optDouble(0, Double.NaN)
                    val c = k.optString(2).toDoubleOrNull() ?: k.optDouble(2, Double.NaN)
                    val h = k.optString(3).toDoubleOrNull() ?: k.optDouble(3, Double.NaN)
                    val l = k.optString(4).toDoubleOrNull() ?: k.optDouble(4, Double.NaN)
                    val o = k.optString(5).toDoubleOrNull() ?: k.optDouble(5, Double.NaN)
                    val v = k.optString(6).toDoubleOrNull() ?: k.optDouble(6, 0.0)
                    val tsMs = tsSec * 1000.0
                    if (listOf(tsMs, o, h, l, c, v).all { it.isFinite() } && o > 0 && c > 0) {
                        out.add(listOf(tsMs, o, h, l, c, v))
                    }
                }
                out.distinctBy { it[0].toLong() }.sortedBy { it[0] }
            } catch (_: Exception) {
                emptyList()
            }
        }

        /** KuCoin `/api/v1/market/candles`: [tsSec, open, close, high, low, volume, turnover]. */
        fun parseKucoinKlines(rawJson: String): List<List<Double>> {
            return try {
                val root = JSONObject(rawJson)
                if (root.optString("code") != "200000") return emptyList()
                val arr = root.optJSONArray("data") ?: return emptyList()
                val out = ArrayList<List<Double>>(arr.length())
                for (i in 0 until arr.length()) {
                    val k = arr.optJSONArray(i) ?: continue
                    val tsSec = k.optString(0).toDoubleOrNull() ?: k.optDouble(0, Double.NaN)
                    val o = k.optString(1).toDoubleOrNull() ?: k.optDouble(1, Double.NaN)
                    val c = k.optString(2).toDoubleOrNull() ?: k.optDouble(2, Double.NaN)
                    val h = k.optString(3).toDoubleOrNull() ?: k.optDouble(3, Double.NaN)
                    val l = k.optString(4).toDoubleOrNull() ?: k.optDouble(4, Double.NaN)
                    val v = k.optString(5).toDoubleOrNull() ?: k.optDouble(5, 0.0)
                    val tsMs = tsSec * 1000.0
                    if (listOf(tsMs, o, h, l, c, v).all { it.isFinite() } && o > 0 && c > 0) {
                        out.add(listOf(tsMs, o, h, l, c, v))
                    }
                }
                out.distinctBy { it[0].toLong() }.sortedBy { it[0] }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
