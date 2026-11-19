package com.enamind.ai.bot

import android.util.Log
import com.enamind.ai.bot.strategy.MarketContextSnapshot
import com.enamind.ai.bot.strategy.OpenPositionContext
import com.enamind.ai.bot.strategy.OrderExecutionPreference
import com.enamind.ai.bot.strategy.StrategyDecision
import com.enamind.ai.core.ActiveInstrument
import com.enamind.ai.core.EnacoreSnapshot
import com.enamind.ai.bybit.ContractCatalogRepo
import com.enamind.ai.core.AppState
import com.enamind.ai.intelligence.ExchangeClockSync
import com.enamind.ai.orderflow.AbsorptionSide
import com.enamind.ai.orderflow.DataFreshness
import com.enamind.ai.orderflow.GlowColorState
import com.enamind.ai.orderflow.LevelAttackPhase
import com.enamind.ai.orderflow.MarketDominance
import com.enamind.ai.orderflow.OrderFlowReport
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Изолированный движок торгового бота ENA (Paper + Bybit Demo / Testnet / Mainnet).
 *
 * Архитектура Слоёв 2–5:
 *  - Честные комиссии через Bybit /v5/account/fee-rate (и разделение Crypto 0.036%/0.10% vs TradFi Металлы 0%/0.0275%);
 *  - Контроль эффективного капитала min(capital, availBalance * 0.95) и minNotionalValue/minOrderQty;
 *  - Горячее переключение стратегий StrategyBridge без сброса WS/стакана/памяти;
 *  - Движок продолжения от незакрытой сделки (1..4 транша, blendedAvgEntry, кумулятивные комиссии,
 *    Anti-Knife Gate, опциональный 50% PeelPartial, СТРОГИЙ ЗАПРЕТ встречных Dual-Pocket замков);
 *  - PostOnly Maker по L1 с re-peg (до 2 попыток) и настраиваемым фоллбэком;
 *  - Адаптивный трейлинг max(userTrail, spread*2.5) + опциональный SL (ROE% или × ATR) во всех режимах.
 */
class TradeBotEngine(
    private val vaultStore: BotVaultStore,
    private val contractCatalog: ContractCatalogRepo,
    private val reportProvider: () -> OrderFlowReport,
    private val marketProvider: () -> AppState.MarketUi,
    private val quantAdxErProvider: () -> Pair<Double?, Double?>,
    private val anchorsProvider: (String) -> MarketAnchorsSnapshot? = { null },
    private val clockSync: ExchangeClockSync = ExchangeClockSync(),
    private val onServiceStateChanged: (shouldRunService: Boolean) -> Unit = {},
    private val onNotify: (BotStatusNotification) -> Unit = {},
    private val coreProvider: () -> EnacoreSnapshot = { EnacoreSnapshot() },
    private val instrumentProvider: () -> ActiveInstrument = { ActiveInstrument() },
    private val onCorePosition: (String, String, Boolean, Double) -> Unit = { _, _, _, _ -> }
) {
    data class NetPnlResult(
        val grossPnlUsdt: Double,
        val estFeeUsdt: Double,
        val netPnlUsdt: Double,
        val netPnlPct: Double
    )

    data class EntryDecision(
        val shouldEnter: Boolean,
        val side: TradeSide? = null,
        val reason: String = ""
    )

    data class ExitDecision(
        val shouldClose: Boolean,
        val reason: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(2))
    private val tradeMutex = Mutex()
    private val client = BybitV5TradingClient(clockSync)

    private val initialConfig = vaultStore.loadConfig()
    private val initialStats = vaultStore.loadStatsByMode()
    private val initialTrades = vaultStore.loadTrades(50)
    private val initialFeeProfile = BybitV5TradingClient.defaultFeeProfile(initialConfig.symbol)

    private val _uiState = MutableStateFlow(
        BotUiState(
            config = initialConfig,
            walletBalanceUsdt = if (initialConfig.mode == BotMode.PAPER) initialConfig.paperStartBalanceUsdt else null,
            feeRateSidePct = initialFeeProfile.effectiveSidePct(initialConfig.executionPreference == OrderExecutionPreference.MAKER_POST_ONLY),
            makerFeePct = initialFeeProfile.makerFeePct,
            takerFeePct = initialFeeProfile.takerFeePct,
            isTradFiFeeProfile = initialFeeProfile.isTradFiMetal,
            effectiveCapitalUsdt = initialConfig.capitalUsdt,
            trailingNoiseWarning = computeTrailingNoiseWarning(initialConfig),
            statsByMode = initialStats,
            recentTrades = initialTrades,
            chartOverlay = buildChartOverlay(initialConfig.symbol, null, initialTrades)
        )
    )
    val uiState: StateFlow<BotUiState> = _uiState

    private var startupJob: Job? = null
    private var loopJob: Job? = null
    private var privateWs: BybitV5TradingClient.PrivateWsHandle? = null

    private var pendingSignalSide: TradeSide? = null
    private var pendingSignalSinceMs: Long = 0L
    private var lastServerSlSyncMs: Long = 0L
    private var lastExchangeSyncMs: Long = 0L

    fun loadCredentialSlot(mode: BotMode): BybitCredentialSlot =
        vaultStore.loadCredentialSlot(mode)

    fun saveCredentialSlot(mode: BotMode, slot: BybitCredentialSlot) {
        vaultStore.saveCredentialSlot(mode, slot)
        _uiState.value = _uiState.value.copy(
            lastError = null,
            lastCheckNote = "Ключи для ${mode.labelRu} сохранены (${slot.maskedKey()})"
        )
    }

    fun clearCredentialSlot(mode: BotMode) {
        vaultStore.clearCredentialSlot(mode)
        _uiState.value = _uiState.value.copy(
            exchangeConnected = false,
            lastCheckNote = "Ключи для ${mode.labelRu} удалены"
        )
    }

    fun updateConfig(transform: (BotConfig) -> BotConfig) {
        val prev = _uiState.value.config
        val raw = transform(prev)
        if ((_uiState.value.running || _uiState.value.activePosition != null) &&
            (raw.symbol != prev.symbol || raw.instrumentCategory != prev.instrumentCategory || raw.mode != prev.mode || raw.enacoreBridge != prev.enacoreBridge)) {
            _uiState.value = _uiState.value.copy(lastError = "Остановите бота и завершите позицию перед сменой режима")
            return
        }
        val cleanSymbol = raw.symbol.trim().uppercase().ifBlank { "BTCUSDT" }
        val contract = contractCatalog.findContract(cleanSymbol, raw.instrumentCategory)
        val maxLev = if (raw.instrumentCategory == "spot") 1 else (contract?.maxLeverage?.toInt() ?: 1).coerceIn(1, 125)
        val maxTr = raw.maxTranches.coerceIn(1, 4)
        val cleanWeights = if (raw.trancheWeightsPct.size == maxTr && raw.trancheWeightsPct.all { it > 0 }) {
            raw.trancheWeightsPct
        } else {
            BotConfig.defaultWeightsFor(maxTr)
        }
        val clean = raw.copy(
            symbol = cleanSymbol,
            capitalUsdt = raw.capitalUsdt.coerceIn(5.0, 100_000.0),
            leverage = raw.leverage.coerceIn(1, maxLev),
            minNetProfitPct = raw.minNetProfitPct.coerceIn(0.15, 10.0),
            maxProfitPct = maxOf(raw.maxProfitPct, raw.minNetProfitPct + 0.2).coerceIn(0.35, 50.0),
            trailingGivebackPct = raw.trailingGivebackPct.coerceIn(0.05, 5.0),
            maxHoldWaitMin = raw.maxHoldWaitMin.coerceIn(1, 720),
            stopLossPct = raw.stopLossPct.coerceIn(0.2, 40.0),
            stopLossAtrMult = raw.stopLossAtrMult.coerceIn(0.5, 10.0),
            dailyMaxLossPct = raw.dailyMaxLossPct.coerceIn(0.5, 50.0),
            confirmSignalSec = raw.confirmSignalSec.coerceIn(1, 30),
            maxTranches = maxTr,
            trancheWeightsPct = cleanWeights,
            scaleStepPct = raw.scaleStepPct.coerceIn(0.08, 5.0)
        )
        vaultStore.saveConfig(clean)
        val modeChanged = prev.mode != clean.mode
        val symChanged = !prev.symbol.equals(clean.symbol, ignoreCase = true)
        val bal = when {
            clean.mode == BotMode.PAPER -> computePaperBalance(clean.paperStartBalanceUsdt, _uiState.value.statsByMode[BotMode.PAPER])
            modeChanged -> null
            else -> _uiState.value.walletBalanceUsdt
        }
        val feeProfile = if (!clean.mode.isLiveExchange || modeChanged || symChanged) {
            if (clean.instrumentCategory == "spot") BybitV5TradingClient.FeeProfile(.1, .1, false) else BybitV5TradingClient.defaultFeeProfile(clean.symbol)
        } else {
            BybitV5TradingClient.FeeProfile(
                makerFeePct = _uiState.value.makerFeePct,
                takerFeePct = _uiState.value.takerFeePct,
                isTradFiMetal = _uiState.value.isTradFiFeeProfile
            )
        }
        val effCap = computeEffectiveCapital(clean.capitalUsdt, bal)
        val sideFee = feeProfile.effectiveSidePct(clean.executionPreference == OrderExecutionPreference.MAKER_POST_ONLY)
        val minNotionalWarn = validateTrancheMinimum(clean, effCap, marketProvider().price ?: reportProvider().price, contract)

        _uiState.value = _uiState.value.copy(
            config = clean,
            walletBalanceUsdt = bal,
            feeRateSidePct = sideFee,
            makerFeePct = feeProfile.makerFeePct,
            takerFeePct = feeProfile.takerFeePct,
            isTradFiFeeProfile = feeProfile.isTradFiMetal,
            effectiveCapitalUsdt = effCap,
            minNotionalWarning = minNotionalWarn,
            trailingNoiseWarning = computeTrailingNoiseWarning(clean),
            exchangeConnected = if (modeChanged) false else _uiState.value.exchangeConnected,
            isUtaAccount = if (modeChanged) null else _uiState.value.isUtaAccount,
            lastError = null,
            chartOverlay = buildChartOverlay(clean.symbol, _uiState.value.activePosition, _uiState.value.recentTrades)
        )
    }

    fun probeSelectedAccount() {
        val cfg = _uiState.value.config
        if (!cfg.mode.isLiveExchange) {
            val paperBal = computePaperBalance(cfg.paperStartBalanceUsdt, _uiState.value.statsByMode[BotMode.PAPER])
            val feeProfile = if (cfg.instrumentCategory == "spot") BybitV5TradingClient.FeeProfile(.1, .1, false) else BybitV5TradingClient.defaultFeeProfile(cfg.symbol)
            val effCap = computeEffectiveCapital(cfg.capitalUsdt, paperBal)
            val sideFee = feeProfile.effectiveSidePct(cfg.executionPreference == OrderExecutionPreference.MAKER_POST_ONLY)
            val contract = contractCatalog.findContract(cfg.symbol)
            _uiState.value = _uiState.value.copy(
                walletBalanceUsdt = paperBal,
                feeRateSidePct = sideFee,
                makerFeePct = feeProfile.makerFeePct,
                takerFeePct = feeProfile.takerFeePct,
                isTradFiFeeProfile = feeProfile.isTradFiMetal,
                effectiveCapitalUsdt = effCap,
                minNotionalWarning = validateTrancheMinimum(cfg, effCap, marketProvider().price, contract),
                exchangeConnected = true,
                lastError = null,
                lastCheckNote = "Paper-баланс: %.2f USDT · Maker %.3f%% / Taker %.4f%%"
                    .format(Locale.US, paperBal, feeProfile.makerFeePct, feeProfile.takerFeePct)
            )
            return
        }
        val slot = vaultStore.loadCredentialSlot(cfg.mode)
        _uiState.value = _uiState.value.copy(lastCheckNote = "Проверка ключей ${cfg.mode.shortLabel}…", lastError = null)
        scope.launch(Dispatchers.IO) {
            val res = client.probeAccountAndBalance(cfg.mode, slot, cfg.symbol)
            if (res.ok && res.data != null) {
                val p = res.data
                val effCap = computeEffectiveCapital(cfg.capitalUsdt, p.walletUsdt)
                val sideFee = if (cfg.executionPreference == OrderExecutionPreference.MAKER_POST_ONLY) p.makerFeePct else p.takerFeePct
                val contract = contractCatalog.findContract(cfg.symbol)
                _uiState.value = _uiState.value.copy(
                    walletBalanceUsdt = p.walletUsdt,
                    feeRateSidePct = sideFee,
                    makerFeePct = p.makerFeePct,
                    takerFeePct = p.takerFeePct,
                    isTradFiFeeProfile = p.isTradFiMetal,
                    effectiveCapitalUsdt = effCap,
                    minNotionalWarning = validateTrancheMinimum(cfg, effCap, marketProvider().price, contract),
                    exchangeConnected = true,
                    isUtaAccount = p.isUta,
                    lastError = null,
                    lastCheckNote = "Связь OK: ${p.note} · Maker ${p.makerFeePct}% / Taker ${p.takerFeePct}%"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    exchangeConnected = false,
                    lastError = res.message,
                    lastCheckNote = "Ошибка: ${res.message}"
                )
            }
        }
    }

    fun startBot(mainnetExplicitConfirmed: Boolean = false) {
        val st = _uiState.value
        val cfg = st.config
        if (st.running || st.closing) return
        if (instrumentProvider().quoteCoin != "USDT") {
            _uiState.value = st.copy(lastError = "Бот ведёт баланс и PnL в USDT. Выбранная котируемая валюта доступна для анализа.")
            return
        }
        if (cfg.instrumentCategory == "inverse") {
            _uiState.value = st.copy(lastError = "Инверсные контракты доступны для анализа. Бот использует линейную модель расчёта объёма и PnL.")
            return
        }
        if (cfg.mode.isLiveExchange && (cfg.instrumentCategory != "linear" || cfg.instrumentVenue != "Bybit")) {
            _uiState.value = st.copy(lastError = "Исполнение на бирже доступно для Bybit Linear. Для выбранного рынка используйте Paper.")
            return
        }

        if (cfg.mode == BotMode.BYBIT_MAINNET && !mainnetExplicitConfirmed && !st.mainnetConfirmedSession) {
            _uiState.value = st.copy(lastError = "Требуется подтверждение запуска на реальном счёте Bybit Mainnet")
            return
        }
        if (cfg.mode.requiresKeys && !vaultStore.loadCredentialSlot(cfg.mode).hasKeys) {
            _uiState.value = st.copy(lastError = "Введите и сохраните API Key и Secret для ${cfg.mode.labelRu}")
            return
        }

        val stats = st.activeModeStats
        if (isDailyLossLimitReached(cfg, stats, st.walletBalanceUsdt)) {
            _uiState.value = st.copy(
                lastError = "Дневной лимит просадки (${cfg.dailyMaxLossPct}%) достигнут — торговля на сегодня остановлена"
            )
            return
        }

        pendingSignalSide = null
        pendingSignalSinceMs = 0L

        _uiState.value = st.copy(
            running = true,
            lastError = null,
            mainnetConfirmedSession = if (cfg.mode == BotMode.BYBIT_MAINNET) true else st.mainnetConfirmedSession,
            statusText = "Запуск (${cfg.mode.shortLabel}) · ${if (cfg.enacoreBridge) "ENΛCORE" else "Ручной режим"} · ${cfg.symbol}…",
            waitReasonBadge = "Подготовка ${cfg.symbol}…"
        )
        onServiceStateChanged(true)

        startupJob = scope.launch {
            tradeMutex.withLock {
            if (cfg.mode.isLiveExchange) {
                val slot = vaultStore.loadCredentialSlot(cfg.mode)
                val probe = withContext(Dispatchers.IO) {
                    client.probeAccountAndBalance(cfg.mode, slot, cfg.symbol)
                }
                if (!probe.ok || probe.data == null) {
                    _uiState.value = _uiState.value.copy(
                        running = false,
                        exchangeConnected = false,
                        lastError = probe.message,
                        statusText = "Ошибка запуска: ${probe.message}",
                        waitReasonBadge = "Ошибка подключения"
                    )
                    onServiceStateChanged(false)
                    return@launch
                }
                val prepMargin = withContext(Dispatchers.IO) {
                    client.ensureMarginAndLeverage(cfg.mode, slot, cfg.symbol, cfg.marginMode, cfg.leverage)
                }
                if (!prepMargin.ok) {
                    _uiState.value = _uiState.value.copy(
                        running = false,
                        lastError = prepMargin.message,
                        statusText = "Ошибка маржи/плеча: ${prepMargin.message}",
                        waitReasonBadge = "Ошибка маржи/плеча"
                    )
                    onServiceStateChanged(false)
                    return@launch
                }

                val p = probe.data
                val effCap = computeEffectiveCapital(cfg.capitalUsdt, p.walletUsdt)
                val sideFee = if (cfg.executionPreference == OrderExecutionPreference.MAKER_POST_ONLY) p.makerFeePct else p.takerFeePct
                val existingResult = withContext(Dispatchers.IO) {
                    client.fetchOpenPosition(cfg.mode, slot, cfg.symbol)
                }
                if (!existingResult.ok) {
                    _uiState.value = _uiState.value.copy(running = false, exchangeConnected = false,
                        lastError = "Не удалось проверить открытую позицию: ${existingResult.message}")
                    onServiceStateChanged(false)
                    return@launch
                }
                val existingPos = existingResult.data
                if (existingPos != null && existingPos.size > 0.0 && existingPos.side != null) {
                    val adopted = buildInitialPosition(
                        id = "adopted_${cfg.symbol}_${System.currentTimeMillis()}",
                        mode = cfg.mode,
                        symbol = cfg.symbol,
                        side = existingPos.side,
                        qty = existingPos.size,
                        entryPrice = existingPos.avgPrice,
                        markPrice = existingPos.markPrice,
                        leverage = existingPos.leverage,
                        marginMode = cfg.marginMode,
                        makerFeePct = p.makerFeePct,
                        takerFeePct = p.takerFeePct,
                        enteredAsMaker = cfg.executionPreference == OrderExecutionPreference.MAKER_POST_ONLY,
                        cfg = cfg,
                        openedAtMs = System.currentTimeMillis(),
                        reason = "Подхвачена открытая позиция биржи",
                        atr1mPct = anchorsProvider(cfg.symbol)?.atr1mPct
                    ).copy(
                        estLiquidationPrice = existingPos.liqPrice,
                        serverTrailingStopPrice = existingPos.stopLoss
                    )
                    _uiState.value = _uiState.value.copy(activePosition = adopted)
                }
                val contract = contractCatalog.findContract(cfg.symbol)
                _uiState.value = _uiState.value.copy(
                    walletBalanceUsdt = p.walletUsdt,
                    feeRateSidePct = sideFee,
                    makerFeePct = p.makerFeePct,
                    takerFeePct = p.takerFeePct,
                    isTradFiFeeProfile = p.isTradFiMetal,
                    effectiveCapitalUsdt = effCap,
                    minNotionalWarning = validateTrancheMinimum(cfg, effCap, marketProvider().price, contract),
                    trailingNoiseWarning = computeTrailingNoiseWarning(cfg),
                    exchangeConnected = true,
                    isUtaAccount = p.isUta,
                    statusText = "${cfg.mode.shortLabel} активен · ${if (cfg.enacoreBridge) "ENΛCORE" else "Ручной режим"} · ${cfg.symbol}",
                    waitReasonBadge = "Поиск сигнала (${if (cfg.enacoreBridge) "ENΛCORE" else "Ручной режим"})"
                )
                startPrivateWs(cfg.mode, slot)
            } else {
                val bal = computePaperBalance(cfg.paperStartBalanceUsdt, stats)
                val feeProfile = BybitV5TradingClient.defaultFeeProfile(cfg.symbol)
                val effCap = computeEffectiveCapital(cfg.capitalUsdt, bal)
                val sideFee = feeProfile.effectiveSidePct(cfg.executionPreference == OrderExecutionPreference.MAKER_POST_ONLY)
                val contract = contractCatalog.findContract(cfg.symbol)
                _uiState.value = _uiState.value.copy(
                    walletBalanceUsdt = bal,
                    feeRateSidePct = sideFee,
                    makerFeePct = feeProfile.makerFeePct,
                    takerFeePct = feeProfile.takerFeePct,
                    isTradFiFeeProfile = feeProfile.isTradFiMetal,
                    effectiveCapitalUsdt = effCap,
                    minNotionalWarning = validateTrancheMinimum(cfg, effCap, marketProvider().price, contract),
                    trailingNoiseWarning = computeTrailingNoiseWarning(cfg),
                    exchangeConnected = true,
                    statusText = "PAPER активен · ${if (cfg.enacoreBridge) "ENΛCORE" else "Ручной режим"} · ${cfg.symbol}",
                    waitReasonBadge = "Поиск сигнала (${if (cfg.enacoreBridge) "ENΛCORE" else "Ручной режим"})"
                )
            }

            kotlin.coroutines.coroutineContext.ensureActive()
            if (!_uiState.value.running) return@withLock
            loopJob?.cancel()
            loopJob = scope.launch {
                runBotLoop()
            }
            }
        }
    }

    fun stopBotOnly(reason: String = "Остановлен пользователем") {
        startupJob?.cancel()
        startupJob = null
        loopJob?.cancel()
        loopJob = null
        privateWs?.close()
        privateWs = null
        pendingSignalSide = null
        _uiState.value = _uiState.value.copy(
            running = false,
            closing = false,
            statusText = reason,
            waitReasonBadge = "Бот на паузе"
        )
        onServiceStateChanged(false)
    }

    fun stopAndCloseMarket(reason: String = "Аварийная остановка и закрытие по рынку") {
        startupJob?.cancel()
        startupJob = null
        _uiState.value = _uiState.value.copy(running = false, closing = true)
        loopJob?.cancel()
        loopJob = null
        pendingSignalSide = null
        scope.launch {
            tradeMutex.withLock {
                _uiState.value = _uiState.value.copy(
                    closing = true,
                    statusText = "Закрытие позиции по рынку…",
                    waitReasonBadge = "Аварийное закрытие"
                )
                val pos = _uiState.value.activePosition
                if (pos != null) {
                    val mark = marketProvider().price ?: pos.markPrice
                    closePositionInternal(pos, mark, reason, forceMarket = true)
                }
            }
            privateWs?.close()
            privateWs = null
            _uiState.value = _uiState.value.copy(
                running = false,
                closing = false,
                statusText = if (_uiState.value.activePosition == null) reason else "Бот остановлен; закрытие позиции ещё не подтверждено биржей",
                waitReasonBadge = if (_uiState.value.activePosition == null) "Остановлен" else "Проверьте позицию на бирже",
                chartOverlay = buildChartOverlay(_uiState.value.config.symbol, _uiState.value.activePosition, _uiState.value.recentTrades)
            )
            onServiceStateChanged(false)
        }
    }

    /** Уточнение #4: реакция на Android 15 Service.onTimeout для dataSync. */
    fun handleAndroid15ServiceTimeout() {
        val st = _uiState.value
        val hasPos = st.activePosition != null
        val cfg = st.config
        if (hasPos && cfg.timeoutBehavior == TimeoutBehavior.CLOSE_MARKET) {
            onNotify(
                BotStatusNotification(
                    title = "Сервис остановлен системой",
                    body = "Android 15 завершил фоновый цикл (6ч) — выполняется закрытие ${cfg.symbol} по рынку."
                )
            )
            stopAndCloseMarket("Тайм-аут Android 15 — закрыто по рынку")
        } else {
            if (hasPos) {
                onNotify(
                    BotStatusNotification(
                        title = "Сервис остановлен системой",
                        body = "Android 15 остановил сервис; позиция ${cfg.symbol} оставлена под защитой биржи."
                    )
                )
            }
            stopBotOnly("Сервис остановлен системой (Android 15)")
        }
    }

    private fun startPrivateWs(mode: BotMode, slot: BybitCredentialSlot) {
        privateWs?.close()
        privateWs = client.PrivateWsHandle(
            mode = mode,
            slot = slot,
            onOrderOrPositionEvent = { topic, payload ->
                if (topic == "position") {
                    handlePrivatePositionWs(payload)
                }
            },
            onState = { connected, note ->
                val cur = _uiState.value
                if (cur.running) {
                    _uiState.value = cur.copy(
                        networkHealthOk = connected,
                        lastCheckNote = note
                    )
                }
            }
        ).also { it.connect() }
    }

    private fun handlePrivatePositionWs(payload: JSONObject) {
        val arr = payload.optJSONArray("data") ?: return
        val cur = _uiState.value
        val pos = cur.activePosition ?: return
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            if (!d.optString("symbol").equals(pos.symbol, ignoreCase = true)) continue
            val size = d.optString("size").toDoubleOrNull() ?: continue
            if (size <= 0.0) {
                val mark = d.optString("markPrice").toDoubleOrNull()?.takeIf { it > 0.0 } ?: pos.markPrice
                scope.launch {
                    tradeMutex.withLock {
                        _uiState.value.activePosition?.let { active ->
                            finalizeTradeRecord(active, mark, "Закрыто на сервере Bybit (SL/ручн.)")
                        }
                    }
                }
            }
        }
    }

    private suspend fun runBotLoop() {
        while (scope.isActive && _uiState.value.running) {
            try {
                tradeMutex.withLock {
                    stepTickLocked()
                }
            } catch (e: Exception) {
                Log.w(TAG, "stepTick: ${e.message}")
            }
            delay(1_500L)
        }
    }

    private suspend fun stepTickLocked() {
        val st = _uiState.value
        if (!st.running) return
        val pos = st.activePosition
        val cfg = if (pos?.coreTargetNetPct != null) st.config.copy(targetBasis = TargetBasis.PRICE_MOVE_PCT,
            minNetProfitPct = pos.coreTargetNetPct, maxProfitPct = pos.coreTargetNetPct,
            maxHoldWaitMin = pos.coreHorizonMin ?: st.config.maxHoldWaitMin, allowUnlimitedHold = false) else st.config
        if (marketProvider().symbol != cfg.symbol || reportProvider().symbol != cfg.symbol) return
        val report = reportProvider()
        val market = marketProvider()
        val (quantAdx, quantEr) = quantAdxErProvider()
        val anchors = anchorsProvider(cfg.symbol)

        val livePrice = market.price
            ?: report.price
            ?: st.activePosition?.markPrice
        if (livePrice == null || !livePrice.isFinite() || livePrice <= 0.0) {
            _uiState.value = st.copy(
                statusText = "${cfg.mode.shortLabel} · ожидание живой котировки ${cfg.symbol}",
                waitReasonBadge = "Ожидание котировки",
                anchorsSummary = anchors?.summaryLine().orEmpty()
            )
            return
        }

        val nowMs = clockSync.exchangeNowMs()
        val effCap = computeEffectiveCapital(cfg.capitalUsdt, st.walletBalanceUsdt)
        val contract = contractCatalog.findContract(cfg.symbol)
        val minNotionalWarn = validateTrancheMinimum(cfg, effCap, livePrice, contract)
        val venueWarn = computeVenueSyncMismatchWarning(cfg, report)
        val trailNoiseWarn = computeTrailingNoiseWarning(cfg)
        val anchorsLine = anchors?.summaryLine().orEmpty()

        val snapshot = buildMarketContextSnapshot(
            report = report,
            market = market,
            anchors = anchors,
            quantAdx = quantAdx,
            quantEr = quantEr,
            livePrice = livePrice,
            nowMs = nowMs
        )
        val core = coreProvider()
        val instrument = instrumentProvider()
        val active = st.activePosition

        if (active == null) {
            if (isDailyLossLimitReached(cfg, st.activeModeStats, st.walletBalanceUsdt)) {
                _uiState.value = st.copy(
                    running = false,
                    lastError = "Достигнут дневной лимит убытка ${cfg.dailyMaxLossPct}%",
                    statusText = "Пауза по дневному лимиту убытка",
                    waitReasonBadge = "Дневной стоп-лимит",
                    effectiveCapitalUsdt = effCap,
                    minNotionalWarning = minNotionalWarn,
                    venueSyncMismatchWarning = venueWarn,
                    trailingNoiseWarning = trailNoiseWarn,
                    anchorsSummary = anchorsLine
                )
                onServiceStateChanged(false)
                return
            }

            if (minNotionalWarn != null) {
                pendingSignalSide = null
                _uiState.value = st.copy(
                    statusText = "${cfg.mode.shortLabel} · $minNotionalWarn",
                    waitReasonBadge = "Недостаточно маржи под minNotional",
                    effectiveCapitalUsdt = effCap,
                    minNotionalWarning = minNotionalWarn,
                    venueSyncMismatchWarning = venueWarn,
                    trailingNoiseWarning = trailNoiseWarn,
                    anchorsSummary = anchorsLine
                )
                return
            }

            val openCtx = OpenPositionContext(
                hasOpenPosition = false,
                maxTranches = cfg.maxTranches,
                minNetProfitPct = cfg.minNetProfitPct,
                preferMaker = cfg.executionPreference == OrderExecutionPreference.MAKER_POST_ONLY,
                allowCounterAbsorption = cfg.allowCounterAbsorption,
                requireRadarNotFlat = cfg.requireRadarNotFlat
            )
            val decision = BotDecisionEngine.evaluate(snapshot, openCtx, cfg, core, instrument)

            val candidateSide = when (decision) {
                is StrategyDecision.Enter -> {
                    val side = if (decision.sideIsBuy) TradeSide.BUY else TradeSide.SELL
                    if ((cfg.directionPolicy == DirectionPolicy.LONG_ONLY && side != TradeSide.BUY) ||
                        (cfg.directionPolicy == DirectionPolicy.SHORT_ONLY && side != TradeSide.SELL)
                    ) {
                        pendingSignalSide = null
                        _uiState.value = st.copy(
                            statusText = "${cfg.mode.shortLabel} · фильтр ${cfg.directionPolicy.labelRu}",
                            waitReasonBadge = "Фильтр: ${cfg.directionPolicy.labelRu}",
                            effectiveCapitalUsdt = effCap,
                            minNotionalWarning = minNotionalWarn,
                            venueSyncMismatchWarning = venueWarn,
                            trailingNoiseWarning = trailNoiseWarn,
                            anchorsSummary = anchorsLine,
                            chartOverlay = buildChartOverlay(cfg.symbol, null, st.recentTrades)
                        )
                        return
                    }
                    side to decision
                }
                is StrategyDecision.Hold -> {
                    pendingSignalSide = null
                    pendingSignalSinceMs = 0L
                    _uiState.value = st.copy(
                        statusText = "${cfg.mode.shortLabel} · ${decision.reason}",
                        waitReasonBadge = decision.reason,
                        effectiveCapitalUsdt = effCap,
                        minNotionalWarning = minNotionalWarn,
                        venueSyncMismatchWarning = venueWarn,
                        trailingNoiseWarning = trailNoiseWarn,
                        anchorsSummary = anchorsLine,
                        chartOverlay = buildChartOverlay(cfg.symbol, null, st.recentTrades)
                    )
                    return
                }
                else -> null
            } ?: return

            val (side, enterDecision) = candidateSide
            if (pendingSignalSide != side) {
                pendingSignalSide = side
                pendingSignalSinceMs = nowMs
                _uiState.value = st.copy(
                    statusText = "${cfg.mode.shortLabel} · подтверждение ${side.labelRu} 1/${cfg.confirmSignalSec}с…",
                    waitReasonBadge = "Подтверждение ${side.labelRu} (1/${cfg.confirmSignalSec}с)",
                    effectiveCapitalUsdt = effCap,
                    venueSyncMismatchWarning = venueWarn,
                    trailingNoiseWarning = trailNoiseWarn,
                    anchorsSummary = anchorsLine
                )
                return
            }

            val heldSec = ((nowMs - pendingSignalSinceMs) / 1000L).toInt()
            if (heldSec < cfg.confirmSignalSec) {
                _uiState.value = st.copy(
                    statusText = "${cfg.mode.shortLabel} · подтверждение ${side.labelRu} $heldSec/${cfg.confirmSignalSec}с…",
                    waitReasonBadge = "Подтверждение ${side.labelRu} ($heldSec/${cfg.confirmSignalSec}с)",
                    effectiveCapitalUsdt = effCap,
                    venueSyncMismatchWarning = venueWarn,
                    trailingNoiseWarning = trailNoiseWarn,
                    anchorsSummary = anchorsLine
                )
                return
            }

            pendingSignalSide = null
            openPositionInternal(
                cfg = if (cfg.enacoreBridge && core.targetPct != null && core.horizonMin != null) cfg.copy(
                    targetBasis = TargetBasis.PRICE_MOVE_PCT, minNetProfitPct = core.targetPct,
                    maxProfitPct = core.targetPct, maxHoldWaitMin = core.horizonMin, allowUnlimitedHold = false) else cfg,
                side = side,
                livePrice = livePrice,
                nowMs = nowMs,
                reason = enterDecision.note,
                orderPref = enterDecision.orderPreference,
                effectiveCapitalUsdt = effCap,
                atr1mPct = snapshot.atr1mPct
            )
            return
        }

        // Ведём открытую позицию (СТРОГО БЕЗ встречного Dual-Pocket Hedge!)
        var currentPos = refreshPositionMetrics(active, livePrice, cfg, snapshot.spreadPct)

        if (cfg.mode.isLiveExchange && (nowMs - lastExchangeSyncMs) >= 10_000L) {
            lastExchangeSyncMs = nowMs
            val slot = vaultStore.loadCredentialSlot(cfg.mode)
            val positionResult = withContext(Dispatchers.IO) {
                client.fetchOpenPosition(cfg.mode, slot, active.symbol)
            }
            if (!positionResult.ok) {
                _uiState.value = _uiState.value.copy(activePosition = currentPos,
                    lastError = "Не удалось синхронизировать позицию: ${positionResult.message}",
                    waitReasonBadge = "Ожидание подтверждения биржи")
                return
            }
            val liveExPos = positionResult.data
            if (liveExPos == null || liveExPos.size <= 0.0) {
                finalizeTradeRecord(currentPos, livePrice, "Исполнено/закрыто на бирже Bybit")
                return
            } else {
                currentPos = currentPos.copy(
                    estLiquidationPrice = liveExPos.liqPrice ?: currentPos.estLiquidationPrice,
                    serverTrailingStopPrice = liveExPos.stopLoss ?: currentPos.serverTrailingStopPrice
                )
            }
        }

        // Защитный server-side trading-stop на Bybit после выхода >= minNetProfitPct
        if (cfg.mode.isLiveExchange &&
            cfg.serverTrailingGuardEnabled &&
            currentPos.trailingFloorPct != null &&
            currentPos.trailFloorPrice != null &&
            currentPos.netPnlPct >= cfg.minNetProfitPct &&
            (nowMs - lastServerSlSyncMs) >= 8_000L
        ) {
            val prevSl = currentPos.serverTrailingStopPrice
            val targetFloor = currentPos.trailFloorPrice
            val movedEnough = prevSl == null ||
                (currentPos.side == TradeSide.BUY && targetFloor > prevSl * 1.0005) ||
                (currentPos.side == TradeSide.SELL && targetFloor < prevSl * 0.9995)
            if (movedEnough) {
                lastServerSlSyncMs = nowMs
                val slot = vaultStore.loadCredentialSlot(cfg.mode)
                val tickSize = contractCatalog.findTickSize(currentPos.symbol)
                val res = withContext(Dispatchers.IO) {
                    client.setTradingStop(cfg.mode, slot, currentPos.symbol, targetFloor, tickSize)
                }
                if (res.ok) {
                    currentPos = currentPos.copy(serverTrailingStopPrice = targetFloor)
                }
            }
        }

        val drawdownFromLastPct = computeDrawdownFromLastTranchePct(
            side = currentPos.side,
            lastTranchePrice = currentPos.lastTranchePrice,
            livePrice = livePrice
        )
        val effStepPct = if (cfg.useAtrScaleStep) {
            max(cfg.scaleStepPct, snapshot.atr1mPct * 1.3)
        } else {
            cfg.scaleStepPct
        }

        val openCtx = OpenPositionContext(
            hasOpenPosition = true,
            sideIsBuy = currentPos.side == TradeSide.BUY,
            avgEntryPrice = currentPos.entryPrice,
            lastTrancheEntryPrice = currentPos.lastTranchePrice,
            tranchesCount = currentPos.trancheCount,
            maxTranches = cfg.maxTranches,
            totalQty = currentPos.qty,
            totalPaidEntryFeesUsdt = currentPos.paidEntryFeesUsdt,
            netPnlPct = currentPos.netPnlPct,
            peakNetPnlPct = currentPos.peakNetPnlPct,
            drawdownFromLastTranchePct = drawdownFromLastPct,
            effectiveStepPct = effStepPct,
            peelDone = currentPos.peelDone,
            peelPartialEnabled = cfg.peelPartialEnabled,
            minNetProfitPct = cfg.minNetProfitPct,
            preferMaker = cfg.executionPreference == OrderExecutionPreference.MAKER_POST_ONLY,
            allowCounterAbsorption = cfg.allowCounterAbsorption,
            requireRadarNotFlat = cfg.requireRadarNotFlat,
            heldMs = (nowMs - currentPos.openedAtMs).coerceAtLeast(0L)
        )

        val decision = BotDecisionEngine.evaluate(snapshot, openCtx, cfg, core, instrument)
        val signalStillAligned = when (decision) {
            is StrategyDecision.ScaleIn -> (decision.sideIsBuy == (currentPos.side == TradeSide.BUY))
            else -> (!snapshot.conflictDetected &&
                ((currentPos.side == TradeSide.BUY && snapshot.deltaPct > 0.0) ||
                    (currentPos.side == TradeSide.SELL && snapshot.deltaPct < 0.0)))
        }

        val exitDecision = evaluateExitDecision(
            pos = currentPos,
            cfg = cfg,
            nowMs = nowMs,
            signalStillAligned = signalStillAligned,
            atr1mPct = snapshot.atr1mPct
        )

        if (exitDecision.shouldClose) {
            closePositionInternal(currentPos, livePrice, exitDecision.reason, forceMarket = false)
            return
        }

        // Проверяем PeelPartial или ScaleIn (Anti-Knife Gate уже проверен стратегией + перепроверяем локально)
        when (decision) {
            is StrategyDecision.PeelPartial -> {
                if (!currentPos.peelDone && currentPos.trancheCount >= 2 && cfg.peelPartialEnabled) {
                    val peeledPos = peelPartialInternal(currentPos, livePrice, decision.fraction, decision.note, cfg)
                    if (peeledPos != null) currentPos = peeledPos
                }
            }
            is StrategyDecision.ScaleIn -> {
                // Строгий запрет встречного хеджа: добор только в ту же сторону и при просадке >= effStepPct
                if (decision.sideIsBuy == (currentPos.side == TradeSide.BUY) &&
                    canScaleInTranche(
                        tranchesCount = currentPos.trancheCount,
                        maxTranches = cfg.maxTranches,
                        drawdownFromLastTranchePct = drawdownFromLastPct,
                        effectiveStepPct = effStepPct,
                        strategyAllowsScaleIn = true
                    )
                ) {
                    val scaled = scaleInTrancheInternal(
                        pos = currentPos,
                        cfg = cfg,
                        livePrice = livePrice,
                        nowMs = nowMs,
                        effectiveCapitalUsdt = effCap,
                        orderPref = decision.orderPreference,
                        note = decision.note,
                        atr1mPct = snapshot.atr1mPct
                    )
                    if (scaled != null) currentPos = scaled
                }
            }
            is StrategyDecision.ExitAll -> {
                closePositionInternal(currentPos, livePrice, decision.reason, forceMarket = false)
                return
            }
            else -> {}
        }

        val trailNote = currentPos.trailingFloorPct?.let {
            " · трал >= %.2f%%".format(Locale.US, it)
        }.orEmpty()
        val trancheNote = if (cfg.maxTranches > 1) {
            " · транш ${currentPos.trancheCount}/${cfg.maxTranches}"
        } else ""
        val holdBadge = when (decision) {
            is StrategyDecision.Hold -> decision.reason
            is StrategyDecision.ScaleIn -> decision.note
            is StrategyDecision.PeelPartial -> decision.note
            else -> "Сопровождение ${currentPos.side.labelRu}"
        }
        _uiState.value = _uiState.value.copy(
            activePosition = currentPos,
            effectiveCapitalUsdt = effCap,
            minNotionalWarning = minNotionalWarn,
            venueSyncMismatchWarning = venueWarn,
            trailingNoiseWarning = trailNoiseWarn,
            anchorsSummary = anchorsLine,
            waitReasonBadge = holdBadge,
            statusText = "${cfg.mode.shortLabel} · ${currentPos.side.labelRu} ${currentPos.symbol}$trancheNote " +
                "${formatSignedPct(currentPos.netPnlPct)}$trailNote",
            chartOverlay = buildChartOverlay(cfg.symbol, currentPos, _uiState.value.recentTrades)
        )
    }

    private suspend fun openPositionInternal(
        cfg: BotConfig,
        side: TradeSide,
        livePrice: Double,
        nowMs: Long,
        reason: String,
        orderPref: OrderExecutionPreference,
        effectiveCapitalUsdt: Double,
        atr1mPct: Double?
    ) {
        val contract = contractCatalog.findContract(cfg.symbol)
        val qtyStep = contract?.qtyStep ?: 0.001
        val minQty = contract?.minOrderQty ?: qtyStep
        val minNotional = contract?.minNotionalValue ?: 0.0
        val tranche1Share = cfg.normalizedTrancheWeights().firstOrNull()?.div(100.0) ?: 1.0
        val trancheMargin = effectiveCapitalUsdt * tranche1Share
        val rawQty = (trancheMargin * cfg.leverage) / livePrice
        val qtyStr = BybitV5TradingClient.roundQtyToStep(rawQty, qtyStep, minQty)
        if (qtyStr == null || (minNotional > 0.0 && qtyStr.toDouble() * livePrice < minNotional)) {
            _uiState.value = _uiState.value.copy(
                minNotionalWarning = "Транш #1 (${"%.2f".format(Locale.US, trancheMargin)} USDT × ${cfg.leverage}x) ниже minOrderQty ($minQty) / minNotional ($minNotional)",
                waitReasonBadge = "Объём меньше минимума биржи",
                statusText = "Объём транша #1 меньше минимального лота биржи"
            )
            return
        }
        val roundedQty = qtyStr.toDouble()
        val st = _uiState.value
        val makerPct = st.makerFeePct
        val takerPct = st.takerFeePct
        val expectedRoundTripPct = (if (orderPref == OrderExecutionPreference.MAKER_POST_ONLY) makerPct else takerPct) + takerPct

        val slPrice = if (cfg.stopLossEnabled) {
            val slPct = effectiveStopLossPct(cfg, atr1mPct)
            targetPriceForNetPct(
                side = side,
                entryPrice = livePrice,
                targetNetPct = -slPct,
                leverage = cfg.leverage,
                feeRateRoundTripPct = expectedRoundTripPct,
                targetBasis = cfg.targetBasis
            )
        } else null

        if (cfg.mode.isLiveExchange) {
            val slot = vaultStore.loadCredentialSlot(cfg.mode)
            val tickSize = contract?.tickSize
            val slFormatted = slPrice?.let { BybitV5TradingClient.formatPriceToTick(it, tickSize) }

            var actualEntry = livePrice
            var actualQty = roundedQty
            var executedAsMaker = orderPref == OrderExecutionPreference.MAKER_POST_ONLY
            var orderId = "live_${nowMs}"
            var execNote = reason

            if (orderPref == OrderExecutionPreference.MAKER_POST_ONLY) {
                val poRes = withContext(Dispatchers.IO) {
                    client.placePostOnlyLimitOrderWithRepeg(
                        mode = cfg.mode,
                        slot = slot,
                        symbol = cfg.symbol,
                        side = side,
                        qtyFormatted = qtyStr,
                        tickSize = tickSize,
                        reduceOnly = false,
                        stopLossFormatted = slFormatted,
                        fallbackToMarket = cfg.makerFallbackPolicy == MakerFallbackPolicy.FALLBACK_MARKET,
                        fallbackPrice = livePrice
                    )
                }
                if (!poRes.ok || poRes.data == null || !poRes.data.filled) {
                    _uiState.value = _uiState.value.copy(
                        waitReasonBadge = poRes.message.ifBlank { "PostOnly отменён (ожидание)" },
                        statusText = "${cfg.mode.shortLabel} · ${poRes.message}"
                    )
                    return
                }
                orderId = poRes.data.orderId.ifBlank { orderId }
                executedAsMaker = poRes.data.executedAsMaker
                if (poRes.data.avgPrice > 0.0) actualEntry = poRes.data.avgPrice
                if (poRes.data.executedQty > 0.0) actualQty = poRes.data.executedQty
                execNote = "$reason · ${poRes.data.note}"
            } else {
                val mktRes = withContext(Dispatchers.IO) {
                    client.placeMarketOrder(
                        mode = cfg.mode,
                        slot = slot,
                        symbol = cfg.symbol,
                        side = side,
                        qtyFormatted = qtyStr,
                        reduceOnly = false,
                        stopLossFormatted = slFormatted
                    )
                }
                if (!mktRes.ok) {
                    _uiState.value = _uiState.value.copy(
                        lastError = "Ордер отклонён (${cfg.mode.shortLabel}): ${mktRes.message}",
                        waitReasonBadge = "Ошибка ордера",
                        statusText = "Ошибка открытия ордера"
                    )
                    return
                }
                orderId = mktRes.data.orEmpty().ifBlank { orderId }
                executedAsMaker = false
            }

            delay(300L)
            val exPos = withContext(Dispatchers.IO) {
                client.fetchOpenPosition(cfg.mode, slot, cfg.symbol)
            }.data
            actualEntry = exPos?.avgPrice?.takeIf { it > 0.0 } ?: actualEntry
            actualQty = exPos?.size?.takeIf { it > 0.0 } ?: actualQty

            val created = buildInitialPosition(
                id = orderId,
                mode = cfg.mode,
                symbol = cfg.symbol,
                side = side,
                qty = actualQty,
                entryPrice = actualEntry,
                markPrice = exPos?.markPrice ?: livePrice,
                leverage = cfg.leverage,
                marginMode = cfg.marginMode,
                makerFeePct = makerPct,
                takerFeePct = takerPct,
                enteredAsMaker = executedAsMaker,
                cfg = cfg,
                openedAtMs = nowMs,
                reason = execNote,
                atr1mPct = atr1mPct
            ).copy(
                estLiquidationPrice = exPos?.liqPrice ?: estimateLiquidationPrice(side, actualEntry, cfg.leverage, cfg.marginMode),
                serverTrailingStopPrice = exPos?.stopLoss ?: slPrice
            )
            onCorePosition(created.id, created.symbol, true, 0.0)
            _uiState.value = _uiState.value.copy(
                activePosition = created.copy(coreTargetNetPct = cfg.minNetProfitPct.takeIf { cfg.enacoreBridge },
                    coreHorizonMin = cfg.maxHoldWaitMin.takeIf { cfg.enacoreBridge }),
                lastError = null,
                waitReasonBadge = "Открыт транш 1/${cfg.maxTranches}",
                statusText = "Открыта ${side.labelRu} ${cfg.symbol} @ $actualEntry",
                chartOverlay = buildChartOverlay(cfg.symbol, created, _uiState.value.recentTrades)
            )
        } else {
            val enteredAsMaker = orderPref == OrderExecutionPreference.MAKER_POST_ONLY
            val created = buildInitialPosition(
                id = "paper_${nowMs}",
                mode = BotMode.PAPER,
                symbol = cfg.symbol,
                side = side,
                qty = roundedQty,
                entryPrice = livePrice,
                markPrice = livePrice,
                leverage = cfg.leverage,
                marginMode = cfg.marginMode,
                makerFeePct = makerPct,
                takerFeePct = takerPct,
                enteredAsMaker = enteredAsMaker,
                cfg = cfg,
                openedAtMs = nowMs,
                reason = "$reason (${if (enteredAsMaker) "Maker" else "Taker"})",
                atr1mPct = atr1mPct
            )
            onCorePosition(created.id, created.symbol, true, 0.0)
            _uiState.value = _uiState.value.copy(
                activePosition = created.copy(coreTargetNetPct = cfg.minNetProfitPct.takeIf { cfg.enacoreBridge },
                    coreHorizonMin = cfg.maxHoldWaitMin.takeIf { cfg.enacoreBridge }),
                lastError = null,
                waitReasonBadge = "PAPER: транш 1/${cfg.maxTranches}",
                statusText = "PAPER: открыта ${side.labelRu} ${cfg.symbol} @ $livePrice",
                chartOverlay = buildChartOverlay(cfg.symbol, created, _uiState.value.recentTrades)
            )
        }
    }

    private suspend fun scaleInTrancheInternal(
        pos: ActivePosition,
        cfg: BotConfig,
        livePrice: Double,
        nowMs: Long,
        effectiveCapitalUsdt: Double,
        orderPref: OrderExecutionPreference,
        note: String,
        atr1mPct: Double?
    ): ActivePosition? {
        val nextIndex = pos.trancheCount
        if (nextIndex >= cfg.maxTranches) return null
        val contract = contractCatalog.findContract(pos.symbol)
        val qtyStep = contract?.qtyStep ?: 0.001
        val minQty = contract?.minOrderQty ?: qtyStep
        val minNotional = contract?.minNotionalValue ?: 0.0
        val weightShare = cfg.normalizedTrancheWeights().getOrElse(nextIndex) { 30 } / 100.0
        val trancheMargin = effectiveCapitalUsdt * weightShare
        val rawQty = (trancheMargin * pos.leverage) / livePrice
        val qtyStr = BybitV5TradingClient.roundQtyToStep(rawQty, qtyStep, minQty) ?: return null
        val addQtyTarget = qtyStr.toDouble()
        if (minNotional > 0.0 && addQtyTarget * livePrice < minNotional) return null

        var fillPrice = livePrice
        var fillQty = addQtyTarget
        var execAsMaker = orderPref == OrderExecutionPreference.MAKER_POST_ONLY

        if (pos.mode.isLiveExchange) {
            val slot = vaultStore.loadCredentialSlot(pos.mode)
            val tickSize = contract?.tickSize
            if (orderPref == OrderExecutionPreference.MAKER_POST_ONLY) {
                val poRes = withContext(Dispatchers.IO) {
                    client.placePostOnlyLimitOrderWithRepeg(
                        mode = pos.mode,
                        slot = slot,
                        symbol = pos.symbol,
                        side = pos.side,
                        qtyFormatted = qtyStr,
                        tickSize = tickSize,
                        reduceOnly = false,
                        stopLossFormatted = null,
                        fallbackToMarket = cfg.makerFallbackPolicy == MakerFallbackPolicy.FALLBACK_MARKET,
                        fallbackPrice = livePrice
                    )
                }
                if (!poRes.ok || poRes.data == null || !poRes.data.filled) return null
                execAsMaker = poRes.data.executedAsMaker
                if (poRes.data.avgPrice > 0.0) fillPrice = poRes.data.avgPrice
                if (poRes.data.executedQty > 0.0) fillQty = poRes.data.executedQty
            } else {
                val mktRes = withContext(Dispatchers.IO) {
                    client.placeMarketOrder(
                        mode = pos.mode,
                        slot = slot,
                        symbol = pos.symbol,
                        side = pos.side,
                        qtyFormatted = qtyStr,
                        reduceOnly = false,
                        stopLossFormatted = null
                    )
                }
                if (!mktRes.ok) return null
                execAsMaker = false
            }
        }

        val addFeeRate = if (execAsMaker) pos.makerFeePct else pos.takerFeePct
        val addFeeUsdt = fillPrice * fillQty * (addFeeRate / 100.0)
        val baseTranches = pos.tranches.ifEmpty {
            listOf(
                TradeTranche(
                    index = 1,
                    entryPrice = pos.entryPrice,
                    qty = pos.qty,
                    entryFeeUsdt = pos.paidEntryFeesUsdt.takeIf { it > 0.0 }
                        ?: (pos.entryPrice * pos.qty * (pos.makerFeePct / 100.0)),
                    enteredAtMs = pos.openedAtMs,
                    isMaker = true
                )
            )
        }
        val newTranches = baseTranches + TradeTranche(
            index = baseTranches.size + 1,
            entryPrice = fillPrice,
            qty = fillQty,
            entryFeeUsdt = addFeeUsdt,
            enteredAtMs = nowMs,
            isMaker = execAsMaker,
            note = note
        )

        val totalQty = newTranches.sumOf { it.qty }
        val blendedAvg = computeBlendedAvgEntry(newTranches)
        val totalPaidEntryFee = newTranches.sumOf { it.entryFeeUsdt }
        val totalMargin = (blendedAvg * totalQty) / pos.leverage.coerceAtLeast(1)

        val updatedBase = pos.copy(
            qty = totalQty,
            entryPrice = blendedAvg,
            usedMarginUsdt = totalMargin,
            tranches = newTranches,
            paidEntryFeesUsdt = totalPaidEntryFee,
            tpMinPrice = blendedTargetPriceForNetPct(
                pos.side, blendedAvg, totalQty, pos.leverage, totalMargin,
                totalPaidEntryFee, pos.exitFeeRatePct, cfg.minNetProfitPct, cfg.targetBasis
            ),
            tpMaxPrice = blendedTargetPriceForNetPct(
                pos.side, blendedAvg, totalQty, pos.leverage, totalMargin,
                totalPaidEntryFee, pos.exitFeeRatePct, cfg.maxProfitPct, cfg.targetBasis
            ),
            stopLossPrice = if (cfg.stopLossEnabled) {
                blendedTargetPriceForNetPct(
                    pos.side, blendedAvg, totalQty, pos.leverage, totalMargin,
                    totalPaidEntryFee, pos.exitFeeRatePct, -effectiveStopLossPct(cfg, atr1mPct), cfg.targetBasis
                )
            } else null,
            estLiquidationPrice = estimateLiquidationPrice(pos.side, blendedAvg, pos.leverage, pos.marginMode),
            peakNetPnlPct = 0.0,
            trailingFloorPct = null,
            trailFloorPrice = null
        )
        return refreshPositionMetrics(updatedBase, livePrice, cfg, 0.0)
    }

    private suspend fun peelPartialInternal(
        pos: ActivePosition,
        livePrice: Double,
        fraction: Double,
        note: String,
        cfg: BotConfig
    ): ActivePosition? {
        val contract = contractCatalog.findContract(pos.symbol)
        val qtyStep = contract?.qtyStep ?: 0.001
        val minQty = contract?.minOrderQty ?: qtyStep
        val peelQtyStr = BybitV5TradingClient.roundQtyToStep(pos.qty * fraction.coerceIn(0.25, 0.75), qtyStep, minQty)
            ?: return null
        val peelQty = peelQtyStr.toDouble()
        val remQty = pos.qty - peelQty
        if (remQty < minQty) return null

        val closeSide = if (pos.side == TradeSide.BUY) TradeSide.SELL else TradeSide.BUY
        if (pos.mode.isLiveExchange) {
            val slot = vaultStore.loadCredentialSlot(pos.mode)
            val res = withContext(Dispatchers.IO) {
                client.placeMarketOrder(
                    mode = pos.mode,
                    slot = slot,
                    symbol = pos.symbol,
                    side = closeSide,
                    qtyFormatted = peelQtyStr,
                    reduceOnly = true,
                    stopLossFormatted = null
                )
            }
            if (!res.ok) return null
        }

        val peelShare = (peelQty / pos.qty).coerceIn(0.0, 1.0)
        val remShare = 1.0 - peelShare
        val peelPaidEntryFee = pos.paidEntryFeesUsdt * peelShare
        val peelPnl = computeBlendedNetPnl(
            side = pos.side,
            qty = peelQty,
            avgEntryPrice = pos.entryPrice,
            markPrice = livePrice,
            leverage = pos.leverage,
            usedMarginUsdt = pos.usedMarginUsdt * peelShare,
            paidEntryFeesUsdt = peelPaidEntryFee,
            expectedExitFeeRatePct = pos.takerFeePct,
            targetBasis = cfg.targetBasis
        )
        val nowMs = clockSync.exchangeNowMs()
        val record = BotTradeRecord(
            id = "${pos.id}_peel",
            mode = pos.mode,
            symbol = pos.symbol,
            side = pos.side,
            qty = peelQty,
            entryPrice = pos.entryPrice,
            exitPrice = livePrice,
            leverage = pos.leverage,
            openedAtMs = pos.openedAtMs,
            closedAtMs = nowMs,
            grossPnlUsdt = peelPnl.grossPnlUsdt,
            feesUsdt = peelPnl.estFeeUsdt,
            netPnlUsdt = peelPnl.netPnlUsdt,
            netPnlPct = peelPnl.netPnlPct,
            exitReason = note,
            won = peelPnl.netPnlUsdt > 0.0
        )
        recordClosedSlice(record)

        val nextPaidEntryFee = pos.paidEntryFeesUsdt * remShare
        val nextMargin = pos.usedMarginUsdt * remShare
        return pos.copy(
            qty = remQty,
            usedMarginUsdt = nextMargin,
            paidEntryFeesUsdt = nextPaidEntryFee,
            peelDone = true,
            tpMinPrice = blendedTargetPriceForNetPct(
                pos.side, pos.entryPrice, remQty, pos.leverage, nextMargin,
                nextPaidEntryFee, pos.exitFeeRatePct, cfg.minNetProfitPct, cfg.targetBasis
            ),
            tpMaxPrice = blendedTargetPriceForNetPct(
                pos.side, pos.entryPrice, remQty, pos.leverage, nextMargin,
                nextPaidEntryFee, pos.exitFeeRatePct, cfg.maxProfitPct, cfg.targetBasis
            )
        )
    }

    private suspend fun closePositionInternal(
        pos: ActivePosition,
        markPrice: Double,
        reason: String,
        forceMarket: Boolean
    ) {
        if (pos.mode.isLiveExchange) {
            val slot = vaultStore.loadCredentialSlot(pos.mode)
            val qtyStep = contractCatalog.findQtyStep(pos.symbol) ?: 0.001
            val qtyStr = BybitV5TradingClient.roundQtyToStep(pos.qty, qtyStep, qtyStep)
                ?: pos.qty.toString()
            val closeSide = if (pos.side == TradeSide.BUY) TradeSide.SELL else TradeSide.BUY
            val res = withContext(Dispatchers.IO) {
                client.cancelAllOrders(pos.mode, slot, pos.symbol)
                client.placeMarketOrder(
                    mode = pos.mode,
                    slot = slot,
                    symbol = pos.symbol,
                    side = closeSide,
                    qtyFormatted = qtyStr,
                    reduceOnly = true,
                    stopLossFormatted = null
                )
            }
            if (!res.ok) {
                _uiState.value = _uiState.value.copy(
                    lastError = "Ошибка закрытия на бирже: ${res.message}"
                )
                return
            }
            val confirmedClose = withContext(Dispatchers.IO) { client.fetchOpenPosition(pos.mode, slot, pos.symbol) }
            if (!confirmedClose.ok || (confirmedClose.data?.size ?: 0.0) > 0.0) {
                _uiState.value = _uiState.value.copy(lastError = "Запрос закрытия отправлен; ожидается подтверждение биржи",
                    activePosition = pos)
                return
            }
            val bal = withContext(Dispatchers.IO) {
                client.fetchWalletBalanceUsdt(pos.mode, slot, _uiState.value.isUtaAccount ?: true)
            }.data
            if (bal != null) {
                _uiState.value = _uiState.value.copy(walletBalanceUsdt = bal)
            }
        }
        finalizeTradeRecord(pos, markPrice, reason)
    }

    private fun recordClosedSlice(record: BotTradeRecord) {
        vaultStore.appendTrade(record)
        val cfg = _uiState.value.config
        val todayUtc = utcDateString(record.closedAtMs)
        val curStatsMap = _uiState.value.statsByMode.toMutableMap()
        val prevModeStats = curStatsMap[record.mode] ?: ModeWinrateStats(record.mode)
        val nextTotal = prevModeStats.totalTrades + 1
        val nextWins = prevModeStats.wins + if (record.won) 1 else 0
        val nextLosses = prevModeStats.losses + if (record.won) 0 else 1
        val prevDayLoss = if (prevModeStats.lastDayUtc == todayUtc) prevModeStats.dailyRealizedLossUsdt else 0.0
        val nextDayLoss = if (record.netPnlUsdt < 0.0) prevDayLoss + abs(record.netPnlUsdt) else prevDayLoss
        curStatsMap[record.mode] = prevModeStats.copy(
            totalTrades = nextTotal,
            wins = nextWins,
            losses = nextLosses,
            winratePct = 100.0 * nextWins / nextTotal,
            totalNetPnlUsdt = prevModeStats.totalNetPnlUsdt + record.netPnlUsdt,
            dailyRealizedLossUsdt = nextDayLoss,
            lastDayUtc = todayUtc
        )
        vaultStore.saveStatsByMode(curStatsMap)

        val nextTrades = (_uiState.value.recentTrades + record).takeLast(50)
        val nextBalance = if (cfg.mode == BotMode.PAPER) {
            computePaperBalance(cfg.paperStartBalanceUsdt, curStatsMap[BotMode.PAPER])
        } else {
            _uiState.value.walletBalanceUsdt
        }
        _uiState.value = _uiState.value.copy(
            statsByMode = curStatsMap,
            recentTrades = nextTrades,
            walletBalanceUsdt = nextBalance
        )
    }

    private fun finalizeTradeRecord(pos: ActivePosition, exitPrice: Double, reason: String) {
        val cfg = if (pos.coreTargetNetPct != null) _uiState.value.config.copy(targetBasis = TargetBasis.PRICE_MOVE_PCT) else _uiState.value.config
        val pnl = if (pos.paidEntryFeesUsdt > 0.0) {
            computeBlendedNetPnl(
                side = pos.side,
                qty = pos.qty,
                avgEntryPrice = pos.entryPrice,
                markPrice = exitPrice,
                leverage = pos.leverage,
                usedMarginUsdt = pos.usedMarginUsdt,
                paidEntryFeesUsdt = pos.paidEntryFeesUsdt,
                expectedExitFeeRatePct = pos.takerFeePct,
                targetBasis = cfg.targetBasis
            )
        } else {
            computeNetPnl(
                side = pos.side,
                qty = pos.qty,
                entryPrice = pos.entryPrice,
                markPrice = exitPrice,
                leverage = pos.leverage,
                usedMarginUsdt = pos.usedMarginUsdt,
                feeRateRoundTripPct = pos.feeRatePct,
                targetBasis = cfg.targetBasis
            )
        }
        val won = pnl.netPnlUsdt > 0.0
        val record = BotTradeRecord(
            id = pos.id,
            mode = pos.mode,
            symbol = pos.symbol,
            side = pos.side,
            qty = pos.qty,
            entryPrice = pos.entryPrice,
            exitPrice = exitPrice,
            leverage = pos.leverage,
            openedAtMs = pos.openedAtMs,
            closedAtMs = clockSync.exchangeNowMs(),
            grossPnlUsdt = pnl.grossPnlUsdt,
            feesUsdt = pnl.estFeeUsdt,
            netPnlUsdt = pnl.netPnlUsdt,
            netPnlPct = pnl.netPnlPct,
            exitReason = reason,
            won = won
        )
        recordClosedSlice(record)
        onCorePosition(pos.id, pos.symbol, false, pnl.netPnlUsdt)
        _uiState.value = _uiState.value.copy(
            activePosition = null,
            statusText = "${pos.mode.shortLabel} · закрыта ${if (won) "+" else ""}${"%.2f".format(Locale.US, pnl.netPnlUsdt)} USDT ($reason)",
            waitReasonBadge = "Сделка закрыта",
            chartOverlay = buildChartOverlay(cfg.symbol, null, _uiState.value.recentTrades)
        )
    }

    private fun refreshPositionMetrics(
        pos: ActivePosition,
        markPrice: Double,
        cfg: BotConfig,
        spreadPct: Double = 0.0
    ): ActivePosition {
        val pnl = if (pos.paidEntryFeesUsdt > 0.0) {
            computeBlendedNetPnl(
                side = pos.side,
                qty = pos.qty,
                avgEntryPrice = pos.entryPrice,
                markPrice = markPrice,
                leverage = pos.leverage,
                usedMarginUsdt = pos.usedMarginUsdt,
                paidEntryFeesUsdt = pos.paidEntryFeesUsdt,
                expectedExitFeeRatePct = pos.exitFeeRatePct,
                targetBasis = cfg.targetBasis
            )
        } else {
            computeNetPnl(
                side = pos.side,
                qty = pos.qty,
                entryPrice = pos.entryPrice,
                markPrice = markPrice,
                leverage = pos.leverage,
                usedMarginUsdt = pos.usedMarginUsdt,
                feeRateRoundTripPct = pos.feeRatePct,
                targetBasis = cfg.targetBasis
            )
        }
        val nextPeak = max(pos.peakNetPnlPct, pnl.netPnlPct)
        val effTrailGiveback = adaptiveTrailingGivebackPct(
            userTrailPct = cfg.trailingGivebackPct,
            spreadPct = spreadPct,
            leverage = pos.leverage,
            targetBasis = cfg.targetBasis
        )
        val nextFloorPct = if (nextPeak >= cfg.minNetProfitPct) {
            max(cfg.minNetProfitPct, nextPeak - effTrailGiveback)
        } else {
            pos.trailingFloorPct
        }
        val nextFloorPrice = nextFloorPct?.let {
            if (pos.paidEntryFeesUsdt > 0.0) {
                blendedTargetPriceForNetPct(
                    pos.side, pos.entryPrice, pos.qty, pos.leverage, pos.usedMarginUsdt,
                    pos.paidEntryFeesUsdt, pos.exitFeeRatePct, it, cfg.targetBasis
                )
            } else {
                targetPriceForNetPct(pos.side, pos.entryPrice, it, pos.leverage, pos.feeRatePct, cfg.targetBasis)
            }
        }
        return pos.copy(
            markPrice = markPrice,
            estFeeUsdt = pnl.estFeeUsdt,
            grossPnlUsdt = pnl.grossPnlUsdt,
            netPnlUsdt = pnl.netPnlUsdt,
            netPnlPct = pnl.netPnlPct,
            peakNetPnlPct = nextPeak,
            trailingFloorPct = nextFloorPct,
            trailFloorPrice = nextFloorPrice
        )
    }

    private fun buildInitialPosition(
        id: String,
        mode: BotMode,
        symbol: String,
        side: TradeSide,
        qty: Double,
        entryPrice: Double,
        markPrice: Double,
        leverage: Int,
        marginMode: MarginMode,
        makerFeePct: Double,
        takerFeePct: Double,
        enteredAsMaker: Boolean,
        cfg: BotConfig,
        openedAtMs: Long,
        reason: String,
        atr1mPct: Double? = null
    ): ActivePosition {
        val entryFeePct = if (enteredAsMaker) makerFeePct else takerFeePct
        val exitFeePct = takerFeePct // Close and peel orders are Market, including after a Maker entry.
        val roundTripFeePct = entryFeePct + exitFeePct
        val paidEntryFeeUsdt = entryPrice * qty * (entryFeePct / 100.0)
        val usedMargin = (entryPrice * qty) / leverage.coerceAtLeast(1)
        val tpMin = blendedTargetPriceForNetPct(
            side, entryPrice, qty, leverage, usedMargin, paidEntryFeeUsdt, exitFeePct, cfg.minNetProfitPct, cfg.targetBasis
        )
        val tpMax = blendedTargetPriceForNetPct(
            side, entryPrice, qty, leverage, usedMargin, paidEntryFeeUsdt, exitFeePct, cfg.maxProfitPct, cfg.targetBasis
        )
        val sl = if (cfg.stopLossEnabled) {
            blendedTargetPriceForNetPct(
                side, entryPrice, qty, leverage, usedMargin, paidEntryFeeUsdt, exitFeePct,
                -effectiveStopLossPct(cfg, atr1mPct), cfg.targetBasis
            )
        } else null
        val liq = estimateLiquidationPrice(side, entryPrice, leverage, marginMode)
        val initialPnl = computeBlendedNetPnl(
            side, qty, entryPrice, markPrice, leverage, usedMargin, paidEntryFeeUsdt, exitFeePct, cfg.targetBasis
        )
        val firstTranche = TradeTranche(
            index = 1,
            entryPrice = entryPrice,
            qty = qty,
            entryFeeUsdt = paidEntryFeeUsdt,
            enteredAtMs = openedAtMs,
            isMaker = enteredAsMaker,
            note = reason
        )
        return ActivePosition(
            id = id,
            mode = mode,
            symbol = symbol,
            side = side,
            qty = qty,
            entryPrice = entryPrice,
            markPrice = markPrice,
            leverage = leverage,
            marginMode = marginMode,
            usedMarginUsdt = usedMargin,
            openedAtMs = openedAtMs,
            feeRatePct = roundTripFeePct,
            estFeeUsdt = initialPnl.estFeeUsdt,
            grossPnlUsdt = initialPnl.grossPnlUsdt,
            netPnlUsdt = initialPnl.netPnlUsdt,
            netPnlPct = initialPnl.netPnlPct,
            peakNetPnlPct = initialPnl.netPnlPct,
            tpMinPrice = tpMin,
            tpMaxPrice = tpMax,
            stopLossPrice = sl,
            estLiquidationPrice = liq,
            entryReason = reason,
            tranches = listOf(firstTranche),
            paidEntryFeesUsdt = paidEntryFeeUsdt,
            makerFeePct = makerFeePct,
            takerFeePct = takerFeePct,
            exitFeeRatePct = exitFeePct,
            peelDone = false
        )
    }

    companion object {
        private const val TAG = "TradeBotEngine"

        fun computePaperBalance(startBalanceUsdt: Double, paperStats: ModeWinrateStats?): Double {
            val delta = paperStats?.totalNetPnlUsdt ?: 0.0
            return (startBalanceUsdt + delta).coerceAtLeast(0.0)
        }

        fun computeEffectiveCapital(requestedCapitalUsdt: Double, availableBalanceUsdt: Double?): Double {
            val req = requestedCapitalUsdt.coerceAtLeast(0.0)
            if (availableBalanceUsdt == null || !availableBalanceUsdt.isFinite() || availableBalanceUsdt <= 0.0) {
                return req
            }
            return min(req, availableBalanceUsdt * 0.95).coerceAtLeast(0.0)
        }

        fun adaptiveTrailingGivebackPct(
            userTrailPct: Double,
            spreadPct: Double,
            leverage: Int = 1,
            targetBasis: TargetBasis = TargetBasis.NET_ROE_PCT
        ): Double {
            val safeSpread = spreadPct.coerceIn(0.0, 1.0)
            val spreadFloor = if (targetBasis == TargetBasis.NET_ROE_PCT) {
                safeSpread * 2.5 * leverage.coerceAtLeast(1)
            } else {
                safeSpread * 2.5
            }
            return max(userTrailPct, spreadFloor)
        }

        fun computeTrailingNoiseWarning(cfg: BotConfig): String? {
            return if (cfg.leverage >= 10 &&
                cfg.targetBasis == TargetBasis.NET_ROE_PCT &&
                cfg.trailingGivebackPct < 0.80
            ) {
                "Откат трала ${cfg.trailingGivebackPct}% ROE при ${cfg.leverage}x < 0.80% — высокий риск выбивания рыночным шумом"
            } else null
        }

        fun computeVenueSyncMismatchWarning(cfg: BotConfig, report: OrderFlowReport): String? {
            if (!cfg.mode.isLiveExchange) return null
            if (!report.venueSyncWarning.isNullOrBlank()) return report.venueSyncWarning
            val src = report.exchangeSource.trim()
            return if (src.isNotBlank() &&
                !src.equals("Bybit", ignoreCase = true) &&
                !src.equals("Поток", ignoreCase = true) &&
                !src.equals("Нет потока", ignoreCase = true)
            ) {
                "Рассинхрон площадок: бот на Bybit (${cfg.mode.shortLabel}), OrderFlow идёт с $src"
            } else null
        }

        fun validateTrancheMinimum(
            cfg: BotConfig,
            effectiveCapitalUsdt: Double,
            price: Double?,
            contract: ContractCatalogRepo.ContractInfo?
        ): String? {
            if (price == null || !price.isFinite() || price <= 0.0) return null
            val firstShare = cfg.normalizedTrancheWeights().firstOrNull()?.div(100.0) ?: 1.0
            val firstMargin = effectiveCapitalUsdt * firstShare
            val notional = firstMargin * cfg.leverage.coerceAtLeast(1)
            val rawQty = notional / price
            val minQty = contract?.minOrderQty ?: contract?.qtyStep
            if (minQty != null && rawQty < minQty) {
                return "Транш #1 (${"%.2f".format(Locale.US, notional)} $) < minOrderQty ($minQty)"
            }
            val minNotional = contract?.minNotionalValue
            if (minNotional != null && minNotional > 0.0 && notional < minNotional) {
                return "Транш #1 (${"%.2f".format(Locale.US, notional)} $) < minNotional (${"%.2f".format(Locale.US, minNotional)} $)"
            }
            return null
        }

        fun computeDrawdownFromLastTranchePct(
            side: TradeSide,
            lastTranchePrice: Double,
            livePrice: Double
        ): Double {
            if (lastTranchePrice <= 0.0 || livePrice <= 0.0) return 0.0
            return if (side == TradeSide.BUY) {
                ((lastTranchePrice - livePrice) / lastTranchePrice * 100.0).coerceAtLeast(0.0)
            } else {
                ((livePrice - lastTranchePrice) / lastTranchePrice * 100.0).coerceAtLeast(0.0)
            }
        }

        fun canScaleInTranche(
            tranchesCount: Int,
            maxTranches: Int,
            drawdownFromLastTranchePct: Double,
            effectiveStepPct: Double,
            strategyAllowsScaleIn: Boolean
        ): Boolean {
            return tranchesCount < maxTranches &&
                drawdownFromLastTranchePct >= effectiveStepPct &&
                strategyAllowsScaleIn
        }

        fun computeBlendedAvgEntry(tranches: List<TradeTranche>): Double {
            val totalQty = tranches.sumOf { it.qty }
            if (totalQty <= 0.0) return 0.0
            return tranches.sumOf { it.entryPrice * it.qty } / totalQty
        }

        fun computeBlendedNetPnl(
            side: TradeSide,
            qty: Double,
            avgEntryPrice: Double,
            markPrice: Double,
            leverage: Int,
            usedMarginUsdt: Double,
            paidEntryFeesUsdt: Double,
            expectedExitFeeRatePct: Double,
            targetBasis: TargetBasis
        ): NetPnlResult {
            val notional = avgEntryPrice * qty
            val safeMargin = if (usedMarginUsdt > 0.0) usedMarginUsdt else notional / leverage.coerceAtLeast(1)
            val gross = if (side == TradeSide.BUY) {
                (markPrice - avgEntryPrice) * qty
            } else {
                (avgEntryPrice - markPrice) * qty
            }
            val estExitFee = (markPrice * qty) * (expectedExitFeeRatePct / 100.0)
            val totalFee = paidEntryFeesUsdt + estExitFee
            val net = gross - totalFee
            val denom = if (targetBasis == TargetBasis.NET_ROE_PCT) safeMargin else notional
            val netPct = if (denom > 0.0) 100.0 * net / denom else 0.0
            return NetPnlResult(gross, totalFee, net, netPct)
        }

        fun blendedTargetPriceForNetPct(
            side: TradeSide,
            avgEntryPrice: Double,
            qty: Double,
            leverage: Int,
            usedMarginUsdt: Double,
            paidEntryFeesUsdt: Double,
            expectedExitFeeRatePct: Double,
            targetNetPct: Double,
            targetBasis: TargetBasis
        ): Double {
            if (avgEntryPrice <= 0.0 || qty <= 0.0) return avgEntryPrice
            val notional = avgEntryPrice * qty
            val safeMargin = if (usedMarginUsdt > 0.0) usedMarginUsdt else notional / leverage.coerceAtLeast(1)
            val denom = if (targetBasis == TargetBasis.NET_ROE_PCT) safeMargin else notional
            val desiredNetUsdt = denom * (targetNetPct / 100.0)
            val exitFeeFrac = (expectedExitFeeRatePct / 100.0).coerceIn(0.0, 0.05)
            val raw = if (side == TradeSide.BUY) {
                (avgEntryPrice * qty + paidEntryFeesUsdt + desiredNetUsdt) / (qty * (1.0 - exitFeeFrac))
            } else {
                (avgEntryPrice * qty - paidEntryFeesUsdt - desiredNetUsdt) / (qty * (1.0 + exitFeeFrac))
            }
            return raw.coerceAtLeast(avgEntryPrice * 0.05)
        }

        fun effectiveStopLossPct(cfg: BotConfig, atr1mPct: Double?): Double {
            if (cfg.stopLossBasis == StopLossBasis.ATR_MULT && atr1mPct != null && atr1mPct > 0.0) {
                val multLev = if (cfg.targetBasis == TargetBasis.NET_ROE_PCT) cfg.leverage.coerceAtLeast(1) else 1
                return (atr1mPct * cfg.stopLossAtrMult * multLev).coerceIn(0.2, 45.0)
            }
            return cfg.stopLossPct
        }

        fun buildMarketContextSnapshot(
            report: OrderFlowReport,
            market: AppState.MarketUi,
            anchors: MarketAnchorsSnapshot?,
            quantAdx: Double?,
            quantEr: Double?,
            livePrice: Double,
            nowMs: Long
        ): MarketContextSnapshot {
            val atr = anchors?.atr1mPct ?: report.levelAttack.atr1mPct.takeIf { it > 0.0 } ?: 0.18
            val bestBid = market.bids.maxOfOrNull { it.price }
            val bestAsk = market.asks.minOfOrNull { it.price }
            val computedSpreadPct = if (
                bestBid != null && bestAsk != null &&
                bestAsk >= bestBid && (bestBid + bestAsk) > 0.0
            ) {
                100.0 * (bestAsk - bestBid) / ((bestBid + bestAsk) / 2.0)
            } else {
                0.02
            }
            return MarketContextSnapshot(
                symbol = report.symbol,
                price = livePrice,
                nowMs = nowMs,
                dataFreshnessLive = report.freshness == DataFreshness.LIVE && !report.wsStale,
                conflictDetected = report.glow.conflictDetected || report.dominance == MarketDominance.CONFLICT,
                spreadPct = computedSpreadPct,
                deltaPct = report.footprint.deltaPct,
                normalizedOfi = report.dom.normalizedOfi,
                cvdSlope = report.footprint.cvdSlope,
                replenishmentRate = report.dom.replenishmentRate,
                levelPersistenceRatio = report.dom.levelPersistenceRatio,
                churnInflationRatio = report.dom.churnInflationRatio,
                liquidityPullDetected = report.dom.liquidityPullDetected,
                absorptionSideName = report.absorption.side.name,
                absorptionDetected = report.absorption.detected,
                absorptionConfirmed = report.absorption.confirmed,
                passiveTrapDetected = report.absorption.passiveTrapDetected,
                progressEfficiency = report.absorption.priceProgressEfficiency,
                targetLevelPrice = report.absorption.targetLevelPrice ?: report.levelAttack.levelPrice,
                levelIsResistance = report.levelAttack.isResistance,
                levelDistancePct = report.levelAttack.distancePct,
                levelPhaseName = report.levelAttack.phase.name,
                glowColorName = report.glow.colorState.name,
                glowStrength = report.glow.strength,
                quantAdx = quantAdx,
                quantEr = quantEr,
                spotPerpCvdDivergence = anchors?.spotPerpCvdDivergence ?: 0.0,
                spotCvd1m = anchors?.spotCvd1m ?: 0.0,
                perpCvd1m = anchors?.perpCvd1m ?: 0.0,
                openInterestValue = anchors?.openInterestValue ?: market.openInterest,
                oiChange5mPct = anchors?.oiChange5mPct,
                fundingRatePct = anchors?.fundingRatePct ?: market.fundingPct,
                longLiqUsd15s = anchors?.longLiqUsd15s ?: 0.0,
                shortLiqUsd15s = anchors?.shortLiqUsd15s ?: 0.0,
                longLiqUsd60s = anchors?.longLiqUsd60s ?: 0.0,
                shortLiqUsd60s = anchors?.shortLiqUsd60s ?: 0.0,
                ema9 = anchors?.ema9,
                ema21 = anchors?.ema21,
                emaDriftPct = anchors?.emaDriftPct ?: 0.0,
                atr1mPct = atr,
                nearestSupport = anchors?.nearestSupport ?: report.overlay.supportLevels.firstOrNull(),
                nearestResistance = anchors?.nearestResistance ?: report.overlay.resistanceLevels.firstOrNull(),
                vpin = anchors?.vpin ?: 0.0,
                kylesLambda = anchors?.kylesLambda ?: 0.0
            )
        }

        fun computeNetPnl(
            side: TradeSide,
            qty: Double,
            entryPrice: Double,
            markPrice: Double,
            leverage: Int,
            usedMarginUsdt: Double,
            feeRateRoundTripPct: Double,
            targetBasis: TargetBasis
        ): NetPnlResult {
            val notional = entryPrice * qty
            val safeMargin = if (usedMarginUsdt > 0.0) usedMarginUsdt else notional / leverage.coerceAtLeast(1)
            val gross = if (side == TradeSide.BUY) {
                (markPrice - entryPrice) * qty
            } else {
                (entryPrice - markPrice) * qty
            }
            val oneSideFrac = (feeRateRoundTripPct / 2.0) / 100.0
            val estFee = (entryPrice * qty + markPrice * qty) * oneSideFrac
            val net = gross - estFee
            val denom = if (targetBasis == TargetBasis.NET_ROE_PCT) safeMargin else notional
            val netPct = if (denom > 0.0) 100.0 * net / denom else 0.0
            return NetPnlResult(gross, estFee, net, netPct)
        }

        fun targetPriceForNetPct(
            side: TradeSide,
            entryPrice: Double,
            targetNetPct: Double,
            leverage: Int,
            feeRateRoundTripPct: Double,
            targetBasis: TargetBasis
        ): Double {
            val levDiv = if (targetBasis == TargetBasis.NET_ROE_PCT) leverage.coerceAtLeast(1).toDouble() else 1.0
            val moveFracNeeded = (targetNetPct / levDiv) / 100.0 + (feeRateRoundTripPct / 100.0)
            return if (side == TradeSide.BUY) {
                (entryPrice * (1.0 + moveFracNeeded)).coerceAtLeast(entryPrice * 0.05)
            } else {
                (entryPrice * (1.0 - moveFracNeeded)).coerceAtLeast(entryPrice * 0.05)
            }
        }

        fun estimateLiquidationPrice(
            side: TradeSide,
            entryPrice: Double,
            leverage: Int,
            marginMode: MarginMode,
            maintenanceMarginRate: Double = 0.005
        ): Double? {
            if (marginMode == MarginMode.CROSS || leverage <= 1 || !entryPrice.isFinite() || entryPrice <= 0.0) {
                return null
            }
            val buffer = (1.0 / leverage.toDouble() - maintenanceMarginRate).coerceIn(0.002, 0.95)
            return if (side == TradeSide.BUY) {
                (entryPrice * (1.0 - buffer)).coerceAtLeast(0.0)
            } else {
                entryPrice * (1.0 + buffer)
            }
        }

        fun evaluateSignalDirection(
            report: OrderFlowReport,
            quantAdx: Double?,
            quantEr: Double?,
            cfg: BotConfig
        ): EntryDecision {
            if (report.freshness != DataFreshness.LIVE || report.wsStale) {
                return EntryDecision(false, null, "ожидание LIVE потока (${report.freshness.labelRu})")
            }
            if (report.glow.conflictDetected || report.dominance == MarketDominance.CONFLICT) {
                return EntryDecision(false, null, "конфликт сигналов — пауза входа")
            }
            if (cfg.requireRadarNotFlat && quantAdx != null && quantEr != null &&
                quantAdx.isFinite() && quantEr.isFinite() && quantAdx < 18.0 && quantEr < 0.20
            ) {
                return EntryDecision(false, null, "флет Радара (ADX %.1f)".format(Locale.US, quantAdx))
            }

            val side: TradeSide? = when {
                report.levelAttack.phase == LevelAttackPhase.CONFIRMED_BREAKOUT ->
                    if (report.levelAttack.isResistance) TradeSide.BUY else TradeSide.SELL
                report.levelAttack.phase == LevelAttackPhase.CONFIRMED_REJECTION ->
                    if (report.levelAttack.isResistance) TradeSide.SELL else TradeSide.BUY
                cfg.requireGlowConsensus && report.glow.colorState == GlowColorState.GREEN && report.glow.strength >= 0.40f ->
                    TradeSide.BUY
                cfg.requireGlowConsensus && report.glow.colorState == GlowColorState.RED && report.glow.strength >= 0.40f ->
                    TradeSide.SELL
                !cfg.requireGlowConsensus && report.footprint.deltaPct >= 14.0 && report.dom.normalizedOfi >= 0.16 ->
                    TradeSide.BUY
                !cfg.requireGlowConsensus && report.footprint.deltaPct <= -14.0 && report.dom.normalizedOfi <= -0.16 ->
                    TradeSide.SELL
                else -> null
            }

            if (side == null) {
                return EntryDecision(false, null, "поиск согласованного импульса…")
            }

            if (!cfg.allowCounterAbsorption && report.absorption.detected) {
                val blockedByAbs = (side == TradeSide.BUY && report.absorption.side == AbsorptionSide.SELLER_ABSORPTION) ||
                    (side == TradeSide.SELL && report.absorption.side == AbsorptionSide.BUYER_ABSORPTION)
                if (blockedByAbs) {
                    return EntryDecision(false, null, "поглощение против ${side.labelRu} — вход отложен")
                }
            }

            if (cfg.directionPolicy == DirectionPolicy.LONG_ONLY && side != TradeSide.BUY) {
                return EntryDecision(false, null, "фильтр «Только Long» — Short пропущен")
            }
            if (cfg.directionPolicy == DirectionPolicy.SHORT_ONLY && side != TradeSide.SELL) {
                return EntryDecision(false, null, "фильтр «Только Short» — Long пропущен")
            }

            val reason = when (report.levelAttack.phase) {
                LevelAttackPhase.CONFIRMED_BREAKOUT -> "Подтверждённый пробой (${report.glow.independentFactors} ф.)"
                LevelAttackPhase.CONFIRMED_REJECTION -> "Подтверждённый отбой от уровня"
                else -> "Консенсус OrderFlow (${report.glow.independentFactors} ф.)"
            }
            return EntryDecision(true, side, reason)
        }

        fun evaluateExitDecision(
            pos: ActivePosition,
            cfg: BotConfig,
            nowMs: Long,
            signalStillAligned: Boolean,
            atr1mPct: Double? = null
        ): ExitDecision {
            if (pos.netPnlPct >= cfg.maxProfitPct) {
                return ExitDecision(true, "Цель ${formatSignedPct(cfg.maxProfitPct)} достигнута")
            }
            val floor = pos.trailingFloorPct
            if (floor != null && pos.peakNetPnlPct >= cfg.minNetProfitPct && pos.netPnlPct <= floor) {
                return ExitDecision(true, "Трейлинг-фиксация ${formatSignedPct(floor)}")
            }
            val effSl = effectiveStopLossPct(cfg, atr1mPct)
            if (cfg.stopLossEnabled && pos.netPnlPct <= -effSl) {
                return ExitDecision(true, "Stop-Loss ${formatSignedPct(-effSl)}")
            }
            val heldMs = (nowMs - pos.openedAtMs).coerceAtLeast(0L)
            if (!cfg.allowUnlimitedHold &&
                heldMs >= cfg.maxHoldWaitMin * 60_000L &&
                pos.netPnlPct < cfg.minNetProfitPct &&
                !signalStillAligned
            ) {
                return ExitDecision(true, "Тайм-аут удержания (${cfg.maxHoldWaitMin} мин)")
            }
            return ExitDecision(false)
        }

        fun isDailyLossLimitReached(cfg: BotConfig, stats: ModeWinrateStats, balanceUsdt: Double?): Boolean {
            val today = utcDateString(System.currentTimeMillis())
            if (stats.lastDayUtc != today) return false
            val base = (balanceUsdt?.takeIf { it > 0.0 } ?: cfg.paperStartBalanceUsdt).coerceAtLeast(cfg.capitalUsdt)
            val lossLimitUsdt = base * (cfg.dailyMaxLossPct / 100.0)
            return stats.dailyRealizedLossUsdt >= lossLimitUsdt
        }

        fun buildChartOverlay(
            symbol: String,
            pos: ActivePosition?,
            trades: List<BotTradeRecord>
        ): BotChartOverlay {
            val markers = trades
                .filter { it.symbol.equals(symbol, ignoreCase = true) }
                .takeLast(10)
                .flatMap { tr ->
                    listOf(
                        BotTradeMarker(tr.openedAtMs, tr.entryPrice, tr.side, isEntry = true, won = null),
                        BotTradeMarker(tr.closedAtMs, tr.exitPrice, tr.side, isEntry = false, won = tr.won)
                    )
                }
            return BotChartOverlay(
                symbol = symbol,
                entryPrice = pos?.takeIf { it.symbol.equals(symbol, ignoreCase = true) }?.entryPrice,
                side = pos?.takeIf { it.symbol.equals(symbol, ignoreCase = true) }?.side,
                tpMinPrice = pos?.takeIf { it.symbol.equals(symbol, ignoreCase = true) }?.tpMinPrice,
                tpMaxPrice = pos?.takeIf { it.symbol.equals(symbol, ignoreCase = true) }?.tpMaxPrice,
                trailFloorPrice = pos?.takeIf { it.symbol.equals(symbol, ignoreCase = true) }?.trailFloorPrice,
                stopLossPrice = pos?.takeIf { it.symbol.equals(symbol, ignoreCase = true) }?.stopLossPrice,
                estLiquidationPrice = pos?.takeIf { it.symbol.equals(symbol, ignoreCase = true) }?.estLiquidationPrice,
                markers = markers
            )
        }

        private fun utcDateString(tsMs: Long): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return fmt.format(Date(tsMs))
        }

        private fun formatSignedPct(v: Double): String = String.format(Locale.US, "%+.2f%%", v)
    }
}
