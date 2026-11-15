package com.enamind.ai.intelligence

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * TrendMemoryVault — выделенная постоянная папка самообучения ENA (без лишних разрешений Android).
 *
 * Почему НЕ нужны READ/WRITE_EXTERNAL_STORAGE:
 *   Android выделяет приложению собственное постоянное пространство:
 *   - filesDir/enamind/learning_vault/ (основной быстрый сейф),
 *   - Android/data/<package>/files/ENA-MIND-Memory/ (выделенная внешняя папка приложения).
 *   Обе папки создаются автоматически, НЕ удаляются при "Очистке кэша" и полностью соответствуют
 *   политике Google Play.
 *
 * Как система учится по записанным данным:
 *   1) В каждый 6-часовой (360м) бакет по символу сохраняется снимок решения (цена входа, фаза,
 *      голоса ботов, ончейн-поток);
 *   2) Когда горизонт (6ч = 360м) проходит, verifyPending() сверяет прогноз с реальной свечой;
 *   3) По накопленным проверкам (от 5 сэмплов) пересчитываются веса ботов через EMA [0.55..1.55] —
 *      точные боты получают больший вес при синтезе следующих трендов.
 */
class TrendMemoryVault(
    private val primaryDir: File,
    private val mirrorDir: File? = null
) {

    data class Observation(
        val id: String,
        val tsMs: Long,
        val symbol: String,
        val horizonMin: Int,
        val entryPrice: Double,
        val predictedBias: String, // UP / DOWN / FLAT
        val phase: String,
        val strength: Int,
        val botVotes: Map<String, String>, // botId -> UP/DOWN/FLAT
        var verified: Boolean = false,
        var actualPrice: Double? = null,
        var actualMovePct: Double? = null,
        var actualOutcome: String? = null,
        var hit: Boolean? = null,
        var verifiedTsMs: Long = 0L
    )

    private data class AccStat(var samples: Int = 0, var hits: Int = 0)

    private val lock = Any()
    private val observationsFile get() = File(primaryDir, "trend_observations.jsonl")
    private val weightsFile get() = File(primaryDir, "bot_weights.json")
    private val summaryFile get() = File(primaryDir, "learning_summary.json")

    private val observations = ArrayList<Observation>()
    private val botWeights = LinkedHashMap<String, Double>()
    private val botStats = LinkedHashMap<String, AccStat>()
    private val phaseStats = LinkedHashMap<String, AccStat>()
    private var lastVerifiedAtMs: Long = 0L
    private var loaded = false

    val folderDisplayName: String
        get() = if (mirrorDir != null) "ENA Memory + learning_vault" else "learning_vault"

    fun ensureInitialized() {
        synchronized(lock) {
            if (!primaryDir.exists()) primaryDir.mkdirs()
            mirrorDir?.takeIf { !it.exists() }?.mkdirs()
            if (!loaded) loadLocked()
            if (!summaryFile.exists()) persistSummaryLocked()
        }
    }

    /** Перечитать папку самообучения с диска (например, после импорта ZIP). */
    fun reload() {
        synchronized(lock) {
            if (!primaryDir.exists()) primaryDir.mkdirs()
            mirrorDir?.takeIf { !it.exists() }?.mkdirs()
            loaded = false
            loadLocked()
            persistSummaryLocked()
        }
    }

    fun currentWeights(): Map<String, Double> = synchronized(lock) {
        if (!loaded) loadLocked()
        HashMap(botWeights)
    }

    fun snapshot(): LearningSnapshot = synchronized(lock) {
        if (!loaded) loadLocked()
        val verified = observations.filter { it.verified && it.hit != null }
        val hitRate = if (verified.isEmpty()) null
        else 100.0 * verified.count { it.hit == true } / verified.size

        val bRates = LinkedHashMap<String, Double>()
        botStats.forEach { (id, st) ->
            if (st.samples > 0) bRates[id] = 100.0 * st.hits / st.samples
        }
        val pRates = LinkedHashMap<String, Double>()
        phaseStats.forEach { (ph, st) ->
            if (st.samples >= 2) pRates[ph] = 100.0 * st.hits / st.samples
        }
        LearningSnapshot(
            folderLabel = folderDisplayName,
            totalRecorded = observations.size,
            verifiedCount = verified.size,
            hitRatePct = hitRate,
            botWeights = HashMap(botWeights),
            botHitRates = bRates,
            phaseHitRates = pRates,
            lastVerifiedAtMs = lastVerifiedAtMs
        )
    }

    /**
     * Записать наблюдение в выделенную папку (1 запись на 6-часовой бакет актива).
     */
    fun recordObservation(
        report: UnifiedIntelligenceReport,
        entryPrice: Double,
        bucketMs: Long = DEFAULT_HORIZON_MIN * 60_000L
    ): Boolean {
        if (!entryPrice.isFinite() || entryPrice <= 0.0) return false
        synchronized(lock) {
            ensureDirsLocked()
            if (!loaded) loadLocked()
            val bucket = report.computedAtMs / bucketMs
            val obsId = "${report.symbol}:$bucket"
            if (observations.any { it.id == obsId }) return false

            val predBias = when (report.trendLogic.biasLabel) {
                "BULLISH" -> "UP"
                "BEARISH" -> "DOWN"
                else -> "FLAT"
            }
            val votes = LinkedHashMap<String, String>()
            report.bots.forEach { b ->
                if (b.category != BotCategory.SENTINEL) votes[b.id] = b.bias.name
            }
            val obs = Observation(
                id = obsId,
                tsMs = report.computedAtMs,
                symbol = report.symbol,
                horizonMin = DEFAULT_HORIZON_MIN,
                entryPrice = entryPrice,
                predictedBias = predBias,
                phase = report.trendLogic.phase,
                strength = report.trendLogic.strength,
                botVotes = votes
            )
            observations.add(obs)
            if (observations.size > MAX_OBSERVATIONS) {
                trimObservationsLocked()
                rewriteObservationsLocked()
            } else {
                appendObservationLocked(obs)
            }
            persistSummaryLocked()
            return true
        }
    }

    /**
     * Проверить истёкшие наблюдения по реальным свечам и обновить веса ботов (EMA).
     */
    fun verifyPending(
        symbol: String,
        bars: List<List<Double>>,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        if (bars.isEmpty()) return 0
        synchronized(lock) {
            ensureDirsLocked()
            if (!loaded) loadLocked()
            var newlyVerified = 0

            for (obs in observations) {
                if (obs.verified || obs.symbol != symbol) continue
                val targetTsMs = obs.tsMs + obs.horizonMin * 60_000L
                if (nowMs < targetTsMs) continue

                // Ищем первую свечу на или после момента истечения горизонта (или свежую закрывающуюся свечу)
                val targetBar = bars.firstOrNull { (it.getOrNull(0)?.toLong() ?: 0L) >= targetTsMs }
                    ?: bars.lastOrNull()?.takeIf {
                        val barTs = it.getOrNull(0)?.toLong() ?: 0L
                        barTs >= targetTsMs - 900_000L && nowMs >= targetTsMs
                    }
                    ?: continue

                val closePx = targetBar.getOrNull(4) ?: continue
                if (!closePx.isFinite() || closePx <= 0.0 || obs.entryPrice <= 0.0) continue

                val movePct = 100.0 * (closePx - obs.entryPrice) / obs.entryPrice
                val outcome = when {
                    movePct >= FLAT_BAND_PCT -> "UP"
                    movePct <= -FLAT_BAND_PCT -> "DOWN"
                    else -> "FLAT"
                }
                val isHit = when (obs.predictedBias) {
                    "UP" -> movePct > 0.0
                    "DOWN" -> movePct < 0.0
                    else -> abs(movePct) < FLAT_BAND_PCT * 1.5
                }

                obs.verified = true
                obs.actualPrice = closePx
                obs.actualMovePct = movePct
                obs.actualOutcome = outcome
                obs.hit = isHit
                obs.verifiedTsMs = nowMs
                lastVerifiedAtMs = nowMs
                newlyVerified++
            }

            if (newlyVerified > 0) {
                recomputeStatsAndWeightsLocked()
                rewriteObservationsLocked()
                persistWeightsLocked()
                persistSummaryLocked()
            }
            return newlyVerified
        }
    }

    private fun recomputeStatsAndWeightsLocked() {
        botStats.clear()
        phaseStats.clear()
        botWeights.clear()

        val verified = observations.filter { it.verified && it.actualOutcome != null }
        for (obs in verified) {
            val ph = phaseStats.getOrPut(obs.phase) { AccStat() }
            ph.samples++
            if (obs.hit == true) ph.hits++

            val actualDir = obs.actualOutcome ?: continue
            if (actualDir == "FLAT") continue

            for ((botId, vote) in obs.botVotes) {
                if (vote != "UP" && vote != "DOWN") continue
                val st = botStats.getOrPut(botId) { AccStat() }
                st.samples++
                if (vote == actualDir) st.hits++
            }
        }

        // Обновление весов с EMA (начинаем адаптировать от MIN_SAMPLES_FOR_WEIGHT проверок)
        for ((botId, st) in botStats) {
            if (st.samples < MIN_SAMPLES_FOR_WEIGHT) continue
            val hr = st.hits.toDouble() / st.samples.toDouble()
            val target = (1.0 + (hr - 0.5) * 1.6).coerceIn(MIN_WEIGHT, MAX_WEIGHT)
            val prev = botWeights[botId] ?: 1.0
            botWeights[botId] = (prev + EMA_ALPHA * (target - prev)).coerceIn(MIN_WEIGHT, MAX_WEIGHT)
        }
    }

    private fun ensureDirsLocked() {
        if (!primaryDir.exists()) primaryDir.mkdirs()
        mirrorDir?.takeIf { !it.exists() }?.mkdirs()
    }

    private fun loadLocked() {
        loaded = true
        observations.clear()
        if (observationsFile.isFile) {
            try {
                observationsFile.forEachLine(Charsets.UTF_8) { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEachLine
                    parseObservation(trimmed)?.let { observations.add(it) }
                }
            } catch (_: Exception) {
            }
        }
        if (observations.size > MAX_OBSERVATIONS) {
            trimObservationsLocked()
            rewriteObservationsLocked()
        }
        loadWeightsLocked()
        recomputeStatsAndWeightsLocked()
    }

    private fun loadWeightsLocked() {
        botWeights.clear()
        if (!weightsFile.isFile) return
        try {
            val root = JSONObject(weightsFile.readText(Charsets.UTF_8))
            lastVerifiedAtMs = root.optLong("last_verified_at_ms", 0L)
            val wObj = root.optJSONObject("weights") ?: return
            for (k in wObj.keys()) {
                val v = wObj.optDouble(k, 1.0)
                if (v.isFinite()) botWeights[k] = v.coerceIn(MIN_WEIGHT, MAX_WEIGHT)
            }
        } catch (_: Exception) {
        }
    }

    private fun trimObservationsLocked() {
        if (observations.size <= KEEP_OBSERVATIONS) return
        val keep = observations.takeLast(KEEP_OBSERVATIONS)
        observations.clear()
        observations.addAll(keep)
    }

    private fun appendObservationLocked(obs: Observation) {
        try {
            observationsFile.appendText(toJson(obs).toString() + "\n", Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    private fun rewriteObservationsLocked() {
        val sb = StringBuilder()
        observations.forEach { sb.append(toJson(it).toString()).append('\n') }
        val payload = sb.toString()
        atomicWrite(observationsFile, payload)
        mirrorDir?.let { md ->
            // В зеркальную внешнюю папку сохраняем хвост последних 300 наблюдений для просмотра
            val tail = observations.takeLast(300).joinToString("\n") { toJson(it).toString() } +
                if (observations.isNotEmpty()) "\n" else ""
            atomicWrite(File(md, "trend_observations.jsonl"), tail)
        }
    }

    private fun persistWeightsLocked() {
        val root = JSONObject()
            .put("updated_at_ms", System.currentTimeMillis())
            .put("last_verified_at_ms", lastVerifiedAtMs)
            .put("weights", JSONObject().apply { botWeights.forEach { (k, v) -> put(k, v) } })
        val txt = root.toString(2)
        atomicWrite(weightsFile, txt)
        mirrorDir?.let { atomicWrite(File(it, "bot_weights.json"), txt) }
    }

    private fun persistSummaryLocked() {
        val verified = observations.filter { it.verified && it.hit != null }
        val hits = verified.count { it.hit == true }
        val hr = if (verified.isEmpty()) JSONObject.NULL else (100.0 * hits / verified.size)
        val root = JSONObject()
            .put("vault_folder", primaryDir.absolutePath)
            .put("external_folder", mirrorDir?.absolutePath ?: JSONObject.NULL)
            .put("updated_at_ms", System.currentTimeMillis())
            .put("total_recorded", observations.size)
            .put("verified_count", verified.size)
            .put("hit_rate_pct", hr)
            .put("bot_weights", JSONObject().apply {
                botWeights.forEach { (k, v) -> put(k, String.format(Locale.US, "%.3f", v).toDouble()) }
            })
            .put("bot_accuracy", JSONObject().apply {
                botStats.forEach { (k, st) ->
                    put(k, JSONObject().put("samples", st.samples).put("hits", st.hits))
                }
            })
            .put("phase_accuracy", JSONObject().apply {
                phaseStats.forEach { (k, st) ->
                    put(k, JSONObject().put("samples", st.samples).put("hits", st.hits))
                }
            })
        val txt = root.toString(2)
        atomicWrite(summaryFile, txt)
        mirrorDir?.let { atomicWrite(File(it, "learning_summary.json"), txt) }
    }

    private fun atomicWrite(target: File, content: String) {
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun toJson(o: Observation): JSONObject {
        val votesObj = JSONObject()
        o.botVotes.forEach { (k, v) -> votesObj.put(k, v) }
        return JSONObject()
            .put("id", o.id)
            .put("ts", o.tsMs)
            .put("symbol", o.symbol)
            .put("horizon_min", o.horizonMin)
            .put("entry_price", o.entryPrice)
            .put("pred_bias", o.predictedBias)
            .put("phase", o.phase)
            .put("strength", o.strength)
            .put("bot_votes", votesObj)
            .put("verified", o.verified)
            .put("actual_price", o.actualPrice ?: JSONObject.NULL)
            .put("actual_move_pct", o.actualMovePct ?: JSONObject.NULL)
            .put("actual_outcome", o.actualOutcome ?: JSONObject.NULL)
            .put("hit", o.hit ?: JSONObject.NULL)
            .put("verified_ts", o.verifiedTsMs)
    }

    private fun parseObservation(line: String): Observation? = try {
        val j = JSONObject(line)
        val votes = LinkedHashMap<String, String>()
        j.optJSONObject("bot_votes")?.let { vo ->
            for (k in vo.keys()) votes[k] = vo.optString(k)
        }
        val actPx = if (j.isNull("actual_price")) null else j.optDouble("actual_price")
        val actMove = if (j.isNull("actual_move_pct")) null else j.optDouble("actual_move_pct")
        val actOut = if (j.isNull("actual_outcome")) null else j.optString("actual_outcome")
        val hitVal = if (j.isNull("hit")) null else j.optBoolean("hit")
        Observation(
            id = j.optString("id"),
            tsMs = j.optLong("ts"),
            symbol = j.optString("symbol"),
            horizonMin = j.optInt("horizon_min", DEFAULT_HORIZON_MIN),
            entryPrice = j.optDouble("entry_price"),
            predictedBias = j.optString("pred_bias"),
            phase = j.optString("phase"),
            strength = j.optInt("strength", 50),
            botVotes = votes,
            verified = j.optBoolean("verified", false),
            actualPrice = actPx,
            actualMovePct = actMove,
            actualOutcome = actOut,
            hit = hitVal,
            verifiedTsMs = j.optLong("verified_ts", 0L)
        ).takeIf { it.id.isNotBlank() && it.symbol.isNotBlank() && it.entryPrice > 0 }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val DEFAULT_HORIZON_MIN = 360
        private const val MAX_OBSERVATIONS = 1500
        private const val KEEP_OBSERVATIONS = 1200
        private const val MIN_SAMPLES_FOR_WEIGHT = 5
        private const val MIN_WEIGHT = 0.55
        private const val MAX_WEIGHT = 1.55
        private const val EMA_ALPHA = 0.25
        private const val FLAT_BAND_PCT = 0.15

        fun create(ctx: Context): TrendMemoryVault {
            val primary = File(File(ctx.filesDir, "enamind"), "learning_vault")
            val mirror = ctx.getExternalFilesDir("ENA-MIND-Memory")
            return TrendMemoryVault(primary, mirror).apply { ensureInitialized() }
        }
    }
}
