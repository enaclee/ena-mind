package com.enamind.ai.core

import android.content.Context
import android.util.Log
import com.enamind.ai.bybit.BinanceWs
import com.enamind.ai.bybit.BybitWs
import com.enamind.ai.bybit.MultiVenueDataBridge
import com.enamind.ai.bybit.OkxWs
import com.enamind.ai.intelligence.ExchangeClockSync
import com.enamind.ai.intelligence.IntelApiClient
import com.enamind.ai.intelligence.RealTimeLeadEngine
import com.enamind.ai.intelligence.RealtimePulseVault
import com.enamind.ai.intelligence.TrendMemoryVault
import com.enamind.ai.intelligence.TrendReasoningEngine
import com.enamind.ai.intelligence.UnifiedIntelligenceReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * NEURAL FORECAST ENGINE v9.0 — Единый оркестратор (Python + 9 Kotlin-ботов + Multi-Venue + RealTime Lead + Chain Radar + Sentinel).
 */
class NeuralEngine(
    private val ctx: Context,
    private val state: AppState
) {
    companion object {
        val WATCHLIST = Settings.SYMBOLS
        private const val TAG = "NeuralEngine"
        const val ENGINE_VERSION = "9.0"
    }

    private val scope: CoroutineScope get() = state.appScope
    private val started = AtomicBoolean(false)
    private val computeMutex = Mutex()
    private val warmupMutex = Mutex()
    private val intelMutex = Mutex()

    val intelClient = IntelApiClient()
    val clockSync = ExchangeClockSync()
    val multiVenueBridge = MultiVenueDataBridge()
    val memoryVault by lazy { TrendMemoryVault.create(ctx) }
    val pulseVault by lazy {
        RealtimePulseVault(
            primaryDir = File(File(ctx.filesDir, "enamind"), "realtime_pulse_vault"),
            mirrorDir = ctx.getExternalFilesDir("ENA-Realtime")
        )
    }

    fun initVault() {
        try {
            memoryVault.ensureInitialized()
            pulseVault.ensureInitialized()
        } catch (e: Exception) {
            Log.w(TAG, "initVault: ${e.message}")
        }
    }

    // ---------------- UI-состояние ----------------
    /** Последний результат конвейера активного символа (полный JSON движка). */
    private val _result = MutableStateFlow<JSONObject?>(null)
    val result: StateFlow<JSONObject?> = _result

    /** Единый отчёт разума, ботов, Chain Radar и Crypto Sentinel. */
    private val _intelligence = MutableStateFlow<UnifiedIntelligenceReport?>(null)
    val intelligence: StateFlow<UnifiedIntelligenceReport?> = _intelligence

    /** Живой упреждающий предиктор 5–15м (нижняя волновая панель реального времени). */
    private val _realTimePulse = MutableStateFlow(
        RealTimeLeadEngine.RealTimeTrendPulse(symbol = state.market.value.symbol)
    )
    val realTimePulse: StateFlow<RealTimeLeadEngine.RealTimeTrendPulse> = _realTimePulse

    private val _radarRefreshing = MutableStateFlow(false)
    val radarRefreshing: StateFlow<Boolean> = _radarRefreshing

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** Последний активный источник свечей движка ("bybit", "binance", "okx"). */
    private val _klineSource = MutableStateFlow("")
    val klineSource: StateFlow<String> = _klineSource

    /** Фоновые результаты по остальным активам: symbol -> короткая сводка. */
    private val _watch = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
    val watch: StateFlow<Map<String, JSONObject>> = _watch

    // ---------------- WS бирж (независимое подтверждение + fallback для UI) ----------------
    private var binanceWs: BinanceWs? = null
    private var okxWs: OkxWs? = null
    @Volatile var binancePrice = 0.0
    @Volatile var okxPrice = 0.0
    @Volatile var binanceDepth: Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>>? = null
    @Volatile var binanceFlow: BinanceWs.FlowStats? = null
    @Volatile var okxFlow: OkxWs.FlowStats? = null
    @Volatile var binanceNote = "подключение…"
    @Volatile var okxNote = "подключение…"
    @Volatile var binanceConnected = false
    @Volatile var okxConnected = false

    // кэш альтернативных свечей (для корреляций): symbol -> [[ts,o,h,l,c,v]...]
    private val altKlines = HashMap<String, JSONArray>()

    // общий Kotlin-кэш свечей по ("SYMBOL:TF") — работает независимо от Python
    private val klinesCache = ConcurrentHashMap<String, List<List<Double>>>()
    private val lastSyncedKlineSig = ConcurrentHashMap<String, String>()
    @Volatile private var lastRadarRaw: IntelApiClient.RawRadarData? = null
    private val lastRadarFetchMs = AtomicLong(0L)
    @Volatile private var lastPythonOkResult: JSONObject? = null
    @Volatile var latestVenueSnapshot: MultiVenueDataBridge.VenueSnapshot? = null

    val symbol: String get() = state.market.value.symbol
    val chartInterval: String get() = state.market.value.interval

    // ================================================================
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            initVault()
            connectExchanges()
            if (state.settings.bgEngine) EngineService.start(ctx)
            // Параллельно поднимаем Python и прогреваем свечи/разведку
            val pyJob = launch(Dispatchers.IO) { ensurePython() }
            try {
                pushKlines(symbol)
                refreshIntelligence(forceRadar = true)
            } catch (e: Exception) {
                Log.w(TAG, "initial warmup: ${e.message}")
            }
            pyJob.join()
            try {
                if (PythonBridge.isReady()) {
                    syncCacheToPython(symbol)
                    runComputeOnce(symbol)
                }
            } catch (e: Exception) {
                Log.w(TAG, "initial python compute: ${e.message}")
            }
            scope.launch { klinesLoop() }
            scope.launch { computeLoop() }
            scope.launch { realTimeLeadLoop() }
            scope.launch { radarLoop() }
            scope.launch { backfillLoop() }
            scope.launch { watchlistLoop() }
            scope.launch { verifyLoop() }
            scope.launch { auditLoop() }
        }
    }

    /** Синхронизация свечей, уже загруженных графиком в AppState. */
    fun seedChartKlines(sym: String, iv: String, rows: List<List<Double>>) {
        if (rows.size < 5) return
        klinesCache["$sym:$iv"] = rows
        if (PythonBridge.isReady()) {
            val sig = klineSig(rows)
            val key = "$sym:$iv"
            if (lastSyncedKlineSig[key] != sig) {
                scope.launch(Dispatchers.IO) {
                    val r = PythonBridge.engineSetKlines(sym, iv, toJsonRows(rows).toString())
                    if (r.optBoolean("ok", true)) lastSyncedKlineSig[key] = sig
                }
            }
        }
    }

    /** v7.1: включить/выключить фоновый режим на лету. */
    fun setBackgroundMode(on: Boolean) {
        if (on) EngineService.start(ctx) else EngineService.stop(ctx)
    }

    private fun ensurePython(): Boolean {
        if (PythonBridge.isReady()) return true
        val r = PythonBridge.init(ctx)
        val ok = r.optBoolean("ok")
        if (!ok) {
            Log.w(TAG, "Python init unavailable: ${PythonBridge.lastInitError}")
        }
        return ok
    }

    fun changeSymbol() {
        binancePrice = 0.0
        okxPrice = 0.0
        binanceDepth = null
        binanceFlow = null
        okxFlow = null
        _result.value = null
        lastPythonOkResult = null
        connectExchanges()
        scope.launch {
            pushKlines(symbol)
            refreshIntelligence(forceRadar = true)
            runComputeOnce(symbol)
        }
    }

    fun changeInterval() {
        connectBinanceOnly()
    }

    // ---------------- соединения бирж ----------------
    private fun connectExchanges() {
        connectBinanceOnly()
        connectOkxOnly()
    }

    private fun connectBinanceOnly() {
        val sym = symbol
        val iv = chartInterval
        binanceWs?.close()
        binanceWs = BinanceWs(sym, object : BinanceWs.Listener {
            override fun onFlow(flow: BinanceWs.FlowStats) {
                binanceFlow = flow
                state.onFallbackFlow(sym, flow)
            }

            override fun onPrice(price: Double) {
                binancePrice = price
                state.onFallbackPrice(sym, price, "Binance")
            }

            override fun onDepth(bids: List<Pair<Double, Double>>, asks: List<Pair<Double, Double>>) {
                binanceDepth = bids to asks
                state.onFallbackDepth(sym, bids, asks)
            }

            override fun onKline(bar: BybitWs.Kline) {
                state.onFallbackKline(sym, iv, bar)
            }

            override fun onTicker24h(price: Double, change24hPct: Double, volume24h: Double, quoteVolume24h: Double) {
                binancePrice = price
                state.onFallbackTicker24h(sym, price, change24hPct, volume24h, quoteVolume24h, "Binance")
            }

            override fun onStatus(connected: Boolean, note: String) {
                binanceConnected = connected
                binanceNote = note
                state.onFallbackStatus()
            }
        }, klineInterval = iv).also { it.connect() }
    }

    private fun connectOkxOnly() {
        val sym = symbol
        okxWs?.close()
        okxWs = OkxWs(sym, object : OkxWs.Listener {
            override fun onFlow(flow: OkxWs.FlowStats) {
                okxFlow = flow
                state.onFallbackOkxFlow(sym, flow)
            }

            override fun onPrice(price: Double, vol24hCcy: Double) {
                okxPrice = price
                state.onFallbackPrice(sym, price, "OKX", vol24hCcy)
            }

            override fun onStatus(connected: Boolean, note: String) {
                okxConnected = connected
                okxNote = note
                state.onFallbackStatus()
            }
        }).also { it.connect() }
    }

    // ---------------- тяжёлый канал: свечи ----------------
    private suspend fun klinesLoop() {
        while (scope.isActive) {
            delay(45_000)
            try {
                pushKlines(symbol)
                pushAltKlines()
            } catch (e: Exception) {
                Log.w(TAG, "klines: ${e.message}")
            }
        }
    }

    /** Свечи активного символа по основным ТФ (Bybit -> Binance Vision/api1/api3 -> OKX). */
    private suspend fun pushKlines(sym: String): Boolean = warmupMutex.withLock {
        withContext(Dispatchers.IO) {
            // Сначала загружаем "1" (он быстрее переводит Bybit в cooldown при геоблоке)
            val r1 = state.rest.klinesUnifiedSync(sym, "1", 250)
            if (r1.rows.isNotEmpty()) {
                klinesCache["$sym:1"] = r1.rows
                _klineSource.value = r1.source
            }
            val otherTfs = coroutineScope {
                listOf("3", "5", "15", "60").map { tf ->
                    async(Dispatchers.IO) {
                        tf to state.rest.klinesUnifiedSync(sym, tf, 250)
                    }
                }.awaitAll()
            }
            var anyOk = r1.rows.isNotEmpty()
            for ((tf, res) in otherTfs) {
                if (res.rows.isEmpty()) continue
                anyOk = true
                klinesCache["$sym:$tf"] = res.rows
                if (_klineSource.value.isBlank()) _klineSource.value = res.source
            }
            if (PythonBridge.isReady()) {
                syncCacheToPython(sym)
            }
            anyOk
        }
    }

    private fun klineSig(rows: List<List<Double>>): String {
        val last = rows.lastOrNull()
        val firstTs = rows.firstOrNull()?.getOrNull(0)?.toLong() ?: 0L
        val lastTs = last?.getOrNull(0)?.toLong() ?: 0L
        val lastClose = last?.getOrNull(4) ?: 0.0
        return "${rows.size}:$firstTs:$lastTs:$lastClose"
    }

    private fun syncCacheToPython(sym: String) {
        for (tf in listOf("1", "3", "5", "15", "60")) {
            val key = "$sym:$tf"
            val rows = klinesCache[key] ?: continue
            if (rows.size < 5) continue
            val sig = klineSig(rows)
            if (lastSyncedKlineSig[key] == sig) continue
            val res = PythonBridge.engineSetKlines(sym, tf, toJsonRows(rows).toString())
            if (res.optBoolean("ok", true)) {
                lastSyncedKlineSig[key] = sig
            }
        }
    }

    private fun toJsonRows(list: List<List<Double>>): JSONArray {
        val rows = JSONArray()
        for (r in list) {
            rows.put(
                JSONArray()
                    .put(r[0].toLong())
                    .put(r[1])
                    .put(r[2])
                    .put(r[3])
                    .put(r[4])
                    .put(r[5])
            )
        }
        return rows
    }

    private fun collectKlinesFor(sym: String): Map<String, List<List<Double>>> {
        val out = LinkedHashMap<String, List<List<Double>>>()
        for (tf in listOf("1", "3", "5", "15", "60", "240", "D")) {
            klinesCache["$sym:$tf"]?.takeIf { it.isNotEmpty() }?.let { out[tf] = it }
        }
        // Подхватываем свечи текущего графика AppState, если по какому-то ТФ ещё пусто
        if (sym == symbol) {
            val chartBars = state.klines.value
            val chartIv = state.market.value.interval
            if (chartBars.isNotEmpty() && !out.containsKey(chartIv)) {
                out[chartIv] = chartBars
            }
        }
        return out
    }

    /** 1m-свечи всех активов watchlist (кросс-активные корреляции). */
    private suspend fun pushAltKlines() = withContext(Dispatchers.IO) {
        for (s in WATCHLIST) {
            if (s == symbol) continue
            val res = state.rest.klinesUnifiedSync(s, "1", 120)
            if (res.rows.isEmpty()) continue
            klinesCache["$s:1"] = res.rows
            synchronized(altKlines) { altKlines[s] = toJsonRows(res.rows) }
            delay(120)
        }
    }

    // ---------------- контур Chain Radar + Crypto Sentinel + Боты ----------------
    private suspend fun radarLoop() {
        while (scope.isActive) {
            delay(50_000)
            try {
                state.ensureFreshNews(maxAgeMs = 5 * 60_000L, force = false)
                refreshIntelligence(forceRadar = true)
            } catch (e: Exception) {
                Log.w(TAG, "radarLoop: ${e.message}")
            }
        }
    }

    suspend fun refreshIntelligence(forceRadar: Boolean = false): UnifiedIntelligenceReport = intelMutex.withLock {
        val targetSym = symbol
        if (forceRadar) _radarRefreshing.value = true
        return@withLock try {
            if (forceRadar) {
                try {
                    state.ensureFreshNews(maxAgeMs = 30_000L, force = false)
                } catch (_: Exception) {
                }
            }
            val now = System.currentTimeMillis()
            val needFetch = forceRadar ||
                lastRadarRaw == null ||
                lastRadarRaw?.symbol != targetSym ||
                (now - lastRadarFetchMs.get()) > 45_000L

            val m = state.market.value
            val btcPrice = if (targetSym == "BTCUSDT") m.price else null

            val rawRadar = if (needFetch) {
                withContext(Dispatchers.IO) {
                    intelClient.fetchRadarSnapshotSync(targetSym, btcPrice, force = forceRadar)
                }.also {
                    lastRadarRaw = it
                    lastRadarFetchMs.set(System.currentTimeMillis())
                }
            } else {
                lastRadarRaw!!
            }

            var kmap = collectKlinesFor(targetSym)
            if (kmap.isEmpty()) {
                pushKlines(targetSym)
                kmap = collectKlinesFor(targetSym)
            }

            val crossSpreadBps = if (binancePrice > 0 && okxPrice > 0) {
                (okxPrice - binancePrice) / binancePrice * 10000.0
            } else null

            val venueSnap = withContext(Dispatchers.IO) {
                multiVenueBridge.fetchVenueSnapshotSync(targetSym)
            }.also { latestVenueSnapshot = it }

            val clockSt = withContext(Dispatchers.IO) {
                clockSync.syncIfNeeded(state.rest.http, state.rest.isBybitInCooldown(), force = forceRadar)
            }
            val nowMs = clockSync.exchangeNowMs()
            val verifyBars = kmap["15"] ?: kmap["60"] ?: kmap["1"] ?: kmap["5"] ?: kmap.values.firstOrNull().orEmpty()
            withContext(Dispatchers.IO) {
                if (verifyBars.isNotEmpty()) {
                    memoryVault.verifyPending(targetSym, verifyBars, nowMs)
                }
            }
            val learnedWeights = memoryVault.currentWeights()
            val learningSnap = memoryVault.snapshot()

            val report = withContext(Dispatchers.Default) {
                TrendReasoningEngine.analyze(
                    symbol = targetSym,
                    klinesByTf = kmap,
                    market = m,
                    crossSpreadBps = crossSpreadBps,
                    radarRaw = rawRadar,
                    pythonResult = lastPythonOkResult,
                    nowMs = nowMs,
                    learnedWeights = learnedWeights,
                    learning = learningSnap,
                    clockStatus = clockSt,
                    venueSnap = venueSnap,
                    binancePrice = binancePrice,
                    okxPrice = okxPrice
                )
            }
            val entryPx = report.price
            if (entryPx != null && entryPx > 0.0 && kmap.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    memoryVault.recordObservation(report, entryPx)
                }
            }
            val updatedReport = report.copy(learning = memoryVault.snapshot())

            if (targetSym == symbol) {
                _intelligence.value = updatedReport
                val curRes = _result.value
                if ((curRes == null || !curRes.optBoolean("ok")) && kmap.isNotEmpty()) {
                    _result.value = updatedReport.toEngineJson()
                }
            }
            updatedReport
        } finally {
            if (forceRadar) _radarRefreshing.value = false
        }
    }

    // ---------------- лёгкий канал: срез каждые 5с ----------------
    private suspend fun computeLoop() {
        while (scope.isActive) {
            delay(5_000)
            runComputeOnce(symbol)
        }
    }

    private suspend fun runComputeOnce(targetSym: String): JSONObject? = computeMutex.withLock {
        _running.value = true
        return try {
            var pyRes: JSONObject? = null
            if (ensurePython()) {
                syncCacheToPython(targetSym)
                val fast = buildFast()
                val resStr = withContext(Dispatchers.IO) {
                    PythonBridge.engineFeed(targetSym, fast.toString())
                }
                pyRes = try {
                    JSONObject(resStr)
                } catch (e: Exception) {
                    Log.w(TAG, "parse engineFeed: ${e.message}")
                    null
                }
            }

            if (pyRes != null && pyRes.optBoolean("ok")) {
                lastPythonOkResult = pyRes
                if (targetSym == symbol) {
                    _result.value = pyRes
                }
                // Синхронизируем Kotlin-разум и Sentinel с новым Python-срезом
                scope.launch {
                    try {
                        refreshIntelligence(forceRadar = false)
                    } catch (_: Exception) {
                    }
                }
                return pyRes
            }

            // Надёжный Kotlin-резерв: если Python ещё не готов или вернул grade D,
            // но у нас есть свечи любого ТФ (из klinesCache или графика AppState),
            // вычисляем полноценный отчёт системой ботов TrendReasoningEngine!
            var kmap = collectKlinesFor(targetSym)
            if (kmap.isEmpty()) {
                withContext(Dispatchers.IO) {
                    val r15 = state.rest.klinesUnifiedSync(targetSym, "15", 200)
                    if (r15.rows.isNotEmpty()) klinesCache["$targetSym:15"] = r15.rows
                    val r60 = state.rest.klinesUnifiedSync(targetSym, "60", 200)
                    if (r60.rows.isNotEmpty()) klinesCache["$targetSym:60"] = r60.rows
                }
                kmap = collectKlinesFor(targetSym)
            }

            if (kmap.isNotEmpty()) {
                val intel = refreshIntelligence(forceRadar = lastRadarRaw == null)
                val fallbackJson = intel.toEngineJson()
                if (targetSym == symbol) {
                    _result.value = fallbackJson
                }
                return fallbackJson
            }

            pyRes
        } catch (e: Exception) {
            Log.w(TAG, "compute: ${e.message}")
            null
        } finally {
            _running.value = false
        }
    }

    /**
     * Немедленный расчёт по требованию (для Чата):
     * гарантирует наличие свечей (из общего кэша / Bybit / Binance Vision+mirrors / OKX)
     * и возвращает результат Python-ядра или Kotlin-системы ботов TrendReasoningEngine.
     */
    suspend fun computeNow(): JSONObject? {
        val current = _result.value
        val curSym = current?.optString("symbol")
        if (current != null && current.optBoolean("ok") && curSym == symbol) {
            return current
        }
        val kmap = collectKlinesFor(symbol)
        if (kmap.size < 2) {
            pushKlines(symbol)
        }
        return runComputeOnce(symbol)
    }

    /**
     * Свежий расчёт прогноза на 6ч или 24ч при КАЖДОМ запросе пользователя (без устаревшего кэша).
     * Объединяет актуальные свечи, мульти-биржевой срез, Chain Radar, деривативы и Python-оценку.
     */
    suspend fun computeFreshOutlook(horizonHours: Int): JSONObject {
        val targetSym = symbol
        val m = state.market.value
        var kmap = collectKlinesFor(targetSym)
        if (kmap["15"].isNullOrEmpty() || kmap["60"].isNullOrEmpty()) {
            pushKlines(targetSym)
            kmap = collectKlinesFor(targetSym)
        }
        val venueSnap = withContext(Dispatchers.IO) {
            multiVenueBridge.fetchVenueSnapshotSync(targetSym)
        }.also { latestVenueSnapshot = it }
        val btcPx = if (targetSym == "BTCUSDT") m.price else null
        val radarRaw = withContext(Dispatchers.IO) {
            intelClient.fetchRadarSnapshotSync(targetSym, btcPx, force = false)
        }.also { lastRadarRaw = it }
        val crossSpreadBps = if (binancePrice > 0 && okxPrice > 0) {
            (okxPrice - binancePrice) / binancePrice * 10000.0
        } else null
        val clockSt = withContext(Dispatchers.IO) {
            clockSync.syncIfNeeded(state.rest.http, state.rest.isBybitInCooldown(), force = false)
        }
        val nowMs = clockSync.exchangeNowMs()
        val fastJson = buildFast()
        var pyOutlook: JSONObject? = null
        if (ensurePython()) {
            withContext(Dispatchers.IO) {
                syncCacheToPython(targetSym)
                val r = PythonBridge.callEngine("engine_outlook", targetSym, horizonHours, fastJson.toString())
                if (r.optBoolean("ok")) pyOutlook = r
            }
        }
        val wrappedPy = pyOutlook?.let { py ->
            val key = if (horizonHours <= 6) "outlook_6h" else "outlook_24h"
            JSONObject().put("ok", true).put(key, py)
        } ?: lastPythonOkResult

        val report = withContext(Dispatchers.Default) {
            TrendReasoningEngine.analyze(
                symbol = targetSym,
                klinesByTf = kmap,
                market = m,
                crossSpreadBps = crossSpreadBps,
                radarRaw = radarRaw,
                pythonResult = wrappedPy,
                nowMs = nowMs,
                learnedWeights = memoryVault.currentWeights(),
                learning = memoryVault.snapshot(),
                clockStatus = clockSt,
                venueSnap = venueSnap,
                binancePrice = binancePrice,
                okxPrice = okxPrice
            )
        }
        if (targetSym == symbol) {
            _intelligence.value = report
        }
        val summary = if (horizonHours <= 6) report.outlook6h else report.outlook24h
        return summary?.toJson(targetSym, report.price ?: m.price)
            ?: JSONObject().put("ok", false).put("error", "Недостаточно свечей для расчёта прогноза ${horizonHours}ч")
    }

    /** Живой срез рынка: всё, что движок получает из WS и REST. */
    private fun buildFast(): JSONObject {
        val m = state.market.value
        val fast = JSONObject()
        val activePx = m.price
            ?: binancePrice.takeIf { it > 0 }
            ?: okxPrice.takeIf { it > 0 }
        activePx?.let { fast.put("price", it) }

        val t = m.tape
        fast.put("tape", JSONObject()
            .put("ok", t != null && t.n >= 10)
            .put("n", t?.n ?: 0)
            .put("cvd", t?.cvd ?: 0.0)
            .put("delta_pct", t?.deltaPct ?: 0.0)
            .put("price_change_pct", t?.priceChangePct ?: 0.0)
            .put("divergence", t?.divergence ?: false)
            .put("age_sec", 1.0))

        fast.put("book", JSONObject()
            .put("ok", m.bids.isNotEmpty() && m.asks.isNotEmpty())
            .put("bids", levels(m.bids))
            .put("asks", levels(m.asks)))

        fast.put("deriv", JSONObject()
            .put("ok", activePx != null)
            .put("funding_pct", m.fundingPct ?: JSONObject.NULL)
            .put("oi", m.openInterest ?: JSONObject.NULL)
            .put("oi_pct_change", m.oiChange24hPct ?: JSONObject.NULL)
            .put("lsr", m.lsr ?: JSONObject.NULL)
            .put("lsr_pct_rank", m.lsrPct ?: JSONObject.NULL)
            .put("liquidations", state.liqStats(symbol)))

        binanceFlow?.let { f ->
            fast.put("binance_flow", JSONObject()
                .put("ok", f.n >= 10).put("n", f.n)
                .put("cvd", f.cvd).put("delta_pct", f.deltaPct)
                .put("age_sec", f.ageSec))
        }
        binanceDepth?.let { (bids, asks) ->
            val b = bids.sumOf { it.second }; val a = asks.sumOf { it.second }
            fast.put("binance_depth", JSONObject()
                .put("ok", bids.isNotEmpty())
                .put("imbalance_pct", if (b + a > 0) 100.0 * (b - a) / (b + a) else 0.0)
                .put("best_bid", bids.firstOrNull()?.first ?: 0.0)
                .put("best_ask", asks.firstOrNull()?.first ?: 0.0))
        }
        okxFlow?.let { f ->
            fast.put("okx_flow", JSONObject()
                .put("ok", f.n >= 10).put("n", f.n)
                .put("cvd", f.cvd).put("delta_pct", f.deltaPct)
                .put("age_sec", f.ageSec))
        }

        val bybitPx = m.price.takeIf { m.feedSource == "Bybit" }
        val bnPx = binancePrice
        val oxPx = okxPrice
        val parts = JSONObject()
        if (bybitPx != null) parts.put("bybit", JSONObject()
            .put("ok", true).put("last", bybitPx)
            .put("funding_pct", m.fundingPct ?: 0.0)
            .put("oi", m.openInterest ?: 0.0))
        if (bnPx > 0) parts.put("binance_price", JSONObject().put("ok", true).put("last", bnPx))
        if (oxPx > 0) parts.put("okx_price", JSONObject().put("ok", true).put("last", oxPx))
        binanceFlow?.let { parts.put("binance_flow", JSONObject().put("ok", it.n >= 10).put("delta_pct", it.deltaPct)) }
        okxFlow?.let { parts.put("okx_flow", JSONObject().put("ok", it.n >= 10).put("delta_pct", it.deltaPct)) }
        val diffs = JSONObject()
        if (bybitPx != null && bnPx > 0) diffs.put("bybit_binance_bps", (bnPx - bybitPx) / bybitPx * 10000)
        if (bybitPx != null && oxPx > 0) diffs.put("bybit_okx_bps", (oxPx - bybitPx) / bybitPx * 10000)
        if (bnPx > 0 && oxPx > 0) diffs.put("binance_okx_bps", (oxPx - bnPx) / bnPx * 10000)
        val nEx = (if (bybitPx != null) 1 else 0) + (if (bnPx > 0) 1 else 0) + (if (oxPx > 0) 1 else 0)
        fast.put("cross", JSONObject().put("parts", parts).put("diffs", diffs).put("n_exchanges", nEx))

        val alts = JSONObject()
        synchronized(altKlines) {
            for ((s, rows) in altKlines) alts.put(s, rows)
        }
        if (alts.length() > 0) fast.put("alt_klines", alts)

        if (m.fng != null) fast.put("fng", JSONObject()
            .put("ok", true).put("value", m.fng)
            .put("delta3", m.fngDelta3 ?: 0).put("delta7", 0)
            .put("classification", m.fngClassification ?: ""))
        fast.put("news", m.news)
        fast.put("ws_status", JSONObject()
            .put("binance", binanceNote).put("okx", okxNote)
            .put("bybit", if (m.wsConnected) "подключён" else m.wsNote))

        latestVenueSnapshot?.let { vs ->
            fast.put("multi_venue", JSONObject().apply {
                vs.coinbasePrice?.let { put("coinbase_price", it) }
                vs.krakenPrice?.let { put("kraken_price", it) }
                vs.binanceTradeVelocityPerSec?.let { put("binance_trade_vel_sec", it) }
                vs.binanceAggressiveBuyPct?.let { put("binance_aggr_buy_pct", it) }
                put("binance_whale_spike_usd", vs.binanceWhaleSpikeUsd)
                put("active_venues", vs.activeVenueCount)
            })
        }
        return fast
    }

    private fun levels(list: List<BybitWs.BookLevel>): JSONArray {
        val arr = JSONArray()
        list.take(25).forEach { arr.put(JSONArray().put(it.price).put(it.qty)) }
        return arr
    }

    // ---------------- редкие бэкфиллы ----------------
    private suspend fun backfillLoop() {
        delay(2_000)
        var ticks = 0
        while (scope.isActive) {
            try {
                if (ensurePython()) {
                    withContext(Dispatchers.IO) {
                        if (!state.rest.isBybitInCooldown()) {
                            PythonBridge.callEngine("engine_fetch_deriv", symbol)
                        }
                        PythonBridge.callEngine("engine_fetch_aux", symbol)
                        PythonBridge.callEngine("engine_fetch_options", symbol)
                        PythonBridge.callEngine("engine_fetch_web", symbol)
                        PythonBridge.callEngine("engine_refresh_onchain", symbol)
                    }
                    if (ticks % 20 == 0) {
                        withContext(Dispatchers.IO) { PythonBridge.callEngine("engine_refresh_slow") }
                    }
                    if (ticks % 40 == 10) {
                        withContext(Dispatchers.IO) { PythonBridge.callEngine("engine_train_model", symbol) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "backfill: ${e.message}")
            }
            ticks++
            delay(30_000)
        }
    }

    // ---------------- высокочастотный цикл упреждающего тренда (каждые ~2с) ----------------
    private suspend fun realTimeLeadLoop() {
        var tick = 0
        while (scope.isActive) {
            try {
                val sym = symbol
                val m = state.market.value
                val bars1m = klinesCache["$sym:1"] ?: state.klines.value
                // Каждые ~10с освежаем мульти-биржевой снимок Coinbase/Kraken/Binance velocity в фоне
                if (tick % 5 == 0) {
                    latestVenueSnapshot = withContext(Dispatchers.IO) {
                        multiVenueBridge.fetchVenueSnapshotSync(sym)
                    }
                }
                val snap = latestVenueSnapshot
                val intel = _intelligence.value?.takeIf { it.symbol == sym }
                val weights = pulseVault.currentWeights()
                val vaultSnap = pulseVault.snapshot()
                val nowMs = System.currentTimeMillis()

                val pulse = withContext(Dispatchers.Default) {
                    RealTimeLeadEngine.evaluate(
                        symbol = sym,
                        market = m,
                        bars1m = bars1m,
                        binancePrice = binancePrice,
                        okxPrice = okxPrice,
                        binanceDepth = binanceDepth,
                        venueSnap = snap,
                        intel = intel,
                        vaultWeights = weights,
                        vaultSnap = vaultSnap,
                        nowMs = nowMs
                    )
                }
                _realTimePulse.value = pulse

                // Фиксируем бакет в RealtimePulseVault и сверяем истёкшие прогнозы по живому графику
                val px = pulse.price
                if (px != null && px > 0.0) {
                    withContext(Dispatchers.IO) {
                        pulseVault.recordPulse(
                            symbol = sym,
                            entryPrice = px,
                            direction = pulse.direction,
                            confidence = pulse.confidence,
                            triggerActive = pulse.strongTrigger,
                            factorVotes = pulse.factorVotes,
                            nowMs = nowMs
                        )
                        if (tick % 3 == 0 && bars1m.isNotEmpty()) {
                            pulseVault.verifyAgainstLiveChart(sym, bars1m, px, nowMs)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "realTimeLeadLoop: ${e.message}")
            }
            tick++
            delay(2_000)
        }
    }

    // ---------------- самодиагностика и контроль сбоев ----------------
    private suspend fun auditLoop() {
        delay(8_000)
        while (scope.isActive) {
            try {
                FaultMonitor.runAudit(state)
            } catch (e: Exception) {
                Log.w(TAG, "auditLoop: ${e.message}")
            }
            delay(30_000)
        }
    }

    // ---------------- фоновый расчёт watchlist (10 активов) ----------------
    private suspend fun watchlistLoop() {
        delay(12_000)
        while (scope.isActive) {
            for (s in WATCHLIST) {
                if (s == symbol) continue
                try {
                    withContext(Dispatchers.IO) {
                        if (!ensurePython()) return@withContext
                        var lastClose = 0.0
                        for (tf in listOf("1", "15", "60")) {
                            val res = state.rest.klinesUnifiedSync(s, tf, 200)
                            if (res.rows.isEmpty()) continue
                            klinesCache["$s:$tf"] = res.rows
                            if (lastClose <= 0.0) lastClose = res.rows.lastOrNull()?.getOrNull(4) ?: 0.0
                            PythonBridge.engineSetKlines(s, tf, toJsonRows(res.rows).toString())
                        }
                        val vSnap = multiVenueBridge.fetchVenueSnapshotSync(s)
                        val fastObj = JSONObject().apply {
                            if (lastClose > 0.0) put("price", lastClose)
                            put("multi_venue", JSONObject().apply {
                                vSnap.coinbasePrice?.let { put("coinbase_price", it) }
                                vSnap.krakenPrice?.let { put("kraken_price", it) }
                                vSnap.binanceTradeVelocityPerSec?.let { put("binance_trade_vel_sec", it) }
                                vSnap.binanceAggressiveBuyPct?.let { put("binance_aggr_buy_pct", it) }
                                put("binance_whale_spike_usd", vSnap.binanceWhaleSpikeUsd)
                            })
                        }
                        val res = JSONObject(PythonBridge.engineFeed(s, fastObj.toString()))
                        if (res.optBoolean("ok")) {
                            val f = res.optJSONObject("forecast")
                            val cur = _watch.value.toMutableMap()
                            cur[s] = JSONObject()
                                .put("symbol", s)
                                .put("side", f?.optString("side") ?: "—")
                                .put("p_up", f?.optDouble("p_up") ?: 0.0)
                                .put("p_down", f?.optDouble("p_down") ?: 0.0)
                                .put("p_flat", f?.optDouble("p_flat") ?: 0.0)
                                .put("grade", res.optJSONObject("data")?.optString("grade") ?: "?")
                                .put("ts", System.currentTimeMillis())
                            _watch.value = cur
                        }
                    }
                    delay(1_200)
                } catch (e: Exception) {
                    Log.w(TAG, "watch $s: ${e.message}")
                }
            }
            delay(90_000)
        }
    }

    // ---------------- автопроверка истёкших прогнозов и гигиена памяти ----------------
    private suspend fun verifyLoop() {
        delay(45_000)
        var cycles = 0
        while (scope.isActive) {
            try {
                if (ensurePython()) {
                    withContext(Dispatchers.IO) { PythonBridge.callEngine("engine_verify_expired") }
                }
                val bars = klinesCache["$symbol:15"] ?: klinesCache["$symbol:60"] ?: klinesCache["$symbol:1"] ?: emptyList()
                val bars1m = klinesCache["$symbol:1"] ?: state.klines.value
                val nowMs = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    if (bars.isNotEmpty()) {
                        memoryVault.verifyPending(symbol, bars, nowMs)
                    }
                    if (bars1m.isNotEmpty()) {
                        pulseVault.verifyAgainstLiveChart(symbol, bars1m, state.market.value.price, nowMs)
                    }
                }
                // Каждые ~30 минут поддерживаем гигиену временного кэша и БД при ночной работе
                if (cycles % 6 == 0) {
                    withContext(Dispatchers.IO) {
                        StorageGuard.sanitizeOnStartup(ctx)
                        StorageGuard.pruneDatabase(state.db)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "verify: ${e.message}")
            }
            cycles++
            delay(300_000)
        }
    }

    /** Разовое вычисление с экрана чата или мониторинга. */
    fun runNow() {
        scope.launch {
            computeNow()
        }
    }

    fun refreshSlow() {
        scope.launch {
            withContext(Dispatchers.IO) { PythonBridge.callEngine("engine_refresh_slow") }
        }
    }
}
