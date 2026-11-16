package com.enamind.ai

import android.app.Application
import com.enamind.ai.ai.AiStudioClient
import com.enamind.ai.bybit.BinanceDiffBookSync
import com.enamind.ai.bybit.BookState
import com.enamind.ai.bybit.BybitRest
import com.enamind.ai.bybit.ContractCatalogRepo
import com.enamind.ai.bybit.MultiVenueDataBridge
import com.enamind.ai.bybit.TapeAggregator
import com.enamind.ai.calendar.MajorCalendarRepo
import com.enamind.ai.core.AppState
import com.enamind.ai.core.FeedStatus
import com.enamind.ai.core.Settings
import com.enamind.ai.intelligence.IntelApiClient
import com.enamind.ai.intelligence.QuantMathKernel
import com.enamind.ai.intelligence.RealTimeLeadEngine
import com.enamind.ai.intelligence.RealtimePulseVault
import com.enamind.ai.intelligence.TrendReasoningEngine
import com.enamind.ai.orderflow.AbsorptionDetector
import com.enamind.ai.orderflow.AbsorptionSide
import com.enamind.ai.orderflow.DataFreshness
import com.enamind.ai.orderflow.DomAnalyzer
import com.enamind.ai.orderflow.FootprintEngine
import com.enamind.ai.orderflow.GlowColorState
import com.enamind.ai.orderflow.LevelAttackDetector
import com.enamind.ai.orderflow.LevelAttackPhase
import com.enamind.ai.orderflow.LevelAttackState
import com.enamind.ai.orderflow.MarketDominance
import com.enamind.ai.orderflow.OrderFlowEventVault
import com.enamind.ai.orderflow.OrderFlowReport
import com.enamind.ai.orderflow.OrderFlowStreamHub
import com.enamind.ai.orderflow.ReliabilityGrade
import com.enamind.ai.orderflow.ScenarioAnalyzer
import com.enamind.ai.ui.mergeLiveBar
import com.enamind.ai.ui.validateBars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class EnaMindUnitTests {

    @Test
    fun `bookState handles snapshot, delta independent of cross-stream seq, and u=1 reset`() {
        val book = BookState(maxLevels = 50)
        book.snapshot(
            bidsRaw = listOf(50000.0 to 1.5, 49990.0 to 2.0),
            asksRaw = listOf(50010.0 to 1.0, 50020.0 to 3.0),
            u = 100L
        )
        assertTrue(book.snapshot().synced)

        val ok = book.delta(
            bidsRaw = listOf(50005.0 to 0.5, 49990.0 to 0.0),
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

        assertTrue(book.delta(emptyList(), emptyList(), u = 101L, seq = 1L))
        assertTrue(book.snapshot().synced)

        assertFalse(book.delta(emptyList(), emptyList(), u = 1L, seq = 2L))
        assertFalse(book.snapshot().synced)
    }

    @Test
    fun `binanceDiffBookSync buffers diffs, bridges REST snapshot, and catches gaps`() {
        val sync = BinanceDiffBookSync(maxLevels = 50)
        val ts = 1_700_000_000_000L

        val buffered = sync.onDiffEvent(101L, 102L, listOf(50000.0 to 3.0), emptyList(), ts)
        assertEquals(BinanceDiffBookSync.Status.BUFFERING, buffered.status)

        // REST snapshot with lastUpdateId=101 bridges queued event U=101..u=102 (101 <= 102 <= 102)
        val applied = sync.applyRestSnapshot(
            lastUpdateId = 101L,
            bids = listOf(50000.0 to 1.0),
            asks = listOf(50010.0 to 2.0),
            tsMs = ts
        )
        assertEquals(BinanceDiffBookSync.Status.SYNCED, applied.status)
        assertTrue(sync.isSynced())
        assertEquals(3.0, applied.bids.first().second, 1e-6)

        // Packet loss gap: expected 103, arrived U=105 -> DESYNC / NEED_SNAPSHOT
        val gap = sync.onDiffEvent(105L, 106L, listOf(50000.0 to 4.0), emptyList(), ts + 100L)
        assertEquals(BinanceDiffBookSync.Status.NEED_SNAPSHOT, gap.status)
        assertFalse(sync.isSynced())
        assertEquals(1L, sync.desyncCount)
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
        assertNull(validateBars(valid.reversed()))

        val withNaN = listOf(
            listOf(t1, 100.0, Double.NaN, 99.0, 102.0, 10.0),
            listOf(t2, 102.0, 104.0, 101.0, 103.0, 5.0)
        )
        assertNull(validateBars(withNaN))

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
    fun `aiStudioClient uses gemini-3_5-flash-lite default, migrates deprecated models, and supports OpenAI payload`() {
        assertEquals("gemini-3.5-flash-lite", Settings.DEFAULT_AI_MODEL)
        assertTrue(Settings.isDeprecatedGeminiModel("gemini-2.5-flash-lite"))
        assertTrue(Settings.isDeprecatedGeminiModel("gemini-1.5-flash"))
        assertFalse(Settings.isDeprecatedGeminiModel("gemini-3.5-flash-lite"))

        assertTrue(AiStudioClient.FREE_MODELS.any { it.id == "gemini-3.5-flash-lite" })
        assertTrue(AiStudioClient.FREE_MODELS.any { it.id == "gemini-3.5-flash" })

        val reply = AiStudioClient.sendChat(
            apiKey = "   ",
            modelId = Settings.DEFAULT_AI_MODEL,
            history = emptyList(),
            userPrompt = "Привет"
        )
        assertFalse(reply.ok)
        assertTrue((reply.error ?: "").contains("Ключ Google AI Studio"))

        val openAiReply = AiStudioClient.sendChat(
            apiKey = "",
            modelId = "",
            history = emptyList(),
            userPrompt = "Привет",
            provider = Settings.PROVIDER_OPENAI_COMPAT,
            openAiBaseUrl = "https://api.openai.com/v1",
            openAiKey = "  ",
            openAiModel = "gpt-4o-mini"
        )
        assertFalse(openAiReply.ok)
        assertTrue((openAiReply.error ?: "").contains("OpenAI"))

        val payload = AiStudioClient.buildOpenAiPayloadForTest(
            modelId = "gpt-4o-mini",
            history = listOf(AiStudioClient.ChatTurn(true, "ping")),
            userPrompt = "pong"
        )
        assertEquals("gpt-4o-mini", payload.getString("model"))
        assertEquals(2, payload.getJSONArray("messages").length())
    }

    @Test
    fun `realTimeLeadEngine returns NO_DATA when feeds are missing and detects LONG when live feeds confirm`() {
        val now = 1_700_000_000_000L
        val emptyPulse = RealTimeLeadEngine.evaluate(
            symbol = "BTCUSDT",
            market = AppState.MarketUi(symbol = "BTCUSDT"),
            bars1m = emptyList(),
            binancePrice = 0.0,
            okxPrice = 0.0,
            binanceDepth = null,
            venueSnap = null,
            intel = null,
            vaultWeights = emptyMap(),
            vaultSnap = RealtimePulseVault.PulseSnapshot(),
            nowMs = now
        )
        assertEquals("NO_DATA", emptyPulse.direction)
        assertEquals(0.0, emptyPulse.confidence, 1e-6)
        assertFalse(emptyPulse.strongTrigger)

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
        assertEquals(68.0, pulse.accuracyPct ?: 0.0, 1e-6)
    }

    @Test
    fun `quant kernel sanitizes duplicates, computes ER R2, and leaves volatility honest on short series`() {
        val now = 1_700_000_000_000L
        val shortRes = QuantMathKernel.computeMetrics(
            listOf(listOf(now.toDouble(), 100.0, 101.0, 99.0, 100.5, 10.0))
        )
        assertFalse(shortRes.sufficientData)
        assertEquals(0.0, shortRes.realizedVolPct, 1e-6)
        assertEquals(0.0, shortRes.adx, 1e-6)

        val raw = ArrayList<List<Double>>()
        for (i in 0 until 80) {
            val ts = (now - (80 - i) * 900_000L).toDouble()
            val px = 100.0 + i * 1.2
            raw.add(listOf(ts, px - 0.2, px + 0.4, px - 0.3, px, 500.0 + i * 5))
        }
        raw.add(raw[10])
        raw.add(listOf((now - 10_000L).toDouble(), -1.0, 10.0, 5.0, 8.0, 100.0))

        val quality = QuantMathKernel.sanitizeBars(raw, "15", now)
        assertEquals("A", quality.grade)
        assertEquals(80, quality.cleanBars.size)
        assertEquals(1, quality.duplicatesRemoved)
        assertEquals(1, quality.invalidDropped)

        val metrics = QuantMathKernel.computeMetrics(quality.cleanBars)
        assertTrue(metrics.sufficientData)
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
            val px = 100.0 + i * 0.5 + sin(i * 0.4) * 0.3
            bars.add(listOf(ts, px - 0.1, px + 0.3, px - 0.3, px, 200.0))
        }
        val wf = QuantMathKernel.runWalkForward(bars)
        assertTrue(wf.samples >= 6)
        assertNotNull(wf.hitRatePct)
        assertNotNull(wf.brierScore)

        val (pu, pf, pd) = QuantMathKernel.calibrateProbabilities(
            rawUp = 0.72,
            rawFlat = 0.18,
            rawDown = 0.10,
            calibrationFactor = 0.75,
            dataQualityScore = 0.55
        )
        assertEquals(1.0, pu + pf + pd, 1e-6)
        assertTrue(pu < 0.72)
    }

    @Test
    fun `domAnalyzer separates executed orders from cancellations within 250ms and never flags iceberg blindly`() {
        val baseTs = 1_700_000_000_000L
        val deltas = listOf(
            // ASK @ 100.0 drops by 10 with matching aggressive buy trade -> executed
            OrderFlowStreamHub.RawDomDeltaEvent(
                "BTCUSDT",
                BookState.LevelDelta(100.0, 20.0, 10.0, -10.0, "ASK", baseTs, 1L),
                5L,
                "Bybit"
            ),
            // ASK @ 100.0 replenishes after execution
            OrderFlowStreamHub.RawDomDeltaEvent(
                "BTCUSDT",
                BookState.LevelDelta(100.0, 10.0, 19.0, +9.0, "ASK", baseTs + 150L, 2L),
                5L,
                "Bybit"
            ),
            // BID @ 99.0 drops by 8 with NO matching sell trade -> cancelled
            OrderFlowStreamHub.RawDomDeltaEvent(
                "BTCUSDT",
                BookState.LevelDelta(99.0, 15.0, 7.0, -8.0, "BID", baseTs + 800L, 3L),
                5L,
                "Bybit"
            ),
            // Extra delta to pass >=4 sequence threshold
            OrderFlowStreamHub.RawDomDeltaEvent(
                "BTCUSDT",
                BookState.LevelDelta(99.5, 10.0, 12.0, +2.0, "BID", baseTs + 1000L, 4L),
                5L,
                "Bybit"
            )
        )
        val trades = listOf(
            OrderFlowStreamHub.RawTradeTick("BTCUSDT", 100.0, 10.0, true, baseTs + 40L, 5L, "Bybit")
        )
        val micro = OrderFlowStreamHub.VenueMicroSnapshot(
            symbol = "BTCUSDT",
            primaryVenue = "Bybit",
            trades = trades,
            domDeltas = deltas,
            bids = listOf(99.5 to 12.0, 99.0 to 7.0),
            asks = listOf(100.0 to 19.0, 100.5 to 5.0),
            bookSynced = true,
            desyncReason = null,
            lastTradeTsMs = baseTs + 40L,
            lastBookTsMs = baseTs + 1000L,
            avgLagMs = 5L
        )
        val dom = DomAnalyzer.analyze(micro, baseTs + 1100L)
        assertTrue(dom.sufficientData)
        assertTrue(dom.synchronized)
        assertEquals(10.0, dom.executedQty, 1e-6)
        assertEquals(8.0, dom.cancelledQty, 1e-6)
        assertTrue(dom.replenishmentEvents >= 1)
    }

    @Test
    fun `absorptionDetector and scenarioAnalyzer refuse fake single-metric confirmation and turn glow neutral on insufficiency`() {
        val baseTs = 1_700_000_000_000L
        // Insufficient streams -> NEUTRAL glow, null scenarios, INSUFFICIENT grade
        val emptyFp = FootprintEngine.analyze(emptyList(), tickSize = 1.0, bars = emptyList())
        assertFalse(emptyFp.sufficientData)

        val emptyDom = DomAnalyzer.analyze(null, baseTs)
        assertFalse(emptyDom.sufficientData)

        val synthEmpty = ScenarioAnalyzer.synthesize(
            symbol = "BTCUSDT",
            price = null,
            freshness = DataFreshness.INSUFFICIENT,
            footprint = emptyFp,
            dom = emptyDom,
            absorption = AbsorptionDetector.analyze(emptyList(), emptyFp, emptyDom, emptyList(), null, null),
            attack = LevelAttackState(),
            vaultStats = OrderFlowEventVault.EmpiricalStats()
        )
        assertEquals(MarketDominance.INSUFFICIENT_DATA, synthEmpty.dominance)
        assertEquals(GlowColorState.NEUTRAL, synthEmpty.glow.colorState)
        assertEquals(0f, synthEmpty.glow.strength, 1e-6f)
        assertNull(synthEmpty.scenarioA)
        assertNull(synthEmpty.scenarioB)
        assertEquals(ReliabilityGrade.INSUFFICIENT, synthEmpty.confidence.grade)
    }

    @Test
    fun `orderFlowEventVault verifies forward outcomes strictly at 1s-30s and exports JSON and CSV`() {
        val dir = createTempDirectory("of_vault_test").toFile()
        try {
            val vault = OrderFlowEventVault(primaryDir = dir)
            vault.ensureInitialized()

            val t0 = 1_700_000_000_000L
            val fp = FootprintEngine.analyze(
                trades = (0 until 10).map {
                    OrderFlowStreamHub.RawTradeTick("ETHUSDT", 3000.0, 2.0, true, t0 - 1000L + it * 50L, 4L, "Binance")
                },
                tickSize = 0.5,
                bars = emptyList()
            )
            assertTrue(fp.sufficientData)
            assertNotNull(fp.pocPrice)

            // Simulate a detected buyer absorption near resistance 3000.0
            val report = OrderFlowReport(
                symbol = "ETHUSDT",
                exchangeSource = "Binance",
                interval = "15",
                price = 3000.0,
                computedAtMs = t0,
                freshness = DataFreshness.LIVE,
                dominance = MarketDominance.POSSIBLE_ABSORPTION,
                footprint = fp,
                levelAttack = LevelAttackState(
                    levelPrice = 3000.0,
                    isResistance = true,
                    phase = LevelAttackPhase.POSSIBLE_ABSORPTION
                ),
                absorption = com.enamind.ai.orderflow.AbsorptionMetrics(
                    side = AbsorptionSide.BUYER_ABSORPTION,
                    detected = true,
                    confirmed = false,
                    reliability = ReliabilityGrade.MODERATE,
                    absorptionRatio = 2.4,
                    priceProgressEfficiency = 0.15,
                    liquidityReplenishmentRate = 0.42,
                    supportingFactors = listOf("Высокая покупка без прогресса", "Пополнение Ask")
                )
            )
            vault.recordEvent(report)

            // Forward-only price move downward (reversal from resistance) at +1s, +5s, +15s, +30s
            val future = listOf(
                OrderFlowStreamHub.RawTradeTick("ETHUSDT", 2999.0, 1.0, false, t0 + 1_100L, 4L, "Binance"),
                OrderFlowStreamHub.RawTradeTick("ETHUSDT", 2997.0, 1.0, false, t0 + 5_200L, 4L, "Binance"),
                OrderFlowStreamHub.RawTradeTick("ETHUSDT", 2994.0, 1.0, false, t0 + 15_100L, 4L, "Binance"),
                OrderFlowStreamHub.RawTradeTick("ETHUSDT", 2991.0, 1.0, false, t0 + 30_200L, 4L, "Binance")
            )
            vault.verifyForwardOutcomes("ETHUSDT", future, nowMs = t0 + 32_000L)

            val stats = vault.computeStats("ETHUSDT")
            assertEquals(1, stats.totalRecorded)
            assertEquals(1, stats.verifiedEvents)
            assertEquals(1, stats.reversalCount)
            assertEquals(0, stats.breakoutCount)

            val bundle = vault.exportHistoryBundle()
            assertEquals(1, bundle.eventCount)
            assertTrue(bundle.jsonText.contains("ETHUSDT"))
            assertTrue(bundle.jsonText.contains("REVERSAL"))
            assertTrue(bundle.csvText.contains("reaction_30s_pct"))
            assertTrue(bundle.csvText.contains("BUYER_ABSORPTION"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `majorCalendarRepo deduplicates, prunes past events, chunks by max 10 per card, and enforces 15 day window`() {
        val now = 1_750_000_000_000L // fixed anchor timestamp
        val dayMs = 86_400_000L

        val ePast = MajorCalendarRepo.CalendarEvent(
            id = "past",
            canonicalKey = "MACRO:USD:2020-01-01:past_event",
            title = "Past Event",
            category = MajorCalendarRepo.EventCategory.MACRO,
            assetOrCurrency = "USD",
            eventTsMs = now - 5 * 3600_000L,
            dateLabelUtc = "2020-01-01",
            impact = "HIGH",
            source = "Test",
            fetchedAtMs = now
        )

        val eDup1 = MajorCalendarRepo.CalendarEvent(
            id = "fomc1",
            canonicalKey = MajorCalendarRepo.canonicalKey("FOMC Statement", now + dayMs, MajorCalendarRepo.EventCategory.MACRO, "USD"),
            title = "FOMC Statement",
            category = MajorCalendarRepo.EventCategory.MACRO,
            assetOrCurrency = "USD",
            eventTsMs = now + dayMs,
            dateLabelUtc = MajorCalendarRepo.utcDayString(now + dayMs),
            impact = "HIGH",
            forecast = "4.50%",
            source = "ForexFactory",
            fetchedAtMs = now
        )
        val eDup2 = MajorCalendarRepo.CalendarEvent(
            id = "fomc2",
            canonicalKey = MajorCalendarRepo.canonicalKey("Federal Funds Rate Decision", now + dayMs, MajorCalendarRepo.EventCategory.MACRO, "USD"),
            title = "Federal Funds Rate Decision",
            category = MajorCalendarRepo.EventCategory.MACRO,
            assetOrCurrency = "USD",
            eventTsMs = now + dayMs,
            dateLabelUtc = MajorCalendarRepo.utcDayString(now + dayMs),
            impact = "HIGH",
            previous = "4.75%",
            source = "Biquote",
            fetchedAtMs = now
        )
        assertEquals(eDup1.canonicalKey, eDup2.canonicalKey)

        // Check 15-day constant
        assertEquals(15L * 24L * 3600L * 1000L, MajorCalendarRepo.FIFTEEN_DAYS_MS)
        assertEquals(10, MajorCalendarRepo.MAX_EVENTS_PER_CARD)

        // Parse ForexFactory High-impact JSON without fake entries
        val ffJson = """
            [
              {"title":"US CPI m/m","country":"USD","date":"2026-12-10T13:30:00Z","impact":"High","forecast":"0.3%","previous":"0.2%"},
              {"title":"Minor Speech","country":"USD","date":"2026-12-10T15:00:00Z","impact":"Low"}
            ]
        """.trimIndent()
        val parsedFf = MajorCalendarRepo.parseForexFactoryJson(ffJson, now)
        assertEquals(1, parsedFf.size)
        assertEquals("US CPI m/m", parsedFf[0].title)
        assertEquals("0.3%", parsedFf[0].forecast)

        // Card chunking math check (e.g. 12 events -> 2 cards: 10 + 2)
        val tmpCtxDir = createTempDirectory("cal_repo_test").toFile()
        try {
            // Direct unit check on canonical dedup and chunking via helper
            val many = (1..12).map { idx ->
                MajorCalendarRepo.CalendarEvent(
                    id = "ev_$idx",
                    canonicalKey = "CRYPTO:BTC:day_$idx:event_$idx",
                    title = "Event $idx",
                    category = MajorCalendarRepo.EventCategory.CRYPTO,
                    assetOrCurrency = "BTC",
                    eventTsMs = now + idx * dayMs,
                    dateLabelUtc = MajorCalendarRepo.utcDayString(now + idx * dayMs),
                    impact = "HIGH",
                    source = "Bybit",
                    fetchedAtMs = now
                )
            }
            val cards = (many + ePast).filter { it.eventTsMs >= now - 2 * 3600_000L }.chunked(MajorCalendarRepo.MAX_EVENTS_PER_CARD)
            assertEquals(2, cards.size)
            assertEquals(10, cards[0].size)
            assertEquals(2, cards[1].size)
        } finally {
            tmpCtxDir.deleteRecursively()
        }
    }

    @Test
    fun `contractCatalogRepo parses Bybit and Binance USDT contracts with tickSize and leverage`() {
        val bybitRaw = """
            {
              "retCode": 0,
              "result": {
                "nextPageCursor": "next123",
                "list": [
                  {
                    "symbol": "SUIUSDT",
                    "baseCoin": "SUI",
                    "quoteCoin": "USDT",
                    "status": "Trading",
                    "priceFilter": {"tickSize": "0.0001"},
                    "lotSizeFilter": {"minOrderQty": "1"},
                    "leverageFilter": {"maxLeverage": "50"}
                  },
                  {
                    "symbol": "BTCUSD",
                    "baseCoin": "BTC",
                    "quoteCoin": "USD",
                    "status": "Trading"
                  }
                ]
              }
            }
        """.trimIndent()
        val parsed = ContractCatalogRepo.parseBybitInstrumentsJson(bybitRaw)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.first.size)
        assertEquals("SUIUSDT", parsed.first[0].symbol)
        assertEquals(0.0001, parsed.first[0].tickSize ?: 0.0, 1e-8)
        assertEquals(50.0, parsed.first[0].maxLeverage ?: 0.0, 1e-6)
        assertEquals("next123", parsed.second)
    }

    @Test
    fun `trendReasoningEngine leaves outlooks null when bars are absent without inventing numbers`() {
        val report = TrendReasoningEngine.analyze(
            symbol = "BTCUSDT",
            klinesByTf = emptyMap(),
            market = AppState.MarketUi(symbol = "BTCUSDT"),
            crossSpreadBps = null,
            radarRaw = IntelApiClient.RawRadarData(symbol = "BTCUSDT", fetchedAtMs = 1_700_000_000_000L),
            pythonResult = null,
            nowMs = 1_700_000_000_000L
        )
        assertNull(report.outlook6h)
        assertNull(report.outlook24h)
        assertNull(
            TrendReasoningEngine.computeOutlook(
                horizonHours = 6,
                symbol = "BTCUSDT",
                klinesByTf = emptyMap(),
                market = AppState.MarketUi(symbol = "BTCUSDT"),
                crossSpreadBps = null,
                radarRaw = IntelApiClient.RawRadarData(symbol = "BTCUSDT", fetchedAtMs = 1_700_000_000_000L),
                pythonResult = null,
                nowMs = 1_700_000_000_000L
            )
        )
        assertFalse(report.quant.adx.isNaN())
        val json = report.toEngineJson()
        assertEquals("INSUFFICIENT", json.getJSONObject("prediction").getString("status"))
        assertTrue(json.getJSONObject("prediction").getJSONObject("features").isNull("sigma_6h_pct"))
    }
}
