package com.enamind.ai.orderflow

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * D. Level Attack Failure Detector — отслеживает атаки строго на реальных касаниях уровней S/R.
 *
 * Исправления:
 *  - Proximity Gate: касание уровня проверяется с жёстким допуском
 *    `touchTol = max(tickSize * 5, min(price * 0.0005, price * atrPct * 0.35 / 100))`
 *    (без срабатывания за 1.2% от уровня).
 *  - Запрет CONFIRMED_BREAKOUT без реального исполнения стакана, согласованного OFI и при
 *    флетовом режиме ядра Радара (ADX < 18 && ER < 0.20).
 */
object LevelAttackDetector {

    data class LevelResult(
        val supports: List<Double>,
        val resistances: List<Double>,
        val attackState: LevelAttackState
    )

    fun analyze(
        currentPrice: Double?,
        bars: List<List<Double>>,
        trades: List<OrderFlowStreamHub.RawTradeTick>,
        footprint: FootprintSummary,
        dom: DomSnapshotMetrics,
        absorption: AbsorptionMetrics,
        tickSize: Double? = null,
        quantAdx: Double? = null,
        quantEfficiencyRatio: Double? = null
    ): LevelResult {
        if (currentPrice == null || !currentPrice.isFinite() || currentPrice <= 0.0 || bars.size < 12) {
            return LevelResult(
                supports = emptyList(),
                resistances = emptyList(),
                attackState = LevelAttackState(phase = LevelAttackPhase.NONE)
            )
        }

        val (supports, resistances) = extractStructuralLevels(bars, currentPrice)

        val nearestSupport = supports.filter { it <= currentPrice * 1.003 }.maxOrNull()
            ?: dom.largeBids.maxByOrNull { it.price }?.price
        val nearestResistance = resistances.filter { it >= currentPrice * 0.997 }.minOrNull()
            ?: dom.largeAsks.minByOrNull { it.price }?.price

        val distResPct = nearestResistance?.let { abs(it - currentPrice) / currentPrice * 100.0 } ?: Double.MAX_VALUE
        val distSupPct = nearestSupport?.let { abs(currentPrice - it) / currentPrice * 100.0 } ?: Double.MAX_VALUE

        val attackingResistance = distResPct <= distSupPct
        val targetLevel = if (attackingResistance) nearestResistance else nearestSupport
        val distPct = if (attackingResistance) distResPct else distSupPct

        val atrPct = computeAtrPct(bars).coerceIn(0.06, 1.5)
        val rawTouchTol = max(
            (tickSize ?: 0.0) * 5.0,
            min(currentPrice * 0.0005, currentPrice * (atrPct * 0.35 / 100.0))
        ).coerceAtLeast(currentPrice * 0.0002)
        val zoneTol = rawTouchTol
        val zonePct = (zoneTol / currentPrice) * 100.0
        val maxActiveDistPct = max(zonePct * 3.0, 0.22)

        if (targetLevel == null || distPct > maxActiveDistPct) {
            return LevelResult(
                supports = supports,
                resistances = resistances,
                attackState = LevelAttackState(
                    levelPrice = targetLevel,
                    isResistance = attackingResistance,
                    distancePct = if (distPct < 100.0) distPct else null,
                    phase = LevelAttackPhase.NONE
                )
            )
        }

        val inTouchZone = abs(currentPrice - targetLevel) <= zoneTol
        val touchCount = countLevelTouches(bars, trades, targetLevel, zoneTol)
        val acceptedBeyond = checkAcceptanceBeyond(trades, bars, targetLevel, zoneTol, attackingResistance)
        val nearLiquidityRatio = computeCounterLiquidityPct(dom, targetLevel, zoneTol, attackingResistance)

        val peakInTrades = if (attackingResistance) trades.maxOfOrNull { it.price } else trades.minOfOrNull { it.price }
        val postReactionPct = if (peakInTrades != null && peakInTrades > 0.0) {
            100.0 * (currentPrice - peakInTrades) / peakInTrades
        } else null

        val progressFromFirst = if (trades.size >= 4) {
            100.0 * (currentPrice - trades.first().price) / max(1e-9, trades.first().price)
        } else 0.0

        val repeatedZones = mutableListOf<Double>()
        val failedZones = mutableListOf<Double>()
        if (touchCount >= 2 && inTouchZone) repeatedZones.add(targetLevel)

        val attackerMatchingDirection = if (attackingResistance) footprint.netDelta > 0 else footprint.netDelta < 0
        val strongAggression = abs(footprint.deltaPct) >= 12.0 && footprint.sufficientData
        val ofiAlignedBreakout = if (attackingResistance) dom.normalizedOfi >= 0.12 else dom.normalizedOfi <= -0.12
        val flatQuantRegime = quantAdx != null && quantEfficiencyRatio != null &&
            quantAdx.isFinite() && quantEfficiencyRatio.isFinite() &&
            quantAdx < 18.0 && quantEfficiencyRatio < 0.20

        val rejectedNow = if (attackingResistance) {
            (peakInTrades != null && peakInTrades >= targetLevel - zoneTol) &&
                currentPrice < targetLevel - zoneTol * 0.9 &&
                (postReactionPct != null && postReactionPct <= -zonePct * 0.65)
        } else {
            (peakInTrades != null && peakInTrades <= targetLevel + zoneTol) &&
                currentPrice > targetLevel + zoneTol * 0.9 &&
                (postReactionPct != null && postReactionPct >= zonePct * 0.65)
        }

        val phase = when {
            acceptedBeyond && !absorption.confirmed && !flatQuantRegime &&
                attackerMatchingDirection && ofiAlignedBreakout && dom.executedQty > 0.0 ->
                LevelAttackPhase.CONFIRMED_BREAKOUT

            rejectedNow && (absorption.detected || touchCount >= 2) -> {
                failedZones.add(targetLevel)
                LevelAttackPhase.CONFIRMED_REJECTION
            }

            inTouchZone && absorption.detected && absorption.attackExhaustion >= 0.55 ->
                LevelAttackPhase.ATTACKER_EXHAUSTION

            inTouchZone && absorption.detected ->
                LevelAttackPhase.POSSIBLE_ABSORPTION

            inTouchZone && attackerMatchingDirection && strongAggression ->
                LevelAttackPhase.ACTIVE_PRESSURE

            (inTouchZone || distPct <= zonePct * 1.5) && touchCount >= 1 && attackerMatchingDirection ->
                LevelAttackPhase.ATTACK_START

            else -> LevelAttackPhase.NONE
        }

        return LevelResult(
            supports = supports,
            resistances = resistances,
            attackState = LevelAttackState(
                levelPrice = targetLevel,
                isResistance = attackingResistance,
                distancePct = distPct,
                touchCount = touchCount,
                phase = phase,
                attackDelta = footprint.netDelta,
                attackProgressPct = progressFromFirst,
                liquidityChangePct = nearLiquidityRatio,
                replenishmentDetected = dom.replenishmentEvents >= 2,
                acceptedBeyondLevel = acceptedBeyond,
                postAttackReactionPct = postReactionPct,
                repeatedAttackZones = repeatedZones,
                failedBreakoutZones = failedZones
            )
        )
    }

    private fun extractStructuralLevels(bars: List<List<Double>>, currentPrice: Double): Pair<List<Double>, List<Double>> {
        val window = bars.takeLast(80)
        val swingHighs = mutableListOf<Double>()
        val swingLows = mutableListOf<Double>()

        for (i in 2 until window.size - 2) {
            val h = window[i].getOrNull(2) ?: continue
            val l = window[i].getOrNull(3) ?: continue
            val prevH1 = window[i - 1].getOrNull(2) ?: h
            val prevH2 = window[i - 2].getOrNull(2) ?: h
            val nextH1 = window[i + 1].getOrNull(2) ?: h
            val nextH2 = window[i + 2].getOrNull(2) ?: h
            if (h >= prevH1 && h >= prevH2 && h >= nextH1 && h >= nextH2) swingHighs.add(h)

            val prevL1 = window[i - 1].getOrNull(3) ?: l
            val prevL2 = window[i - 2].getOrNull(3) ?: l
            val nextL1 = window[i + 1].getOrNull(3) ?: l
            val nextL2 = window[i + 2].getOrNull(3) ?: l
            if (l <= prevL1 && l <= prevL2 && l <= nextL1 && l <= nextL2) swingLows.add(l)
        }

        val resClusters = clusterLevels(swingHighs.filter { it >= currentPrice * 0.994 }, currentPrice * 0.0025)
            .sortedBy { abs(it - currentPrice) }
            .take(3)
        val supClusters = clusterLevels(swingLows.filter { it <= currentPrice * 1.006 }, currentPrice * 0.0025)
            .sortedBy { abs(it - currentPrice) }
            .take(3)

        val finalRes = resClusters.ifEmpty {
            listOfNotNull(window.maxOfOrNull { it.getOrNull(2) ?: 0.0 }?.takeIf { it > 0 })
        }
        val finalSup = supClusters.ifEmpty {
            listOfNotNull(window.minOfOrNull { it.getOrNull(3) ?: Double.MAX_VALUE }?.takeIf { it < Double.MAX_VALUE && it > 0 })
        }
        return finalSup to finalRes
    }

    private fun clusterLevels(raw: List<Double>, tol: Double): List<Double> {
        if (raw.isEmpty()) return emptyList()
        val sorted = raw.sorted()
        val groups = mutableListOf<MutableList<Double>>()
        for (px in sorted) {
            val g = groups.lastOrNull()
            if (g != null && abs(px - g.average()) <= tol) {
                g.add(px)
            } else {
                groups.add(mutableListOf(px))
            }
        }
        return groups.sortedByDescending { it.size }.map { it.average() }
    }

    private fun countLevelTouches(
        bars: List<List<Double>>,
        trades: List<OrderFlowStreamHub.RawTradeTick>,
        level: Double,
        tol: Double
    ): Int {
        var barTouches = 0
        for (b in bars.takeLast(24)) {
            val h = b.getOrNull(2) ?: continue
            val l = b.getOrNull(3) ?: continue
            if (h >= level - tol && l <= level + tol) barTouches++
        }
        var tradeTouches = 0
        var inside = false
        for (t in trades) {
            val near = abs(t.price - level) <= tol
            if (near && !inside) {
                tradeTouches++
                inside = true
            } else if (!near && abs(t.price - level) > tol * 1.5) {
                inside = false
            }
        }
        return max(barTouches, tradeTouches)
    }

    private fun checkAcceptanceBeyond(
        trades: List<OrderFlowStreamHub.RawTradeTick>,
        bars: List<List<Double>>,
        level: Double,
        tol: Double,
        isResistance: Boolean
    ): Boolean {
        val recentCloses = bars.takeLast(3).mapNotNull { it.getOrNull(4) }
        val barsBeyond = if (isResistance) {
            recentCloses.count { it > level + tol * 0.8 } >= 2
        } else {
            recentCloses.count { it < level - tol * 0.8 } >= 2
        }
        if (barsBeyond) return true

        if (trades.size >= 15) {
            val tail = trades.takeLast(12)
            val allBeyond = if (isResistance) {
                tail.count { it.price > level + tol * 0.6 } >= 10
            } else {
                tail.count { it.price < level - tol * 0.6 } >= 10
            }
            if (allBeyond) return true
        }
        return false
    }

    private fun computeCounterLiquidityPct(
        dom: DomSnapshotMetrics,
        level: Double,
        tol: Double,
        isResistance: Boolean
    ): Double {
        if (!dom.sufficientData) return 0.0
        val zones = if (isResistance) dom.largeAsks else dom.largeBids
        val hasWallAtLevel = zones.any { abs(it.price - level) <= tol * 2.5 }
        return when {
            hasWallAtLevel && dom.replenishmentEvents >= 2 -> 35.0
            hasWallAtLevel -> 15.0
            dom.liquidityPullDetected -> -40.0
            else -> 0.0
        }
    }

    private fun computeAtrPct(bars: List<List<Double>>): Double {
        val slice = bars.takeLast(15)
        val vals = slice.mapNotNull { b ->
            val h = b.getOrNull(2); val l = b.getOrNull(3); val c = b.getOrNull(4)
            if (h != null && l != null && c != null && c > 0 && h >= l) 100.0 * (h - l) / c else null
        }
        return if (vals.isNotEmpty()) vals.average() else 0.25
    }
}
