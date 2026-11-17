package com.enamind.ai.bot.strategy

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Модель 3: WALL_ABSORPTION_SNIPER.
 * Контр-трендовый снайпер от истинных стен S/R:
 *  - Жёсткий Proximity Gate (вблизи уровня поддержки/сопротивления);
 *  - Положительное пополнение пассивной стороны и `0 <= progressEfficiency <= 0.28`;
 *  - Исключает торговлю против сильного встречного каскада ликвидаций;
 *  - Вход строго PostOnly Maker (экономия комиссии).
 */
class WallAbsorptionSniperStrategy : StrategyBridge {
    override val id: StrategyModelId = StrategyModelId.WALL_ABSORPTION_SNIPER

    override fun evaluate(
        snapshot: MarketContextSnapshot,
        openPosition: OpenPositionContext
    ): StrategyDecision {
        if (!snapshot.dataFreshnessLive) {
            return StrategyDecision.Hold("Ожидание LIVE потока (Sniper)")
        }

        val maxDistPct = max(0.15, snapshot.atr1mPct * 0.45)

        if (openPosition.hasOpenPosition && openPosition.sideIsBuy != null) {
            val isBuy = openPosition.sideIsBuy
            if (openPosition.peelPartialEnabled &&
                !openPosition.peelDone &&
                openPosition.tranchesCount >= 2 &&
                openPosition.netPnlPct >= openPosition.minNetProfitPct + 0.08
            ) {
                return StrategyDecision.PeelPartial(
                    0.50,
                    "Sniper-фиксация 50% от уровня (+${fmt(openPosition.netPnlPct)}%)"
                )
            }
            if (openPosition.tranchesCount < openPosition.maxTranches &&
                openPosition.drawdownFromLastTranchePct >= openPosition.effectiveStepPct
            ) {
                val srLevel = if (isBuy) snapshot.nearestSupport else snapshot.nearestResistance
                val nearNextSr = srLevel != null && snapshot.price > 0.0 &&
                    (abs(snapshot.price - srLevel) / snapshot.price * 100.0) <= max(0.22, snapshot.atr1mPct * 0.6)
                val wallConfirm = if (isBuy) {
                    snapshot.absorptionSideName == "BUYER_ABSORPTION" ||
                        (snapshot.replenishmentRate >= 0.25 && snapshot.normalizedOfi >= 0.10)
                } else {
                    snapshot.absorptionSideName == "SELLER_ABSORPTION" ||
                        (snapshot.replenishmentRate >= 0.25 && snapshot.normalizedOfi <= -0.10)
                }
                val noWaterfall = (isBuy && snapshot.longLiqUsd15s < 10_000.0) ||
                    (!isBuy && snapshot.shortLiqUsd15s < 10_000.0)
                if ((nearNextSr || snapshot.absorptionDetected) && wallConfirm && noWaterfall) {
                    return StrategyDecision.ScaleIn(
                        sideIsBuy = isBuy,
                        orderPreference = OrderExecutionPreference.MAKER_POST_ONLY,
                        note = "Добор от следующей S/R стены #${openPosition.tranchesCount + 1}"
                    )
                }
            }
            return StrategyDecision.Hold("Sniper удержание у стены (${openPosition.tranchesCount}/${openPosition.maxTranches})")
        }

        if (snapshot.passiveTrapDetected) {
            return StrategyDecision.Hold("PASSIVE_TRAP — пропуск входа")
        }
        if (snapshot.liquidityPullDetected) {
            return StrategyDecision.Hold("Снятие ликвидности у стены")
        }
        val inZone = snapshot.levelDistancePct != null && snapshot.levelDistancePct <= maxDistPct
        if (!inZone && !snapshot.absorptionDetected) {
            return StrategyDecision.Hold("Вне Proximity Gate S/R (порог ${fmt(maxDistPct)}%)")
        }
        if (snapshot.progressEfficiency !in -0.15..0.28) {
            return StrategyDecision.Hold("progressEff ${fmt(snapshot.progressEfficiency)} вне окна [-0.15..0.28]")
        }
        if (snapshot.replenishmentRate < 0.20) {
            return StrategyDecision.Hold("Слабое пополнение стены (${fmt(snapshot.replenishmentRate)})")
        }

        val sellerWallBounce = snapshot.absorptionSideName == "SELLER_ABSORPTION" &&
            snapshot.shortLiqUsd15s < 8_000.0
        val buyerWallBounce = snapshot.absorptionSideName == "BUYER_ABSORPTION" &&
            snapshot.longLiqUsd15s < 8_000.0

        val fallbackShortWall = !sellerWallBounce && !buyerWallBounce && inZone && snapshot.levelIsResistance &&
            snapshot.deltaPct >= 14.0 && snapshot.levelPersistenceRatio >= 0.50 && snapshot.shortLiqUsd15s < 8_000.0
        val fallbackLongWall = !sellerWallBounce && !buyerWallBounce && inZone && !snapshot.levelIsResistance &&
            snapshot.deltaPct <= -14.0 && snapshot.levelPersistenceRatio >= 0.50 && snapshot.longLiqUsd15s < 8_000.0

        return when {
            sellerWallBounce || fallbackShortWall -> StrategyDecision.Enter(
                sideIsBuy = false,
                orderPreference = OrderExecutionPreference.MAKER_POST_ONLY,
                note = "Sniper SHORT от сопротивления (пополн. ${fmt(snapshot.replenishmentRate)})"
            )
            buyerWallBounce || fallbackLongWall -> StrategyDecision.Enter(
                sideIsBuy = true,
                orderPreference = OrderExecutionPreference.MAKER_POST_ONLY,
                note = "Sniper LONG от поддержки (пополн. ${fmt(snapshot.replenishmentRate)})"
            )
            else -> StrategyDecision.Hold("Ожидание чёткой S/R стены")
        }
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
}
