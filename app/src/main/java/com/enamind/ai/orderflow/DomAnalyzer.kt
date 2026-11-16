package com.enamind.ai.orderflow

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * DOM Analyzer — анализ последовательности обновлений стакана в реальном времени.
 *
 * Ключевой принцип честности:
 *  - Каждое уменьшение объёма на уровне стакана сопоставляется со сделками в синхронизированном
 *    окне (±250 мс) по близкой цене:
 *      * Если встречная сделка произошла -> это ИСПОЛНЕНИЕ (executedQty);
 *      * Если сделок в это время на уровне не было -> это СНЯТИЕ / ОТМЕНА (cancelledQty).
 *  - Пополнение ликвидности после исполнения фиксируется, но НИКОГДА не объявляется
 *    «подтверждённым iceberg» по одному лишь факту пополнения.
 */
object DomAnalyzer {

    private const val MATCH_WINDOW_MS = 250L

    fun analyze(
        micro: OrderFlowStreamHub.VenueMicroSnapshot?,
        nowMs: Long
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

        val bids = micro.bids
        val asks = micro.asks
        val bestBid = bids.maxOfOrNull { it.first } ?: 0.0
        val bestAsk = asks.minOfOrNull { it.first } ?: 0.0
        val mid = if (bestBid > 0 && bestAsk > 0) (bestBid + bestAsk) / 2.0 else max(bestBid, bestAsk)
        val priceTol = (mid * 0.0005).coerceAtLeast(abs(bestAsk - bestBid) * 1.5).coerceAtLeast(1e-8)

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
        var ofi = 0.0
        var absOfiMass = 0.0
        var executedQty = 0.0
        var cancelledQty = 0.0
        var replenishCount = 0
        var replenishQty = 0.0
        var totalDrops = 0
        var survivedDrops = 0
        val removedLargeWalls = mutableListOf<String>()

        // Последнее исполнение по уровню (key = bucket int) для обнаружения пополнения после исполнения
        val recentExecutedAtPrice = HashMap<Long, Long>()
        // Суммарный объём подходящих сделок для потребления при матчинге (чтобы 1 сделка не списывала 10 снятий)
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
            val nearSpread = mid <= 0.0 || abs(d.price - mid) <= mid * 0.008 // фокус в пределах 0.8% от спреда

            if (nearSpread) {
                val signedFlow = if (d.side == "BID") dq else -dq
                ofi += signedFlow
                absOfiMass += abs(dq)
            }

            val bucket = (d.price / priceTol).toLong()
            if (dq > 0.0) {
                addCount++
                val execTs = recentExecutedAtPrice[bucket]
                if (execTs != null && (d.tsMs - execTs) in 0L..12_000L) {
                    replenishCount++
                    replenishQty += dq
                }
            } else if (dq < 0.0) {
                val dropQty = -dq
                if (nearSpread) totalDrops++
                // Сопоставляем снятие/снижение со встречными сделками в ±MATCH_WINDOW_MS
                // Уровень BID исполняется рыночными продажами (isAggressiveBuy == false), ASK — покупками
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
                    recentExecutedAtPrice[bucket] = d.tsMs
                    if (nearSpread && d.newQty > 0.0) survivedDrops++
                }
                if (cancelPart > 1e-9) {
                    cancelledQty += cancelPart
                    cancelCount++
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

        val normOfi = if (absOfiMass > 1e-9) (ofi / absOfiMass).coerceIn(-1.0, 1.0) else 0.0
        val addRate = addCount / spanSec
        val cancelRate = cancelCount / spanSec
        val denomRep = max(1e-9, executedQty)
        val replenishRate = if (executedQty > 0.0) (replenishQty / denomRep).coerceIn(0.0, 3.0) else 0.0
        val persistenceRatio = if (totalDrops > 0) {
            (1.0 - (cancelCount.toDouble() / max(1, totalDrops))).coerceIn(0.0, 1.0)
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
            if (pullNote.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(pullNote)
            }
        }

        return DomSnapshotMetrics(
            ofi = ofi,
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
                "Исп.: %s · Снято: %s · Пополн.: %d (из %d дельт)",
                compact(executedQty), compact(cancelledQty), replenishCount, deltas.size
            )
        )
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
