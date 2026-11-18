package com.enamind.ai.orderflow

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Единый координатор ENA — ORDER FLOW & ABSORPTION INTELLIGENCE.
 * Собирает срез реального времени из OrderFlowStreamHub, оценивает свежесть и рассинхрон,
 * прогоняет FootprintEngine -> DomAnalyzer (с привязкой к атакуемому уровню) -> AbsorptionDetector
 * -> LevelAttackDetector (с ADX/ER из ядра Радара) -> ScenarioAnalyzer (+ Glow Temporal Hysteresis),
 * ведёт пост-проверку в OrderFlowEventVault и публикует OrderFlowReport.
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

    // Temporal hysteresis state for Glow anti-flicker
    private var lastGlowSymbol: String = ""
    private var pendingGlowColor: GlowColorState = GlowColorState.NEUTRAL
    private var pendingGlowSinceMs: Long = 0L
    private var stableGlowColor: GlowColorState = GlowColorState.NEUTRAL
    private var neutralSinceMs = 0L

    fun resetForInstrument(symbol: String) {
        streamHub.clearSymbol(symbol)
        confirmedMarkersHistory.clear()
        lastGlowSymbol = ""
        stableGlowColor = GlowColorState.NEUTRAL
        _report.value = OrderFlowReport(symbol = symbol)
    }

    fun evaluate(
        symbol: String,
        interval: String,
        tickSize: Double?,
        livePrice: Double?,
        bars: List<List<Double>>,
        nowMs: Long = System.currentTimeMillis(),
        quantAdx: Double? = null,
        quantEr: Double? = null,
        signalConfirmMs: Long = DEFAULT_GLOW_CONFIRM_MS,
        venueSyncWarning: String? = null
    ): OrderFlowReport {
        if (lastGlowSymbol != symbol) {
            lastGlowSymbol = symbol
            pendingGlowColor = GlowColorState.NEUTRAL
            pendingGlowSinceMs = nowMs
            stableGlowColor = GlowColorState.NEUTRAL
        }

        val micro = streamHub.snapshot(symbol, nowMs)
        val effectivePrice = livePrice?.takeIf { it.isFinite() && it > 0.0 }
            ?: micro?.trades?.lastOrNull()?.price
            ?: bars.lastOrNull()?.getOrNull(4)

        val tradeAgeMs = if (micro != null && micro.lastTradeTsMs > 0L) (nowMs - micro.lastTradeTsMs).coerceAtLeast(0L) else Long.MAX_VALUE
        val bookAgeMs = if (micro != null && micro.lastBookTsMs > 0L) (nowMs - micro.lastBookTsMs).coerceAtLeast(0L) else Long.MAX_VALUE
        val isWsStale = (micro?.wsStale == true) || tradeAgeMs > STALE_THRESHOLD_MS || bookAgeMs > STALE_THRESHOLD_MS

        val freshness = when {
            micro == null || (micro.trades.isEmpty() && micro.bids.isEmpty()) -> DataFreshness.INSUFFICIENT
            !micro.bookSynced && micro.desyncReason != null -> DataFreshness.DESYNC
            isWsStale -> DataFreshness.STALE
            else -> DataFreshness.LIVE
        }

        val freshnessWarn = when (freshness) {
            DataFreshness.LIVE -> if ((micro?.avgLagMs ?: 0L) > 1200L) "Повышенная задержка сети: ${micro?.avgLagMs} мс" else null
            DataFreshness.STALE -> if (micro?.wsStale == true) {
                "WS_STALE (${micro.desyncReason ?: "переподключение"}): выполняется ресинхронизация стакана"
            } else {
                "WS_STALE: поток сделок/стакана устарел (возр. тиков ${if (tradeAgeMs > 600_000) "—" else "${tradeAgeMs / 1000}с"})"
            }
            DataFreshness.DESYNC -> "Рассинхрон стакана (${micro?.desyncReason ?: "потеря последовательности"}) — выполняется ресинхронизация"
            DataFreshness.INSUFFICIENT -> "Ожидание реальных сделок и обновлений стакана по $symbol…"
        }

        val trades = micro?.trades.orEmpty()
        val footprint = FootprintEngine.analyze(trades, tickSize, bars)
        val preDom = DomAnalyzer.analyze(micro, nowMs)

        // Первый проход структурных уровней, чтобы определить активный уровень и зону допуска
        val preLevels = LevelAttackDetector.analyze(
            currentPrice = effectivePrice,
            bars = bars,
            trades = trades,
            footprint = footprint,
            dom = preDom,
            absorption = AbsorptionMetrics(),
            tickSize = tickSize,
            quantAdx = quantAdx,
            quantEfficiencyRatio = quantEr
        )
        val focusLevel = preLevels.attackState.levelPrice
        val focusTol = if (effectivePrice != null && effectivePrice > 0.0) {
            max((tickSize ?: 0.0) * 5.0, min(effectivePrice * 0.0008, effectivePrice * 0.0018))
        } else null
        val dom = if (focusLevel != null) {
            DomAnalyzer.analyze(micro, nowMs, activeLevelPrice = focusLevel, zoneTolerance = focusTol)
        } else {
            preDom
        }

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
            absorption = absorption,
            tickSize = tickSize,
            quantAdx = quantAdx,
            quantEfficiencyRatio = quantEr
        )

        // Post-only проверка исторических событий по реально поступившим тикам, свежему ТФ-бару или LIVE-цене (без заморозки!)
        if (trades.isNotEmpty() || bars.isNotEmpty() || (freshness == DataFreshness.LIVE && effectivePrice != null)) {
            vault.verifyForwardOutcomes(
                symbol = symbol,
                futureTicks = trades,
                nowMs = nowMs,
                bars = bars,
                barsInterval = interval,
                livePrice = if (freshness == DataFreshness.LIVE && !isWsStale) effectivePrice else null
            )
        }
        val vaultStats = vault.computeStats(symbol)

        val lastBar = bars.lastOrNull()
        val candleOpen = lastBar?.getOrNull(1)
        val candleClose = lastBar?.getOrNull(4)
        val candleChangePct = if (candleOpen != null && candleClose != null && candleOpen > 0.0) {
            100.0 * (candleClose - candleOpen) / candleOpen
        } else null

        val synthesis = ScenarioAnalyzer.synthesize(
            symbol = symbol,
            price = effectivePrice,
            freshness = freshness,
            footprint = footprint,
            dom = dom,
            absorption = absorption,
            attack = levelResult.attackState,
            vaultStats = vaultStats,
            quantAdx = quantAdx,
            quantEfficiencyRatio = quantEr,
            candleChangePct = candleChangePct
        )

        val stabilizedGlow = applyGlowHysteresis(synthesis.glow, freshness, nowMs, signalConfirmMs)

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
            wsStale = isWsStale,
            venueSyncWarning = venueSyncWarning,
            dominance = synthesis.dominance,
            footprint = footprint,
            dom = dom,
            absorption = absorption,
            levelAttack = levelResult.attackState,
            scenarioA = synthesis.scenarioA,
            scenarioB = synthesis.scenarioB,
            explanationText = synthesis.explanationText,
            confidence = synthesis.confidence,
            glow = stabilizedGlow,
            overlay = overlay,
            councilPayload = synthesis.councilPayload
        )

        if (freshness == DataFreshness.LIVE && !isWsStale && effectivePrice != null) {
            vault.recordEvent(rep)
        }
        _report.value = rep
        return rep
    }

    private fun applyGlowHysteresis(
        rawGlow: GlowSignal,
        freshness: DataFreshness,
        nowMs: Long,
        confirmMs: Long
    ): GlowSignal {
        // Сбой, рассинхрон, устаревание или конфликт сразу переводят свечение в NEUTRAL без задержки
        if (freshness != DataFreshness.LIVE || rawGlow.conflictDetected) {
            pendingGlowColor = GlowColorState.NEUTRAL
            pendingGlowSinceMs = nowMs
            stableGlowColor = GlowColorState.NEUTRAL
            return rawGlow.copy(colorState = GlowColorState.NEUTRAL)
        }

        if (rawGlow.colorState == GlowColorState.NEUTRAL) {
            if (neutralSinceMs == 0L) neutralSinceMs = nowMs
            if (nowMs - neutralSinceMs < 1200L && stableGlowColor != GlowColorState.NEUTRAL)
                return rawGlow.copy(colorState = stableGlowColor, strength = 0.20f)
            stableGlowColor = GlowColorState.NEUTRAL
            pendingGlowColor = GlowColorState.NEUTRAL
            return rawGlow
        }
        neutralSinceMs = 0L
        if (rawGlow.colorState == stableGlowColor) {
            pendingGlowColor = rawGlow.colorState
            return rawGlow
        }

        if (pendingGlowColor != rawGlow.colorState) {
            pendingGlowColor = rawGlow.colorState
            pendingGlowSinceMs = nowMs
        }

        val elapsed = (nowMs - pendingGlowSinceMs).coerceAtLeast(0L)
        return if (elapsed >= confirmMs.coerceAtMost(1500L)) {
            stableGlowColor = rawGlow.colorState
            rawGlow
        } else {
            val leftSec = ((confirmMs - elapsed + 999L) / 1000L).coerceAtLeast(1L)
            rawGlow.copy(
                colorState = stableGlowColor,
                strength = if (stableGlowColor == GlowColorState.NEUTRAL) 0.14f else rawGlow.strength * 0.7f,
                reason = "${rawGlow.reason} · стабилизация ${leftSec}с"
            )
        }
    }

    fun exportHistoryBundle(): OrderFlowEventVault.ExportBundle = vault.exportHistoryBundle()

    companion object {
        private const val STALE_THRESHOLD_MS = 18_000L
        private const val DEFAULT_GLOW_CONFIRM_MS = 4_000L
    }
}
