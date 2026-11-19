package com.enamind.ai

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.enamind.ai.bot.BotConfig
import com.enamind.ai.bot.BotDecisionEngine
import com.enamind.ai.bot.strategy.MarketContextSnapshot
import com.enamind.ai.bot.strategy.OpenPositionContext
import com.enamind.ai.bot.strategy.StrategyDecision
import com.enamind.ai.bybit.ContractCatalogRepo
import com.enamind.ai.core.ActiveInstrument
import com.enamind.ai.core.EnacoreSnapshot
import com.enamind.ai.core.Settings
import com.enamind.ai.orderflow.OrderFlowIntelligenceOrchestrator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class EnaverseUnitTests {
    private val now = 1800000000000L
    private fun live() = MarketContextSnapshot("BTCUSDT", 100.0, now, true, false,
        deltaPct = 30.0, normalizedOfi = .4)

    @Test fun `category and generation isolate identically named instruments`() {
        val linear = ActiveInstrument(generation = 7)
        val spot = linear.copy(category = "spot", generation = 8)
        assertNotEquals(linear.key, spot.key)
        val signal = EnacoreSnapshot(key = linear.key, generation = 7, computedAt = now)
        assertTrue(signal.isUsable(linear, now))
        assertFalse(signal.isUsable(spot, now))
        assertFalse(signal.isUsable(linear.copy(generation = 9), now))
        assertFalse(signal.isUsable(linear, now + 6001))
        assertFalse(signal.copy(bookAgeMs = -1).isUsable(linear, now))
        assertFalse(signal.copy(tradeAgeMs = 7000).isUsable(linear, now))
    }

    @Test fun `core bridge requires matching fresh confirmation and valid matrix target`() {
        val instrument = ActiveInstrument(generation = 2)
        val cfg = BotConfig(enacoreBridge = true)
        val core = EnacoreSnapshot(key = instrument.key, generation = 2, computedAt = now,
            verdict = "CONFIRM LONG", targetPct = .75, horizonMin = 30)
        fun decision(value: EnacoreSnapshot) = BotDecisionEngine.evaluate(live(), OpenPositionContext(), cfg, value, instrument, now)
        assertTrue(decision(core) is StrategyDecision.Enter)
        assertTrue(decision(core.copy(verdict = "VETO")) is StrategyDecision.Hold)
        assertTrue(decision(core.copy(generation = 1)) is StrategyDecision.Hold)
        assertTrue(decision(core.copy(targetPct = null)) is StrategyDecision.Hold)
        assertTrue(decision(core.copy(horizonMin = 1)) is StrategyDecision.Hold)
        assertTrue(decision(core.copy(computedAt = now - 7000)) is StrategyDecision.Hold)
        val spot = instrument.copy(category = "spot")
        assertTrue(BotDecisionEngine.evaluate(live(), OpenPositionContext(hasOpenPosition = true, sideIsBuy = true),
            cfg.copy(instrumentCategory = "spot"), core.copy(key = spot.key, verdict = "CONFIRM SHORT"), spot, now) is StrategyDecision.ExitAll)
    }

    @Test fun `manual rules reject nonfinite input and unborrowed spot shorts`() {
        val cfg = BotConfig(requireRadarNotFlat = false, requireGlowConsensus = false)
        assertTrue(BotDecisionEngine.evaluate(live().copy(deltaPct = Double.NaN), OpenPositionContext(), cfg) is StrategyDecision.Hold)
        assertTrue(BotDecisionEngine.evaluate(live().copy(deltaPct = -30.0, normalizedOfi = -.4),
            OpenPositionContext(), cfg.copy(instrumentCategory = "spot")) is StrategyDecision.Hold)
    }

    @Test fun `inverse contracts normalize to base quantity without changing linear units`() {
        assertEquals(10.0, ActiveInstrument(category = "inverse").baseQuantity(100.0, 1000.0), 1e-9)
        assertEquals(1000.0, ActiveInstrument().baseQuantity(100.0, 1000.0), 1e-9)
        assertEquals(0.0, ActiveInstrument(category = "inverse").baseQuantity(0.0, 1000.0), 0.0)
    }

    @Test fun `spot catalogue uses base precision and quote minimum and preserves RWA`() {
        val raw = """{"retCode":0,"result":{"list":[{"symbol":"TSLAXUSDC","baseCoin":"TSLAX","quoteCoin":"USDC","status":"Trading","symbolType":"xstocks","priceFilter":{"tickSize":"0.01"},"lotSizeFilter":{"basePrecision":"0.000001","minOrderAmt":"5"}}]}}"""
        val contract = ContractCatalogRepo.parseBybitInstrumentsJson(raw, "spot")!!.first.single()
        assertEquals("USDC", contract.quoteCoin)
        assertEquals(0.000001, contract.qtyStep!!, 1e-12)
        assertEquals(5.0, contract.minNotionalValue!!, 0.0)
        val selected = ActiveInstrument.from(contract, 3)
        assertEquals(1.0, selected.maxLeverage, 0.0)
        assertEquals("Металлы / RWA", selected.assetClass)
        assertEquals(.1, selected.takerFeePct, 0.0)
    }

    @Test fun `RGB and notification channels survive Settings recreation independently`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("enamind", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val s = Settings(ctx)
        s.theme = Settings.Theme.RGB; s.accentColor = 0xFFEF0CCF.toInt(); s.rgbDarkBase = false
        s.setNotificationEnabled("core", true); s.setNotificationEnabled("bot_stop", false)
        val next = Settings(ctx)
        assertEquals(Settings.Theme.RGB, next.theme)
        assertEquals(0xFFEF0CCF.toInt(), next.accentColor)
        assertFalse(next.rgbDarkBase)
        assertTrue(next.notificationEnabled("core"))
        assertFalse(next.notificationEnabled("bot_stop"))
        prefs.edit().clear().commit()
    }

    @Test fun `old OrderFlow computation cannot replace a newly selected instrument`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val flow = OrderFlowIntelligenceOrchestrator(ctx)
        flow.resetForInstrument("XAUUSDT", 4)
        flow.evaluate("BTCUSDT", "1", .1, 100.0, emptyList(), nowMs = now, expectedGeneration = 3)
        assertEquals("XAUUSDT", flow.report.value.symbol)
        assertTrue(flow.report.value.overlay.supportLevels.isEmpty())
    }
}
