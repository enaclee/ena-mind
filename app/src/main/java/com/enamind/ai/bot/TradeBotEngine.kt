package com.enamind.ai.bot

import android.util.Log
import com.enamind.ai.bybit.ContractCatalogRepo
import com.enamind.ai.core.AppState
import com.enamind.ai.intelligence.ExchangeClockSync
import com.enamind.ai.orderflow.AbsorptionSide
import com.enamind.ai.orderflow.DataFreshness
import com.enamind.ai.orderflow.GlowColorState
import com.enamind.ai.orderflow.LevelAttackPhase
import com.enamind.ai.orderflow.MarketDominance
import com.enamind.ai.orderflow.OrderFlowReport
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
 * Архитектурные гарантии:
 *  - Выполняется в выделенном ограниченном пуле корутин без блокировки UI / WS-потоков;
 *  - Независимая статистика Winrate и дневного убытка по каждому из 4 режимов;
 *  - Динамический выход: минимум 0.5% чистыми (с учётом комиссий), далее трейлинг от 0.5% до 3.5%;
 *  - После выхода >= minNetProfitPct (0.5%) опционально дублирует безубыточный/трейлинг-стоп
 *    на сервере Bybit (/v5/position/trading-stop), чтобы зафиксированный плюс не пропал при обрыве связи;
 *  - Разрешает отключение Stop-Loss во всех режимах с честным расчётом цены ликвидации;
 *  - Атомарная аварийная остановка + закрытие позиции по рынку.
 */
class TradeBotEngine(
    private val vaultStore: BotVaultStore,
    private val contractCatalog: ContractCatalogRepo,
    private val reportProvider: () -> OrderFlowReport,
    private val marketProvider: () -> AppState.MarketUi,
    private val quantAdxErProvider: () -> Pair<Double?, Double?>,
    private val clockSync: ExchangeClockSync = ExchangeClockSync(),
    private val onServiceStateChanged: (shouldRunService: Boolean) -> Unit = {},
    private val onNotify: (BotStatusNotification) -> Unit = {}
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

    private val _uiState = MutableStateFlow(
        BotUiState(
            config = initialConfig,
            walletBalanceUsdt = if (initialConfig.mode == BotMode.PAPER) initialConfig.paperStartBalanceUsdt else null,
            statsByMode = initialStats,
            recentTrades = initialTrades,
            chartOverlay = buildChartOverlay(initialConfig.symbol, null, initialTrades)
        )
    )
    val uiState: StateFlow<BotUiState> = _uiState

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
        val contract = contractCatalog.findContract(raw.symbol)
        val maxLev = (contract?.maxLeverage?.toInt() ?: 125).coerceIn(1, 125)
        val clean = raw.copy(
            symbol = raw.symbol.trim().uppercase().ifBlank { "BTCUSDT" },
            capitalUsdt = raw.capitalUsdt.coerceIn(5.0, 100_000.0),
            leverage = raw.leverage.coerceIn(1, maxLev),
            minNetProfitPct = raw.minNetProfitPct.coerceIn(0.15, 10.0),
            maxProfitPct = maxOf(raw.maxProfitPct, raw.minNetProfitPct + 0.2).coerceIn(0.35, 50.0),
            trailingGivebackPct = raw.trailingGivebackPct.coerceIn(0.05, 5.0),
            maxHoldWaitMin = raw.maxHoldWaitMin.coerceIn(1, 720),
            stopLossPct = raw.stopLossPct.coerceIn(0.2, 40.0),
            dailyMaxLossPct = raw.dailyMaxLossPct.coerceIn(0.5, 50.0),
            confirmSignalSec = raw.confirmSignalSec.coerceIn(2, 30)
        )
        vaultStore.saveConfig(clean)
        val modeChanged = prev.mode != clean.mode
        val bal = when {
            clean.mode == BotMode.PAPER -> computePaperBalance(clean.paperStartBalanceUsdt, _uiState.value.statsByMode[BotMode.PAPER])
            modeChanged -> null
            else -> _uiState.value.walletBalanceUsdt
        }
        _uiState.value = _uiState.value.copy(
            config = clean,
            walletBalanceUsdt = bal,
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
            _uiState.value = _uiState.value.copy(
                walletBalanceUsdt = paperBal,
                feeRateSidePct = 0.055,
                exchangeConnected = true,
                lastError = null,
                lastCheckNote = "Paper-баланс: %.2f USDT".format(Locale.US, paperBal)
            )
            return
        }
        val slot = vaultStore.loadCredentialSlot(cfg.mode)
        _uiState.value = _uiState.value.copy(lastCheckNote = "Проверка ключей ${cfg.mode.shortLabel}…", lastError = null)
        scope.launch(Dispatchers.IO) {
            val res = client.probeAccountAndBalance(cfg.mode, slot, cfg.symbol)
            if (res.ok && res.data != null) {
                val p = res.data
                _uiState.value = _uiState.value.copy(
                    walletBalanceUsdt = p.walletUsdt,
                    feeRateSidePct = p.feeRateSidePct,
                    exchangeConnected = true,
                    isUtaAccount = p.isUta,
                    lastError = null,
                    lastCheckNote = "Связь OK: ${p.note}"
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
        if (st.running) return

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
            statusText = "Запуск (${cfg.mode.shortLabel}) · подготовка ${cfg.symbol}…"
        )
        onServiceStateChanged(true)

        scope.launch {
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
                        statusText = "Ошибка запуска: ${probe.message}"
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
                        statusText = "Ошибка маржи/плеча: ${prepMargin.message}"
                    )
                    onServiceStateChanged(false)
                    return@launch
                }
                // Проверим, нет ли уже открытой позиции на бирже по этой паре
                val existingPos = withContext(Dispatchers.IO) {
                    client.fetchOpenPosition(cfg.mode, slot, cfg.symbol)
                }.data
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
                        feeRateSidePct = probe.data.feeRateSidePct,
                        cfg = cfg,
                        openedAtMs = System.currentTimeMillis(),
                        reason = "Подхвачена открытая позиция биржи"
                    ).copy(
                        estLiquidationPrice = existingPos.liqPrice,
                        serverTrailingStopPrice = existingPos.stopLoss
                    )
                    _uiState.value = _uiState.value.copy(activePosition = adopted)
                }
                _uiState.value = _uiState.value.copy(
                    walletBalanceUsdt = probe.data.walletUsdt,
                    feeRateSidePct = probe.data.feeRateSidePct,
                    exchangeConnected = true,
                    isUtaAccount = probe.data.isUta,
                    statusText = "${cfg.mode.shortLabel} активен · ${cfg.symbol} (${cfg.leverage}x ${cfg.marginMode.labelRu})"
                )
                startPrivateWs(cfg.mode, slot)
            } else {
                val bal = computePaperBalance(cfg.paperStartBalanceUsdt, stats)
                _uiState.value = _uiState.value.copy(
                    walletBalanceUsdt = bal,
                    feeRateSidePct = 0.055,
                    exchangeConnected = true,
                    statusText = "PAPER активен · ожидание консенсуса ${cfg.symbol}"
                )
            }

            loopJob?.cancel()
            loopJob = scope.launch {
                runBotLoop()
            }
        }
    }

    fun stopBotOnly(reason: String = "Остановлен пользователем") {
        loopJob?.cancel()
        loopJob = null
        privateWs?.close()
        privateWs = null
        pendingSignalSide = null
        _uiState.value = _uiState.value.copy(
            running = false,
            closing = false,
            statusText = reason
        )
        onServiceStateChanged(false)
    }

    fun stopAndCloseMarket(reason: String = "Аварийная остановка и закрытие по рынку") {
        loopJob?.cancel()
        loopJob = null
        pendingSignalSide = null
        scope.launch {
            tradeMutex.withLock {
                _uiState.value = _uiState.value.copy(closing = true, statusText = "Закрытие позиции по рынку…")
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
                statusText = reason,
                chartOverlay = buildChartOverlay(_uiState.value.config.symbol, null, _uiState.value.recentTrades)
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
                // Позиция уже закрыта на стороне биржи (например, по серверному trading-stop или вручную)
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
        val cfg = st.config
        val report = reportProvider()
        val market = marketProvider()
        val (quantAdx, quantEr) = quantAdxErProvider()

        val livePrice = market.price
            ?: report.price
            ?: st.activePosition?.markPrice
        if (livePrice == null || !livePrice.isFinite() || livePrice <= 0.0) {
            _uiState.value = st.copy(statusText = "${cfg.mode.shortLabel} · ожидание живой котировки ${cfg.symbol}")
            return
        }

        val nowMs = clockSync.exchangeNowMs()
        val active = st.activePosition

        if (active == null) {
            // Проверка дневного лимита убытка
            if (isDailyLossLimitReached(cfg, st.activeModeStats, st.walletBalanceUsdt)) {
                _uiState.value = st.copy(
                    running = false,
                    lastError = "Достигнут дневной лимит убытка ${cfg.dailyMaxLossPct}%",
                    statusText = "Пауза по дневному лимиту убытка"
                )
                onServiceStateChanged(false)
                return
            }

            // Оцениваем консенсус OrderFlow + Radar
            val rawDecision = evaluateSignalDirection(report, quantAdx, quantEr, cfg)
            val candidateSide = rawDecision.side
            if (!rawDecision.shouldEnter || candidateSide == null) {
                pendingSignalSide = null
                pendingSignalSinceMs = 0L
                _uiState.value = st.copy(
                    statusText = "${cfg.mode.shortLabel} · ${rawDecision.reason}",
                    chartOverlay = buildChartOverlay(cfg.symbol, null, st.recentTrades)
                )
                return
            }

            if (pendingSignalSide != candidateSide) {
                pendingSignalSide = candidateSide
                pendingSignalSinceMs = nowMs
                _uiState.value = st.copy(
                    statusText = "${cfg.mode.shortLabel} · подтверждение ${candidateSide.labelRu} 1/${cfg.confirmSignalSec}с…"
                )
                return
            }

            val heldSec = ((nowMs - pendingSignalSinceMs) / 1000L).toInt()
            if (heldSec < cfg.confirmSignalSec) {
                _uiState.value = st.copy(
                    statusText = "${cfg.mode.shortLabel} · подтверждение ${candidateSide.labelRu} $heldSec/${cfg.confirmSignalSec}с…"
                )
                return
            }

            // Вход разрешён!
            pendingSignalSide = null
            openPositionInternal(cfg, candidateSide, livePrice, nowMs, rawDecision.reason)
            return
        }

        // Ведём открытую позицию
        var currentPos = refreshPositionMetrics(active, livePrice, cfg)

        // Периодическая сверка с сервером Bybit раз в 10 секунд (чтобы не пропустить закрытие на бирже)
        if (cfg.mode.isLiveExchange && (nowMs - lastExchangeSyncMs) >= 10_000L) {
            lastExchangeSyncMs = nowMs
            val slot = vaultStore.loadCredentialSlot(cfg.mode)
            val liveExPos = withContext(Dispatchers.IO) {
                client.fetchOpenPosition(cfg.mode, slot, active.symbol)
            }.data
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

        // Уточнение #6: дублирование защитного безубыточного/трейлинг-стопа на сервере Bybit
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

        val sigCheck = evaluateSignalDirection(report, quantAdx, quantEr, cfg)
        val signalStillAligned = sigCheck.side == currentPos.side
        val exitDecision = evaluateExitDecision(
            pos = currentPos,
            cfg = cfg,
            nowMs = nowMs,
            signalStillAligned = signalStillAligned
        )

        if (exitDecision.shouldClose) {
            closePositionInternal(currentPos, livePrice, exitDecision.reason, forceMarket = false)
        } else {
            val trailNote = currentPos.trailingFloorPct?.let {
                " · трал >= %.2f%%".format(Locale.US, it)
            }.orEmpty()
            _uiState.value = _uiState.value.copy(
                activePosition = currentPos,
                statusText = "${cfg.mode.shortLabel} · ${currentPos.side.labelRu} ${currentPos.symbol} " +
                    "${formatSignedPct(currentPos.netPnlPct)}$trailNote",
                chartOverlay = buildChartOverlay(cfg.symbol, currentPos, _uiState.value.recentTrades)
            )
        }
    }

    private suspend fun openPositionInternal(
        cfg: BotConfig,
        side: TradeSide,
        livePrice: Double,
        nowMs: Long,
        reason: String
    ) {
        val contract = contractCatalog.findContract(cfg.symbol)
        val qtyStep = contract?.qtyStep ?: 0.001
        val minQty = contract?.minOrderQty ?: qtyStep
        val rawQty = (cfg.capitalUsdt * cfg.leverage) / livePrice
        val qtyStr = BybitV5TradingClient.roundQtyToStep(rawQty, qtyStep, minQty)
        if (qtyStr == null) {
            _uiState.value = _uiState.value.copy(
                lastError = "Сумма ${cfg.capitalUsdt} USDT с плечом ${cfg.leverage}x меньше minOrderQty ($minQty) для ${cfg.symbol}",
                statusText = "Объём меньше минимального лота биржи"
            )
            return
        }
        val roundedQty = qtyStr.toDouble()
        val feeSidePct = _uiState.value.feeRateSidePct.coerceIn(0.01, 0.25)

        val slPrice = if (cfg.stopLossEnabled) {
            targetPriceForNetPct(
                side = side,
                entryPrice = livePrice,
                targetNetPct = -cfg.stopLossPct,
                leverage = cfg.leverage,
                feeRateRoundTripPct = feeSidePct * 2.0,
                targetBasis = cfg.targetBasis
            )
        } else null

        if (cfg.mode.isLiveExchange) {
            val slot = vaultStore.loadCredentialSlot(cfg.mode)
            val tickSize = contract?.tickSize
            val slFormatted = slPrice?.let { BybitV5TradingClient.formatPriceToTick(it, tickSize) }
            val orderRes = withContext(Dispatchers.IO) {
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
            if (!orderRes.ok) {
                _uiState.value = _uiState.value.copy(
                    lastError = "Ордер отклонён (${cfg.mode.shortLabel}): ${orderRes.message}",
                    statusText = "Ошибка открытия ордера"
                )
                return
            }
            // Подтверждаем фактическую цену исполнения на бирже
            delay(350L)
            val exPos = withContext(Dispatchers.IO) {
                client.fetchOpenPosition(cfg.mode, slot, cfg.symbol)
            }.data
            val actualEntry = exPos?.avgPrice?.takeIf { it > 0.0 } ?: livePrice
            val actualQty = exPos?.size?.takeIf { it > 0.0 } ?: roundedQty
            val created = buildInitialPosition(
                id = orderRes.data.orEmpty().ifBlank { "live_${nowMs}" },
                mode = cfg.mode,
                symbol = cfg.symbol,
                side = side,
                qty = actualQty,
                entryPrice = actualEntry,
                markPrice = exPos?.markPrice ?: livePrice,
                leverage = cfg.leverage,
                marginMode = cfg.marginMode,
                feeRateSidePct = feeSidePct,
                cfg = cfg,
                openedAtMs = nowMs,
                reason = reason
            ).copy(
                estLiquidationPrice = exPos?.liqPrice ?: estimateLiquidationPrice(side, actualEntry, cfg.leverage, cfg.marginMode),
                serverTrailingStopPrice = exPos?.stopLoss ?: slPrice
            )
            _uiState.value = _uiState.value.copy(
                activePosition = created,
                lastError = null,
                statusText = "Открыта ${side.labelRu} ${cfg.symbol} @ $actualEntry",
                chartOverlay = buildChartOverlay(cfg.symbol, created, _uiState.value.recentTrades)
            )
        } else {
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
                feeRateSidePct = feeSidePct,
                cfg = cfg,
                openedAtMs = nowMs,
                reason = reason
            )
            _uiState.value = _uiState.value.copy(
                activePosition = created,
                lastError = null,
                statusText = "PAPER: открыта ${side.labelRu} ${cfg.symbol} @ $livePrice",
                chartOverlay = buildChartOverlay(cfg.symbol, created, _uiState.value.recentTrades)
            )
        }
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
            if (!res.ok && !forceMarket) {
                _uiState.value = _uiState.value.copy(
                    lastError = "Ошибка закрытия на бирже: ${res.message}"
                )
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

    private fun finalizeTradeRecord(pos: ActivePosition, exitPrice: Double, reason: String) {
        val cfg = _uiState.value.config
        val pnl = computeNetPnl(
            side = pos.side,
            qty = pos.qty,
            entryPrice = pos.entryPrice,
            markPrice = exitPrice,
            leverage = pos.leverage,
            usedMarginUsdt = pos.usedMarginUsdt,
            feeRateRoundTripPct = pos.feeRatePct,
            targetBasis = cfg.targetBasis
        )
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
        vaultStore.appendTrade(record)

        val todayUtc = utcDateString(record.closedAtMs)
        val curStatsMap = _uiState.value.statsByMode.toMutableMap()
        val prevModeStats = curStatsMap[pos.mode] ?: ModeWinrateStats(pos.mode)
        val nextTotal = prevModeStats.totalTrades + 1
        val nextWins = prevModeStats.wins + if (won) 1 else 0
        val nextLosses = prevModeStats.losses + if (won) 0 else 1
        val prevDayLoss = if (prevModeStats.lastDayUtc == todayUtc) prevModeStats.dailyRealizedLossUsdt else 0.0
        val nextDayLoss = if (pnl.netPnlUsdt < 0.0) prevDayLoss + abs(pnl.netPnlUsdt) else prevDayLoss
        val updatedModeStats = prevModeStats.copy(
            totalTrades = nextTotal,
            wins = nextWins,
            losses = nextLosses,
            winratePct = 100.0 * nextWins / nextTotal,
            totalNetPnlUsdt = prevModeStats.totalNetPnlUsdt + pnl.netPnlUsdt,
            dailyRealizedLossUsdt = nextDayLoss,
            lastDayUtc = todayUtc
        )
        curStatsMap[pos.mode] = updatedModeStats
        vaultStore.saveStatsByMode(curStatsMap)

        val nextTrades = (_uiState.value.recentTrades + record).takeLast(50)
        val nextBalance = if (cfg.mode == BotMode.PAPER) {
            computePaperBalance(cfg.paperStartBalanceUsdt, curStatsMap[BotMode.PAPER])
        } else {
            _uiState.value.walletBalanceUsdt
        }

        _uiState.value = _uiState.value.copy(
            activePosition = null,
            statsByMode = curStatsMap,
            recentTrades = nextTrades,
            walletBalanceUsdt = nextBalance,
            statusText = "${pos.mode.shortLabel} · закрыта ${if (won) "+" else ""}${"%.2f".format(Locale.US, pnl.netPnlUsdt)} USDT ($reason)",
            chartOverlay = buildChartOverlay(cfg.symbol, null, nextTrades)
        )
    }

    private fun refreshPositionMetrics(pos: ActivePosition, markPrice: Double, cfg: BotConfig): ActivePosition {
        val pnl = computeNetPnl(
            side = pos.side,
            qty = pos.qty,
            entryPrice = pos.entryPrice,
            markPrice = markPrice,
            leverage = pos.leverage,
            usedMarginUsdt = pos.usedMarginUsdt,
            feeRateRoundTripPct = pos.feeRatePct,
            targetBasis = cfg.targetBasis
        )
        val nextPeak = max(pos.peakNetPnlPct, pnl.netPnlPct)
        val nextFloorPct = if (nextPeak >= cfg.minNetProfitPct) {
            max(cfg.minNetProfitPct, nextPeak - cfg.trailingGivebackPct)
        } else {
            pos.trailingFloorPct
        }
        val nextFloorPrice = nextFloorPct?.let {
            targetPriceForNetPct(pos.side, pos.entryPrice, it, pos.leverage, pos.feeRatePct, cfg.targetBasis)
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
        feeRateSidePct: Double,
        cfg: BotConfig,
        openedAtMs: Long,
        reason: String
    ): ActivePosition {
        val roundTripFeePct = feeRateSidePct * 2.0
        val usedMargin = (entryPrice * qty) / leverage.coerceAtLeast(1)
        val tpMin = targetPriceForNetPct(side, entryPrice, cfg.minNetProfitPct, leverage, roundTripFeePct, cfg.targetBasis)
        val tpMax = targetPriceForNetPct(side, entryPrice, cfg.maxProfitPct, leverage, roundTripFeePct, cfg.targetBasis)
        val sl = if (cfg.stopLossEnabled) {
            targetPriceForNetPct(side, entryPrice, -cfg.stopLossPct, leverage, roundTripFeePct, cfg.targetBasis)
        } else null
        val liq = estimateLiquidationPrice(side, entryPrice, leverage, marginMode)
        val initialPnl = computeNetPnl(side, qty, entryPrice, markPrice, leverage, usedMargin, roundTripFeePct, cfg.targetBasis)
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
            entryReason = reason
        )
    }

    companion object {
        private const val TAG = "TradeBotEngine"

        fun computePaperBalance(startBalanceUsdt: Double, paperStats: ModeWinrateStats?): Double {
            val delta = paperStats?.totalNetPnlUsdt ?: 0.0
            return (startBalanceUsdt + delta).coerceAtLeast(0.0)
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
            if (report.freshness != DataFreshness.LIVE) {
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

            var side: TradeSide? = when {
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

            // Фильтр поглощения против направления входа
            if (!cfg.allowCounterAbsorption && report.absorption.detected) {
                val blockedByAbs = (side == TradeSide.BUY && report.absorption.side == AbsorptionSide.SELLER_ABSORPTION) ||
                    (side == TradeSide.SELL && report.absorption.side == AbsorptionSide.BUYER_ABSORPTION)
                if (blockedByAbs) {
                    return EntryDecision(false, null, "поглощение против ${side.labelRu} — вход отложен")
                }
            }

            // Политика разрешённого направления
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
            signalStillAligned: Boolean
        ): ExitDecision {
            if (pos.netPnlPct >= cfg.maxProfitPct) {
                return ExitDecision(true, "Цель ${formatSignedPct(cfg.maxProfitPct)} достигнута")
            }
            val floor = pos.trailingFloorPct
            if (floor != null && pos.peakNetPnlPct >= cfg.minNetProfitPct && pos.netPnlPct <= floor) {
                return ExitDecision(true, "Трейлинг-фиксация ${formatSignedPct(floor)}")
            }
            if (cfg.stopLossEnabled && pos.netPnlPct <= -cfg.stopLossPct) {
                return ExitDecision(true, "Stop-Loss ${formatSignedPct(-cfg.stopLossPct)}")
            }
            val heldMs = (nowMs - pos.openedAtMs).coerceAtLeast(0L)
            if (heldMs >= cfg.maxHoldWaitMin * 60_000L && pos.netPnlPct < cfg.minNetProfitPct && !signalStillAligned) {
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
