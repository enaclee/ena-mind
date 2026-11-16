package com.enamind.ai.orderflow

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign

/**
 * Построение конкурирующих сценариев A (возможный разворот) и B (возможный пробой),
 * понятного текстового объяснения ENA на русском языке, калиброванной оценки достоверности
 * и аналитического сигнала Dynamic Market Glow.
 *
 * Исправления анти-шума и консенсуса:
 *  - Корректная полярность AbsorptionSide:
 *      * SELLER_ABSORPTION = продавцы удерживают сопротивление (поглощают покупателей);
 *      * BUYER_ABSORPTION = покупатели удерживают поддержку (поглощают продавцов).
 *  - Согласование с ядром Радара (quantAdx / quantEfficiencyRatio) и наклоном свечи:
 *    при противоречии свечи, Радара и потока (или сильном расхождении Delta vs OFI)
 *    выставляется MarketDominance.CONFLICT и Glow переходит в нейтральный режим.
 *  - Предупреждение о раздувании котировок (CTR churnInflationRatio >= 10x).
 */
object ScenarioAnalyzer {

    data class SynthesisResult(
        val dominance: MarketDominance,
        val scenarioA: ScenarioCase?,
        val scenarioB: ScenarioCase?,
        val explanationText: String,
        val confidence: ConfidenceAssessment,
        val glow: GlowSignal,
        val councilPayload: OrderFlowCouncilPayload
    )

    fun synthesize(
        symbol: String,
        price: Double?,
        freshness: DataFreshness,
        footprint: FootprintSummary,
        dom: DomSnapshotMetrics,
        absorption: AbsorptionMetrics,
        attack: LevelAttackState,
        vaultStats: OrderFlowEventVault.EmpiricalStats,
        quantAdx: Double? = null,
        quantEfficiencyRatio: Double? = null,
        candleChangePct: Double? = null
    ): SynthesisResult {
        if (freshness == DataFreshness.INSUFFICIENT || freshness == DataFreshness.DESYNC || !footprint.sufficientData || !dom.sufficientData) {
            val reason = when (freshness) {
                DataFreshness.DESYNC -> "Обнаружен сбой/рассинхронизация последовательности стакана — анализ поставлен на паузу до повторного snapshot."
                else -> "Недостаточно свежих сделок и обновлений стакана по $symbol. Ожидается накопление потока без подстановки искусственных данных."
            }
            val conf = ConfidenceAssessment(
                grade = ReliabilityGrade.INSUFFICIENT,
                alignedFactorsCount = 0,
                calibratedAbsorptionProbPct = null,
                calibratedReversalProbPct = null,
                calibratedBreakoutProbPct = null,
                historicalSampleSize = vaultStats.verifiedEvents,
                dataQualityNote = reason
            )
            return SynthesisResult(
                dominance = MarketDominance.INSUFFICIENT_DATA,
                scenarioA = null,
                scenarioB = null,
                explanationText = reason,
                confidence = conf,
                glow = GlowSignal(
                    colorState = GlowColorState.NEUTRAL,
                    strength = 0f,
                    speedFactor = 0.45f,
                    independentFactors = 0,
                    reason = reason,
                    conflictDetected = false
                ),
                councilPayload = OrderFlowCouncilPayload(
                    detectedEvent = "INSUFFICIENT_DATA",
                    levelPrice = attack.levelPrice,
                    signalStrength = ReliabilityGrade.INSUFFICIENT.labelRu,
                    supportingEvidence = emptyList(),
                    contradictingEvidence = listOf(reason),
                    dataQuality = freshness.labelRu,
                    historicalReversalRatePct = vaultStats.reversalRatePct,
                    historicalBreakoutRatePct = vaultStats.breakoutRatePct,
                    historicalSamples = vaultStats.verifiedEvents,
                    bullishBiasWeight = 0.0,
                    sufficientData = false
                )
            )
        }

        val conflictInfo = detectConflict(
            fp = footprint,
            dom = dom,
            quantAdx = quantAdx,
            quantEfficiencyRatio = quantEfficiencyRatio,
            candleChangePct = candleChangePct
        )

        // 1. Определение доминирования с учётом конфликта свечи/Радара/потока
        val dominance = when {
            attack.phase == LevelAttackPhase.CONFIRMED_BREAKOUT && !conflictInfo.hardConflict -> MarketDominance.CONFIRMED_BREAKOUT
            attack.phase == LevelAttackPhase.CONFIRMED_REJECTION -> MarketDominance.CONFIRMED_REJECTION
            absorption.detected -> MarketDominance.POSSIBLE_ABSORPTION
            conflictInfo.hardConflict -> MarketDominance.CONFLICT
            footprint.deltaPct >= 14.0 && dom.normalizedOfi >= 0.15 -> MarketDominance.BUYERS_DOMINATE
            footprint.deltaPct <= -14.0 && dom.normalizedOfi <= -0.15 -> MarketDominance.SELLERS_DOMINATE
            else -> MarketDominance.CONTESTED
        }

        // 2. Построение конкурирующих сценариев A (разворот) и B (пробой)
        val levelTxt = attack.levelPrice?.let { fmtPx(it) } ?: footprint.pocPrice?.let { fmtPx(it) } ?: "текущий узел"
        val isRes = attack.isResistance

        val scenarioA = buildReversalScenario(isRes, levelTxt, footprint, dom, absorption, attack)
        val scenarioB = buildBreakoutScenario(isRes, levelTxt, footprint, dom, absorption, attack)

        // 3. Оценка достоверности
        val alignedCount = countAlignedMetrics(footprint, dom, absorption, attack)
        val grade = when {
            conflictInfo.hardConflict -> ReliabilityGrade.WEAK
            attack.phase == LevelAttackPhase.CONFIRMED_BREAKOUT ||
                attack.phase == LevelAttackPhase.CONFIRMED_REJECTION ||
                absorption.confirmed -> ReliabilityGrade.CONFIRMED
            alignedCount >= 4 -> ReliabilityGrade.STRONG
            alignedCount >= 2 -> ReliabilityGrade.MODERATE
            else -> ReliabilityGrade.WEAK
        }

        val hasCalibratedHistory = vaultStats.verifiedEvents >= 8
        val absProb = if (hasCalibratedHistory) vaultStats.absorptionConfirmationRatePct else null
        val revProb = if (hasCalibratedHistory) vaultStats.reversalRatePct else null
        val brkProb = if (hasCalibratedHistory) vaultStats.breakoutRatePct else null

        val histInsight = if (hasCalibratedHistory) {
            "По истории (${vaultStats.verifiedEvents} проверенных событий): " +
                "развороты ${vaultStats.reversalRatePct?.let { "%.0f%%".format(Locale.US, it) } ?: "—"}, " +
                "пробои ${vaultStats.breakoutRatePct?.let { "%.0f%%".format(Locale.US, it) } ?: "—"}. " +
                vaultStats.topFeatureSummary
        } else {
            "Вероятности откалибруются автоматически после накопления ≥8 проверенных событий (сейчас ${vaultStats.verifiedEvents})."
        }

        val noiseNote = if (dom.churnInflationRatio >= 10.0) {
            " · CTR ${fmtD(dom.churnInflationRatio)}x"
        } else ""
        val confidence = ConfidenceAssessment(
            grade = grade,
            alignedFactorsCount = alignedCount,
            calibratedAbsorptionProbPct = absProb,
            calibratedReversalProbPct = revProb,
            calibratedBreakoutProbPct = brkProb,
            historicalSampleSize = vaultStats.verifiedEvents,
            dataQualityNote = "Согласовано признаков: $alignedCount · задержка ${if (dom.lagMs >= 0) "${dom.lagMs} мс" else "лок."}$noiseNote",
            historicalInsight = histInsight
        )

        // 4. Человеческое объяснение ENA
        val explanation = buildHumanExplanation(
            symbol = symbol,
            price = price,
            levelTxt = levelTxt,
            isResistance = isRes,
            dominance = dominance,
            fp = footprint,
            dom = dom,
            absM = absorption,
            atk = attack,
            conflictNote = conflictInfo.reason
        )

        // 5. Dynamic Market Glow (многофакторный консенсус без фликеров при конфликте)
        val glow = computeGlowSignal(
            freshness = freshness,
            fp = footprint,
            dom = dom,
            absM = absorption,
            atk = attack,
            quantAdx = quantAdx,
            quantEfficiencyRatio = quantEfficiencyRatio,
            candleChangePct = candleChangePct
        )

        // 6. Пакет для Neural Council (жёстко [-0.25 .. +0.25])
        val rawBiasWeight = if (conflictInfo.hardConflict) {
            0.0
        } else {
            computeCouncilWeight(dominance, footprint, dom, absorption, attack)
        }
        val payload = OrderFlowCouncilPayload(
            detectedEvent = "${dominance.name} / ${attack.phase.name}",
            levelPrice = attack.levelPrice,
            signalStrength = grade.labelRu,
            supportingEvidence = absorption.supportingFactors.ifEmpty {
                listOf("Delta ${fmtPct(footprint.deltaPct)}", "OFI ${fmtD(dom.normalizedOfi)}", "Исп. ${fmtCompact(dom.executedQty)}")
            },
            contradictingEvidence = (absorption.conflictingFactors + listOfNotNull(conflictInfo.reason)).distinct(),
            dataQuality = "${freshness.labelRu} (${footprint.tradesAnalyzed} сделок / ${dom.deltaSequenceCount} дельт)",
            historicalReversalRatePct = vaultStats.reversalRatePct,
            historicalBreakoutRatePct = vaultStats.breakoutRatePct,
            historicalSamples = vaultStats.verifiedEvents,
            bullishBiasWeight = rawBiasWeight.coerceIn(-0.25, 0.25),
            sufficientData = true
        )

        return SynthesisResult(
            dominance = dominance,
            scenarioA = scenarioA,
            scenarioB = scenarioB,
            explanationText = explanation,
            confidence = confidence,
            glow = glow,
            councilPayload = payload
        )
    }

    private data class ConflictAssessment(val hardConflict: Boolean, val reason: String?)

    private fun detectConflict(
        fp: FootprintSummary,
        dom: DomSnapshotMetrics,
        quantAdx: Double?,
        quantEfficiencyRatio: Double?,
        candleChangePct: Double?
    ): ConflictAssessment {
        // 1. Delta против нормированного MLOFI стакана
        val deltaVsOfi = abs(fp.deltaPct) >= 10.0 &&
            abs(dom.normalizedOfi) >= 0.16 &&
            sign(fp.deltaPct) != sign(dom.normalizedOfi)
        if (deltaVsOfi) {
            return ConflictAssessment(
                hardConflict = true,
                reason = "Расхождение ленты и стакана: Delta ${fmtPct(fp.deltaPct)} против OFI ${fmtD(dom.normalizedOfi)}"
            )
        }

        // 2. Свеча двигается против CVD и агрессивной дельты
        if (candleChangePct != null && candleChangePct.isFinite() && abs(candleChangePct) >= 0.10 && abs(fp.deltaPct) >= 12.0) {
            val candleVsFlow = (candleChangePct > 0.10 && fp.deltaPct <= -12.0 && fp.cvdSlope <= 0.0) ||
                (candleChangePct < -0.10 && fp.deltaPct >= 12.0 && fp.cvdSlope >= 0.0)
            if (candleVsFlow) {
                return ConflictAssessment(
                    hardConflict = true,
                    reason = "Конфликт свечи (${fmtPct(candleChangePct)}) и кумулятивного потока (Delta ${fmtPct(fp.deltaPct)})"
                )
            }
        }

        // 3. Ядро Радара фиксирует плоский флет (ADX < 18 && ER < 0.20)
        val radarFlat = quantAdx != null && quantEfficiencyRatio != null &&
            quantAdx.isFinite() && quantEfficiencyRatio.isFinite() &&
            quantAdx < 18.0 && quantEfficiencyRatio < 0.20
        if (radarFlat && abs(fp.deltaPct) >= 12.0 && abs(dom.normalizedOfi) < 0.25) {
            return ConflictAssessment(
                hardConflict = true,
                reason = "Флет по Радару (ADX ${fmtD(quantAdx ?: 0.0)}, ER ${fmtD(quantEfficiencyRatio ?: 0.0)}) — всплеск потока отфильтрован"
            )
        }

        return ConflictAssessment(false, null)
    }

    private fun buildReversalScenario(
        isResistance: Boolean,
        levelTxt: String,
        fp: FootprintSummary,
        dom: DomSnapshotMetrics,
        absM: AbsorptionMetrics,
        atk: LevelAttackState
    ): ScenarioCase {
        val dirTitle = if (isResistance) {
            "Сценарий A — возможный разворот вниз от $levelTxt"
        } else {
            "Сценарий A — возможный разворот вверх от $levelTxt"
        }
        val pro = mutableListOf<String>()
        val contra = mutableListOf<String>()
        val confirm = mutableListOf<String>()
        val invalid = mutableListOf<String>()

        if (isResistance) {
            if (fp.netDelta > 0) pro += "Покупатели активно бьют по рынку (Delta ${fmtPct(fp.deltaPct)}), создавая высокий оборот у уровня"
            if (absM.side == AbsorptionSide.SELLER_ABSORPTION && absM.detected) {
                pro += "Продавцы поглощают покупки на сопротивлении (эффективность хода ${fmtD(absM.priceProgressEfficiency)})"
            }
            if (dom.replenishmentEvents >= 2) pro += "Ликвидность продавцов пополняется после исполнений (${dom.replenishmentEvents}x)"
            if (atk.attackProgressPct <= 0.1) pro += "Цена практически не продвигается вверх, несмотря на рыночный напор"

            if (dom.normalizedOfi > 0.2) contra += "OFI всё ещё смещён в сторону спроса (${fmtD(dom.normalizedOfi)})"
            if (dom.liquidityPullDetected) contra += "Фиксируется снятие части лимитных заявок — стену могут убрать"
            if (fp.stackedAskCount >= 3) contra += "Серия Ask-дисбалансов (${fp.stackedAskCount} уровней подряд) поддерживает покупателей"

            confirm += "Ослабление покупательской активности и переход Delta ниже нуля"
            confirm += "Возврат и удержание цены ниже $levelTxt с импульсом отката"
            invalid += "Закрепление свечи выше $levelTxt на снижающейся встречной ликвидности Ask"
        } else {
            if (fp.netDelta < 0) pro += "Продавцы давят рыночными ордерами (Delta ${fmtPct(fp.deltaPct)})"
            if (absM.side == AbsorptionSide.BUYER_ABSORPTION && absM.detected) {
                pro += "Покупатели поглощают продажи на поддержке (эффективность ${fmtD(absM.priceProgressEfficiency)})"
            }
            if (dom.replenishmentEvents >= 2) pro += "Встречный спрос Bid восстанавливается после исполнений (${dom.replenishmentEvents}x)"
            if (atk.attackProgressPct >= -0.1) pro += "Давление вниз не даёт существенного снижения цены"

            if (dom.normalizedOfi < -0.2) contra += "OFI подтверждает преобладание продавцов (${fmtD(dom.normalizedOfi)})"
            if (dom.liquidityPullDetected) contra += "Замечено снятие крупных заявок Bid"
            if (fp.stackedBidCount >= 3) contra += "Серия Bid-дисбалансов (${fp.stackedBidCount} уровней) усиливает давление вниз"

            confirm += "Затухание агрессивных продаж и рост CVD"
            confirm += "Отскок и удержание выше $levelTxt"
            invalid += "Уверенный пробой и принятие цены ниже $levelTxt"
        }

        if (pro.isEmpty()) pro += "Рядом с уровнем $levelTxt замечена реакция встречной стороны"
        if (contra.isEmpty()) contra += "Разворот ещё не подтверждён структурой цены"

        return ScenarioCase(dirTitle, pro, contra, confirm, invalid)
    }

    private fun buildBreakoutScenario(
        isResistance: Boolean,
        levelTxt: String,
        fp: FootprintSummary,
        dom: DomSnapshotMetrics,
        absM: AbsorptionMetrics,
        atk: LevelAttackState
    ): ScenarioCase {
        val dirTitle = if (isResistance) {
            "Сценарий B — возможный пробой $levelTxt вверх"
        } else {
            "Сценарий B — возможный пробой $levelTxt вниз"
        }
        val pro = mutableListOf<String>()
        val contra = mutableListOf<String>()
        val confirm = mutableListOf<String>()
        val invalid = mutableListOf<String>()

        if (isResistance) {
            if (fp.netDelta > 0 && fp.cvdSlope > 0) pro += "Покупатели сохраняют положительную дельту и растущий CVD"
            if (dom.executedQty > dom.cancelledQty && dom.replenishmentEvents < 2) pro += "Ликвидность продавцов исполняется без полноценного восстановления"
            if (dom.normalizedOfi > 0.15) pro += "OFI (${fmtD(dom.normalizedOfi)}) поддерживает движение вверх"
            if (atk.acceptedBeyondLevel) pro += "Цена начала закрепляться выше уровня"

            if (absM.detected && absM.side == AbsorptionSide.SELLER_ABSORPTION) {
                contra += "Обнаружено поглощение покупателей продавцами (риск ложного пробоя)"
            }
            if (dom.replenishmentEvents >= 2) contra += "Встречные Ask-заявки продолжают пополняться (${dom.replenishmentEvents}x)"
            if (absM.attackExhaustion >= 0.55) contra += "Сила повторных атак снижается"

            confirm += "Поглощение остатка Ask на $levelTxt без нового пополнения"
            confirm += "Продвижение цены и удержание выше $levelTxt"
            invalid += "Резкий откат под $levelTxt и смена дельты на отрицательную"
        } else {
            if (fp.netDelta < 0 && fp.cvdSlope < 0) pro += "Продавцы удерживают инициативу, CVD продолжает снижаться"
            if (dom.executedQty > dom.cancelledQty && dom.replenishmentEvents < 2) pro += "Ликвидность Bid выедается и не восстанавливается в прежнем объёме"
            if (dom.normalizedOfi < -0.15) pro += "OFI (${fmtD(dom.normalizedOfi)}) указывает на давление вниз"
            if (atk.acceptedBeyondLevel) pro += "Цена удерживается ниже поддержки"

            if (absM.detected && absM.side == AbsorptionSide.BUYER_ABSORPTION) {
                contra += "Крупные продажи поглощаются покупателями на поддержке"
            }
            if (dom.replenishmentEvents >= 2) contra += "На Bid появляется повторное пополнение ликвидности (${dom.replenishmentEvents}x)"
            if (absM.attackExhaustion >= 0.55) contra += "Импульс продавцов теряет эффективность"

            confirm += "Выход ниже $levelTxt без мгновенного выкупа"
            confirm += "Закрепление ниже уровня с сохранением отрицательной дельты"
            invalid += "Быстрый возврат выше $levelTxt при положительной реакции Bid"
        }

        if (pro.isEmpty()) pro += "Атакующая сторона сохраняет давление у $levelTxt"
        if (contra.isEmpty()) contra += "Пока нет окончательного закрепления за границей уровня"

        return ScenarioCase(dirTitle, pro, contra, confirm, invalid)
    }

    private fun buildHumanExplanation(
        symbol: String,
        price: Double?,
        levelTxt: String,
        isResistance: Boolean,
        dominance: MarketDominance,
        fp: FootprintSummary,
        dom: DomSnapshotMetrics,
        absM: AbsorptionMetrics,
        atk: LevelAttackState,
        conflictNote: String?
    ): String {
        val sideActive = when {
            fp.deltaPct >= 10.0 -> "покупатели"
            fp.deltaPct <= -10.0 -> "продавцы"
            else -> "участники в равновесии"
        }
        val levelWord = if (isResistance) "сопротивление $levelTxt" else "поддержку $levelTxt"

        return buildString {
            when (dominance) {
                MarketDominance.POSSIBLE_ABSORPTION -> {
                    val buyersAttacking = absM.side == AbsorptionSide.SELLER_ABSORPTION
                    val attacker = if (buyersAttacking) "Покупатели активно атакуют" else "Продавцы активно давят на"
                    val defender = if (buyersAttacking) "продавцов" else "покупателей"
                    append("$attacker $levelWord ($symbol). За последние ${atk.touchCount.coerceAtLeast(1)} попытки агрессивный поток вырос (Delta ${fmtPct(fp.deltaPct)}), однако цена практически не продвинулась. ")
                    if (dom.replenishmentEvents >= 2) {
                        append("На уровне сохраняется и пополняется встречная ликвидность $defender (${dom.replenishmentEvents}x после сделок). ")
                    } else {
                        append("Встречные заявки $defender удерживают напор (устойчивость ${(dom.levelPersistenceRatio * 100).toInt()}%). ")
                    }
                    append("Обнаружены признаки возможного поглощения. Разворот пока НЕ подтверждён. ")
                    append(
                        if (buyersAttacking) {
                            "Если покупательское давление ослабнет и цена уйдёт ниже $levelTxt, вероятность снижения увеличится; при доисполнении Ask возможен пробой вверх."
                        } else {
                            "Если давление продавцов ослабнет и цена закрепится выше $levelTxt, вероятен отскок вверх; при истощении Bid — пробой вниз."
                        }
                    )
                }

                MarketDominance.CONFIRMED_REJECTION -> {
                    append("После атак на $levelWord ($symbol) цена получила отказ и отошла от уровня (${atk.postAttackReactionPct?.let { fmtPct(it) } ?: "откат"}). ")
                    append("Атакующая сторона (${if (isResistance) "покупатели" else "продавцы"}) ослабла, встречная ликвидность удержала границу.")
                }

                MarketDominance.CONFIRMED_BREAKOUT -> {
                    append("Пробой $levelWord ($symbol) получил подтверждение: ликвидность на уровне исполнена (исп. ${fmtCompact(dom.executedQty)}), цена закрепилась за границей с поддержкой потока (Delta ${fmtPct(fp.deltaPct)}, OFI ${fmtD(dom.normalizedOfi)}).")
                }

                MarketDominance.CONFLICT -> {
                    append("По $symbol зафиксирован конфликт сигналов: ${conflictNote ?: "лента и стакан расходятся"}. ")
                    append("Свечение и агрессивные входы приглушены до разрешения противоречия.")
                }

                MarketDominance.BUYERS_DOMINATE -> {
                    append("На рынке $symbol доминируют покупатели: положительная Delta ${fmtPct(fp.deltaPct)}, восходящий CVD и перевес стакана (OFI ${fmtD(dom.normalizedOfi)}). Цена ${price?.let { fmtPx(it) } ?: ""} продвигается без критического сопротивления.")
                }

                MarketDominance.SELLERS_DOMINATE -> {
                    append("На рынке $symbol доминируют продавцы: отрицательная Delta ${fmtPct(fp.deltaPct)}, давление в ленте (CVD ${fmtCompact(fp.cvd)}) и перевес предложений (OFI ${fmtD(dom.normalizedOfi)}).")
                }

                else -> {
                    append("По $symbol наблюдается борьба сторон ($sideActive, Delta ${fmtPct(fp.deltaPct)}, OFI ${fmtD(dom.normalizedOfi)}) рядом с $levelWord. Явного подтверждённого преимущества пока нет — ожидаем реакцию на уровень.")
                }
            }

            if (dom.churnInflationRatio >= 10.0) {
                append(" Внимание: повышенный HFT-шум отмен (CTR ${fmtD(dom.churnInflationRatio)}x) — признаки фильтруются.")
            }
        }
    }

    /**
     * Аналитическая логика Dynamic Market Glow:
     *  - Зелёный: преимущество покупателей; Красный: преимущество продавцов; Нейтральный: неопределённость / конфликт / один фактор.
     *  - Яркость = число и согласованность независимых факторов (Delta, CVD, MLOFI, Footprint, Level Attack/Breakout).
     *  - При конфликте свечи/Радара и потока или CTR-раздувании без исполнений — жёстко уходит в NEUTRAL с conflictDetected=true.
     */
    internal fun computeGlowSignal(
        freshness: DataFreshness,
        fp: FootprintSummary,
        dom: DomSnapshotMetrics,
        absM: AbsorptionMetrics,
        atk: LevelAttackState,
        quantAdx: Double? = null,
        quantEfficiencyRatio: Double? = null,
        candleChangePct: Double? = null
    ): GlowSignal {
        if (freshness == DataFreshness.INSUFFICIENT || freshness == DataFreshness.DESYNC ||
            freshness == DataFreshness.STALE || !fp.sufficientData || !dom.sufficientData
        ) {
            return GlowSignal(
                colorState = GlowColorState.NEUTRAL,
                strength = 0f,
                speedFactor = 0.45f,
                independentFactors = 0,
                reason = "Нейтральный режим: ${freshness.labelRu}",
                conflictDetected = false
            )
        }

        val conflict = detectConflict(fp, dom, quantAdx, quantEfficiencyRatio, candleChangePct)
        if (conflict.hardConflict) {
            return GlowSignal(
                colorState = GlowColorState.NEUTRAL,
                strength = 0.10f,
                speedFactor = 0.5f,
                independentFactors = 0,
                reason = conflict.reason ?: "Конфликт свечи, Радара и потока — нейтральный режим",
                conflictDetected = true
            )
        }

        var bullVotes = 0
        var bearVotes = 0
        var intensitySum = 0.0

        // 1. Delta %
        if (fp.deltaPct >= 10.0) { bullVotes++; intensitySum += (fp.deltaPct / 45.0).coerceIn(0.2, 1.0) }
        else if (fp.deltaPct <= -10.0) { bearVotes++; intensitySum += (abs(fp.deltaPct) / 45.0).coerceIn(0.2, 1.0) }

        // 2. CVD slope
        if (fp.cvdSlope > 0 && fp.cvd > 0) { bullVotes++; intensitySum += 0.55 }
        else if (fp.cvdSlope < 0 && fp.cvd < 0) { bearVotes++; intensitySum += 0.55 }

        // 3. DOM MLOFI
        if (dom.normalizedOfi >= 0.16) { bullVotes++; intensitySum += abs(dom.normalizedOfi).coerceIn(0.2, 1.0) }
        else if (dom.normalizedOfi <= -0.16) { bearVotes++; intensitySum += abs(dom.normalizedOfi).coerceIn(0.2, 1.0) }

        // 4. Stacked Imbalance / агрессия стакана
        if (fp.stackedAskCount >= 3 || dom.bookImbalancePct >= 15.0) { bullVotes++; intensitySum += 0.6 }
        else if (fp.stackedBidCount >= 3 || dom.bookImbalancePct <= -15.0) { bearVotes++; intensitySum += 0.6 }

        // 5. Подтверждённый отказ / пробой уровня
        when (atk.phase) {
            LevelAttackPhase.CONFIRMED_BREAKOUT -> {
                if (atk.isResistance) { bullVotes += 2; intensitySum += 1.0 }
                else { bearVotes += 2; intensitySum += 1.0 }
            }
            LevelAttackPhase.CONFIRMED_REJECTION -> {
                if (atk.isResistance) { bearVotes += 2; intensitySum += 1.0 }
                else { bullVotes += 2; intensitySum += 1.0 }
            }
            LevelAttackPhase.POSSIBLE_ABSORPTION, LevelAttackPhase.ATTACKER_EXHAUSTION -> {
                intensitySum *= 0.65
            }
            else -> Unit
        }

        val netVotes = bullVotes - bearVotes
        val dominantVotes = maxOf(bullVotes, bearVotes)
        val hasVoteConflict = bullVotes >= 2 && bearVotes >= 2

        val color = when {
            hasVoteConflict -> GlowColorState.NEUTRAL
            netVotes >= 2 && bullVotes >= 2 -> GlowColorState.GREEN
            netVotes <= -2 && bearVotes >= 2 -> GlowColorState.RED
            else -> GlowColorState.NEUTRAL
        }

        // Неподтверждённое поглощение ПРОТИВ атакующих гасит цвет до нейтрали
        val dampedByAbsorption = absM.detected && !absM.confirmed && (
            (color == GlowColorState.GREEN && absM.side == AbsorptionSide.SELLER_ABSORPTION) ||
                (color == GlowColorState.RED && absM.side == AbsorptionSide.BUYER_ABSORPTION)
            )

        val finalColor = if (dampedByAbsorption) GlowColorState.NEUTRAL else color
        var rawStrength = if (finalColor == GlowColorState.NEUTRAL) {
            0.12f
        } else {
            ((abs(netVotes) / 4.5) * 0.55 + (intensitySum / maxOf(1, dominantVotes)) * 0.45).toFloat().coerceIn(0.22f, 1.0f)
        }
        // При высоком HFT-раздувании снижаем интенсивность свечения
        if (dom.churnInflationRatio >= 15.0 && finalColor != GlowColorState.NEUTRAL) {
            rawStrength = (rawStrength * 0.72f).coerceAtLeast(0.22f)
        }
        val pressureRate = ((dom.addRatePerSec + dom.cancelRatePerSec) / 14.0).toFloat().coerceIn(0.5f, 2.0f)

        val reason = when (finalColor) {
            GlowColorState.GREEN -> "Преимущество покупателей ($bullVotes факторов, яркость ${(rawStrength * 100).toInt()}%)"
            GlowColorState.RED -> "Преимущество продавцов ($bearVotes факторов, яркость ${(rawStrength * 100).toInt()}%)"
            GlowColorState.NEUTRAL -> when {
                hasVoteConflict -> "Нейтрально: конфликт факторов (быки $bullVotes vs медведи $bearVotes)"
                dampedByAbsorption -> "Нейтрально: обнаружено возможное поглощение — разворот пока не подтверждён"
                else -> "Нейтрально: баланс сил или недостаточно согласованных признаков"
            }
        }

        return GlowSignal(
            colorState = finalColor,
            strength = rawStrength,
            speedFactor = pressureRate,
            independentFactors = dominantVotes,
            reason = reason,
            conflictDetected = hasVoteConflict
        )
    }

    private fun countAlignedMetrics(
        fp: FootprintSummary,
        dom: DomSnapshotMetrics,
        absM: AbsorptionMetrics,
        atk: LevelAttackState
    ): Int {
        var c = 0
        if (fp.sufficientData && abs(fp.deltaPct) >= 12.0) c++
        if (dom.sufficientData && abs(dom.normalizedOfi) >= 0.16) c++
        if (dom.replenishmentEvents >= 2 || dom.executedQty > 0.0) c++
        if (fp.stackedAskCount >= 3 || fp.stackedBidCount >= 3) c++
        if (absM.detected || atk.phase != LevelAttackPhase.NONE) c++
        return c
    }

    private fun computeCouncilWeight(
        dominance: MarketDominance,
        fp: FootprintSummary,
        dom: DomSnapshotMetrics,
        absM: AbsorptionMetrics,
        atk: LevelAttackState
    ): Double {
        return when (dominance) {
            MarketDominance.CONFIRMED_BREAKOUT -> if (atk.isResistance) 0.22 else -0.22
            MarketDominance.CONFIRMED_REJECTION -> if (atk.isResistance) -0.22 else 0.22
            MarketDominance.POSSIBLE_ABSORPTION -> if (absM.confirmed) {
                if (absM.side == AbsorptionSide.SELLER_ABSORPTION) -0.14 else 0.14
            } else 0.0
            MarketDominance.BUYERS_DOMINATE -> ((fp.deltaPct / 100.0) * 0.15 + dom.normalizedOfi * 0.10).coerceIn(0.05, 0.20)
            MarketDominance.SELLERS_DOMINATE -> ((fp.deltaPct / 100.0) * 0.15 + dom.normalizedOfi * 0.10).coerceIn(-0.20, -0.05)
            else -> 0.0
        }
    }

    private fun fmtPx(p: Double): String = when {
        p >= 1000.0 -> String.format(Locale.US, "%.1f", p)
        p >= 1.0 -> String.format(Locale.US, "%.3f", p)
        else -> String.format(Locale.US, "%.5f", p)
    }

    private fun fmtPct(v: Double): String = String.format(Locale.US, "%+.2f%%", v)
    private fun fmtD(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun fmtCompact(v: Double): String = when {
        abs(v) >= 1_000_000 -> String.format(Locale.US, "%.2fM", v / 1_000_000.0)
        abs(v) >= 1_000 -> String.format(Locale.US, "%.1fK", v / 1_000.0)
        else -> String.format(Locale.US, "%.2f", v)
    }
}
