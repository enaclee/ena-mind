package com.enamind.ai.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.enamind.ai.ai.AiStudioClient
import com.enamind.ai.bot.BotFloatingOverlayManager
import com.enamind.ai.bot.BotForegroundService
import com.enamind.ai.bot.BotVaultStore
import com.enamind.ai.bot.TradeBotEngine
import com.enamind.ai.bybit.BinanceWs
import com.enamind.ai.bybit.BookState
import com.enamind.ai.bybit.BybitRest
import com.enamind.ai.bybit.BybitWs
import com.enamind.ai.bybit.ContractCatalogRepo
import com.enamind.ai.bybit.LiquidationAggregator
import com.enamind.ai.bybit.OkxWs
import com.enamind.ai.bybit.TapeAggregator
import com.enamind.ai.calendar.MajorCalendarRepo
import com.enamind.ai.db.AnalysisEntity
import com.enamind.ai.db.ApiCheckEntity
import com.enamind.ai.db.AppDatabase
import com.enamind.ai.db.ForecastEntity
import com.enamind.ai.db.NewsItemEntity
import com.enamind.ai.export.ZipManager
import com.enamind.ai.news.NewsRepo
import com.enamind.ai.orderflow.OrderFlowEventVault
import com.enamind.ai.orderflow.OrderFlowIntelligenceOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Единое состояние ENA:
 * ORDER FLOW & ABSORPTION INTELLIGENCE, MARKET MONITOR,
 * NEURAL ENGINE (боты + 6ч/24ч аналитика), Календарь крупных событий (раз в 15 дней),
 * каталог всех контрактов биржи и изолированный универсальный ИИ-чат.
 */
class AppState(
    private val ctx: Context,
    val db: AppDatabase
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = Settings(ctx)
    val contractCatalog = ContractCatalogRepo(ctx, settings)
    val majorCalendar = MajorCalendarRepo(ctx, settings)
    val orderFlow = OrderFlowIntelligenceOrchestrator(ctx)
    val botVault = BotVaultStore(ctx)

    private val _favoriteSymbols = MutableStateFlow(settings.favoriteSymbols)
    val favoriteSymbols: StateFlow<Set<String>> = _favoriteSymbols

    fun toggleFavoriteSymbol(sym: String) {
        val clean = sym.trim().uppercase()
        if (clean.isBlank()) return
        val next = _favoriteSymbols.value.toMutableSet()
        if (!next.add(clean)) next.remove(clean)
        settings.favoriteSymbols = next
        _favoriteSymbols.value = next
    }

    fun refreshContractsCatalog(force: Boolean = false) {
        appScope.launch(Dispatchers.IO) {
            contractCatalog.refreshSync(force = force)
        }
    }

    fun refreshMajorCalendar(force: Boolean = true) {
        appScope.launch(Dispatchers.IO) {
            if (force) majorCalendar.refreshNowSync() else majorCalendar.refreshIfDueSync()
        }
    }

    fun exportOrderFlowHistoryBundle(): OrderFlowEventVault.ExportBundle =
        orderFlow.exportHistoryBundle()

    // ---------------- MARKET MONITOR ----------------
    data class MarketUi(
        val symbol: String = "BTCUSDT",
        val interval: String = "60",
        val price: Double? = null,
        val change24hPct: Double? = null,
        val volume24h: Double? = null,
        val turnover24h: Double? = null,
        val fundingPct: Double? = null,
        val openInterest: Double? = null,
        val oiChange24hPct: Double? = null,
        val lsr: Double? = null,
        val lsrPct: Double? = null,
        val fng: Int? = null,
        val fngDelta3: Int? = null,
        val fngClassification: String? = null,
        val liq1hUsd: Double? = null,
        val liq24hUsd: Double? = null,
        val bids: List<BybitWs.BookLevel> = emptyList(),
        val asks: List<BybitWs.BookLevel> = emptyList(),
        val tape: TapeAggregator.TapeStats? = null,
        val wsConnected: Boolean = false,
        val wsNote: String = "подключение…",
        val feedSource: String = "Подключение…",
        val sources: JSONObject? = null,
        val news: JSONArray = JSONArray(),
        val newsStatus: String = ""
    )

    private val _market = MutableStateFlow(MarketUi(symbol = settings.symbol, interval = settings.interval))
    val market: StateFlow<MarketUi> = _market

    /** Честные статусы тем WS (Bybit или активный fallback Binance/OKX). */
    private val _feedTopics = MutableStateFlow<Map<FeedStatus.Kind, FeedStatus.Topic>>(emptyMap())
    val feedTopics: StateFlow<Map<FeedStatus.Kind, FeedStatus.Topic>> = _feedTopics

    /** Свечи графика: [[ts,o,h,l,c,v],...] старые -> новые. */
    private val _klines = MutableStateFlow<List<List<Double>>>(emptyList())
    val klines: StateFlow<List<List<Double>>> = _klines

    /** Последняя (незакрытая) свеча из WS kline-топика (Bybit или Binance fallback). */
    private val _lastBar = MutableStateFlow<List<Double>?>(null)
    val lastBar: StateFlow<List<Double>?> = _lastBar

    private var ws: BybitWs? = null
    private val tape = TapeAggregator(500)
    private val liq = LiquidationAggregator()
    val rest = BybitRest(settings.bybitBaseUrl)

    // Временные метки живых пакетов Bybit — если Bybit молчит >4с, UI бережно переключается на Binance/OKX
    private val lastBybitTickerMs = AtomicLong(0L)
    private val lastBybitTradeMs = AtomicLong(0L)
    private val lastBybitBookMs = AtomicLong(0L)
    private val lastBybitKlineMs = AtomicLong(0L)

    private val fallbackTracker = FeedStatus.Tracker(
        setOf(
            FeedStatus.Kind.TICKER,
            FeedStatus.Kind.TRADES,
            FeedStatus.Kind.BOOK,
            FeedStatus.Kind.KLINE
        )
    )

    private fun isBybitFresh(tsMs: Long, windowMs: Long = 4_000L): Boolean =
        tsMs > 0L && (System.currentTimeMillis() - tsMs) < windowMs

    fun hasFreshBybitTrades(): Boolean = isBybitFresh(lastBybitTradeMs.get(), 6_000L)
    fun hasFreshBybitBook(): Boolean = isBybitFresh(lastBybitBookMs.get(), 6_000L)

    /** Статистика ликвидаций для движка. */
    fun liqStats(symbol: String): JSONObject = liq.stats(symbol, System.currentTimeMillis())

    // ---------------- NEURAL ENGINE ----------------
    val engine: NeuralEngine by lazy { NeuralEngine(ctx, this) }

    // ---------------- TRADE BOT + FLOATING HUD ----------------
    val botOverlayManager: BotFloatingOverlayManager by lazy {
        BotFloatingOverlayManager(ctx) {
            botEngine.stopAndCloseMarket("Остановлен из плавающего HUD")
        }
    }

    val botEngine: TradeBotEngine by lazy {
        TradeBotEngine(
            vaultStore = botVault,
            contractCatalog = contractCatalog,
            reportProvider = { orderFlow.report.value },
            marketProvider = { _market.value },
            quantAdxErProvider = {
                val q = engine.intelligence.value?.quant
                q?.adx to q?.efficiencyRatio
            },
            clockSync = engine.clockSync,
            onServiceStateChanged = { shouldRun ->
                if (shouldRun) BotForegroundService.start(ctx) else BotForegroundService.stop(ctx)
            },
            onNotify = { notif ->
                BotForegroundService.postHighPriorityAlert(ctx, notif.title, notif.body)
            }
        )
    }

    fun selectBotSymbol(sym: String) {
        val clean = sym.trim().uppercase()
        if (clean.isBlank()) return
        botEngine.updateConfig { it.copy(symbol = clean) }
        changeSymbol(clean)
    }

    // ---------------- ГЛУБОКИЙ АНАЛИЗ A→Б ----------------
    data class ChainNodeUi(
        val name: String, val status: String,
        val summary: String, val reason: String
    )

    private val _chainNodes = MutableStateFlow<List<ChainNodeUi>>(emptyList())
    val chainNodes: StateFlow<List<ChainNodeUi>> = _chainNodes

    private val _chainRunning = MutableStateFlow(false)
    val chainRunning: StateFlow<Boolean> = _chainRunning

    private val _lastReport = MutableStateFlow("")
    val lastReport: StateFlow<String> = _lastReport

    private val _lastResult = MutableStateFlow<JSONObject?>(null)
    val lastResult: StateFlow<JSONObject?> = _lastResult

    private val msgSeq = AtomicLong(0L)
    fun nextChatMsgId(): Long = System.currentTimeMillis() * 1000L + (msgSeq.incrementAndGet() % 1000L)

    suspend fun requestFreshOutlook(horizonHours: Int): JSONObject =
        engine.computeFreshOutlook(horizonHours)

    // ---------------- Изолированный свободный ИИ-чат (Google AI Studio + OpenAI-совместимые) ----------------
    data class AiChatMessage(
        val id: Long,
        val fromUser: Boolean,
        val text: String,
        val attachmentLabels: List<String> = emptyList(),
        val modelId: String = "",
        val latencyMs: Long = 0L,
        val error: Boolean = false,
        val busy: Boolean = false,
        val ts: Long = System.currentTimeMillis()
    )

    private fun activeAiModelId(): String =
        if (settings.aiProvider == Settings.PROVIDER_OPENAI_COMPAT) settings.aiOpenAiModel else settings.aiStudioModel

    private val _aiChatMessages = MutableStateFlow<List<AiChatMessage>>(
        listOf(
            AiChatMessage(
                id = 1L,
                fromUser = false,
                modelId = activeAiModelId(),
                text = "Это изолированный свободный ИИ-чат ENA (Google AI Studio + OpenAI-совместимые ключи).\n" +
                    "Он НЕ вмешивается в расчёты рынка, Order Flow и ботов ENA.\n\n" +
                    "Настройте ключ в Настройках → API и задавайте любые вопросы или прикрепляйте фото и файлы."
            )
        )
    )
    val aiChatMessages: StateFlow<List<AiChatMessage>> = _aiChatMessages

    private val _aiChatBusy = MutableStateFlow(false)
    val aiChatBusy: StateFlow<Boolean> = _aiChatBusy

    suspend fun loadChatAttachment(uri: Uri): AiStudioClient.ChatAttachment? = withContext(Dispatchers.IO) {
        runCatching {
            val cr = ctx.contentResolver
            var displayName = "attachment"
            var reportedSize = -1L
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) displayName = c.getString(nameIdx) ?: displayName
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) reportedSize = c.getLong(sizeIdx)
                }
            }
            val rawMime = cr.getType(uri).orEmpty().ifBlank { guessMimeFromName(displayName) }
            val isImage = rawMime.startsWith("image/", ignoreCase = true)
            val isPdf = rawMime.equals("application/pdf", ignoreCase = true)
            val maxBytes = when {
                isPdf -> 4L * 1024L * 1024L
                isImage -> 2_500_000L
                else -> 512_000L
            }
            if (reportedSize > maxBytes) {
                toast("Файл $displayName слишком большой (лимит ${maxBytes / 1024} КБ)")
                return@withContext null
            }
            val bytes = cr.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) {
                        toast("Файл $displayName превышает лимит ${maxBytes / 1024} КБ")
                        return@withContext null
                    }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: return@withContext null

            if (bytes.isEmpty()) return@withContext null

            if (isImage) {
                AiStudioClient.ChatAttachment(
                    id = UUID.randomUUID().toString(),
                    displayName = displayName,
                    mimeType = rawMime,
                    sizeBytes = bytes.size.toLong(),
                    isImage = true,
                    base64Data = Base64.getEncoder().encodeToString(bytes),
                    textPreview = null
                )
            } else if (isPdf) {
                AiStudioClient.ChatAttachment(
                    id = UUID.randomUUID().toString(),
                    displayName = displayName,
                    mimeType = "application/pdf",
                    sizeBytes = bytes.size.toLong(),
                    isImage = false,
                    base64Data = Base64.getEncoder().encodeToString(bytes),
                    textPreview = null
                )
            } else {
                val decoded = String(bytes, Charsets.UTF_8)
                val preview = if (decoded.length > 24_000) {
                    decoded.take(24_000) + "\n… [обрезано до 24000 символов]"
                } else {
                    decoded
                }
                AiStudioClient.ChatAttachment(
                    id = UUID.randomUUID().toString(),
                    displayName = displayName,
                    mimeType = rawMime.ifBlank { "text/plain" },
                    sizeBytes = bytes.size.toLong(),
                    isImage = false,
                    base64Data = null,
                    textPreview = preview
                )
            }
        }.onFailure {
            toast("Не удалось прочитать файл: ${it.message}")
        }.getOrNull()
    }

    private fun guessMimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "json" -> "application/json"
            "csv" -> "text/csv"
            else -> "text/plain"
        }
    }

    fun sendAiChatMessage(prompt: String, attachments: List<AiStudioClient.ChatAttachment> = emptyList()) {
        val clean = prompt.trim()
        if ((clean.isEmpty() && attachments.isEmpty()) || _aiChatBusy.value) return
        val provider = settings.aiProvider
        val activeModel = activeAiModelId()
        val apiKey = settings.aiStudioApiKey
        val openAiBaseUrl = settings.aiOpenAiBaseUrl
        val openAiKey = settings.aiOpenAiApiKey
        val openAiModel = settings.aiOpenAiModel

        val historySnapshot = _aiChatMessages.value
            .filter { !it.busy && !it.error && it.id != 1L }
            .map { AiStudioClient.ChatTurn(it.fromUser, it.text) }

        val userId = nextChatMsgId()
        val replyId = nextChatMsgId()
        val displayUserText = clean.ifBlank { "Проанализируй приложенные файлы." }
        val attLabels = attachments.map { it.displayName }

        _aiChatMessages.value = (_aiChatMessages.value + listOf(
            AiChatMessage(id = userId, fromUser = true, text = displayUserText, attachmentLabels = attLabels),
            AiChatMessage(id = replyId, fromUser = false, text = "Думаю ($activeModel)…", modelId = activeModel, busy = true)
        )).takeLast(100)
        _aiChatBusy.value = true

        appScope.launch(Dispatchers.IO) {
            val reply = AiStudioClient.sendChat(
                apiKey = apiKey,
                modelId = activeModel,
                history = historySnapshot,
                userPrompt = displayUserText,
                attachments = attachments,
                provider = provider,
                openAiBaseUrl = openAiBaseUrl,
                openAiKey = openAiKey,
                openAiModel = openAiModel
            )
            _aiChatMessages.value = _aiChatMessages.value.map { msg ->
                if (msg.id == replyId) {
                    if (reply.ok) {
                        msg.copy(text = reply.text, modelId = activeModel, latencyMs = reply.latencyMs, error = false, busy = false)
                    } else {
                        msg.copy(text = reply.error ?: "Не удалось получить ответ", modelId = activeModel, latencyMs = reply.latencyMs, error = true, busy = false)
                    }
                } else msg
            }
            _aiChatBusy.value = false
        }
    }

    fun clearAiChat() {
        _aiChatMessages.value = listOf(
            AiChatMessage(
                id = nextChatMsgId(),
                fromUser = false,
                modelId = activeAiModelId(),
                text = "Диалог ИИ очищен. Подключение полностью изолировано от движка ENA."
            )
        )
    }

    // ---------------- UI-события ----------------
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    fun toast(msg: String?) { _toast.value = msg }

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    // ================================================================
    fun start() {
        if (!started.compareAndSet(false, true)) return
        connectWs()
        reloadKlines()
        refreshMarketStats()
        appScope.launch { refreshNews() }
        appScope.launch { contractCatalog.refreshSync(force = false) }
        appScope.launch { calendarAutoLoop() }
        appScope.launch { verifyOldForecasts() }
        appScope.launch { feedStatusLoop() }
        appScope.launch {
            StorageGuard.pruneBotCaches(ctx)
            botEngine.uiState.collect { st ->
                botOverlayManager.updateState(st)
            }
        }
        engine.start()
    }

    private suspend fun calendarAutoLoop() {
        while (true) {
            try {
                majorCalendar.refreshIfDueSync()
            } catch (e: Exception) {
                Log.w("AppState", "calendarAutoLoop: ${e.message}")
            }
            delay(6 * 3600_000L) // проверка каждые 6ч: скачивает с сети только когда прошло 15 дней или кэш пуст
        }
    }

    /** Принудительное обновление новостей, Chain Radar, Crypto Sentinel и календаря. */
    fun refreshRadarNow() {
        appScope.launch {
            try {
                ensureFreshNews(maxAgeMs = 0L, force = true)
                majorCalendar.refreshIfDueSync()
                engine.refreshIntelligence(forceRadar = true)
            } catch (e: Exception) {
                Log.w("AppState", "refreshRadarNow: ${e.message}")
            }
        }
    }

    /** Раз в секунду обновляем снимок честных статусов тем WS. */
    private suspend fun feedStatusLoop() {
        while (true) {
            try {
                fallbackTracker.refreshStale()
                val bybitTracker = ws?.feedTracker
                bybitTracker?.refreshStale()
                val bybitSnap = bybitTracker?.snapshot().orEmpty()
                val fbSnap = fallbackTracker.snapshot()

                val merged = HashMap<FeedStatus.Kind, FeedStatus.Topic>()
                for (kind in FeedStatus.Kind.entries) {
                    val b = bybitSnap[kind]
                    val fb = fbSnap[kind]
                    merged[kind] = when {
                        b != null && b.status == FeedStatus.LIVE -> b
                        fb != null && (fb.status == FeedStatus.LIVE || fb.status == FeedStatus.STALE) -> fb
                        b != null -> b
                        fb != null -> fb
                        else -> FeedStatus.Topic()
                    }
                }
                _feedTopics.value = merged
            } catch (_: Exception) {
            }
            delay(1_000)
        }
    }

    fun changeSymbol(symbol: String) {
        if (_market.value.symbol == symbol) return
        settings.symbol = symbol
        _klines.value = emptyList()
        _lastBar.value = null
        lastBybitTickerMs.set(0L)
        lastBybitTradeMs.set(0L)
        lastBybitBookMs.set(0L)
        lastBybitKlineMs.set(0L)
        fallbackTracker.onConnecting()
        _market.value = _market.value.copy(
            symbol = symbol,
            price = null,
            change24hPct = null,
            volume24h = null,
            turnover24h = null,
            fundingPct = null,
            openInterest = null,
            oiChange24hPct = null,
            lsr = null,
            lsrPct = null,
            liq1hUsd = null,
            liq24hUsd = null,
            bids = emptyList(),
            asks = emptyList(),
            tape = null,
            feedSource = "Подключение…"
        )
        connectWs()
        reloadKlines()
        refreshMarketStats()
        engine.changeSymbol()
    }

    fun changeInterval(interval: String) {
        if (_market.value.interval == interval) return
        settings.interval = interval
        _klines.value = emptyList()
        _lastBar.value = null
        lastBybitKlineMs.set(0L)
        _market.value = _market.value.copy(interval = interval)
        connectWs()
        engine.changeInterval()
        reloadKlines()
    }

    private fun connectWs() {
        ws?.close()
        val sym = _market.value.symbol
        val iv = _market.value.interval
        ws = BybitWs(sym, object : BybitWs.Listener {
            override fun onTicker(t: BybitWs.Ticker) {
                if (t.last.isFinite() && t.last > 0) lastBybitTickerMs.set(System.currentTimeMillis())
                _market.value = _market.value.copy(
                    price = t.last.takeIf { it.isFinite() && it > 0 } ?: _market.value.price,
                    change24hPct = t.price24hPcnt.takeIf { it.isFinite() && it != 0.0 } ?: _market.value.change24hPct,
                    volume24h = t.volume24h.takeIf { it.isFinite() && it > 0 } ?: _market.value.volume24h,
                    turnover24h = t.turnover24h.takeIf { it.isFinite() && it > 0 } ?: _market.value.turnover24h,
                    fundingPct = t.fundingRate.times(100).takeIf { it.isFinite() && it != 0.0 } ?: _market.value.fundingPct,
                    openInterest = t.openInterest.takeIf { it.isFinite() && it > 0 } ?: _market.value.openInterest,
                    feedSource = "Bybit"
                )
            }

            override fun onTrade(tr: BybitWs.Trade) {
                lastBybitTradeMs.set(System.currentTimeMillis())
                tape.add(tr)
                orderFlow.streamHub.recordTrade(
                    symbol = sym,
                    price = tr.price,
                    qty = tr.size,
                    isAggressiveBuy = tr.side.equals("Buy", ignoreCase = true),
                    exchangeTsMs = tr.tsMs,
                    lagMs = tr.lagMs,
                    venue = "Bybit"
                )
                _market.value = _market.value.copy(tape = tape.stats())
            }

            override fun onBook(bids: List<BybitWs.BookLevel>, asks: List<BybitWs.BookLevel>) {
                lastBybitBookMs.set(System.currentTimeMillis())
                orderFlow.streamHub.recordBookSnapshot(
                    symbol = sym,
                    bids = bids.map { it.price to it.qty },
                    asks = asks.map { it.price to it.qty },
                    synced = true,
                    tsMs = System.currentTimeMillis(),
                    venue = "Bybit"
                )
                _market.value = _market.value.copy(bids = bids.take(12), asks = asks.take(12))
            }

            override fun onBookDeltas(deltas: List<BookState.LevelDelta>, synced: Boolean, lagMs: Long) {
                orderFlow.streamHub.recordDomDeltas(sym, deltas, synced, lagMs, "Bybit")
            }

            override fun onLiquidation(l: BybitWs.Liquidation) {
                liq.add(l)
                val s = liq.stats(_market.value.symbol, System.currentTimeMillis())
                _market.value = _market.value.copy(
                    liq1hUsd = s.optDouble("sum_usd_1h").takeIf { it.isFinite() },
                    liq24hUsd = s.optDouble("sum_usd_24h").takeIf { it.isFinite() }
                )
            }

            override fun onKline(bar: BybitWs.Kline) {
                lastBybitKlineMs.set(System.currentTimeMillis())
                applyLiveBar(bar)
            }

            override fun onStatus(connected: Boolean, note: String) {
                val cur = _market.value
                val resolvedNote = if (connected && !isBybitFresh(lastBybitTickerMs.get(), 10_000L) &&
                    (engine.binanceConnected || engine.okxConnected)
                ) {
                    "Bybit: $note · fallback Binance/OKX активен"
                } else {
                    note
                }
                _market.value = cur.copy(wsConnected = connected, wsNote = resolvedNote)
            }

            override fun onBookDesync(reason: String) {
                if (!engine.binanceConnected) {
                    orderFlow.streamHub.recordDesync(sym, reason, "Bybit")
                    _market.value = _market.value.copy(
                        bids = emptyList(), asks = emptyList(),
                        wsNote = "стакан: $reason"
                    )
                }
            }
        }, klineInterval = iv, clockOffsetMsProvider = { engine.clockSync.status().offsetMs }).also { it.connect() }
    }

    private fun applyLiveBar(bar: BybitWs.Kline) {
        val row = listOf(bar.tsMs.toDouble(), bar.open, bar.high, bar.low, bar.close, bar.volume)
        _lastBar.value = row
        if (bar.confirm) {
            val cur = _klines.value
            val lastTs = cur.lastOrNull()?.firstOrNull() ?: 0.0
            _klines.value = when {
                cur.isEmpty() || bar.tsMs.toDouble() > lastTs -> (cur + listOf(row)).takeLast(400)
                else -> cur.mapIndexed { i, r ->
                    if (i == cur.size - 1 && r.firstOrNull() == bar.tsMs.toDouble()) row else r
                }
            }
        }
    }

    // ---------------- Fallback-потоки от BinanceWs / OkxWs ----------------
    fun onFallbackStatus() {
        fallbackTracker.onOpen()
        val cur = _market.value
        if (!isBybitFresh(lastBybitTickerMs.get(), 6_000L)) {
            val src = when {
                engine.binanceConnected && engine.okxConnected -> "Binance + OKX"
                engine.binanceConnected -> "Binance"
                engine.okxConnected -> "OKX"
                else -> cur.feedSource
            }
            if (src.isNotBlank() && cur.feedSource != "Bybit") {
                _market.value = cur.copy(feedSource = src)
            }
        }
    }

    fun onFallbackPrice(sym: String, price: Double, exchange: String, vol24hCcy: Double = 0.0) {
        if (sym != _market.value.symbol || !price.isFinite() || price <= 0) return
        fallbackTracker.onValidMessage(FeedStatus.Kind.TICKER)
        if (!isBybitFresh(lastBybitTickerMs.get())) {
            val cur = _market.value
            _market.value = cur.copy(
                price = price,
                volume24h = cur.volume24h ?: vol24hCcy.takeIf { it.isFinite() && it > 0 },
                feedSource = if (cur.feedSource == "Bybit") "Bybit" else exchange
            )
        }
    }

    fun onFallbackTicker24h(
        sym: String,
        price: Double,
        change24hPct: Double,
        volume24h: Double,
        quoteVolume24h: Double,
        exchange: String
    ) {
        if (sym != _market.value.symbol || !price.isFinite() || price <= 0) return
        fallbackTracker.onValidMessage(FeedStatus.Kind.TICKER)
        if (!isBybitFresh(lastBybitTickerMs.get())) {
            _market.value = _market.value.copy(
                price = price,
                change24hPct = change24hPct.takeIf { it.isFinite() } ?: _market.value.change24hPct,
                volume24h = volume24h.takeIf { it.isFinite() && it > 0 } ?: _market.value.volume24h,
                turnover24h = quoteVolume24h.takeIf { it.isFinite() && it > 0 } ?: _market.value.turnover24h,
                feedSource = if (engine.okxConnected) "Binance + OKX" else exchange
            )
        }
    }

    fun onFallbackFlow(sym: String, flow: BinanceWs.FlowStats) {
        if (sym != _market.value.symbol || flow.n == 0) return
        fallbackTracker.onValidMessage(FeedStatus.Kind.TRADES)
        if (!isBybitFresh(lastBybitTradeMs.get())) {
            _market.value = _market.value.copy(
                tape = TapeAggregator.TapeStats(
                    n = flow.n,
                    buyQty = flow.buyQty,
                    sellQty = flow.sellQty,
                    cvd = flow.cvd,
                    deltaPct = flow.deltaPct,
                    priceChangePct = flow.priceChangePct,
                    divergence = flow.divergence
                )
            )
        }
    }

    fun onFallbackOkxFlow(sym: String, flow: OkxWs.FlowStats) {
        if (sym != _market.value.symbol || flow.n == 0) return
        if (!isBybitFresh(lastBybitTradeMs.get()) && !engine.binanceConnected) {
            fallbackTracker.onValidMessage(FeedStatus.Kind.TRADES)
            _market.value = _market.value.copy(
                tape = TapeAggregator.TapeStats(
                    n = flow.n,
                    buyQty = flow.buyQty,
                    sellQty = flow.sellQty,
                    cvd = flow.cvd,
                    deltaPct = flow.deltaPct,
                    priceChangePct = 0.0,
                    divergence = false
                )
            )
        }
    }

    fun onFallbackDepth(
        sym: String,
        bids: List<Pair<Double, Double>>,
        asks: List<Pair<Double, Double>>
    ) {
        if (sym != _market.value.symbol) return
        fallbackTracker.onValidMessage(FeedStatus.Kind.BOOK)
        if (!isBybitFresh(lastBybitBookMs.get())) {
            _market.value = _market.value.copy(
                bids = bids.take(12).map { BybitWs.BookLevel(it.first, it.second) },
                asks = asks.take(12).map { BybitWs.BookLevel(it.first, it.second) }
            )
        }
    }

    fun onFallbackKline(sym: String, iv: String, bar: BybitWs.Kline) {
        if (sym != _market.value.symbol || iv != _market.value.interval) return
        fallbackTracker.onValidMessage(FeedStatus.Kind.KLINE)
        if (!isBybitFresh(lastBybitKlineMs.get())) {
            applyLiveBar(bar)
        }
    }

    fun reloadKlines() {
        val sym = _market.value.symbol
        val iv = _market.value.interval
        rest.klinesWithFallback(sym, iv, 300) { res ->
            if (sym != _market.value.symbol || iv != _market.value.interval) return@klinesWithFallback
            if (res.rows.isEmpty()) {
                appScope.launch { toast("свечи недоступны ($sym): ${res.error ?: "нет связи"}") }
                return@klinesWithFallback
            }
            _klines.value = res.rows
            engine.seedChartKlines(sym, iv, res.rows)
            val srcName = when (res.source) {
                "bybit", "bytick" -> "Bybit"
                "binance" -> "Binance"
                "okx" -> "OKX"
                else -> res.source
            }
            val lastClose = res.rows.lastOrNull()?.getOrNull(4)
            _market.value = _market.value.copy(
                price = _market.value.price ?: lastClose,
                feedSource = if (_market.value.feedSource.startsWith("Подкл")) srcName else _market.value.feedSource
            )
        }
    }

    /** Обновление метрик: LSR, OI, funding, 24h тикер + F&G с резервом на Binance/OKX. */
    fun refreshMarketStats() {
        val sym = _market.value.symbol
        rest.accountRatio(sym, 168) { arr ->
            if (arr != null && arr.length() > 0 && sym == _market.value.symbol) {
                try {
                    val ratios = (0 until arr.length()).mapNotNull {
                        val r = arr.getJSONObject(it)
                        val buy = r.optDouble("buyRatio")
                        val sell = r.optDouble("sellRatio")
                        if (buy.isFinite() && sell.isFinite()) buy / sell.coerceAtLeast(1e-9) else null
                    }
                    val cur = ratios.firstOrNull()
                    if (cur != null && cur.isFinite() && cur > 0) {
                        val pct = 100.0 * ratios.count { it <= cur } / ratios.size
                        _market.value = _market.value.copy(lsr = cur, lsrPct = pct.takeIf { it.isFinite() })
                    }
                } catch (_: Exception) {
                }
            }
        }
        rest.openInterest(sym, 25) { arr ->
            if (arr != null && arr.length() >= 2 && sym == _market.value.symbol) {
                try {
                    val first = arr.getJSONObject(arr.length() - 1).optDouble("openInterest")
                    val last = arr.getJSONObject(0).optDouble("openInterest")
                    if (first.isFinite() && last.isFinite() && first > 0) {
                        val ch = 100.0 * (last - first) / first
                        if (ch.isFinite()) _market.value = _market.value.copy(oiChange24hPct = ch)
                    }
                } catch (_: Exception) {
                }
            }
        }
        // Fallback stats (Binance 24h + OKX funding/OI/LSR), чтобы данные не пустовали при геоблоке Bybit
        appScope.launch(Dispatchers.IO) {
            val fb = rest.fetchFallbackStatsSync(sym)
            if (sym == _market.value.symbol) {
                val cur = _market.value
                _market.value = cur.copy(
                    price = cur.price ?: fb.price?.takeIf { it.isFinite() && it > 0 },
                    change24hPct = cur.change24hPct ?: fb.change24hPct?.takeIf { it.isFinite() },
                    volume24h = cur.volume24h ?: fb.volume24h?.takeIf { it.isFinite() && it > 0 },
                    turnover24h = cur.turnover24h ?: fb.turnover24h?.takeIf { it.isFinite() && it > 0 },
                    fundingPct = cur.fundingPct ?: fb.fundingPct?.takeIf { it.isFinite() },
                    openInterest = cur.openInterest ?: fb.openInterest?.takeIf { it.isFinite() && it > 0 },
                    lsr = cur.lsr ?: fb.lsr?.takeIf { it.isFinite() && it > 0 },
                    lsrPct = cur.lsrPct ?: fb.lsrPct?.takeIf { it.isFinite() }
                )
            }
        }
        appScope.launch { fetchFng() }
    }

    private suspend fun fetchFng() = withContext(Dispatchers.IO) {
        try {
            val http = NetworkHub.restClient(connectTimeoutSec = 8L, readTimeoutSec = 10L)
            val req = Request.Builder().url("https://api.alternative.me/fng/?limit=7").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext
                val root = JSONObject(resp.body?.string() ?: return@withContext)
                val data = root.optJSONArray("data") ?: return@withContext
                val cur = data.getJSONObject(0)
                val d3 = if (data.length() > 2) cur.optInt("value") - data.getJSONObject(2).optInt("value") else 0
                _market.value = _market.value.copy(
                    fng = cur.optInt("value"),
                    fngDelta3 = d3,
                    fngClassification = cur.optString("value_classification")
                )
            }
        } catch (e: Exception) {
            Log.w("AppState", "F&G: ${e.message}")
        }
    }

    private val lastNewsFetchMs = AtomicLong(0L)
    private val newsMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun ensureFreshNews(
        maxAgeMs: Long = 5 * 60_000L,
        force: Boolean = false
    ): JSONArray = newsMutex.withLock {
        val now = System.currentTimeMillis()
        val cur = _market.value.news
        if (!force && cur.length() > 0 && (now - lastNewsFetchMs.get()) < maxAgeMs) {
            return@withLock cur
        }
        return@withLock refreshNewsLocked()
    }

    private suspend fun refreshNews() {
        ensureFreshNews(maxAgeMs = 0L, force = true)
    }

    private suspend fun refreshNewsLocked(): JSONArray {
        val prev = _market.value.news
        _market.value = _market.value.copy(newsStatus = "обновление…")
        val repo = NewsRepo(settings.newsApiKey)
        val news = repo.fetch(15)
        val now = System.currentTimeMillis()
        val timeLabel = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(now))
        if (news.length() == 0 && prev.length() > 0) {
            _market.value = _market.value.copy(
                news = prev,
                newsStatus = "${prev.length()} новостей (кэш)"
            )
            return prev
        }
        lastNewsFetchMs.set(now)
        _market.value = _market.value.copy(
            news = news,
            newsStatus = "${news.length()} новостей · $timeLabel"
        )
        appScope.launch {
            try { engine.refreshIntelligence(forceRadar = false) } catch (_: Exception) {}
        }
        try {
            val items = ArrayList<NewsItemEntity>()
            for (i in 0 until news.length()) {
                val n = news.getJSONObject(i)
                items.add(
                    NewsItemEntity(
                        title = n.optString("title"),
                        source = n.optString("source"),
                        url = n.optString("url"),
                        publishedTs = n.optLong("published_ts"),
                        fetchedTs = now,
                        importance = n.optString("importance"),
                        impact = n.optDouble("impact"),
                        assets = n.optJSONArray("assets")?.toString() ?: "[]"
                    )
                )
            }
            db.newsDao().insertAll(items)
            db.newsDao().prune(now - 3 * 24 * 3600_000L)
        } catch (e: Exception) {
            Log.w("AppState", "news cache: ${e.message}")
        }
        return news
    }

    // ================================================================
    // ГЛУБОКИЙ АНАЛИЗ: полный прогон цепочки A -> Б
    // ================================================================
    fun runAnalysis(onDone: (() -> Unit)? = null) {
        if (_chainRunning.value) { toast("анализ уже выполняется"); return }
        _chainRunning.value = true
        _chainNodes.value = emptyList()
        val symbol = _market.value.symbol
        val interval = _market.value.interval
        appScope.launch {
            try {
                if (!PythonBridge.isReady()) {
                    val r = withContext(Dispatchers.IO) { PythonBridge.init(ctx) }
                    if (!r.optBoolean("ok")) {
                        _chainRunning.value = false
                        toast("python: ${r.optString("error")}")
                        return@launch
                    }
                }
                val newsArr = ensureFreshNews(maxAgeMs = 5 * 60_000L, force = false)
                val liqStats = liq.stats(symbol, System.currentTimeMillis())
                val res = withContext(Dispatchers.IO) {
                    PythonBridge.runChain(
                        symbol, interval, newsArr.toString(), liqStats.toString(),
                        null, 45.0
                    ) { nodeJson ->
                        val n = JSONObject(nodeJson)
                        _chainNodes.value = _chainNodes.value + ChainNodeUi(
                            n.optString("name"), n.optString("status"),
                            n.optString("summary"), n.optString("reason")
                        )
                    }
                }
                if (!res.optBoolean("ok", true) && res.has("error")) {
                    _lastReport.value = "ошибка цепочки: ${res.optString("error")}"
                } else {
                    _lastResult.value = res
                    _market.value = _market.value.copy(
                        sources = res.optJSONObject("snap")?.optJSONObject("sources")
                    )
                    val report = withContext(Dispatchers.IO) {
                        PythonBridge.explain(res.toString())
                    }
                    _lastReport.value = report
                    saveAnalysis(res, report)
                    syncForecasts()
                    verifyOldForecasts()
                }
            } catch (e: Exception) {
                _lastReport.value = "сбой анализа: ${e.message}"
                Log.e("AppState", "runAnalysis", e)
            } finally {
                _chainRunning.value = false
                onDone?.invoke()
            }
        }
    }

    private suspend fun saveAnalysis(res: JSONObject, report: String) {
        try {
            db.analysisDao().insert(
                AnalysisEntity(
                    ts = System.currentTimeMillis(),
                    symbol = res.optString("symbol"),
                    interval = res.optString("interval"),
                    regime = res.optJSONObject("regime")?.optString("kind"),
                    headline = res.optJSONObject("output")?.optString("headline") ?: "",
                    report = report,
                    resultJson = res.toString()
                )
            )
        } catch (e: Exception) {
            Log.w("AppState", "saveAnalysis: ${e.message}")
        }
    }

    /** Синхронизация памяти прогнозов (Python forecasts.csv -> Room). */
    fun syncForecasts() {
        appScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { PythonBridge.forecastRecords() }
                val rows = r.optJSONArray("rows") ?: return@launch
                val out = ArrayList<ForecastEntity>()
                for (i in 0 until rows.length()) {
                    val f = rows.getJSONObject(i)
                    out.add(
                        ForecastEntity(
                            fid = f.optString("id"),
                            ts = f.optString("ts"),
                            symbol = f.optString("pair"),
                            interval = f.optString("interval"),
                            regime = f.optString("regime"),
                            side = f.optString("side"),
                            confidence = f.optDouble("confidence", 0.0),
                            p = if (f.optString("p").isNotBlank()) f.optDouble("p") else null,
                            horizonH = f.optDouble("horizon_h", 24.0),
                            entryPrice = if (f.optString("entry_price").isNotBlank()) f.optDouble("entry_price") else null,
                            councilStatus = f.optString("council_status"),
                            gateStatus = f.optString("gate_status"),
                            dataGrade = f.optString("data_grade"),
                            verifyTs = f.optString("verify_ts").ifBlank { null },
                            exitPrice = if (f.optString("exit_price").isNotBlank()) f.optDouble("exit_price") else null,
                            movePct = if (f.optString("move_pct").isNotBlank()) f.optDouble("move_pct") else null,
                            hit = when (f.optString("hit")) {
                                "1" -> 1; "0" -> 0; else -> null
                            },
                            note = f.optString("note")
                        )
                    )
                }
                db.forecastDao().upsertAll(out)
            } catch (e: Exception) {
                Log.w("AppState", "syncForecasts: ${e.message}")
            }
        }
    }

    /** Проверка старых прогнозов по фактическому рынку (горизонты истекли). */
    fun verifyOldForecasts() {
        appScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { PythonBridge.verifyForecasts() }
                if (r.optBoolean("ok") && r.optInt("checked", 0) > 0) {
                    toast("проверено прогнозов: ${r.optInt("checked")}")
                }
                syncForecasts()
            } catch (e: Exception) {
                Log.w("AppState", "verify: ${e.message}")
            }
        }
    }

    fun forecastStatsNow(onDone: (JSONObject) -> Unit) {
        appScope.launch {
            val r = withContext(Dispatchers.IO) { PythonBridge.forecastStats() }
            onDone(r)
        }
    }

    // ================================================================
    // Проверка всех API-связей движка (кнопка «ПРОВЕРИТЬ ВСЕ API»)
    // ================================================================
    fun checkAllApis(onDone: (JSONArray) -> Unit) {
        appScope.launch {
            val ordered = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, JSONObject>>())
            suspend fun addResult(order: Int, name: String, status: String, detail: String) {
                val obj = JSONObject()
                    .put("name", name)
                    .put("ok", status == "OK")
                    .put("status", status)
                    .put("detail", detail)
                ordered.add(order to obj)
                db.apiCheckDao().insert(
                    ApiCheckEntity(
                        ts = System.currentTimeMillis(), name = name,
                        status = status, detail = detail
                    )
                )
            }

            suspend fun httpCheck(url: String, timeoutSec: Long = 6): Pair<String, String> =
                withContext(Dispatchers.IO) {
                    val t0 = System.currentTimeMillis()
                    try {
                        val http = NetworkHub.restClient(
                            connectTimeoutSec = timeoutSec,
                            readTimeoutSec = timeoutSec
                        )
                        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                            val ms = System.currentTimeMillis() - t0
                            when {
                                r.code == 429 -> "RATE_LIMIT" to "HTTP 429 (лимит запросов)"
                                r.isSuccessful && ms > 4000 -> "SLOW" to "HTTP ${r.code}, ${ms}мс"
                                r.isSuccessful -> "OK" to "HTTP ${r.code}, ${ms}мс"
                                else -> "UNAVAILABLE" to "HTTP ${r.code}"
                            }
                        }
                    } catch (e: Exception) {
                        "UNAVAILABLE" to (e.message ?: "нет соединения").take(80)
                    }
                }

            val jobs = mutableListOf<kotlinx.coroutines.Job>()
            fun checkAsync(block: suspend () -> Unit) {
                jobs += appScope.launch { block() }
            }

            // 1. Bybit REST V5 (с диагностикой fallback на Binance/OKX)
            checkAsync {
                withContext(Dispatchers.IO) {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var ok = false
                    var detail = "нет ответа"
                    rest.ping { success, d ->
                        ok = success
                        detail = d
                        latch.countDown()
                    }
                    latch.await(9, TimeUnit.SECONDS)
                    val status = if (ok) "OK" else "UNAVAILABLE"
                    val uiDetail = if (ok) detail else "$detail · активен fallback Binance/OKX"
                    addResult(0, "Bybit REST V5 (свечи/тикеры/OI)", status, uiDetail)
                }
            }
            // 2. Bybit WS — честная проверка живых сообщений
            checkAsync {
                val topicOverall = ws?.feedTracker?.overall()
                val live = topicOverall?.status == FeedStatus.LIVE || isBybitFresh(lastBybitTickerMs.get(), 15_000L)
                val st = when {
                    live -> "OK"
                    _market.value.wsConnected -> "UNAVAILABLE"
                    else -> "UNAVAILABLE"
                }
                val note = if (live) {
                    "поток активен (${topicOverall?.events ?: 0} соб.)"
                } else {
                    "подключён, тишина Bybit · данные идут через Binance/OKX"
                }
                addResult(1, "Bybit WebSocket (поток V5)", st, note)
            }
            // 3. Binance WebSocket (стрим ленты/свечей/стакана)
            checkAsync {
                val st = if (engine.binanceConnected) "OK" else "SLOW"
                addResult(2, "Binance WebSocket (лента/свечи/стакан)", st, engine.binanceNote)
            }
            // 4. OKX WebSocket
            checkAsync {
                val st = if (engine.okxConnected) "OK" else "SLOW"
                addResult(3, "OKX WebSocket (лента/тикер)", st, engine.okxNote)
            }
            // 5. Binance public REST
            checkAsync {
                val r = httpCheck("https://data-api.binance.vision/api/v3/ping")
                addResult(4, "Binance data-api (свечи, киты >= $100K)", r.first, r.second)
            }
            // 6. OKX public REST
            checkAsync {
                val r = httpCheck("https://www.okx.com/api/v5/public/time")
                addResult(5, "OKX public (свечи/funding/OI/LSR)", r.first, r.second)
            }
            // 7. Deribit
            checkAsync {
                val r = httpCheck("https://www.deribit.com/api/v2/public/test")
                addResult(6, "Deribit (опционы BTC/ETH)", r.first, r.second)
            }
            // 8. CoinGecko
            checkAsync {
                val r = httpCheck("https://api.coingecko.com/api/v3/ping")
                addResult(7, "CoinGecko (доминирование BTC, объём)", r.first, r.second)
            }
            // 9. DefiLlama
            checkAsync {
                val r = httpCheck("https://api.llama.fi/v2/chains", 8)
                addResult(8, "DefiLlama (TVL DeFi-цепей)", r.first, r.second)
            }
            // 10. Ончейн BTC
            checkAsync {
                var r = httpCheck("https://blockstream.info/api/blocks/tip/height", 6)
                if (r.first != "OK") {
                    val r2 = httpCheck("https://mempool.space/api/blocks/tip/height", 6)
                    if (r2.first == "OK") r = "OK" to "${r.second} → mempool: ${r2.second}"
                }
                addResult(9, "Ончейн BTC (blockstream/mempool)", r.first, r.second)
            }
            // 11. Fear & Greed
            checkAsync {
                val r = httpCheck("https://api.alternative.me/fng/?limit=1", 8)
                addResult(10, "Fear & Greed (alternative.me)", r.first, r.second)
            }
            // 12. Новости RSS
            checkAsync {
                withContext(Dispatchers.IO) {
                    try {
                        val repo = NewsRepo(settings.newsApiKey)
                        val n = repo.fetch(5)
                        val st = if (n.length() > 0) "OK" else "UNAVAILABLE"
                        addResult(11, "Новости (RSS)", st, "получено ${n.length()} заголовков")
                    } catch (e: Exception) {
                        addResult(11, "Новости (RSS)", "UNAVAILABLE", (e.message ?: "ошибка").take(80))
                    }
                }
            }

            jobs.joinAll()
            val results = JSONArray()
            ordered.sortedBy { it.first }.forEach { results.put(it.second) }
            withContext(Dispatchers.Main) { onDone(results) }
        }
    }

    // ================================================================
    // Экспорт / импорт
    // ================================================================
    fun exportZip(onDone: (JSONObject) -> Unit) {
        appScope.launch {
            val r = withContext(Dispatchers.IO) {
                ZipManager.export(ctx, com.example.BuildConfig.VERSION_NAME)
            }
            withContext(Dispatchers.Main) { onDone(r) }
        }
    }

    fun importZip(file: java.io.File, onDone: (JSONObject) -> Unit) {
        appScope.launch {
            val r = withContext(Dispatchers.IO) { ZipManager.import(ctx, file) }
            withContext(Dispatchers.Main) {
                syncForecasts()
                onDone(r)
            }
        }
    }
}
