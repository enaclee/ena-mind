package com.enamind.ai.bot

import android.util.Log
import com.enamind.ai.core.NetworkHub
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Слой 3: Лёгкий асинхронный сборщик публичных рыночных якорей Bybit V5 (без API-ключей):
 *  - Spot vs Linear Perp CVD Divergence (1m / 5m)
 *  - Open Interest (5m delta + ticker value) & Funding Rate %
 *  - Real-time Liquidations (rolling 15s / 60s Long vs Short)
 *  - Быстрый дрейф EMA(9)/EMA(21), ATR(1m) %, ближайшие S/R (swing + cluster)
 *  - Микроструктурные VPIN и Kyle's Lambda
 */
data class MarketAnchorsSnapshot(
    val symbol: String = "BTCUSDT",
    val updatedAtMs: Long = 0L,
    val spotAvailable: Boolean = false,
    val spotCvd1m: Double = 0.0,
    val perpCvd1m: Double = 0.0,
    val spotCvd5m: Double = 0.0,
    val perpCvd5m: Double = 0.0,
    val spotPerpCvdDivergence: Double = 0.0, // >0: spot сильнее перпа (бычья опора), <0: перп раздут против спота
    val openInterestValue: Double? = null,
    val oiChange5mPct: Double? = null,
    val fundingRatePct: Double? = null,
    val longLiqUsd15s: Double = 0.0,
    val shortLiqUsd15s: Double = 0.0,
    val longLiqUsd60s: Double = 0.0,
    val shortLiqUsd60s: Double = 0.0,
    val ema9: Double? = null,
    val ema21: Double? = null,
    val emaDriftPct: Double = 0.0,
    val atr1mPct: Double = 0.18,
    val nearestSupport: Double? = null,
    val nearestResistance: Double? = null,
    val vpin: Double = 0.0,         // 0.0 .. 1.0 (токсичность потока по volume buckets)
    val kylesLambda: Double = 0.0   // bps сдвига цены на $100k подписанного объёма
) {
    fun summaryLine(): String = buildString {
        append("EMA9/21 ")
        append(String.format(Locale.US, "%+.2f%%", emaDriftPct))
        append(" · ATR ")
        append(String.format(Locale.US, "%.2f%%", atr1mPct))
        append(" · VPIN ")
        append(String.format(Locale.US, "%.2f", vpin))
        val liq15 = longLiqUsd15s + shortLiqUsd15s
        if (liq15 > 0.0) {
            append(" · Liq15s $")
            append(String.format(Locale.US, "%.0f", liq15))
        }
        oiChange5mPct?.let {
            append(" · OI5m ")
            append(String.format(Locale.US, "%+.2f%%", it))
        }
    }
}

class MarketAnchorsCollector(
    private val baseUrlProvider: () -> String = { "https://api.bybit.com" }
) {
    private data class TradePoint(
        val tsMs: Long,
        val price: Double,
        val qty: Double,
        val isBuy: Boolean
    ) {
        val signedQty: Double get() = if (isBuy) qty else -qty
        val notionalUsd: Double get() = price * qty
    }

    private data class LiqPoint(
        val tsMs: Long,
        val isLongLiq: Boolean, // Sell liquidation in Bybit = Long position liquidated
        val usd: Double
    )

    private data class SymbolRestCache(
        var lastOiFetchMs: Long = 0L,
        var oiChange5mPct: Double? = null,
        var openInterestValue: Double? = null,
        var fundingRatePct: Double? = null,
        var lastSpotFetchMs: Long = 0L,
        var spotSupported: Boolean = true,
        var spotCvd1m: Double = 0.0,
        var spotCvd5m: Double = 0.0
    )

    private val http = NetworkHub.restClient(connectTimeoutSec = 5L, readTimeoutSec = 7L)
    private val lock = Any()
    private val perpTradesBySymbol = HashMap<String, ArrayDeque<TradePoint>>()
    private val liqBySymbol = HashMap<String, ArrayDeque<LiqPoint>>()
    private val restCacheBySymbol = ConcurrentHashMap<String, SymbolRestCache>()

    fun recordPerpTrade(symbol: String, price: Double, qty: Double, isBuy: Boolean, tsMs: Long) {
        if (!price.isFinite() || !qty.isFinite() || price <= 0.0 || qty <= 0.0) return
        val sym = symbol.uppercase(Locale.ROOT)
        val effTs = if (tsMs > 0L) tsMs else System.currentTimeMillis()
        synchronized(lock) {
            val dq = perpTradesBySymbol.getOrPut(sym) { ArrayDeque(600) }
            dq.addLast(TradePoint(effTs, price, qty, isBuy))
            val cutoff = effTs - 330_000L
            while (dq.size > 800 || (dq.isNotEmpty() && dq.first().tsMs < cutoff)) {
                dq.removeFirst()
            }
        }
    }

    fun recordTicker(symbol: String, lastPrice: Double, openInterest: Double, fundingRate: Double) {
        val sym = symbol.uppercase(Locale.ROOT)
        val cache = restCacheBySymbol.getOrPut(sym) { SymbolRestCache() }
        if (openInterest.isFinite() && openInterest > 0.0 && lastPrice.isFinite() && lastPrice > 0.0) {
            cache.openInterestValue = openInterest * lastPrice
        }
        if (fundingRate.isFinite()) {
            cache.fundingRatePct = fundingRate * 100.0
        }
    }

    fun recordLiquidation(symbol: String, side: String, price: Double, size: Double, tsMs: Long) {
        val sym = symbol.uppercase(Locale.ROOT)
        val usd = abs(price * size)
        if (!usd.isFinite() || usd <= 0.0) return
        val effTs = if (tsMs > 0L) tsMs else System.currentTimeMillis()
        // On Bybit allLiquidation: side="Sell" means a Long position was liquidated (sold into bids).
        val isLongLiq = side.equals("Sell", ignoreCase = true)
        synchronized(lock) {
            val dq = liqBySymbol.getOrPut(sym) { ArrayDeque(200) }
            dq.addLast(LiqPoint(effTs, isLongLiq, usd))
            val cutoff = effTs - 120_000L
            while (dq.size > 400 || (dq.isNotEmpty() && dq.first().tsMs < cutoff)) {
                dq.removeFirst()
            }
        }
    }

    /** Асинхронный/фоновый REST-поллинг (с жёстким троттлингом интервалов) для Spot CVD и Open Interest. */
    fun pollRestAnchorsIfDue(symbol: String, nowMs: Long = System.currentTimeMillis()) {
        val sym = symbol.uppercase(Locale.ROOT)
        val cache = restCacheBySymbol.getOrPut(sym) { SymbolRestCache() }
        val base = baseUrlProvider().trimEnd('/').ifBlank { "https://api.bybit.com" }
        val host = NetworkHub.extractHost(base)
        if (NetworkHub.isHostRateLimited(host)) return

        // 1. Open Interest 5m delta (раз в 20с)
        if (nowMs - cache.lastOiFetchMs >= 20_000L) {
            cache.lastOiFetchMs = nowMs
            runCatching {
                val req = Request.Builder()
                    .url("$base/v5/market/open-interest?category=linear&symbol=$sym&intervalTime=5min&limit=6")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.code == 429) {
                        NetworkHub.recordRateLimit(host, 20L)
                        return@use
                    }
                    if (!resp.isSuccessful) return@use
                    val list = JSONObject(resp.body?.string().orEmpty())
                        .optJSONObject("result")?.optJSONArray("list")
                    if (list != null && list.length() >= 2) {
                        val newest = list.optJSONObject(0)?.optString("openInterest")?.toDoubleOrNull()
                        val prev = list.optJSONObject(1)?.optString("openInterest")?.toDoubleOrNull()
                        if (newest != null && prev != null && prev > 0.0) {
                            cache.oiChange5mPct = 100.0 * (newest - prev) / prev
                        }
                    }
                }
            }.onFailure {
                Log.w("Anchors", "OI poll $sym: ${it.message}")
            }
        }

        // 2. Spot recent-trade CVD (раз в 5с, только если инструмент есть на споте)
        if (cache.spotSupported && nowMs - cache.lastSpotFetchMs >= 5_000L) {
            cache.lastSpotFetchMs = nowMs
            runCatching {
                val req = Request.Builder()
                    .url("$base/v5/market/recent-trade?category=spot&symbol=$sym&limit=100")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.code == 429) {
                        NetworkHub.recordRateLimit(host, 20L)
                        return@use
                    }
                    if (!resp.isSuccessful) return@use
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val retCode = json.optInt("retCode", -1)
                    if (retCode != 0) {
                        if (retCode == 10001) cache.spotSupported = false
                        return@use
                    }
                    val list = json.optJSONObject("result")?.optJSONArray("list") ?: return@use
                    var cvd1 = 0.0
                    var cvd5 = 0.0
                    for (i in 0 until list.length()) {
                        val o = list.optJSONObject(i) ?: continue
                        val qty = o.optString("size").toDoubleOrNull() ?: continue
                        val side = o.optString("side")
                        val ts = o.optString("time").toLongOrNull() ?: nowMs
                        val signed = if (side.equals("Buy", ignoreCase = true)) qty else -qty
                        if (nowMs - ts <= 60_000L) cvd1 += signed
                        if (nowMs - ts <= 300_000L) cvd5 += signed
                    }
                    cache.spotCvd1m = cvd1
                    cache.spotCvd5m = cvd5
                }
            }.onFailure {
                Log.w("Anchors", "Spot poll $sym: ${it.message}")
            }
        }
    }

    fun snapshot(
        symbol: String,
        bars1m: List<List<Double>>,
        currentPrice: Double?,
        nowMs: Long = System.currentTimeMillis()
    ): MarketAnchorsSnapshot {
        val sym = symbol.uppercase(Locale.ROOT)
        val cache = restCacheBySymbol[sym]

        val (perpCvd1, perpCvd5, vpin, lambda, tradeAnchorPx) = synchronized(lock) {
            val dq = perpTradesBySymbol[sym].orEmpty()
            var c1 = 0.0
            var c5 = 0.0
            for (t in dq) {
                val age = nowMs - t.tsMs
                if (age in 0L..60_000L) c1 += t.signedQty
                if (age in 0L..300_000L) c5 += t.signedQty
            }
            val recent = dq.filter { (nowMs - it.tsMs) in 0L..90_000L }
            val (v, l) = computeVpinAndKylesLambda(recent)
            Quint(c1, c5, v, l, recent.lastOrNull()?.price)
        }

        val (l15, s15, l60, s60) = synchronized(lock) {
            val dq = liqBySymbol[sym].orEmpty()
            var long15 = 0.0
            var short15 = 0.0
            var long60 = 0.0
            var short60 = 0.0
            for (lp in dq) {
                val age = nowMs - lp.tsMs
                if (age in 0L..60_000L) {
                    if (lp.isLongLiq) long60 += lp.usd else short60 += lp.usd
                    if (age <= 15_000L) {
                        if (lp.isLongLiq) long15 += lp.usd else short15 += lp.usd
                    }
                }
            }
            Quad(long15, short15, long60, short60)
        }

        val spot1 = cache?.spotCvd1m ?: 0.0
        val spot5 = cache?.spotCvd5m ?: 0.0
        val spotAvail = cache?.spotSupported == true && (spot1 != 0.0 || spot5 != 0.0)
        val divScore = if (spotAvail) {
            val scale = max(1e-6, max(abs(spot1), abs(perpCvd1)))
            ((spot1 - perpCvd1) / scale).coerceIn(-1.0, 1.0)
        } else 0.0

        val closes = bars1m.mapNotNull { it.getOrNull(4)?.takeIf { p -> p.isFinite() && p > 0.0 } }
        val ema9 = computeEma(closes, 9)
        val ema21 = computeEma(closes, 21)
        val emaDrift = if (ema9 != null && ema21 != null && ema21 > 0.0) {
            100.0 * (ema9 - ema21) / ema21
        } else 0.0
        val atr1m = computeAtr1mPct(bars1m)
        val refPrice = currentPrice?.takeIf { it.isFinite() && it > 0.0 } ?: closes.lastOrNull() ?: tradeAnchorPx
        val (sup, res) = if (refPrice != null && refPrice > 0.0) {
            extractSwingSr(bars1m, refPrice)
        } else null to null

        return MarketAnchorsSnapshot(
            symbol = sym,
            updatedAtMs = nowMs,
            spotAvailable = spotAvail,
            spotCvd1m = spot1,
            perpCvd1m = perpCvd1,
            spotCvd5m = spot5,
            perpCvd5m = perpCvd5,
            spotPerpCvdDivergence = divScore,
            openInterestValue = cache?.openInterestValue,
            oiChange5mPct = cache?.oiChange5mPct,
            fundingRatePct = cache?.fundingRatePct,
            longLiqUsd15s = l15,
            shortLiqUsd15s = s15,
            longLiqUsd60s = l60,
            shortLiqUsd60s = s60,
            ema9 = ema9,
            ema21 = ema21,
            emaDriftPct = emaDrift,
            atr1mPct = atr1m,
            nearestSupport = sup,
            nearestResistance = res,
            vpin = vpin,
            kylesLambda = lambda
        )
    }

    private data class Quad(val a: Double, val b: Double, val c: Double, val d: Double)
    private data class Quint(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double?)

    companion object {
        fun computeEma(values: List<Double>, period: Int): Double? {
            if (values.size < period || period <= 1) return values.lastOrNull()
            val alpha = 2.0 / (period + 1.0)
            var ema = values.take(period).average()
            for (i in period until values.size) {
                ema = alpha * values[i] + (1.0 - alpha) * ema
            }
            return ema
        }

        fun computeAtr1mPct(bars: List<List<Double>>, period: Int = 14): Double {
            if (bars.size < 3) return 0.18
            val slice = bars.takeLast(period + 1)
            val trs = ArrayList<Double>(slice.size)
            for (i in 1 until slice.size) {
                val h = slice[i].getOrNull(2) ?: continue
                val l = slice[i].getOrNull(3) ?: continue
                val prevC = slice[i - 1].getOrNull(4) ?: continue
                if (prevC <= 0.0 || h < l) continue
                val tr = max(h - l, max(abs(h - prevC), abs(l - prevC)))
                trs += 100.0 * tr / prevC
            }
            return if (trs.isNotEmpty()) trs.average().coerceIn(0.04, 3.0) else 0.18
        }

        fun extractSwingSr(bars: List<List<Double>>, currentPrice: Double): Pair<Double?, Double?> {
            if (bars.size < 8) return null to null
            val window = bars.takeLast(60)
            val highs = mutableListOf<Double>()
            val lows = mutableListOf<Double>()
            for (i in 2 until window.size - 2) {
                val h = window[i].getOrNull(2) ?: continue
                val l = window[i].getOrNull(3) ?: continue
                val ph1 = window[i - 1].getOrNull(2) ?: h
                val nh1 = window[i + 1].getOrNull(2) ?: h
                val pl1 = window[i - 1].getOrNull(3) ?: l
                val nl1 = window[i + 1].getOrNull(3) ?: l
                if (h >= ph1 && h >= nh1 && h >= currentPrice * 0.996) highs += h
                if (l <= pl1 && l <= nl1 && l <= currentPrice * 1.004) lows += l
            }
            val sup = lows.filter { it <= currentPrice * 1.002 }.minByOrNull { abs(currentPrice - it) }
            val res = highs.filter { it >= currentPrice * 0.998 }.minByOrNull { abs(it - currentPrice) }
            return sup to res
        }

        private fun computeVpinAndKylesLambda(trades: List<TradePoint>): Pair<Double, Double> {
            if (trades.size < 8) return 0.0 to 0.0
            val totalQty = trades.sumOf { it.qty }
            if (totalQty <= 0.0) return 0.0 to 0.0
            val bucketsCount = min(8, max(3, trades.size / 6))
            val bucketSize = totalQty / bucketsCount
            var curBucketBuy = 0.0
            var curBucketSell = 0.0
            var curVol = 0.0
            val imbalances = ArrayList<Double>(bucketsCount)
            for (t in trades) {
                if (t.isBuy) curBucketBuy += t.qty else curBucketSell += t.qty
                curVol += t.qty
                if (curVol >= bucketSize) {
                    imbalances += abs(curBucketBuy - curBucketSell) / max(1e-9, curVol)
                    curBucketBuy = 0.0
                    curBucketSell = 0.0
                    curVol = 0.0
                }
            }
            if (curVol > bucketSize * 0.25) {
                imbalances += abs(curBucketBuy - curBucketSell) / max(1e-9, curVol)
            }
            val vpin = if (imbalances.isNotEmpty()) imbalances.average().coerceIn(0.0, 1.0) else 0.0

            val pFirst = trades.first().price
            val pLast = trades.last().price
            val moveBps = abs(pLast - pFirst) / max(1e-9, pFirst) * 10_000.0
            val signedNotional = abs(trades.sumOf { it.signedQty * it.price })
            val lambda = if (signedNotional > 100.0) {
                (moveBps / (signedNotional / 100_000.0)).coerceIn(0.0, 200.0)
            } else 0.0
            return vpin to lambda
        }
    }
}
