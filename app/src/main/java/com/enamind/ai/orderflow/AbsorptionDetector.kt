package com.enamind.ai.orderflow

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * C. Absorption Detector — многофакторный детектор поглощения с корректной полярностью пассивной стороны:
 *
 *  - Агрессивные ПОКУПКИ (Delta > 0) не продвигают цену на сопротивлении -> SELLER_ABSORPTION
 *    (продавцы лимитами поглощают покупателей -> риск разворота ВНИЗ).
 *  - Агрессивные ПРОДАЖИ (Delta < 0) не продвигают цену на поддержке -> BUYER_ABSORPTION
 *    (покупатели лимитами поглощают продавцов -> риск разворота ВВЕРХ).
 *
 * Двойная защита:
 *  - Эффективность хода цены (progressEfficiency) ограничивается диапазоном [-0.25 .. 0.30]:
 *    если цена активно пошла ПРОТИВ потока (< -0.25) или УШЛА за потоком (> 0.30), это не поглощение.
 *  - При раздутом стакане без реальных исполнений (executedQty == 0 при высоком HFT-CTR) ложное
 *    поглощение отклоняется.
 */
object AbsorptionDetector {

    fun analyze(
        trades: List<OrderFlowStreamHub.RawTradeTick>,
        footprint: FootprintSummary,
        dom: DomSnapshotMetrics,
        bars: List<List<Double>>,
        candidateResistance: Double?,
        candidateSupport: Double?
    ): AbsorptionMetrics {
        if (!footprint.sufficientData || !dom.sufficientData || !dom.synchronized || trades.size < 16) {
            return AbsorptionMetrics(
                side = AbsorptionSide.NONE,
                detected = false,
                confirmed = false,
                reliability = ReliabilityGrade.INSUFFICIENT,
                supportingFactors = emptyList(),
                conflictingFactors = listOf("Недостаточно синхронизированных сделок и дельт стакана для проверки поглощения"),
                note = "Ожидание потока сделок и синхронизированного стакана"
            )
        }

        val totalVol = footprint.totalAskVolume + footprint.totalBidVolume
        if (totalVol <= 0.0) {
            return AbsorptionMetrics(reliability = ReliabilityGrade.INSUFFICIENT)
        }

        val prices = trades.map { it.price }
        val lastPrice = prices.last()
        val firstPrice = prices.first()
        val priceRange = ((prices.maxOrNull() ?: lastPrice) - (prices.minOrNull() ?: lastPrice)).coerceAtLeast(1e-9)
        val spanSec = ((trades.last().exchangeTsMs - trades.first().exchangeTsMs).coerceAtLeast(1000L)) / 1000.0

        val typicalBarRangePct = computeTypicalBarRangePct(bars)
        val actualMovePct = 100.0 * (lastPrice - firstPrice) / max(1e-9, firstPrice)
        val rangePct = 100.0 * priceRange / max(1e-9, firstPrice)

        val chunk = max(4, trades.size / 3)
        val earlyTrades = trades.take(chunk)
        val lateTrades = trades.takeLast(chunk)

        val earlyDelta = earlyTrades.sumOf { if (it.isAggressiveBuy) it.qty else -it.qty }
        val lateDelta = lateTrades.sumOf { if (it.isAggressiveBuy) it.qty else -it.qty }
        val earlyMovePct = 100.0 * (earlyTrades.last().price - earlyTrades.first().price) / max(1e-9, earlyTrades.first().price)
        val lateMovePct = 100.0 * (lateTrades.last().price - lateTrades.first().price) / max(1e-9, lateTrades.first().price)

        // Delta > 0 -> агрессивны покупатели -> на сопротивлении поглощают пассивные ПРОДАВЦЫ (SELLER_ABSORPTION)
        // Delta < 0 -> агрессивны продавцы -> на поддержке поглощают пассивные ПОКУПАТЕЛИ (BUYER_ABSORPTION)
        val aggressiveBuyPressure = footprint.netDelta > 0.0 && footprint.deltaPct >= 12.0
        val aggressiveSellPressure = footprint.netDelta < 0.0 && footprint.deltaPct <= -12.0

        if (!aggressiveBuyPressure && !aggressiveSellPressure) {
            return AbsorptionMetrics(
                side = AbsorptionSide.NONE,
                detected = false,
                confirmed = false,
                reliability = ReliabilityGrade.WEAK,
                aggressiveVolume = max(footprint.totalAskVolume, footprint.totalBidVolume),
                liquidityReplenishmentRate = dom.replenishmentRate,
                levelPersistence = dom.levelPersistenceRatio,
                holdDurationSec = spanSec,
                supportingFactors = emptyList(),
                conflictingFactors = listOf("Нет выраженного одностороннего агрессивного напора (Delta ${fmtPct(footprint.deltaPct)})"),
                note = "Поток сбалансирован — признаков одностороннего поглощения нет"
            )
        }

        // isSellerAbsorbing == true -> пассивные продавцы удерживают сопротивление (SELLER_ABSORPTION)
        val isSellerAbsorbing = aggressiveBuyPressure
        val aggVol = if (isSellerAbsorbing) footprint.totalAskVolume else footprint.totalBidVolume
        val targetLevel = if (isSellerAbsorbing) {
            candidateResistance ?: footprint.pocPrice
        } else {
            candidateSupport ?: footprint.pocPrice
        }

        // 1. Signed Price Progress Efficiency: движение цены в сторону агрессивного потока / типичная норма
        val favorableMovePct = if (isSellerAbsorbing) actualMovePct else -actualMovePct
        val denomNorm = max(0.05, typicalBarRangePct ?: rangePct.coerceAtLeast(0.08))
        val progressEfficiency = (favorableMovePct / denomNorm).coerceIn(-1.5, 3.0)
        // Истинное поглощение: цена почти не продвинулась и не ушла сильно против потока
        val poorProgress = progressEfficiency in -0.25..0.30

        // 2. Absorption Ratio
        val rawAr = (abs(footprint.deltaPct) / 100.0) * (1.0 - progressEfficiency.coerceIn(0.0, 1.0))
        val absorptionRatio = (0.5 * rawAr + 0.3 * (dom.replenishmentRate / 2.0).coerceIn(0.0, 1.0) + 0.2 * dom.levelPersistenceRatio)
            .coerceIn(0.0, 1.0)

        // 3. Повторные атаки на уровень
        val refLevel = targetLevel ?: lastPrice
        val tol = max(footprint.priceStep * 2.5, refLevel * 0.0008)
        val repeatedAttacks = countRepeatedTouches(trades, refLevel, tol, isSellerAbsorbing)

        // 4. Истощение атак (Attack Exhaustion)
        val earlyEff = if (abs(earlyDelta) > 1e-9) {
            (if (isSellerAbsorbing) earlyMovePct else -earlyMovePct) / abs(earlyDelta)
        } else 0.0
        val lateEff = if (abs(lateDelta) > 1e-9) {
            (if (isSellerAbsorbing) lateMovePct else -lateMovePct) / abs(lateDelta)
        } else 0.0
        val fadingAggression = if (isSellerAbsorbing) {
            lateDelta < earlyDelta * 0.75 || (lateMovePct <= 0.0 && abs(lateDelta) > 0)
        } else {
            lateDelta > earlyDelta * 0.75 || (lateMovePct >= 0.0 && abs(lateDelta) > 0)
        }
        val attackExhaustion = when {
            fadingAggression && lateEff <= earlyEff * 0.6 -> 0.85
            fadingAggression -> 0.60
            lateEff < earlyEff -> 0.45
            else -> 0.15
        }

        // 5. Многофакторное голосование
        val supporting = ArrayList<String>()
        val conflicting = ArrayList<String>()
        var factorHits = 0

        if (abs(footprint.deltaPct) >= 14.0) {
            factorHits++
            supporting += "Высокий агрессивный объём ${if (isSellerAbsorbing) "покупок" else "продаж"} (Delta ${fmtPct(footprint.deltaPct)})"
        } else {
            conflicting += "Умеренная Delta (${fmtPct(footprint.deltaPct)}) без явного экстремума"
        }

        if (poorProgress) {
            factorHits++
            supporting += "Цена удержана в узком диапазоне (${fmtPct(actualMovePct)} при норме ~${fmtPct(denomNorm)}, эфф. ${fmtD(progressEfficiency)})"
        } else {
            conflicting += if (progressEfficiency < -0.25) {
                "Цена уже пошла против потока (${fmtPct(actualMovePct)}) — фаза поглощения завершена"
            } else {
                "Цена эффективно продвигается вслед за потоком (${fmtPct(actualMovePct)})"
            }
        }

        if (dom.replenishmentEvents >= 2 || dom.replenishmentRate >= 0.25) {
            factorHits++
            supporting += "Зафиксировано пополнение встречной ликвидности после сделок (${dom.replenishmentEvents}x, коэф. ${fmtD(dom.replenishmentRate)})"
        } else {
            conflicting += "Пополнение встречных заявок после исполнения слабое (${dom.replenishmentEvents}x)"
        }

        if (dom.levelPersistenceRatio >= 0.55) {
            factorHits++
            supporting += "Ликвидность на уровне устойчива (${(dom.levelPersistenceRatio * 100).toInt()}% падений — реальное исполнение)"
        } else {
            conflicting += "Низкая устойчивость стакана (${(dom.levelPersistenceRatio * 100).toInt()}%): много отмен без сделок"
        }

        if (repeatedAttacks >= 2) {
            factorHits++
            supporting += "Повторные атаки одного ценового уровня ($repeatedAttacks попытки)"
        } else {
            conflicting += "Повторных заходов на уровень пока недостаточно ($repeatedAttacks)"
        }

        if (attackExhaustion >= 0.55) {
            factorHits++
            supporting += "Снижение эффективности последующих атак (${(attackExhaustion * 100).toInt()}%)"
        }

        if (dom.liquidityPullDetected) {
            conflicting += "В стакане зафиксировано снятие заявок — стена может убираться перед пробоем"
        }
        if (dom.churnInflationRatio >= 15.0 && dom.executedQty <= 0.0) {
            conflicting += "Высокий HFT-шум (CTR ×${fmtD(dom.churnInflationRatio)}) без сматченных исполнений в стакане"
        }
        val ofiSupportsAttacker = (isSellerAbsorbing && dom.normalizedOfi > 0.25) ||
            (!isSellerAbsorbing && dom.normalizedOfi < -0.25)
        if (ofiSupportsAttacker) {
            conflicting += "OFI направлен в сторону атакующих (${fmtD(dom.normalizedOfi)})"
        }

        val detected = factorHits >= 3 && poorProgress
        val confirmed = factorHits >= 5 && poorProgress && (dom.replenishmentEvents >= 2) && !dom.liquidityPullDetected

        val grade = when {
            confirmed -> ReliabilityGrade.CONFIRMED
            factorHits >= 4 && detected -> ReliabilityGrade.STRONG
            detected -> ReliabilityGrade.MODERATE
            else -> ReliabilityGrade.WEAK
        }

        val side = if (detected) {
            if (isSellerAbsorbing) AbsorptionSide.SELLER_ABSORPTION else AbsorptionSide.BUYER_ABSORPTION
        } else {
            AbsorptionSide.NONE
        }

        val note = when {
            confirmed -> "${side.labelRu} · подтверждено ${supporting.size} признаками"
            detected -> "${side.labelRu} · признаки умеренные/сильные (${supporting.size}), ожидает подтверждения"
            else -> "Признаков поглощения недостаточно (${supporting.size} за, ${conflicting.size} против)"
        }

        return AbsorptionMetrics(
            side = side,
            detected = detected,
            confirmed = confirmed,
            reliability = grade,
            aggressiveVolume = aggVol,
            priceProgressEfficiency = progressEfficiency,
            absorptionRatio = absorptionRatio,
            liquidityReplenishmentRate = dom.replenishmentRate,
            levelPersistence = dom.levelPersistenceRatio,
            attackExhaustion = attackExhaustion,
            repeatedAttacks = repeatedAttacks,
            holdDurationSec = spanSec,
            targetLevelPrice = targetLevel,
            supportingFactors = supporting,
            conflictingFactors = conflicting,
            note = note
        )
    }

    private fun countRepeatedTouches(
        trades: List<OrderFlowStreamHub.RawTradeTick>,
        level: Double,
        tol: Double,
        fromBelowResistance: Boolean
    ): Int {
        var touches = 0
        var insideZone = false
        for (t in trades) {
            val dist = if (fromBelowResistance) level - t.price else t.price - level
            val near = abs(t.price - level) <= tol
            if (near && !insideZone) {
                touches++
                insideZone = true
            } else if (!near && dist > tol * 1.4) {
                insideZone = false
            }
        }
        return touches
    }

    private fun computeTypicalBarRangePct(bars: List<List<Double>>): Double? {
        if (bars.size < 8) return null
        val list = bars.takeLast(16).mapNotNull { b ->
            val h = b.getOrNull(2); val l = b.getOrNull(3); val c = b.getOrNull(4)
            if (h != null && l != null && c != null && c > 0.0 && h >= l) 100.0 * (h - l) / c else null
        }
        return if (list.isNotEmpty()) list.average() else null
    }

    private fun fmtPct(v: Double): String = String.format(Locale.US, "%+.2f%%", v)
    private fun fmtD(v: Double): String = String.format(Locale.US, "%.2f", v)
}
