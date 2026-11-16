package com.enamind.ai.intelligence

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class BiasDirection { UP, DOWN, FLAT }

enum class BotCategory(val titleRu: String) {
    TREND("Мульти-ТФ тренд"),
    STRUCTURE("Структура цены"),
    FLOW("Поток и ликвидность"),
    RADAR("Chain Radar"),
    SENTINEL("Crypto Sentinel"),
    COUNCIL("Python-контур")
}

data class BotSignal(
    val id: String,
    val name: String,
    val category: BotCategory,
    val bias: BiasDirection,
    val confidence: Double,
    val summary: String,
    val metrics: Map<String, String> = emptyMap(),
    val hasData: Boolean = true
)

data class CausalStep(
    val index: Int,
    val observation: String,
    val mechanism: String,
    val conclusion: String,
    val invalidation: String
)

data class TrendLogicSummary(
    val biasLabel: String,
    val phase: String,
    val strength: Int,
    val entryQuality: String,
    val riskLevel: String,
    val pUp: Double,
    val pFlat: Double,
    val pDown: Double,
    val expectedMovePct: Double,
    val agreementPct: Int,
    val mtfDirections: Map<String, String>,
    val structureLabel: String,
    val causalChain: List<CausalStep>,
    val conflicts: List<String>,
    val invalidationRules: List<String>
)

data class OutlookSummary(
    val horizonHours: Int,
    val side: String, // "UP" | "DOWN" | "FLAT"
    val pUp: Double,
    val pFlat: Double,
    val pDown: Double,
    val expectedMovePct: Double,
    val sigmaPct: Double,
    val rangeLowPct: Double,
    val rangeHighPct: Double,
    val thresholdPct: Double,
    val calibrationLabel: String,
    val drivers: List<String> = emptyList(),
    val invalidationRules: List<String> = emptyList(),
    val computedAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(symbol: String, price: Double? = null): JSONObject = JSONObject().apply {
        put("ok", true)
        put("symbol", symbol)
        put("horizon_h", horizonHours)
        put("horizon_hours", horizonHours)
        put("side", side)
        put("p_up", pUp)
        put("p_flat", pFlat)
        put("p_down", pDown)
        put("expected_move_pct", expectedMovePct)
        put("sigma_pct", sigmaPct)
        put("range_low_pct", rangeLowPct)
        put("range_high_pct", rangeHighPct)
        put("threshold_pct", thresholdPct)
        put("calibration", calibrationLabel)
        put("fresh", true)
        put("computed_at_ms", computedAtMs)
        if (price != null && price.isFinite() && price > 0.0) {
            put("price", price)
            put("range_low_price", price * (1.0 + rangeLowPct / 100.0))
            put("range_high_price", price * (1.0 + rangeHighPct / 100.0))
        }
        put("drivers", JSONArray().apply { drivers.forEach { put(it) } })
        put("invalidation", JSONArray().apply { invalidationRules.forEach { put(it) } })
    }
}

data class VenueSummary(
    val activeVenues: Int = 0,
    val venueNames: List<String> = emptyList(),
    val coinbasePrice: Double? = null,
    val krakenPrice: Double? = null,
    val binanceTradeVelPerSec: Double? = null,
    val binanceAggrBuyPct: Double? = null,
    val maxSpreadBps: Double? = null
)

data class LargeTransferItem(
    val amountBtc: Double,
    val usdApprox: Double?,
    val txHashShort: String,
    val directionLabel: String,
    val tsSec: Long
)

data class ChainRadarSnapshot(
    val symbol: String,
    val updatedAtMs: Long,
    val preChartAnswer: String,
    val moneyFlowBias: String, // ACCUMULATION / DISTRIBUTION / NEUTRAL / INSUFFICIENT
    val whaleTradesCount: Int = 0,
    val whaleBuyUsd: Double = 0.0,
    val whaleSellUsd: Double = 0.0,
    val whaleDeltaPct: Double? = null,
    val mempoolTxCount: Int? = null,
    val feeFastSat: Int? = null,
    val feeHalfHourSat: Int? = null,
    val btcLargeTransfers: List<LargeTransferItem> = emptyList(),
    val stablecoinsTotalUsd: Double? = null,
    val stablecoinsChange7dPct: Double? = null,
    val defiTotalTvlUsd: Double? = null,
    val topChains: List<Pair<String, Double>> = emptyList(),
    val sources: Map<String, String> = emptyMap()
)

enum class SentinelSeverity(val labelRu: String) {
    CRITICAL("КРИТИЧНО"),
    WARNING("ВНИМАНИЕ"),
    INFO("ФАКТ")
}

enum class SentinelCategory(val labelRu: String) {
    WHALE_TRANSFER("Крупный перевод"),
    VOLATILITY_SHOCK("Шок волатильности"),
    VOLUME_SPIKE("Аномалия объёма"),
    LIQUIDATION_WAVE("Каскад ликвидаций"),
    EXCHANGE_SPREAD("Рассинхрон бирж"),
    FUNDING_EXTREME("Экстремум фандинга"),
    CROWD_EXTREME("Перекос толпы LSR"),
    SECURITY_EXPLOIT("Безопасность / Взлом"),
    TOKEN_UNLOCK("Разблокировка / Эмиссия"),
    REGULATION_MACRO("Регуляция / Макро")
}

data class SentinelEvent(
    val id: String,
    val title: String,
    val detail: String,
    val severity: SentinelSeverity,
    val category: SentinelCategory,
    val source: String,
    val tsMs: Long,
    val metricBadge: String = ""
)

data class LearningSnapshot(
    val folderLabel: String = "ENA Memory",
    val totalRecorded: Int = 0,
    val verifiedCount: Int = 0,
    val hitRatePct: Double? = null,
    val botWeights: Map<String, Double> = emptyMap(),
    val botHitRates: Map<String, Double> = emptyMap(),
    val phaseHitRates: Map<String, Double> = emptyMap(),
    val lastVerifiedAtMs: Long = 0L
)

data class QuantDiagnostics(
    val clockSynced: Boolean = false,
    val clockOffsetMs: Long = 0L,
    val clockRttMs: Long = 0L,
    val clockSource: String = "device",
    val dataQualityScore: Double = 1.0,
    val dataGrade: String = "A",
    val dataNotes: List<String> = emptyList(),
    val adx: Double = 0.0,
    val efficiencyRatio: Double = 0.0,
    val regressionR2: Double = 0.0,
    val walkForwardSamples: Int = 0,
    val walkForwardHitPct: Double? = null,
    val brierScore: Double? = null,
    val calibrationFactor: Double = 1.0
)

data class UnifiedIntelligenceReport(
    val symbol: String,
    val price: Double?,
    val sourceLabel: String,
    val pythonEngineOk: Boolean,
    val trendLogic: TrendLogicSummary,
    val bots: List<BotSignal>,
    val chainRadar: ChainRadarSnapshot,
    val sentinelEvents: List<SentinelEvent>,
    val synthesisHeadline: String,
    val synthesisNarrative: String,
    val computedAtMs: Long,
    val learning: LearningSnapshot = LearningSnapshot(),
    val quant: QuantDiagnostics = QuantDiagnostics(),
    val outlook6h: OutlookSummary? = null,
    val outlook24h: OutlookSummary? = null,
    val venues: VenueSummary = VenueSummary()
) {
    /**
     * Унифицированный JSON-адаптер: позволяет чату и UI работать единообразно
     * как поверх Python-ядра, так и при прямом Kotlin-вычислении.
     */
    fun toEngineJson(): JSONObject {
        val side = when {
            trendLogic.pUp >= trendLogic.pDown && trendLogic.pUp >= trendLogic.pFlat -> "UP"
            trendLogic.pDown > trendLogic.pUp && trendLogic.pDown >= trendLogic.pFlat -> "DOWN"
            else -> "FLAT"
        }
        val conf = maxOf(trendLogic.pUp, trendLogic.pDown, trendLogic.pFlat)
        val predFeatures = JSONObject()
            .put("p_up", trendLogic.pUp)
            .put("p_flat", trendLogic.pFlat)
            .put("p_down", trendLogic.pDown)
            .put("expected_move_pct", trendLogic.expectedMovePct)
            .put("horizon_hours", 6)
            .put("sigma_6h_pct", outlook6h?.sigmaPct ?: JSONObject.NULL)

        val invArr = JSONArray().apply {
            trendLogic.invalidationRules.forEach { put(it) }
        }
        val prediction = JSONObject()
            .put("status", if (outlook6h != null) "OK" else "INSUFFICIENT")
            .put("side", side)
            .put("confidence", conf)
            .put("features", predFeatures)
            .put("invalidate", invArr)

        val forecast = JSONObject()
            .put("side", side)
            .put("horizon_hours", 6)
            .put("p_up", trendLogic.pUp)
            .put("p_flat", trendLogic.pFlat)
            .put("p_down", trendLogic.pDown)
            .put("expected_move_pct", trendLogic.expectedMovePct)
            .put("council_status", if (trendLogic.conflicts.isEmpty()) "AGREE" else "CAUTION")

        val mtfObj = JSONObject()
        trendLogic.mtfDirections["micro"]?.let {
            mtfObj.put("micro", JSONObject().put("status", "OK").put("tf", "1–5м").put("direction", it).put("strength", trendLogic.strength))
        }
        trendLogic.mtfDirections["short"]?.let {
            mtfObj.put("short", JSONObject().put("status", "OK").put("tf", "15м–1ч").put("direction", it).put("strength", trendLogic.strength))
        }
        trendLogic.mtfDirections["mid"]?.let {
            mtfObj.put("mid", JSONObject().put("status", "OK").put("tf", "4ч–6ч").put("direction", it).put("strength", trendLogic.strength))
        }

        val stObj = JSONObject()
            .put("status", "OK")
            .put("structure", trendLogic.structureLabel)
            .put("events", JSONArray().apply {
                trendLogic.causalChain.take(3).forEach { put("${it.observation} → ${it.conclusion}") }
            })

        val trend = JSONObject()
            .put("status", "OK")
            .put("bias", trendLogic.biasLabel)
            .put("phase", trendLogic.phase)
            .put("strength", trendLogic.strength)
            .put("entry_quality", trendLogic.entryQuality)
            .put("risk", trendLogic.riskLevel)
            .put("confidence", conf)
            .put("mtf", mtfObj)
            .put("structure", stObj)
            .put("conflicts", JSONArray().apply { trendLogic.conflicts.forEach { put(it) } })

        val agentsArr = JSONArray()
        bots.forEach { b ->
            val agentSide = when (b.bias) {
                BiasDirection.UP -> "LONG"
                BiasDirection.DOWN -> "SHORT"
                BiasDirection.FLAT -> "FLAT"
            }
            agentsArr.put(
                JSONObject()
                    .put("agent", b.name)
                    .put("status", "OK")
                    .put("side", agentSide)
                    .put("confidence", b.confidence)
                    .put("summary", b.summary)
            )
        }

        val council = JSONObject()
            .put("status", if (trendLogic.agreementPct >= 60 && trendLogic.conflicts.isEmpty()) "AGREE" else "CAUTION")
            .put("note", "Согласие ботов ${trendLogic.agreementPct}% · фаза: ${trendLogic.phase}")
            .put("contradictions", JSONArray().apply { trendLogic.conflicts.forEach { put(it) } })

        val okSources = chainRadar.sources.count { it.value == "LIVE" } + venues.activeVenues.coerceAtLeast(if (price != null) 1 else 0)
        val totalSources = (chainRadar.sources.size + maxOf(1, venues.activeVenues)).coerceAtLeast(1)
        val dataQuality = JSONObject()
            .put("grade", quant.dataGrade)
            .put("score", quant.dataQualityScore)
            .put("n_available", okSources)
            .put("n_total", totalSources)

        val quantObj = JSONObject()
            .put("clock_synced", quant.clockSynced)
            .put("clock_offset_ms", quant.clockOffsetMs)
            .put("clock_source", quant.clockSource)
            .put("adx", quant.adx)
            .put("efficiency_ratio", quant.efficiencyRatio)
            .put("regression_r2", quant.regressionR2)
            .put("walk_forward_samples", quant.walkForwardSamples)
            .put("walk_forward_hit_pct", quant.walkForwardHitPct ?: JSONObject.NULL)
            .put("brier_score", quant.brierScore ?: JSONObject.NULL)
            .put("calibration_factor", quant.calibrationFactor)

        return JSONObject().apply {
            put("ok", true)
            put("symbol", symbol)
            put("mode", "ANALYTICS")
            put("engine", "ena-core")
            put("source", sourceLabel)
            put("data", dataQuality)
            put("quant_diagnostics", quantObj)
            put("prediction", prediction)
            put("forecast", forecast)
            put("trend", trend)
            put("council", council)
            put("agents", agentsArr)
            put("synthesis_headline", synthesisHeadline)
            put("synthesis_narrative", synthesisNarrative)
            outlook6h?.let { put("outlook_6h", it.toJson(symbol, price)) }
            outlook24h?.let { put("outlook_24h", it.toJson(symbol, price)) }
            if (venues.activeVenues > 0) {
                put("multi_venue", JSONObject().apply {
                    put("active_venues", venues.activeVenues)
                    put("venues", JSONArray(venues.venueNames))
                    venues.coinbasePrice?.let { put("coinbase_price", it) }
                    venues.krakenPrice?.let { put("kraken_price", it) }
                    venues.binanceTradeVelPerSec?.let { put("binance_trade_vel_sec", it) }
                    venues.binanceAggrBuyPct?.let { put("binance_aggr_buy_pct", it) }
                    venues.maxSpreadBps?.let { put("max_spread_bps", it) }
                })
            }
        }
    }
}

internal fun safeFmtDouble(fmt: String, value: Double, fallback: String = "—"): String {
    if (!value.isFinite()) return fallback
    return runCatching { String.format(Locale.US, fmt, value) }.getOrDefault(fallback)
}
