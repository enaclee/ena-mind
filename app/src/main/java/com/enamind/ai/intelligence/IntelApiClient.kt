package com.enamind.ai.intelligence

import android.util.Log
import com.enamind.ai.bybit.BybitRest
import com.enamind.ai.core.FaultMonitor
import com.enamind.ai.core.NetworkHub
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Публичный read-only клиент разведданных Chain Radar (без API-ключей):
 * - Binance AggTrades >= $100K (активность китов на споте в реальном времени);
 * - Mempool.space / Blockstream (комиссии сети BTC, загрузка мемпула);
 * - Blockchain.info (крупные неподтверждённые переводы >= 50 BTC);
 * - DefiLlama (общая капитализация стейблкоинов + 7d приток/отток, TVL топ-сетей).
 *
 * Никаких выдуманных значений: при сбое источника возвращается честный статус UNAVAILABLE.
 */
class IntelApiClient {

    data class RawRadarData(
        val symbol: String,
        val fetchedAtMs: Long,
        val whaleTradesCount: Int = 0,
        val whaleBuyUsd: Double = 0.0,
        val whaleSellUsd: Double = 0.0,
        val mempoolTxCount: Int? = null,
        val feeFastSat: Int? = null,
        val feeHalfHourSat: Int? = null,
        val largeBtcTxs: List<LargeTransferItem> = emptyList(),
        val stablecoinsTotalUsd: Double? = null,
        val stablecoinsChange7dPct: Double? = null,
        val defiTotalTvlUsd: Double? = null,
        val topChains: List<Pair<String, Double>> = emptyList(),
        val sources: Map<String, String> = emptyMap()
    )

    private val http: OkHttpClient = NetworkHub.restClient(connectTimeoutSec = 6L, readTimeoutSec = 8L, writeTimeoutSec = 8L)

    private val whaleCache = ConcurrentHashMap<String, Pair<Long, WhaleResult>>()
    @Volatile private var mempoolCache: Pair<Long, MempoolResult>? = null
    @Volatile private var btcTxCache: Pair<Long, List<LargeTransferItem>?>? = null
    @Volatile private var llamaCache: Pair<Long, LlamaResult>? = null

    private data class WhaleResult(val ok: Boolean, val count: Int, val buyUsd: Double, val sellUsd: Double)
    private data class MempoolResult(val ok: Boolean, val txCount: Int?, val feeFast: Int?, val feeHalf: Int?)
    private data class LlamaResult(
        val stableOk: Boolean,
        val tvlOk: Boolean,
        val stableTotalUsd: Double?,
        val stable7dPct: Double?,
        val defiTvlUsd: Double?,
        val topChains: List<Pair<String, Double>>
    )

    fun fetchRadarSnapshotSync(symbol: String, btcSpotPrice: Double? = null, force: Boolean = false): RawRadarData {
        val now = System.currentTimeMillis()
        val whales = fetchWhales(symbol, now, force)
        val mempool = fetchMempool(now, force)
        val largeTxs = fetchLargeBtcTransfers(now, btcSpotPrice, force)
        val llama = fetchDefiAndStablecoins(now, force)

        val statuses = linkedMapOf(
            "Киты Binance (≥$100K)" to if (whales.ok) "LIVE" else "UNAVAILABLE",
            "Мемпул и комиссии BTC" to if (mempool.ok) "LIVE" else "UNAVAILABLE",
            "Крупные переводы BTC (≥50 BTC)" to if (largeTxs != null) "LIVE" else "UNAVAILABLE",
            "Стейблкоины (DefiLlama)" to if (llama.stableOk) "LIVE" else "UNAVAILABLE",
            "DeFi TVL (DefiLlama)" to if (llama.tvlOk) "LIVE" else "UNAVAILABLE"
        )

        return RawRadarData(
            symbol = symbol,
            fetchedAtMs = now,
            whaleTradesCount = whales.count,
            whaleBuyUsd = whales.buyUsd,
            whaleSellUsd = whales.sellUsd,
            mempoolTxCount = mempool.txCount,
            feeFastSat = mempool.feeFast,
            feeHalfHourSat = mempool.feeHalf,
            largeBtcTxs = largeTxs ?: emptyList(),
            stablecoinsTotalUsd = llama.stableTotalUsd,
            stablecoinsChange7dPct = llama.stable7dPct,
            defiTotalTvlUsd = llama.defiTvlUsd,
            topChains = llama.topChains,
            sources = statuses
        )
    }

    private fun fetchWhales(symbol: String, now: Long, force: Boolean): WhaleResult {
        if (!force) {
            whaleCache[symbol]?.let { (ts, cached) ->
                if (now - ts < 45_000L && cached.ok) return cached
            }
        }
        val bnSym = BybitRest.toBinanceSymbol(symbol)
        val urls = listOf(
            "https://data-api.binance.vision/api/v3/aggTrades?symbol=$bnSym&limit=1000",
            "https://api1.binance.com/api/v3/aggTrades?symbol=$bnSym&limit=1000",
            "https://api3.binance.com/api/v3/aggTrades?symbol=$bnSym&limit=1000"
        )
        for (url in urls) {
            val txt = getText(url) ?: continue
            try {
                val arr = JSONArray(txt)
                var count = 0
                var buyUsd = 0.0
                var sellUsd = 0.0
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val px = o.optString("p").toDoubleOrNull() ?: continue
                    val qty = o.optString("q").toDoubleOrNull() ?: continue
                    val usd = px * qty
                    if (usd >= 100_000.0) {
                        count++
                        // m == true означает buyer is maker -> инициатор сделки продавец (рыночная продажа)
                        val isSell = o.optBoolean("m", false)
                        if (isSell) sellUsd += usd else buyUsd += usd
                    }
                }
                val res = WhaleResult(ok = true, count = count, buyUsd = buyUsd, sellUsd = sellUsd)
                whaleCache[symbol] = now to res
                return res
            } catch (e: Exception) {
                Log.w("IntelApi", "whales parse: ${e.message}")
            }
        }
        // Бесплатный резерв OKX public trades при недоступности Binance
        val okxInst = BybitRest.toOkxInstId(symbol)
        val okxTxt = getText("https://www.okx.com/api/v5/market/trades?instId=$okxInst&limit=500")
        if (okxTxt != null) {
            try {
                val arr = JSONObject(okxTxt).optJSONArray("data")
                if (arr != null && arr.length() > 0) {
                    var count = 0; var buyUsd = 0.0; var sellUsd = 0.0
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val px = o.optString("px").toDoubleOrNull() ?: continue
                        val qty = o.optString("sz").toDoubleOrNull() ?: continue
                        val usd = px * qty
                        if (usd >= 100_000.0) {
                            count++
                            if (o.optString("side").equals("sell", ignoreCase = true)) sellUsd += usd else buyUsd += usd
                        }
                    }
                    val res = WhaleResult(ok = true, count = count, buyUsd = buyUsd, sellUsd = sellUsd)
                    whaleCache[symbol] = now to res
                    return res
                }
            } catch (_: Exception) {
            }
        }

        // Резерв Gate.io public trades
        val gatePair = BybitRest.toGatePair(symbol)
        val gateTxt = getText("https://api.gateio.ws/api/v4/spot/trades?currency_pair=$gatePair&limit=500")
        if (gateTxt != null) {
            try {
                val arr = JSONArray(gateTxt)
                var count = 0; var buyUsd = 0.0; var sellUsd = 0.0
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val px = o.optString("price").toDoubleOrNull() ?: continue
                    val qty = o.optString("amount").toDoubleOrNull() ?: continue
                    val usd = px * qty
                    if (usd >= 100_000.0) {
                        count++
                        if (o.optString("side").equals("sell", ignoreCase = true)) sellUsd += usd else buyUsd += usd
                    }
                }
                val res = WhaleResult(ok = true, count = count, buyUsd = buyUsd, sellUsd = sellUsd)
                whaleCache[symbol] = now to res
                return res
            } catch (_: Exception) {
            }
        }

        return whaleCache[symbol]?.second ?: WhaleResult(false, 0, 0.0, 0.0)
    }

    private fun fetchMempool(now: Long, force: Boolean): MempoolResult {
        if (!force) {
            mempoolCache?.let { (ts, cached) ->
                if (now - ts < 90_000L && cached.ok) return cached
            }
        }
        var feeFast: Int? = null
        var feeHalf: Int? = null
        var txCount: Int? = null

        val feesTxt = getText("https://mempool.space/api/v1/fees/recommended")
        if (feesTxt != null) {
            try {
                val obj = JSONObject(feesTxt)
                feeFast = obj.optInt("fastestFee", -1).takeIf { it > 0 }
                feeHalf = obj.optInt("halfHourFee", -1).takeIf { it > 0 }
            } catch (_: Exception) {
            }
        } else {
            val bsTxt = getText("https://blockstream.info/api/fee-estimates")
            if (bsTxt != null) {
                try {
                    val obj = JSONObject(bsTxt)
                    feeFast = obj.optDouble("2", -1.0).takeIf { it > 0 }?.toInt()
                    feeHalf = obj.optDouble("6", -1.0).takeIf { it > 0 }?.toInt()
                } catch (_: Exception) {
                }
            }
        }

        val memTxt = getText("https://mempool.space/api/mempool")
        if (memTxt != null) {
            try {
                val obj = JSONObject(memTxt)
                txCount = obj.optInt("count", -1).takeIf { it >= 0 }
            } catch (_: Exception) {
            }
        } else {
            val bsMem = getText("https://blockstream.info/api/mempool")
            if (bsMem != null) {
                try {
                    txCount = JSONObject(bsMem).optInt("count", -1).takeIf { it >= 0 }
                } catch (_: Exception) {
                }
            }
        }

        val ok = feeFast != null || txCount != null
        val res = MempoolResult(ok, txCount, feeFast, feeHalf)
        if (ok) mempoolCache = now to res
        return res
    }

    private fun fetchLargeBtcTransfers(now: Long, btcPrice: Double?, force: Boolean): List<LargeTransferItem>? {
        if (!force) {
            btcTxCache?.let { (ts, cached) ->
                if (now - ts < 90_000L && cached != null) return cached
            }
        }
        val txt = getText("https://blockchain.info/unconfirmed-transactions?format=json") ?: return btcTxCache?.second
        return try {
            val root = JSONObject(txt)
            val txs = root.optJSONArray("txs") ?: return emptyList()
            val out = ArrayList<LargeTransferItem>()
            for (i in 0 until minOf(txs.length(), 400)) {
                val tx = txs.optJSONObject(i) ?: continue
                val outs = tx.optJSONArray("out") ?: continue
                var satSum = 0.0
                for (j in 0 until outs.length()) {
                    val v = outs.optJSONObject(j)?.optDouble("value", 0.0) ?: 0.0
                    satSum += v
                }
                val btc = satSum / 1e8
                if (btc >= 50.0) {
                    val hash = tx.optString("hash")
                    val shortHash = if (hash.length > 10) "${hash.take(6)}…${hash.takeLast(4)}" else hash
                    val tsSec = tx.optLong("time", now / 1000L)
                    out.add(
                        LargeTransferItem(
                            amountBtc = btc,
                            usdApprox = btcPrice?.takeIf { it > 0 }?.let { btc * it },
                            txHashShort = shortHash,
                            directionLabel = if (outs.length() <= 2) "крупный перевод / консолидация" else "батч / распределение",
                            tsSec = tsSec
                        )
                    )
                }
            }
            val sorted = out.sortedByDescending { it.amountBtc }.take(10)
            btcTxCache = now to sorted
            sorted
        } catch (e: Exception) {
            Log.w("IntelApi", "blockchain.info: ${e.message}")
            btcTxCache?.second
        }
    }

    private fun fetchDefiAndStablecoins(now: Long, force: Boolean): LlamaResult {
        if (!force) {
            llamaCache?.let { (ts, cached) ->
                if (now - ts < 300_000L && (cached.stableOk || cached.tvlOk)) return cached
            }
        }
        var stableOk = false
        var stableTotal: Double? = null
        var stable7dPct: Double? = null

        val stTxt = getText("https://stablecoins.llama.fi/stablecoins?includePrices=false")
        if (stTxt != null) {
            try {
                val assets = JSONObject(stTxt).optJSONArray("peggedAssets")
                if (assets != null && assets.length() > 0) {
                    var sumNow = 0.0
                    var sumPrev7d = 0.0
                    for (i in 0 until minOf(assets.length(), 40)) {
                        val a = assets.optJSONObject(i) ?: continue
                        val curUsd = a.optJSONObject("circulating")?.optDouble("peggedUSD", 0.0) ?: 0.0
                        val prevUsd = a.optJSONObject("circulatingPrevWeek")?.optDouble("peggedUSD", 0.0) ?: 0.0
                        if (curUsd > 0) sumNow += curUsd
                        if (prevUsd > 0) sumPrev7d += prevUsd
                    }
                    if (sumNow > 0) {
                        stableOk = true
                        stableTotal = sumNow
                        if (sumPrev7d > 0) {
                            stable7dPct = (sumNow - sumPrev7d) / sumPrev7d * 100.0
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("IntelApi", "stablecoins: ${e.message}")
            }
        }

        var tvlOk = false
        var totalTvl: Double? = null
        var topChains = emptyList<Pair<String, Double>>()
        val chainsTxt = getText("https://api.llama.fi/v2/chains")
        if (chainsTxt != null) {
            try {
                val arr = JSONArray(chainsTxt)
                val list = ArrayList<Pair<String, Double>>()
                var sum = 0.0
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val name = c.optString("name")
                    val tvl = c.optDouble("tvl", 0.0)
                    if (tvl > 0) {
                        sum += tvl
                        if (name.isNotBlank()) list.add(name to tvl)
                    }
                }
                if (sum > 0) {
                    tvlOk = true
                    totalTvl = sum
                    topChains = list.sortedByDescending { it.second }.take(5)
                }
            } catch (e: Exception) {
                Log.w("IntelApi", "llama chains: ${e.message}")
            }
        }

        val res = LlamaResult(stableOk, tvlOk, stableTotal, stable7dPct, totalTvl, topChains)
        if (stableOk || tvlOk) llamaCache = now to res
        return res
    }

    private fun getText(url: String): String? {
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
                            component = "Radar API ($host)",
                            kind = FaultMonitor.Kind.NETWORK,
                            severity = FaultMonitor.Severity.WARNING,
                            message = "HTTP ${r.code} от $host",
                            recovery = "Используется кэш или резервные зеркала"
                        )
                    }
                    null
                } else {
                    NetworkHub.clearRateLimit(host)
                    FaultMonitor.markHealthy("Radar API ($host)")
                    r.body?.string()?.takeIf { it.isNotBlank() }
                }
            }
        } catch (e: Exception) {
            FaultMonitor.recordFault(
                component = "Radar API ($host)",
                kind = FaultMonitor.Kind.NETWORK,
                severity = FaultMonitor.Severity.WARNING,
                message = (e.message ?: "таймаут сети").take(140),
                recovery = "Используется кэш или резервные зеркала"
            )
            null
        }
    }
}
