package com.enamind.ai.bybit

import android.content.Context
import android.util.Log
import com.enamind.ai.core.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Динамический каталог всех доступных USDT-контрактов биржи (Bybit Linear -> Binance Futures/Spot -> OKX SWAP).
 * Никаких вымышленных тикеров: загружает реальные спецификации и кэширует их локально.
 */
class ContractCatalogRepo(
    private val ctx: Context,
    private val settings: Settings
) {
    data class ContractInfo(
        val symbol: String,
        val baseCoin: String,
        val quoteCoin: String,
        val tickSize: Double?,
        val minOrderQty: Double?,
        val maxLeverage: Double?,
        val source: String
    )

    data class CatalogState(
        val contracts: List<ContractInfo> = emptyList(),
        val source: String = "не загружен",
        val loading: Boolean = false,
        val error: String? = null,
        val lastSyncMs: Long = 0L
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cacheFile = File(File(ctx.filesDir, "enamind"), "contracts_catalog.json")

    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state

    init {
        loadFromCache()
    }

    fun loadFromCache() {
        try {
            if (!cacheFile.exists()) return
            val root = JSONObject(cacheFile.readText())
            val arr = root.optJSONArray("contracts") ?: return
            val list = ArrayList<ContractInfo>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sym = o.optString("symbol").uppercase(Locale.ROOT)
                if (sym.isBlank()) continue
                list.add(
                    ContractInfo(
                        symbol = sym,
                        baseCoin = o.optString("baseCoin", sym.removeSuffix("USDT")),
                        quoteCoin = o.optString("quoteCoin", "USDT"),
                        tickSize = o.optDouble("tickSize", Double.NaN).takeIf { it.isFinite() && it > 0 },
                        minOrderQty = o.optDouble("minOrderQty", Double.NaN).takeIf { it.isFinite() && it > 0 },
                        maxLeverage = o.optDouble("maxLeverage", Double.NaN).takeIf { it.isFinite() && it > 0 },
                        source = o.optString("source", "cache")
                    )
                )
            }
            if (list.isNotEmpty()) {
                _state.value = CatalogState(
                    contracts = list,
                    source = "${root.optString("source", "кэш")} (кэш)",
                    loading = false,
                    lastSyncMs = root.optLong("syncedAtMs", 0L)
                )
            }
        } catch (e: Exception) {
            Log.w("ContractCatalog", "cache read: ${e.message}")
        }
    }

    fun refreshSync(force: Boolean = false): CatalogState {
        val now = System.currentTimeMillis()
        val cur = _state.value
        if (!force && cur.contracts.size >= 50 && (now - cur.lastSyncMs) < 6 * 3600_000L) {
            return cur
        }
        _state.value = cur.copy(loading = true, error = null)

        val fetched = fetchBybitLinear(settings.bybitBaseUrl)
            ?: fetchBinanceFutures()
            ?: fetchBinanceSpotUsdt()
            ?: fetchOkxSwaps()

        return if (fetched != null && fetched.first.isNotEmpty()) {
            val sorted = fetched.first.distinctBy { it.symbol }.sortedWith(
                compareBy<ContractInfo> {
                    val idx = Settings.SYMBOLS.indexOf(it.symbol)
                    if (idx >= 0) idx else Int.MAX_VALUE
                }.thenBy { it.symbol }
            )
            saveCache(sorted, fetched.second, now)
            val next = CatalogState(
                contracts = sorted,
                source = fetched.second,
                loading = false,
                error = null,
                lastSyncMs = now
            )
            _state.value = next
            next
        } else {
            val err = "Не удалось загрузить каталог контрактов (Bybit/Binance/OKX недоступны)"
            val next = cur.copy(loading = false, error = err)
            _state.value = next
            next
        }
    }

    fun findTickSize(symbol: String): Double? =
        _state.value.contracts.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }?.tickSize

    private fun fetchBybitLinear(baseUrl: String): Pair<List<ContractInfo>, String>? {
        val all = ArrayList<ContractInfo>()
        var cursor = ""
        val bases = listOf(baseUrl.trimEnd('/'), "https://api.bytick.com").distinct()
        for (base in bases) {
            all.clear()
            cursor = ""
            var pages = 0
            var ok = false
            while (pages < 5) {
                pages++
                val cursorParam = if (cursor.isNotBlank()) "&cursor=$cursor" else ""
                val url = "$base/v5/market/instruments-info?category=linear&status=Trading&limit=1000$cursorParam"
                val txt = getText(url) ?: break
                val parsed = parseBybitInstrumentsJson(txt) ?: break
                all.addAll(parsed.first)
                ok = true
                cursor = parsed.second
                if (cursor.isBlank()) break
            }
            if (ok && all.isNotEmpty()) return all to "Bybit"
        }
        return null
    }

    private fun fetchBinanceFutures(): Pair<List<ContractInfo>, String>? {
        val txt = getText("https://fapi.binance.com/fapi/v1/exchangeInfo") ?: return null
        val list = parseBinanceExchangeInfoJson(txt, source = "Binance Futures") ?: return null
        return if (list.isNotEmpty()) list to "Binance Futures" else null
    }

    private fun fetchBinanceSpotUsdt(): Pair<List<ContractInfo>, String>? {
        val urls = listOf(
            "https://data-api.binance.vision/api/v3/exchangeInfo",
            "https://api1.binance.com/api/v3/exchangeInfo"
        )
        for (u in urls) {
            val txt = getText(u) ?: continue
            val list = parseBinanceExchangeInfoJson(txt, source = "Binance") ?: continue
            if (list.isNotEmpty()) return list to "Binance"
        }
        return null
    }

    private fun fetchOkxSwaps(): Pair<List<ContractInfo>, String>? {
        val txt = getText("https://www.okx.com/api/v5/public/instruments?instType=SWAP") ?: return null
        val list = parseOkxInstrumentsJson(txt) ?: return null
        return if (list.isNotEmpty()) list to "OKX" else null
    }

    private fun saveCache(list: List<ContractInfo>, source: String, nowMs: Long) {
        try {
            cacheFile.parentFile?.mkdirs()
            val arr = JSONArray()
            for (c in list) {
                arr.put(
                    JSONObject()
                        .put("symbol", c.symbol)
                        .put("baseCoin", c.baseCoin)
                        .put("quoteCoin", c.quoteCoin)
                        .put("tickSize", c.tickSize ?: JSONObject.NULL)
                        .put("minOrderQty", c.minOrderQty ?: JSONObject.NULL)
                        .put("maxLeverage", c.maxLeverage ?: JSONObject.NULL)
                        .put("source", c.source)
                )
            }
            cacheFile.writeText(
                JSONObject()
                    .put("source", source)
                    .put("syncedAtMs", nowMs)
                    .put("contracts", arr)
                    .toString()
            )
        } catch (e: Exception) {
            Log.w("ContractCatalog", "saveCache: ${e.message}")
        }
    }

    private fun getText(url: String): String? = try {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string()?.takeIf { it.isNotBlank() } else null
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        fun parseBybitInstrumentsJson(rawJson: String): Pair<List<ContractInfo>, String>? {
            return try {
                val root = JSONObject(rawJson)
                if (root.optInt("retCode", -1) != 0) return null
                val res = root.optJSONObject("result") ?: return null
                val arr = res.optJSONArray("list") ?: return null
                val cursor = res.optString("nextPageCursor", "")
                val out = ArrayList<ContractInfo>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val sym = o.optString("symbol").uppercase(Locale.ROOT)
                    val quote = o.optString("quoteCoin").uppercase(Locale.ROOT)
                    val status = o.optString("status")
                    if (sym.isBlank() || quote != "USDT") continue
                    if (status.isNotBlank() && status != "Trading") continue
                    val base = o.optString("baseCoin").uppercase(Locale.ROOT).ifBlank { sym.removeSuffix("USDT") }
                    val tick = o.optJSONObject("priceFilter")?.optString("tickSize")?.toDoubleOrNull()
                    val minQty = o.optJSONObject("lotSizeFilter")?.optString("minOrderQty")?.toDoubleOrNull()
                    val maxLev = o.optJSONObject("leverageFilter")?.optString("maxLeverage")?.toDoubleOrNull()
                    out.add(ContractInfo(sym, base, quote, tick, minQty, maxLev, "Bybit"))
                }
                out to cursor
            } catch (_: Exception) {
                null
            }
        }

        fun parseBinanceExchangeInfoJson(rawJson: String, source: String): List<ContractInfo>? {
            return try {
                val root = JSONObject(rawJson)
                val arr = root.optJSONArray("symbols") ?: return null
                val out = ArrayList<ContractInfo>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val quote = o.optString("quoteAsset").uppercase(Locale.ROOT)
                    val status = o.optString("status")
                    if (quote != "USDT" || status != "TRADING") continue
                    val sym = o.optString("symbol").uppercase(Locale.ROOT)
                    if (!sym.endsWith("USDT") || sym.contains("_")) continue
                    val base = o.optString("baseAsset").uppercase(Locale.ROOT).ifBlank { sym.removeSuffix("USDT") }
                    var tick: Double? = null
                    var minQty: Double? = null
                    val filters = o.optJSONArray("filters")
                    if (filters != null) {
                        for (j in 0 until filters.length()) {
                            val f = filters.optJSONObject(j) ?: continue
                            when (f.optString("filterType")) {
                                "PRICE_FILTER" -> tick = f.optString("tickSize").toDoubleOrNull()?.takeIf { it > 0 }
                                "LOT_SIZE" -> minQty = f.optString("minQty").toDoubleOrNull()?.takeIf { it > 0 }
                            }
                        }
                    }
                    out.add(ContractInfo(sym, base, quote, tick, minQty, null, source))
                }
                out
            } catch (_: Exception) {
                null
            }
        }

        fun parseOkxInstrumentsJson(rawJson: String): List<ContractInfo>? {
            return try {
                val root = JSONObject(rawJson)
                if (root.optString("code") != "0") return null
                val arr = root.optJSONArray("data") ?: return null
                val out = ArrayList<ContractInfo>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val settle = o.optString("settleCcy").uppercase(Locale.ROOT)
                    val state = o.optString("state").lowercase(Locale.ROOT)
                    if (settle != "USDT" || state != "live") continue
                    val base = o.optString("ctValCcy").uppercase(Locale.ROOT)
                    if (base.isBlank()) continue
                    val sym = "${base}USDT"
                    val tick = o.optString("tickSz").toDoubleOrNull()?.takeIf { it > 0 }
                    val minQty = o.optString("minSz").toDoubleOrNull()?.takeIf { it > 0 }
                    val maxLev = o.optString("lever").toDoubleOrNull()?.takeIf { it > 0 }
                    out.add(ContractInfo(sym, base, "USDT", tick, minQty, maxLev, "OKX"))
                }
                out
            } catch (_: Exception) {
                null
            }
        }
    }
}
