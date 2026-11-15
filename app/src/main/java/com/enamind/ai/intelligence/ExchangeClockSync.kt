package com.enamind.ai.intelligence

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * ExchangeClockSync — синхронизация времени с биржевыми серверами (компенсация дрейфа часов Android).
 *
 * Зачем: на мобильных устройствах (особенно после сна/Doze) локальные часы могут уходить
 * на секунды. Смещение искажает возраст свечей, выравнивание кросс-биржевого спреда и момент
 * закрытия 15-минутных окон проверки прогнозов.
 */
class ExchangeClockSync {

    data class ClockSyncStatus(
        val synced: Boolean = false,
        val offsetMs: Long = 0L,
        val rttMs: Long = 0L,
        val source: String = "device",
        val lastSyncMs: Long = 0L
    )

    private val offsetMs = AtomicLong(0L)
    private val rttMs = AtomicLong(0L)
    private val lastSyncMs = AtomicLong(0L)
    @Volatile private var synced = false
    @Volatile private var source = "device"

    fun exchangeNowMs(): Long = System.currentTimeMillis() + offsetMs.get()

    fun status(): ClockSyncStatus = ClockSyncStatus(
        synced = synced,
        offsetMs = offsetMs.get(),
        rttMs = rttMs.get(),
        source = source,
        lastSyncMs = lastSyncMs.get()
    )

    fun syncIfNeeded(
        http: OkHttpClient,
        bybitCooldown: Boolean,
        force: Boolean = false,
        minIntervalMs: Long = 5 * 60_000L
    ): ClockSyncStatus {
        val now = System.currentTimeMillis()
        if (!force && synced && (now - lastSyncMs.get()) < minIntervalMs) {
            return status()
        }

        val samples = mutableListOf<Triple<Long, Long, String>>() // offset, rtt, src

        // 1. Binance data-api / mirror
        fetchTimeSample(
            http,
            "https://data-api.binance.vision/api/v3/time",
            "Binance"
        ) { json -> json.optLong("serverTime", 0L) }?.let { samples += it }

        // 2. OKX public time
        fetchTimeSample(
            http,
            "https://www.okx.com/api/v5/public/time",
            "OKX"
        ) { json ->
            json.optJSONArray("data")?.optJSONObject(0)?.optString("ts")?.toLongOrNull() ?: 0L
        }?.let { samples += it }

        // 3. Bybit V5 time (если не в cooldown)
        if (!bybitCooldown) {
            fetchTimeSample(
                http,
                "https://api.bybit.com/v5/market/time",
                "Bybit"
            ) { json ->
                val res = json.optJSONObject("result")
                val nano = res?.optString("timeNano")?.toLongOrNull()
                val sec = res?.optString("timeSecond")?.toLongOrNull()
                when {
                    nano != null && nano > 0L -> nano / 1_000_000L
                    sec != null && sec > 0L -> sec * 1_000L
                    else -> 0L
                }
            }?.let { samples += it }
        }

        if (samples.isNotEmpty()) {
            // Берём самый быстрый отклик (минимальный RTT = минимальная асимметрия сети)
            val best = samples.minByOrNull { it.second }!!
            val sortedOffsets = samples.map { it.first }.sorted()
            val medianOffset = sortedOffsets[sortedOffsets.size / 2]
            // Защита от абсурдных значений при сбое прокси (> 24 часов)
            val chosen = if (abs(medianOffset) < 86_400_000L) medianOffset else best.first
            offsetMs.set(chosen)
            rttMs.set(best.second)
            source = best.third
            synced = true
            lastSyncMs.set(System.currentTimeMillis())
        }
        return status()
    }

    private fun fetchTimeSample(
        http: OkHttpClient,
        url: String,
        label: String,
        extractor: (JSONObject) -> Long
    ): Triple<Long, Long, String>? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "ENA-MIND/8.3")
                .build()
            val t0 = System.currentTimeMillis()
            http.newCall(req).execute().use { resp ->
                val t1 = System.currentTimeMillis()
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                val srvMs = extractor(JSONObject(body))
                if (srvMs <= 1_600_000_000_000L) return null
                val rtt = (t1 - t0).coerceAtLeast(1L)
                val midLocal = (t0 + t1) / 2L
                Triple(srvMs - midLocal, rtt, label)
            }
        } catch (_: Exception) {
            null
        }
    }
}
