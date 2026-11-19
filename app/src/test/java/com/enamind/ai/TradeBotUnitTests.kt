package com.enamind.ai

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.enamind.ai.bot.BotConfig
import com.enamind.ai.bot.BotFloatingOverlayManager
import com.enamind.ai.bot.BotMode
import com.enamind.ai.bot.BotTradeRecord
import com.enamind.ai.bot.BotVaultStore
import com.enamind.ai.bot.BybitCredentialSlot
import com.enamind.ai.bot.BybitV5TradingClient
import com.enamind.ai.bot.HudGlowZone
import com.enamind.ai.bot.MarginMode
import com.enamind.ai.bot.ModeWinrateStats
import com.enamind.ai.bot.TargetBasis
import com.enamind.ai.bot.TradeBotEngine
import com.enamind.ai.bot.TradeSide
import com.enamind.ai.bot.TradeTranche
import com.enamind.ai.bot.strategy.MarketContextSnapshot
import com.enamind.ai.bot.strategy.OpenPositionContext
import com.enamind.ai.bot.strategy.OrderExecutionPreference
import com.enamind.ai.bot.strategy.StrategyDecision
import com.enamind.ai.bot.BotDecisionEngine
import com.enamind.ai.bybit.ContractCatalogRepo
import com.enamind.ai.core.AppState
import com.enamind.ai.core.Settings
import com.enamind.ai.core.StorageGuard
import com.enamind.ai.orderflow.OrderFlowReport
import org.json.JSONObject
import com.google.crypto.tink.Aead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TradeBotUnitTests {

    private class DeterministicTestAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val mask = if (associatedData.isNotEmpty()) associatedData else byteArrayOf(0x5A)
            val out = ByteArray(plaintext.size)
            for (i in plaintext.indices) {
                out[i] = (plaintext[i].toInt() xor mask[i % mask.size].toInt() xor 0x37).toByte()
            }
            return out
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            val mask = if (associatedData.isNotEmpty()) associatedData else byteArrayOf(0x5A)
            val out = ByteArray(ciphertext.size)
            for (i in ciphertext.indices) {
                out[i] = (ciphertext[i].toInt() xor mask[i % mask.size].toInt() xor 0x37).toByte()
            }
            return out
        }
    }

    @Test
    fun `computeNetPnl and targetPriceForNetPct account for roundtrip fees and leverage ROE`() {
        // Long BTCUSDT @ 10000, qty=0.1, lev=10x => notional=1000, margin=100 USDT
        // Round-trip fee = 0.11% -> 0.055% each side
        val tpMinPrice = TradeBotEngine.targetPriceForNetPct(
            side = TradeSide.BUY,
            entryPrice = 10_000.0,
            targetNetPct = 0.5,
            leverage = 10,
            feeRateRoundTripPct = 0.11,
            targetBasis = TargetBasis.NET_ROE_PCT
        )
        // +0.5% ROE @ 10x needs +0.05% price move + 0.11% fee = +0.16% => ~10016.0
        assertEquals(10_016.0, tpMinPrice, 0.5)

        val pnlAtMin = TradeBotEngine.computeNetPnl(
            side = TradeSide.BUY,
            qty = 0.1,
            entryPrice = 10_000.0,
            markPrice = tpMinPrice,
            leverage = 10,
            usedMarginUsdt = 100.0,
            feeRateRoundTripPct = 0.11,
            targetBasis = TargetBasis.NET_ROE_PCT
        )
        assertTrue(pnlAtMin.netPnlUsdt > 0.0)
        assertEquals(0.5, pnlAtMin.netPnlPct, 0.05)

        // Price-move basis: +3.5% net on SHORT requires lower price move + fees
        val tpShortMove = TradeBotEngine.targetPriceForNetPct(
            side = TradeSide.SELL,
            entryPrice = 1000.0,
            targetNetPct = 3.5,
            leverage = 5,
            feeRateRoundTripPct = 0.11,
            targetBasis = TargetBasis.PRICE_MOVE_PCT
        )
        assertTrue(tpShortMove < 1000.0)
        val pnlShort = TradeBotEngine.computeNetPnl(
            side = TradeSide.SELL,
            qty = 1.0,
            entryPrice = 1000.0,
            markPrice = tpShortMove,
            leverage = 5,
            usedMarginUsdt = 200.0,
            feeRateRoundTripPct = 0.11,
            targetBasis = TargetBasis.PRICE_MOVE_PCT
        )
        assertEquals(3.5, pnlShort.netPnlPct, 0.15)
    }

    @Test
    fun `bybitV5TradingClient rounds qtyStep, formats tickSize, signs HMAC, and tolerates already-matched retCodes`() {
        assertEquals("1.23", BybitV5TradingClient.roundQtyToStep(1.2399, qtyStep = 0.01, minQty = 0.1))
        assertNull(BybitV5TradingClient.roundQtyToStep(0.05, qtyStep = 0.01, minQty = 0.1))
        assertEquals("64123.5", BybitV5TradingClient.formatPriceToTick(64123.54, tickSize = 0.1))
        assertEquals("0.0124", BybitV5TradingClient.formatPriceToTick(0.01238, tickSize = 0.0001))

        val payload = BybitV5TradingClient.buildSignPayload(
            timestampMs = 1700000000000L,
            apiKey = "key123",
            recvWindow = "5000",
            queryOrBody = "category=linear&symbol=BTCUSDT"
        )
        assertEquals("1700000000000key1235000category=linear&symbol=BTCUSDT", payload)
        val sig1 = BybitV5TradingClient.signPayload("secret123", payload)
        val sig2 = BybitV5TradingClient.signPayload("secret123", payload)
        assertEquals(64, sig1.length)
        assertEquals(sig1, sig2)

        assertTrue(BybitV5TradingClient.isAlreadyMatchedRet(110026, "Cross/isolated margin mode is same"))
        assertTrue(BybitV5TradingClient.isAlreadyMatchedRet(100028, "not modified"))
        assertTrue(BybitV5TradingClient.isAlreadyMatchedRet(34036, "leverage not modified"))
        assertFalse(BybitV5TradingClient.isAlreadyMatchedRet(10001, "Invalid parameter"))

        // Verify UTA vs Classic margin mode representations and 3 separate live Bybit endpoints
        assertEquals("ISOLATED_MARGIN", MarginMode.ISOLATED.bybitUtaValue)
        assertEquals("REGULAR_MARGIN", MarginMode.CROSS.bybitUtaValue)
        assertEquals(1, MarginMode.ISOLATED.classicTradeMode)
        assertEquals(0, MarginMode.CROSS.classicTradeMode)
        assertEquals("https://api-demo.bybit.com", BotMode.BYBIT_DEMO.restBaseUrl)
        assertEquals("https://api-testnet.bybit.com", BotMode.BYBIT_TESTNET.restBaseUrl)
        assertEquals("https://api.bybit.com", BotMode.BYBIT_MAINNET.restBaseUrl)
    }

    @Test
    fun `botVaultStore keeps 3 independent encrypted Bybit slots and atomic config stats trades`() {
        val dir = createTempDirectory("bot_vault_test").toFile()
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds = PreferenceDataStoreFactory.create(
                scope = dsScope,
                produceFile = { File(dir, "vault.preferences_pb") }
            )
            val botDir = File(dir, "bot_engine")
            val store = BotVaultStore(botDir, ds, DeterministicTestAead())

            store.saveCredentialSlot(
                BotMode.BYBIT_DEMO,
                BybitCredentialSlot("demo_key", "demo_sec", "DemoAcc")
            )
            store.saveCredentialSlot(
                BotMode.BYBIT_TESTNET,
                BybitCredentialSlot("test_key", "test_sec", "TestAcc")
            )
            store.saveCredentialSlot(
                BotMode.BYBIT_MAINNET,
                BybitCredentialSlot("main_key", "main_sec", "MainAcc")
            )

            // Verify zero cross-slot overwrite
            assertEquals("demo_key", store.loadCredentialSlot(BotMode.BYBIT_DEMO).apiKey)
            assertEquals("test_key", store.loadCredentialSlot(BotMode.BYBIT_TESTNET).apiKey)
            assertEquals("main_key", store.loadCredentialSlot(BotMode.BYBIT_MAINNET).apiKey)
            assertNotEquals(
                store.loadCredentialSlot(BotMode.BYBIT_DEMO).apiKey,
                store.loadCredentialSlot(BotMode.BYBIT_TESTNET).apiKey
            )

            store.clearCredentialSlot(BotMode.BYBIT_TESTNET)
            assertFalse(store.loadCredentialSlot(BotMode.BYBIT_TESTNET).hasKeys)
            assertTrue(store.loadCredentialSlot(BotMode.BYBIT_DEMO).hasKeys)
            assertTrue(store.loadCredentialSlot(BotMode.BYBIT_MAINNET).hasKeys)

            // Atomic config + per-mode stats + trades persistence
            val cfg = BotConfig(
                mode = BotMode.BYBIT_DEMO,
                symbol = "ETHUSDT",
                leverage = 15,
                stopLossEnabled = false,
                hudEnabled = true, enacoreBridge = true, instrumentCategory = "spot",
                entryDeltaPct = 23.0, entryOfi = .31, maxSpreadPct = .08
            )
            store.saveConfig(cfg)
            val loadedCfg = store.loadConfig()
            assertEquals("ETHUSDT", loadedCfg.symbol)
            assertEquals(15, loadedCfg.leverage)
            assertFalse(loadedCfg.stopLossEnabled)
            assertTrue(loadedCfg.hudEnabled)
            assertTrue(loadedCfg.enacoreBridge)
            assertEquals("spot", loadedCfg.instrumentCategory)
            assertEquals(23.0, loadedCfg.entryDeltaPct, 0.0)
            assertEquals(.31, loadedCfg.entryOfi, 0.0)
            assertEquals(.08, loadedCfg.maxSpreadPct, 0.0)

            store.saveStatsByMode(
                mapOf(
                    BotMode.PAPER to ModeWinrateStats(BotMode.PAPER, totalTrades = 10, wins = 7, losses = 3, winratePct = 70.0),
                    BotMode.BYBIT_DEMO to ModeWinrateStats(BotMode.BYBIT_DEMO, totalTrades = 4, wins = 1, losses = 3, winratePct = 25.0)
                )
            )
            val stats = store.loadStatsByMode()
            assertEquals(70.0, stats[BotMode.PAPER]!!.winratePct, 1e-6)
            assertEquals(25.0, stats[BotMode.BYBIT_DEMO]!!.winratePct, 1e-6)

            store.appendTrade(
                BotTradeRecord(
                    id = "tr1",
                    mode = BotMode.BYBIT_DEMO,
                    symbol = "ETHUSDT",
                    side = TradeSide.BUY,
                    qty = 0.5,
                    entryPrice = 3000.0,
                    exitPrice = 3030.0,
                    leverage = 10,
                    openedAtMs = 1_700_000_000_000L,
                    closedAtMs = 1_700_000_060_000L,
                    grossPnlUsdt = 15.0,
                    feesUsdt = 1.2,
                    netPnlUsdt = 13.8,
                    netPnlPct = 9.2,
                    exitReason = "Цель +3.5%",
                    won = true
                )
            )
            val trades = store.loadTrades()
            assertEquals(1, trades.size)
            assertEquals("ETHUSDT", trades[0].symbol)
        } finally {
            dsScope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `evaluateExitDecision handles dynamic 0_5 to 3_5 trailing and optional disabled StopLoss`() {
        val cfgNoSl = BotConfig(
            mode = BotMode.BYBIT_MAINNET,
            minNetProfitPct = 0.5,
            maxProfitPct = 3.5,
            trailingGivebackPct = 0.35,
            maxHoldWaitMin = 30,
            stopLossEnabled = false,
            stopLossPct = 1.5
        )
        val posDrawdownNoSl = com.enamind.ai.bot.ActivePosition(
            id = "p1",
            mode = BotMode.BYBIT_MAINNET,
            symbol = "BTCUSDT",
            side = TradeSide.BUY,
            qty = 0.1,
            entryPrice = 60_000.0,
            markPrice = 59_800.0,
            leverage = 10,
            marginMode = MarginMode.ISOLATED,
            usedMarginUsdt = 600.0,
            openedAtMs = 1_700_000_000_000L,
            feeRatePct = 0.11,
            estFeeUsdt = 6.6,
            grossPnlUsdt = -20.0,
            netPnlUsdt = -26.6,
            netPnlPct = -4.4, // Beyond stopLossPct=-1.5%, yet SL disabled!
            peakNetPnlPct = 0.1,
            tpMinPrice = 60_096.0,
            tpMaxPrice = 60_276.0,
            stopLossPrice = null,
            estLiquidationPrice = TradeBotEngine.estimateLiquidationPrice(TradeSide.BUY, 60_000.0, 10, MarginMode.ISOLATED)
        )
        assertNotNull(posDrawdownNoSl.estLiquidationPrice)
        assertTrue(posDrawdownNoSl.estLiquidationPrice!! < 60_000.0)
        // With stopLossEnabled=false, SL must NOT trigger closure
        assertFalse(TradeBotEngine.evaluateExitDecision(posDrawdownNoSl, cfgNoSl, 1_700_000_060_000L, signalStillAligned = true).shouldClose)

        // With stopLossEnabled=true, SL DOES trigger closure
        val cfgWithSl = cfgNoSl.copy(stopLossEnabled = true)
        assertTrue(TradeBotEngine.evaluateExitDecision(posDrawdownNoSl, cfgWithSl, 1_700_000_060_000L, signalStillAligned = true).shouldClose)

        // Dynamic trailing: reached peak +1.80%, floor=1.45%, pulled back to +1.40% -> triggers trailing lock exit
        val posTrailHit = posDrawdownNoSl.copy(
            netPnlPct = 1.40,
            peakNetPnlPct = 1.80,
            trailingFloorPct = 1.45
        )
        val trailExit = TradeBotEngine.evaluateExitDecision(posTrailHit, cfgNoSl, 1_700_000_120_000L, signalStillAligned = true)
        assertTrue(trailExit.shouldClose)
        assertTrue(trailExit.reason.contains("Трейлинг"))

        // Max target >= 3.5% triggers immediate take-profit
        val posMaxHit = posDrawdownNoSl.copy(netPnlPct = 3.55, peakNetPnlPct = 3.55, trailingFloorPct = 3.20)
        assertTrue(TradeBotEngine.evaluateExitDecision(posMaxHit, cfgNoSl, 1_700_000_120_000L, signalStillAligned = true).shouldClose)
    }

    @Test
    fun `hudGlowZone and floating overlay color thresholds map lt50 red, 50to65 yellow, gt65 green`() {
        assertEquals(HudGlowZone.NEUTRAL, HudGlowZone.fromStats(totalTrades = 0, winratePct = 0.0))
        assertEquals(HudGlowZone.RED, HudGlowZone.fromStats(totalTrades = 5, winratePct = 49.9))
        assertEquals(HudGlowZone.YELLOW, HudGlowZone.fromStats(totalTrades = 4, winratePct = 50.0))
        assertEquals(HudGlowZone.YELLOW, HudGlowZone.fromStats(totalTrades = 20, winratePct = 65.0))
        assertEquals(HudGlowZone.GREEN, HudGlowZone.fromStats(totalTrades = 10, winratePct = 65.1))

        assertEquals(
            BotFloatingOverlayManager.COLOR_NEUTRAL,
            BotFloatingOverlayManager.resolveHudGlowColorInt(ModeWinrateStats(BotMode.PAPER, totalTrades = 0, winratePct = 0.0))
        )
        assertEquals(
            BotFloatingOverlayManager.COLOR_RED,
            BotFloatingOverlayManager.resolveHudGlowColorInt(ModeWinrateStats(BotMode.PAPER, totalTrades = 3, winratePct = 33.3))
        )
        assertEquals(
            BotFloatingOverlayManager.COLOR_YELLOW,
            BotFloatingOverlayManager.resolveHudGlowColorInt(ModeWinrateStats(BotMode.PAPER, totalTrades = 2, winratePct = 50.0))
        )
        assertEquals(
            BotFloatingOverlayManager.COLOR_GREEN,
            BotFloatingOverlayManager.resolveHudGlowColorInt(ModeWinrateStats(BotMode.PAPER, totalTrades = 4, winratePct = 75.0))
        )
    }

    @Test
    fun `storageGuard creates isolated bot_engine and cache directories`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val botDir = StorageGuard.botEngineDir(ctx)
        val cacheDir = StorageGuard.botCacheDir(ctx)
        assertTrue(botDir.exists() && botDir.isDirectory)
        assertTrue(cacheDir.exists() && cacheDir.isDirectory)
        assertTrue(botDir.absolutePath.endsWith("enamind/bot_engine"))
        assertTrue(cacheDir.absolutePath.endsWith("enamind/bot_engine/cache"))
    }

    @Test
    fun `tradeBotEngine stopAndCloseMarket resets running and closing state cleanly`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dir = createTempDirectory("bot_engine_test").toFile()
        val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds = PreferenceDataStoreFactory.create(
                scope = dsScope,
                produceFile = { File(dir, "vault.preferences_pb") }
            )
            val store = BotVaultStore(File(dir, "bot_engine"), ds, DeterministicTestAead())
            val catalog = ContractCatalogRepo(ctx, Settings(ctx))
            val engine = TradeBotEngine(
                vaultStore = store,
                contractCatalog = catalog,
                reportProvider = { OrderFlowReport() },
                marketProvider = { AppState.MarketUi(symbol = "BTCUSDT", price = 65_000.0) },
                quantAdxErProvider = { 25.0 to 0.5 }
            )
            engine.startBot()
            assertTrue(engine.uiState.value.running)
            engine.stopAndCloseMarket("Тестовая остановка")
            delay(250L)
            assertFalse(engine.uiState.value.running)
            assertFalse(engine.uiState.value.closing)
            assertNull(engine.uiState.value.activePosition)
        } finally {
            dsScope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `bybitV5TradingClient fee profiles support crypto and zero-maker TradFi metals`() {
        val btcFee = BybitV5TradingClient.defaultFeeProfile("BTCUSDT")
        assertEquals(0.036, btcFee.makerFeePct, 1e-6)
        assertEquals(0.10, btcFee.takerFeePct, 1e-6)
        assertFalse(btcFee.isTradFiMetal)

        val xauFee = BybitV5TradingClient.defaultFeeProfile("XAUUSDT")
        assertEquals(0.0, xauFee.makerFeePct, 1e-6)
        assertEquals(0.0275, xauFee.takerFeePct, 1e-6)
        assertTrue(xauFee.isTradFiMetal)

        val parsedJson = JSONObject(
            """{"retCode":0,"result":{"list":[{"symbol":"XAGUSDT","makerFeeRate":"0","takerFeeRate":"0.000275"}]}}"""
        )
        val parsed = BybitV5TradingClient.parseFeeProfileJson(parsedJson, "XAGUSDT")
        assertEquals(0.0, parsed.makerFeePct, 1e-6)
        assertEquals(0.0275, parsed.takerFeePct, 1e-6)
        assertTrue(parsed.isTradFiMetal)
    }

    @Test
    fun `manual thresholds respect spread freshness and anti knife scale gates`() {
        val snap = MarketContextSnapshot(symbol = "BTCUSDT", price = 65000.0, nowMs = 1700000000000L,
            dataFreshnessLive = true, conflictDetected = false, deltaPct = 22.0, normalizedOfi = .24)
        val cfg = BotConfig()
        assertTrue(BotDecisionEngine.evaluate(snap, OpenPositionContext(), cfg) is StrategyDecision.Enter)
        assertTrue(BotDecisionEngine.evaluate(snap.copy(dataFreshnessLive = false), OpenPositionContext(), cfg) is StrategyDecision.Hold)
        assertTrue(BotDecisionEngine.evaluate(snap.copy(spreadPct = 2.0), OpenPositionContext(), cfg) is StrategyDecision.Hold)
        val pos = OpenPositionContext(hasOpenPosition = true, sideIsBuy = true, tranchesCount = 1,
            maxTranches = 3, drawdownFromLastTranchePct = .12, effectiveStepPct = .28)
        assertTrue(BotDecisionEngine.evaluate(snap, pos, cfg) is StrategyDecision.Hold)
        assertTrue(BotDecisionEngine.evaluate(snap, pos.copy(drawdownFromLastTranchePct = .35), cfg) is StrategyDecision.ScaleIn)
        assertTrue(BotDecisionEngine.evaluate(snap.copy(longLiqUsd15s = 25000.0), pos.copy(drawdownFromLastTranchePct = .35), cfg) is StrategyDecision.Hold)
        assertTrue(BotDecisionEngine.evaluate(snap, OpenPositionContext(), cfg.copy(enacoreBridge = true)) is StrategyDecision.Hold)
    }

    @Test
    fun `blended tranche math, cumulative paid fees, effectiveCapital, adaptive trailing, and unlimitedHold work accurately`() {
        val tranches = listOf(
            TradeTranche(index = 1, entryPrice = 100.0, qty = 3.0, entryFeeUsdt = 0.108, enteredAtMs = 1L, isMaker = true),
            TradeTranche(index = 2, entryPrice = 98.0, qty = 3.5, entryFeeUsdt = 0.12348, enteredAtMs = 2L, isMaker = true)
        )
        val avgEntry = TradeBotEngine.computeBlendedAvgEntry(tranches)
        // (300 + 343) / 6.5 = 643 / 6.5 = 98.9230769
        assertEquals(643.0 / 6.5, avgEntry, 1e-6)
        val paidFees = tranches.sumOf { it.entryFeeUsdt }
        val totalQty = 6.5
        val margin = (avgEntry * totalQty) / 10.0

        val tpMin = TradeBotEngine.blendedTargetPriceForNetPct(
            side = TradeSide.BUY,
            avgEntryPrice = avgEntry,
            qty = totalQty,
            leverage = 10,
            usedMarginUsdt = margin,
            paidEntryFeesUsdt = paidFees,
            expectedExitFeeRatePct = 0.036,
            targetNetPct = 0.5,
            targetBasis = TargetBasis.NET_ROE_PCT
        )
        val pnlAtTp = TradeBotEngine.computeBlendedNetPnl(
            side = TradeSide.BUY,
            qty = totalQty,
            avgEntryPrice = avgEntry,
            markPrice = tpMin,
            leverage = 10,
            usedMarginUsdt = margin,
            paidEntryFeesUsdt = paidFees,
            expectedExitFeeRatePct = 0.036,
            targetBasis = TargetBasis.NET_ROE_PCT
        )
        assertEquals(0.5, pnlAtTp.netPnlPct, 1e-4)

        assertEquals(47.5, TradeBotEngine.computeEffectiveCapital(100.0, 50.0), 1e-6)
        assertEquals(1.0, TradeBotEngine.adaptiveTrailingGivebackPct(0.35, 0.04, 10, TargetBasis.NET_ROE_PCT), 1e-6)

        val unlimitedCfg = BotConfig(allowUnlimitedHold = true, maxHoldWaitMin = 1, stopLossEnabled = false)
        val oldPos = com.enamind.ai.bot.ActivePosition(
            id = "p_hold",
            mode = BotMode.PAPER,
            symbol = "BTCUSDT",
            side = TradeSide.BUY,
            qty = 1.0,
            entryPrice = 100.0,
            markPrice = 99.8,
            leverage = 10,
            marginMode = MarginMode.ISOLATED,
            usedMarginUsdt = 10.0,
            openedAtMs = 1_000_000L,
            feeRatePct = 0.072,
            estFeeUsdt = 0.07,
            grossPnlUsdt = -0.2,
            netPnlUsdt = -0.27,
            netPnlPct = -2.7,
            peakNetPnlPct = 0.0,
            tpMinPrice = 100.2,
            tpMaxPrice = 100.5
        )
        assertFalse(
            TradeBotEngine.evaluateExitDecision(oldPos, unlimitedCfg, nowMs = 99_000_000L, signalStillAligned = false).shouldClose
        )
    }
}
