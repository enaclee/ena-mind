package com.enamind.ai.intelligence

import com.enamind.ai.bybit.MultiVenueDataBridge
import com.enamind.ai.core.AppState
import com.enamind.ai.orderflow.OrderFlowCouncilPayload
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Вычислительная и логическая система ботов-агентов ENA.
 *
 * Задачи:
 * 1) Разгрузить Python-мост: считать структуру, тренд, поток, кросс-биржевые потоки,
 *    скорость ленты, давление сквиза, Order Flow / Absorption, Chain Radar и Crypto Sentinel прямо в Kotlin;
 * 2) Строить причинно-следственную логику (Наблюдение -> Механизм -> Вывод -> Отмена);
 * 3) Выдавать свежие прогнозы на 6ч и 24ч при достаточном числе реальных свечей и честно сообщать при нехватке данных.
 */
object TrendReasoningEngine {

    fun analyze(
        symbol: String,
        klinesByTf: Map<String, List<List<Double>>>,
        market: AppState.MarketUi,
        crossSpreadBps: Double?,
        radarRaw: IntelApiClient.RawRadarData,
        pythonResult: JSONObject?,
        nowMs: Long = System.currentTimeMillis(),
        learnedWeights: Map<String, Double> = emptyMap(),
        learning: LearningSnapshot = LearningSnapshot(),
        clockStatus: ExchangeClockSync.ClockSyncStatus? = null,
        venueSnap: MultiVenueDataBridge.VenueSnapshot? = null,
        binancePrice: Double = 0.0,
        okxPrice: Double = 0.0,
        orderFlow: OrderFlowCouncilPayload? = null
    ): UnifiedIntelligenceReport {
        // 1. Аудит и очистка свечей по всем ТФ с учётом биржевого времени
        val cleanByTf = LinkedHashMap<String, List<List<Double>>>()
        val qualityByTf = LinkedHashMap<String, QuantMathKernel.DataQualityReport>()
        for ((tf, rawBars) in klinesByTf) {
            val qr = QuantMathKernel.sanitizeBars(rawBars, tf, nowMs)
            qualityByTf[tf] = qr
            if (qr.cleanBars.isNotEmpty()) cleanByTf[tf] = qr.cleanBars
        }
        val activeTf = chooseBestSeries(cleanByTf)
        val mainQuality = qualityByTf[activeTf]
            ?: QuantMathKernel.sanitizeBars(klinesByTf[activeTf].orEmpty(), activeTf, nowMs)
        val mainBars = mainQuality.cleanBars
        val livePrice = market.price?.takeIf { it.isFinite() && it > 0.0 }
            ?: mainBars.lastOrNull()?.getOrNull(4)?.takeIf { it.isFinite() && it > 0.0 }

        // 2. Количественные метрики тренда (ADX, Kaufman ER, OLS R², Welford σ) + Walk-Forward бэктест
        val quantMetrics = QuantMathKernel.computeMetrics(mainBars)
        val walkForward = QuantMathKernel.runWalkForward(mainBars)

        val mtfBot = runMtfTrendBot(cleanByTf)
        val structBot = runMarketStructureBot(mainBars, activeTf)
        val flowBot = runOrderFlowBot(market)
        val radarSnapshot = buildChainRadarSnapshot(symbol, radarRaw, nowMs)
        val radarBot = runChainRadarBot(radarSnapshot)
        val crossVenueBot = runCrossVenueBot(livePrice, binancePrice, okxPrice, crossSpreadBps, venueSnap)
        val velocityBot = runVelocityBot(market, venueSnap)
        val bookPressureBot = runBookPressureBot(market, venueSnap)
        val squeezeBot = runSqueezeBot(market, mainBars)

        val sentinelEvents = detectSentinelEvents(
            symbol = symbol,
            bars = mainBars,
            market = market,
            crossSpreadBps = crossSpreadBps,
            radar = radarSnapshot,
            news = market.news,
            nowMs = nowMs,
            venueSnap = venueSnap
        )
        val sentinelBot = runSentinelBot(sentinelEvents)
        val ofBot = runOrderFlowIntelligenceBot(orderFlow)

        val bots = buildList {
            add(mtfBot)
            add(structBot)
            add(flowBot)
            add(crossVenueBot)
            add(velocityBot)
            add(bookPressureBot)
            add(squeezeBot)
            add(radarBot)
            add(sentinelBot)
            ofBot?.let { add(it) }
            pythonCouncilBot(pythonResult)?.let { add(it) }
        }

        // Комбинированный калибратор вероятностей: Walk-Forward OOS + накопленная память 6ч
        val vaultCal = learning.hitRatePct?.let { hr ->
            if (learning.verifiedCount >= 5 && hr.isFinite()) (0.75 + (hr - 50.0) / 100.0).coerceIn(0.55, 1.15) else 1.0
        } ?: 1.0
        val combinedCal = ((0.65 * walkForward.calibrationFactor + 0.35 * vaultCal)).coerceIn(0.45, 1.15)

        val quantDiag = QuantDiagnostics(
            clockSynced = clockStatus?.synced == true,
            clockOffsetMs = clockStatus?.offsetMs ?: 0L,
            clockRttMs = clockStatus?.rttMs ?: 0L,
            clockSource = clockStatus?.source ?: "device",
            dataQualityScore = mainQuality.score,
            dataGrade = mainQuality.grade,
            dataNotes = mainQuality.notes,
            adx = quantMetrics.adx,
            efficiencyRatio = quantMetrics.efficiencyRatio,
            regressionR2 = quantMetrics.regressionR2,
            walkForwardSamples = walkForward.samples,
            walkForwardHitPct = walkForward.hitRatePct,
            brierScore = walkForward.brierScore,
            calibrationFactor = combinedCal
        )

        val trendLogic = synthesizeLogic(
            symbol = symbol,
            livePrice = livePrice,
            activeTf = activeTf,
            bars = mainBars,
            klinesByTf = cleanByTf,
            market = market,
            radar = radarSnapshot,
            sentinelEvents = sentinelEvents,
            bots = bots,
            pythonResult = pythonResult,
            learnedWeights = learnedWeights,
            learning = learning,
            quantMetrics = quantMetrics,
            walkForward = walkForward,
            quantDiag = quantDiag,
            orderFlow = orderFlow
        )

        val headline = buildString {
            append(trendLogic.biasLabel)
            append(" · ")
            append(trendLogic.phase)
            append(" (сила ${trendLogic.strength}/100)")
        }

        val narrative = buildString {
            append(radarSnapshot.preChartAnswer)
            if (orderFlow != null && orderFlow.sufficientData) {
                val lvlStr = orderFlow.levelPrice?.let { " у ${fmtPx(it)}" } ?: ""
                append("\nOrder Flow: ${orderFlow.detectedEvent}$lvlStr (${orderFlow.signalStrength}; ${orderFlow.dataQuality}).")
            }
            if (sentinelEvents.isNotEmpty()) {
                val topEv = sentinelEvents.first()
                append("\nСобытие Sentinel [${topEv.severity.labelRu}]: ${topEv.title}.")
            } else {
                append("\nSentinel: критических аномалий по потоку и волатильности не зафиксировано.")
            }
            val adxS = if (quantMetrics.sufficientData && quantMetrics.adx > 0.0) {
                "ADX %.0f".format(Locale.US, quantMetrics.adx)
            } else {
                "ADX нет истории"
            }
            val erS = "%.2f".format(Locale.US, quantMetrics.efficiencyRatio)
            val r2S = "%.2f".format(Locale.US, quantMetrics.regressionR2)
            append("\nКачество данных ${mainQuality.grade} · $adxS · ER $erS · R² $r2S.")
            if (learning.verifiedCount >= 3 && learning.hitRatePct != null) {
                val hrStr = "%.0f%%".format(Locale.US, learning.hitRatePct)
                append("\nПамять самообучения: проверено ${learning.verifiedCount} прогнозов · точность $hrStr.")
            }
        }

        val outlook6 = computeOutlookFromClean(
            horizonHours = 6,
            cleanByTf = cleanByTf,
            trendLogic = trendLogic,
            quantDiag = quantDiag,
            market = market,
            radar = radarSnapshot,
            venueSnap = venueSnap,
            pythonResult = pythonResult,
            nowMs = nowMs
        )
        val outlook24 = computeOutlookFromClean(
            horizonHours = 24,
            cleanByTf = cleanByTf,
            trendLogic = trendLogic,
            quantDiag = quantDiag,
            market = market,
            radar = radarSnapshot,
            venueSnap = venueSnap,
            pythonResult = pythonResult,
            nowMs = nowMs
        )
        val venueSummary = buildVenueSummary(livePrice, binancePrice, okxPrice, crossSpreadBps, venueSnap)

        return UnifiedIntelligenceReport(
            symbol = symbol,
            price = livePrice,
            sourceLabel = market.feedSource,
            pythonEngineOk = pythonResult?.optBoolean("ok") == true,
            trendLogic = trendLogic,
            bots = bots,
            chainRadar = radarSnapshot,
            sentinelEvents = sentinelEvents,
            synthesisHeadline = headline,
            synthesisNarrative = narrative,
            computedAtMs = nowMs,
            learning = learning,
            quant = quantDiag,
            outlook6h = outlook6,
            outlook24h = outlook24,
            venues = venueSummary
        )
    }

    // ---------------- Бот 1: Мульти-таймфрейм тренд ----------------
    private fun runMtfTrendBot(klinesByTf: Map<String, List<List<Double>>>): BotSignal {
        val tfs = listOf("1", "5", "15", "60")
        val dirs = LinkedHashMap<String, BiasDirection>()
        var scoreSum = 0.0
        var weightSum = 0.0

        val weights = mapOf("1" to 0.15, "5" to 0.20, "15" to 0.30, "60" to 0.35)
        for (tf in tfs) {
            val bars = klinesByTf[tf].orEmpty()
            if (bars.size < 25) continue
            val closes = bars.mapNotNull { it.getOrNull(4) }
            if (closes.size < 25) continue
            val ema9 = ema(closes, 9)
            val ema21 = ema(closes, 21)
            val ema50 = ema(closes, min(50, closes.size))
            val last = closes.last()
            val slope = if (closes.size >= 8) (last - closes[closes.size - 8]) / max(1e-9, closes[closes.size - 8]) else 0.0
            val dir = when {
                last > ema9 && ema9 >= ema21 && ema21 >= ema50 * 0.999 && slope > 0.0005 -> BiasDirection.UP
                last < ema9 && ema9 <= ema21 && ema21 <= ema50 * 1.001 && slope < -0.0005 -> BiasDirection.DOWN
                last > ema21 && slope > 0.0012 -> BiasDirection.UP
                last < ema21 && slope < -0.0012 -> BiasDirection.DOWN
                else -> BiasDirection.FLAT
            }
            dirs[tf] = dir
            val w = weights[tf] ?: 0.2
            weightSum += w
            scoreSum += w * when (dir) {
                BiasDirection.UP -> 1.0
                BiasDirection.DOWN -> -1.0
                BiasDirection.FLAT -> 0.0
            }
        }

        if (weightSum == 0.0) {
            return BotSignal(
                id = "bot_mtf",
                name = "Бот-Тренд (MTF)",
                category = BotCategory.TREND,
                bias = BiasDirection.FLAT,
                confidence = 0.0,
                summary = "Недостаточно свечей для мульти-ТФ оценки.",
                hasData = false
            )
        }

        val net = scoreSum / weightSum
        val bias = when {
            net >= 0.25 -> BiasDirection.UP
            net <= -0.25 -> BiasDirection.DOWN
            else -> BiasDirection.FLAT
        }
        val conf = (0.35 + abs(net) * 0.55).coerceIn(0.30, 0.92)
        val desc = dirs.entries.joinToString(", ") { (tf, d) ->
            "${tfLabel(tf)}=${dirSymbol(d)}"
        }
        return BotSignal(
            id = "bot_mtf",
            name = "Бот-Тренд (MTF)",
            category = BotCategory.TREND,
            bias = bias,
            confidence = conf,
            summary = "Выравнивание таймфреймов: $desc",
            metrics = dirs.mapKeys { tfLabel(it.key) }.mapValues { it.value.name }
        )
    }

    // ---------------- Бот 2: Рыночная структура и фаза ----------------
    private fun runMarketStructureBot(bars: List<List<Double>>, tf: String): BotSignal {
        if (bars.size < 30) {
            return BotSignal(
                id = "bot_struct",
                name = "Бот-Структура (HH/HL)",
                category = BotCategory.STRUCTURE,
                bias = BiasDirection.FLAT,
                confidence = 0.0,
                summary = "Жду ≥30 баров для анализа свингов.",
                hasData = false
            )
        }
        val highs = bars.mapNotNull { it.getOrNull(2) }
        val lows = bars.mapNotNull { it.getOrNull(3) }
        val closes = bars.mapNotNull { it.getOrNull(4) }
        val half = bars.size / 2
        val firstHigh = highs.subList(0, half).maxOrNull() ?: 0.0
        val secondHigh = highs.subList(half, highs.size).maxOrNull() ?: 0.0
        val firstLow = lows.subList(0, half).minOrNull() ?: 0.0
        val secondLow = lows.subList(half, lows.size).minOrNull() ?: 0.0
        val last = closes.last()
        val atrPct = computeAtrPct(bars, 14)

        val structType = when {
            secondHigh > firstHigh * 1.001 && secondLow > firstLow * 1.001 -> "HH/HL (восходящая)"
            secondHigh < firstHigh * 0.999 && secondLow < firstLow * 0.999 -> "LH/LL (нисходящая)"
            else -> "Боковик / Консолидация"
        }
        val bias = when {
            structType.startsWith("HH") -> BiasDirection.UP
            structType.startsWith("LH") -> BiasDirection.DOWN
            else -> BiasDirection.FLAT
        }
        val conf = if (bias == BiasDirection.FLAT) 0.42 else 0.72
        val summary = "ТФ ${tfLabel(tf)}: $structType · ATR %.2f%% · пик ${fmtPx(secondHigh)} / дно ${fmtPx(secondLow)}".format(Locale.US, atrPct)
        return BotSignal(
            id = "bot_struct",
            name = "Бот-Структура (HH/HL)",
            category = BotCategory.STRUCTURE,
            bias = bias,
            confidence = conf,
            summary = summary,
            metrics = mapOf(
                "structure" to structType,
                "atr_pct" to "%.2f%%".format(Locale.US, atrPct),
                "support" to fmtPx(secondLow),
                "resistance" to fmtPx(secondHigh),
                "price" to fmtPx(last)
            )
        )
    }

    // ---------------- Бот 3: Order Flow, Стакан и Деривативы ----------------
    private fun runOrderFlowBot(market: AppState.MarketUi): BotSignal {
        var score = 0.0
        var activeSignals = 0
        val notes = mutableListOf<String>()

        market.tape?.takeIf { it.n >= 10 }?.let { t ->
            activeSignals++
            when {
                t.deltaPct >= 10.0 -> { score += 1.0; notes += "агрессия покупателей %+.1f%%".format(Locale.US, t.deltaPct) }
                t.deltaPct <= -10.0 -> { score -= 1.0; notes += "давление продавцов %+.1f%%".format(Locale.US, t.deltaPct) }
                else -> notes += "нейтральная лента (%+.1f%%)".format(Locale.US, t.deltaPct)
            }
            if (t.divergence) {
                notes += "⚠ дивергенция цена/CVD"
            }
        }

        if (market.bids.isNotEmpty() && market.asks.isNotEmpty()) {
            activeSignals++
            val b = market.bids.sumOf { it.qty }
            val a = market.asks.sumOf { it.qty }
            val imb = if (b + a > 0) 100.0 * (b - a) / (b + a) else 0.0
            when {
                imb >= 15.0 -> { score += 0.6; notes += "перевес бидов %+.0f%%".format(Locale.US, imb) }
                imb <= -15.0 -> { score -= 0.6; notes += "перевес асков %+.0f%%".format(Locale.US, imb) }
            }
        }

        market.fundingPct?.let { f ->
            activeSignals++
            when {
                f >= 0.03 -> { score -= 0.4; notes += "перегретый лонг-фандинг %+.4f%%".format(Locale.US, f) }
                f <= -0.02 -> { score += 0.4; notes += "шорт-фандинг %+.4f%% (топливо шорт-сквиза)".format(Locale.US, f) }
            }
        }

        market.lsrPct?.let { pct ->
            activeSignals++
            when {
                pct >= 88 -> { score -= 0.4; notes += "толпа в лонгах (LSR п%.0f)".format(Locale.US, pct) }
                pct <= 12 -> { score += 0.4; notes += "толпа в шортах (LSR п%.0f)".format(Locale.US, pct) }
            }
        }

        val bias = when {
            score >= 0.55 -> BiasDirection.UP
            score <= -0.55 -> BiasDirection.DOWN
            else -> BiasDirection.FLAT
        }
        val conf = if (activeSignals == 0) 0.0 else (0.38 + min(0.50, abs(score) * 0.25))
        return BotSignal(
            id = "bot_flow",
            name = "Бот-Поток и Деривативы",
            category = BotCategory.FLOW,
            bias = bias,
            confidence = conf,
            summary = if (notes.isEmpty()) "Ожидание ленты сделок и деривативов…" else notes.joinToString(" · "),
            hasData = activeSignals > 0
        )
    }

    // ---------------- Бот 4: Chain Radar ----------------
    private fun buildChainRadarSnapshot(
        symbol: String,
        raw: IntelApiClient.RawRadarData,
        nowMs: Long
    ): ChainRadarSnapshot {
        val totalWhale = raw.whaleBuyUsd + raw.whaleSellUsd
        val whaleDeltaPct = if (totalWhale > 0) 100.0 * (raw.whaleBuyUsd - raw.whaleSellUsd) / totalWhale else null

        val moneyFlowBias = when {
            whaleDeltaPct != null && whaleDeltaPct >= 18.0 && (raw.stablecoinsChange7dPct ?: 0.0) >= -0.2 -> "ACCUMULATION"
            whaleDeltaPct != null && whaleDeltaPct <= -18.0 -> "DISTRIBUTION"
            (raw.stablecoinsChange7dPct ?: 0.0) >= 0.8 -> "ACCUMULATION"
            (raw.stablecoinsChange7dPct ?: 0.0) <= -0.8 -> "DISTRIBUTION"
            totalWhale > 0 || raw.stablecoinsTotalUsd != null -> "NEUTRAL"
            else -> "INSUFFICIENT"
        }

        val answer = buildString {
            when {
                whaleDeltaPct != null && whaleDeltaPct >= 15.0 ->
                    append("Крупные участники (сделки ≥$100K) забирают предложение с рынка: покупки $%s против продаж $%s (%+.1f%%). ".format(
                        Locale.US, compactUsd(raw.whaleBuyUsd), compactUsd(raw.whaleSellUsd), whaleDeltaPct
                    ))
                whaleDeltaPct != null && whaleDeltaPct <= -15.0 ->
                    append("Фиксируется разгрузка крупных объёмов (≥$100K): продажи $%s превышают покупки $%s (%+.1f%%). ".format(
                        Locale.US, compactUsd(raw.whaleSellUsd), compactUsd(raw.whaleBuyUsd), whaleDeltaPct
                    ))
                raw.whaleTradesCount > 0 ->
                    append("Крупные сделки ≥$100K ($%s покупок / $%s продаж) находятся в равновесии без явного перекоса. ".format(
                        Locale.US, compactUsd(raw.whaleBuyUsd), compactUsd(raw.whaleSellUsd)
                    ))
                else ->
                    append("Крупных сделок ≥$100K на споте Binance в последнем окне не зафиксировано. ")
            }

            raw.stablecoinsChange7dPct?.let { st7 ->
                val tot = raw.stablecoinsTotalUsd?.let { "$${compactUsd(it)}" } ?: "—"
                when {
                    st7 >= 0.4 -> append("Предложение стейблкоинов растёт (%+.2f%% за 7д, всего %s) — в систему заходит свежая покупательная ликвидность. ".format(Locale.US, st7, tot))
                    st7 <= -0.4 -> append("Стейблкоины сжимаются (%+.2f%% за 7д, всего %s) — свободная ликвидность уходит с рынка. ".format(Locale.US, st7, tot))
                    else -> append("Объём стейблкоинов стабилен (%s, %+.2f%% за 7д). ".format(Locale.US, tot, st7))
                }
            }

            if (raw.largeBtcTxs.isNotEmpty()) {
                val sumBtc = raw.largeBtcTxs.sumOf { it.amountBtc }
                append("В мемпуле BTC замечено %d крупных переводов ≥50 BTC на общую сумму %.0f BTC.".format(
                    Locale.US, raw.largeBtcTxs.size, sumBtc
                ))
            } else if (raw.feeFastSat != null) {
                append("Сеть BTC спокойна: быстрая комиссия %d sat/vB.".format(Locale.US, raw.feeFastSat))
            }
        }

        return ChainRadarSnapshot(
            symbol = symbol,
            updatedAtMs = nowMs,
            preChartAnswer = answer.trim(),
            moneyFlowBias = moneyFlowBias,
            whaleTradesCount = raw.whaleTradesCount,
            whaleBuyUsd = raw.whaleBuyUsd,
            whaleSellUsd = raw.whaleSellUsd,
            whaleDeltaPct = whaleDeltaPct,
            mempoolTxCount = raw.mempoolTxCount,
            feeFastSat = raw.feeFastSat,
            feeHalfHourSat = raw.feeHalfHourSat,
            btcLargeTransfers = raw.largeBtcTxs,
            stablecoinsTotalUsd = raw.stablecoinsTotalUsd,
            stablecoinsChange7dPct = raw.stablecoinsChange7dPct,
            defiTotalTvlUsd = raw.defiTotalTvlUsd,
            topChains = raw.topChains,
            sources = raw.sources
        )
    }

    private fun runChainRadarBot(radar: ChainRadarSnapshot): BotSignal {
        val bias = when (radar.moneyFlowBias) {
            "ACCUMULATION" -> BiasDirection.UP
            "DISTRIBUTION" -> BiasDirection.DOWN
            else -> BiasDirection.FLAT
        }
        val conf = when (radar.moneyFlowBias) {
            "ACCUMULATION", "DISTRIBUTION" -> 0.68
            "NEUTRAL" -> 0.42
            else -> 0.0
        }
        return BotSignal(
            id = "bot_radar",
            name = "Бот Chain Radar",
            category = BotCategory.RADAR,
            bias = bias,
            confidence = conf,
            summary = when (radar.moneyFlowBias) {
                "ACCUMULATION" -> "Преобладает накопление и приток ликвидности до графика."
                "DISTRIBUTION" -> "Преобладает распределение и выход крупного капитала."
                "NEUTRAL" -> "Ончейн-потоки и киты в балансе."
                else -> "Ончейн-лента обновляется…"
            },
            hasData = radar.moneyFlowBias != "INSUFFICIENT"
        )
    }

    // ---------------- Бот 5: Crypto Sentinel (Обнаружение аномалий и событий) ----------------
    fun detectSentinelEvents(
        symbol: String,
        bars: List<List<Double>>,
        market: AppState.MarketUi,
        crossSpreadBps: Double?,
        radar: ChainRadarSnapshot,
        news: JSONArray,
        nowMs: Long,
        venueSnap: MultiVenueDataBridge.VenueSnapshot? = null
    ): List<SentinelEvent> {
        val out = mutableListOf<SentinelEvent>()

        if (venueSnap != null && venueSnap.binanceWhaleSpikeUsd >= 90_000.0) {
            val wUsd = venueSnap.binanceWhaleSpikeUsd
            out += SentinelEvent(
                id = "bn_whale_${venueSnap.fetchedAtMs / 30_000L}",
                title = "Крупный принт Binance: $${compactUsd(wUsd)}",
                detail = "Зафиксирована агрессивная сделка в стакане Binance; скорость потока ${safeFmtDouble("%.1f", venueSnap.binanceTradeVelocityPerSec ?: 0.0, "0")} сд/с.",
                severity = if (wUsd >= 500_000.0) SentinelSeverity.CRITICAL else SentinelSeverity.WARNING,
                category = SentinelCategory.WHALE_TRANSFER,
                source = "Binance AggTrades",
                tsMs = nowMs,
                metricBadge = "$${compactUsd(wUsd)}"
            )
        }

        // 1. Статистические Z-score аномалии по реальным свечам (волатильность и объём)
        if (bars.size >= 30) {
            val window = bars.takeLast(30)
            val returns = window.dropLast(1).mapNotNull { b ->
                val o = b.getOrNull(1) ?: return@mapNotNull null
                val c = b.getOrNull(4) ?: return@mapNotNull null
                if (o > 0) (c - o) / o * 100.0 else null
            }
            val vols = window.dropLast(1).mapNotNull { it.getOrNull(5) }
            val lastBar = window.last()
            val lastO = lastBar.getOrNull(1) ?: 0.0
            val lastC = lastBar.getOrNull(4) ?: 0.0
            val lastV = lastBar.getOrNull(5) ?: 0.0
            val lastRet = if (lastO > 0) (lastC - lastO) / lastO * 100.0 else 0.0

            if (returns.size >= 15) {
                val meanR = returns.average()
                val stdR = stdDev(returns).coerceAtLeast(0.03)
                val zPrice = (lastRet - meanR) / stdR
                if (abs(zPrice) >= 2.3) {
                    out += SentinelEvent(
                        id = "shock_price_${lastBar[0].toLong()}",
                        title = "Резкий ценовой импульс %+.2f%% (Z=%.1fσ)".format(Locale.US, lastRet, zPrice),
                        detail = "Последняя свеча $symbol отклонилась от 30-барного среднего на %.1f стандартных отклонений — возможен стоп-ран или новостной выброс.".format(Locale.US, abs(zPrice)),
                        severity = if (abs(zPrice) >= 3.2) SentinelSeverity.CRITICAL else SentinelSeverity.WARNING,
                        category = SentinelCategory.VOLATILITY_SHOCK,
                        source = market.feedSource,
                        tsMs = nowMs,
                        metricBadge = "Z=%.1fσ".format(Locale.US, zPrice)
                    )
                }
            }

            if (vols.size >= 15) {
                val meanV = vols.average().coerceAtLeast(1e-6)
                val stdV = stdDev(vols).coerceAtLeast(meanV * 0.25)
                val zVol = (lastV - meanV) / stdV
                val ratio = lastV / meanV
                if (zVol >= 2.4 && ratio >= 2.2) {
                    out += SentinelEvent(
                        id = "shock_vol_${lastBar[0].toLong()}",
                        title = "Всплеск объёма x%.1f от нормы".format(Locale.US, ratio),
                        detail = "Текущий торговый объём по $symbol в %.1f раз выше среднего по последним 30 свечам — крупный участник вошёл или закрыл позицию.".format(Locale.US, ratio),
                        severity = if (ratio >= 4.0) SentinelSeverity.CRITICAL else SentinelSeverity.WARNING,
                        category = SentinelCategory.VOLUME_SPIKE,
                        source = market.feedSource,
                        tsMs = nowMs,
                        metricBadge = "x%.1f VOL".format(Locale.US, ratio)
                    )
                }
            }
        }

        // 2. Рассинхрон цен между биржами (Bybit / Binance / OKX)
        if (crossSpreadBps != null && abs(crossSpreadBps) >= 12.0) {
            out += SentinelEvent(
                id = "spread_cross",
                title = "Межбиржевой спред %.1f bps (%+.2f%%)".format(Locale.US, abs(crossSpreadBps), crossSpreadBps / 100.0),
                detail = "Разница цен между Binance и OKX превысила порог ликвидности — рынок испытывает локальный перекос стаканов.",
                severity = if (abs(crossSpreadBps) >= 30.0) SentinelSeverity.CRITICAL else SentinelSeverity.WARNING,
                category = SentinelCategory.EXCHANGE_SPREAD,
                source = "Binance / OKX",
                tsMs = nowMs,
                metricBadge = "%.0f bps".format(Locale.US, abs(crossSpreadBps))
            )
        }

        // 3. Каскад ликвидаций / экстремальный фандинг / перекос толпы
        val liq1h = market.liq1hUsd ?: 0.0
        if (liq1h >= 250_000.0) {
            out += SentinelEvent(
                id = "liq_wave",
                title = "Волна ликвидаций: $%s за 1ч".format(Locale.US, compactUsd(liq1h)),
                detail = "Зафиксированы принудительные закрытия позиций — возможна дозачистка плеч и резкий откат.",
                severity = if (liq1h >= 1_500_000.0) SentinelSeverity.CRITICAL else SentinelSeverity.WARNING,
                category = SentinelCategory.LIQUIDATION_WAVE,
                source = "Bybit WS",
                tsMs = nowMs,
                metricBadge = "$${compactUsd(liq1h)}"
            )
        }

        market.fundingPct?.let { f ->
            if (abs(f) >= 0.03) {
                out += SentinelEvent(
                    id = "funding_ext",
                    title = "Экстремальный фандинг: %+.4f%%".format(Locale.US, f),
                    detail = if (f > 0) "Высокая плата за лонги — риск сквиза вниз при малейшем откате."
                    else "Отрицательный фандинг — перегруженность шортами создаёт риск резкого выноса вверх.",
                    severity = SentinelSeverity.WARNING,
                    category = SentinelCategory.FUNDING_EXTREME,
                    source = "Деривативы",
                    tsMs = nowMs,
                    metricBadge = "%+.3f%%".format(Locale.US, f)
                )
            }
        }

        market.lsrPct?.let { pct ->
            if (pct >= 88.0 || pct <= 12.0) {
                out += SentinelEvent(
                    id = "lsr_ext",
                    title = "Экстремальный перекос толпы (LSR п%.0f)".format(Locale.US, pct),
                    detail = if (pct >= 88.0) "Розничные счета массово стоят в лонг — исторически зона уязвима к сбору стопов."
                    else "Розничные счета массово стоят в шорт — повышен шанс контртрендового импульса вверх.",
                    severity = SentinelSeverity.INFO,
                    category = SentinelCategory.CROWD_EXTREME,
                    source = "OKX / Bybit LSR",
                    tsMs = nowMs,
                    metricBadge = "п${pct.toInt()}"
                )
            }
        }

        // 4. Крупные ончейн-переводы >= 100 BTC в мемпуле
        radar.btcLargeTransfers.filter { it.amountBtc >= 100.0 }.take(2).forEach { tx ->
            val usdTxt = tx.usdApprox?.let { " (~$${compactUsd(it)})" } ?: ""
            out += SentinelEvent(
                id = "btc_tx_${tx.txHashShort}",
                title = "Крупный перевод %.0f BTC$usdTxt".format(Locale.US, tx.amountBtc),
                detail = "Неподтверждённая транзакция ${tx.txHashShort} (${tx.directionLabel}). Крупные переводы часто предшествуют всплеску волатильности.",
                severity = if (tx.amountBtc >= 500.0) SentinelSeverity.CRITICAL else SentinelSeverity.WARNING,
                category = SentinelCategory.WHALE_TRANSFER,
                source = "blockchain.info",
                tsMs = tx.tsSec * 1000L,
                metricBadge = "%.0f BTC".format(Locale.US, tx.amountBtc)
            )
        }

        // 5. Классификация живых RSS-новостей по событиям риска (взломы, разблоки, SEC/ETF/ФРС)
        for (i in 0 until min(news.length(), 15)) {
            val item = news.optJSONObject(i) ?: continue
            val title = item.optString("title")
            if (title.isBlank()) continue
            val low = title.lowercase(Locale.US)
            val src = item.optString("source", "RSS")
            val ts = item.optLong("published_ts").takeIf { it > 0 } ?: nowMs
            when {
                Regex("""hack|exploit|drain|breach|rug\s?pull|stolen|compromis|взлом|атак|утечк""").containsMatchIn(low) -> {
                    out += SentinelEvent(
                        id = "news_sec_$i",
                        title = title,
                        detail = "Событие безопасности из ленты $src. Проверьте влияние на волатильность и коррелированные протоколы.",
                        severity = SentinelSeverity.CRITICAL,
                        category = SentinelCategory.SECURITY_EXPLOIT,
                        source = src,
                        tsMs = ts,
                        metricBadge = "SECURITY"
                    )
                }
                Regex("""unlock|vesting|cliff|mint|burn|airdrop|разблок|эмисс""").containsMatchIn(low) -> {
                    out += SentinelEvent(
                        id = "news_unlock_$i",
                        title = title,
                        detail = "Событие предложения токенов из $src — возможен локальный навес продавцов.",
                        severity = SentinelSeverity.WARNING,
                        category = SentinelCategory.TOKEN_UNLOCK,
                        source = src,
                        tsMs = ts,
                        metricBadge = "SUPPLY"
                    )
                }
                Regex("""sec\b|etf|fed|fomc|cpi|inflation|lawsuit|ban|регул|фрс|ставк""").containsMatchIn(low) -> {
                    out += SentinelEvent(
                        id = "news_reg_$i",
                        title = title,
                        detail = "Макро или регуляторный триггер ($src), способный сместить общий аппетит к риску.",
                        severity = SentinelSeverity.INFO,
                        category = SentinelCategory.REGULATION_MACRO,
                        source = src,
                        tsMs = ts,
                        metricBadge = "MACRO"
                    )
                }
            }
        }

        return out.sortedWith(compareBy<SentinelEvent> { it.severity.ordinal }.thenByDescending { it.tsMs })
    }

    private fun runSentinelBot(events: List<SentinelEvent>): BotSignal {
        val critCount = events.count { it.severity == SentinelSeverity.CRITICAL }
        val warnCount = events.count { it.severity == SentinelSeverity.WARNING }
        val summary = when {
            critCount > 0 -> "Обнаружено критических аномалий: $critCount (предупреждений: $warnCount) — режим повышенного риска."
            warnCount > 0 -> "Аномалий внимания: $warnCount — рынок чувствителен к импульсам."
            events.isNotEmpty() -> "Зафиксировано инфо-событий: ${events.size}, критических угроз нет."
            else -> "Шоковых аномалий, всплесков ликвидаций и угроз не обнаружено."
        }
        return BotSignal(
            id = "bot_sentinel",
            name = "Бот Crypto Sentinel",
            category = BotCategory.SENTINEL,
            bias = BiasDirection.FLAT,
            confidence = if (critCount > 0) 0.85 else 0.50,
            summary = summary
        )
    }

    private fun pythonCouncilBot(pythonResult: JSONObject?): BotSignal? {
        if (pythonResult == null || !pythonResult.optBoolean("ok")) return null
        val pred = pythonResult.optJSONObject("prediction") ?: return null
        val side = pred.optString("side")
        val bias = when (side) {
            "UP" -> BiasDirection.UP
            "DOWN" -> BiasDirection.DOWN
            else -> BiasDirection.FLAT
        }
        val conf = pred.optDouble("confidence", 0.5).let { if (it.isNaN()) 0.5 else it }
        val councilSt = pythonResult.optJSONObject("council")?.optString("status") ?: "OK"
        return BotSignal(
            id = "bot_py_council",
            name = "Python-Совет (18 агентов + OCIE)",
            category = BotCategory.COUNCIL,
            bias = bias,
            confidence = conf,
            summary = "Вердикт Python-контура: $side ($councilSt, увер. ${(conf * 100).toInt()}%)"
        )
    }

    // ---------------- Бот 6: Кросс-биржевой консенсус (Bybit/Binance/OKX/Coinbase/Kraken) ----------------
    private fun runCrossVenueBot(
        bybitPrice: Double?,
        binancePrice: Double,
        okxPrice: Double,
        crossSpreadBps: Double?,
        venueSnap: MultiVenueDataBridge.VenueSnapshot?
    ): BotSignal {
        val prices = LinkedHashMap<String, Double>()
        if (bybitPrice != null && bybitPrice.isFinite() && bybitPrice > 0) prices["Bybit"] = bybitPrice
        if (binancePrice.isFinite() && binancePrice > 0) prices["Binance"] = binancePrice
        if (okxPrice.isFinite() && okxPrice > 0) prices["OKX"] = okxPrice
        venueSnap?.coinbasePrice?.takeIf { it.isFinite() && it > 0 }?.let { prices["Coinbase"] = it }
        venueSnap?.krakenPrice?.takeIf { it.isFinite() && it > 0 }?.let { prices["Kraken"] = it }

        if (prices.size < 2) {
            return BotSignal(
                id = "bot_cross_venue",
                name = "Бот-Кросс-Биржи",
                category = BotCategory.FLOW,
                bias = BiasDirection.FLAT,
                confidence = 0.0,
                summary = "Подключено площадок: ${prices.size} — ожидание мультибиржевого среза…",
                hasData = false
            )
        }
        val ref = bybitPrice ?: prices.values.first()
        val extAvg = prices.filterKeys { it != "Bybit" }.values.ifEmpty { prices.values }.average()
        val leadBps = if (ref > 0) (extAvg - ref) / ref * 10_000.0 else (crossSpreadBps ?: 0.0)
        val bias = when {
            leadBps >= 4.0 -> BiasDirection.UP
            leadBps <= -4.0 -> BiasDirection.DOWN
            else -> BiasDirection.FLAT
        }
        val conf = (0.35 + min(0.50, abs(leadBps) / 20.0)).coerceIn(0.30, 0.85)
        val spreadTxt = safeFmtDouble("%+.1f bps", leadBps, "0.0 bps")
        return BotSignal(
            id = "bot_cross_venue",
            name = "Бот-Кросс-Биржи (${prices.size} площ.)",
            category = BotCategory.FLOW,
            bias = bias,
            confidence = conf,
            summary = "Площадки: ${prices.keys.joinToString("/")} · отклонение $spreadTxt"
        )
    }

    // ---------------- Бот 7: Скорость потока и HFT-импульс ----------------
    private fun runVelocityBot(
        market: AppState.MarketUi,
        venueSnap: MultiVenueDataBridge.VenueSnapshot?
    ): BotSignal {
        val vel = venueSnap?.binanceTradeVelocityPerSec
        val buyPct = venueSnap?.binanceAggressiveBuyPct
        val tape = market.tape
        if ((vel == null || !vel.isFinite()) && (tape == null || tape.n < 8)) {
            return BotSignal(
                id = "bot_velocity",
                name = "Бот-Скорость Ленты",
                category = BotCategory.FLOW,
                bias = BiasDirection.FLAT,
                confidence = 0.0,
                summary = "Накопление высокочастотных тиков…",
                hasData = false
            )
        }
        var score = 0.0
        val parts = mutableListOf<String>()
        if (vel != null && buyPct != null && vel.isFinite() && buyPct.isFinite()) {
            val skew = buyPct - 50.0
            score += (skew / 25.0) * (vel / 10.0).coerceIn(0.5, 1.5)
            parts += "Binance ${safeFmtDouble("%.1f", vel)} сд/с (${safeFmtDouble("%.0f", buyPct)}% пок.)"
        }
        if (tape != null && tape.n >= 8 && tape.deltaPct.isFinite()) {
            score += tape.deltaPct / 30.0
            parts += "WS-лента ${safeFmtDouble("%+.1f%%", tape.deltaPct)}"
        }
        val bias = when {
            score >= 0.45 -> BiasDirection.UP
            score <= -0.45 -> BiasDirection.DOWN
            else -> BiasDirection.FLAT
        }
        val conf = (0.36 + min(0.52, abs(score) * 0.28)).coerceIn(0.30, 0.88)
        return BotSignal(
            id = "bot_velocity",
            name = "Бот-Скорость Ленты",
            category = BotCategory.FLOW,
            bias = bias,
            confidence = conf,
            summary = parts.joinToString(" · ")
        )
    }

    // ---------------- Бот 8: Глубина стакана и лимитные стены ----------------
    private fun runBookPressureBot(
        market: AppState.MarketUi,
        venueSnap: MultiVenueDataBridge.VenueSnapshot?
    ): BotSignal {
        var imbSum = 0.0
        var count = 0
        val notes = mutableListOf<String>()

        if (market.bids.isNotEmpty() && market.asks.isNotEmpty()) {
            val b = market.bids.sumOf { if (it.qty.isFinite()) it.qty else 0.0 }
            val a = market.asks.sumOf { if (it.qty.isFinite()) it.qty else 0.0 }
            if (b + a > 0.0) {
                val imb = 100.0 * (b - a) / (b + a)
                imbSum += imb
                count++
                notes += "Bybit стакан ${safeFmtDouble("%+.0f%%", imb)}"
            }
        }
        val cbB = venueSnap?.coinbaseBid
        val cbA = venueSnap?.coinbaseAsk
        if (cbB != null && cbA != null && cbB > 0 && cbA > 0) {
            notes += "Coinbase ${fmtPx(cbB)}/${fmtPx(cbA)}"
        }
        if (count == 0) {
            return BotSignal(
                id = "bot_book_pressure",
                name = "Бот-Глубина Стакана",
                category = BotCategory.FLOW,
                bias = BiasDirection.FLAT,
                confidence = 0.0,
                summary = "Ожидание стакана заявок…",
                hasData = false
            )
        }
        val avgImb = imbSum / count
        val bias = when {
            avgImb >= 14.0 -> BiasDirection.UP
            avgImb <= -14.0 -> BiasDirection.DOWN
            else -> BiasDirection.FLAT
        }
        val conf = (0.35 + min(0.45, abs(avgImb) / 60.0)).coerceIn(0.30, 0.80)
        return BotSignal(
            id = "bot_book_pressure",
            name = "Бот-Глубина Стакана",
            category = BotCategory.FLOW,
            bias = bias,
            confidence = conf,
            summary = notes.joinToString(" · ")
        )
    }

    // ---------------- Бот 9: Сквиз-давление и перегрев плеч ----------------
    private fun runSqueezeBot(market: AppState.MarketUi, bars: List<List<Double>>): BotSignal {
        var score = 0.0
        val notes = mutableListOf<String>()

        market.fundingPct?.takeIf { it.isFinite() }?.let { f ->
            if (abs(f) >= 0.025) {
                score += if (f < 0) 0.7 else -0.7
                notes += "funding ${safeFmtDouble("%+.4f%%", f)}"
            }
        }
        market.oiChange24hPct?.takeIf { it.isFinite() }?.let { oiCh ->
            if (abs(oiCh) >= 3.0) {
                notes += "OI 24ч ${safeFmtDouble("%+.1f%%", oiCh)}"
            }
        }
        val liq1h = market.liq1hUsd ?: 0.0
        if (liq1h.isFinite() && liq1h >= 150_000.0) {
            val ch = market.change24hPct ?: 0.0
            score += if (ch >= 0) 0.45 else -0.45
            notes += "ликв. 1ч $${compactUsd(liq1h)}"
        }
        if (bars.size >= 12) {
            val atr = computeAtrPct(bars, 12)
            if (atr < 0.22) {
                notes += "сжатие пружины ATR ${safeFmtDouble("%.2f%%", atr)}"
            }
        }
        val bias = when {
            score >= 0.45 -> BiasDirection.UP
            score <= -0.45 -> BiasDirection.DOWN
            else -> BiasDirection.FLAT
        }
        val conf = if (notes.isEmpty()) 0.0 else (0.38 + min(0.45, abs(score) * 0.4))
        return BotSignal(
            id = "bot_squeeze",
            name = "Бот-Сквиз и Плечи",
            category = BotCategory.STRUCTURE,
            bias = bias,
            confidence = conf,
            summary = if (notes.isEmpty()) "Плечи и ликвидации в пределах нормы" else notes.joinToString(" · "),
            hasData = notes.isNotEmpty()
        )
    }

    private fun runOrderFlowIntelligenceBot(of: OrderFlowCouncilPayload?): BotSignal? {
        if (of == null) return null
        if (!of.sufficientData) {
            return BotSignal(
                id = "bot_orderflow",
                name = "ENA Order Flow & Absorption",
                category = BotCategory.FLOW,
                bias = BiasDirection.FLAT,
                confidence = 0.0,
                summary = of.contradictingEvidence.firstOrNull() ?: "Ожидание синхронизированного потока сделок и DOM",
                hasData = false
            )
        }
        val bw = of.bullishBiasWeight.coerceIn(-0.25, 0.25)
        val bias = when {
            bw >= 0.06 -> BiasDirection.UP
            bw <= -0.06 -> BiasDirection.DOWN
            else -> BiasDirection.FLAT
        }
        // Жёстко ограничиваем уверенность в Совете (≤ 0.45), чтобы модуль не мог бесконтрольно перевесить существующие прогнозы и риск-фильтры
        val boundedConf = (0.18 + abs(bw) * 1.1).coerceIn(0.18, 0.45)
        val histStr = if (of.historicalSamples >= 8 && of.historicalReversalRatePct != null) {
            " · история (${of.historicalSamples} соб.): развороты ${"%.0f%%".format(Locale.US, of.historicalReversalRatePct)}"
        } else ""
        val lvlStr = of.levelPrice?.let { " у ${fmtPx(it)}" } ?: ""
        return BotSignal(
            id = "bot_orderflow",
            name = "ENA Order Flow & Absorption",
            category = BotCategory.FLOW,
            bias = bias,
            confidence = boundedConf,
            summary = "${of.detectedEvent}$lvlStr [${of.signalStrength}]$histStr",
            hasData = true
        )
    }

    // ---------------- Логический синтезатор (Разум) ----------------
    private fun synthesizeLogic(
        symbol: String,
        livePrice: Double?,
        activeTf: String,
        bars: List<List<Double>>,
        klinesByTf: Map<String, List<List<Double>>>,
        market: AppState.MarketUi,
        radar: ChainRadarSnapshot,
        sentinelEvents: List<SentinelEvent>,
        bots: List<BotSignal>,
        pythonResult: JSONObject?,
        learnedWeights: Map<String, Double> = emptyMap(),
        learning: LearningSnapshot = LearningSnapshot(),
        quantMetrics: QuantMathKernel.QuantTrendMetrics = QuantMathKernel.computeMetrics(bars),
        walkForward: QuantMathKernel.WalkForwardReport = QuantMathKernel.WalkForwardReport(0, null, null, null, null, null, 1.0),
        quantDiag: QuantDiagnostics = QuantDiagnostics(),
        orderFlow: OrderFlowCouncilPayload? = null
    ): TrendLogicSummary {
        val pyTrend = pythonResult?.takeIf { it.optBoolean("ok") }?.optJSONObject("trend")
        val pyForecast = pythonResult?.takeIf { it.optBoolean("ok") }?.optJSONObject("forecast")

        val directionalBots = bots.filter { it.category != BotCategory.SENTINEL && it.hasData }
        var upW = 0.0
        var downW = 0.0
        var flatW = 0.0
        directionalBots.forEach { b ->
            val mult = (learnedWeights[b.id] ?: 1.0).coerceIn(0.55, 1.55)
            val maxCap = if (b.id == "bot_orderflow") 0.45 else 1.55
            val w = (b.confidence.coerceIn(0.10, 1.0) * mult).coerceIn(0.10, maxCap)
            when (b.bias) {
                BiasDirection.UP -> upW += w
                BiasDirection.DOWN -> downW += w
                BiasDirection.FLAT -> flatW += w * 0.7
            }
        }
        val sumW = (upW + downW + flatW)
        var rawUp = if (sumW > 1e-6) upW / sumW else 1.0 / 3.0
        var rawDown = if (sumW > 1e-6) downW / sumW else 1.0 / 3.0
        var rawFlat = if (sumW > 1e-6) flatW / sumW else 1.0 / 3.0

        // Если Python-прогноз готов, смешиваем его вероятности с логическим консенсусом ботов
        if (pyForecast != null) {
            val pyUp = pyForecast.optDouble("p_up", Double.NaN)
            val pyFlat = pyForecast.optDouble("p_flat", Double.NaN)
            val pyDown = pyForecast.optDouble("p_down", Double.NaN)
            if (pyUp.isFinite() && pyFlat.isFinite() && pyDown.isFinite() && (pyUp + pyFlat + pyDown) > 0.5) {
                rawUp = 0.55 * pyUp + 0.45 * rawUp
                rawFlat = 0.55 * pyFlat + 0.45 * rawFlat
                rawDown = 0.55 * pyDown + 0.45 * rawDown
            }
        }

        // Численно устойчивая log-odds калибровка по Brier/Walk-Forward и качеству исходных данных
        val (pUp, pFlat, pDown) = QuantMathKernel.calibrateProbabilities(
            rawUp = rawUp,
            rawFlat = rawFlat,
            rawDown = rawDown,
            calibrationFactor = quantDiag.calibrationFactor,
            dataQualityScore = quantDiag.dataQualityScore
        )

        val mtfBot = bots.firstOrNull { it.id == "bot_mtf" }
        val structBot = bots.firstOrNull { it.id == "bot_struct" }
        val flowBot = bots.firstOrNull { it.id == "bot_flow" }
        val radarBot = bots.firstOrNull { it.id == "bot_radar" }

        val dominantBias = when {
            pUp - pDown >= 0.08 -> BiasDirection.UP
            pDown - pUp >= 0.08 -> BiasDirection.DOWN
            else -> BiasDirection.FLAT
        }
        val clarityBonus = if (quantMetrics.adx >= 25 && quantMetrics.regressionR2 >= 0.55) 8 else 0
        val strength = (abs(pUp - pDown) * 115 + (1.0 - pFlat) * 20 + clarityBonus).toInt().coerceIn(15, 96)

        val biasLabel = when (dominantBias) {
            BiasDirection.UP -> "BULLISH"
            BiasDirection.DOWN -> "BEARISH"
            BiasDirection.FLAT -> "NEUTRAL / RANGE"
        }

        val atrPct = computeAtrPct(bars, 14)
        val hasCritSentinel = sentinelEvents.any { it.severity == SentinelSeverity.CRITICAL }
        val isCleanTrend = quantMetrics.adx >= 23.0 && quantMetrics.efficiencyRatio >= 0.35 && quantMetrics.regressionR2 >= 0.45
        val phase = when {
            hasCritSentinel -> "ВОЛАТИЛЬНЫЙ ВЫБРОС / РИСК"
            dominantBias == BiasDirection.UP && isCleanTrend && flowBot?.bias == BiasDirection.UP -> "ЧИСТЫЙ ИМПУЛЬС ВВЕРХ"
            dominantBias == BiasDirection.UP && flowBot?.bias == BiasDirection.DOWN -> "РАСТЯНУТЫЙ РОСТ (ОТКАТ РИСК)"
            dominantBias == BiasDirection.UP -> "ВОСХОДЯЩАЯ СТРУКТУРА"
            dominantBias == BiasDirection.DOWN && isCleanTrend && flowBot?.bias == BiasDirection.DOWN -> "ЧИСТОЕ ДАВЛЕНИЕ ВНИЗ"
            dominantBias == BiasDirection.DOWN && flowBot?.bias == BiasDirection.UP -> "ОТСКОК ПРОТИВ НИСХОДЯЩЕГО ТРЕНДА"
            dominantBias == BiasDirection.DOWN -> "НИСХОДЯЩАЯ СТРУКТУРА"
            quantMetrics.efficiencyRatio < 0.22 && atrPct >= 0.5 -> "ШУМНЫЙ РАСПИЛ (НИЗКИЙ ER)"
            atrPct < 0.25 -> "СЖАТИЕ ВОЛАТИЛЬНОСТИ"
            else -> "ДИАПАЗОН / НАКОПЛЕНИЕ"
        }

        val conflicts = mutableListOf<String>()
        if (quantDiag.dataQualityScore < 0.70) {
            conflicts += "Снижено качество исходных свечей (${quantDiag.dataGrade}): ${quantDiag.dataNotes.joinToString(", ")}"
        }
        if (mtfBot != null && flowBot != null && mtfBot.bias != BiasDirection.FLAT && flowBot.bias != BiasDirection.FLAT && mtfBot.bias != flowBot.bias) {
            conflicts += "Тренд свечей (${mtfBot.bias.name}) расходится с живым потоком сделок (${flowBot.bias.name})"
        }
        if (mtfBot != null && radarBot != null && mtfBot.bias != BiasDirection.FLAT && radarBot.bias != BiasDirection.FLAT && mtfBot.bias != radarBot.bias) {
            conflicts += "График смотрит ${mtfBot.bias.name}, но деньги до графика (Chain Radar) идут в ${radarBot.bias.name}"
        }
        if (market.tape?.divergence == true) {
            conflicts += "Дивергенция цены и CVD: цена движется без поддержки агрессивных рыночных ордеров"
        }

        val agreeCount = directionalBots.count { it.bias == dominantBias || (dominantBias == BiasDirection.FLAT && it.bias == BiasDirection.FLAT) }
        val agreementPct = if (directionalBots.isEmpty()) 0 else (100 * agreeCount / directionalBots.size)

        val riskLevel = when {
            hasCritSentinel || atrPct >= 1.2 || quantDiag.dataQualityScore < 0.55 -> "HIGH"
            conflicts.isNotEmpty() || atrPct >= 0.6 -> "MEDIUM"
            else -> "LOW"
        }
        val entryQuality = when {
            hasCritSentinel || conflicts.size >= 2 || quantDiag.dataQualityScore < 0.55 -> "WEAK"
            dominantBias == BiasDirection.FLAT -> "WAIT"
            agreementPct >= 70 && isCleanTrend && conflicts.isEmpty() -> "GOOD"
            else -> "MODERATE"
        }

        val supportStr = structBot?.metrics?.get("support") ?: "—"
        val resistStr = structBot?.metrics?.get("resistance") ?: "—"
        val adxStr = "%.1f".format(Locale.US, quantMetrics.adx)
        val erStr = "%.2f".format(Locale.US, quantMetrics.efficiencyRatio)
        val r2Str = "%.2f".format(Locale.US, quantMetrics.regressionR2)
        val wfStr = if (walkForward.samples >= 6 && walkForward.hitRatePct != null && walkForward.brierScore != null) {
            val hrS = "%.0f%%".format(Locale.US, walkForward.hitRatePct)
            val brS = "%.2f".format(Locale.US, walkForward.brierScore)
            val calS = "%.2f".format(Locale.US, quantDiag.calibrationFactor)
            "OOS-тест (${walkForward.samples} окон): точность $hrS, Brier=$brS, калибратор ×$calS"
        } else {
            "История OOS: накапливается окно для калибровки (качество данных ${quantDiag.dataGrade})"
        }
        val clockStr = if (quantDiag.clockSynced) {
            "Часы: ${quantDiag.clockSource} (сдвиг ${quantDiag.clockOffsetMs}мс)"
        } else {
            "Часы устройства"
        }

        // Причинно-следственная цепочка рассуждений
        val causalChain = buildList {
            mtfBot?.let {
                add(
                    CausalStep(
                        index = 1,
                        observation = it.summary,
                        mechanism = "Согласованность младших и старших ТФ снижает риск ложного пробоя",
                        conclusion = "Базовый вектор по графику: ${it.bias.name}",
                        invalidation = "Слом EMA21 на старшем ТФ ${tfLabel(activeTf)}"
                    )
                )
            }
            structBot?.let {
                add(
                    CausalStep(
                        index = 2,
                        observation = "${it.summary} · ADX $adxStr · ER $erStr · R² $r2Str",
                        mechanism = "ADX, Kaufman ER и OLS R² отделяют чистый направленный тренд от случайного распила",
                        conclusion = if (isCleanTrend) {
                            "Чистая структура тренда; поддержка $supportStr · сопротивление $resistStr"
                        } else {
                            "Смешанная/шумная структура; границы $supportStr … $resistStr"
                        },
                        invalidation = "Закрепление цены за $supportStr / $resistStr с объёмом"
                    )
                )
            }
            add(
                CausalStep(
                    index = 3,
                    observation = "Chain Radar: ${radar.moneyFlowBias} (${radar.whaleTradesCount} сделок китов ≥$100K)",
                    mechanism = "Крупный спот и ликвидность стейблкоинов смещают баланс до того, как импульс виден на свечах",
                    conclusion = radar.preChartAnswer,
                    invalidation = "Разворот дельты китов на ±25% и более"
                )
            )
            flowBot?.let {
                add(
                    CausalStep(
                        index = 4,
                        observation = "Поток и деривативы: ${it.summary}",
                        mechanism = "Перекос фандинга и LSR создаёт принудительные ликвидации при движении против толпы",
                        conclusion = if (conflicts.isEmpty()) "Поток не противоречит основному сценарию" else "Есть конфликт: ${conflicts.first()}",
                        invalidation = "Резкая смена CVD или каскад ликвидаций"
                    )
                )
            }
            if (orderFlow != null && orderFlow.sufficientData) {
                val contraTxt = orderFlow.contradictingEvidence.firstOrNull()?.let { " (против: $it)" } ?: ""
                add(
                    CausalStep(
                        index = 5,
                        observation = "Order Flow & Absorption: ${orderFlow.detectedEvent} [${orderFlow.signalStrength}] · ${orderFlow.dataQuality}",
                        mechanism = "Оценка исполнения стакана vs снятия заявок, пополнения ликвидности и эффективности продвижения цены",
                        conclusion = (orderFlow.supportingEvidence.take(2).joinToString("; ") + contraTxt).ifBlank { orderFlow.detectedEvent },
                        invalidation = "Потеря синхронизации стакана или смена направленного OFI/Delta"
                    )
                )
            }
            add(
                CausalStep(
                    index = 6,
                    observation = "$wfStr · $clockStr",
                    mechanism = "Log-odds калибровка сжимает вероятности при расхождении с историческим Brier или задержке свечей",
                    conclusion = "Итоговые вероятности откалиброваны под реальную статистику и качество данных (${quantDiag.dataGrade})",
                    invalidation = "Деградация Brier > 0.30 или падение качества свечей ниже C"
                )
            )
        }

        val mtfMap = LinkedHashMap<String, String>()
        mtfBot?.metrics?.let { m ->
            m["1м"]?.let { mtfMap["micro"] = it }
            (m["15м"] ?: m["1ч"])?.let { mtfMap["short"] = it }
            m["1ч"]?.let { mtfMap["mid"] = it }
        }
        pyTrend?.optJSONObject("mtf")?.let { pyMtf ->
            listOf("micro", "short", "mid").forEach { k ->
                pyMtf.optJSONObject(k)?.optString("direction")?.takeIf { it.isNotBlank() }?.let { mtfMap[k] = it }
            }
        }

        val expectedMove = ((pUp - pDown) * max(0.25, atrPct * 1.4))
        val invalidations = buildList {
            if (livePrice != null) {
                add("Уход за структурную границу ($supportStr / $resistStr)")
            }
            if (conflicts.isNotEmpty()) add("Усиление расхождения цены и дельты объёма")
            add("Появление CRITICAL-события в Crypto Sentinel")
        }

        return TrendLogicSummary(
            biasLabel = biasLabel,
            phase = phase,
            strength = strength,
            entryQuality = entryQuality,
            riskLevel = riskLevel,
            pUp = pUp,
            pFlat = pFlat,
            pDown = pDown,
            expectedMovePct = expectedMove,
            agreementPct = agreementPct,
            mtfDirections = mtfMap,
            structureLabel = structBot?.metrics?.get("structure") ?: "RANGE",
            causalChain = causalChain,
            conflicts = conflicts,
            invalidationRules = invalidations
        )
    }

    /**
     * Свежий расчёт прогноза на 6ч или 24ч при каждом запросе (без устаревшего кэша).
     */
    fun computeOutlook(
        horizonHours: Int,
        symbol: String,
        klinesByTf: Map<String, List<List<Double>>>,
        market: AppState.MarketUi,
        crossSpreadBps: Double?,
        radarRaw: IntelApiClient.RawRadarData,
        pythonResult: JSONObject?,
        venueSnap: MultiVenueDataBridge.VenueSnapshot? = null,
        learning: LearningSnapshot = LearningSnapshot(),
        nowMs: Long = System.currentTimeMillis()
    ): OutlookSummary? {
        val report = analyze(
            symbol = symbol,
            klinesByTf = klinesByTf,
            market = market,
            crossSpreadBps = crossSpreadBps,
            radarRaw = radarRaw,
            pythonResult = pythonResult,
            nowMs = nowMs,
            learning = learning,
            venueSnap = venueSnap
        )
        return if (horizonHours <= 6) report.outlook6h else report.outlook24h
    }

    private fun computeOutlookFromClean(
        horizonHours: Int,
        cleanByTf: Map<String, List<List<Double>>>,
        trendLogic: TrendLogicSummary,
        quantDiag: QuantDiagnostics,
        market: AppState.MarketUi,
        radar: ChainRadarSnapshot,
        venueSnap: MultiVenueDataBridge.VenueSnapshot?,
        pythonResult: JSONObject?,
        nowMs: Long
    ): OutlookSummary? {
        val hBars = cleanByTf["60"].orEmpty()
        val f15Bars = cleanByTf["15"].orEmpty()
        val baseBars = if (hBars.size >= 12) hBars else (f15Bars.ifEmpty { cleanByTf.values.firstOrNull().orEmpty() })
        if (baseBars.size < 6) return null
        val stepHours = if (baseBars === hBars) 1.0 else if (baseBars === f15Bars) 0.25 else 1.0
        val rawAtrPct = computeAtrPctNullable(baseBars, 14) ?: return null
        val sigmaH = (rawAtrPct * sqrt(horizonHours.toDouble() / stepHours)).coerceIn(
            if (horizonHours <= 6) 0.45 else 0.90,
            if (horizonHours <= 6) 6.5 else 14.0
        )
        val thresholdPct = max(if (horizonHours <= 6) 0.25 else 0.50, sigmaH * 0.35)

        var rawUp = trendLogic.pUp
        var rawFlat = trendLogic.pFlat
        var rawDown = trendLogic.pDown

        // Учитываем давление деривативов и ончейн-поток для среднесрочного окна
        var derivEdge = 0.0
        val drivers = mutableListOf<String>()
        market.fundingPct?.takeIf { it.isFinite() }?.let { f ->
            if (abs(f) >= 0.025) {
                derivEdge += if (f < 0) 0.06 else -0.06
                drivers += "Funding ${safeFmtDouble("%+.4f%%", f)}"
            }
        }
        market.lsrPct?.takeIf { it.isFinite() }?.let { p ->
            if (p >= 85.0) {
                derivEdge -= 0.05
                drivers += "LSR толпа перегрета в лонг (п${p.toInt()})"
            } else if (p <= 15.0) {
                derivEdge += 0.05
                drivers += "LSR толпа в шорт (п${p.toInt()})"
            }
        }
        if (radar.moneyFlowBias == "ACCUMULATION") {
            derivEdge += 0.06
            drivers += "Chain Radar: накопление крупного капитала"
        } else if (radar.moneyFlowBias == "DISTRIBUTION") {
            derivEdge -= 0.06
            drivers += "Chain Radar: распределение ликвидности"
        }
        venueSnap?.binanceAggressiveBuyPct?.takeIf { it.isFinite() }?.let { bp ->
            if (abs(bp - 50.0) >= 12.0) {
                derivEdge += ((bp - 50.0) / 100.0) * 0.14
                drivers += "Поток Binance: ${safeFmtDouble("%.0f", bp)}% агрессивных покупок"
            }
        }

        // Если Python-контур посчитал свежий outlook_6h / outlook_24h, смешиваем веса
        val pyKey = if (horizonHours <= 6) "outlook_6h" else "outlook_24h"
        val pyOut = pythonResult?.takeIf { it.optBoolean("ok") }?.optJSONObject(pyKey)
        if (pyOut != null && pyOut.optBoolean("ok", true)) {
            val pu = pyOut.optDouble("p_up", Double.NaN)
            val pf = pyOut.optDouble("p_flat", Double.NaN)
            val pd = pyOut.optDouble("p_down", Double.NaN)
            if (pu.isFinite() && pf.isFinite() && pd.isFinite() && (pu + pf + pd) > 0.5) {
                rawUp = 0.55 * pu + 0.45 * rawUp
                rawFlat = 0.55 * pf + 0.45 * rawFlat
                rawDown = 0.55 * pd + 0.45 * rawDown
            }
        }

        val shiftedUp = (rawUp + derivEdge).coerceIn(0.02, 0.94)
        val shiftedDown = (rawDown - derivEdge).coerceIn(0.02, 0.94)
        val horScale = if (horizonHours <= 6) quantDiag.calibrationFactor else (quantDiag.calibrationFactor * 0.94).coerceIn(0.45, 1.15)
        val (pUp, pFlat, pDown) = QuantMathKernel.calibrateProbabilities(
            rawUp = shiftedUp,
            rawFlat = rawFlat.coerceIn(0.04, 0.80),
            rawDown = shiftedDown,
            calibrationFactor = horScale,
            dataQualityScore = quantDiag.dataQualityScore
        )

        val expectedMove = ((pUp - pDown) * sigmaH * 0.85)
        val rangeLow = expectedMove - 1.28 * sigmaH
        val rangeHigh = expectedMove + 1.28 * sigmaH
        val side = when {
            pUp - pDown >= 0.07 -> "UP"
            pDown - pUp >= 0.07 -> "DOWN"
            else -> "FLAT"
        }
        val calTxt = if (quantDiag.walkForwardSamples >= 5 && quantDiag.walkForwardHitPct != null) {
            "OOS ${quantDiag.walkForwardSamples} окон · Brier ${safeFmtDouble("%.2f", quantDiag.brierScore ?: 0.25)}"
        } else {
            "Log-odds калибровка (Grade ${quantDiag.dataGrade})"
        }
        if (drivers.isEmpty()) {
            drivers += "MTF баланс и структура цены (${trendLogic.structureLabel})"
            drivers += "Волатильность σ(${horizonHours}ч) ≈ ${safeFmtDouble("%.2f%%", sigmaH)}"
        }
        return OutlookSummary(
            horizonHours = horizonHours,
            side = side,
            pUp = pUp,
            pFlat = pFlat,
            pDown = pDown,
            expectedMovePct = expectedMove,
            sigmaPct = sigmaH,
            rangeLowPct = rangeLow,
            rangeHighPct = rangeHigh,
            thresholdPct = thresholdPct,
            calibrationLabel = calTxt,
            drivers = drivers.take(4),
            invalidationRules = trendLogic.invalidationRules,
            computedAtMs = nowMs
        )
    }

    private fun buildVenueSummary(
        bybitPrice: Double?,
        binancePrice: Double,
        okxPrice: Double,
        crossSpreadBps: Double?,
        venueSnap: MultiVenueDataBridge.VenueSnapshot?
    ): VenueSummary {
        val names = mutableListOf<String>()
        if (bybitPrice != null && bybitPrice.isFinite() && bybitPrice > 0) names += "Bybit"
        if (binancePrice.isFinite() && binancePrice > 0) names += "Binance"
        if (okxPrice.isFinite() && okxPrice > 0) names += "OKX"
        if (venueSnap?.coinbasePrice != null) names += "Coinbase"
        if (venueSnap?.krakenPrice != null) names += "Kraken"
        return VenueSummary(
            activeVenues = names.size,
            venueNames = names,
            coinbasePrice = venueSnap?.coinbasePrice,
            krakenPrice = venueSnap?.krakenPrice,
            binanceTradeVelPerSec = venueSnap?.binanceTradeVelocityPerSec,
            binanceAggrBuyPct = venueSnap?.binanceAggressiveBuyPct,
            maxSpreadBps = crossSpreadBps
        )
    }

    // ---------------- Математика ----------------
    private fun chooseBestSeries(map: Map<String, List<List<Double>>>): String {
        for (tf in listOf("15", "5", "60", "1", "3", "240", "30", "D")) {
            if ((map[tf]?.size ?: 0) >= 25) return tf
        }
        return map.entries.maxByOrNull { it.value.size }?.key ?: "60"
    }

    private fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val k = 2.0 / (period + 1)
        var e = values.first()
        for (i in 1 until values.size) {
            e = values[i] * k + e * (1.0 - k)
        }
        return e
    }

    private fun computeAtrPctNullable(bars: List<List<Double>>, period: Int): Double? {
        if (bars.size < 3) return null
        val slice = bars.takeLast(min(bars.size, period + 1))
        var sumTrPct = 0.0
        var n = 0
        for (i in 1 until slice.size) {
            val prevC = slice[i - 1].getOrNull(4) ?: continue
            val h = slice[i].getOrNull(2) ?: continue
            val l = slice[i].getOrNull(3) ?: continue
            if (prevC <= 0.0 || !h.isFinite() || !l.isFinite() || h < l) continue
            val tr = maxOf(h - l, abs(h - prevC), abs(l - prevC))
            sumTrPct += tr / prevC * 100.0
            n++
        }
        return if (n == 0) null else sumTrPct / n
    }

    private fun computeAtrPct(bars: List<List<Double>>, period: Int): Double =
        computeAtrPctNullable(bars, period) ?: 0.0

    private fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = values.average()
        val v = values.sumOf { (it - m).pow(2) } / (values.size - 1)
        return sqrt(v)
    }

    private fun tfLabel(tf: String): String = when (tf) {
        "1" -> "1м"
        "3" -> "3м"
        "5" -> "5м"
        "15" -> "15м"
        "30" -> "30м"
        "60" -> "1ч"
        "240" -> "4ч"
        "D" -> "1Д"
        else -> tf
    }

    private fun dirSymbol(d: BiasDirection): String = when (d) {
        BiasDirection.UP -> "↑"
        BiasDirection.DOWN -> "↓"
        BiasDirection.FLAT -> "→"
    }

    private fun fmtPx(p: Double): String =
        if (p >= 1000) "%,.1f".format(Locale.US, p)
        else if (p >= 10) "%,.2f".format(Locale.US, p)
        else "%.4f".format(Locale.US, p)

    fun compactUsd(v: Double): String = when {
        v >= 1e9 -> "%.2fB".format(Locale.US, v / 1e9)
        v >= 1e6 -> "%.2fM".format(Locale.US, v / 1e6)
        v >= 1e3 -> "%.1fK".format(Locale.US, v / 1e3)
        else -> "%.0f".format(Locale.US, v)
    }
}
