package com.enamind.ai.orderflow

import com.enamind.ai.bybit.BookState
import java.util.concurrent.ConcurrentHashMap

/**
 * Потоковая шина микроструктуры Order Flow в реальном времени.
 *
 * Собирает:
 *  - Каждую исполненную сделку (Bybit publicTrade / Binance aggTrade) с исходным биржевым tsMs и lagMs;
 *  - Последовательные обновления стакана LevelDelta (Bybit orderbook.50 / Binance @depth@100ms diff),
 *    НЕ ограничиваясь снимками раз в секунду;
 *  - Текущее синхронизированное состояние книги заявок и статус DESYNC.
 */
class OrderFlowStreamHub {

    data class RawTradeTick(
        val symbol: String,
        val price: Double,
        val qty: Double,
        val isAggressiveBuy: Boolean,
        val exchangeTsMs: Long,
        val lagMs: Long,
        val venue: String
    )

    data class RawDomDeltaEvent(
        val symbol: String,
        val delta: BookState.LevelDelta,
        val lagMs: Long,
        val venue: String
    )

    data class VenueMicroSnapshot(
        val symbol: String,
        val primaryVenue: String,
        val trades: List<RawTradeTick>,
        val domDeltas: List<RawDomDeltaEvent>,
        val bids: List<Pair<Double, Double>>,
        val asks: List<Pair<Double, Double>>,
        val bookSynced: Boolean,
        val desyncReason: String?,
        val lastTradeTsMs: Long,
        val lastBookTsMs: Long,
        val avgLagMs: Long,
        val rollingTop5MedianMass: Double? = null,
        val wsStale: Boolean = false
    )

    private class SymbolBuffer {
        val trades = ArrayDeque<RawTradeTick>(MAX_TRADES)
        val domDeltas = ArrayDeque<RawDomDeltaEvent>(MAX_DELTAS)
        val top5MassSamples = ArrayDeque<Pair<Long, Double>>(MAX_MASS_SAMPLES)
        var bids: List<Pair<Double, Double>> = emptyList()
        var asks: List<Pair<Double, Double>> = emptyList()
        var bookSynced: Boolean = false
        var wsStale: Boolean = false
        var desyncReason: String? = null
        var primaryVenue: String = ""
        var lastTradeTsMs: Long = 0L
        var lastBookTsMs: Long = 0L
        var lastMassSampleMs: Long = 0L
        var lagSumMs: Long = 0L
        var lagCount: Int = 0
    }

    private val buffers = ConcurrentHashMap<String, SymbolBuffer>()

    fun clearSymbol(symbol: String) {
        buffers.remove(symbol.uppercase())
    }

    fun markWsStale(symbol: String, reason: String, venue: String) {
        val key = symbol.uppercase()
        val buf = buffers.getOrPut(key) { SymbolBuffer() }
        synchronized(buf) {
            buf.wsStale = true
            buf.bookSynced = false
            buf.desyncReason = "$venue: $reason"
        }
    }

    fun recordTrade(
        symbol: String,
        price: Double,
        qty: Double,
        isAggressiveBuy: Boolean,
        exchangeTsMs: Long,
        lagMs: Long,
        venue: String
    ) {
        if (!price.isFinite() || !qty.isFinite() || price <= 0.0 || qty <= 0.0 || exchangeTsMs <= 0L) return
        val key = symbol.uppercase()
        val buf = buffers.getOrPut(key) { SymbolBuffer() }
        synchronized(buf) {
            if (buf.trades.size >= MAX_TRADES) buf.trades.removeFirst()
            buf.trades.addLast(RawTradeTick(key, price, qty, isAggressiveBuy, exchangeTsMs, lagMs.coerceAtLeast(0L), venue))
            buf.lastTradeTsMs = maxOf(buf.lastTradeTsMs, exchangeTsMs)
            buf.primaryVenue = venue
            if (lagMs >= 0L) {
                buf.lagSumMs = if (buf.lagCount > 200) lagMs else buf.lagSumMs + lagMs
                buf.lagCount = if (buf.lagCount > 200) 1 else buf.lagCount + 1
            }
        }
    }

    fun recordDomDeltas(
        symbol: String,
        deltas: List<BookState.LevelDelta>,
        synced: Boolean,
        lagMs: Long,
        venue: String
    ) {
        val key = symbol.uppercase()
        val buf = buffers.getOrPut(key) { SymbolBuffer() }
        synchronized(buf) {
            buf.bookSynced = synced
            if (synced) {
                buf.desyncReason = null
                buf.wsStale = false
            }
            if (deltas.isNotEmpty()) {
                for (d in deltas) {
                    if (buf.domDeltas.size >= MAX_DELTAS) buf.domDeltas.removeFirst()
                    buf.domDeltas.addLast(RawDomDeltaEvent(key, d, lagMs.coerceAtLeast(0L), venue))
                    buf.lastBookTsMs = maxOf(buf.lastBookTsMs, d.tsMs)
                }
            }
            if (venue.isNotBlank()) buf.primaryVenue = venue
        }
    }

    fun recordBookSnapshot(
        symbol: String,
        bids: List<Pair<Double, Double>>,
        asks: List<Pair<Double, Double>>,
        synced: Boolean,
        tsMs: Long,
        venue: String
    ) {
        val key = symbol.uppercase()
        val buf = buffers.getOrPut(key) { SymbolBuffer() }
        synchronized(buf) {
            buf.bids = bids
            buf.asks = asks
            buf.bookSynced = synced
            if (synced) {
                buf.desyncReason = null
                buf.wsStale = false
            }
            buf.lastBookTsMs = maxOf(buf.lastBookTsMs, tsMs)
            if (venue.isNotBlank()) buf.primaryVenue = venue

            // Сэмплируем активный объём L1-L5 (раз в >=500 мс) для скользящей медианы за 60с
            if (synced && bids.isNotEmpty() && asks.isNotEmpty() && (tsMs - buf.lastMassSampleMs) >= 500L) {
                val top5Mass = bids.take(5).sumOf { it.second } + asks.take(5).sumOf { it.second }
                if (top5Mass.isFinite() && top5Mass > 0.0) {
                    buf.lastMassSampleMs = tsMs
                    if (buf.top5MassSamples.size >= MAX_MASS_SAMPLES) buf.top5MassSamples.removeFirst()
                    buf.top5MassSamples.addLast(tsMs to top5Mass)
                    while (buf.top5MassSamples.isNotEmpty() && buf.top5MassSamples.first().first < tsMs - MASS_WINDOW_MS) {
                        buf.top5MassSamples.removeFirst()
                    }
                }
            }
        }
    }

    fun recordDesync(symbol: String, reason: String, venue: String) {
        val key = symbol.uppercase()
        val buf = buffers.getOrPut(key) { SymbolBuffer() }
        synchronized(buf) {
            buf.bookSynced = false
            buf.desyncReason = "$venue: $reason"
        }
    }

    fun snapshot(
        symbol: String,
        nowMs: Long,
        tradeWindowMs: Long = 180_000L,
        domWindowMs: Long = 90_000L
    ): VenueMicroSnapshot? {
        val key = symbol.uppercase()
        val buf = buffers[key] ?: return null
        return synchronized(buf) {
            val recentTrades = buf.trades.filter { it.exchangeTsMs >= nowMs - tradeWindowMs }
            val recentDeltas = buf.domDeltas.filter { it.delta.tsMs >= nowMs - domWindowMs }
            val avgLag = if (buf.lagCount > 0) buf.lagSumMs / buf.lagCount else -1L
            val massVals = buf.top5MassSamples
                .asSequence()
                .filter { it.first >= nowMs - MASS_WINDOW_MS && it.second > 0.0 }
                .map { it.second }
                .sorted()
                .toList()
            val medianMass = if (massVals.isNotEmpty()) massVals[massVals.size / 2] else null
            VenueMicroSnapshot(
                symbol = key,
                primaryVenue = buf.primaryVenue.ifBlank { "Поток" },
                trades = recentTrades,
                domDeltas = recentDeltas,
                bids = buf.bids,
                asks = buf.asks,
                bookSynced = buf.bookSynced,
                desyncReason = buf.desyncReason,
                lastTradeTsMs = buf.lastTradeTsMs,
                lastBookTsMs = buf.lastBookTsMs,
                avgLagMs = avgLag,
                rollingTop5MedianMass = medianMass,
                wsStale = buf.wsStale
            )
        }
    }

    companion object {
        private const val MAX_TRADES = 3000
        private const val MAX_DELTAS = 5000
        private const val MAX_MASS_SAMPLES = 180
        private const val MASS_WINDOW_MS = 60_000L
    }
}
