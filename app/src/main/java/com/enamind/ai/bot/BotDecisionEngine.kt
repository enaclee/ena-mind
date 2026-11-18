package com.enamind.ai.bot

import com.enamind.ai.bot.strategy.MarketContextSnapshot
import com.enamind.ai.bot.strategy.OpenPositionContext
import com.enamind.ai.bot.strategy.StrategyDecision
import com.enamind.ai.core.ActiveInstrument
import com.enamind.ai.core.EnacoreSnapshot
import kotlin.math.abs

/** Manual thresholds or a fail-closed ENΛCORE bridge; no preset registry. */
object BotDecisionEngine {
    fun evaluate(s: MarketContextSnapshot, p: OpenPositionContext, cfg: BotConfig,
                 core: EnacoreSnapshot = EnacoreSnapshot(), instrument: ActiveInstrument = ActiveInstrument(),
                 now: Long = System.currentTimeMillis()): StrategyDecision {
        if (!s.dataFreshnessLive || !s.spreadPct.isFinite() || s.spreadPct > cfg.maxSpreadPct || s.spreadPct < 0)
            return StrategyDecision.Hold("Нет свежего согласованного потока или слишком широкий спред")
        val buy: Boolean
        val note: String
        if (cfg.enacoreBridge) {
            if (!core.isUsable(instrument, now) || !core.verdict.startsWith("CONFIRM "))
                return StrategyDecision.Hold("ENΛCORE: ${core.reason}")
            buy = core.verdict == "CONFIRM LONG"
            note = "ENΛCORE · Net ${core.targetPct}% · ${core.horizonMin}м · AWO"
        } else {
            if (s.conflictDetected || abs(s.deltaPct) < cfg.entryDeltaPct || abs(s.normalizedOfi) < cfg.entryOfi)
                return StrategyDecision.Hold("Ожидание ручных порогов Delta / OFI")
            buy = s.deltaPct > 0
            if ((s.normalizedOfi > 0) != buy) return StrategyDecision.Hold("Delta и OFI расходятся")
            if (cfg.requireRadarNotFlat && (s.quantAdx == null || s.quantEr == null || (s.quantAdx < 18 && s.quantEr < .20)))
                return StrategyDecision.Hold("Радар: флет или нет подтверждения")
            if (cfg.requireGlowConsensus && (s.glowStrength < .4f || s.glowColorName != if (buy) "GREEN" else "RED"))
                return StrategyDecision.Hold("Ожидание подтверждения свечения")
            note = "Ручной режим · Delta / OFI / Радар"
        }
        if (!cfg.allowCounterAbsorption && s.absorptionConfirmed &&
            ((buy && s.absorptionSideName == "SELLER_ABSORPTION") || (!buy && s.absorptionSideName == "BUYER_ABSORPTION")))
            return StrategyDecision.Hold("Встречное поглощение")
        if (cfg.instrumentCategory == "spot" && !buy) return StrategyDecision.Hold("Спот: только Long без займа")
        if (!p.hasOpenPosition) return StrategyDecision.Enter(buy, cfg.executionPreference, note)
        if (cfg.enacoreBridge && p.sideIsBuy != buy) return StrategyDecision.ExitAll("ENΛCORE подтвердил смену направления")
        if (p.peelPartialEnabled && !p.peelDone && p.tranchesCount >= 2 && p.netPnlPct >= p.minNetProfitPct)
            return StrategyDecision.PeelPartial(.5, "Ручная фиксация половины прибыльной позиции")
        val adverseLiquidation = if (buy) s.longLiqUsd15s > s.shortLiqUsd15s * 3 && s.longLiqUsd15s > 10000
            else s.shortLiqUsd15s > s.longLiqUsd15s * 3 && s.shortLiqUsd15s > 10000
        if (p.sideIsBuy == buy && p.tranchesCount < p.maxTranches &&
            p.drawdownFromLastTranchePct >= p.effectiveStepPct && !adverseLiquidation)
            return StrategyDecision.ScaleIn(buy, cfg.executionPreference, note)
        return StrategyDecision.Hold("Сопровождение по заданным TP / SL / тралу")
    }
}
