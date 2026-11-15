package com.enamind.ai

import com.enamind.ai.ai.AiStudioClient
import com.enamind.ai.bybit.BookState
import com.enamind.ai.bybit.BybitRest
import com.enamind.ai.bybit.MultiVenueDataBridge
import com.enamind.ai.bybit.TapeAggregator
import com.enamind.ai.core.AppState
import com.enamind.ai.core.FeedStatus
import com.enamind.ai.core.Settings
import com.enamind.ai.intelligence.RealTimeLeadEngine
import com.enamind.ai.intelligence.RealtimePulseVault
import com.enamind.ai.ui.ChatIntentRouter
import com.enamind.ai.ui.mergeLiveBar
import com.enamind.ai.ui.validateBars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnaMindUnitTests {

    @Test
    fun `bookState handles snapshot, delta independent of cross-stream seq, and u=1 reset`() {
        val book = BookState(maxLevels = 50)
        // Snapshot with per-symbol update id u=100
        book.snapshot(
            bidsRaw = listOf(50000.0 to 1.5, 49990.0 to 2.0),
            asksRaw = listOf(50010.0 to 1.0, 50020.0 to 3.0),
            u = 100L
        )
        assertTrue(book.snapshot().synced)

        // Delta with u=101 but much larger cross-stream seq=999_999_999 (must NOT desync)
        val ok = book.delta(
            bidsRaw = listOf(50005.0 to 0.5, 49990.0 to 0.0), // add 50005, delete 49990
            asksRaw = emptyList(),
            u = 101L,
            seq = 999_999_999L
        )
        assertTrue(ok)
        val snap = book.snapshot()
        assertTrue(snap.synced)
        assertEquals(2, snap.bids.size)
        assertEquals(50005.0, snap.bids[0].first, 1e-6)
        assertEquals(50000.0, snap.bids[1].first, 1e-6)

        // Duplicate delivery u <= lastU is ignored gracefully without desync
        assertTrue(book.delta(emptyList(), emptyList(), u = 101L, seq = 1L))
        assertTrue(book.snapshot().synced)

        // Matching-engine restart signal u == 1L triggers DESYNC until next snapshot
        assertFalse(book.delta(emptyList(), emptyList(), u = 1L, seq = 2L))
        assertFalse(book.snapshot().synced)
    }

    @Test
    fun `feedStatus tracker transitions CONNECTING to CONNECTED_NO_DATA to LIVE`() {
        val tracker = FeedStatus.Tracker(
            setOf(FeedStatus.Kind.TICKER, FeedStatus.Kind.BOOK, FeedStatus.Kind.LIQUIDATION)
        )
        assertEquals(FeedStatus.CONNECTING, tracker.overall().status)

        tracker.onOpen()
        assertEquals(FeedStatus.CONNECTED_NO_DATA, tracker.overall().status)

        tracker.onValidMessage(FeedStatus.Kind.TICKER)
        val snap = tracker.snapshot()
        assertEquals(FeedStatus.LIVE, snap[FeedStatus.Kind.TICKER]?.status)
        assertEquals(1L, snap[FeedStatus.Kind.TICKER]?.events)
        assertEquals(FeedStatus.CONNECTED_NO_DATA, snap[FeedStatus.Kind.BOOK]?.status)
    }

    @Test
    fun `validateBars rejects NaN, descending timestamps, and invalid OHLC while accepting sorted valid bars`() {
        val t1 = 1_700_000_000_000.0
        val t2 = 1_700_000_060_000.0

        val valid = listOf(
            listOf(t1, 100.0, 105.0, 99.0, 102.0, 10.0),
            listOf(t2, 102.0, 104.0, 101.0, 103.0, 5.0)
        )
        assertNotNull(validateBars(valid))

        // Descending timestamps (reversed Bybit REST order) must fail
        assertNull(validateBars(valid.reversed()))

        // NaN value must fail
        val withNaN = listOf(
            listOf(t1, 100.0, Double.NaN, 99.0, 102.0, 10.0),
            listOf(t2, 102.0, 104.0, 101.0, 103.0, 5.0)
        )
        assertNull(validateBars(withNaN))

        // High < Close must fail
        val badHigh = listOf(
            listOf(t1, 100.0, 101.0, 99.0, 102.0, 10.0),
            listOf(t2, 102.0, 104.0, 101.0, 103.0, 5.0)
        )
        assertNull(validateBars(badHigh))
    }

    @Test
    fun `mergeLiveBar updates matching bar, appends newer bar, and ignores stale bar`() {
        val t1 = 1_700_000_000_000.0
        val t2 = 1_700_000_060_000.0
        val t3 = 1_700_000_120_000.0
        val history = listOf(
            listOf(t1, 100.0, 105.0, 99.0, 102.0, 10.0),
            listOf(t2, 102.0, 104.0, 101.0, 103.0, 5.0)
        )

        val updated = mergeLiveBar(history, listOf(t2, 102.0, 106.0, 101.0, 105.5, 8.0))
        assertEquals(2, updated.size)
        assertEquals(105.5, updated.last()[4], 1e-6)

        val appended = mergeLiveBar(history, listOf(t3, 103.0, 107.0, 102.5, 106.0, 4.0))
        assertEquals(3, appended.size)
        assertEquals(t3, appended.last()[0], 1e-6)

        val ignoredStale = mergeLiveBar(history, listOf(t1, 90.0, 91.0, 89.0, 90.5, 1.0))
        assertEquals(history, ignoredStale)
    }

    @Test
    fun `bybitRest symbol and interval mappers convert correctly for binance and okx`() {
        assertEquals("BTC", BybitRest.baseCurrency("BTCUSDT"))
        assertEquals("ETH", BybitRest.baseCurrency("eth-usdt-swap"))
        assertEquals("SOLUSDT", BybitRest.toBinanceSymbol("SOLUSDT"))
        assertEquals("BTC-USDT-SWAP", BybitRest.toOkxInstId("BTCUSDT"))
        assertEquals("1m", BybitRest.toBinanceInterval("1"))
        assertEquals("15m", BybitRest.toBinanceInterval("15"))
        assertEquals("1h", BybitRest.toBinanceInterval("60"))
        assertEquals("4h", BybitRest.toBinanceInterval("240"))
        assertEquals("1d", BybitRest.toBinanceInterval("D"))
        assertEquals("1m", BybitRest.toOkxInterval("1"))
        assertEquals("1H", BybitRest.toOkxInterval("60"))
        assertEquals("4H", BybitRest.toOkxInterval("240"))
        assertEquals("1D", BybitRest.toOkxInterval("D"))
    }

    @Test
    fun `chatIntentRouter resolves expanded coins and 6h or 24h commands accurately`() {
        assertEquals("ETHUSDT", ChatIntentRouter.detectSymbolMention("прогноз по эфиру"))
        assertEquals("SOLUSDT", ChatIntentRouter.detectSymbolMention("что с sol на 6ч?"))
        assertEquals("BTCUSDT", ChatIntentRouter.detectSymbolMention("биток тренд"))
        assertEquals("BNBUSDT", ChatIntentRouter.detectSymbolMention("что по bnb?"))
        assertEquals("LINKUSDT", ChatIntentRouter.detectSymbolMention("прогноз chainlink"))
        assertNull(ChatIntentRouter.detectSymbolMention("покажи прогноз"))

        // 15м удалён; запросы прогноза по умолчанию и на 6ч маршрутизируются на свежий 6-часовой прогноз
        assertEquals("outlook6", ChatIntentRouter.resolveCommand("Какой прогноз на 6 часов?"))
        assertEquals("outlook6", ChatIntentRouter.resolveCommand("дай прогноз"))
        assertEquals("trend", ChatIntentRouter.resolveCommand("тренд и структура"))
        assertEquals("agents", ChatIntentRouter.resolveCommand("что говорят агенты?"))
        assertEquals("onchain", ChatIntentRouter.resolveCommand("ончейн комиссии"))
        assertEquals("outlook24", ChatIntentRouter.resolveCommand("сценарий на 24 часа"))
        assertEquals("graph", ChatIntentRouter.resolveCommand("путь рассуждения"))
        assertEquals("council", ChatIntentRouter.resolveCommand("совет и риски"))
        assertEquals("market", ChatIntentRouter.resolveCommand("какая цена и стакан?"))
        assertEquals("news", ChatIntentRouter.resolveCommand("свежие новости"))
        assertEquals("help", ChatIntentRouter.resolveCommand("помощь"))
        assertEquals("radar", ChatIntentRouter.resolveCommand("покажи радар и китов"))
        assertEquals("sentinel", ChatIntentRouter.resolveCommand("страж аномалии и взломы"))
        assertEquals("logic", ChatIntentRouter.resolveCommand("объясни логику почему растет"))
        assertEquals("summary", ChatIntentRouter.resolveCommand("что происходит"))

        assertEquals(10, Settings.SYMBOLS.size)
        assertTrue("BNBUSDT" in Settings.SYMBOLS)
        assertTrue("LINKUSDT" in Settings.SYMBOLS)
    }

    @Test
    fun `aiStudioClient rejects empty api key without network and exposes free fast defaults`() {
        assertEquals("gemini-2.5-flash-lite", Settings.DEFAULT_AI_MODEL)
        assertTrue(AiStudioClient.FREE_MODELS.any { it.id == "gemini-2.5-flash-lite" })
        assertTrue(AiStudioClient.FREE_MODELS.any { it.id == "gemini-2.5-flash" })

        val reply = AiStudioClient.sendChat(
            apiKey = "   ",
            modelId = Settings.DEFAULT_AI_MODEL,
            history = emptyList(),
            userPrompt = "Привет"
        )
        assertFalse(reply.ok)
        assertTrue((reply.error ?: "").contains("Ключ Google AI Studio"))
    }

    @Test
    fun `realTimeLeadEngine detects strong LONG trigger on whale spike and high velocity`() {
        val now = 1_700_000_000_000L
        val bars = (0 until 25).map { i ->
            val ts = (now - (25 - i) * 60_000L).toDouble()
            val px = 64000.0 + i * 22.0
            listOf(ts, px - 5.0, px + 10.0, px - 8.0, px, 100.0 + i * 10.0)
        }
        val market = AppState.MarketUi(
            symbol = "BTCUSDT",
            price = 64620.0,
            feedSource = "Binance + Coinbase",
            tape = TapeAggregator.TapeStats(
                n = 60,
                buyQty = 52.0,
                sellQty = 8.0,
                cvd = 44.0,
                deltaPct = 73.0,
                priceChangePct = 0.85,
                divergence = false
            )
        )
        val venueSnap = MultiVenueDataBridge.VenueSnapshot(
            symbol = "BTCUSDT",
            fetchedAtMs = now,
            coinbasePrice = 64680.0,
            krakenPrice = 64660.0,
            binanceTradeVelocityPerSec = 34.0,
            binanceAggressiveBuyPct = 76.0,
            binanceWhaleSpikeUsd = 240_000.0,
            activeVenueCount = 5
        )
        val pulse = RealTimeLeadEngine.evaluate(
            symbol = "BTCUSDT",
            market = market,
            bars1m = bars,
            binancePrice = 64665.0,
            okxPrice = 64650.0,
            binanceDepth = null,
            venueSnap = venueSnap,
            intel = null,
            vaultWeights = emptyMap(),
            vaultSnap = RealtimePulseVault.PulseSnapshot(accuracyPct = 68.0, verifiedCount = 12),
            nowMs = now
        )
        assertEquals("LONG", pulse.direction)
        assertTrue(pulse.score > 0.0)
        assertTrue(pulse.strongTrigger)
        assertTrue(pulse.triggerReason.isNotBlank())
        assertEquals(68.0, pulse.accuracyPct ?: 0.0, 1e-6)
        assertEquals(12, pulse.verifiedCount)
    }

    @Test
    fun `quant kernel sanitizes duplicates and computes high ER R2 on clean uptrend`() {
        val now = 1_700_000_000_000L
        val raw = ArrayList<List<Double>>()
        for (i in 0 until 80) {
            val ts = (now - (80 - i) * 900_000L).toDouble()
            val px = 100.0 + i * 1.2
            raw.add(listOf(ts, px - 0.2, px + 0.4, px - 0.3, px, 500.0 + i * 5))
        }
        raw.add(raw[10])
        raw.add(listOf((now - 10_000L).toDouble(), -1.0, 10.0, 5.0, 8.0, 100.0))

        val quality = com.enamind.ai.intelligence.QuantMathKernel.sanitizeBars(raw, "15", now)
        assertEquals("A", quality.grade)
        assertEquals(80, quality.cleanBars.size)
        assertEquals(1, quality.duplicatesRemoved)
        assertEquals(1, quality.invalidDropped)

        val metrics = com.enamind.ai.intelligence.QuantMathKernel.computeMetrics(quality.cleanBars)
        assertTrue(metrics.efficiencyRatio > 0.85)
        assertTrue(metrics.regressionR2 > 0.90)
        assertTrue(metrics.adx > 25.0)
        assertTrue(metrics.regressionSlopePctPerBar > 0.0)
    }

    @Test
    fun `quant kernel walk forward and log odds calibration preserve unity and shrink low quality`() {
        val now = 1_700_000_000_000L
        val bars = ArrayList<List<Double>>()
        for (i in 0 until 120) {
            val ts = (now - (120 - i) * 900_000L).toDouble()
            val px = 100.0 + i * 0.5 + kotlin.math.sin(i * 0.4) * 0.3
            bars.add(listOf(ts, px - 0.1, px + 0.3, px - 0.3, px, 200.0))
        }
        val wf = com.enamind.ai.intelligence.QuantMathKernel.runWalkForward(bars)
        assertTrue(wf.samples >= 6)
        assertNotNull(wf.hitRatePct)
        assertNotNull(wf.brierScore)

        val (pu, pf, pd) = com.enamind.ai.intelligence.QuantMathKernel.calibrateProbabilities(
            rawUp = 0.72,
            rawFlat = 0.18,
            rawDown = 0.10,
            calibrationFactor = 0.75,
            dataQualityScore = 0.55
        )
        assertEquals(1.0, pu + pf + pd, 1e-6)
        assertTrue(pu < 0.72)
    }
}
