package com.enamind.ai.bybit

import com.enamind.ai.core.FaultMonitor
import com.enamind.ai.core.NetworkHub
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * MultiVenueDataBridge (ТЗ п.4, п.7) — расширенный высокочастотный мост данных.
 *
 * Помимо Bybit, Binance и OKX собирает публичные потоки Coinbase Exchange и Kraken,
 * оценивает скорость торгов (trades/sec, USD/sec), межбиржевой спред/опережение,
 * дисбаланс глубины стакана и всплески крупных сделок — для разгрузки основного
 * конвейера и упреждающего прогноза в реальном времени.
 */
class MultiVenueDataBridge {

    data class VenueSnapshot(
        val symbol: String,
        val fetchedAtMs: Long,
        val coinbasePrice: Double? = null,
        val coinbaseBid: Double? = null,
        val coinbaseAsk: Double? = null,
        val krakenPrice: Double? = null,
        val krakenVolume24h: Double? = null,
        val krakenBid: Double? = null,
        val krakenAsk: Double? = null,
        val binanceTradeVelocityPerSec: Double? = null,
        val binanceQuoteUsdPerSec: Double? = null,
        val binanceAggressiveBuyPct: Double? = null,
        val binanceWhaleSpikeUsd: Double = 0.0,
        val activeVenueCount: Int = 0,
        val venuePrices: Map<String, Double> = emptyMap()
    )

    private val http: OkHttpClient = NetworkHub.restClient(connectTimeoutSec = 5L, readTimeoutSec = 6L, writeTimeoutSec = 8L)

    private val cache = ConcurrentHashMap<String, VenueSnapshot>()

    fun latest(symbol: String): VenueSnapshot? = cache[symbol]

    fun fetchVenueSnapshotSync(symbol: String): VenueSnapshot {
        val now = System.currentTimeMillis()
        cache[symbol]?.let { prev ->
            if (now - prev.fetchedAtMs < 6_000L && prev.activeVenueCount > 0) return prev
        }

        val prices = LinkedHashMap<String, Double>()
        var cbPx: Double? = null
        var cbBid: Double? = null
        var cbAsk: Double? = null
        var krPx: Double? = null
        var krVol: Double? = null
        var krBid: Double? = null
        var krAsk: Double? = null
        var bnVel: Double? = null
        var bnUsdSec: Double? = null
        var bnBuyPct: Double? = null
        var bnWhaleUsd = 0.0

        // 1. Coinbase Exchange public ticker
        val cbPair = toCoinbasePair(symbol)
        if (cbPair != null) {
            val txt = getText("https://api.exchange.coinbase.com/products/$cbPair/ticker", "Coinbase Ticker")
            if (txt != null) {
                runCatching {
                    val o = JSONObject(txt)
                    val p = o.optString("price").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
                    val b = o.optString("bid").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
                    val a = o.optString("ask").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
                    if (p != null) {
                        cbPx = p; cbBid = b; cbAsk = a
                        prices["coinbase"] = p
                        FaultMonitor.markHealthy("Coinbase Public API")
                    }
                }
            }
        }

        // 2. Kraken public ticker
        val krPair = toKrakenPair(symbol)
        if (krPair != null) {
            val txt = getText("https://api.kraken.com/0/public/Ticker?pair=$krPair", "Kraken Ticker")
            if (txt != null) {
                runCatching {
                    val res = JSONObject(txt).optJSONObject("result")
                    val firstKey = res?.keys()?.takeIf { it.hasNext() }?.next()
                    val t = firstKey?.let { res.optJSONObject(it) }
                    if (t != null) {
                        val p = t.optJSONArray("c")?.optString(0)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
                        val b = t.optJSONArray("b")?.optString(0)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
                        val a = t.optJSONArray("a")?.optString(0)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
                        val v = t.optJSONArray("v")?.optString(1)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
                        if (p != null) {
                            krPx = p; krBid = b; krAsk = a; krVol = v
                            prices["kraken"] = p
                            FaultMonitor.markHealthy("Kraken Public API")
                        }
                    }
                }
            }
        }

        // 3. Binance recent trades velocity & instant whale pulse (легковесный срез 250 сделок)
        val bnSym = BybitRest.toBinanceSymbol(symbol)
        val bnUrls = listOf(
            "https://data-api.binance.vision/api/v3/aggTrades?symbol=$bnSym&limit=250",
            "https://api1.binance.com/api/v3/aggTrades?symbol=$bnSym&limit=250"
        )
        for (u in bnUrls) {
            val txt = getText(u, "Binance AggTrades Velocity") ?: continue
            val parsed = runCatching {
                val arr = JSONArray(txt)
                if (arr.length() >= 10) {
                    var tMin = Long.MAX_VALUE
                    var tMax = Long.MIN_VALUE
                    var buyUsd = 0.0
                    var sellUsd = 0.0
                    var whaleMax = 0.0
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val p = o.optString("p").toDoubleOrNull() ?: continue
                        val q = o.optString("q").toDoubleOrNull() ?: continue
                        val ts = o.optLong("T", 0L)
                        if (ts > 0) {
                            if (ts < tMin) tMin = ts
                            if (ts > tMax) tMax = ts
                        }
                        val usd = p * q
                        if (usd >= 90_000.0 && usd > whaleMax) whaleMax = usd
                        val isSell = o.optBoolean("m", false)
                        if (isSell) sellUsd += usd else buyUsd += usd
                    }
                    val spanSec = ((tMax - tMin).coerceAtLeast(1000L)) / 1000.0
                    val totUsd = buyUsd + sellUsd
                    bnVel = arr.length() / spanSec
                    bnUsdSec = totUsd / spanSec
                    bnBuyPct = if (totUsd > 0) 100.0 * buyUsd / totUsd else 50.0
                    bnWhaleUsd = whaleMax
                    FaultMonitor.markHealthy("Binance AggTrades Velocity")
                    true
                } else false
            }.getOrDefault(false)
            if (parsed) break
        }

        val snap = VenueSnapshot(
            symbol = symbol,
            fetchedAtMs = now,
            coinbasePrice = cbPx,
            coinbaseBid = cbBid,
            coinbaseAsk = cbAsk,
            krakenPrice = krPx,
            krakenVolume24h = krVol,
            krakenBid = krBid,
            krakenAsk = krAsk,
            binanceTradeVelocityPerSec = bnVel,
            binanceQuoteUsdPerSec = bnUsdSec,
            binanceAggressiveBuyPct = bnBuyPct,
            binanceWhaleSpikeUsd = bnWhaleUsd,
            activeVenueCount = prices.size + (if (bnVel != null) 1 else 0),
            venuePrices = prices
        )
        cache[symbol] = snap
        return snap
    }

    private fun getText(url: String, label: String): String? {
        val host = NetworkHub.extractHost(url)
        if (NetworkHub.isHostRateLimited(host)) return null
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "ena-terminal/9.0")
                .header("Accept", "application/json")
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    if (r.code == 429) {
                        NetworkHub.recordRateLimit(host, r.header("Retry-After")?.toLongOrNull() ?: 25L)
                    }
                    if (r.code == 429 || r.code >= 500) {
                        FaultMonitor.recordFault(
                            component = label,
                            kind = FaultMonitor.Kind.NETWORK,
                            severity = FaultMonitor.Severity.WARNING,
                            message = "HTTP ${r.code} от $url",
                            recovery = "Переход на другие биржевые мосты"
                        )
                    }
                    null
                } else {
                    NetworkHub.clearRateLimit(host)
                    r.body?.string()?.takeIf { it.isNotBlank() }
                }
            }
        } catch (e: Exception) {
            FaultMonitor.recordFault(
                component = label,
                kind = FaultMonitor.Kind.NETWORK,
                severity = FaultMonitor.Severity.WARNING,
                message = (e.message ?: "таймаут сети").take(140),
                recovery = "Работают резервные каналы"
            )
            null
        }
    }

    companion object {
        fun toCoinbasePair(symbol: String): String? {
            val base = symbol.removeSuffix("USDT").removeSuffix("USDC")
            return when (base) {
                "BTC", "ETH", "SOL", "XRP", "SUI", "AVAX", "DOGE", "OP", "LINK", "ADA" -> "$base-USD"
                else -> null
            }
        }

        fun toKrakenPair(symbol: String): String? {
            val base = symbol.removeSuffix("USDT").removeSuffix("USDC")
            return when (base) {
                "BTC" -> "XBTUSD"
                "ETH" -> "ETHUSD"
                "SOL" -> "SOLUSD"
                "XRP" -> "XRPUSD"
                "SUI" -> "SUIUSD"
                "AVAX" -> "AVAXUSD"
                "DOGE" -> "DOGEUSD"
                "LINK" -> "LINKUSD"
                "ADA" -> "ADAUSD"
                else -> null
            }
        }
    }
}
