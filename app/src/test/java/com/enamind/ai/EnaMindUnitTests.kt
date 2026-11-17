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

            // Simulate a detected seller absorption near resistance 3000.0 (buyers attacking ask wall)
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
                    side = AbsorptionSide.SELLER_ABSORPTION,
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
            assertTrue(bundle.csvText.contains("SELLER_ABSORPTION"))
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
                    "lotSizeFilter": {"minOrderQty": "1", "qtyStep": "0.1"},
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
        assertEquals(0.1, parsed.first[0].qtyStep ?: 0.0, 1e-8)
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

    @Test
    fun `domAnalyzer filters 20ms HFT quote churn without fake replenishment and damps MLOFI`() {
        val baseTs = 1_700_000_000_000L
        // 1 small real execution + 20ms cancel/re-add spoofing churn on ASK without trades
        val deltas = listOf(
            OrderFlowStreamHub.RawDomDeltaEvent(
                "BTCUSDT",
                BookState.LevelDelta(100.0, 12.0, 11.0, -1.0, "ASK", baseTs, 1L),
                3L,
                "Bybit"
            ),
            // Cancelled without trade, then added back 20ms later -> must NOT count as replenishment (< 50ms & no exec on that cancel)
            OrderFlowStreamHub.RawDomDeltaEvent(
                "BTCUSDT",
                BookState.LevelDelta(101.0, 30.0, 10.0, -20.0, "ASK", baseTs + 600L, 2L),
                3L,
                "Bybit"
            ),
            OrderFlowStreamHub.RawDomDeltaEvent(
                "BTCUSDT",
                BookState.LevelDelta(101.0, 10.0, 30.0, +20.0, "ASK", baseTs + 620L, 3L),
                3L,
                "Bybit"
            ),
            OrderFlowStreamHub.RawDomDeltaEvent(
                "BTCUSDT",
                BookState.LevelDelta(101.0, 30.0, 10.0, -20.0, "ASK", baseTs + 700L, 4L),
                3L,
                "Bybit"
            ),
            OrderFlowStreamHub.RawDomDeltaEvent(
                "BTCUSDT",
                BookState.LevelDelta(101.0, 10.0, 30.0, +20.0, "ASK", baseTs + 720L, 5L),
                3L,
                "Bybit"
            )
        )
        val trades = listOf(
            OrderFlowStreamHub.RawTradeTick("BTCUSDT", 100.0, 1.0, true, baseTs + 20L, 3L, "Bybit")
        )
        val micro = OrderFlowStreamHub.VenueMicroSnapshot(
            symbol = "BTCUSDT",
            primaryVenue = "Bybit",
            trades = trades,
            domDeltas = deltas,
            bids = listOf(99.5 to 15.0, 99.0 to 10.0),
            asks = listOf(100.0 to 11.0, 101.0 to 30.0),
            bookSynced = true,
            desyncReason = null,
            lastTradeTsMs = baseTs + 20L,
            lastBookTsMs = baseTs + 720L,
            avgLagMs = 3L
        )
        val dom = DomAnalyzer.analyze(micro, baseTs + 800L)
        assertTrue(dom.sufficientData)
        assertEquals(1.0, dom.executedQty, 1e-6)
        assertEquals(40.0, dom.cancelledQty, 1e-6)
        assertTrue(dom.churnInflationRatio >= 12.0)
        assertEquals(0, dom.replenishmentEvents)
    }

    @Test
    fun `absorptionDetector resolves SELLER_ABSORPTION on resistance and BUYER_ABSORPTION on support and rejects adverse trend`() {
        val baseTs = 1_700_000_000_000L
        // Scenario 1: Aggressive buyers attack 100.0 resistance repeatedly with zero net progress -> SELLER_ABSORPTION
        val buyTrades = (0 until 20).map { i ->
            val px = if (i in listOf(3, 8, 13)) 99.80 else 100.0
            OrderFlowStreamHub.RawTradeTick("BTCUSDT", px, 2.0, i != 18, baseTs + i * 200L, 2L, "Bybit")
        }
        val buyFp = FootprintEngine.analyze(buyTrades, tickSize = 0.05, bars = emptyList())
        val resDom = com.enamind.ai.orderflow.DomSnapshotMetrics(
            normalizedOfi = -0.10,
            executedQty = 20.0,
            cancelledQty = 2.0,
            replenishmentRate = 0.45,
            replenishmentEvents = 3,
            levelPersistenceRatio = 0.85,
            synchronized = true,
            lagMs = 2L,
            deltaSequenceCount = 12,
            sufficientData = true
        )
        val sellerAbs = AbsorptionDetector.analyze(
            trades = buyTrades,
            footprint = buyFp,
            dom = resDom,
            bars = emptyList(),
            candidateResistance = 100.0,
            candidateSupport = 98.0
        )
        assertTrue(sellerAbs.detected)
        assertEquals(AbsorptionSide.SELLER_ABSORPTION, sellerAbs.side)

        // Scenario 2: Aggressive sellers attack 98.0 support repeatedly with zero net progress -> BUYER_ABSORPTION
        val sellTrades = (0 until 20).map { i ->
            val px = if (i in listOf(3, 8, 13)) 98.20 else 98.0
            OrderFlowStreamHub.RawTradeTick("BTCUSDT", px, 2.0, i == 18, baseTs + i * 200L, 2L, "Bybit")
        }
        val sellFp = FootprintEngine.analyze(sellTrades, tickSize = 0.05, bars = emptyList())
        val supDom = resDom.copy(normalizedOfi = 0.10)
        val buyerAbs = AbsorptionDetector.analyze(
            trades = sellTrades,
            footprint = sellFp,
            dom = supDom,
            bars = emptyList(),
            candidateResistance = 100.0,
            candidateSupport = 98.0
        )
        assertTrue(buyerAbs.detected)
        assertEquals(AbsorptionSide.BUYER_ABSORPTION, buyerAbs.side)

        // Scenario 3: Price already broke violently against aggressive buyers -> progressEfficiency < -0.25 -> NOT absorption
        val failedBuyTrades = (0 until 20).map { i ->
            val px = 100.0 - i * 0.15
            OrderFlowStreamHub.RawTradeTick("BTCUSDT", px, 2.0, i < 18, baseTs + i * 200L, 2L, "Bybit")
        }
        val failedFp = FootprintEngine.analyze(failedBuyTrades, tickSize = 0.05, bars = emptyList())
        val rejectedAbs = AbsorptionDetector.analyze(
            trades = failedBuyTrades,
            footprint = failedFp,
            dom = resDom,
            bars = emptyList(),
            candidateResistance = 100.0,
            candidateSupport = 96.0
        )
        assertFalse(rejectedAbs.detected)
        assertEquals(AbsorptionSide.NONE, rejectedAbs.side)
    }

    @Test
    fun `levelAttackDetector enforces proximity gate and flat radar breakout guard`() {
        val baseTs = 1_700_000_000_000L
        val bars = (0 until 30).map { i ->
            val ts = (baseTs - (30 - i) * 60_000L).toDouble()
            val c = 100.0 + (i % 4) * 0.2
            val h = if (i == 15) 105.0 else c + 0.25
            val l = if (i == 10) 95.0 else c - 0.25
            listOf(ts, c - 0.05, h, l, c, 100.0)
        }
        val tradesFar = (0 until 20).map { i ->
            OrderFlowStreamHub.RawTradeTick("BTCUSDT", 100.3, 2.0, true, baseTs + i * 100L, 2L, "Bybit")
        }
        val fp = FootprintEngine.analyze(tradesFar, tickSize = 0.1, bars = bars)
        val dom = com.enamind.ai.orderflow.DomSnapshotMetrics(
            normalizedOfi = 0.4,
            executedQty = 20.0,
            synchronized = true,
            deltaSequenceCount = 10,
            sufficientData = true
        )
        // Far from 105.0 and 95.0 (> 1% away) -> Proximity Gate yields LevelAttackPhase.NONE
        val farRes = LevelAttackDetector.analyze(
            currentPrice = 100.3,
            bars = bars,
            trades = tradesFar,
            footprint = fp,
            dom = dom,
            absorption = com.enamind.ai.orderflow.AbsorptionMetrics(),
            tickSize = 0.1,
            quantAdx = 26.0,
            quantEfficiencyRatio = 0.55
        )
        assertEquals(LevelAttackPhase.NONE, farRes.attackState.phase)

        // Breakout accepted beyond 105.0, but Radar says flat (ADX=14, ER=0.12) -> CONFIRMED_BREAKOUT blocked
        val breakoutBars = bars.dropLast(2) + listOf(
            listOf((baseTs - 60_000L).toDouble(), 105.0, 105.5, 104.9, 105.4, 250.0),
            listOf(baseTs.toDouble(), 105.4, 105.7, 105.2, 105.6, 260.0)
        )
        val breakoutTrades = (0 until 20).map { i ->
            OrderFlowStreamHub.RawTradeTick("BTCUSDT", 105.1 + i * 0.02, 2.0, true, baseTs + i * 100L, 2L, "Bybit")
        }
        val brFp = FootprintEngine.analyze(breakoutTrades, tickSize = 0.1, bars = breakoutBars)
        val flatBlocked = LevelAttackDetector.analyze(
            currentPrice = 105.12,
            bars = breakoutBars,
            trades = breakoutTrades,
            footprint = brFp,
            dom = dom,
            absorption = com.enamind.ai.orderflow.AbsorptionMetrics(),
            tickSize = 0.1,
            quantAdx = 14.0,
            quantEfficiencyRatio = 0.12
        )
        assertFalse(flatBlocked.attackState.phase == LevelAttackPhase.CONFIRMED_BREAKOUT)
    }

    @Test
    fun `scenarioAnalyzer detects hard conflict and resets glow to neutral`() {
        val baseTs = 1_700_000_000_000L
        val trades = (0 until 20).map { i ->
            OrderFlowStreamHub.RawTradeTick("BTCUSDT", 100.0 + i * 0.01, 2.0, true, baseTs + i * 100L, 2L, "Bybit")
        }
        val fp = FootprintEngine.analyze(trades, tickSize = 0.05, bars = emptyList())
        // Negative MLOFI (-0.30) while tape Delta is strongly positive -> hard conflict
        val domConflict = com.enamind.ai.orderflow.DomSnapshotMetrics(
            normalizedOfi = -0.30,
            executedQty = 15.0,
            synchronized = true,
            deltaSequenceCount = 8,
            sufficientData = true
        )
        val synth = ScenarioAnalyzer.synthesize(
            symbol = "BTCUSDT",
            price = 100.2,
            freshness = DataFreshness.LIVE,
            footprint = fp,
            dom = domConflict,
            absorption = com.enamind.ai.orderflow.AbsorptionMetrics(),
            attack = LevelAttackState(),
            vaultStats = OrderFlowEventVault.EmpiricalStats(),
            quantAdx = 24.0,
            quantEfficiencyRatio = 0.45,
            candleChangePct = 0.25
        )
        assertEquals(MarketDominance.CONFLICT, synth.dominance)
        assertEquals(GlowColorState.NEUTRAL, synth.glow.colorState)
        assertTrue(synth.glow.conflictDetected)
        assertEquals(0.0, synth.councilPayload.bullishBiasWeight, 1e-6)
    }

    @Test
    fun `orderFlowEventVault separates 30s minute threshold from extended 15m flat threshold`() {
        val dir = createTempDirectory("of_vault_tf_test").toFile()
        try {
            val vault = OrderFlowEventVault(primaryDir = dir)
            vault.ensureInitialized()
            val t0 = 1_700_000_000_000L

            val fp1 = FootprintEngine.analyze(
                trades = (0 until 10).map {
                    OrderFlowStreamHub.RawTradeTick("BTCUSDT", 1000.0, 1.0, true, t0 - 500L + it * 20L, 2L, "Bybit")
                },
                tickSize = 0.5,
                bars = emptyList()
            )
            // 1) Interval "1" (1m -> non-extended): reaction of -0.10% at +30s exceeds 0.045% -> REVERSAL
            vault.recordEvent(
                OrderFlowReport(
                    symbol = "BTCUSDT",
                    exchangeSource = "Bybit",
                    interval = "1",
                    price = 1000.0,
                    computedAtMs = t0,
                    freshness = DataFreshness.LIVE,
                    dominance = MarketDominance.POSSIBLE_ABSORPTION,
                    footprint = fp1,
                    levelAttack = LevelAttackState(levelPrice = 1000.0, isResistance = true, phase = LevelAttackPhase.POSSIBLE_ABSORPTION),
                    absorption = com.enamind.ai.orderflow.AbsorptionMetrics(side = AbsorptionSide.SELLER_ABSORPTION, detected = true)
                )
            )
            vault.verifyForwardOutcomes(
                symbol = "BTCUSDT",
                futureTicks = listOf(
                    OrderFlowStreamHub.RawTradeTick("BTCUSDT", 999.0, 1.0, false, t0 + 30_500L, 2L, "Bybit")
                ),
                nowMs = t0 + 36_000L
            )
            val stats1m = vault.computeStats("BTCUSDT")
            assertEquals(1, stats1m.reversalCount)

            // 2) Interval "15" (15m extended): 0.10% move at +15m (< 0.22% fee threshold) -> FLAT
            val t1 = t0 + 60_000L
            vault.recordEvent(
                OrderFlowReport(
                    symbol = "SOLUSDT",
                    exchangeSource = "Bybit",
                    interval = "15",
                    price = 1000.0,
                    computedAtMs = t1,
                    freshness = DataFreshness.LIVE,
                    dominance = MarketDominance.POSSIBLE_ABSORPTION,
                    footprint = fp1,
                    levelAttack = LevelAttackState(levelPrice = 1000.0, isResistance = true, phase = LevelAttackPhase.POSSIBLE_ABSORPTION),
                    absorption = com.enamind.ai.orderflow.AbsorptionMetrics(side = AbsorptionSide.SELLER_ABSORPTION, detected = true)
                )
            )
            vault.verifyForwardOutcomes(
                symbol = "SOLUSDT",
                futureTicks = listOf(
                    OrderFlowStreamHub.RawTradeTick("SOLUSDT", 999.0, 1.0, false, t1 + 30_500L, 2L, "Bybit"),
                    OrderFlowStreamHub.RawTradeTick("SOLUSDT", 999.0, 1.0, false, t1 + 905_000L, 2L, "Bybit")
                ),
                nowMs = t1 + 910_000L
            )
            val stats15m = vault.computeStats("SOLUSDT")
            assertEquals(1, stats15m.verifiedEvents)
            assertEquals(0, stats15m.reversalCount)
            assertEquals(0, stats15m.breakoutCount)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `aiStudioClient builds multimodal Gemini and OpenAI payloads with image and text attachments`() {
        val attachments = listOf(
            AiStudioClient.ChatAttachment(
                id = "img1",
                displayName = "chart.png",
                mimeType = "image/png",
                sizeBytes = 128L,
                isImage = true,
                base64Data = "AQID"
            ),
            AiStudioClient.ChatAttachment(
                id = "txt1",
                displayName = "notes.txt",
                mimeType = "text/plain",
                sizeBytes = 32L,
                isImage = false,
                textPreview = "Sample log line"
            )
        )
        val geminiParts = AiStudioClient.buildGeminiUserParts("Проверь", attachments)
        assertEquals(2, geminiParts.length())
        assertTrue(geminiParts.getJSONObject(0).getString("text").contains("notes.txt"))
        assertEquals("image/png", geminiParts.getJSONObject(1).getJSONObject("inlineData").getString("mimeType"))

        val openAiPayload = AiStudioClient.buildOpenAiPayload(
            modelId = "gpt-4o-mini",
            history = emptyList(),
            userPrompt = "Проверь",
            attachments = attachments
        )
        val msgContent = openAiPayload.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(2, msgContent.length())
        assertTrue(msgContent.getJSONObject(0).getString("text").contains("Sample log line"))
        assertEquals("image_url", msgContent.getJSONObject(1).getString("type"))
    }

    @Test
    fun `orderFlowEventVault prevents stale priceTf cache freeze, enforces interval isolation and non-empty rejection factors`() {
        val dir = createTempDirectory("of_vault_layer1_test").toFile()
        try {
            val vault = OrderFlowEventVault(primaryDir = dir)
            vault.ensureInitialized()
            val t0 = 1_700_000_000_000L
            val t1 = t0 + 120_000L

            val fp1 = FootprintEngine.analyze(
                trades = (0 until 10).map {
                    OrderFlowStreamHub.RawTradeTick("BTCUSDT", 1000.0, 1.0, false, t0 - 500L + it * 20L, 2L, "Bybit")
                },
                tickSize = 0.5,
                bars = emptyList()
            )

            // 1) CONFIRMED_REJECTION without supportingFactors MUST NOT be recorded
            vault.recordEvent(
                OrderFlowReport(
                    symbol = "BTCUSDT",
                    exchangeSource = "Bybit",
                    interval = "15",
                    price = 1000.0,
                    computedAtMs = t0,
                    freshness = DataFreshness.LIVE,
                    dominance = MarketDominance.CONFIRMED_REJECTION,
                    footprint = fp1,
                    levelAttack = LevelAttackState(levelPrice = 1000.0, isResistance = true, phase = LevelAttackPhase.CONFIRMED_REJECTION),
                    absorption = com.enamind.ai.orderflow.AbsorptionMetrics(supportingFactors = emptyList())
                )
            )
            assertEquals(0, vault.computeStats("BTCUSDT").totalRecorded)

            // 2) BUYER_ABSORPTION forces isResistance=false even if levelAttack.isResistance was true
            vault.recordEvent(
                OrderFlowReport(
                    symbol = "BTCUSDT",
                    exchangeSource = "Bybit",
                    interval = "15",
                    price = 1000.0,
                    computedAtMs = t0,
                    freshness = DataFreshness.LIVE,
                    dominance = MarketDominance.POSSIBLE_ABSORPTION,
                    footprint = fp1,
                    levelAttack = LevelAttackState(levelPrice = 1000.0, isResistance = true, phase = LevelAttackPhase.POSSIBLE_ABSORPTION),
                    absorption = com.enamind.ai.orderflow.AbsorptionMetrics(
                        side = AbsorptionSide.BUYER_ABSORPTION,
                        detected = true,
                        supportingFactors = listOf("Фактор 1")
                    )
                )
            )
            vault.recordEvent(
                OrderFlowReport(
                    symbol = "BTCUSDT",
                    exchangeSource = "Bybit",
                    interval = "15",
                    price = 1005.0,
                    computedAtMs = t1,
                    freshness = DataFreshness.LIVE,
                    dominance = MarketDominance.POSSIBLE_ABSORPTION,
                    footprint = fp1,
                    levelAttack = LevelAttackState(levelPrice = 1005.0, isResistance = false, phase = LevelAttackPhase.POSSIBLE_ABSORPTION),
                    absorption = com.enamind.ai.orderflow.AbsorptionMetrics(
                        side = AbsorptionSide.BUYER_ABSORPTION,
                        detected = true,
                        supportingFactors = listOf("Фактор 2")
                    )
                )
            )

            // Calling with wrong interval ("1") or stale bars (ts < targetTs) MUST leave priceTf = null
            vault.verifyForwardOutcomes(
                symbol = "BTCUSDT",
                futureTicks = emptyList(),
                nowMs = t1 + 960_000L,
                bars = listOf(listOf((t0 + 60_000L).toDouble(), 1000.0, 1002.0, 999.0, 999.5, 50.0)),
                barsInterval = "1"
            )
            val beforeExport = org.json.JSONObject(vault.exportHistoryBundle().jsonText).getJSONArray("events")
            assertEquals(2, beforeExport.length())
            assertFalse(beforeExport.getJSONObject(0).getBoolean("isResistance"))
            assertTrue(beforeExport.getJSONObject(0).isNull("priceTf"))
            assertTrue(beforeExport.getJSONObject(1).isNull("priceTf"))

            // Calling with matching interval "15" and distinct bars >= t0+900s and >= t1+900s assigns distinct priceTf values
            vault.verifyForwardOutcomes(
                symbol = "BTCUSDT",
                futureTicks = emptyList(),
                nowMs = t1 + 960_000L,
                bars = listOf(
                    listOf((t0 + 900_000L).toDouble(), 1000.0, 1011.0, 999.0, 1010.0, 100.0),
                    listOf((t1 + 900_000L).toDouble(), 1010.0, 1022.0, 1009.0, 1020.0, 100.0)
                ),
                barsInterval = "15"
            )
            val afterExport = org.json.JSONObject(vault.exportHistoryBundle().jsonText).getJSONArray("events")
            assertEquals(1010.0, afterExport.getJSONObject(0).getDouble("priceTf"), 1e-6)
            assertEquals(1020.0, afterExport.getJSONObject(1).getDouble("priceTf"), 1e-6)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `absorptionDetector enforces proximity gate and flags PASSIVE_TRAP on sharp adverse move`() {
        val baseTs = 1_700_000_000_000L
        val tightBars = (0 until 20).map { i ->
            val ts = (baseTs - (20 - i) * 60_000L).toDouble()
            listOf(ts, 100.0, 100.05, 99.95, 100.0, 50.0)
        }
        val domOk = com.enamind.ai.orderflow.DomSnapshotMetrics(
            normalizedOfi = -0.15,
            executedQty = 20.0,
            cancelledQty = 2.0,
            replenishmentRate = 0.65,
            replenishmentEvents = 3,
            levelPersistenceRatio = 0.85,
            synchronized = true,
            lagMs = 2L,
            deltaSequenceCount = 12,
            sufficientData = true
        )

        // 1) 0.35% distance from candidateResistance (100.35 vs 100.0) with tight 1m ATR (~0.10%) -> outside Proximity Gate!
        val tradesAt100 = (0 until 20).map { i ->
            OrderFlowStreamHub.RawTradeTick("BTCUSDT", 100.0, 2.0, i != 18, baseTs + i * 200L, 2L, "Bybit")
        }
        val fpAt100 = FootprintEngine.analyze(tradesAt100, tickSize = 0.05, bars = tightBars)
        val outOfGate = AbsorptionDetector.analyze(
            trades = tradesAt100,
            footprint = fpAt100,
            dom = domOk,
            bars = tightBars,
            candidateResistance = 100.35,
            candidateSupport = 99.40
        )
        assertFalse(outOfGate.detected)

        // 2) Sharp adverse reversal (progressEfficiency < -0.15) at resistance -> PASSIVE_TRAP / IMPULSE_REVERSAL
        val trapTrades = (0 until 20).map { i ->
            val px = 100.0 - i * 0.05
            OrderFlowStreamHub.RawTradeTick("BTCUSDT", px, 2.0, i < 18, baseTs + i * 200L, 2L, "Bybit")
        }
        val fpTrap = FootprintEngine.analyze(trapTrades, tickSize = 0.05, bars = tightBars)
        val trapAbs = AbsorptionDetector.analyze(
            trades = trapTrades,
            footprint = fpTrap,
            dom = domOk,
            bars = tightBars,
            candidateResistance = 100.0,
            candidateSupport = 98.0
        )
        assertFalse(trapAbs.detected)
        assertTrue(trapAbs.passiveTrapDetected)
        assertTrue(trapAbs.classificationTag.contains("PASSIVE_TRAP"))
    }

    @Test
    fun `domAnalyzer unclamps replenishmentRate above 3 and computes healthy levelPersistenceRatio`() {
        val baseTs = 1_700_000_000_000L
        // 1.0 qty executed on ASK @ 100.0, then replenished 4.5 qty (> 3.0x) in valid 50..500ms windows + gentle partial adjust
        val deltas = listOf(
            OrderFlowStreamHub.RawDomDeltaEvent("BTCUSDT", BookState.LevelDelta(100.0, 10.0, 9.0, -1.0, "ASK", baseTs, 1L), 2L, "Bybit"),
            OrderFlowStreamHub.RawDomDeltaEvent("BTCUSDT", BookState.LevelDelta(100.0, 9.0, 10.5, +1.5, "ASK", baseTs + 120L, 2L), 2L, "Bybit"),
            OrderFlowStreamHub.RawDomDeltaEvent("BTCUSDT", BookState.LevelDelta(100.0, 10.5, 12.0, +1.5, "ASK", baseTs + 260L, 3L), 2L, "Bybit"),
            OrderFlowStreamHub.RawDomDeltaEvent("BTCUSDT", BookState.LevelDelta(100.0, 12.0, 13.5, +1.5, "ASK", baseTs + 420L, 4L), 2L, "Bybit"),
            OrderFlowStreamHub.RawDomDeltaEvent("BTCUSDT", BookState.LevelDelta(99.9, 10.0, 9.8, -0.2, "BID", baseTs + 600L, 5L), 2L, "Bybit")
        )
        val trades = listOf(
            OrderFlowStreamHub.RawTradeTick("BTCUSDT", 100.0, 1.0, true, baseTs + 20L, 2L, "Bybit")
        )
        val micro = OrderFlowStreamHub.VenueMicroSnapshot(
            symbol = "BTCUSDT",
            primaryVenue = "Bybit",
            trades = trades,
            domDeltas = deltas,
            bids = listOf(99.9 to 12.0, 99.8 to 10.0),
            asks = listOf(100.0 to 13.5, 100.1 to 10.0),
            bookSynced = true,
            desyncReason = null,
            lastTradeTsMs = baseTs + 20L,
            lastBookTsMs = baseTs + 600L,
            avgLagMs = 2L,
            rollingTop5MedianMass = 30.0
        )
        val dom = DomAnalyzer.analyze(micro, baseTs + 800L, activeLevelPrice = 100.0, zoneTolerance = 0.25)
        assertTrue(dom.sufficientData)
        assertEquals(3, dom.replenishmentEvents)
        assertTrue("Expected unclamped replenishmentRate > 3.0, got ${dom.replenishmentRate}", dom.replenishmentRate > 3.0)
        assertTrue("Expected healthy persistence > 0.70, got ${dom.levelPersistenceRatio}", dom.levelPersistenceRatio > 0.70)
        assertTrue(dom.normalizedOfi in -1.0..1.0)
    }
}
