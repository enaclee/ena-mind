package com.enamind.ai.orderflow

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Единый координатор ENA — ORDER FLOW & ABSORPTION INTELLIGENCE.
 * Собирает срез реального времени из OrderFlowStreamHub, оценивает свежесть и рассинхрон,
 * прогоняет FootprintEngine -> DomAnalyzer -> AbsorptionDetector -> LevelAttackDetector -> ScenarioAnalyzer,
 * ведёт честную пост-проверку (1с/5с/15с/30с) в OrderFlowEventVault и публикует OrderFlowReport.
 */
class OrderFlowIntelligenceOrchestrator(
    ctx: Context
) {
    val streamHub = OrderFlowStreamHub()
    val vault = OrderFlowEventVault(
        primaryDir = File(File(ctx.filesDir, "enamind"), "order_flow_events"),
        mirrorDir = ctx.getExternalFilesDir("ENA-OrderFlow")
    ).also { it.ensureInitialized() }

    private val _report = MutableStateFlow(OrderFlowReport())
    val report: StateFlow<OrderFlowReport> = _report

    private val confirmedMarkersHistory = ArrayDeque<Pair<Long, String>>(12)

    fun evaluate(
        symbol: String,
        interval: String,
        tickSize: Double?,
        livePrice: Double?,
        bars: List<List<Double>>,
        nowMs: Long = System.currentTimeMillis()
    ): OrderFlowReport {
        val micro = streamHub.snapshot(symbol, nowMs)
        val effectivePrice = livePrice?.takeIf { it.isFinite() && it > 0.0 }
            ?: micro?.trades?.lastOrNull()?.price
            ?: bars.lastOrNull()?.getOrNull(4)

        val tradeAgeMs = if (micro != null && micro.lastTradeTsMs > 0L) (nowMs - micro.lastTradeTsMs).coerceAtLeast(0L) else Long.MAX_VALUE
        val bookAgeMs = if (micro != null && micro.lastBookTsMs > 0L) (nowMs - micro.lastBookTsMs).coerceAtLeast(0L) else Long.MAX_VALUE

        val freshness = when {
            micro == null || (micro.trades.isEmpty() && micro.bids.isEmpty()) -> DataFreshness.INSUFFICIENT
            !micro.bookSynced && micro.desyncReason != null -> DataFreshness.DESYNC
            tradeAgeMs > STALE_THRESHOLD_MS || bookAgeMs > STALE_THRESHOLD_MS -> DataFreshness.STALE
            else -> DataFreshness.LIVE
        }

        val freshnessWarn = when (freshness) {
            DataFreshness.LIVE -> if ((micro?.avgLagMs ?: 0L) > 1200L) "Повышенная задержка сети: ${micro?.avgLagMs} мс" else null
            DataFreshness.STALE -> "Поток сделок/стакана устарел (возр. тиков ${if (tradeAgeMs > 600_000) "—" else "${tradeAgeMs / 1000}с"}) — расчёты приглушены"
            DataFreshness.DESYNC -> "Рассинхрон стакана (${micro?.desyncReason ?: "потеря последовательности"}) — выполняется ресинхронизация"
            DataFreshness.INSUFFICIENT -> "Ожидание реальных сделок и обновлений стакана по $symbol…"
        }

        val trades = micro?.trades.orEmpty()
        val footprint = FootprintEngine.analyze(trades, tickSize, bars)
        val dom = DomAnalyzer.analyze(micro, nowMs)

        // Сначала структурные уровни без поглощения, чтобы передать целевые уровни в AbsorptionDetector
        val preLevels = LevelAttackDetector.analyze(
            currentPrice = effectivePrice,
            bars = bars,
            trades = trades,
            footprint = footprint,
            dom = dom,
            absorption = AbsorptionMetrics()
        )
        val absorption = AbsorptionDetector.analyze(
            trades = trades,
            footprint = footprint,
            dom = dom,
            bars = bars,
            candidateResistance = preLevels.resistances.firstOrNull(),
            candidateSupport = preLevels.supports.firstOrNull()
        )
        val levelResult = LevelAttackDetector.analyze(
            currentPrice = effectivePrice,
            bars = bars,
            trades = trades,
            footprint = footprint,
            dom = dom,
            absorption = absorption
        )

        // Post-only проверка исторических событий по реально поступившим тикам (без look-ahead!)
        if (trades.isNotEmpty()) {
            vault.verifyForwardOutcomes(symbol, trades, nowMs)
        }
        val vaultStats = vault.computeStats(symbol)

        val synthesis = ScenarioAnalyzer.synthesize(
            symbol = symbol,
            price = effectivePrice,
            freshness = freshness,
            footprint = footprint,
            dom = dom,
            absorption = absorption,
            attack = levelResult.attackState,
            vaultStats = vaultStats
        )

        if (levelResult.attackState.phase == LevelAttackPhase.CONFIRMED_REJECTION ||
            levelResult.attackState.phase == LevelAttackPhase.CONFIRMED_BREAKOUT
        ) {
            val kind = if (levelResult.attackState.phase == LevelAttackPhase.CONFIRMED_BREAKOUT) "BREAKOUT" else "REJECTION"
            val lastMark = confirmedMarkersHistory.lastOrNull()
            if (lastMark == null || (nowMs - lastMark.first) > 15_000L || lastMark.second != kind) {
                if (confirmedMarkersHistory.size >= 8) confirmedMarkersHistory.removeFirst()
                confirmedMarkersHistory.addLast(nowMs to kind)
            }
        }

        val highLiq = buildList {
            dom.largeBids.take(3).forEach { add(it.price to "BID") }
            dom.largeAsks.take(3).forEach { add(it.price to "ASK") }
        }
        val absZones = listOfNotNull(absorption.targetLevelPrice.takeIf { absorption.detected })

        val overlay = ChartOverlayData(
            supportLevels = levelResult.supports,
            resistanceLevels = levelResult.resistances,
            highLiquidityZones = highLiq,
            absorptionZones = absZones,
            attackMarkerPrices = levelResult.attackState.repeatedAttackZones,
            failedBreakoutPrices = levelResult.attackState.failedBreakoutZones,
            confirmedEventMarkers = confirmedMarkersHistory.toList()
        )

        val rep = OrderFlowReport(
            symbol = symbol,
            exchangeSource = micro?.primaryVenue?.ifBlank { "Поток" } ?: "Нет потока",
            interval = interval,
            price = effectivePrice,
            computedAtMs = nowMs,
            freshness = freshness,
            freshnessWarning = freshnessWarn,
            dominance = synthesis.dominance,
            footprint = footprint,
            dom = dom,
            absorption = absorption,
            levelAttack = levelResult.attackState,
            scenarioA = synthesis.scenarioA,
            scenarioB = synthesis.scenarioB,
            explanationText = synthesis.explanationText,
            confidence = synthesis.confidence,
            glow = synthesis.glow,
            overlay = overlay,
            councilPayload = synthesis.councilPayload
        )

        if (freshness == DataFreshness.LIVE && effectivePrice != null) {
            vault.recordEvent(rep)
        }
        _report.value = rep
        return rep
    }

    fun exportHistoryBundle(): OrderFlowEventVault.ExportBundle = vault.exportHistoryBundle()

    companion object {
        private const val STALE_THRESHOLD_MS = 18_000L
    }
}
