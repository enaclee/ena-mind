package com.enamind.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.CandlestickChart
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enamind.ai.bot.ActivePosition
import com.enamind.ai.bot.BotMode
import com.enamind.ai.bot.BotTradeRecord
import com.enamind.ai.bot.BotUiState
import com.enamind.ai.bot.HudGlowZone
import com.enamind.ai.core.AppState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TradeBotScreen(state: AppState) {
    val uiState by state.botEngine.uiState.collectAsState()
    var showContractPicker by remember { mutableStateOf(false) }
    var showMainnetConfirm by remember { mutableStateOf(false) }

    if (showContractPicker) {
        ContractPickerDialog(
            state = state,
            currentSymbol = uiState.config.symbol,
            onSelect = { state.selectBotSymbol(it) },
            onDismiss = { showContractPicker = false }
        )
    }

    if (showMainnetConfirm) {
        AlertDialog(
            onDismissRequest = { showMainnetConfirm = false },
            icon = {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = Color.EnaRed
                )
            },
            title = { Text("Запуск на Bybit Mainnet (РЕАЛ)") },
            text = {
                Text(
                    "Бот будет выставлять реальные рыночные ордера на паре ${uiState.config.symbol} " +
                        "(капитал ${uiState.config.capitalUsdt} USDT, плечо ${uiState.config.leverage}x, " +
                        if (uiState.config.stopLossEnabled) "SL -${uiState.config.stopLossPct}%)."
                        else "Stop-Loss ОТКЛЮЧЁН!)"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showMainnetConfirm = false
                        state.botEngine.startBot(mainnetExplicitConfirmed = true)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.EnaRed,
                        contentColor = Color.White
                    )
                ) {
                    Text("Подтвердить Mainnet")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMainnetConfirm = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("trade_bot_screen"),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "bot_header") {
            BotHeaderControlCard(
                state = state,
                uiState = uiState,
                onPickPair = { showContractPicker = true },
                onStartClick = {
                    if (uiState.config.mode == BotMode.BYBIT_MAINNET && !uiState.mainnetConfirmedSession) {
                        showMainnetConfirm = true
                    } else {
                        state.botEngine.startBot(mainnetExplicitConfirmed = true)
                    }
                }
            )
        }

        uiState.activePosition?.let { pos ->
            item(key = "bot_active_pos") {
                BotActivePositionCard(
                    pos = pos,
                    slEnabled = uiState.config.stopLossEnabled,
                    maxTranches = uiState.config.maxTranches
                )
            }
        }

        item(key = "bot_mode_keys") {
            BotModeAndKeysCard(state = state, uiState = uiState)
        }

        item(key = "bot_strategy") {
            BotStrategyRiskCard(state = state, cfg = uiState.config, uiState = uiState)
        }

        item(key = "bot_hud_bg") {
            BotHudAndBackgroundCard(state = state, uiState = uiState)
        }

        item(key = "bot_stats") {
            BotWinrateStatsCard(uiState = uiState)
        }

        if (uiState.recentTrades.isNotEmpty()) {
            item(key = "bot_trades_header") {
                Text(
                    "Последние сделки (${uiState.recentTrades.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            items(uiState.recentTrades.takeLast(12).reversed(), key = { "${it.id}_${it.closedAtMs}" }) { tr ->
                BotTradeRecordRow(tr)
            }
        }
    }
}

@Composable
private fun BotHeaderControlCard(
    state: AppState,
    uiState: BotUiState,
    onPickPair: () -> Unit,
    onStartClick: () -> Unit
) {
    val cfg = uiState.config
    val amber = enaAmberText()
    val green = enaGreenText()
    val modeColor = when (cfg.mode) {
        BotMode.PAPER -> MaterialTheme.colorScheme.primary
        BotMode.BYBIT_DEMO -> green
        BotMode.BYBIT_TESTNET -> amber
        BotMode.BYBIT_MAINNET -> Color.EnaRed
    }

    BotSectionCard(
        title = "ТОРГОВЫЙ БОТ ENΛVERSE",
        subtitle = "Order Flow + Radar · ${cfg.mode.labelRu}",
        icon = Icons.Outlined.SmartToy,
        badgeText = if (uiState.running) "РАБОТАЕТ" else "ПАУЗА",
        badgeColor = if (uiState.running) green else modeColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onPickPair,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("bot_pick_pair"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("${cfg.symbol} · ${cfg.leverage}x", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("Баланс", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        uiState.formattedBalance(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        val utaTxt = when (uiState.isUtaAccount) {
            true -> "UTA"
            false -> "Classic"
            null -> "—"
        }
        val feeLabel = buildString {
            append("Maker ${botFmt("%.3f%%", uiState.makerFeePct)} / Taker ${botFmt("%.4f%%", uiState.takerFeePct)}")
            if (uiState.isTradFiFeeProfile) append(" (TradFi)")
        }
        Text(
            "Счёт: $utaTxt · $feeLabel · Эфф. маржа ${botFmt("%.2f", uiState.effectiveCapitalUsdt)}/${botFmt("%.0f", cfg.capitalUsdt)} $",
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Стратегия: ${if (cfg.enacoreBridge) "ENΛCORE" else "Ручной режим"} · Статус: ${uiState.statusText}",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Сигнал / Ожидание: ${uiState.waitReasonBadge}",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (uiState.anchorsSummary.isNotBlank()) {
                    Text(
                        "Якори: ${uiState.anchorsSummary}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (uiState.isNetworkRateLimited) {
            Text(
                "⏳ Сработал лимит API (HTTP 429) — включена пауза защиты от бана",
                fontSize = 10.5.sp,
                color = amber
            )
        }

        listOfNotNull(
            uiState.venueSyncMismatchWarning,
            uiState.minNotionalWarning,
            uiState.trailingNoiseWarning
        ).forEach { warn ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = amber.copy(alpha = 0.12f),
                border = BorderStroke(0.8.dp, amber.copy(alpha = 0.45f))
            ) {
                Text("⚠ $warn", fontSize = 10.5.sp, color = amber, modifier = Modifier.padding(9.dp))
            }
        }

        uiState.lastError?.let { err ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color.EnaRed.copy(alpha = 0.12f),
                border = BorderStroke(0.8.dp, Color.EnaRed.copy(alpha = 0.45f))
            ) {
                Text(err, fontSize = 11.sp, color = Color.EnaRed, modifier = Modifier.padding(10.dp))
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!uiState.running) {
                Button(
                    onClick = onStartClick,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("bot_start_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (cfg.mode == BotMode.BYBIT_MAINNET) Color.EnaRed else MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("ЗАПУСТИТЬ БОТА", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            } else {
                OutlinedButton(
                    onClick = { state.botEngine.stopBotOnly() },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("bot_stop_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("ПАУЗА БОТА", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        if (uiState.running || uiState.activePosition != null) {
            Button(
                onClick = { state.botEngine.stopAndCloseMarket() },
                enabled = !uiState.closing,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("bot_emergency_stop_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.EnaRed,
                    contentColor = Color.White
                )
            ) {
                Text(
                    if (uiState.closing) "ЗАКРЫТИЕ ПО РЫНКУ…" else "ОСТАНОВИТЬ БОТА И ЗАКРЫТЬ ПО РЫНКУ",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun BotActivePositionCard(pos: ActivePosition, slEnabled: Boolean, maxTranches: Int) {
    val green = enaGreenText()
    val amber = enaAmberText()
    val pnlColor = if (pos.netPnlUsdt >= 0) green else Color.EnaRed

    BotSectionCard(
        title = "ОТКРЫТАЯ ПОЗИЦИЯ · ${pos.symbol}",
        subtitle = "${pos.side.labelRu} · ${pos.leverage}x ${pos.marginMode.labelRu} · Транш ${pos.trancheCount}/$maxTranches",
        icon = Icons.Outlined.CandlestickChart,
        badgeText = "${botFmt("%+.2f%%", pos.netPnlPct)} (${botFmt("%+.2f", pos.netPnlUsdt)} $)",
        badgeColor = pnlColor
    ) {
        BotRow("Средняя (Blended) → Марк", "${botFmtPrice(pos.entryPrice)} → ${botFmtPrice(pos.markPrice)}")
        BotRow("Объём / Маржа", "${pos.qty} · ${botFmt("%.2f", pos.usedMarginUsdt)} USDT")
        BotRow(
            "Пик Net PnL / Комиссии",
            "${botFmt("%+.2f%%", pos.peakNetPnlPct)} · вход ${botFmt("%.3f", pos.paidEntryFeesUsdt)}$ / все ${botFmt("%.3f", pos.estFeeUsdt)}$"
        )
        if (pos.peelDone) {
            BotRow("Скальп-фиксация 50%", "Выполнена (остаток в сопровождении)", green)
        }
        BotRow("Цель мин / макс", "${botFmtPrice(pos.tpMinPrice)} / ${botFmtPrice(pos.tpMaxPrice)}", green)
        pos.trailFloorPrice?.let { tf ->
            BotRow("Трал-фиксация (≥${botFmt("%.2f%%", pos.trailingFloorPct ?: 0.0)})", botFmtPrice(tf), amber)
        }
        if (slEnabled && pos.stopLossPrice != null) {
            BotRow("Stop-Loss", botFmtPrice(pos.stopLossPrice), Color.EnaRed)
        } else {
            BotRow(
                "Stop-Loss ОТКЛЮЧЁН · Ликвидация ≈",
                pos.estLiquidationPrice?.let { botFmtPrice(it) } ?: "Cross margin",
                amber
            )
        }
        pos.serverTrailingStopPrice?.let { srvSl ->
            BotRow("Защита на сервере Bybit", botFmtPrice(srvSl), MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun BotWinrateStatsCard(uiState: BotUiState) {
    val st = uiState.activeModeStats
    val amber = enaAmberText()
    val green = enaGreenText()
    val wrColor = when (st.glowZone) {
        HudGlowZone.RED -> Color.EnaRed
        HudGlowZone.YELLOW -> amber
        HudGlowZone.GREEN -> green
        HudGlowZone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    BotSectionCard(
        title = "СТАТИСТИКА WINRATE (${st.mode.shortLabel})",
        subtitle = "Независимый учёт по каждому из 4 режимов",
        icon = Icons.Outlined.Assessment,
        badgeText = if (st.totalTrades > 0) "WR ${botFmt("%.1f%%", st.winratePct)}" else "0 СДЕЛОК",
        badgeColor = wrColor
    ) {
        BotRow("Сделок / Плюс / Минус", "${st.totalTrades} · ${st.wins} / ${st.losses}", wrColor)
        BotRow(
            "Суммарный Net PnL",
            "${botFmt("%+.2f", st.totalNetPnlUsdt)} USDT",
            if (st.totalNetPnlUsdt >= 0) green else Color.EnaRed
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BotMode.entries.forEach { m ->
                val ms = uiState.statsByMode[m]
                val c = when (ms?.glowZone ?: HudGlowZone.NEUTRAL) {
                    HudGlowZone.RED -> Color.EnaRed
                    HudGlowZone.YELLOW -> amber
                    HudGlowZone.GREEN -> green
                    HudGlowZone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(m.shortLabel, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if ((ms?.totalTrades ?: 0) > 0) "${botFmt("%.0f%%", ms!!.winratePct)} (${ms.totalTrades})" else "—",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = c
                    )
                }
            }
        }
    }
}

@Composable
private fun BotTradeRecordRow(tr: BotTradeRecord) {
    val c = if (tr.won) enaGreenText() else Color.EnaRed
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${tr.mode.shortLabel} · ${tr.symbol} · ${tr.side.labelRu} ${tr.leverage}x",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${botFmt("%+.2f%%", tr.netPnlPct)} (${botFmt("%+.2f", tr.netPnlUsdt)}$)",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = c
                )
            }
            Text(
                "${botFmtPrice(tr.entryPrice)} → ${botFmtPrice(tr.exitPrice)} · ${tr.exitReason} · ${botFmtTime(tr.closedAtMs)}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun BotSectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    badgeText: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.9.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(7.dp).size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!badgeText.isNullOrBlank()) {
                    Surface(shape = CircleShape, color = badgeColor.copy(alpha = 0.14f)) {
                        Text(
                            badgeText,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun BotRow(label: String, value: String, valueColor: Color? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun botFmt(fmt: String, v: Double): String =
    if (!v.isFinite()) "—" else runCatching { String.format(Locale.US, fmt, v) }.getOrDefault("—")

private fun botFmtPrice(p: Double): String = when {
    !p.isFinite() || p <= 0.0 -> "—"
    p >= 1000.0 -> botFmt("%,.1f", p)
    p >= 10.0 -> botFmt("%,.2f", p)
    else -> botFmt("%.4f", p)
}

private fun botFmtTime(tsMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(tsMs))
