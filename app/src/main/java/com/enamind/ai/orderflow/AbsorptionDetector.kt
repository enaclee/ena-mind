package com.enamind.ai.orderflow

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * C. Absorption Detector — многофакторный детектор поглощения покупателей и продавцов.
 *
 * Жёсткое правило честности:
 *  - Ни один отдельный признак (только высокая Delta, только пополнение или только пауза цены)
 *    НЕ объявляется достаточным доказательством поглощения.
 *  - Требуется согласованная совокупность минимум 3 независимых признаков (для POSSIBLE)
 *    и ≥ 5 с сохранением/пополнением встречной ликвидности и истощением атак (для CONFIRMED).
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

        // Базовая нормальная волатильность по реальным барам для корректного сравнения продвижения цены
        val typicalBarRangePct = computeTypicalBarRangePct(bars)
        val actualMovePct = 100.0 * (lastPrice - firstPrice) / max(1e-9, firstPrice)
        val rangePct = 100.0 * priceRange / max(1e-9, firstPrice)

        // Разбиваем поток на 3 временных окна (под-атаки) для проверки истощения атак (Attack Exhaustion)
        val chunk = max(4, trades.size / 3)
        val earlyTrades = trades.take(chunk)
        val lateTrades = trades.takeLast(chunk)

        val earlyDelta = earlyTrades.sumOf { if (it.isAggressiveBuy) it.qty else -it.qty }
        val lateDelta = lateTrades.sumOf { if (it.isAggressiveBuy) it.qty else -it.qty }
        val earlyMovePct = 100.0 * (earlyTrades.last().price - earlyTrades.first().price) / max(1e-9, earlyTrades.first().price)
        val lateMovePct = 100.0 * (lateTrades.last().price - lateTrades.first().price) / max(1e-9, lateTrades.first().price)

        // Определяем сторону возможного поглощения:
        // Поглощение покупателей: агрессивный поток положительный (Ask > Bid), но цена застряла / не растёт у сопротивления
        // Поглощение продавцов: агрессивный поток отрицательный (Bid > Ask), но цена удерживается / не падает у поддержки
        val buyPressureDominant = footprint.netDelta > 0.0 && footprint.deltaPct >= 10.0
        val sellPressureDominant = footprint.netDelta < 0.0 && footprint.deltaPct <= -10.0

        if (!buyPressureDominant && !sellPressureDominant) {
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

        val isBuyerAbsorptionCandidate = buyPressureDominant
        val aggVol = if (isBuyerAbsorptionCandidate) footprint.totalAskVolume else footprint.totalBidVolume
        val targetLevel = if (isBuyerAbsorptionCandidate) {
            candidateResistance ?: footprint.pocPrice
        } else {
            candidateSupport ?: footprint.pocPrice
        }

        // 1. Price Progress Efficiency: сколько % движения цены (в ожидаемую сторону) получено на единицу дисбаланса/волатильности
        val favorableMovePct = if (isBuyerAbsorptionCandidate) actualMovePct else -actualMovePct
        val denomNorm = max(0.05, typicalBarRangePct ?: rangePct.coerceAtLeast(0.08))
        val progressEfficiency = (favorableMovePct / denomNorm).coerceIn(-1.5, 3.0)
        // При поглощении цена либо стоит, либо даже сползает против атакующих (progressEfficiency <= 0.35)
        val poorProgress = progressEfficiency <= 0.35

        // 2. Absorption Ratio: отношение агрессивного давления (|Delta%|) к реальному продвижению цены
        val rawAr = (abs(footprint.deltaPct) / 100.0) * (1.0 - progressEfficiency.coerceIn(0.0, 1.0))
        val absorptionRatio = (0.5 * rawAr + 0.3 * (dom.replenishmentRate / 2.0).coerceIn(0.0, 1.0) + 0.2 * dom.levelPersistenceRatio)
            .coerceIn(0.0, 1.0)

        // 3. Повторные атаки на один ценовой узел
        val refLevel = targetLevel ?: lastPrice
        val tol = max(footprint.priceStep * 2.5, refLevel * 0.0012)
        val repeatedAttacks = countRepeatedTouches(trades, refLevel, tol, isBuyerAbsorptionCandidate)

        // 4. Истощение атакующей стороны: ранние атаки двигали цену лучше или имели большую дельту, чем поздние
        val earlyEff = if (abs(earlyDelta) > 1e-9) {
            (if (isBuyerAbsorptionCandidate) earlyMovePct else -earlyMovePct) / abs(earlyDelta)
        } else 0.0
        val lateEff = if (abs(lateDelta) > 1e-9) {
            (if (isBuyerAbsorptionCandidate) lateMovePct else -lateMovePct) / abs(lateDelta)
        } else 0.0
        val fadingAggression = if (isBuyerAbsorptionCandidate) {
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

        // 5. Многофакторное голосование (ЗА и ПРОТИВ)
        val supporting = ArrayList<String>()
        val conflicting = ArrayList<String>()
        var factorHits = 0

        if (abs(footprint.deltaPct) >= 14.0) {
            factorHits++
            supporting += "Высокий агрессивный объём ${if (isBuyerAbsorptionCandidate) "покупок" else "продаж"} (Delta ${fmtPct(footprint.deltaPct)})"
        } else {
            conflicting += "Умеренная Delta (${fmtPct(footprint.deltaPct)}) без явного экстремума"
        }

        if (poorProgress) {
            factorHits++
            supporting += "Цена почти не продвинулась (${fmtPct(actualMovePct)} при норме ~${fmtPct(denomNorm)})"
        } else {
            conflicting += "Цена эффективно продвигается вслед за потоком (${fmtPct(actualMovePct)})"
        }

        if (dom.replenishmentEvents >= 2 || dom.replenishmentRate >= 0.25) {
            factorHits++
            supporting += "Зафиксировано пополнение встречной ликвидности после сделок (${dom.replenishmentEvents}x, коэф. ${fmtD(dom.replenishmentRate)})"
        } else {
            conflicting += "Пополнение встречных заявок после исполнения слабое (${dom.replenishmentEvents}x)"
        }

        if (dom.levelPersistenceRatio >= 0.55) {
            factorHits++
            supporting += "Ликвидность на уровне устойчива (${(dom.levelPersistenceRatio * 100).toInt()}% падений — реальное исполнение, а не снятие)"
        } else {
            conflicting += "Низкая устойчивость стакана (${(dom.levelPersistenceRatio * 100).toInt()}%): много снятий без сделок"
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

        // Противоречащий сигнал от снятия крупных стен или смены OFI в сторону пробоя
        if (dom.liquidityPullDetected) {
            conflicting += "В стакане зафиксировано снятие заявок — стена может убираться перед пробоем"
        }
        val ofiSupportsAttacker = (isBuyerAbsorptionCandidate && dom.normalizedOfi > 0.25) ||
            (!isBuyerAbsorptionCandidate && dom.normalizedOfi < -0.25)
        if (ofiSupportsAttacker) {
            conflicting += "OFI направлен в сторону атакующих (${fmtD(dom.normalizedOfi)})"
        }

        // Требуем минимум 3 независимых фактора + неэффективное продвижение цены!
        val detected = factorHits >= 3 && poorProgress
        val confirmed = factorHits >= 5 && poorProgress && (dom.replenishmentEvents >= 2) && !dom.liquidityPullDetected

        val grade = when {
            confirmed -> ReliabilityGrade.CONFIRMED
            factorHits >= 4 && detected -> ReliabilityGrade.STRONG
            detected -> ReliabilityGrade.MODERATE
            else -> ReliabilityGrade.WEAK
        }

        val side = if (detected) {
            if (isBuyerAbsorptionCandidate) AbsorptionSide.BUYER_ABSORPTION else AbsorptionSide.SELLER_ABSORPTION
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
