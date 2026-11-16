package com.enamind.ai.calendar

import android.content.Context
import android.util.Log
import com.enamind.ai.core.FaultMonitor
import com.enamind.ai.core.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Календарь крупных предстоящих событий (макроэкономика + крипто-анонсы).
 *
 * Правила:
 *  - НИКАКИХ вымышленных или захардкоженных событий: только реальные открытые публичные API без ключей;
 *  - Строгая дедупликация по каноническому ключу (нормализованное событие + день UTC + категория);
 *  - Автоматизация обновления строго каждые 15 дней (плюс первый запуск при пустом кэше и ручное обновление);
 *  - Карточки содержат до 10 важных предстоящих событий в каждой, прошедшие события автоматически скрываются.
 */
class MajorCalendarRepo(
    private val ctx: Context,
    private val settings: Settings
) {
    enum class EventCategory(val labelRu: String) {
        MACRO("Макро / ЦБ"),
        CRYPTO("Крипто / Биржи")
    }

    data class CalendarEvent(
        val id: String,
        val canonicalKey: String,
        val title: String,
        val category: EventCategory,
        val assetOrCurrency: String,
        val eventTsMs: Long,
        val dateLabelUtc: String,
        val impact: String, // "HIGH"
        val forecast: String? = null,
        val previous: String? = null,
        val source: String,
        val fetchedAtMs: Long
    )

    data class CalendarCard(
        val index: Int,
        val title: String,
        val events: List<CalendarEvent>
    )

    data class CalendarUiState(
        val cards: List<CalendarCard> = emptyList(),
        val totalUpcoming: Int = 0,
        val lastSyncMs: Long = 0L,
        val nextAutoSyncMs: Long = 0L,
        val syncing: Boolean = false,
        val sourceSummary: String = "не загружен",
        val error: String? = null
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cacheFile = File(File(ctx.filesDir, "enamind"), "calendar_events_vault.json")
    private val lock = Any()
    private val storedEvents = LinkedHashMap<String, CalendarEvent>()

    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state

    init {
        loadCache()
        publishState()
    }

    fun isSyncDue(nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = settings.calendarLastSyncMs
        if (last <= 0L) return true
        return (nowMs - last) >= FIFTEEN_DAYS_MS
    }

    fun refreshIfDueSync(nowMs: Long = System.currentTimeMillis()): CalendarUiState {
        return if (isSyncDue(nowMs)) refreshNowSync(nowMs) else {
            publishState(nowMs)
            _state.value
        }
    }

    fun refreshNowSync(nowMs: Long = System.currentTimeMillis()): CalendarUiState {
        _state.value = _state.value.copy(syncing = true, error = null)

        val collected = ArrayList<CalendarEvent>()
        val okSources = ArrayList<String>()
        val failedSources = ArrayList<String>()

        // 1. Biquote high-impact macro JSON (без ключа)
        fetchJson("https://biquote.io/api/calendar?importance=high&countries=US,EU,GB,JP")?.let { txt ->
            val parsed = parseBiquoteCalendar(txt, nowMs)
            if (parsed.isNotEmpty()) {
                collected.addAll(parsed)
                okSources.add("Biquote Macro")
            }
        } ?: failedSources.add("Biquote")

        // 2. FinanceCalendar WP REST JSON (без ключа)
        fetchJson("https://www.financecalendar.com/wp-json/fc/v1/calendar?impact=high")?.let { txt ->
            val parsed = parseFinanceCalendar(txt, nowMs)
            if (parsed.isNotEmpty()) {
                collected.addAll(parsed)
                okSources.add("FinanceCalendar")
            }
        } ?: failedSources.add("FinanceCalendar")

        // 3. ForexFactory weekly JSON (с защитой от частого опроса — уважаем лимиты CDN)
        val allowFf = (nowMs - settings.calendarLastSyncMs) >= 4 * 3600_000L || collected.isEmpty()
        if (allowFf) {
            fetchJson("https://nfs.faireconomy.media/ff_calendar_thisweek.json")?.let { txt ->
                if (!txt.trimStart().startsWith("<")) {
                    val parsed = parseForexFactoryJson(txt, nowMs)
                    if (parsed.isNotEmpty()) {
                        collected.addAll(parsed)
                        okSources.add("ForexFactory")
                    }
                }
            }
        }

        // 4. Bybit V5 Public Announcements (крупные листинги, делистинги, апгрейды сетей)
        fetchJson("https://api.bybit.com/v5/announcements/index?locale=en-US&limit=25")?.let { txt ->
            val parsed = parseBybitAnnouncements(txt, nowMs)
            if (parsed.isNotEmpty()) {
                collected.addAll(parsed)
                okSources.add("Bybit Анонсы")
            }
        } ?: failedSources.add("Bybit")

        // 5. Binance Public CMS Announcements
        fetchJson("https://www.binance.com/bapi/composite/v1/public/cms/article/list/query?type=1&pageNo=1&pageSize=25")?.let { txt ->
            val parsed = parseBinanceAnnouncements(txt, nowMs)
            if (parsed.isNotEmpty()) {
                collected.addAll(parsed)
                okSources.add("Binance Анонсы")
            }
        } ?: failedSources.add("Binance")

        val syncErr: String?
        if (okSources.isNotEmpty()) {
            mergeAndDeduplicate(collected, nowMs)
            settings.calendarLastSyncMs = nowMs
            settings.calendarLastError = ""
            saveCache(nowMs, okSources.joinToString(" · "))
            FaultMonitor.markHealthy("Календарь событий")
            syncErr = null
        } else {
            syncErr = "Открытые источники календаря временно недоступны (${failedSources.joinToString(", ")})"
            settings.calendarLastError = syncErr
            FaultMonitor.recordFault(
                component = "Календарь событий",
                kind = FaultMonitor.Kind.NETWORK,
                severity = FaultMonitor.Severity.WARNING,
                message = syncErr,
                recovery = "Повтор при следующей синхронизации или вручную"
            )
        }

        publishState(
            nowMs = nowMs,
            sourceSummaryOverride = if (okSources.isNotEmpty()) okSources.joinToString(" · ") else null,
            errorOverride = syncErr
        )
        return _state.value
    }

    /**
     * Слияние без повторов по canonicalKey и очистка событий, дата которых уже прошла (>2ч назад).
     */
    fun mergeAndDeduplicate(incoming: List<CalendarEvent>, nowMs: Long) {
        synchronized(lock) {
            val cutoffMs = nowMs - 2 * 3600_000L
            // Удаляем давно прошедшие события
            val it = storedEvents.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.eventTsMs < cutoffMs) it.remove()
            }
            for (ev in incoming) {
                if (ev.eventTsMs < cutoffMs) continue
                val existing = storedEvents[ev.canonicalKey]
                storedEvents[ev.canonicalKey] = if (existing == null) {
                    ev
                } else {
                    // Обогащаем недостающие поля без дублирования
                    existing.copy(
                        forecast = existing.forecast ?: ev.forecast,
                        previous = existing.previous ?: ev.previous,
                        eventTsMs = minOf(existing.eventTsMs, ev.eventTsMs),
                        source = if (existing.source.contains(ev.source)) existing.source else "${existing.source} + ${ev.source}",
                        fetchedAtMs = maxOf(existing.fetchedAtMs, ev.fetchedAtMs)
                    )
                }
            }
        }
    }

    fun upcomingEvents(nowMs: Long = System.currentTimeMillis()): List<CalendarEvent> {
        val cutoff = nowMs - 2 * 3600_000L
        return synchronized(lock) {
            storedEvents.values
                .filter { it.eventTsMs >= cutoff }
                .sortedBy { it.eventTsMs }
        }
    }

    fun buildCards(nowMs: Long = System.currentTimeMillis()): List<CalendarCard> {
        val upcoming = upcomingEvents(nowMs)
        if (upcoming.isEmpty()) return emptyList()
        return upcoming.chunked(MAX_EVENTS_PER_CARD).mapIndexed { idx, chunk ->
            val startIdx = idx * MAX_EVENTS_PER_CARD + 1
            val endIdx = startIdx + chunk.size - 1
            CalendarCard(
                index = idx,
                title = "Предстоящие события ($startIdx–$endIdx)",
                events = chunk
            )
        }
    }

    private fun publishState(
        nowMs: Long = System.currentTimeMillis(),
        sourceSummaryOverride: String? = null,
        errorOverride: String? = null
    ) {
        val cards = buildCards(nowMs)
        val total = cards.sumOf { it.events.size }
        val lastSync = settings.calendarLastSyncMs
        val nextAuto = if (lastSync > 0L) lastSync + FIFTEEN_DAYS_MS else nowMs
        val src = sourceSummaryOverride ?: _state.value.sourceSummary.takeIf { it != "не загружен" } ?: "публичные API"
        _state.value = CalendarUiState(
            cards = cards,
            totalUpcoming = total,
            lastSyncMs = lastSync,
            nextAutoSyncMs = nextAuto,
            syncing = false,
            sourceSummary = src,
            error = errorOverride ?: settings.calendarLastError.ifBlank { null }
        )
    }

    private fun loadCache() {
        try {
            if (!cacheFile.exists()) return
            val root = JSONObject(cacheFile.readText())
            val arr = root.optJSONArray("events") ?: return
            val list = ArrayList<CalendarEvent>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cat = runCatching { EventCategory.valueOf(o.optString("category")) }
                    .getOrDefault(EventCategory.MACRO)
                list.add(
                    CalendarEvent(
                        id = o.optString("id"),
                        canonicalKey = o.optString("canonicalKey"),
                        title = o.optString("title"),
                        category = cat,
                        assetOrCurrency = o.optString("assetOrCurrency"),
                        eventTsMs = o.optLong("eventTsMs"),
                        dateLabelUtc = o.optString("dateLabelUtc"),
                        impact = o.optString("impact", "HIGH"),
                        forecast = o.optString("forecast").ifBlank { null },
                        previous = o.optString("previous").ifBlank { null },
                        source = o.optString("source"),
                        fetchedAtMs = o.optLong("fetchedAtMs")
                    )
                )
            }
            mergeAndDeduplicate(list, System.currentTimeMillis())
            val cachedSrc = root.optString("sourceSummary", "")
            if (cachedSrc.isNotBlank()) {
                _state.value = _state.value.copy(sourceSummary = "$cachedSrc (кэш)")
            }
        } catch (e: Exception) {
            Log.w("MajorCalendarRepo", "loadCache: ${e.message}")
        }
    }

    private fun saveCache(nowMs: Long, sourceSummary: String) {
        try {
            cacheFile.parentFile?.mkdirs()
            val arr = JSONArray()
            synchronized(lock) {
                for (ev in storedEvents.values) {
                    arr.put(
                        JSONObject()
                            .put("id", ev.id)
                            .put("canonicalKey", ev.canonicalKey)
                            .put("title", ev.title)
                            .put("category", ev.category.name)
                            .put("assetOrCurrency", ev.assetOrCurrency)
                            .put("eventTsMs", ev.eventTsMs)
                            .put("dateLabelUtc", ev.dateLabelUtc)
                            .put("impact", ev.impact)
                            .put("forecast", ev.forecast ?: "")
                            .put("previous", ev.previous ?: "")
                            .put("source", ev.source)
                            .put("fetchedAtMs", ev.fetchedAtMs)
                    )
                }
            }
            cacheFile.writeText(
                JSONObject()
                    .put("savedAtMs", nowMs)
                    .put("sourceSummary", sourceSummary)
                    .put("events", arr)
                    .toString()
            )
        } catch (e: Exception) {
            Log.w("MajorCalendarRepo", "saveCache: ${e.message}")
        }
    }

    private fun fetchJson(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "ENA-Terminal/1.0")
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string()?.takeIf { it.isNotBlank() } else null
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val MAX_EVENTS_PER_CARD = 10
        const val FIFTEEN_DAYS_MS = 15L * 24L * 3600L * 1000L

        private val MAJOR_CRYPTO_KEYWORDS = listOf(
            "upgrade", "hard fork", "hardfork", "mainnet", "listing", "will list", "delist",
            "delisting", "etf", "halving", "migration", "merge", "fomc", "sec", "spot etf",
            "launchpool", "maintenance", "rebrand", "swap"
        )

        fun utcDayString(tsMs: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return sdf.format(java.util.Date(tsMs))
        }

        fun canonicalKey(title: String, tsMs: Long, category: EventCategory, asset: String): String {
            val low = title.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9а-яё\\s]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val normTitle = when {
                low.contains("fomc") || low.contains("fed interest rate") || low.contains("federal funds rate") ->
                    "us_fomc_rate_decision"
                low.contains("cpi") || low.contains("consumer price index") ->
                    "macro_cpi_release"
                low.contains("non farm") || low.contains("nonfarm") || low.contains("nfp") ->
                    "us_nfp_payrolls"
                low.contains("gdp") -> "macro_gdp_release"
                low.contains("pce") && low.contains("price") -> "us_core_pce"
                low.contains("ecb") && (low.contains("rate") || low.contains("interest")) ->
                    "ecb_rate_decision"
                else -> low.take(56).replace(" ", "_")
            }
            val day = utcDayString(tsMs)
            return "${category.name}:${asset.uppercase(Locale.ROOT)}:$day:$normTitle"
        }

        fun parseForexFactoryJson(rawJson: String, nowMs: Long): List<CalendarEvent> {
            return try {
                val arr = JSONArray(rawJson)
                val out = ArrayList<CalendarEvent>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val impact = o.optString("impact").trim()
                    if (!impact.equals("High", ignoreCase = true)) continue
                    val title = o.optString("title").trim()
                    val country = o.optString("country", "USD").trim().uppercase(Locale.ROOT)
                    val ts = parseAnyDateMs(o.optString("date"), o.optString("time"))
                    if (title.isBlank() || ts <= 0L) continue
                    val key = canonicalKey(title, ts, EventCategory.MACRO, country)
                    out.add(
                        CalendarEvent(
                            id = key,
                            canonicalKey = key,
                            title = title,
                            category = EventCategory.MACRO,
                            assetOrCurrency = country,
                            eventTsMs = ts,
                            dateLabelUtc = utcDayString(ts),
                            impact = "HIGH",
                            forecast = o.optString("forecast").trim().ifBlank { null },
                            previous = o.optString("previous").trim().ifBlank { null },
                            source = "ForexFactory",
                            fetchedAtMs = nowMs
                        )
                    )
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun parseBiquoteCalendar(rawJson: String, nowMs: Long): List<CalendarEvent> {
            return try {
                val trimmed = rawJson.trimStart()
                val arr = if (trimmed.startsWith("[")) JSONArray(trimmed)
                else JSONObject(trimmed).let {
                    it.optJSONArray("events") ?: it.optJSONArray("data") ?: JSONArray()
                }
                val out = ArrayList<CalendarEvent>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val imp = o.optString("importance").ifBlank { o.optString("impact") }
                    if (imp.isNotBlank() && !imp.equals("high", ignoreCase = true) && imp != "3") continue
                    val title = o.optString("title").ifBlank { o.optString("name") }.trim()
                    val country = o.optString("currency").ifBlank { o.optString("country", "USD") }.uppercase(Locale.ROOT)
                    val ts = o.optLong("timestamp", 0L).let { if (it in 1L..9_999_999_999L) it * 1000L else it }
                        .takeIf { it > 0L }
                        ?: parseAnyDateMs(o.optString("date").ifBlank { o.optString("time") })
                    if (title.isBlank() || ts <= 0L) continue
                    val key = canonicalKey(title, ts, EventCategory.MACRO, country)
                    out.add(
                        CalendarEvent(
                            id = key,
                            canonicalKey = key,
                            title = title,
                            category = EventCategory.MACRO,
                            assetOrCurrency = country,
                            eventTsMs = ts,
                            dateLabelUtc = utcDayString(ts),
                            impact = "HIGH",
                            forecast = o.optString("forecast").trim().ifBlank { null },
                            previous = o.optString("previous").trim().ifBlank { null },
                            source = "Biquote",
                            fetchedAtMs = nowMs
                        )
                    )
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun parseFinanceCalendar(rawJson: String, nowMs: Long): List<CalendarEvent> {
            return try {
                val trimmed = rawJson.trimStart()
                val arr = if (trimmed.startsWith("[")) JSONArray(trimmed)
                else JSONObject(trimmed).let { it.optJSONArray("data") ?: it.optJSONArray("events") ?: JSONArray() }
                val out = ArrayList<CalendarEvent>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val title = o.optString("title").ifBlank { o.optString("event") }.trim()
                    val ccy = o.optString("currency").ifBlank { o.optString("country", "USD") }.uppercase(Locale.ROOT)
                    val ts = parseAnyDateMs(o.optString("date").ifBlank { o.optString("datetime") })
                    if (title.isBlank() || ts <= 0L) continue
                    val key = canonicalKey(title, ts, EventCategory.MACRO, ccy)
                    out.add(
                        CalendarEvent(
                            id = key,
                            canonicalKey = key,
                            title = title,
                            category = EventCategory.MACRO,
                            assetOrCurrency = ccy,
                            eventTsMs = ts,
                            dateLabelUtc = utcDayString(ts),
                            impact = "HIGH",
                            forecast = o.optString("forecast").trim().ifBlank { null },
                            previous = o.optString("previous").trim().ifBlank { null },
                            source = "FinanceCalendar",
                            fetchedAtMs = nowMs
                        )
                    )
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun parseBybitAnnouncements(rawJson: String, nowMs: Long): List<CalendarEvent> {
            return try {
                val root = JSONObject(rawJson)
                if (root.optInt("retCode", -1) != 0) return emptyList()
                val list = root.optJSONObject("result")?.optJSONArray("list") ?: return emptyList()
                val out = ArrayList<CalendarEvent>()
                for (i in 0 until list.length()) {
                    val o = list.optJSONObject(i) ?: continue
                    val title = o.optString("title").trim()
                    val low = title.lowercase(Locale.ROOT)
                    if (title.isBlank() || MAJOR_CRYPTO_KEYWORDS.none { low.contains(it) }) continue
                    val startTs = o.optLong("startDateTimestamp", 0L)
                        .takeIf { it > 0L }
                        ?: o.optLong("dateTimestamp", 0L)
                    if (startTs <= 0L) continue
                    val key = canonicalKey(title, startTs, EventCategory.CRYPTO, "CRYPTO")
                    out.add(
                        CalendarEvent(
                            id = key,
                            canonicalKey = key,
                            title = title,
                            category = EventCategory.CRYPTO,
                            assetOrCurrency = "CRYPTO",
                            eventTsMs = startTs,
                            dateLabelUtc = utcDayString(startTs),
                            impact = "HIGH",
                            source = "Bybit",
                            fetchedAtMs = nowMs
                        )
                    )
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun parseBinanceAnnouncements(rawJson: String, nowMs: Long): List<CalendarEvent> {
            return try {
                val root = JSONObject(rawJson)
                val catalogs = root.optJSONObject("data")?.optJSONArray("catalogs") ?: return emptyList()
                val out = ArrayList<CalendarEvent>()
                for (c in 0 until catalogs.length()) {
                    val articles = catalogs.optJSONObject(c)?.optJSONArray("articles") ?: continue
                    for (i in 0 until articles.length()) {
                        val a = articles.optJSONObject(i) ?: continue
                        val title = a.optString("title").trim()
                        val low = title.lowercase(Locale.ROOT)
                        if (title.isBlank() || MAJOR_CRYPTO_KEYWORDS.none { low.contains(it) }) continue
                        val ts = a.optLong("releaseDate", 0L)
                        if (ts <= 0L) continue
                        val key = canonicalKey(title, ts, EventCategory.CRYPTO, "CRYPTO")
                        out.add(
                            CalendarEvent(
                                id = key,
                                canonicalKey = key,
                                title = title,
                                category = EventCategory.CRYPTO,
                                assetOrCurrency = "CRYPTO",
                                eventTsMs = ts,
                                dateLabelUtc = utcDayString(ts),
                                impact = "HIGH",
                                source = "Binance",
                                fetchedAtMs = nowMs
                            )
                        )
                    }
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun parseAnyDateMs(dateStr: String, timeStr: String = ""): Long {
            val d = dateStr.trim()
            if (d.isBlank()) return 0L
            val combined = if (timeStr.isNotBlank() && !d.contains("T")) "$d $timeStr" else d
            val formats = listOf(
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd",
                "MM-dd-yyyy h:mma",
                "MM-dd-yyyy HH:mm",
                "MM-dd-yyyy"
            )
            for (input in listOf(combined, d)) {
                for (fmt in formats) {
                    try {
                        val sdf = SimpleDateFormat(fmt, Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("UTC")
                        }
                        val parsed = sdf.parse(input)?.time
                        if (parsed != null && parsed > 0L) {
                            if (fmt == "yyyy-MM-dd" || fmt == "MM-dd-yyyy") {
                                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                                cal.timeInMillis = parsed
                                cal.set(Calendar.HOUR_OF_DAY, 12)
                                return cal.timeInMillis
                            }
                            return parsed
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            return 0L
        }
    }
}
