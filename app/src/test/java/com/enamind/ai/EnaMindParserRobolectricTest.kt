package com.enamind.ai

import android.app.Application
import com.enamind.ai.bybit.BybitRest
import com.enamind.ai.core.FaultMonitor
import com.enamind.ai.intelligence.RealTimeLeadEngine
import com.enamind.ai.intelligence.RealtimePulseVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class EnaMindParserRobolectricTest {

    @Test
    fun `bybitRest kline parsers reverse descending feeds and reject error payloads`() {
        val bybitRaw = """
            {"retCode":0,"result":{"list":[
              ["1700000060000","102","104","101","103","5"],
              ["1700000000000","100","105","99","102","10"]
            ]}}
        """.trimIndent()
        val bybitBars = BybitRest.parseBybitKlines(bybitRaw)
        assertEquals(2, bybitBars.size)
        assertEquals(1_700_000_000_000.0, bybitBars[0][0], 1e-6)
        assertEquals(1_700_000_060_000.0, bybitBars[1][0], 1e-6)
        assertTrue(BybitRest.parseBybitKlines("""{"retCode":10001,"result":{}}""").isEmpty())

        val binanceRaw = """
            [
              [1700000000000,"100","105","99","102","10"],
              [1700000060000,"102","104","101","103","5"]
            ]
        """.trimIndent()
        val bnBars = BybitRest.parseBinanceKlines(binanceRaw)
        assertEquals(2, bnBars.size)
        assertEquals(102.0, bnBars[0][4], 1e-6)
        assertEquals(103.0, bnBars[1][4], 1e-6)

        val okxRaw = """
            {"code":"0","data":[
              ["1700000060000","102","104","101","103","5"],
              ["1700000000000","100","105","99","102","10"]
            ]}
        """.trimIndent()
        val okxBars = BybitRest.parseOkxKlines(okxRaw)
        assertEquals(2, okxBars.size)
        assertEquals(1_700_000_000_000.0, okxBars[0][0], 1e-6)
        assertEquals(1_700_000_060_000.0, okxBars[1][0], 1e-6)
        assertTrue(BybitRest.parseOkxKlines("""{"code":"51000","data":[]}""").isEmpty())
    }

    @Test
    fun `crypto sentinel classifies anomalies and rss risk events and exports engine json`() {
        val now = 1_700_000_000_000L
        val bars = (0 until 35).map { i ->
            val ts = (now - (35 - i) * 60_000L).toDouble()
            if (i == 34) {
                listOf(ts, 100.0, 108.0, 99.8, 107.5, 900.0)
            } else {
                listOf(ts, 100.0, 100.2, 99.8, 100.05, 100.0)
            }
        }
        val newsArr = org.json.JSONArray().apply {
            put(org.json.JSONObject().put("title", "Major DeFi protocol hit by $20M exploit").put("source", "CoinDesk"))
            put(org.json.JSONObject().put("title", "Token unlock schedule adds $50M supply").put("source", "TheBlock"))
        }
        val market = com.enamind.ai.core.AppState.MarketUi(
            symbol = "ETHUSDT",
            price = 107.5,
            feedSource = "Binance + OKX",
            fundingPct = 0.045,
            liq1hUsd = 2_000_000.0,
            news = newsArr
        )
        val rawRadar = com.enamind.ai.intelligence.IntelApiClient.RawRadarData(
            symbol = "ETHUSDT",
            fetchedAtMs = now,
            whaleTradesCount = 3,
            whaleBuyUsd = 500_000.0,
            whaleSellUsd = 100_000.0,
            sources = mapOf("Киты Binance (≥$100K)" to "LIVE")
        )

        val report = com.enamind.ai.intelligence.TrendReasoningEngine.analyze(
            symbol = "ETHUSDT",
            klinesByTf = mapOf("15" to bars),
            market = market,
            crossSpreadBps = 35.0,
            radarRaw = rawRadar,
            pythonResult = null,
            nowMs = now
        )

        val cats = report.sentinelEvents.map { it.category }.toSet()
        assertTrue(com.enamind.ai.intelligence.SentinelCategory.VOLATILITY_SHOCK in cats)
        assertTrue(com.enamind.ai.intelligence.SentinelCategory.VOLUME_SPIKE in cats)
        assertTrue(com.enamind.ai.intelligence.SentinelCategory.EXCHANGE_SPREAD in cats)
        assertTrue(com.enamind.ai.intelligence.SentinelCategory.LIQUIDATION_WAVE in cats)
        assertTrue(com.enamind.ai.intelligence.SentinelCategory.FUNDING_EXTREME in cats)
        assertTrue(com.enamind.ai.intelligence.SentinelCategory.SECURITY_EXPLOIT in cats)
        assertTrue(com.enamind.ai.intelligence.SentinelCategory.TOKEN_UNLOCK in cats)

        val engineJson = report.toEngineJson()
        assertTrue(engineJson.optBoolean("ok"))
        assertEquals("ETHUSDT", engineJson.optString("symbol"))
        assertEquals("OK", engineJson.optJSONObject("trend")?.optString("status"))
        assertEquals("OK", engineJson.optJSONObject("prediction")?.optString("status"))
    }

    @Test
    fun `trendReasoningEngine detects uptrend accumulation causal chain and normalizes probabilities`() {
        val now = 1_700_000_000_000L
        val bars = (0 until 60).map { i ->
            val ts = (now - (60 - i) * 60_000L).toDouble()
            val o = 100.0 + i * 0.6
            val c = o + 0.55
            listOf(ts, o, c + 0.2, o - 0.1, c, 100.0 + i * 2)
        }
        val klines = mapOf("1" to bars, "15" to bars, "60" to bars)
        val market = com.enamind.ai.core.AppState.MarketUi(
            symbol = "BTCUSDT",
            price = bars.last()[4],
            feedSource = "Binance",
            fundingPct = -0.025,
            lsrPct = 10.0,
            tape = com.enamind.ai.bybit.TapeAggregator.TapeStats(
                n = 200, buyQty = 150.0, sellQty = 50.0, cvd = 100.0,
                deltaPct = 50.0, priceChangePct = 1.2, divergence = false
            )
        )
        val rawRadar = com.enamind.ai.intelligence.IntelApiClient.RawRadarData(
            symbol = "BTCUSDT",
            fetchedAtMs = now,
            whaleTradesCount = 8,
            whaleBuyUsd = 2_400_000.0,
            whaleSellUsd = 400_000.0,
            mempoolTxCount = 45_000,
            feeFastSat = 22,
            feeHalfHourSat = 18,
            stablecoinsTotalUsd = 210_000_000_000.0,
            stablecoinsChange7dPct = 1.15,
            defiTotalTvlUsd = 110_000_000_000.0,
            sources = mapOf("Киты Binance (≥$100K)" to "LIVE")
        )

        val report = com.enamind.ai.intelligence.TrendReasoningEngine.analyze(
            symbol = "BTCUSDT",
            klinesByTf = klines,
            market = market,
            crossSpreadBps = 2.0,
            radarRaw = rawRadar,
            pythonResult = null,
            nowMs = now
        )

        assertEquals("BULLISH", report.trendLogic.biasLabel)
        assertEquals("ACCUMULATION", report.chainRadar.moneyFlowBias)
        assertTrue(report.trendLogic.causalChain.size >= 4)
        assertTrue(report.quant.adx > 0.0)
        assertTrue(report.quant.efficiencyRatio > 0.5)
        assertTrue(report.quant.regressionR2 > 0.8)
        assertTrue(report.toEngineJson().has("quant_diagnostics"))
        val probSum = report.trendLogic.pUp + report.trendLogic.pFlat + report.trendLogic.pDown
        assertEquals(1.0, probSum, 1e-6)
        assertTrue(report.trendLogic.pUp > report.trendLogic.pDown)

        val probLine = com.enamind.ai.ui.formatLogicProbabilityLine(report.trendLogic)
        assertTrue(probLine.contains("P(UP) "))
        assertTrue(probLine.contains("ожид."))
    }

    @Test
    fun trend_memory_vault_records_verifies_6h_horizon_and_adapts_bot_weights() {
        val primary = java.nio.file.Files.createTempDirectory("vault_primary").toFile()
        val mirror = java.nio.file.Files.createTempDirectory("vault_mirror").toFile()
        try {
            val vault = com.enamind.ai.intelligence.TrendMemoryVault(primary, mirror)
            vault.ensureInitialized()
            assertTrue(java.io.File(primary, "learning_summary.json").isFile)
            assertTrue(java.io.File(mirror, "learning_summary.json").isFile)

            val baseTs = 1_700_000_000_000L
            val horizonMs = com.enamind.ai.intelligence.TrendMemoryVault.DEFAULT_HORIZON_MIN * 60_000L
            // Создаём 6 бакетов по 6ч с прогнозом UP, где bot_mtf голосует UP, а цена реально растёт
            for (i in 0 until 6) {
                val obsTs = baseTs + i * (horizonMs + 120_000L)
                val entryPx = 50_000.0 + i * 100.0
                val mockReport = com.enamind.ai.intelligence.UnifiedIntelligenceReport(
                    symbol = "BTCUSDT",
                    price = entryPx,
                    sourceLabel = "Binance",
                    pythonEngineOk = false,
                    trendLogic = com.enamind.ai.intelligence.TrendLogicSummary(
                        biasLabel = "BULLISH",
                        phase = "ВОСХОДЯЩАЯ СТРУКТУРА",
                        strength = 75,
                        entryQuality = "GOOD",
                        riskLevel = "LOW",
                        pUp = 0.65,
                        pFlat = 0.20,
                        pDown = 0.15,
                        expectedMovePct = 0.45,
                        agreementPct = 80,
                        mtfDirections = emptyMap(),
                        structureLabel = "HH/HL",
                        causalChain = emptyList(),
                        conflicts = emptyList(),
                        invalidationRules = emptyList()
                    ),
                    bots = listOf(
                        com.enamind.ai.intelligence.BotSignal(
                            id = "bot_mtf",
                            name = "Бот-Тренд",
                            category = com.enamind.ai.intelligence.BotCategory.TREND,
                            bias = com.enamind.ai.intelligence.BiasDirection.UP,
                            confidence = 0.80,
                            summary = "UP"
                        ),
                        com.enamind.ai.intelligence.BotSignal(
                            id = "bot_flow",
                            name = "Бот-Поток",
                            category = com.enamind.ai.intelligence.BotCategory.FLOW,
                            bias = com.enamind.ai.intelligence.BiasDirection.DOWN,
                            confidence = 0.60,
                            summary = "DOWN"
                        )
                    ),
                    chainRadar = com.enamind.ai.intelligence.ChainRadarSnapshot(
                        symbol = "BTCUSDT",
                        updatedAtMs = obsTs,
                        preChartAnswer = "Накопление",
                        moneyFlowBias = "ACCUMULATION"
                    ),
                    sentinelEvents = emptyList(),
                    synthesisHeadline = "BULLISH",
                    synthesisNarrative = "Тест",
                    computedAtMs = obsTs
                )
                assertTrue(vault.recordObservation(mockReport, entryPx))

                val exitTs = obsTs + horizonMs + 10_000L
                val exitPx = entryPx * 1.008 // +0.8% реальный рост -> UP
                val bars = listOf(
                    listOf(obsTs.toDouble(), entryPx, entryPx, entryPx, entryPx, 10.0),
                    listOf(exitTs.toDouble(), entryPx, exitPx, entryPx, exitPx, 20.0)
                )
                val verifiedNow = vault.verifyPending("BTCUSDT", bars, exitTs + 5_000L)
                assertEquals(1, verifiedNow)
            }

            val weights = vault.currentWeights()
            val snap = vault.snapshot()
            assertEquals(6, snap.totalRecorded)
            assertEquals(6, snap.verifiedCount)
            assertEquals(100.0, snap.hitRatePct ?: 0.0, 1e-3)
            // Угадавший 6/6 bot_mtf получил вес > 1.0, ошибавшийся bot_flow — вес < 1.0
            assertTrue((weights["bot_mtf"] ?: 1.0) > 1.0)
            assertTrue((weights["bot_flow"] ?: 1.0) < 1.0)
        } finally {
            primary.deleteRecursively()
            mirror.deleteRecursively()
        }
    }

    @Test
    fun realtime_pulse_vault_records_verifies_and_adapts_factor_weights() {
        val primary = java.nio.file.Files.createTempDirectory("pulse_primary").toFile()
        val mirror = java.nio.file.Files.createTempDirectory("pulse_mirror").toFile()
        try {
            val vault = RealtimePulseVault(primary, mirror)
            vault.ensureInitialized()
            assertTrue(java.io.File(primary, "pulse_summary.json").isFile)

            val baseTs = 1_700_000_000_000L
            for (i in 0 until 6) {
                val obsTs = baseTs + i * 6 * 60_000L
                val entryPx = 64_000.0 + i * 50.0
                assertTrue(
                    vault.recordPulse(
                        symbol = "BTCUSDT",
                        entryPrice = entryPx,
                        direction = "LONG",
                        confidence = 0.78,
                        triggerActive = true,
                        factorVotes = mapOf("tape_delta" to "LONG", "venue_velocity" to "SHORT"),
                        horizonMin = 5,
                        nowMs = obsTs
                    )
                )
                val exitTs = obsTs + 5 * 60_000L + 5_000L
                val exitPx = entryPx * 1.004
                val bars = listOf(
                    listOf(obsTs.toDouble(), entryPx, entryPx, entryPx, entryPx, 10.0),
                    listOf(exitTs.toDouble(), entryPx, exitPx, entryPx, exitPx, 20.0)
                )
                val verified = vault.verifyAgainstLiveChart(
                    symbol = "BTCUSDT",
                    bars1m = bars,
                    livePrice = exitPx,
                    nowMs = exitTs + 1_000L
                )
                assertEquals(1, verified)
            }

            val snap = vault.snapshot()
            assertEquals(6, snap.totalRecorded)
            assertEquals(6, snap.verifiedCount)
            assertEquals(100.0, snap.accuracyPct ?: 0.0, 1e-3)
            assertTrue((snap.factorWeights["tape_delta"] ?: 1.0) > 1.0)
            assertTrue((snap.factorWeights["venue_velocity"] ?: 1.0) < 1.0)
        } finally {
            primary.deleteRecursively()
            mirror.deleteRecursively()
        }
    }

    @Test
    fun fault_monitor_records_deduplicates_resolves_and_clears() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        FaultMonitor.init(ctx)
        FaultMonitor.clear()

        FaultMonitor.recordFault(
            component = "TestBybitRest",
            kind = FaultMonitor.Kind.NETWORK,
            severity = FaultMonitor.Severity.ERROR,
            message = "HTTP 403 geoblock",
            recovery = "Переход на Binance"
        )
        // Повторное идентичное сообщение в окне дедупликации не дублируется
        FaultMonitor.recordFault(
            component = "TestBybitRest",
            kind = FaultMonitor.Kind.NETWORK,
            severity = FaultMonitor.Severity.ERROR,
            message = "HTTP 403 geoblock",
            recovery = "Переход на Binance"
        )
        assertEquals(1, FaultMonitor.events.value.count { it.component == "TestBybitRest" })
        assertTrue(FaultMonitor.summary.value.activeFaults >= 1)

        FaultMonitor.markHealthy("TestBybitRest")
        val ev = FaultMonitor.events.value.first { it.component == "TestBybitRest" }
        assertTrue(ev.resolved)

        FaultMonitor.clear()
        assertEquals(0, FaultMonitor.events.value.size)
        assertTrue(FaultMonitor.summary.value.healthy)
    }

    @Test
    fun zip_manager_exports_and_imports_vaults_without_secrets() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val rootDir = java.io.File(ctx.filesDir, "enamind").apply { mkdirs() }
        val vaultDir = java.io.File(rootDir, "learning_vault").apply { mkdirs() }
        val pulseDir = java.io.File(rootDir, "realtime_pulse_vault").apply { mkdirs() }

        java.io.File(vaultDir, "learning_summary.json").writeText("""{"total_recorded":3,"verified_count":2}""")
        java.io.File(vaultDir, "bot_weights.json").writeText("""{"weights":{"bot_mtf":1.15}}""")
        java.io.File(vaultDir, "trend_observations.jsonl").writeText("""{"id":"BTCUSDT:1","symbol":"BTCUSDT"}""" + "\n")
        java.io.File(pulseDir, "pulse_summary.json").writeText("""{"total_recorded":2,"verified_count":2,"accuracy_pct":100.0}""")

        val settings = com.enamind.ai.core.Settings(ctx)
        settings.aiStudioApiKey = "AIzaTestSecretShouldNotExport123"

        FaultMonitor.init(ctx)
        FaultMonitor.recordFault(
            component = "ZipTestNode",
            kind = FaultMonitor.Kind.ENGINE,
            severity = FaultMonitor.Severity.WARNING,
            message = "Тестовая запись для проверки экспорта"
        )

        val exportRes = com.enamind.ai.export.ZipManager.export(ctx, com.example.BuildConfig.VERSION_NAME)
        assertTrue(exportRes.optBoolean("ok"))
        val zipFile = java.io.File(exportRes.optString("path"))
        assertTrue(zipFile.isFile)
        try {
            val entries = mutableSetOf<String>()
            java.util.zip.ZipInputStream(java.io.FileInputStream(zipFile)).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    entries.add(e.name)
                    zin.closeEntry()
                    e = zin.nextEntry
                }
            }
            assertTrue("ena-mind/learning_vault/learning_summary.json" in entries)
            assertTrue("ena-mind/learning_vault/bot_weights.json" in entries)
            assertTrue("ena-mind/learning_vault/trend_observations.jsonl" in entries)
            assertTrue("ena-mind/realtime_pulse_vault/pulse_summary.json" in entries)
            assertTrue("ena-mind/fault_history.jsonl" in entries)
            assertTrue("ena-mind/export_meta.json" in entries)
            // Секреты не должны попадать в архив ни в каком виде
            assertFalse(entries.any { it.contains("shared_prefs") || it.contains("ai_studio") })

            vaultDir.deleteRecursively()
            pulseDir.deleteRecursively()
            assertFalse(java.io.File(vaultDir, "bot_weights.json").exists())

            val trust = com.enamind.ai.export.ZipManager.inspectTrust(zipFile)
            assertTrue(trust.optBoolean("ok"))
            assertTrue(trust.optInt("data_files") >= 4)

            val importRes = com.enamind.ai.export.ZipManager.import(ctx, zipFile, confirmed = true)
            assertTrue(importRes.optBoolean("ok"))
            assertTrue(java.io.File(vaultDir, "bot_weights.json").isFile)
            assertTrue(java.io.File(vaultDir, "learning_summary.json").isFile)
            assertTrue(java.io.File(vaultDir, "trend_observations.jsonl").readText().contains("BTCUSDT:1"))
            assertTrue(java.io.File(pulseDir, "pulse_summary.json").isFile)
        } finally {
            zipFile.delete()
            vaultDir.deleteRecursively()
            pulseDir.deleteRecursively()
            FaultMonitor.clear()
            settings.aiStudioApiKey = ""
        }
    }

    @Test
    fun outlook_summary_formats_json_with_range_and_drivers_cleanly() {
        val summary = com.enamind.ai.intelligence.OutlookSummary(
            horizonHours = 24,
            side = "UP",
            pUp = 0.54,
            pFlat = 0.24,
            pDown = 0.22,
            expectedMovePct = 1.15,
            sigmaPct = 2.15,
            rangeLowPct = -1.40,
            rangeHighPct = 2.90,
            thresholdPct = 0.85,
            calibrationLabel = "Brier 0.21",
            drivers = listOf("Кросс-биржевое опережение Coinbase +18 bps"),
            invalidationRules = listOf("Пробой вниз 63200")
        )
        val rendered = summary.toJson("BTCUSDT", 65000.0)
        assertTrue(rendered.optBoolean("ok"))
        assertEquals(24, rendered.optInt("horizon_hours"))
        assertEquals(0.54, rendered.optDouble("p_up"), 1e-6)
        assertEquals("Brier 0.21", rendered.optString("calibration"))
        assertTrue(rendered.optDouble("range_low_price") < 65000.0)
        assertTrue(rendered.optDouble("range_high_price") > 65000.0)
        assertTrue(rendered.getJSONArray("drivers").getString(0).contains("Кросс-биржевое"))
        assertEquals("Пробой вниз 63200", rendered.getJSONArray("invalidation").getString(0))
    }
}
