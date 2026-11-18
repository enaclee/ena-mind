package com.enamind.ai.core

import android.content.Context
import com.chaquo.python.Python
import com.enamind.ai.bybit.BybitWs
import com.enamind.ai.orderflow.AbsorptionSide
import com.enamind.ai.orderflow.DataFreshness
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class CoreAgent(val name: String, val score: Double, val weight: Double, val confidence: Double)
data class CoreCell(val target: Double, val horizon: Int, val probability: Double?, val samples: Int, val calibrated: Boolean)

data class EnacoreSnapshot(
    val key: String = "", val generation: Long = 0, val computedAt: Long = 0,
    val pressure: Double = 0.0, val verdict: String = "NEUTRAL", val reason: String = "Ожидание рыночных данных",
    val bars: Int = 0, val trades: Int = 0, val oiSamples: Int = 0, val events: Long = 0,
    val bookAgeMs: Long = 0, val tradeAgeMs: Long = 0, val spreadPct: Double? = null,
    val targetPct: Double? = null, val horizonMin: Int? = null,
    val agents: List<CoreAgent> = emptyList(), val longCells: List<CoreCell> = emptyList(),
    val shortCells: List<CoreCell> = emptyList()
) {
    fun isUsable(instrument: ActiveInstrument, now: Long): Boolean =
        key == instrument.key && generation == instrument.generation &&
            now - computedAt in 0..6000 && bookAgeMs <= 6000 && tradeAgeMs <= 6000

    companion object {
        fun parse(o: JSONObject): EnacoreSnapshot {
            fun cells(side: String): List<CoreCell> {
                val a = o.optJSONObject("matrix")?.optJSONArray(side) ?: return emptyList()
                return (0 until a.length()).map { i -> a.getJSONObject(i).let {
                    CoreCell(it.optDouble("target"), it.optInt("horizon"),
                        it.optDouble("probability").takeIf { n -> n.isFinite() },
                        it.optInt("samples"), it.optBoolean("calibrated"))
                } }
            }
            val a = o.optJSONArray("agents") ?: JSONArray()
            val best = o.optJSONObject("best")
            return EnacoreSnapshot(
                key = o.optString("key"), generation = o.optLong("generation"), computedAt = o.optLong("computed_at"),
                pressure = o.optDouble("pressure", 0.0), verdict = o.optString("verdict", "VETO"), reason = o.optString("reason"),
                bars = o.optInt("bars"), trades = o.optInt("trades"), oiSamples = o.optInt("oi_samples"), events = o.optLong("events"),
                bookAgeMs = o.optLong("book_age_ms"), tradeAgeMs = o.optLong("trade_age_ms"),
                spreadPct = o.optDouble("spread_pct").takeIf { it.isFinite() },
                targetPct = best?.optDouble("target")?.takeIf { it.isFinite() }, horizonMin = best?.optInt("horizon"),
                agents = (0 until a.length()).map { i -> a.getJSONObject(i).let {
                    CoreAgent(it.optString("name"), it.optDouble("score"), it.optDouble("weight"), it.optDouble("confidence"))
                } }, longCells = cells("LONG"), shortCells = cells("SHORT")
            )
        }
    }
}

/** Batches primary stream events on IO; no blocking Python work on WS/UI threads. */
class EnacoreEngine(private val ctx: Context, private val app: AppState) {
    private val started = AtomicBoolean(false)
    private val pendingTrades = ConcurrentLinkedQueue<Pair<Long, BybitWs.Trade>>()
    private val liveBars = java.util.concurrent.ConcurrentHashMap<Long, List<Double>>()
    private val _state = MutableStateFlow(EnacoreSnapshot())
    val state: StateFlow<EnacoreSnapshot> = _state

    fun reset(instrument: ActiveInstrument) {
        pendingTrades.clear()
        liveBars.clear()
        _state.value = EnacoreSnapshot(key = instrument.key, generation = instrument.generation)
    }

    fun onTrade(generation: Long, trade: BybitWs.Trade) {
        if (generation != app.activeInstrument.value.generation) return
        pendingTrades.add(generation to trade)
        while (pendingTrades.size > 1500) pendingTrades.poll()
    }

    fun onMinuteBar(generation: Long, bar: BybitWs.Kline) {
        if (generation != app.activeInstrument.value.generation) return
        liveBars[bar.tsMs] = listOf(bar.tsMs.toDouble(), bar.open, bar.high, bar.low, bar.close, bar.volume)
        if (liveBars.size > 1500) liveBars.keys.sorted().take(liveBars.size - 1500).forEach { liveBars.remove(it) }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        app.appScope.launch {
            while (isActive) {
                val instrument = app.activeInstrument.value
                try {
                    if (PythonBridge.isReady()) {
                        val report = app.orderFlow.report.value
                        val market = app.market.value
                        val (bookAt, tradeAt) = app.coreStreamTimes()
                        val trades = JSONArray()
                        while (true) {
                            val (gen, t) = pendingTrades.poll() ?: break
                            if (gen == instrument.generation) trades.put(JSONObject()
                                .put("ts", t.tsMs).put("price", t.price).put("qty", t.size).put("buy", t.side == "Buy"))
                        }
                        val rows = (app.engine.enacoreBars() + liveBars.values).associateBy { it.first() }.toSortedMap().values
                        val bars = JSONArray().also { a -> rows.forEach { a.put(JSONArray(it)) } }
                        val metrics = JSONObject().put("price", market.price ?: 0.0)
                            .put("book_ts", bookAt).put("trade_ts", tradeAt)
                            .put("bid", market.bids.firstOrNull()?.price ?: 0.0)
                            .put("ask", market.asks.firstOrNull()?.price ?: 0.0)
                            .put("synced", report.symbol == instrument.symbol && report.freshness == DataFreshness.LIVE && report.dom.synchronized)
                            .put("delta", report.footprint.deltaPct).put("ofi", report.dom.normalizedOfi)
                            .put("book_imbalance", report.dom.bookImbalancePct)
                            .put("persistence", report.dom.levelPersistenceRatio)
                            .put("liquidity_pull", report.dom.liquidityPullDetected)
                            .put("oi", market.openInterest ?: 0.0)
                            .put("absorption", if (!report.absorption.confirmed) 0 else when (report.absorption.side) {
                                AbsorptionSide.BUYER_ABSORPTION -> 1
                                AbsorptionSide.SELLER_ABSORPTION -> -1
                                else -> 0
                            })
                        // Cross-venue observations already owned by Kotlin are reused, not polled twice.
                        if (app.engine.binanceConnected && app.engine.binancePrice > 0) {
                            metrics.put("cross_price", app.engine.binancePrice).put("cross_ts", app.engine.crossPriceAtMs)
                        } else if (app.engine.okxConnected && app.engine.okxPrice > 0) {
                            metrics.put("cross_price", app.engine.okxPrice).put("cross_ts", app.engine.crossPriceAtMs)
                        }
                        val fees = app.botEngine.uiState.value.let { it.makerFeePct + it.takerFeePct }
                        val payload = JSONObject().put("instrument", JSONObject()
                            .put("key", instrument.key).put("generation", instrument.generation)
                            .put("category", instrument.category).put("base", instrument.baseCoin)
                            .put("quote", instrument.quoteCoin).put("round_trip_fee_pct", fees))
                            .put("bars", bars).put("trades", trades).put("metrics", metrics).put("enrich", true)
                        val result = JSONObject(Python.getInstance().getModule("enacore").callAttr("process_json", payload.toString()).toString())
                        if (app.activeInstrument.value == instrument && !result.optBoolean("discarded")) {
                            _state.value = EnacoreSnapshot.parse(result)
                        }
                    }
                } catch (e: Exception) {
                    if (app.activeInstrument.value == instrument) _state.value = EnacoreSnapshot(
                        key = instrument.key, generation = instrument.generation, verdict = "VETO",
                        reason = "Ядро недоступно: " + (e.message ?: "ошибка").take(100))
                }
                delay(1000)
            }
        }
    }

    fun position(id: String, symbol: String, opened: Boolean, netPnl: Double = 0.0) {
        app.appScope.launch {
            if (PythonBridge.isReady()) runCatching {
                val event = JSONObject().put("id", id).put("symbol", symbol).put("net_pnl", netPnl).put("at", System.currentTimeMillis())
                Python.getInstance().getModule("enacore").callAttr("position_json", event.toString(), opened)
            }
        }
    }
}
