package com.enamind.ai.orderflow

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Историческая память и обучение Order Flow.
 *
 * Правила:
 *  - Каждое обнаруженное событие сохраняется в `order_flow_events/events.jsonl`;
 *  - Фактическая реакция цены проверяется СТРОГО вперёд во времени через 1с, 5с, 15с, 30с
 *    и расширенный горизонт таймфрейма (priceTf / reactionTfPct) без заглядывания в будущее;
 *  - Порог FLAT ~0.22% (с учётом комиссий) применяется ТОЛЬКО к расширенным ТФ-горизонтам (3m/5m/15m+),
 *    тогда как на минутном / 30s-окне сохраняется чувствительный порог 0.045%, чтобы не обнулить
 *    калибровку на минутном шуме.
 */
class OrderFlowEventVault(
    private val primaryDir: File,
    private val mirrorDir: File? = null
) {
    data class RecordedEvent(
        val id: String,
        val symbol: String,
        val exchange: String,
        val interval: String,
        val detectedAtMs: Long,
        val entryPrice: Double,
        val levelPrice: Double?,
        val eventType: String,           // BUYER_ABSORPTION / SELLER_ABSORPTION / CONFIRMED_REJECTION / CONFIRMED_BREAKOUT
        val isResistance: Boolean,
        val grade: String,
        val deltaPct: Double,
        val normalizedOfi: Double,
        val absorptionRatio: Double,
        val progressEfficiency: Double,
        val replenishmentRate: Double,
        val levelPersistence: Double,
        val repeatedAttacks: Int,
        val supportingFactors: List<String>,
        val conflictingFactors: List<String>,
        val scenarioASummary: String,
        val scenarioBSummary: String,
        // Пост-проверка строго вперёд во времени:
        val price1s: Double? = null,
        val price5s: Double? = null,
        val price15s: Double? = null,
        val price30s: Double? = null,
        val priceTf: Double? = null,
        val reaction1sPct: Double? = null,
        val reaction5sPct: Double? = null,
        val reaction15sPct: Double? = null,
        val reaction30sPct: Double? = null,
        val reactionTfPct: Double? = null,
        val verifiedOutcome: String = "PENDING" // PENDING | REVERSAL | BREAKOUT | FLAT
    )

    data class EmpiricalStats(
        val totalRecorded: Int = 0,
        val verifiedEvents: Int = 0,
        val reversalCount: Int = 0,
        val breakoutCount: Int = 0,
        val reversalRatePct: Double? = null,
        val breakoutRatePct: Double? = null,
        val absorptionConfirmationRatePct: Double? = null,
        val informativeFeatures: List<String> = emptyList(),
        val failureRegimes: List<String> = emptyList(),
        val bestCombinations: List<String> = emptyList(),
        val topFeatureSummary: String = "Накопление проверенных событий…"
    )

    data class ExportBundle(
        val jsonText: String,
        val csvText: String,
        val eventCount: Int,
        val verifiedCount: Int
    )

    private val lock = Any()
    private val events = ArrayList<RecordedEvent>()

    fun ensureInitialized() {
        synchronized(lock) {
            primaryDir.mkdirs()
            mirrorDir?.mkdirs()
            loadLocked()
        }
    }

    fun recordEvent(report: OrderFlowReport) {
        val px = report.price ?: return
        if (!px.isFinite() || px <= 0.0) return
        val significant = report.absorption.detected ||
            report.levelAttack.phase == LevelAttackPhase.CONFIRMED_REJECTION ||
            report.levelAttack.phase == LevelAttackPhase.CONFIRMED_BREAKOUT
        if (!significant) return

        val evType = when {
            report.levelAttack.phase == LevelAttackPhase.CONFIRMED_BREAKOUT -> "CONFIRMED_BREAKOUT"
            report.levelAttack.phase == LevelAttackPhase.CONFIRMED_REJECTION -> "CONFIRMED_REJECTION"
            report.absorption.side != AbsorptionSide.NONE -> report.absorption.side.name
            else -> return
        }

        val now = report.computedAtMs
        synchronized(lock) {
            val last = events.lastOrNull()
            if (last != null && last.symbol == report.symbol && last.eventType == evType &&
                (now - last.detectedAtMs) < 20_000L
            ) {
                return
            }

            val rec = RecordedEvent(
                id = "${report.symbol}_${now}_${evType}",
                symbol = report.symbol,
                exchange = report.exchangeSource,
                interval = report.interval,
                detectedAtMs = now,
                entryPrice = px,
                levelPrice = report.levelAttack.levelPrice ?: report.absorption.targetLevelPrice,
                eventType = evType,
                isResistance = report.levelAttack.isResistance,
                grade = report.confidence.grade.name,
                deltaPct = report.footprint.deltaPct,
                normalizedOfi = report.dom.normalizedOfi,
                absorptionRatio = report.absorption.absorptionRatio,
                progressEfficiency = report.absorption.priceProgressEfficiency,
                replenishmentRate = report.absorption.liquidityReplenishmentRate,
                levelPersistence = report.absorption.levelPersistence,
                repeatedAttacks = report.absorption.repeatedAttacks,
                supportingFactors = report.absorption.supportingFactors,
                conflictingFactors = report.absorption.conflictingFactors,
                scenarioASummary = report.scenarioA?.title ?: "",
                scenarioBSummary = report.scenarioB?.title ?: ""
            )
            events.add(rec)
            if (events.size > MAX_EVENTS) {
                events.removeAt(0)
            }
            persistLocked()
        }
    }

    /**
     * Строго forward-only проверка исходов через 1с, 5с, 15с, 30с и горизонт ТФ (priceTf / reactionTfPct).
     *  - На 30s-горизонте (и для ТФ "1") используется порог 0.045%.
     *  - На расширенном ТФ-горизонте (3m/5m/15m+ -> horizonMs >= 180_000L), как только наступает horizonMs,
     *    итог пересчитывается по reactionTfPct с fee-aware порогом FLAT = 0.22%.
     */
    fun verifyForwardOutcomes(
        symbol: String,
        futureTicks: List<OrderFlowStreamHub.RawTradeTick>,
        nowMs: Long,
        bars: List<List<Double>> = emptyList()
    ) {
        if (futureTicks.isEmpty() && bars.isEmpty()) return
        synchronized(lock) {
            var changed = false
            for (idx in events.indices) {
                val ev = events[idx]
                if (ev.symbol != symbol) continue

                val horizonMs = intervalToHorizonMs(ev.interval)
                val isExtendedTf = horizonMs >= 180_000L
                val tfComplete = ev.priceTf != null
                if (ev.verifiedOutcome != "PENDING" && (!isExtendedTf || tfComplete)) continue

                val after = futureTicks.filter { it.exchangeTsMs > ev.detectedAtMs }
                if (after.isEmpty() && bars.isEmpty() && (nowMs - ev.detectedAtMs) < 35_000L) continue

                fun tickAtOffset(offsetMs: Long): Double? {
                    val targetTs = ev.detectedAtMs + offsetMs
                    val exact = after.firstOrNull { it.exchangeTsMs >= targetTs }
                    if (exact != null) return exact.price
                    return if (after.isNotEmpty() && nowMs >= targetTs + 5_000L) after.last().price else null
                }

                fun priceAtTfHorizon(): Double? {
                    val targetTs = ev.detectedAtMs + horizonMs
                    val fromTicks = after.firstOrNull { it.exchangeTsMs >= targetTs }?.price
                    if (fromTicks != null) return fromTicks
                    if (bars.isNotEmpty() && nowMs >= targetTs) {
                        val bar = bars.firstOrNull { (it.getOrNull(0)?.toLong() ?: 0L) >= targetTs }
                        val close = bar?.getOrNull(4)?.takeIf { it.isFinite() && it > 0.0 }
                        if (close != null) return close
                        if (nowMs >= targetTs + 15_000L) {
                            return after.lastOrNull()?.price ?: bars.lastOrNull()?.getOrNull(4)?.takeIf { it.isFinite() && it > 0.0 }
                        }
                    }
                    return null
                }

                val p1 = ev.price1s ?: tickAtOffset(1_000L)
                val p5 = ev.price5s ?: tickAtOffset(5_000L)
                val p15 = ev.price15s ?: tickAtOffset(15_000L)
                val p30 = ev.price30s ?: tickAtOffset(30_000L)
                val pTf = ev.priceTf ?: if (isExtendedTf) priceAtTfHorizon() else null

                fun pct(p: Double?): Double? = p?.let { 100.0 * (it - ev.entryPrice) / ev.entryPrice }

                val r1 = pct(p1)
                val r5 = pct(p5)
                val r15 = pct(p15)
                val r30 = pct(p30)
                val rTf = pct(pTf)

                val isResistanceOutcome = resolveIsResistance(ev)

                val finalOutcome = when {
                    isExtendedTf && rTf != null -> classifyOutcome(isResistanceOutcome, rTf, EXTENDED_TF_FLAT_THRESHOLD_PCT)
                    r30 != null -> classifyOutcome(isResistanceOutcome, r30, SHORT_WINDOW_FLAT_THRESHOLD_PCT)
                    else -> ev.verifiedOutcome
                }

                if (p1 != ev.price1s || p5 != ev.price5s || p15 != ev.price15s ||
                    p30 != ev.price30s || pTf != ev.priceTf || finalOutcome != ev.verifiedOutcome
                ) {
                    events[idx] = ev.copy(
                        price1s = p1,
                        price5s = p5,
                        price15s = p15,
                        price30s = p30,
                        priceTf = pTf,
                        reaction1sPct = r1,
                        reaction5sPct = r5,
                        reaction15sPct = r15,
                        reaction30sPct = r30,
                        reactionTfPct = rTf,
                        verifiedOutcome = finalOutcome
                    )
                    changed = true
                }
            }
            if (changed) persistLocked()
        }
    }

    private fun resolveIsResistance(ev: RecordedEvent): Boolean = when (ev.eventType) {
        "SELLER_ABSORPTION" -> true
        "BUYER_ABSORPTION" -> false
        else -> ev.isResistance
    }

    private fun classifyOutcome(isResistance: Boolean, reactionPct: Double, thresholdPct: Double): String = when {
        isResistance && reactionPct <= -thresholdPct -> "REVERSAL"
        isResistance && reactionPct >= thresholdPct -> "BREAKOUT"
        !isResistance && reactionPct >= thresholdPct -> "REVERSAL"
        !isResistance && reactionPct <= -thresholdPct -> "BREAKOUT"
        else -> "FLAT"
    }

    fun computeStats(symbolFilter: String? = null): EmpiricalStats {
        synchronized(lock) {
            val all = if (symbolFilter.isNullOrBlank()) events else events.filter { it.symbol == symbolFilter }
            val verified = all.filter { it.verifiedOutcome != "PENDING" }
            if (verified.isEmpty()) {
                return EmpiricalStats(totalRecorded = all.size, verifiedEvents = 0)
            }
            val revCount = verified.count { it.verifiedOutcome == "REVERSAL" }
            val brkCount = verified.count { it.verifiedOutcome == "BREAKOUT" }
            val absEvents = verified.filter { it.eventType.contains("ABSORPTION") }
            val absRevRate = if (absEvents.isNotEmpty()) {
                100.0 * absEvents.count { it.verifiedOutcome == "REVERSAL" } / absEvents.size
            } else null

            val revPct = 100.0 * revCount / verified.size
            val brkPct = 100.0 * brkCount / verified.size

            val highRepRev = verified.filter { it.replenishmentRate >= 0.30 }
            val highRepRate = if (highRepRev.size >= 3) {
                "Пополнение ≥0.30 -> разворот ${"%.0f%%".format(Locale.US, 100.0 * highRepRev.count { it.verifiedOutcome == "REVERSAL" } / highRepRev.size)}"
            } else null

            val lowEffRev = verified.filter { it.progressEfficiency <= 0.25 }
            val lowEffRate = if (lowEffRev.size >= 3) {
                "Низкий прогресс цены ≤0.25 -> разворот ${"%.0f%%".format(Locale.US, 100.0 * lowEffRev.count { it.verifiedOutcome == "REVERSAL" } / lowEffRev.size)}"
            } else null

            val features = listOfNotNull(highRepRate, lowEffRate)
            val failureRegimes = mutableListOf<String>()
            val conflictFails = verified.filter { it.conflictingFactors.size >= 2 && it.verifiedOutcome == "BREAKOUT" }
            if (conflictFails.size >= 2) {
                failureRegimes += "Ошибки поглощения при ≥2 противоречащих признаках (${conflictFails.size} пробоев)"
            }
            val comboList = if (highRepRate != null && lowEffRate != null) {
                listOf("Пополнение ≥0.30 + слабый прогресс цены")
            } else emptyList()

            return EmpiricalStats(
                totalRecorded = all.size,
                verifiedEvents = verified.size,
                reversalCount = revCount,
                breakoutCount = brkCount,
                reversalRatePct = revPct,
                breakoutRatePct = brkPct,
                absorptionConfirmationRatePct = absRevRate,
                informativeFeatures = features,
                failureRegimes = failureRegimes,
                bestCombinations = comboList,
                topFeatureSummary = features.firstOrNull() ?: "Накоплено ${verified.size} проверенных событий"
            )
        }
    }

    /**
     * Экспорт полной истории Order Flow + 1с/5с/15с/30с/ТФ + статистики разворотов для кнопки «Скачать историю».
     */
    fun exportHistoryBundle(): ExportBundle {
        synchronized(lock) {
            val stats = computeStats(null)
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val arr = JSONArray()
            val csv = StringBuilder()
            csv.appendLine("id,symbol,exchange,interval,detected_utc,event_type,grade,entry_price,level_price,delta_pct,norm_ofi,absorption_ratio,progress_eff,replenish_rate,persistence,repeated_attacks,reaction_1s_pct,reaction_5s_pct,reaction_15s_pct,reaction_30s_pct,reaction_tf_pct,verified_outcome")

            for (ev in events) {
                arr.put(toJson(ev))
                val dt = iso.format(Date(ev.detectedAtMs))
                csv.appendLine(
                    listOf(
                        csvCell(ev.id),
                        csvCell(ev.symbol),
                        csvCell(ev.exchange),
                        csvCell(ev.interval),
                        csvCell(dt),
                        csvCell(ev.eventType),
                        csvCell(ev.grade),
                        ev.entryPrice.toString(),
                        ev.levelPrice?.toString() ?: "",
                        fmt(ev.deltaPct),
                        fmt(ev.normalizedOfi),
                        fmt(ev.absorptionRatio),
                        fmt(ev.progressEfficiency),
                        fmt(ev.replenishmentRate),
                        fmt(ev.levelPersistence),
                        ev.repeatedAttacks.toString(),
                        ev.reaction1sPct?.let { fmt(it) } ?: "",
                        ev.reaction5sPct?.let { fmt(it) } ?: "",
                        ev.reaction15sPct?.let { fmt(it) } ?: "",
                        ev.reaction30sPct?.let { fmt(it) } ?: "",
                        ev.reactionTfPct?.let { fmt(it) } ?: "",
                        csvCell(ev.verifiedOutcome)
                    ).joinToString(",")
                )
            }

            val rootJson = JSONObject()
                .put("exported_at_utc", iso.format(Date()))
                .put("total_events", events.size)
                .put("verified_events", stats.verifiedEvents)
                .put("reversal_count", stats.reversalCount)
                .put("breakout_count", stats.breakoutCount)
                .put("reversal_rate_pct", stats.reversalRatePct ?: JSONObject.NULL)
                .put("breakout_rate_pct", stats.breakoutRatePct ?: JSONObject.NULL)
                .put("informative_features", JSONArray(stats.informativeFeatures))
                .put("failure_regimes", JSONArray(stats.failureRegimes))
                .put("best_combinations", JSONArray(stats.bestCombinations))
                .put("events", arr)
                .toString(2)

            return ExportBundle(
                jsonText = rootJson,
                csvText = csv.toString(),
                eventCount = events.size,
                verifiedCount = stats.verifiedEvents
            )
        }
    }

    private fun csvCell(s: String): String = "\"${s.replace("\"", "\"\"")}\""
    private fun fmt(v: Double): String = if (v.isFinite()) String.format(Locale.US, "%.4f", v) else ""

    private fun loadLocked() {
        events.clear()
        val f = File(primaryDir, FILE_NAME)
        if (!f.exists()) return
        runCatching {
            f.readLines(Charsets.UTF_8).forEach { line ->
                val t = line.trim()
                if (t.isNotEmpty()) {
                    fromJson(JSONObject(t))?.let { events.add(it) }
                }
            }
            if (events.size > MAX_EVENTS) {
                val keep = events.takeLast(MAX_EVENTS)
                events.clear()
                events.addAll(keep)
            }
        }.onFailure { e ->
            Log.w("OrderFlowVault", "load: ${e.message}")
        }
    }

    private fun persistLocked() {
        runCatching {
            val content = buildString {
                for (ev in events) {
                    append(toJson(ev).toString()).append('\n')
                }
            }
            File(primaryDir, FILE_NAME).writeText(content, Charsets.UTF_8)
            mirrorDir?.let { File(it, FILE_NAME).writeText(content, Charsets.UTF_8) }
        }.onFailure { e ->
            Log.w("OrderFlowVault", "persist: ${e.message}")
        }
    }

    private fun toJson(ev: RecordedEvent): JSONObject = JSONObject().apply {
        put("id", ev.id)
        put("symbol", ev.symbol)
        put("exchange", ev.exchange)
        put("interval", ev.interval)
        put("detectedAtMs", ev.detectedAtMs)
        put("entryPrice", ev.entryPrice)
        put("levelPrice", ev.levelPrice ?: JSONObject.NULL)
        put("eventType", ev.eventType)
        put("isResistance", ev.isResistance)
        put("grade", ev.grade)
        put("deltaPct", ev.deltaPct)
        put("normalizedOfi", ev.normalizedOfi)
        put("absorptionRatio", ev.absorptionRatio)
        put("progressEfficiency", ev.progressEfficiency)
        put("replenishmentRate", ev.replenishmentRate)
        put("levelPersistence", ev.levelPersistence)
        put("repeatedAttacks", ev.repeatedAttacks)
        put("supportingFactors", JSONArray(ev.supportingFactors))
        put("conflictingFactors", JSONArray(ev.conflictingFactors))
        put("scenarioASummary", ev.scenarioASummary)
        put("scenarioBSummary", ev.scenarioBSummary)
        put("price1s", ev.price1s ?: JSONObject.NULL)
        put("price5s", ev.price5s ?: JSONObject.NULL)
        put("price15s", ev.price15s ?: JSONObject.NULL)
        put("price30s", ev.price30s ?: JSONObject.NULL)
        put("priceTf", ev.priceTf ?: JSONObject.NULL)
        put("reaction1sPct", ev.reaction1sPct ?: JSONObject.NULL)
        put("reaction5sPct", ev.reaction5sPct ?: JSONObject.NULL)
        put("reaction15sPct", ev.reaction15sPct ?: JSONObject.NULL)
        put("reaction30sPct", ev.reaction30sPct ?: JSONObject.NULL)
        put("reactionTfPct", ev.reactionTfPct ?: JSONObject.NULL)
        put("verifiedOutcome", ev.verifiedOutcome)
    }

    private fun fromJson(o: JSONObject): RecordedEvent? {
        val id = o.optString("id")
        val sym = o.optString("symbol")
        val entry = o.optDouble("entryPrice", Double.NaN)
        if (id.isBlank() || sym.isBlank() || !entry.isFinite() || entry <= 0.0) return null

        fun optNullableDouble(k: String): Double? =
            if (o.isNull(k)) null else o.optDouble(k, Double.NaN).takeIf { it.isFinite() }

        fun optStrList(k: String): List<String> {
            val a = o.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
        }

        return RecordedEvent(
            id = id,
            symbol = sym,
            exchange = o.optString("exchange", ""),
            interval = o.optString("interval", "60"),
            detectedAtMs = o.optLong("detectedAtMs", 0L),
            entryPrice = entry,
            levelPrice = optNullableDouble("levelPrice"),
            eventType = o.optString("eventType", "NONE"),
            isResistance = o.optBoolean("isResistance", true),
            grade = o.optString("grade", "WEAK"),
            deltaPct = o.optDouble("deltaPct", 0.0),
            normalizedOfi = o.optDouble("normalizedOfi", 0.0),
            absorptionRatio = o.optDouble("absorptionRatio", 0.0),
            progressEfficiency = o.optDouble("progressEfficiency", 0.0),
            replenishmentRate = o.optDouble("replenishmentRate", 0.0),
            levelPersistence = o.optDouble("levelPersistence", 0.0),
            repeatedAttacks = o.optInt("repeatedAttacks", 0),
            supportingFactors = optStrList("supportingFactors"),
            conflictingFactors = optStrList("conflictingFactors"),
            scenarioASummary = o.optString("scenarioASummary", ""),
            scenarioBSummary = o.optString("scenarioBSummary", ""),
            price1s = optNullableDouble("price1s"),
            price5s = optNullableDouble("price5s"),
            price15s = optNullableDouble("price15s"),
            price30s = optNullableDouble("price30s"),
            priceTf = optNullableDouble("priceTf"),
            reaction1sPct = optNullableDouble("reaction1sPct"),
            reaction5sPct = optNullableDouble("reaction5sPct"),
            reaction15sPct = optNullableDouble("reaction15sPct"),
            reaction30sPct = optNullableDouble("reaction30sPct"),
            reactionTfPct = optNullableDouble("reactionTfPct"),
            verifiedOutcome = o.optString("verifiedOutcome", "PENDING")
        )
    }

    companion object {
        private const val FILE_NAME = "events.jsonl"
        private const val MAX_EVENTS = 1500
        internal const val SHORT_WINDOW_FLAT_THRESHOLD_PCT = 0.045
        internal const val EXTENDED_TF_FLAT_THRESHOLD_PCT = 0.22

        fun intervalToHorizonMs(interval: String): Long = when (interval.trim()) {
            "1" -> 60_000L
            "3" -> 180_000L
            "5" -> 300_000L
            "15" -> 900_000L
            "30", "60", "240", "D" -> 900_000L
            else -> 300_000L
        }
    }
}
