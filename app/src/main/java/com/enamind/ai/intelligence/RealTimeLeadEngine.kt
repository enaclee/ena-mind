package com.enamind.ai.intelligence

import com.enamind.ai.bybit.MultiVenueDataBridge
import com.enamind.ai.core.AppState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Оценка живой микроструктуры потока по реальным данным (без краткосрочных предсказаний и без
 * искусственного принуждения направления при отсутствии данных).
 */
object RealTimeLeadEngine {

    data class RealTimeTrendPulse(
        val symbol: String = "BTCUSDT",
        val tsMs: Long = System.currentTimeMillis(),
        val direction: String = "NO_DATA", // "LONG" | "SHORT" | "NEUTRAL" | "NO_DATA"
        val score: Double = 0.0,           // -1.0 .. +1.0
        val confidence: Double = 0.0,      // 0.0 .. 1.0
        val strongTrigger: Boolean = false,
        val triggerReason: String = "",
        val leadWindowLabel: String = "микроструктура (реал-тайм)",
        val factorsSummary: String = "Ожидание живых рыночных данных…",
        val factorVotes: Map<String, String> = emptyMap(),
        val accuracyPct: Double? = null,
        val recentAccuracyPct: Double? = null,
        val verifiedCount: Int = 0,
        val price: Double? = null,
        val sufficientData: Boolean = false
    )

    fun evaluate(
        symbol: String,
        market: AppState.MarketUi,
        bars1m: List<List<Double>>,
        binancePrice: Double,
        okxPrice: Double,
        binanceDepth: Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>>?,
        venueSnap: MultiVenueDataBridge.VenueSnapshot?,
        intel: UnifiedIntelligenceReport?,
        vaultWeights: Map<String, Double>,
        vaultSnap: RealtimePulseVault.PulseSnapshot,
        nowMs: Long = System.currentTimeMillis()
    ): RealTimeTrendPulse {
        val votes = LinkedHashMap<String, String>()
        val reasons = mutableListOf<String>()
        val triggers = mutableListOf<String>()

        var weightedScore = 0.0
        var totalWeight = 0.0

        fun addFactor(id: String, rawScore: Double, baseWeight: Double, label: String?) {
            if (!rawScore.isFinite()) return
            val clamped = rawScore.coerceIn(-1.0, 1.0)
            val learned = (vaultWeights[id] ?: 1.0).coerceIn(0.60, 1.50)
            val w = baseWeight * learned
            weightedScore += clamped * w
            totalWeight += w
            votes[id] = when {
                clamped > 0.12 -> "LONG"
                clamped < -0.12 -> "SHORT"
                else -> "FLAT"
            }
            if (!label.isNullOrBlank()) reasons += label
        }

        // 1. Живая лента сделок
        market.tape?.takeIf { it.n >= 5 && it.deltaPct.isFinite() }?.let { t ->
            val s = tanh(t.deltaPct / 28.0)
            val lbl = String.format(Locale.US, "Лента %+.1f%%", t.deltaPct)
            addFactor("tape_delta", s, 1.25, lbl)
            if (t.n >= 20 && abs(t.deltaPct) >= 35.0) {
                triggers += String.format(Locale.US, "Перекос ленты %+.0f%%", t.deltaPct)
            }
        }

        // 2. Дисбаланс стакана (Bybit + Binance depth)
        val depthScores = mutableListOf<Double>()
        if (market.bids.isNotEmpty() && market.asks.isNotEmpty()) {
            val b = market.bids.sumOf { if (it.qty.isFinite()) it.qty else 0.0 }
            val a = market.asks.sumOf { if (it.qty.isFinite()) it.qty else 0.0 }
            if (b + a > 0.0) depthScores += ((b - a) / (b + a))
        }
        binanceDepth?.let { (bids, asks) ->
            val b = bids.sumOf { if (it.second.isFinite()) it.second else 0.0 }
            val a = asks.sumOf { if (it.second.isFinite()) it.second else 0.0 }
            if (b + a > 0.0) depthScores += ((b - a) / (b + a))
        }
        if (depthScores.isNotEmpty()) {
            val avgImb = depthScores.average()
            val lbl = String.format(Locale.US, "Стакан %+.0f%%", avgImb * 100.0)
            addFactor("book_imbalance", tanh(avgImb * 2.2), 1.0, lbl)
        }

        // 3. Мультибиржевая скорость потока
        var maxSpreadBps = 0.0
        venueSnap?.let { vs ->
            val vel = vs.binanceTradeVelocityPerSec
            val buyPct = vs.binanceAggressiveBuyPct
            if (vel != null && buyPct != null && vel.isFinite() && buyPct.isFinite() && vel > 0.0) {
                val skew = (buyPct - 50.0) / 35.0
                val velBoost = (vel / 12.0).coerceIn(0.6, 1.6)
                val s = tanh(skew * velBoost)
                val lbl = String.format(Locale.US, "Binance %.1f сд/с · пок %.0f%%", vel, buyPct)
                addFactor("venue_velocity", s, 1.3, lbl)
                if (vel >= 25.0 && abs(buyPct - 50.0) >= 16.0) {
                    triggers += String.format(Locale.US, "Всплеск потока %.0f сд/с", vel)
                }
            }
            if (vs.binanceWhaleSpikeUsd >= 90_000.0 && buyPct != null) {
                val whaleDir = if (buyPct >= 50.0) 1.0 else -1.0
                addFactor("whale_spike", whaleDir * 0.85, 1.1, "Крупная сделка $${TrendReasoningEngine.compactUsd(vs.binanceWhaleSpikeUsd)}")
                triggers += "Крупная сделка $${TrendReasoningEngine.compactUsd(vs.binanceWhaleSpikeUsd)}"
            }
        }

        // 4. Межбиржевое расхождение цены
        val livePx = market.price?.takeIf { it.isFinite() && it > 0.0 }
        val venuePrices = LinkedHashMap<String, Double>()
        if (livePx != null) venuePrices["Bybit"] = livePx
        if (binancePrice.isFinite() && binancePrice > 0.0) venuePrices["Binance"] = binancePrice
        if (okxPrice.isFinite() && okxPrice > 0.0) venuePrices["OKX"] = okxPrice
        venueSnap?.coinbasePrice?.takeIf { it.isFinite() && it > 0.0 }?.let { venuePrices["Coinbase"] = it }
        venueSnap?.krakenPrice?.takeIf { it.isFinite() && it > 0.0 }?.let { venuePrices["Kraken"] = it }
        if (venuePrices.size >= 2 && livePx != null) {
            val extAvg = venuePrices.filterKeys { it != "Bybit" }.values.average()
            val leadBps = (extAvg - livePx) / livePx * 10_000.0
            maxSpreadBps = abs(leadBps)
            val s = tanh(leadBps / 8.0)
            val lbl = String.format(Locale.US, "Спред %d бирж %+.1f bps", venuePrices.size, leadBps)
            addFactor("cross_venue_lead", s, 0.9, lbl)
            if (maxSpreadBps >= 15.0) {
                triggers += String.format(Locale.US, "Рассинхрон %.1f bps (%d бирж)", maxSpreadBps, venuePrices.size)
            }
        }

        // 5. Импульс реальных 1m свечей
        if (bars1m.size >= 15) {
            val window = bars1m.takeLast(20)
            val closes = window.mapNotNull { it.getOrNull(4)?.takeIf { v -> v.isFinite() && v > 0.0 } }
            if (closes.size >= 10) {
                val rets = ArrayList<Double>(closes.size - 1)
                for (i in 1 until closes.size) {
                    rets.add(100.0 * (closes[i] - closes[i - 1]) / closes[i - 1])
                }
                val lastRet = rets.last()
                val mean = rets.average()
                val sd = sqrt(rets.sumOf { (it - mean).pow(2) } / max(1, rets.size - 1)).coerceAtLeast(1e-5)
                val z = (lastRet - mean) / sd
                val microDrift = 100.0 * (closes.last() - closes[closes.size - 5]) / closes[closes.size - 5]
                val s = tanh(0.55 * z / 2.0 + 0.45 * microDrift / 0.25)
                val lbl = String.format(Locale.US, "1м Z=%.1fσ (%+.2f%%)", z, microDrift)
                addFactor("micro_impulse", s, 1.05, lbl)
                if (abs(z) >= 2.2) {
                    triggers += String.format(Locale.US, "Импульс 1м Z=%.1fσ", z)
                }
            }
        }

        // 6. Ликвидации и фандинг
        val liq1h = market.liq1hUsd ?: 0.0
        if (liq1h.isFinite() && liq1h >= 200_000.0) {
            triggers += "Ликвидации $${TrendReasoningEngine.compactUsd(liq1h)} (1ч)"
        }
        market.fundingPct?.takeIf { it.isFinite() }?.let { f ->
            if (abs(f) >= 0.025) {
                val squeezeDir = if (f < 0) 0.55 else -0.55
                addFactor("funding_squeeze", squeezeDir, 0.70, String.format(Locale.US, "Фандинг %+.4f%%", f))
            }
        }

        // 7. Контекст основного анализа + Sentinel
        intel?.let { rep ->
            val engineEdge = (rep.trendLogic.pUp - rep.trendLogic.pDown).coerceIn(-1.0, 1.0)
            if (engineEdge.isFinite() && abs(engineEdge) > 0.02) {
                addFactor("engine_bias", tanh(engineEdge * 2.2), 0.90, "Ядро ENA ${rep.trendLogic.biasLabel}")
            }
            if (rep.sentinelEvents.any { it.severity == SentinelSeverity.CRITICAL }) {
                val crit = rep.sentinelEvents.first { it.severity == SentinelSeverity.CRITICAL }
                triggers += "Sentinel: ${crit.title.take(42)}"
            }
        }

        val hasData = totalWeight > 0.0 && reasons.isNotEmpty() && livePx != null
        if (!hasData) {
            return RealTimeTrendPulse(
                symbol = symbol,
                tsMs = nowMs,
                direction = "NO_DATA",
                score = 0.0,
                confidence = 0.0,
                strongTrigger = false,
                triggerReason = "",
                leadWindowLabel = "микроструктура (нет данных)",
                factorsSummary = "Недостаточно живых рыночных данных по $symbol",
                factorVotes = votes,
                accuracyPct = vaultSnap.accuracyPct?.takeIf { it.isFinite() },
                recentAccuracyPct = vaultSnap.recentAccuracyPct?.takeIf { it.isFinite() },
                verifiedCount = vaultSnap.verifiedCount,
                price = livePx,
                sufficientData = false
            )
        }

        val rawScore = (weightedScore / totalWeight).coerceIn(-1.0, 1.0)
        val cleanScore = if (rawScore.isFinite()) rawScore else 0.0
        val direction = when {
            reasons.size >= 2 && cleanScore >= 0.14 -> "LONG"
            reasons.size >= 2 && cleanScore <= -0.14 -> "SHORT"
            else -> "NEUTRAL"
        }
        val coverage = (reasons.size / 4.0).coerceIn(0.25, 1.0)
        val confidence = (abs(cleanScore) * coverage).coerceIn(0.0, 1.0)
        val strongTrigger = triggers.isNotEmpty()
        val triggerText = if (strongTrigger) triggers.first() else ""

        return RealTimeTrendPulse(
            symbol = symbol,
            tsMs = nowMs,
            direction = direction,
            score = cleanScore,
            confidence = confidence,
            strongTrigger = strongTrigger,
            triggerReason = triggerText,
            leadWindowLabel = "микроструктура (реал-тайм)",
            factorsSummary = reasons.take(4).joinToString(" · "),
            factorVotes = votes,
            accuracyPct = vaultSnap.accuracyPct?.takeIf { it.isFinite() },
            recentAccuracyPct = vaultSnap.recentAccuracyPct?.takeIf { it.isFinite() },
            verifiedCount = vaultSnap.verifiedCount,
            price = livePx,
            sufficientData = true
        )
    }
}
