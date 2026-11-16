package com.enamind.ai.orderflow

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * DOM Analyzer — анти-шумовой анализ последовательности обновлений стакана (HFT / MLOFI / CTR).
 *
 * Ключевые принципы честности и защиты от «раздувания рынка»:
 *  1. MLOFI считается только по top-5 уровням книги с затухающими весами [1.0, 0.70, 0.45, 0.30, 0.20]
 *     и нормируется на реальный объём top-5, а не 0.8% от спреда.
 *  2. Пополнение ликвидности (replenishment) учитывается СТРОГО на пассивной стороне исполнения
 *     в окне 50..500 мс ПОСЛЕ реальной сматченной сделки (`executedQty > 0`) на том же уровне цены,
 *     отсеивая 20ms HFT-мигания котировок.
 *  3. `levelPersistenceRatio` рассчитывается строго внутри единой зоны (`focusZone`), чтобы
 *     глобальные отмены не делились на ближние снижения и не занижали устойчивость ложно.
 *  4. `churnInflationRatio` (Cancel-to-Trade Ratio) измеряет долю фиктивных снятий относительно сделок.
 */
object DomAnalyzer {

    private const val MATCH_WINDOW_MS = 250L
    private const val MIN_REPLENISH_DELAY_MS = 50L
    private const val MAX_REPLENISH_DELAY_MS = 500L
    private val MLOFI_WEIGHTS = doubleArrayOf(1.0, 0.70, 0.45, 0.30, 0.20)

    private data class ExecRecord(val tsMs: Long, val passiveSide: String, val qty: Double)

    fun analyze(
        micro: OrderFlowStreamHub.VenueMicroSnapshot?,
        nowMs: Long,
        activeLevelPrice: Double? = null,
        zoneTolerance: Double? = null
    ): DomSnapshotMetrics {
        if (micro == null || !micro.bookSynced || micro.bids.isEmpty() || micro.asks.isEmpty()) {
            val reason = micro?.desyncReason ?: "Стакан не синхронизирован"
            return DomSnapshotMetrics(
                synchronized = false,
                lagMs = micro?.avgLagMs ?: -1L,
                sufficientData = false,
                note = reason
            )
        }

        val bids = micro.bids.sortedByDescending { it.first }
        val asks = micro.asks.sortedBy { it.first }
        val bestBid = bids.firstOrNull()?.first ?: 0.0
        val bestAsk = asks.firstOrNull()?.first ?: 0.0
        val mid = if (bestBid > 0 && bestAsk > 0) (bestBid + bestAsk) / 2.0 else max(bestBid, bestAsk)
        val priceTol = (mid * 0.0005).coerceAtLeast(abs(bestAsk - bestBid) * 1.5).coerceAtLeast(1e-8)

        // Топ-5 границы для MLOFI и фокусной зоны анализа
        val topBids = bids.take(5)
        val topAsks = asks.take(5)
        val top5MinBid = topBids.lastOrNull()?.first ?: bestBid
        val top5MaxAsk = topAsks.lastOrNull()?.first ?: bestAsk
        val top5ActiveMass = (topBids.sumOf { it.second } + topAsks.sumOf { it.second }).coerceAtLeast(1e-9)

        val focusCenter = activeLevelPrice?.takeIf { it.isFinite() && it > 0.0 } ?: mid
        val focusHalfWidth = zoneTolerance?.takeIf { it.isFinite() && it > 0.0 }
            ?: max(abs(bestAsk - bestBid) * 3.0, mid * 0.0018).coerceAtLeast(1e-8)

        val bidTotal = bids.sumOf { it.second }
        val askTotal = asks.sumOf { it.second }
        val imbPct = if (bidTotal + askTotal > 0.0) 100.0 * (bidTotal - askTotal) / (bidTotal + askTotal) else 0.0

        val deltas = micro.domDeltas
        val trades = micro.trades

        if (deltas.size < 4) {
            return DomSnapshotMetrics(
                bidTotal = bidTotal,
                askTotal = askTotal,
                bookImbalancePct = imbPct,
                synchronized = true,
                lagMs = micro.avgLagMs,
                deltaSequenceCount = deltas.size,
                sufficientData = false,
                note = "Сбор последовательности изменений стакана (${deltas.size}/4 дельт)"
            )
        }

        var spanSec = 1.0
        if (deltas.size >= 2) {
            val dtMs = (deltas.last().delta.tsMs - deltas.first().delta.tsMs).coerceAtLeast(500L)
            spanSec = dtMs / 1000.0
        }

        var addCount = 0
        var cancelCount = 0
        var weightedOfi = 0.0
        var absWeightedOfiMass = 0.0
        var executedQty = 0.0
        var cancelledQty = 0.0
        var replenishCount = 0
        var replenishQty = 0.0
        var focusDrops = 0
        var focusCancels = 0
        val removedLargeWalls = mutableListOf<String>()

        // Последнее реальное исполнение (tsMs + passiveSide) по ценовому бакету
        val recentExecutedAtBucket = HashMap<Long, ExecRecord>()
        val availableTradeQty = DoubleArray(trades.size) { trades[it].qty }

        val allBookSizes = (bids.map { it.second } + asks.map { it.second }).filter { it > 0 }
        val meanSize = if (allBookSizes.isNotEmpty()) allBookSizes.average() else 0.0
        val stdSize = if (allBookSizes.size > 1) {
            sqrt(allBookSizes.sumOf { (it - meanSize).pow(2) } / (allBookSizes.size - 1))
        } else 0.0
        val largeThreshold = if (meanSize > 0.0) meanSize + 1.5 * stdSize else Double.MAX_VALUE

        for (ev in deltas) {
            val d = ev.delta
            val dq = d.deltaQty
            val inFocusZone = focusCenter <= 0.0 ||
                abs(d.price - focusCenter) <= focusHalfWidth ||
                (d.price in top5MinBid..top5MaxAsk)

            // MLOFI по top-5 уровням с весовым затуханием
            val levelWeight = mlofiWeight(d.price, d.side, topBids, topAsks, priceTol)
            if (levelWeight > 0.0) {
                val signedFlow = (if (d.side == "BID") dq else -dq) * levelWeight
                weightedOfi += signedFlow
                absWeightedOfiMass += abs(dq) * levelWeight
            }

            val bucket = (d.price / priceTol).toLong()
            if (dq > 0.0) {
                addCount++
                val exec = recentExecutedAtBucket[bucket]
                if (exec != null && exec.passiveSide == d.side && exec.qty > 0.0) {
                    val dt = d.tsMs - exec.tsMs
                    if (dt in MIN_REPLENISH_DELAY_MS..MAX_REPLENISH_DELAY_MS) {
                        replenishCount++
                        replenishQty += dq
                    }
                }
            } else if (dq < 0.0) {
                val dropQty = -dq
                if (inFocusZone) focusDrops++

                // BID исполняется рыночными продажами (isAggressiveBuy == false), ASK — покупками
                val expectAggBuy = (d.side == "ASK")
                var matchedQty = 0.0
                for (i in trades.indices) {
                    val tr = trades[i]
                    if (availableTradeQty[i] <= 1e-12) continue
                    if (abs(tr.exchangeTsMs - d.tsMs) <= MATCH_WINDOW_MS &&
                        tr.isAggressiveBuy == expectAggBuy &&
                        abs(tr.price - d.price) <= priceTol * 2.0
                    ) {
                        val take = min(dropQty - matchedQty, availableTradeQty[i])
                        if (take > 0.0) {
                            matchedQty += take
                            availableTradeQty[i] -= take
                        }
                        if (matchedQty >= dropQty - 1e-9) break
                    }
                }
                val cancelPart = (dropQty - matchedQty).coerceAtLeast(0.0)
                if (matchedQty > 0.0) {
                    executedQty += matchedQty
                    recentExecutedAtBucket[bucket] = ExecRecord(d.tsMs, d.side, matchedQty)
                }
                if (cancelPart > 1e-9) {
                    cancelledQty += cancelPart
                    cancelCount++
                    if (inFocusZone && cancelPart >= dropQty * 0.5) {
                        focusCancels++
                    }
                    if (d.prevQty >= largeThreshold && cancelPart >= d.prevQty * 0.7 && d.newQty <= d.prevQty * 0.2) {
                        removedLargeWalls += "${d.side} ${formatPrice(d.price)}"
                    }
                }
            }
        }

        val largeBids = bids.filter { it.second >= largeThreshold }.take(4).map { (p, q) ->
            RestingOrderZone(p, q, "BID", spanSec)
        }
        val largeAsks = asks.filter { it.second >= largeThreshold }.take(4).map { (p, q) ->
            RestingOrderZone(p, q, "ASK", spanSec)
        }

        // CTR (Cancel-to-Trade Ratio) измеряет искусственное раздувание/снятия стакана
        val churnInflationRatio = when {
            executedQty > 1e-9 -> (cancelledQty / executedQty).coerceIn(0.0, 99.0)
            cancelledQty > 0.0 -> 99.0
            else -> 0.0
        }
        // Если CTR слишком высок, подавляем вес стаканного OFI против шума
        val ctrDamping = when {
            churnInflationRatio >= 12.0 -> 0.35
            churnInflationRatio >= 6.0 -> 0.65
            else -> 1.0
        }
        val ofiDenom = max(absWeightedOfiMass, top5ActiveMass * 0.10).coerceAtLeast(1e-9)
        val normOfi = ((weightedOfi / ofiDenom) * ctrDamping).coerceIn(-1.0, 1.0)

        val addRate = addCount / spanSec
        val cancelRate = cancelCount / spanSec
        val replenishRate = if (executedQty > 1e-9) {
            (replenishQty / executedQty).coerceIn(0.0, 3.0)
        } else 0.0

        val persistenceRatio = if (focusDrops > 0) {
            (1.0 - (focusCancels.toDouble() / max(1, focusDrops))).coerceIn(0.0, 1.0)
        } else 0.5

        val pullDetected = removedLargeWalls.isNotEmpty() || (cancelledQty > executedQty * 3.0 && cancelledQty > bidTotal * 0.25)
        val pullNote = if (removedLargeWalls.isNotEmpty()) {
            "Зафиксировано снятие крупных заявок без исполнения: ${removedLargeWalls.distinct().take(3).joinToString(", ")}"
        } else if (pullDetected) {
            "Преобладает отмена ликвидности (отменено ${compact(cancelledQty)} против исполнено ${compact(executedQty)})"
        } else ""

        val migNote = buildString {
            if (replenishCount >= 2) {
                append("Повторное пополнение после сделок (${replenishCount}x)")
            }
            if (churnInflationRatio >= 8.0) {
                if (isNotEmpty()) append(" · ")
                append("Высокий HFT-шум стакана (CTR ×${String.format(Locale.US, "%.1f", churnInflationRatio)})")
            }
            if (pullNote.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(pullNote)
            }
        }

        return DomSnapshotMetrics(
            ofi = weightedOfi,
            normalizedOfi = normOfi,
            bidTotal = bidTotal,
            askTotal = askTotal,
            bookImbalancePct = imbPct,
            addRatePerSec = addRate,
            cancelRatePerSec = cancelRate,
            executedQty = executedQty,
            cancelledQty = cancelledQty,
            replenishmentRate = replenishRate,
            replenishmentEvents = replenishCount,
            levelPersistenceRatio = persistenceRatio,
            largeBids = largeBids,
            largeAsks = largeAsks,
            liquidityPullDetected = pullDetected,
            liquidityPullNote = pullNote,
            migrationNote = migNote,
            synchronized = true,
            lagMs = micro.avgLagMs,
            deltaSequenceCount = deltas.size,
            sufficientData = true,
            note = String.format(
                Locale.US,
                "Исп.: %s · Снято: %s · Пополн.: %d (CTR ×%.1f)",
                compact(executedQty), compact(cancelledQty), replenishCount, churnInflationRatio
            ),
            churnInflationRatio = churnInflationRatio
        )
    }

    private fun mlofiWeight(
        price: Double,
        side: String,
        topBids: List<Pair<Double, Double>>,
        topAsks: List<Pair<Double, Double>>,
        tol: Double
    ): Double {
        val list = if (side == "BID") topBids else topAsks
        for (i in list.indices) {
            if (i >= MLOFI_WEIGHTS.size) break
            if (abs(list[i].first - price) <= tol) return MLOFI_WEIGHTS[i]
        }
        return 0.0
    }

    private fun compact(v: Double): String = when {
        v >= 1_000_000 -> String.format(Locale.US, "%.2fM", v / 1_000_000.0)
        v >= 1_000 -> String.format(Locale.US, "%.1fK", v / 1_000.0)
        else -> String.format(Locale.US, "%.2f", v)
    }

    private fun formatPrice(p: Double): String = when {
        p >= 1000.0 -> String.format(Locale.US, "%.1f", p)
        p >= 1.0 -> String.format(Locale.US, "%.3f", p)
        else -> String.format(Locale.US, "%.5f", p)
    }
}
