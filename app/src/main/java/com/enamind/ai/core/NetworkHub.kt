package com.enamind.ai.core

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * NetworkHub — единый пул HTTP-соединений и WebSocket для всей ENA.
 *
 * Решает проблему истощения сокетов, утечки ConnectionPool/Dispatcher-потоков и
 * деградации API-ключей при длительной работе:
 *  - один общий Dispatcher + ConnectionPool вместо десятков OkHttpClient() на каждый цикл;
 *  - локальный реестр per-host HTTP 429 cooldown для защиты от шквального спама;
 *  - переиспользование пула через .newBuilder() только для индивидуальных таймаутов.
 */
object NetworkHub {

    private val sharedPool = ConnectionPool(12, 5, TimeUnit.MINUTES)
    private val sharedDispatcher = Dispatcher().apply {
        maxRequests = 40
        maxRequestsPerHost = 10
    }

    val baseClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(sharedDispatcher)
        .connectionPool(sharedPool)
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val clientsByProfile = ConcurrentHashMap<String, OkHttpClient>()
    private val wsClientsByPing = ConcurrentHashMap<Long, OkHttpClient>()
    private val rateLimitedUntil = ConcurrentHashMap<String, Long>()

    fun restClient(
        connectTimeoutSec: Long = 7L,
        readTimeoutSec: Long = 10L,
        writeTimeoutSec: Long = 10L
    ): OkHttpClient {
        val key = "${connectTimeoutSec}_${readTimeoutSec}_${writeTimeoutSec}"
        return clientsByProfile.getOrPut(key) {
            baseClient.newBuilder()
                .connectTimeout(connectTimeoutSec.coerceAtLeast(1L), TimeUnit.SECONDS)
                .readTimeout(readTimeoutSec.coerceAtLeast(1L), TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSec.coerceAtLeast(1L), TimeUnit.SECONDS)
                .build()
        }
    }

    fun wsClient(
        pingIntervalSec: Long = 20L,
        connectTimeoutSec: Long = 12L
    ): OkHttpClient {
        val key = pingIntervalSec * 1000L + connectTimeoutSec
        return wsClientsByPing.getOrPut(key) {
            baseClient.newBuilder()
                .connectTimeout(connectTimeoutSec.coerceAtLeast(3L), TimeUnit.SECONDS)
                .readTimeout(0L, TimeUnit.MILLISECONDS)
                .writeTimeout(15L, TimeUnit.SECONDS)
                .pingInterval(pingIntervalSec.coerceAtLeast(5L), TimeUnit.SECONDS)
                .build()
        }
    }

    fun recordRateLimit(host: String, cooldownSec: Long = 20L, nowMs: Long = System.currentTimeMillis()) {
        if (host.isBlank()) return
        val until = nowMs + cooldownSec.coerceIn(5L, 120L) * 1000L
        rateLimitedUntil[host] = maxOf(rateLimitedUntil[host] ?: 0L, until)
    }

    fun clearRateLimit(host: String) {
        if (host.isNotBlank()) rateLimitedUntil.remove(host)
    }

    fun isHostRateLimited(host: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = rateLimitedUntil[host] ?: return false
        if (nowMs >= until) {
            rateLimitedUntil.remove(host)
            return false
        }
        return true
    }

    fun isAnyExchangeCooldown(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (rateLimitedUntil.isEmpty()) return false
        var active = false
        val it = rateLimitedUntil.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (nowMs >= e.value) it.remove() else active = true
        }
        return active
    }

    fun extractHost(url: String): String =
        url.substringAfter("//", url).substringBefore("/")
}
