package com.enamind.ai.bot.strategy

import java.util.Locale
import kotlin.math.abs

/**
 * Модель 2: SQUEEZE_LIQUIDATION_HUNTER.
 * Охотник за пробоями сброшенных стен (спуфинг / резкое падение levelPersistence при высоком CTR)
 * и каскадами ликвидаций (allLiquidation + VPIN + OI).
 */
class SqueezeLiquidationHunterStrategy : StrategyBridge {
    override val id: StrategyModelId = StrategyModelId.SQUEEZE_LIQUIDATION_HUNTER

    override fun evaluate(
        snapshot: MarketContextSnapshot,
        openPosition: OpenPositionContext
    ): StrategyDecision {
        if (!snapshot.dataFreshnessLive) {
            return StrategyDecision.Hold("Ожидание LIVE потока (Squeeze)")
        }

        val defaultPref = if (openPosition.preferMaker) {
            OrderExecutionPreference.MAKER_POST_ONLY
        } else {
            OrderExecutionPreference.TAKER_MARKET
        }

        // Сопровождение открытой позиции
        if (openPosition.hasOpenPosition && openPosition.sideIsBuy != null) {
            val isBuy = openPosition.sideIsBuy
            if (openPosition.peelPartialEnabled &&
                !openPosition.peelDone &&
                openPosition.tranchesCount >= 2 &&
                openPosition.netPnlPct >= openPosition.minNetProfitPct + 0.15
            ) {
                return StrategyDecision.PeelPartial(
                    0.50,
                    "Фиксация 50% импульса сквиза (+${fmt(openPosition.netPnlPct)}%)"
                )
            }
            if (openPosition.tranchesCount < openPosition.maxTranches &&
                openPosition.drawdownFromLastTranchePct >= openPosition.effectiveStepPct
            ) {
                // Добор только когда первичный каскад ликвидаций затухает (VPIN < 0.65) и поток возвращается
                val cascadeSubsided = snapshot.vpin < 0.65 && (snapshot.longLiqUsd15s + snapshot.shortLiqUsd15s) < 10_000.0
                val freshAlign = (isBuy && snapshot.deltaPct >= 16.0 && snapshot.normalizedOfi >= 0.10) ||
                    (!isBuy && snapshot.deltaPct <= -16.0 && snapshot.normalizedOfi <= -0.10)
                if (cascadeSubsided && freshAlign && !snapshot.conflictDetected) {
                    return StrategyDecision.ScaleIn(
                        sideIsBuy = isBuy,
                        orderPreference = defaultPref,
                        note = "Добор сквиза #${openPosition.tranchesCount + 1}: затухание VPIN ${fmt(snapshot.vpin)}"
                    )
                }
            }
            return StrategyDecision.Hold("Удержание Squeeze-позиции (${openPosition.tranchesCount}/${openPosition.maxTranches})")
        }

        // Сигнал 1: каскад ликвидаций + VPIN
        val liqShortSqueeze = snapshot.shortLiqUsd15s >= 3_000.0 &&
            snapshot.shortLiqUsd15s >= snapshot.longLiqUsd15s * 1.4 &&
            snapshot.deltaPct >= 14.0
        val liqLongSqueeze = snapshot.longLiqUsd15s >= 3_000.0 &&
            snapshot.longLiqUsd15s >= snapshot.shortLiqUsd15s * 1.4 &&
            snapshot.deltaPct <= -14.0

        // Сигнал 2: провалившаяся/снятая стена спуфера у уровня (Failed-Wall Squeeze / H3)
        val spoofingDrop = (snapshot.liquidityPullDetected || snapshot.churnInflationRatio >= 8.0) &&
            snapshot.levelPersistenceRatio < 0.30
        val wallBreakUp = spoofingDrop && snapshot.deltaPct >= 22.0 && snapshot.normalizedOfi >= 0.15 &&
            (snapshot.progressEfficiency > 0.20 || snapshot.levelPhaseName == "CONFIRMED_BREAKOUT")
        val wallBreakDown = spoofingDrop && snapshot.deltaPct <= -22.0 && snapshot.normalizedOfi <= -0.15 &&
            (snapshot.progressEfficiency > 0.20 || snapshot.levelPhaseName == "CONFIRMED_BREAKOUT")

        // Сигнал 3: резкий токсичный сдвиг потока при высоком VPIN и подтверждении OI
        val vpinSurgeUp = snapshot.vpin >= 0.62 && snapshot.deltaPct >= 26.0 &&
            (snapshot.oiChange5mPct == null || abs(snapshot.oiChange5mPct) >= 0.05)
        val vpinSurgeDown = snapshot.vpin >= 0.62 && snapshot.deltaPct <= -26.0 &&
            (snapshot.oiChange5mPct == null || abs(snapshot.oiChange5mPct) >= 0.05)

        val buySqueeze = liqShortSqueeze || wallBreakUp || vpinSurgeUp
        val sellSqueeze = liqLongSqueeze || wallBreakDown || vpinSurgeDown

        if (buySqueeze == sellSqueeze) {
            return StrategyDecision.Hold(
                "Ожидание сквиза/сброса стен (VPIN ${fmt(snapshot.vpin)}, Liq $${fmt(snapshot.longLiqUsd15s + snapshot.shortLiqUsd15s)})"
            )
        }

        val urgentTaker = snapshot.vpin >= 0.72 || (snapshot.longLiqUsd15s + snapshot.shortLiqUsd15s) >= 20_000.0
        val orderPref = if (urgentTaker || !openPosition.preferMaker) {
            OrderExecutionPreference.TAKER_MARKET
        } else {
            OrderExecutionPreference.MAKER_POST_ONLY
        }

        val reason = when {
            liqShortSqueeze || liqLongSqueeze -> "Каскад ликвидаций ($${fmt(snapshot.shortLiqUsd15s + snapshot.longLiqUsd15s)})"
            wallBreakUp || wallBreakDown -> "Пробой снятой стены (CTR ×${fmt(snapshot.churnInflationRatio)})"
            else -> "Токсичный сквиз VPIN ${fmt(snapshot.vpin)}"
        }
        return StrategyDecision.Enter(
            sideIsBuy = buySqueeze,
            orderPreference = orderPref,
            note = "Squeeze ${if (buySqueeze) "LONG" else "SHORT"} · $reason"
        )
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
}
