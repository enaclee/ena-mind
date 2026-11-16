package com.enamind.ai.orderflow

/**
 * Полный набор строго типизированных моделей модуля ENA — ORDER FLOW & ABSORPTION INTELLIGENCE.
 * При нехватке, устаревании или рассинхронизации реальных данных система явно выставляет
 * INSUFFICIENT / STALE / DESYNC вместо подстановки декоративных значений.
 */

enum class DataFreshness(val labelRu: String) {
    LIVE("В реальном времени"),
    STALE("Данные устарели"),
    DESYNC("Рассинхронизация стакана"),
    INSUFFICIENT("Недостаточно данных")
}

enum class MarketDominance(val labelRu: String) {
    BUYERS_DOMINATE("Покупатели доминируют"),
    SELLERS_DOMINATE("Продавцы доминируют"),
    CONTESTED("Борьба сторон"),
    POSSIBLE_ABSORPTION("Обнаружено возможное поглощение"),
    CONFIRMED_REJECTION("Подтверждён отказ от пробоя"),
    CONFIRMED_BREAKOUT("Подтверждён пробой"),
    INSUFFICIENT_DATA("Недостаточно реальных данных")
}

enum class ReliabilityGrade(val labelRu: String) {
    INSUFFICIENT("Недостаточно данных"),
    WEAK("Слабые признаки"),
    MODERATE("Умеренные признаки"),
    STRONG("Сильные признаки"),
    CONFIRMED("Подтверждённое рыночное событие")
}

enum class LevelAttackPhase(val labelRu: String) {
    NONE("Нет активной атаки на уровень"),
    ATTACK_START("Начало атаки"),
    ACTIVE_PRESSURE("Активное давление на уровень"),
    POSSIBLE_ABSORPTION("Возможное поглощение"),
    ATTACKER_EXHAUSTION("Ослабление атакующей стороны"),
    CONFIRMED_REJECTION("Подтверждённый отказ от пробоя"),
    CONFIRMED_BREAKOUT("Успешный пробой уровня")
}

enum class AbsorptionSide(val labelRu: String) {
    NONE("Поглощение не выявлено"),
    BUYER_ABSORPTION("Поглощение покупателей (продавцы удерживают сопротивление)"),
    SELLER_ABSORPTION("Поглощение продавцов (покупатели удерживают поддержку)")
}

enum class GlowColorState { GREEN, RED, NEUTRAL }

data class FootprintLevel(
    val priceLevel: Double,
    val bidVolume: Double,
    val askVolume: Double,
    val delta: Double,
    val deltaPct: Double,
    val totalVolume: Double,
    val tradeCount: Int,
    val imbalanceType: String, // "ASK_IMBALANCE" | "BID_IMBALANCE" | "NONE"
    val isPoc: Boolean,
    val isClusterAnomaly: Boolean,
    val activityZScore: Double
)

data class FootprintSummary(
    val levels: List<FootprintLevel> = emptyList(),
    val priceStep: Double = 0.0,
    val totalAskVolume: Double = 0.0,
    val totalBidVolume: Double = 0.0,
    val netDelta: Double = 0.0,
    val deltaPct: Double = 0.0,
    val cvd: Double = 0.0,
    val cvdSlope: Double = 0.0,
    val pocPrice: Double? = null,
    val stackedAskCount: Int = 0,
    val stackedBidCount: Int = 0,
    val clusterLevels: List<Double> = emptyList(),
    val anomaliesCount: Int = 0,
    val tradesAnalyzed: Int = 0,
    val sufficientData: Boolean = false,
    val note: String = "Ожидание реальных сделок…"
)

data class RestingOrderZone(
    val price: Double,
    val qty: Double,
    val side: String, // "BID" | "ASK"
    val persistenceSec: Double
)

data class DomSnapshotMetrics(
    val ofi: Double = 0.0,
    val normalizedOfi: Double = 0.0, // -1.0 .. +1.0 (только при реальных обновлениях)
    val bidTotal: Double = 0.0,
    val askTotal: Double = 0.0,
    val bookImbalancePct: Double = 0.0,
    val addRatePerSec: Double = 0.0,
    val cancelRatePerSec: Double = 0.0,
    val executedQty: Double = 0.0,
    val cancelledQty: Double = 0.0,
    val replenishmentRate: Double = 0.0,
    val replenishmentEvents: Int = 0,
    val levelPersistenceRatio: Double = 0.0,
    val largeBids: List<RestingOrderZone> = emptyList(),
    val largeAsks: List<RestingOrderZone> = emptyList(),
    val liquidityPullDetected: Boolean = false,
    val liquidityPullNote: String = "",
    val migrationNote: String = "",
    val synchronized: Boolean = false,
    val lagMs: Long = -1L,
    val deltaSequenceCount: Int = 0,
    val sufficientData: Boolean = false,
    val note: String = "Ожидание синхронизированного стакана…"
)

data class AbsorptionMetrics(
    val side: AbsorptionSide = AbsorptionSide.NONE,
    val detected: Boolean = false,
    val confirmed: Boolean = false,
    val reliability: ReliabilityGrade = ReliabilityGrade.INSUFFICIENT,
    val aggressiveVolume: Double = 0.0,
    val priceProgressEfficiency: Double = 0.0,
    val absorptionRatio: Double = 0.0,
    val liquidityReplenishmentRate: Double = 0.0,
    val levelPersistence: Double = 0.0,
    val attackExhaustion: Double = 0.0,
    val repeatedAttacks: Int = 0,
    val holdDurationSec: Double = 0.0,
    val targetLevelPrice: Double? = null,
    val supportingFactors: List<String> = emptyList(),
    val conflictingFactors: List<String> = emptyList(),
    val note: String = "Недостаточно совпавших признаков поглощения"
)

data class LevelAttackState(
    val levelPrice: Double? = null,
    val isResistance: Boolean = true,
    val distancePct: Double? = null,
    val touchCount: Int = 0,
    val phase: LevelAttackPhase = LevelAttackPhase.NONE,
    val attackDelta: Double = 0.0,
    val attackProgressPct: Double = 0.0,
    val liquidityChangePct: Double = 0.0,
    val replenishmentDetected: Boolean = false,
    val acceptedBeyondLevel: Boolean = false,
    val postAttackReactionPct: Double? = null,
    val repeatedAttackZones: List<Double> = emptyList(),
    val failedBreakoutZones: List<Double> = emptyList()
)

data class ScenarioCase(
    val title: String,
    val proFactors: List<String>,
    val contraFactors: List<String>,
    val needsConfirmation: List<String>,
    val invalidationConditions: List<String>
)

data class ConfidenceAssessment(
    val grade: ReliabilityGrade = ReliabilityGrade.INSUFFICIENT,
    val alignedFactorsCount: Int = 0,
    val calibratedAbsorptionProbPct: Double? = null,
    val calibratedReversalProbPct: Double? = null,
    val calibratedBreakoutProbPct: Double? = null,
    val historicalSampleSize: Int = 0,
    val dataQualityNote: String = "Недостаточно исторических событий для вероятностной калибровки",
    val historicalInsight: String = ""
)

data class GlowSignal(
    val colorState: GlowColorState = GlowColorState.NEUTRAL,
    val strength: Float = 0f,     // 0.0 .. 1.0 (яркость)
    val speedFactor: Float = 0.6f,// скорость ping-pong волны
    val independentFactors: Int = 0,
    val reason: String = "Ожидание подтверждения потока"
)

data class ChartOverlayData(
    val supportLevels: List<Double> = emptyList(),
    val resistanceLevels: List<Double> = emptyList(),
    val highLiquidityZones: List<Pair<Double, String>> = emptyList(), // (price, "BID"|"ASK")
    val absorptionZones: List<Double> = emptyList(),
    val attackMarkerPrices: List<Double> = emptyList(),
    val failedBreakoutPrices: List<Double> = emptyList(),
    val confirmedEventMarkers: List<Pair<Long, String>> = emptyList() // (tsMs, "REJECTION"|"BREAKOUT")
)

data class OrderFlowCouncilPayload(
    val detectedEvent: String,
    val levelPrice: Double?,
    val signalStrength: String,
    val supportingEvidence: List<String>,
    val contradictingEvidence: List<String>,
    val dataQuality: String,
    val historicalReversalRatePct: Double?,
    val historicalBreakoutRatePct: Double?,
    val historicalSamples: Int,
    val bullishBiasWeight: Double, // жёстко ограничено [-0.25 .. +0.25] в Neural Council
    val sufficientData: Boolean
)

data class OrderFlowReport(
    val symbol: String = "BTCUSDT",
    val exchangeSource: String = "Ожидание",
    val interval: String = "60",
    val price: Double? = null,
    val computedAtMs: Long = 0L,
    val freshness: DataFreshness = DataFreshness.INSUFFICIENT,
    val freshnessWarning: String? = "Ожидание подключения биржевых потоков…",
    val dominance: MarketDominance = MarketDominance.INSUFFICIENT_DATA,
    val footprint: FootprintSummary = FootprintSummary(),
    val dom: DomSnapshotMetrics = DomSnapshotMetrics(),
    val absorption: AbsorptionMetrics = AbsorptionMetrics(),
    val levelAttack: LevelAttackState = LevelAttackState(),
    val scenarioA: ScenarioCase? = null,
    val scenarioB: ScenarioCase? = null,
    val explanationText: String = "Данные Order Flow ещё собираются. Как только поступят сделки и синхронизированный стакан, ENA разберёт борьбу сторон.",
    val confidence: ConfidenceAssessment = ConfidenceAssessment(),
    val glow: GlowSignal = GlowSignal(),
    val overlay: ChartOverlayData = ChartOverlayData(),
    val councilPayload: OrderFlowCouncilPayload? = null
)
