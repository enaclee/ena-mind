package com.enamind.ai.orderflow

import java.util.Locale
import kotlin.math.abs

/**
 * Построение конкурирующих сценариев A (возможный разворот) и B (возможный пробой),
 * понятного текстового объяснения ENA на русском языке, калиброванной оценки достоверности
 * и аналитического сигнала Dynamic Market Glow.
 *
 * Жёсткие ограничения:
 *  - НЕ выдаёт гарантированных прогнозов и автоматических BUY/SELL сигналов;
 *  - НЕ включает зелёный/красный свет по одному изолированному показателю;
 *  - При нехватке/рассинхроне данных переводит Glow в нейтральный режим и сообщает об этом;
 *  - НЕ путает обнаруженное поглощение с уже подтверждённым разворотом.
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
        vaultStats: OrderFlowEventVault.EmpiricalStats
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
                    reason = reason
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

        // 1. Определение доминирования
        val dominance = when {
            attack.phase == LevelAttackPhase.CONFIRMED_BREAKOUT -> MarketDominance.CONFIRMED_BREAKOUT
            attack.phase == LevelAttackPhase.CONFIRMED_REJECTION -> MarketDominance.CONFIRMED_REJECTION
            absorption.detected -> MarketDominance.POSSIBLE_ABSORPTION
            footprint.deltaPct >= 14.0 && dom.normalizedOfi >= 0.15 -> MarketDominance.BUYERS_DOMINATE
            footprint.deltaPct <= -14.0 && dom.normalizedOfi <= -0.15 -> MarketDominance.SELLERS_DOMINATE
            else -> MarketDominance.CONTESTED
        }

        // 2. Построение двух сценариев одновременно (A — разворот, B — пробой)
        val levelTxt = attack.levelPrice?.let { fmtPx(it) } ?: footprint.pocPrice?.let { fmtPx(it) } ?: "текущий узел"
        val isRes = attack.isResistance

        val scenarioA = buildReversalScenario(isRes, levelTxt, footprint, dom, absorption, attack)
        val scenarioB = buildBreakoutScenario(isRes, levelTxt, footprint, dom, absorption, attack)

        // 3. Оценка достоверности (без произвольных процентов!)
        val alignedCount = countAlignedMetrics(footprint, dom, absorption, attack)
        val grade = when {
            attack.phase == LevelAttackPhase.CONFIRMED_BREAKOUT ||
                attack.phase == LevelAttackPhase.CONFIRMED_REJECTION ||
                absorption.confirmed -> ReliabilityGrade.CONFIRMED
            alignedCount >= 4 -> ReliabilityGrade.STRONG
            alignedCount >= 2 -> ReliabilityGrade.MODERATE
            else -> ReliabilityGrade.WEAK
        }

        // Вероятности показываются ТОЛЬКО если есть достаточная историческая выборка в OrderFlowEventVault
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

        val confidence = ConfidenceAssessment(
            grade = grade,
            alignedFactorsCount = alignedCount,
            calibratedAbsorptionProbPct = absProb,
            calibratedReversalProbPct = revProb,
            calibratedBreakoutProbPct = brkProb,
            historicalSampleSize = vaultStats.verifiedEvents,
            dataQualityNote = "Согласовано независимых признаков: $alignedCount · задержка стакана ${if (dom.lagMs >= 0) "${dom.lagMs} мс" else "локально"}",
            historicalInsight = histInsight
        )

        // 4. Человеческое объяснение ENA (отвечает на 7 ключевых вопросов раздела 1 ТЗ)
        val explanation = buildHumanExplanation(
            symbol = symbol,
            price = price,
            levelTxt = levelTxt,
            isResistance = isRes,
            dominance = dominance,
            fp = footprint,
            dom = dom,
            absM = absorption,
            atk = attack
        )

        // 5. Dynamic Market Glow (многофакторный расчёт без единичных ложных срабатываний)
        val glow = computeGlowSignal(freshness, footprint, dom, absorption, attack)

        // 6. Пакет для Neural Council (с жёстким ограничением влияния на вес [-0.25 .. +0.25])
        val rawBiasWeight = computeCouncilWeight(dominance, footprint, dom, absorption, attack)
        val payload = OrderFlowCouncilPayload(
            detectedEvent = "${dominance.name} / ${attack.phase.name}",
            levelPrice = attack.levelPrice,
            signalStrength = grade.labelRu,
            supportingEvidence = absorption.supportingFactors.ifEmpty {
                listOf("Delta ${fmtPct(footprint.deltaPct)}", "OFI ${fmtD(dom.normalizedOfi)}", "Исп. ${fmtCompact(dom.executedQty)}")
            },
            contradictingEvidence = absorption.conflictingFactors,
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
            if (fp.netDelta > 0) pro += "Покупатели активно бьют по рынку (Delta ${fmtPct(fp.deltaPct)}), создавая повышенный оборот у уровня"
            if (absM.side == AbsorptionSide.BUYER_ABSORPTION && absM.detected) pro += "Выявлены признаки поглощения покупок (эффективность хода ${fmtD(absM.priceProgressEfficiency)})"
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
            if (absM.side == AbsorptionSide.SELLER_ABSORPTION && absM.detected) pro += "Выявлены признаки поглощения продаж покупателями"
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

            if (absM.detected && absM.side == AbsorptionSide.BUYER_ABSORPTION) contra += "Обнаружено поглощение покупок (риск ложного пробоя)"
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

            if (absM.detected && absM.side == AbsorptionSide.SELLER_ABSORPTION) contra += "Крупные продажи поглощаются покупателями на уровне"
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
        atk: LevelAttackState
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
                    val attacker = if (absM.side == AbsorptionSide.BUYER_ABSORPTION) "Покупатели активно атакуют" else "Продавцы активно давят на"
                    val defender = if (absM.side == AbsorptionSide.BUYER_ABSORPTION) "продавцов" else "покупателей"
                    append("$attacker $levelWord ($symbol). За последние ${atk.touchCount.coerceAtLeast(1)} попытки агрессивный поток вырос (Delta ${fmtPct(fp.deltaPct)}), однако цена практически не продвинулась. ")
                    if (dom.replenishmentEvents >= 2) {
                        append("На уровне сохраняется и пополняется встречная ликвидность $defender (${dom.replenishmentEvents}x после сделок). ")
                    } else {
                        append("Встречные заявки $defender удерживают напор (устойчивость ${(dom.levelPersistenceRatio * 100).toInt()}%). ")
                    }
                    append("Обнаружены признаки возможного поглощения. Разворот пока НЕ подтверждён. ")
                    append(
                        if (absM.side == AbsorptionSide.BUYER_ABSORPTION) {
                            "Если покупательское давление ослабнет и цена уйдёт ниже $levelTxt, вероятность снижения может увеличиться; при доисполнении Ask возможен пробой вверх."
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
                    append("Пробой $levelWord ($symbol) получил подтверждение: ликвидность на уровне была исполнена (исп. ${fmtCompact(dom.executedQty)}), цена закрепилась за границей с поддержкой потока (Delta ${fmtPct(fp.deltaPct)}, OFI ${fmtD(dom.normalizedOfi)}).")
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
        }
    }

    /**
     * Аналитическая логика Dynamic Market Glow:
     *  - Зелёный: преимущество покупателей; Красный: преимущество продавцов; Нейтральный: неопределённость / сбой / один фактор.
     *  - Яркость = число и согласованность независимых факторов (Delta, CVD, OFI, Footprint, Absorption, Level Attack/Breakout).
     *  - Никогда не включает Зелёный/Красный на одном изолированном показателе; не путает возможное поглощение с уже подтверждённым разворотом.
     */
    internal fun computeGlowSignal(
        freshness: DataFreshness,
        fp: FootprintSummary,
        dom: DomSnapshotMetrics,
        absM: AbsorptionMetrics,
        atk: LevelAttackState
    ): GlowSignal {
        if (freshness == DataFreshness.INSUFFICIENT || freshness == DataFreshness.DESYNC ||
            freshness == DataFreshness.STALE || !fp.sufficientData || !dom.sufficientData
        ) {
            return GlowSignal(
                colorState = GlowColorState.NEUTRAL,
                strength = 0f,
                speedFactor = 0.45f,
                independentFactors = 0,
                reason = "Нейтральный режим: ${freshness.labelRu}"
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

        // 3. DOM OFI
        if (dom.normalizedOfi >= 0.16) { bullVotes++; intensitySum += abs(dom.normalizedOfi).coerceIn(0.2, 1.0) }
        else if (dom.normalizedOfi <= -0.16) { bearVotes++; intensitySum += abs(dom.normalizedOfi).coerceIn(0.2, 1.0) }

        // 4. Stacked Imbalance / агрессия стакана
        if (fp.stackedAskCount >= 3 || dom.bookImbalancePct >= 15.0) { bullVotes++; intensitySum += 0.6 }
        else if (fp.stackedBidCount >= 3 || dom.bookImbalancePct <= -15.0) { bearVotes++; intensitySum += 0.6 }

        // 5. Подтверждённый отказ / пробой уровня (НЕ путаем возможное поглощение с подтверждённым разворотом!)
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
                // Поглощение НЕ включает противоположный цвет само по себе; оно лишь ослабляет уверенность атакующей стороны!
                intensitySum *= 0.65
            }
            else -> Unit
        }

        // Запрет переключения цвета на 1 изолированном признаке: требуем ≥ 2 независимых голосов с явным перевесом
        val netVotes = bullVotes - bearVotes
        val dominantVotes = maxOf(bullVotes, bearVotes)
        val hasConflict = bullVotes >= 2 && bearVotes >= 2

        val color = when {
            hasConflict -> GlowColorState.NEUTRAL
            netVotes >= 2 && bullVotes >= 2 -> GlowColorState.GREEN
            netVotes <= -2 && bearVotes >= 2 -> GlowColorState.RED
            else -> GlowColorState.NEUTRAL
        }

        // Если обнаружено неподтверждённое поглощение против атакующих — приглушаем или уходим в нейтраль (честность ТЗ п.9)
        val dampedByAbsorption = absM.detected && !absM.confirmed && (
            (color == GlowColorState.GREEN && absM.side == AbsorptionSide.BUYER_ABSORPTION) ||
                (color == GlowColorState.RED && absM.side == AbsorptionSide.SELLER_ABSORPTION)
            )

        val finalColor = if (dampedByAbsorption) GlowColorState.NEUTRAL else color
        val rawStrength = if (finalColor == GlowColorState.NEUTRAL) {
            0.12f
        } else {
            ((abs(netVotes) / 4.5) * 0.55 + (intensitySum / maxOf(1, dominantVotes)) * 0.45).toFloat().coerceIn(0.22f, 1.0f)
        }
        val pressureRate = ((dom.addRatePerSec + dom.cancelRatePerSec) / 14.0).toFloat().coerceIn(0.5f, 2.2f)

        val reason = when (finalColor) {
            GlowColorState.GREEN -> "Преимущество покупателей ($bullVotes факторов, яркость ${(rawStrength * 100).toInt()}%)"
            GlowColorState.RED -> "Преимущество продавцов ($bearVotes факторов, яркость ${(rawStrength * 100).toInt()}%)"
            GlowColorState.NEUTRAL -> if (dampedByAbsorption) {
                "Нейтрально: обнаружено возможное поглощение — разворот пока не подтверждён"
            } else {
                "Нейтрально: баланс сил или недостаточно согласованных признаков"
            }
        }

        return GlowSignal(
            colorState = finalColor,
            strength = rawStrength,
            speedFactor = pressureRate,
            independentFactors = dominantVotes,
            reason = reason
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
                if (absM.side == AbsorptionSide.BUYER_ABSORPTION) -0.14 else 0.14
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
