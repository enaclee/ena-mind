package com.enamind.ai.orderflow

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Footprint Engine — кластерный анализ реальных исполненных сделок по ценовым уровням.
 * Рассчитывает Bid Volume, Ask Volume, Delta, Delta %, CVD, Volume POC,
 * Bid/Ask Imbalance, Stacked Imbalance, крупные скопления объёма и Z-score аномалии активности.
 */
object FootprintEngine {

    fun analyze(
        trades: List<OrderFlowStreamHub.RawTradeTick>,
        tickSize: Double?,
        bars: List<List<Double>>
    ): FootprintSummary {
        if (trades.size < 8) {
            return FootprintSummary(
                tradesAnalyzed = trades.size,
                sufficientData = false,
                note = "Недостаточно сделок для кластерного расчёта (${trades.size}/8)"
            )
        }

        val lastPrice = trades.last().price
        val step = resolveAdaptiveStep(lastPrice, tickSize, bars)
        if (!step.isFinite() || step <= 0.0) {
            return FootprintSummary(
                tradesAnalyzed = trades.size,
                sufficientData = false,
                note = "Не удалось определить ценовой шаг кластера"
            )
        }

        class Acc(var bidVol: Double = 0.0, var askVol: Double = 0.0, var count: Int = 0)

        val buckets = LinkedHashMap<Long, Acc>()
        var totalAsk = 0.0
        var totalBid = 0.0
        var cvd = 0.0
        val cvdSeries = ArrayList<Double>(trades.size)

        for (t in trades) {
            val bucketIdx = floor(t.price / step).toLong()
            val acc = buckets.getOrPut(bucketIdx) { Acc() }
            if (t.isAggressiveBuy) {
                acc.askVol += t.qty
                totalAsk += t.qty
                cvd += t.qty
            } else {
                acc.bidVol += t.qty
                totalBid += t.qty
                cvd -= t.qty
            }
            acc.count++
            cvdSeries.add(cvd)
        }

        val totVol = totalAsk + totalBid
        if (buckets.isEmpty() || totVol <= 0.0) {
            return FootprintSummary(
                tradesAnalyzed = trades.size,
                sufficientData = false,
                note = "Пустой профиль сделок"
            )
        }

        val cvdSlope = if (cvdSeries.size >= 6) {
            val half = cvdSeries.size / 2
            val firstHalf = cvdSeries.subList(0, half).average()
            val secondHalf = cvdSeries.subList(half, cvdSeries.size).average()
            secondHalf - firstHalf
        } else 0.0

        val vols = buckets.values.map { it.askVol + it.bidVol }
        val meanVol = vols.average()
        val stdVol = if (vols.size > 1) {
            sqrt(vols.sumOf { (it - meanVol).pow(2) } / (vols.size - 1))
        } else 0.0

        val counts = buckets.values.map { it.count.toDouble() }
        val meanCnt = counts.average()
        val stdCnt = if (counts.size > 1) {
            sqrt(counts.sumOf { (it - meanCnt).pow(2) } / (counts.size - 1))
        } else 0.0

        val pocBucket = buckets.maxByOrNull { it.value.askVol + it.value.bidVol }?.key
        val pocPrice = pocBucket?.let { roundPrice(it * step, step) }

        val sortedKeys = buckets.keys.sortedDescending() // сверху вниз по цене
        val levels = ArrayList<FootprintLevel>(sortedKeys.size)
        val clusterLevels = ArrayList<Double>()
        var anomalies = 0

        for (idx in sortedKeys) {
            val acc = buckets[idx]!!
            val px = roundPrice(idx * step, step)
            val levelVol = acc.askVol + acc.bidVol
            val delta = acc.askVol - acc.bidVol
            val dPct = if (levelVol > 0.0) 100.0 * delta / levelVol else 0.0

            // Дисбаланс Ask/Bid (диагональное или внутриуровневое преобладание >= 2.5x при значимом объёме)
            val minSignificant = meanVol * 0.35
            val belowBid = buckets[idx - 1]?.bidVol ?: acc.bidVol
            val aboveAsk = buckets[idx + 1]?.askVol ?: acc.askVol
            val imbalanceType = when {
                levelVol >= minSignificant && acc.askVol >= max(1e-9, belowBid) * 2.5 && delta > 0 -> "ASK_IMBALANCE"
                levelVol >= minSignificant && acc.bidVol >= max(1e-9, aboveAsk) * 2.5 && delta < 0 -> "BID_IMBALANCE"
                else -> "NONE"
            }

            val volZ = if (stdVol > 1e-12) (levelVol - meanVol) / stdVol else 0.0
            val cntZ = if (stdCnt > 1e-12) (acc.count - meanCnt) / stdCnt else 0.0
            val activityZ = max(volZ, cntZ)
            val isCluster = levelVol > meanVol + 1.65 * stdVol && buckets.size >= 4
            if (isCluster) clusterLevels.add(px)
            if (activityZ >= 2.0) anomalies++

            levels.add(
                FootprintLevel(
                    priceLevel = px,
                    bidVolume = acc.bidVol,
                    askVolume = acc.askVol,
                    delta = delta,
                    deltaPct = dPct,
                    totalVolume = levelVol,
                    tradeCount = acc.count,
                    imbalanceType = imbalanceType,
                    isPoc = idx == pocBucket,
                    isClusterAnomaly = isCluster,
                    activityZScore = activityZ
                )
            )
        }

        // Stacked Imbalance: серии из >= 3 смежных уровней с односторонним дисбалансом
        var stackedAsk = 0
        var stackedBid = 0
        var runAsk = 0
        var runBid = 0
        for (lv in levels) {
            if (lv.imbalanceType == "ASK_IMBALANCE") {
                runAsk++
                if (runAsk >= 3) stackedAsk = max(stackedAsk, runAsk)
            } else {
                runAsk = 0
            }
            if (lv.imbalanceType == "BID_IMBALANCE") {
                runBid++
                if (runBid >= 3) stackedBid = max(stackedBid, runBid)
            } else {
                runBid = 0
            }
        }

        val netDelta = totalAsk - totalBid
        val deltaPct = 100.0 * netDelta / totVol

        return FootprintSummary(
            levels = levels.take(24),
            priceStep = step,
            totalAskVolume = totalAsk,
            totalBidVolume = totalBid,
            netDelta = netDelta,
            deltaPct = deltaPct,
            cvd = cvd,
            cvdSlope = cvdSlope,
            pocPrice = pocPrice,
            stackedAskCount = stackedAsk,
            stackedBidCount = stackedBid,
            clusterLevels = clusterLevels.take(6),
            anomaliesCount = anomalies,
            tradesAnalyzed = trades.size,
            sufficientData = true,
            note = "Окно ${trades.size} сделок · шаг ${formatStep(step)}"
        )
    }

    private fun resolveAdaptiveStep(
        price: Double,
        tickSize: Double?,
        bars: List<List<Double>>
    ): Double {
        if (price <= 0.0 || !price.isFinite()) return 0.0
        val atrPct = if (bars.size >= 10) {
            val ranges = bars.takeLast(14).mapNotNull { b ->
                val h = b.getOrNull(2); val l = b.getOrNull(3); val c = b.getOrNull(4)
                if (h != null && l != null && c != null && c > 0 && h >= l) (h - l) / c else null
            }
            if (ranges.isNotEmpty()) ranges.average().coerceIn(0.0004, 0.03) else 0.0025
        } else 0.0025

        val targetStep = price * (atrPct / 6.0).coerceIn(0.00015, 0.005)
        if (tickSize != null && tickSize.isFinite() && tickSize > 0.0) {
            val mult = max(1L, round(targetStep / tickSize).toLong())
            return tickSize * mult
        }
        val mag = 10.0.pow(floor(kotlin.math.log10(targetStep)))
        val norm = targetStep / mag
        val nice = when {
            norm <= 1.5 -> 1.0
            norm <= 3.5 -> 2.5
            norm <= 7.0 -> 5.0
            else -> 10.0
        }
        return nice * mag
    }

    private fun roundPrice(v: Double, step: Double): Double {
        if (step <= 0.0) return v
        return round(v / step) * step
    }

    private fun formatStep(step: Double): String = when {
        step >= 10.0 -> String.format(java.util.Locale.US, "%.1f", step)
        step >= 0.1 -> String.format(java.util.Locale.US, "%.2f", step)
        else -> String.format(java.util.Locale.US, "%.5f", step)
    }
}
