package com.enamind.ai.intelligence

import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * RealtimePulseVault (ТЗ п.7.5) — отдельная папка памяти для прогнозов в реальном времени.
 *
 * Хранит историю упреждающих сигналов (окно оценки 5–15 минут в будущее),
 * автоматически проверяет их по живым свечам/цене графика и рассчитывает
 * честный процент точности (%), отображаемый в нижней панели приложения.
 *
 * Включает разгрузочное кэширование и EMA-обучение весов поведенческих агентов.
 */
class RealtimePulseVault(
    private val primaryDir: File,
    private val mirrorDir: File? = null
) {
    data class PulseObservation(
        val id: String,
        val tsMs: Long,
        val symbol: String,
        val horizonMin: Int,         // окно упреждения (например 5 или 10 мин)
        val entryPrice: Double,
        val direction: String,       // "LONG" | "SHORT"
        val confidence: Double,
        val triggerActive: Boolean,
        val factorVotes: Map<String, String> = emptyMap(),
        var verified: Boolean = false,
        var actualPrice: Double? = null,
        var actualMovePct: Double? = null,
        var hit: Boolean? = null,
        var verifiedTsMs: Long = 0L
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("ts_ms", tsMs)
            put("symbol", symbol)
            put("horizon_min", horizonMin)
            put("entry_price", entryPrice)
            put("direction", direction)
            put("confidence", confidence)
            put("trigger_active", triggerActive)
            put("factor_votes", JSONObject(factorVotes))
            put("verified", verified)
            if (actualPrice != null) put("actual_price", actualPrice)
            if (actualMovePct != null) put("actual_move_pct", actualMovePct)
            if (hit != null) put("hit", hit)
            if (verifiedTsMs > 0L) put("verified_ts_ms", verifiedTsMs)
        }

        companion object {
            fun fromJson(o: JSONObject): PulseObservation? {
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
                val sym = o.optString("symbol").takeIf { it.isNotBlank() } ?: return null
                val px = o.optDouble("entry_price", Double.NaN)
                if (!px.isFinite() || px <= 0.0) return null
                val dir = o.optString("direction")
                if (dir != "LONG" && dir != "SHORT") return null
                val votesObj = o.optJSONObject("factor_votes")
                val votes = LinkedHashMap<String, String>()
                if (votesObj != null) {
                    val keys = votesObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        votes[k] = votesObj.optString(k)
                    }
                }
                return PulseObservation(
                    id = id,
                    tsMs = o.optLong("ts_ms", 0L),
                    symbol = sym,
                    horizonMin = o.optInt("horizon_min", DEFAULT_LEAD_MIN).coerceIn(5, 15),
                    entryPrice = px,
                    direction = dir,
                    confidence = o.optDouble("confidence", 0.5).let { if (it.isFinite()) it else 0.5 },
                    triggerActive = o.optBoolean("trigger_active", false),
                    factorVotes = votes,
                    verified = o.optBoolean("verified", false),
                    actualPrice = if (o.has("actual_price")) o.optDouble("actual_price") else null,
                    actualMovePct = if (o.has("actual_move_pct")) o.optDouble("actual_move_pct") else null,
                    hit = if (o.has("hit")) o.optBoolean("hit") else null,
                    verifiedTsMs = o.optLong("verified_ts_ms", 0L)
                )
            }
        }
    }

    data class PulseSnapshot(
        val folderName: String = "realtime_pulse_vault",
        val totalRecorded: Int = 0,
        val verifiedCount: Int = 0,
        val accuracyPct: Double? = null,
        val recentAccuracyPct: Double? = null,
        val factorWeights: Map<String, Double> = emptyMap(),
        val lastVerifiedAtMs: Long = 0L
    )

    private val lock = Any()
    private val observations = ArrayList<PulseObservation>()
    private val factorWeights = LinkedHashMap<String, Double>()
    private var loaded = false
    private var lastVerifiedAtMs = 0L

    private val observationsFile get() = File(primaryDir, "pulse_observations.jsonl")
    private val weightsFile get() = File(primaryDir, "pulse_weights.json")
    private val summaryFile get() = File(primaryDir, "pulse_summary.json")

    fun ensureInitialized() {
        synchronized(lock) {
            ensureDirsLocked()
            if (!loaded) loadLocked()
            if (!summaryFile.exists()) persistSummaryLocked()
        }
    }

    fun reload() {
        synchronized(lock) {
            ensureDirsLocked()
            loadLocked()
        }
    }

    fun currentWeights(): Map<String, Double> = synchronized(lock) {
        if (!loaded) loadLocked()
        HashMap(factorWeights)
    }

    fun snapshot(): PulseSnapshot = synchronized(lock) {
        if (!loaded) loadLocked()
        val verified = observations.filter { it.verified && it.hit != null }
        val acc = if (verified.isEmpty()) null else 100.0 * verified.count { it.hit == true } / verified.size
        val recent = verified.takeLast(24)
        val recentAcc = if (recent.size < 4) acc else 100.0 * recent.count { it.hit == true } / recent.size
        PulseSnapshot(
            folderName = if (mirrorDir != null) "realtime_pulse_vault + ENA-Realtime" else "realtime_pulse_vault",
            totalRecorded = observations.size,
            verifiedCount = verified.size,
            accuracyPct = acc,
            recentAccuracyPct = recentAcc,
            factorWeights = HashMap(factorWeights),
            lastVerifiedAtMs = lastVerifiedAtMs
        )
    }

    /**
     * Записать снимок упреждающего прогноза (шаг бакетирования ~2 мин на актив,
     * чтобы не дублировать записи каждую секунду, но плотно покрывать 5–15м горизонт).
     */
    fun recordPulse(
        symbol: String,
        entryPrice: Double,
        direction: String,
        confidence: Double,
        triggerActive: Boolean,
        factorVotes: Map<String, String>,
        horizonMin: Int = DEFAULT_LEAD_MIN,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!entryPrice.isFinite() || entryPrice <= 0.0) return false
        if (direction != "LONG" && direction != "SHORT") return false
        synchronized(lock) {
            ensureDirsLocked()
            if (!loaded) loadLocked()
            val bucket = nowMs / BUCKET_MS
            val id = "$symbol:$bucket"
            if (observations.any { it.id == id }) return false

            val obs = PulseObservation(
                id = id,
                tsMs = nowMs,
                symbol = symbol,
                horizonMin = horizonMin.coerceIn(5, 15),
                entryPrice = entryPrice,
                direction = direction,
                confidence = confidence.coerceIn(0.1, 0.99),
                triggerActive = triggerActive,
                factorVotes = factorVotes
            )
            observations.add(obs)
            if (observations.size > MAX_RECORDS) {
                val trim = observations.size - MAX_RECORDS
                observations.subList(0, trim).clear()
                rewriteObservationsLocked()
            } else {
                appendObservationLocked(obs)
            }
            persistSummaryLocked()
            return true
        }
    }

    /**
     * Проверить созревшие прогнозы реального времени по живым 1м свечам и текущей цене.
     */
    fun verifyAgainstLiveChart(
        symbol: String,
        bars1m: List<List<Double>>,
        livePrice: Double? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        synchronized(lock) {
            ensureDirsLocked()
            if (!loaded) loadLocked()
            var newlyVerified = 0
            for (obs in observations) {
                if (obs.verified || obs.symbol != symbol) continue
                val targetTs = obs.tsMs + obs.horizonMin * 60_000L
                if (nowMs < targetTs) continue

                val bar = bars1m.firstOrNull { (it.getOrNull(0)?.toLong() ?: 0L) >= targetTs }
                val actualPx = bar?.getOrNull(4)?.takeIf { it.isFinite() && it > 0.0 }
                    ?: livePrice?.takeIf { it.isFinite() && it > 0.0 && nowMs >= targetTs }
                    ?: continue

                val movePct = 100.0 * (actualPx - obs.entryPrice) / obs.entryPrice
                // Для упреждения в реальном времени считаем успешным попадание в направлении движения
                // или удержание вне существенного обратного срыва (> -0.03%)
                val isHit = when (obs.direction) {
                    "LONG" -> movePct > -DEADBAND_PCT && movePct >= 0.0 || (obs.triggerActive && movePct >= 0.0)
                    "SHORT" -> movePct < DEADBAND_PCT && movePct <= 0.0 || (obs.triggerActive && movePct <= 0.0)
                    else -> false
                }
                // Если цена почти не сдвинулась (< 0.015%), считаем точность по чистому знаку только если он ненулевой
                val strictHit = if (abs(movePct) < 0.015) {
                    (obs.direction == "LONG" && movePct >= 0.0) || (obs.direction == "SHORT" && movePct <= 0.0)
                } else {
                    (obs.direction == "LONG" && movePct > 0.0) || (obs.direction == "SHORT" && movePct < 0.0)
                }

                obs.verified = true
                obs.actualPrice = actualPx
                obs.actualMovePct = movePct
                obs.hit = strictHit && isHit
                obs.verifiedTsMs = nowMs
                lastVerifiedAtMs = nowMs
                newlyVerified++
            }
            if (newlyVerified > 0) {
                recomputeWeightsLocked()
                rewriteObservationsLocked()
                persistWeightsLocked()
                persistSummaryLocked()
            }
            return newlyVerified
        }
    }

    private fun recomputeWeightsLocked() {
        val stats = HashMap<String, Pair<Int, Int>>() // id -> (samples, hits)
        for (obs in observations) {
            if (!obs.verified) continue
            val mv = obs.actualMovePct ?: continue
            val actualDir = if (mv >= 0.0) "LONG" else "SHORT"
            for ((k, vote) in obs.factorVotes) {
                if (vote != "LONG" && vote != "SHORT") continue
                val cur = stats[k] ?: (0 to 0)
                stats[k] = (cur.first + 1) to (cur.second + if (vote == actualDir) 1 else 0)
            }
        }
        for ((k, pair) in stats) {
            if (pair.first < 6) continue
            val hr = pair.second.toDouble() / pair.first.toDouble()
            val target = (1.0 + (hr - 0.5) * 1.4).coerceIn(0.60, 1.50)
            val prev = factorWeights[k] ?: 1.0
            factorWeights[k] = (prev + 0.25 * (target - prev)).coerceIn(0.60, 1.50)
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
            runCatching {
                observationsFile.readLines(Charsets.UTF_8).forEach { line ->
                    val t = line.trim()
                    if (t.isNotEmpty()) {
                        runCatching { PulseObservation.fromJson(JSONObject(t)) }.getOrNull()?.let {
                            observations.add(it)
                            if (it.verifiedTsMs > lastVerifiedAtMs) lastVerifiedAtMs = it.verifiedTsMs
                        }
                    }
                }
            }
        }
        factorWeights.clear()
        if (weightsFile.isFile) {
            runCatching {
                val root = JSONObject(weightsFile.readText(Charsets.UTF_8))
                val w = root.optJSONObject("weights") ?: JSONObject()
                val keys = w.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = w.optDouble(k, 1.0)
                    if (v.isFinite()) factorWeights[k] = v.coerceIn(0.60, 1.50)
                }
            }
        }
    }

    private fun appendObservationLocked(obs: PulseObservation) {
        val line = obs.toJson().toString() + "\n"
        runCatching { observationsFile.appendText(line, Charsets.UTF_8) }
        mirrorDir?.let { m -> runCatching { File(m, "pulse_observations.jsonl").appendText(line, Charsets.UTF_8) } }
    }

    private fun rewriteObservationsLocked() {
        val content = buildString {
            observations.forEach { append(it.toJson().toString()).append('\n') }
        }
        writeAtomic(observationsFile, content)
        mirrorDir?.let { m -> writeAtomic(File(m, "pulse_observations.jsonl"), content) }
    }

    private fun persistWeightsLocked() {
        val obj = JSONObject()
            .put("updated_at_ms", System.currentTimeMillis())
            .put("weights", JSONObject(factorWeights))
        val text = obj.toString(2)
        writeAtomic(weightsFile, text)
        mirrorDir?.let { m -> writeAtomic(File(m, "pulse_weights.json"), text) }
    }

    private fun persistSummaryLocked() {
        val snap = snapshot()
        val obj = JSONObject()
            .put("folder", primaryDir.absolutePath)
            .put("updated_at_ms", System.currentTimeMillis())
            .put("total_recorded", snap.totalRecorded)
            .put("verified_count", snap.verifiedCount)
            .put("accuracy_pct", snap.accuracyPct ?: JSONObject.NULL)
            .put("recent_accuracy_pct", snap.recentAccuracyPct ?: JSONObject.NULL)
            .put("factor_weights", JSONObject(snap.factorWeights))
        val text = obj.toString(2)
        writeAtomic(summaryFile, text)
        mirrorDir?.let { m -> writeAtomic(File(m, "pulse_summary.json"), text) }
    }

    private fun writeAtomic(file: File, text: String) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    companion object {
        const val DEFAULT_LEAD_MIN = 8
        private const val BUCKET_MS = 120_000L
        private const val MAX_RECORDS = 1500
        private const val DEADBAND_PCT = 0.02
    }
}
