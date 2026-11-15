package com.enamind.ai.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * FaultMonitor (ТЗ п.10.5) — сторожевой агент самодиагностики и «История сбоев».
 *
 * Отслеживает нерабочий код/скрипты, ошибки движка, деградацию агентов/ботов,
 * сбои WebSocket/REST-источников и аномалии расчётов (NaN/Inf).
 * Сохраняет структурированную историю в filesDir/enamind/fault_history.jsonl
 * и предоставляет срез состояния для вкладки Настройки → «История сбоев».
 */
object FaultMonitor {

    enum class Severity { INFO, WARNING, ERROR, CRITICAL }

    enum class Kind(val labelRu: String) {
        ENGINE("Движок"),
        PYTHON("Python-скрипт"),
        AGENT("Агент / Бот"),
        NETWORK("API / Биржа"),
        WEBSOCKET("WebSocket поток"),
        MEMORY("Память / Файлы"),
        CALC("Ошибка расчёта")
    }

    data class FaultEvent(
        val id: Long,
        val tsMs: Long,
        val component: String,
        val kind: Kind,
        val severity: Severity,
        val message: String,
        val recovery: String = "",
        val resolved: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("ts", tsMs)
            .put("component", component)
            .put("kind", kind.name)
            .put("severity", severity.name)
            .put("message", message)
            .put("recovery", recovery)
            .put("resolved", resolved)

        companion object {
            fun fromJson(o: JSONObject): FaultEvent? {
                val msg = o.optString("message").takeIf { it.isNotBlank() } ?: return null
                val comp = o.optString("component").ifBlank { "Неизвестный узел" }
                val k = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.ENGINE)
                val sev = runCatching { Severity.valueOf(o.optString("severity")) }.getOrDefault(Severity.WARNING)
                return FaultEvent(
                    id = o.optLong("id", 0L),
                    tsMs = o.optLong("ts", 0L),
                    component = comp,
                    kind = k,
                    severity = sev,
                    message = msg,
                    recovery = o.optString("recovery", ""),
                    resolved = o.optBoolean("resolved", false)
                )
            }
        }
    }

    data class HealthSummary(
        val healthy: Boolean = true,
        val activeFaults: Int = 0,
        val criticalCount: Int = 0,
        val totalLogged: Int = 0,
        val lastAuditTsMs: Long = 0L,
        val failingComponents: List<String> = emptyList(),
        val workingComponents: Int = 0
    )

    private const val FILE_NAME = "fault_history.jsonl"
    private const val MAX_EVENTS = 300
    private const val DEDUP_WINDOW_MS = 45_000L

    private val lock = Any()
    private val seq = AtomicLong(1L)
    private var appDir: File? = null
    private val eventsList = ArrayList<FaultEvent>()
    private val lastDedupMs = HashMap<String, Long>()
    private val componentHealthy = LinkedHashMap<String, Boolean>()

    private val _events = MutableStateFlow<List<FaultEvent>>(emptyList())
    val events: StateFlow<List<FaultEvent>> = _events

    private val _summary = MutableStateFlow(HealthSummary())
    val summary: StateFlow<HealthSummary> = _summary

    fun init(ctx: Context) {
        synchronized(lock) {
            val dir = File(ctx.filesDir, "enamind").apply { mkdirs() }
            if (appDir?.absolutePath == dir.absolutePath) return
            appDir = dir
            lastDedupMs.clear()
            componentHealthy.clear()
            loadLocked()
            publishLocked(System.currentTimeMillis())
        }
    }

    fun recordFault(
        component: String,
        kind: Kind,
        severity: Severity,
        message: String,
        recovery: String = ""
    ) {
        val cleanMsg = message.trim().take(280)
        if (cleanMsg.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            componentHealthy[component] = severity == Severity.INFO
            val dedupKey = "$component|${kind.name}|${cleanMsg.take(80)}"
            val prevTs = lastDedupMs[dedupKey] ?: 0L
            if (now - prevTs < DEDUP_WINDOW_MS) {
                publishLocked(now)
                return
            }
            lastDedupMs[dedupKey] = now

            val ev = FaultEvent(
                id = now * 1000L + (seq.incrementAndGet() % 1000L),
                tsMs = now,
                component = component,
                kind = kind,
                severity = severity,
                message = cleanMsg,
                recovery = recovery,
                resolved = false
            )
            eventsList.add(0, ev)
            if (eventsList.size > MAX_EVENTS) {
                eventsList.subList(MAX_EVENTS, eventsList.size).clear()
            }
            persistLocked()
            publishLocked(now)
        }
    }

    fun markHealthy(component: String) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val wasBad = componentHealthy[component] == false
            componentHealthy[component] = true
            if (wasBad) {
                for (i in eventsList.indices) {
                    val e = eventsList[i]
                    if (e.component == component && !e.resolved) {
                        eventsList[i] = e.copy(resolved = true)
                    }
                }
                persistLocked()
            }
            publishLocked(now)
        }
    }

    /**
     * Глубокий аудит целостности всех узлов ENA (вызывается периодически и вручную).
     */
    fun runAudit(state: AppState) {
        val now = System.currentTimeMillis()
        // 1. Python bridge
        val pyErr = PythonBridge.lastInitError
        if (!PythonBridge.isReady()) {
            recordFault(
                component = "PythonBridge (Chaquopy)",
                kind = Kind.PYTHON,
                severity = Severity.ERROR,
                message = pyErr ?: "Python-движок ещё не инициализирован или не запустился",
                recovery = "Работает Kotlin-контур резервирования"
            )
        } else {
            markHealthy("PythonBridge (Chaquopy)")
        }

        // 2. Результаты Python-конвейера и агенты
        val res = state.engine.result.value
        if (res != null) {
            if (!res.optBoolean("ok")) {
                val err = res.optString("error").ifBlank {
                    res.optJSONObject("data")?.optJSONArray("issues")?.optString(0) ?: "нет сигнала"
                }
                recordFault(
                    component = "Python NeuralEngine",
                    kind = Kind.ENGINE,
                    severity = Severity.WARNING,
                    message = "Цикл вычислений завершился с ограничением: $err",
                    recovery = "Включён многобиржевой резервный поток"
                )
            } else {
                markHealthy("Python NeuralEngine")
            }

            val agents = res.optJSONArray("agents")
            if (agents != null) {
                for (i in 0 until agents.length()) {
                    val a = agents.optJSONObject(i) ?: continue
                    val name = a.optString("agent", "AGENT_$i")
                    val st = a.optString("status", "UNKNOWN")
                    val conf = a.optDouble("confidence", Double.NaN)
                    if (st == "ERROR" || st == "FAILED" || conf.isNaN()) {
                        recordFault(
                            component = "Агент $name",
                            kind = Kind.AGENT,
                            severity = Severity.ERROR,
                            message = "Статус=$st, reason=${a.optString("reason", "NaN/сбой расчёта")}",
                            recovery = "Исключён из голосования до стабилизации"
                        )
                    } else if (st == "OK") {
                        markHealthy("Агент $name")
                    }
                }
            }
        }

        // 3. Kotlin-боты и качество данных
        val intel = state.engine.intelligence.value
        if (intel != null) {
            if (intel.quant.dataGrade == "D") {
                recordFault(
                    component = "Data Quality Gate",
                    kind = Kind.CALC,
                    severity = Severity.WARNING,
                    message = "Качество данных Grade D (${intel.quant.dataNotes.joinToString("; ")})",
                    recovery = "Вероятности сжаты к безопасному центру"
                )
            } else {
                markHealthy("Data Quality Gate")
            }
            intel.bots.forEach { b ->
                if (!b.confidence.isFinite() || b.confidence <= 0.05) {
                    recordFault(
                        component = b.name,
                        kind = Kind.AGENT,
                        severity = Severity.WARNING,
                        message = "Низкая/битая уверенность бота: ${b.summary}",
                        recovery = "Переход на резервные потоки"
                    )
                } else {
                    markHealthy(b.name)
                }
            }
        }

        // 4. WebSocket потоки
        val topics = state.feedTopics.value
        val brokenTopics = topics.filter { it.value.status == FeedStatus.ERROR || it.value.status == FeedStatus.DISCONNECTED }
        if (brokenTopics.isNotEmpty()) {
            val detail = brokenTopics.entries.joinToString(", ") { "${it.key.name}:${it.value.error.ifBlank { it.value.status }}" }
            recordFault(
                component = "WebSocket Потоки",
                kind = Kind.WEBSOCKET,
                severity = Severity.WARNING,
                message = "Проблема тем: $detail",
                recovery = "Авто-переподключение и fallback Binance/OKX/Coinbase"
            )
        } else if (topics.isNotEmpty()) {
            markHealthy("WebSocket Потоки")
        }

        // 5. Real-time pulse engine
        val pulse = state.engine.realTimePulse.value
        if (!pulse.score.isFinite() || !pulse.accuracyPct.let { it == null || it.isFinite() }) {
            recordFault(
                component = "RealTimeLeadEngine",
                kind = Kind.CALC,
                severity = Severity.CRITICAL,
                message = "Обнаружен NaN/Inf в потоке упреждающего расчёта",
                recovery = "Сброс состояния пульса"
            )
        } else {
            markHealthy("RealTimeLeadEngine")
        }

        synchronized(lock) {
            publishLocked(now)
        }
    }

    fun clear() {
        synchronized(lock) {
            eventsList.clear()
            lastDedupMs.clear()
            componentHealthy.keys.forEach { componentHealthy[it] = true }
            appDir?.let { File(it, FILE_NAME).delete() }
            publishLocked(System.currentTimeMillis())
        }
    }

    fun historyFile(ctx: Context): File = File(File(ctx.filesDir, "enamind"), FILE_NAME)

    private fun loadLocked() {
        eventsList.clear()
        val f = appDir?.let { File(it, FILE_NAME) } ?: return
        if (!f.isFile) return
        runCatching {
            f.readLines(Charsets.UTF_8)
                .asReversed()
                .take(MAX_EVENTS)
                .mapNotNull { line ->
                    val t = line.trim()
                    if (t.isEmpty()) null else runCatching { FaultEvent.fromJson(JSONObject(t)) }.getOrNull()
                }
                .let { eventsList.addAll(it) }
        }
    }

    private fun persistLocked() {
        val dir = appDir ?: return
        val f = File(dir, FILE_NAME)
        val tmp = File(dir, "$FILE_NAME.tmp")
        runCatching {
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                for (ev in eventsList.asReversed()) {
                    w.write(ev.toJson().toString())
                    w.newLine()
                }
            }
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun publishLocked(now: Long) {
        val snap = eventsList.toList()
        _events.value = snap
        val failing = componentHealthy.filterValues { !it }.keys.toList()
        val unresolved = snap.filter { !it.resolved && it.severity != Severity.INFO }
        val crit = unresolved.count { it.severity == Severity.CRITICAL || it.severity == Severity.ERROR }
        _summary.value = HealthSummary(
            healthy = failing.isEmpty() && crit == 0,
            activeFaults = unresolved.size,
            criticalCount = crit,
            totalLogged = snap.size,
            lastAuditTsMs = now,
            failingComponents = failing,
            workingComponents = componentHealthy.count { it.value }
        )
    }
}
