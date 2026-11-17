package com.enamind.ai.bot.strategy

/**
 * Единый реестр стратегий StrategyBridge.
 * Позволяет переключать активную модель на лету без сброса WebSocket, стакана и истории.
 */
object StrategyRegistry {
    private val models: Map<StrategyModelId, StrategyBridge> = mapOf(
        StrategyModelId.FLOW_PULSE_CONTINUATION to FlowPulseContinuationStrategy(),
        StrategyModelId.SQUEEZE_LIQUIDATION_HUNTER to SqueezeLiquidationHunterStrategy(),
        StrategyModelId.WALL_ABSORPTION_SNIPER to WallAbsorptionSniperStrategy()
    )

    fun resolve(id: StrategyModelId): StrategyBridge =
        models[id] ?: models.getValue(StrategyModelId.FLOW_PULSE_CONTINUATION)
}
