package com.enamind.ai.bot.strategy

import java.util.Locale
import kotlin.math.abs

/**
 * Модель 1 (по умолчанию): FLOW_PULSE_CONTINUATION.
 * Быстрый высокочастотный скальпер: опирается на поток (Delta >= 18% или MLOFI >= 0.18),
 * мягкий фильтр дрейфа EMA(9)/EMA(21) и Spot/Perp CVD, не блокируется мелким флетом,
 * и полностью поддерживает каскадное продолжение от незакрытой сделки (Anti-Knife Gate).
 */
class FlowPulseContinuationStrategy : StrategyBridge {
    override val id: StrategyModelId = StrategyModelId.FLOW_PULSE_CONTINUATION

    override fun evaluate(
        snapshot: MarketContextSnapshot,
        openPosition: OpenPositionContext
    ): StrategyDecision {
        if (!snapshot.dataFreshnessLive) {
            return StrategyDecision.Hold("Ожидание LIVE потока")
        }
        val pref = if (openPosition.preferMaker) {
            OrderExecutionPreference.MAKER_POST_ONLY
        } else {
            OrderExecutionPreference.TAKER_MARKET
        }

        // Если позиция уже открыта — работаем от неё (Слой 5: Tranche Continuation + Peel)
        if (openPosition.hasOpenPosition && openPosition.sideIsBuy != null) {
            val isBuy = openPosition.sideIsBuy
            if (openPosition.peelPartialEnabled &&
                !openPosition.peelDone &&
                openPosition.tranchesCount >= 2 &&
                openPosition.netPnlPct >= openPosition.minNetProfitPct + 0.10
            ) {
                return StrategyDecision.PeelPartial(
                    fraction = 0.50,
                    note = "Снятие 50% скальп-слоя (${openPosition.tranchesCount} транша, +${fmt(openPosition.netPnlPct)}%)"
                )
            }

            if (openPosition.tranchesCount < openPosition.maxTranches) {
                if (openPosition.drawdownFromLastTranchePct < openPosition.effectiveStepPct) {
                    return StrategyDecision.Hold(
                        "Удержание ${if (isBuy) "LONG" else "SHORT"} ${openPosition.tranchesCount}/${openPosition.maxTranches} " +
                            "(просадка ${fmt(openPosition.drawdownFromLastTranchePct)}% < шаг ${fmt(openPosition.effectiveStepPct)}%)"
                    )
                }
                if (snapshot.conflictDetected) {
                    return StrategyDecision.Hold("Пауза добора: конфликт потока и свечи")
                }
                // Anti-Knife: запрет добора в отвесный встречный водопад ликвидаций
                val adverseWaterfall = (isBuy && snapshot.longLiqUsd15s > 12_000.0 && snapshot.deltaPct < -25.0) ||
                    (!isBuy && snapshot.shortLiqUsd15s > 12_000.0 && snapshot.deltaPct > 25.0)
                if (adverseWaterfall) {
                    return StrategyDecision.Hold("Anti-Knife защита: встречный каскад ликвидаций")
                }

                val flowMatchesPos = if (isBuy) {
                    snapshot.deltaPct >= 14.0 || snapshot.normalizedOfi >= 0.16 ||
                        snapshot.absorptionSideName == "BUYER_ABSORPTION"
                } else {
                    snapshot.deltaPct <= -14.0 || snapshot.normalizedOfi <= -0.16 ||
                        snapshot.absorptionSideName == "SELLER_ABSORPTION"
                }
                if (flowMatchesPos) {
                    return StrategyDecision.ScaleIn(
                        sideIsBuy = isBuy,
                        orderPreference = pref,
                        note = "Добор транша #${openPosition.tranchesCount + 1}: просадка ${fmt(openPosition.drawdownFromLastTranchePct)}% + импульс Δ ${fmt(snapshot.deltaPct)}%"
                    )
                }
            }
            return StrategyDecision.Hold(
                "Сопровождение ${if (isBuy) "LONG" else "SHORT"} (${openPosition.tranchesCount}/${openPosition.maxTranches})"
            )
        }

        // Поиск нового входа
        if (snapshot.conflictDetected) {
            return StrategyDecision.Hold("Конфликт потока (DOM/Лента)")
        }
        if (openPosition.requireRadarNotFlat &&
            snapshot.quantAdx != null && snapshot.quantEr != null &&
            snapshot.quantAdx < 14.0 && snapshot.quantEr < 0.15
        ) {
            return StrategyDecision.Hold("Глубокий штиль (ADX ${fmt(snapshot.quantAdx)})")
        }

        val longImpulse = snapshot.deltaPct >= 18.0 ||
            (snapshot.normalizedOfi >= 0.18 && snapshot.deltaPct >= 6.0) ||
            (snapshot.glowColorName == "GREEN" && snapshot.glowStrength >= 0.40f)
        val shortImpulse = snapshot.deltaPct <= -18.0 ||
            (snapshot.normalizedOfi <= -0.18 && snapshot.deltaPct <= -6.0) ||
            (snapshot.glowColorName == "RED" && snapshot.glowStrength >= 0.40f)

        val candidateBuy = when {
            longImpulse && !shortImpulse -> true
            shortImpulse && !longImpulse -> false
            else -> return StrategyDecision.Hold("Ожидание импульса (Δ ${fmt(snapshot.deltaPct)}%, OFI ${fmt(snapshot.normalizedOfi)})")
        }

        // Мягкая проверка поглощения против направления
        if (!openPosition.allowCounterAbsorption && snapshot.absorptionDetected) {
            val blocked = (candidateBuy && snapshot.absorptionSideName == "SELLER_ABSORPTION") ||
                (!candidateBuy && snapshot.absorptionSideName == "BUYER_ABSORPTION")
            if (blocked) {
                return StrategyDecision.Hold("Блок: поглощение против ${if (candidateBuy) "LONG" else "SHORT"}")
            }
        }

        // Мягкий компас дрейфа EMA9/21: блокируем только сильный контр-обрыв (>0.30%), но мелкий флет пропускаем
        val strongAgainstEma = (candidateBuy && snapshot.emaDriftPct < -0.30 && snapshot.spotPerpCvdDivergence < -0.35) ||
            (!candidateBuy && snapshot.emaDriftPct > 0.30 && snapshot.spotPerpCvdDivergence > 0.35)
        if (strongAgainstEma) {
            return StrategyDecision.Hold("Сильный дрейф EMA9/21 (${fmt(snapshot.emaDriftPct)}%) против входа")
        }

        return StrategyDecision.Enter(
            sideIsBuy = candidateBuy,
            orderPreference = pref,
            note = "Pulse ${if (candidateBuy) "LONG" else "SHORT"} · Δ ${fmt(snapshot.deltaPct)}% · OFI ${fmt(snapshot.normalizedOfi)}"
        )
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
}
