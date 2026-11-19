package com.enamind.ai.bot.strategy

/**
 * Слой 4: Изолированные контракты StrategyBridge.
 * Полностью отвязаны от Android Context, Jetpack Compose и сетевого клиента Bybit.
 */

enum class OrderExecutionPreference(val labelRu: String) {
    MAKER_POST_ONLY("Умный Maker (PostOnly)"),
    TAKER_MARKET("Быстрый Market (Taker)");

    companion object {
        fun fromName(name: String?): OrderExecutionPreference =
            entries.firstOrNull { it.name == name } ?: MAKER_POST_ONLY
    }
}

data class MarketContextSnapshot(
    val symbol: String,
    val price: Double,
    val nowMs: Long,
    val dataFreshnessLive: Boolean,
    val conflictDetected: Boolean,
    val spreadPct: Double = 0.02,
    val deltaPct: Double = 0.0,
    val normalizedOfi: Double = 0.0,
    val cvdSlope: Double = 0.0,
    val replenishmentRate: Double = 0.0,
    val levelPersistenceRatio: Double = 0.0,
    val churnInflationRatio: Double = 0.0,
    val liquidityPullDetected: Boolean = false,
    val absorptionSideName: String = "NONE", // "NONE" | "BUYER_ABSORPTION" | "SELLER_ABSORPTION"
    val absorptionDetected: Boolean = false,
    val absorptionConfirmed: Boolean = false,
    val passiveTrapDetected: Boolean = false,
    val progressEfficiency: Double = 0.0,
    val targetLevelPrice: Double? = null,
    val levelIsResistance: Boolean = true,
    val levelDistancePct: Double? = null,
    val levelPhaseName: String = "NONE",
    val glowColorName: String = "NEUTRAL", // "GREEN" | "RED" | "NEUTRAL"
    val glowStrength: Float = 0f,
    val quantAdx: Double? = null,
    val quantEr: Double? = null,
    val spotPerpCvdDivergence: Double = 0.0,
    val spotCvd1m: Double = 0.0,
    val perpCvd1m: Double = 0.0,
    val openInterestValue: Double? = null,
    val oiChange5mPct: Double? = null,
    val fundingRatePct: Double? = null,
    val longLiqUsd15s: Double = 0.0,
    val shortLiqUsd15s: Double = 0.0,
    val longLiqUsd60s: Double = 0.0,
    val shortLiqUsd60s: Double = 0.0,
    val ema9: Double? = null,
    val ema21: Double? = null,
    val emaDriftPct: Double = 0.0,
    val atr1mPct: Double = 0.18,
    val nearestSupport: Double? = null,
    val nearestResistance: Double? = null,
    val vpin: Double = 0.0,
    val kylesLambda: Double = 0.0
)

data class OpenPositionContext(
    val hasOpenPosition: Boolean = false,
    val sideIsBuy: Boolean? = null,
    val avgEntryPrice: Double? = null,
    val lastTrancheEntryPrice: Double? = null,
    val tranchesCount: Int = 0,
    val maxTranches: Int = 3,
    val totalQty: Double = 0.0,
    val totalPaidEntryFeesUsdt: Double = 0.0,
    val netPnlPct: Double = 0.0,
    val peakNetPnlPct: Double = 0.0,
    val drawdownFromLastTranchePct: Double = 0.0,
    val effectiveStepPct: Double = 0.28,
    val peelDone: Boolean = false,
    val peelPartialEnabled: Boolean = true,
    val minNetProfitPct: Double = 0.5,
    val preferMaker: Boolean = true,
    val allowCounterAbsorption: Boolean = false,
    val requireRadarNotFlat: Boolean = false,
    val heldMs: Long = 0L
)

sealed class StrategyDecision {
    data class Hold(val reason: String) : StrategyDecision()
    data class Enter(
        val sideIsBuy: Boolean,
        val orderPreference: OrderExecutionPreference,
        val note: String
    ) : StrategyDecision()

    data class ScaleIn(
        val sideIsBuy: Boolean,
        val orderPreference: OrderExecutionPreference,
        val note: String
    ) : StrategyDecision()

    data class PeelPartial(
        val fraction: Double,
        val note: String
    ) : StrategyDecision()

    data class ExitAll(val reason: String) : StrategyDecision()
}

