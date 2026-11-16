package com.enamind.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.CandlestickChart
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enamind.ai.bot.BotUiState
import com.enamind.ai.core.AppState
import com.enamind.ai.core.Settings
import com.enamind.ai.orderflow.AbsorptionSide
import com.enamind.ai.orderflow.DataFreshness
import com.enamind.ai.orderflow.FootprintLevel
import com.enamind.ai.orderflow.LevelAttackPhase
import com.enamind.ai.orderflow.MarketDominance
import com.enamind.ai.orderflow.OrderFlowReport
import com.enamind.ai.orderflow.ReliabilityGrade
import com.enamind.ai.orderflow.ScenarioCase
import java.util.Locale

/**
 * ENA — ORDER FLOW & ABSORPTION INTELLIGENCE.
 * Содержит 5 областей:
 *  1. Текущее состояние рынка (+ выбор любого контракта и ТФ);
 *  2. График с Order Flow & Bot overlay (уровни S/R, зоны поглощения, ликвидность, уровни бота);
 *  3. Footprint (кластеры Bid/Ask по ценам, Delta/%, CVD, POC, Stacked Imbalance);
 *  4. Анализ стакана (DOM: исполнение vs снятие, OFI, пополнение, удержание стен);
 *  5. Объяснение ENA человеческим языком + Сценарии A и B + Оценка достоверности и калибровки.
 */
@Composable
fun OrderFlowScreen(state: AppState) {
    val market by state.market.collectAsState()
    val klines by state.klines.collectAsState()
    val lastBar by state.lastBar.collectAsState()
    val report by state.orderFlow.report.collectAsState()
    val botState by state.botEngine.uiState.collectAsState()
    val favorites by state.favoriteSymbols.collectAsState()
    var showContractPicker by remember { mutableStateOf(false) }

    if (showContractPicker) {
        ContractPickerDialog(
            state = state,
            currentSymbol = market.symbol,
            onSelect = { state.changeSymbol(it) },
            onDismiss = { showContractPicker = false }
        )
    }

    val ivLabel = Settings.INTERVALS.firstOrNull { it.first == market.interval }?.second ?: market.interval
    val quickPairs = remember(favorites, market.symbol) {
        (listOf(market.symbol) + favorites.toList() + Settings.SYMBOLS).distinct()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("order_flow_screen"),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Выбор пары (все контракты биржи + избранное) и таймфрейма
        item(key = "of_pair_selector") {
            val chipColors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { showContractPicker = true },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("of_all_contracts_button"),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = "Поиск всех контрактов биржи",
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text("Все контракты", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        colors = chipColors
                    )
                    quickPairs.forEach { sym ->
                        key(sym) {
                            FilterChip(
                                selected = market.symbol == sym,
                                onClick = { state.changeSymbol(sym) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("of_sym_$sym"),
                                label = { Text(sym.removeSuffix("USDT"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                colors = chipColors
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Settings.INTERVALS.forEach { (iv, label) ->
                        key(iv) {
                            FilterChip(
                                selected = market.interval == iv,
                                onClick = { state.changeInterval(iv) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("of_iv_$iv"),
                                label = { Text(label, fontSize = 11.sp) },
                                colors = chipColors
                            )
                        }
                    }
                }
            }
        }

        // Область 1: Текущее состояние рынка
        item(key = "of_market_state") {
            MarketStateAreaCard(state = state, report = report, priceFallback = market.price)
        }

        // Область 2: График с Order Flow & Bot overlay
        item(key = "of_chart_overlay") {
            val hasBotOnSymbol = botState.chartOverlay.symbol.equals(market.symbol, ignoreCase = true) &&
                (botState.chartOverlay.entryPrice != null || botState.chartOverlay.markers.isNotEmpty())
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KlineChart(
                    bars = klines,
                    lastBar = lastBar,
                    theme = if (state.settings.theme == Settings.Theme.WHITE) "white" else "black",
                    symbol = market.symbol,
                    intervalLabel = ivLabel,
                    sourceLabel = report.exchangeSource,
                    orderFlowOverlay = report.overlay,
                    botOverlay = botState.chartOverlay
                )
                OverlayLegendRow(hasBotOnSymbol)
                BotSymbolOverlayStrip(symbol = market.symbol, botState = botState)
            }
        }

        // Область 3: Footprint (Кластеры по ценам)
        item(key = "of_footprint") {
            FootprintAreaCard(report)
        }

        // Область 4: Анализ стакана (DOM) + Атака уровня
        item(key = "of_dom_attack") {
            DomAndLevelAttackAreaCard(report)
        }

        // Область 5: Объяснение ENA + Сценарии A/B + Оценка достоверности
        item(key = "of_explanation_scenarios") {
            ExplanationAndScenariosAreaCard(report)
        }
    }
}

@Composable
private fun OrderFlowSectionCard(
    indexLabel: String,
    title: String,
    subtitle: String,
    icon: ImageVector,
    badgeText: String,
    badgeColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.9.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(7.dp)
                            .size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "$indexLabel. $title",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        subtitle,
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = badgeColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        badgeText,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun MarketStateAreaCard(
    state: AppState,
    report: OrderFlowReport,
    priceFallback: Double?
) {
    val amber = enaAmberText()
    val (domColor, domBadge) = when (report.dominance) {
        MarketDominance.BUYERS_DOMINATE, MarketDominance.CONFIRMED_BREAKOUT ->
            Color.EnaGreen to "ПОКУПАТЕЛИ"
        MarketDominance.SELLERS_DOMINATE, MarketDominance.CONFIRMED_REJECTION ->
            Color.EnaRed to "ПРОДАВЦЫ"
        MarketDominance.POSSIBLE_ABSORPTION ->
            amber to "ПОГЛОЩЕНИЕ"
        MarketDominance.CONFLICT ->
            amber to "КОНФЛИКТ"
        MarketDominance.CONTESTED ->
            MaterialTheme.colorScheme.primary to "БОРЬБА"
        MarketDominance.INSUFFICIENT_DATA ->
            MaterialTheme.colorScheme.onSurfaceVariant to "НЕТ ДАННЫХ"
    }

    val (freshColor, freshLabel) = when (report.freshness) {
        DataFreshness.LIVE -> Color.EnaGreen to report.freshness.labelRu
        DataFreshness.STALE -> amber to report.freshness.labelRu
        DataFreshness.DESYNC -> Color.EnaRed to report.freshness.labelRu
        DataFreshness.INSUFFICIENT -> MaterialTheme.colorScheme.onSurfaceVariant to report.freshness.labelRu
    }

    OrderFlowSectionCard(
        indexLabel = "1",
        title = "ТЕКУЩЕЕ СОСТОЯНИЕ РЫНКА",
        subtitle = "ORDER FLOW & ABSORPTION · ${report.symbol} · ${report.exchangeSource}",
        icon = Icons.Outlined.Analytics,
        badgeText = domBadge,
        badgeColor = domColor
    ) {
        // Живая полоса Dynamic Market Glow синхронизированная с реальным преимуществом
        DynamicMarketGlowStrip(state = state)

        val livePrice = report.price ?: priceFallback
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    report.dominance.labelRu,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = domColor
                )
                Text(
                    "Атака уровня: ${report.levelAttack.phase.labelRu}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                livePrice?.takeIf { it.isFinite() && it > 0 }?.let { ofFmtPrice(it) } ?: "—",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Статус актуальности/рассинхронизации потока
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = freshColor.copy(alpha = 0.11f),
            border = BorderStroke(0.75.dp, freshColor.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Поток: $freshLabel · задержка DOM ${if (report.dom.lagMs >= 0) "${report.dom.lagMs}мс" else "—"}",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = freshColor
                )
                report.freshnessWarning?.let { warn ->
                    Text(
                        warn,
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayLegendRow(showBotChip: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LegendChip("Поддержка", Color(0xFF00E5A0))
        LegendChip("Сопротивление", Color(0xFFFF4D5E))
        LegendChip("Зона поглощения", Color(0xFFF59E0B))
        LegendChip("Ликвидность DOM", Color(0xFF60A5FA))
        LegendChip("Неудавш. пробой", Color(0xFFFF6D00))
        LegendChip("Событие", Color(0xFFA855F7))
        if (showBotChip) {
            LegendChip("Уровни/сделки бота", Color(0xFF3B82F6))
        }
    }
}

@Composable
private fun BotSymbolOverlayStrip(symbol: String, botState: BotUiState) {
    val active = botState.activePosition?.takeIf { it.symbol.equals(symbol, ignoreCase = true) }
    val symbolTrades = botState.recentTrades.filter { it.symbol.equals(symbol, ignoreCase = true) }.takeLast(3).reversed()
    if (active == null && symbolTrades.isEmpty()) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (active != null) {
                val pnlColor = if (active.netPnlUsdt >= 0) enaGreenText() else Color.EnaRed
                Text(
                    "БОТ (${active.mode.shortLabel}) · ${active.side.labelRu} @ ${ofFmtPrice(active.entryPrice)} · " +
                        "Net ${ofFmt("%+.2f%%", active.netPnlPct)} (${ofFmt("%+.2f", active.netPnlUsdt)}$)" +
                        (active.trailFloorPrice?.let { " · Трал ${ofFmtPrice(it)}" } ?: ""),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = pnlColor
                )
            }
            if (symbolTrades.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    symbolTrades.forEach { tr ->
                        val c = if (tr.won) enaGreenText() else Color.EnaRed
                        Text(
                            "${tr.side.labelRu} ${ofFmt("%+.2f%%", tr.netPnlPct)} (${tr.mode.shortLabel})",
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = c
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendChip(label: String, color: Color) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(5.dp))
            Text(label, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FootprintAreaCard(report: OrderFlowReport) {
    val fp = report.footprint
    val fpColor = when {
        !fp.sufficientData -> MaterialTheme.colorScheme.onSurfaceVariant
        fp.netDelta > 0 -> Color.EnaGreen
        fp.netDelta < 0 -> Color.EnaRed
        else -> MaterialTheme.colorScheme.primary
    }

    OrderFlowSectionCard(
        indexLabel = "3",
        title = "FOOTPRINT (КЛАСТЕРЫ ПО ЦЕНАМ)",
        subtitle = "Агрессивные покупки vs продажи на каждом ценовом уровне",
        icon = Icons.Outlined.CandlestickChart,
        badgeText = if (fp.sufficientData) "${fp.tradesAnalyzed} сделок" else "ОЖИДАНИЕ",
        badgeColor = fpColor
    ) {
        if (!fp.sufficientData) {
            Text(
                fp.note,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            OfMetricRow("Ask (агресс. покупки)", ofFmtQty(fp.totalAskVolume), Color.EnaGreen)
            OfMetricRow("Bid (агресс. продажи)", ofFmtQty(fp.totalBidVolume), Color.EnaRed)
            OfMetricRow(
                "Net Delta / %",
                "${ofFmtSignedQty(fp.netDelta)} (${ofFmt("%+.1f%%", fp.deltaPct)})",
                if (fp.netDelta >= 0) Color.EnaGreen else Color.EnaRed
            )
            OfMetricRow(
                "CVD / Наклон CVD",
                "${ofFmtSignedQty(fp.cvd)} (${ofFmtSignedQty(fp.cvdSlope)})",
                if (fp.cvdSlope >= 0) Color.EnaGreen else Color.EnaRed
            )
            OfMetricRow("Volume POC", fp.pocPrice?.let { ofFmtPrice(it) } ?: "—")
            OfMetricRow(
                "Stacked Imbalance",
                "Ask ${fp.stackedAskCount} · Bid ${fp.stackedBidCount} · аномалий ${fp.anomaliesCount}"
            )

            if (fp.levels.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "Ценовые уровни (Bid × Ask · Delta)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                val maxLevelVol = fp.levels.maxOfOrNull { it.totalVolume }?.coerceAtLeast(1e-9) ?: 1.0
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    fp.levels.take(10).forEach { lvl ->
                        FootprintLevelRow(lvl, maxLevelVol)
                    }
                }
            }
        }
    }
}

@Composable
private fun FootprintLevelRow(lvl: FootprintLevel, maxTotal: Double) {
    val deltaColor = if (lvl.delta >= 0) Color.EnaGreen else Color.EnaRed
    val tag = buildString {
        if (lvl.isPoc) append("POC ")
        if (lvl.imbalanceType == "ASK_IMBALANCE") append("IMB↑ ")
        if (lvl.imbalanceType == "BID_IMBALANCE") append("IMB↓ ")
        if (lvl.isClusterAnomaly) append("CLUST")
    }.trim()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (lvl.isPoc) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.background
        },
        border = BorderStroke(
            0.6.dp,
            if (lvl.isPoc) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ofFmtPrice(lvl.priceLevel),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(84.dp)
                )
                Text(
                    "${ofFmtQty(lvl.bidVolume)} × ${ofFmtQty(lvl.askVolume)}",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (tag.isNotBlank()) {
                    Text(
                        tag,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = enaAmberText(),
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                Text(
                    ofFmtSignedQty(lvl.delta),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = deltaColor
                )
            }
            val frac = (lvl.totalVolume / maxTotal).toFloat().coerceIn(0.03f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac)
                    .height(3.dp)
                    .background(deltaColor.copy(alpha = 0.65f), CircleShape)
            )
        }
    }
}

@Composable
private fun DomAndLevelAttackAreaCard(report: OrderFlowReport) {
    val dom = report.dom
    val abs = report.absorption
    val atk = report.levelAttack
    val amber = enaAmberText()

    val badgeColor = when {
        !dom.sufficientData -> MaterialTheme.colorScheme.onSurfaceVariant
        abs.confirmed -> amber
        dom.normalizedOfi > 0.15 -> Color.EnaGreen
        dom.normalizedOfi < -0.15 -> Color.EnaRed
        else -> MaterialTheme.colorScheme.primary
    }

    OrderFlowSectionCard(
        indexLabel = "4",
        title = "АНАЛИЗ СТАКАНА (DOM) И АТАК УРОВНЕЙ",
        subtitle = "Исполнение ≠ снятие (±250мс), пополнение, OFI и удержание стен",
        icon = Icons.Outlined.Layers,
        badgeText = if (dom.synchronized) "SYNC" else "WAIT",
        badgeColor = badgeColor
    ) {
        if (!dom.sufficientData) {
            Text(
                dom.note,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            OfMetricRow(
                "Order Flow Imbalance (OFI)",
                "${ofFmt("%+.2f", dom.ofi)} (норм. ${ofFmt("%+.2f", dom.normalizedOfi)})",
                if (dom.normalizedOfi >= 0) Color.EnaGreen else Color.EnaRed
            )
            OfMetricRow(
                "Исполнено / Отменено",
                "${ofFmtQty(dom.executedQty)} исп. · ${ofFmtQty(dom.cancelledQty)} снято"
            )
            OfMetricRow(
                "Появление / Снятие в сек",
                "+${ofFmtQty(dom.addRatePerSec)}/с · -${ofFmtQty(dom.cancelRatePerSec)}/с"
            )
            OfMetricRow(
                "Пополнение / Устойчивость",
                "${dom.replenishmentEvents} соб. (${ofFmt("%.2f", dom.replenishmentRate)}/с) · удерж. ${ofFmt("%.0f%%", dom.levelPersistenceRatio * 100)}"
            )
            OfMetricRow(
                "Баланс стакана (Bid/Ask)",
                "${ofFmt("%+.1f%%", dom.bookImbalancePct)}",
                if (dom.bookImbalancePct >= 0) Color.EnaGreen else Color.EnaRed
            )
            if (dom.liquidityPullDetected && dom.liquidityPullNote.isNotBlank()) {
                Text(
                    "⚠ ${dom.liquidityPullNote}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = amber
                )
            }
            if (dom.migrationNote.isNotBlank()) {
                Text(
                    dom.migrationNote,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (dom.largeBids.isNotEmpty() || dom.largeAsks.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "Крупные заявки (стены стакана)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("BID стены", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.EnaGreen)
                        if (dom.largeBids.isEmpty()) {
                            Text("—", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            dom.largeBids.take(3).forEach { z ->
                                Text(
                                    "${ofFmtPrice(z.price)} · ${ofFmtQty(z.qty)} (${z.persistenceSec.toInt()}с)",
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("ASK стены", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.EnaRed)
                        if (dom.largeAsks.isEmpty()) {
                            Text("—", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            dom.largeAsks.take(3).forEach { z ->
                                Text(
                                    "${ofFmtPrice(z.price)} · ${ofFmtQty(z.qty)} (${z.persistenceSec.toInt()}с)",
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // Поглощение и атака уровня S/R
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            "Детектор поглощения и атаки уровня",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            abs.side.labelRu,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = when (abs.side) {
                AbsorptionSide.BUYER_ABSORPTION -> Color.EnaGreen
                AbsorptionSide.SELLER_ABSORPTION -> Color.EnaRed
                AbsorptionSide.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        OfMetricRow(
            "Уровень / Дистанция",
            "${atk.levelPrice?.let { ofFmtPrice(it) } ?: "—"} (${atk.distancePct?.let { ofFmt("%.2f%%", it) } ?: "—"})"
        )
        OfMetricRow(
            "Касания / Фаза",
            "${atk.touchCount} · ${atk.phase.labelRu}"
        )
        OfMetricRow(
            "Absorption / Progress Eff.",
            "${ofFmt("%.2f", abs.absorptionRatio)} · Эффект. ${ofFmt("%.2f", abs.priceProgressEfficiency)}"
        )
        OfMetricRow(
            "Ослабление / Удержание",
            "${ofFmt("%.0f%%", abs.attackExhaustion * 100)} · ${abs.holdDurationSec.toInt()}с · ${abs.reliability.labelRu}"
        )
    }
}

@Composable
private fun ExplanationAndScenariosAreaCard(report: OrderFlowReport) {
    val conf = report.confidence
    val amber = enaAmberText()
    val confColor = when (conf.grade) {
        ReliabilityGrade.CONFIRMED -> Color.EnaGreen
        ReliabilityGrade.STRONG -> MaterialTheme.colorScheme.primary
        ReliabilityGrade.MODERATE -> amber
        ReliabilityGrade.WEAK -> amber
        ReliabilityGrade.INSUFFICIENT -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    OrderFlowSectionCard(
        indexLabel = "5",
        title = "ОБЪЯСНЕНИЕ ENA, СЦЕНАРИИ И ДОСТОВЕРНОСТЬ",
        subtitle = "Без автоматических BUY/SELL — интерпретация борьбы сторон",
        icon = Icons.Outlined.Psychology,
        badgeText = conf.grade.labelRu,
        badgeColor = confColor
    ) {
        // Человекопонятное объяснение ENA
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Объяснение текущей ситуации",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    report.explanationText,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Оба сценария одновременно (A — разворот, B — пробой)
        report.scenarioA?.let { scA ->
            ScenarioCaseCard(scenario = scA, accent = amber)
        }
        report.scenarioB?.let { scB ->
            ScenarioCaseCard(scenario = scB, accent = MaterialTheme.colorScheme.primary)
        }

        // Система оценки, подтверждающие и противоречащие признаки, калибровка
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = confColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Достоверность и калибровка (${conf.grade.labelRu})",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = confColor
                    )
                }
                OfMetricRow("Совпавших факторов", "${conf.alignedFactorsCount}")
                OfMetricRow(
                    "Вероятность поглощения",
                    conf.calibratedAbsorptionProbPct?.let { "${ofFmt("%.0f%%", it)} (n=${conf.historicalSampleSize})" }
                        ?: "недостаточно выборки"
                )
                OfMetricRow(
                    "Статистика исхода (разворот / пробой)",
                    if (conf.calibratedReversalProbPct != null && conf.calibratedBreakoutProbPct != null) {
                        "${ofFmt("%.0f%%", conf.calibratedReversalProbPct)} разв. / ${ofFmt("%.0f%%", conf.calibratedBreakoutProbPct)} проб."
                    } else {
                        "не откалибровано"
                    }
                )
                Text(
                    conf.dataQualityNote,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (conf.historicalInsight.isNotBlank()) {
                    Text(
                        conf.historicalInsight,
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (report.absorption.supportingFactors.isNotEmpty()) {
                    Text("Подтверждают:", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color.EnaGreen)
                    report.absorption.supportingFactors.forEach { f ->
                        Text("✓ $f", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (report.absorption.conflictingFactors.isNotEmpty()) {
                    Text("Противоречат:", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color.EnaRed)
                    report.absorption.conflictingFactors.forEach { f ->
                        Text("✕ $f", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScenarioCaseCard(scenario: ScenarioCase, accent: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(0.8.dp, accent.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Timeline,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    scenario.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            }
            if (scenario.proFactors.isNotEmpty()) {
                Text("За сценарий:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.EnaGreen)
                scenario.proFactors.forEach { Text("• $it", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface) }
            }
            if (scenario.contraFactors.isNotEmpty()) {
                Text("Против сценария:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.EnaRed)
                scenario.contraFactors.forEach { Text("• $it", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface) }
            }
            if (scenario.needsConfirmation.isNotEmpty()) {
                Text("Что нужно для подтверждения:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                scenario.needsConfirmation.forEach { Text("• $it", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (scenario.invalidationConditions.isNotEmpty()) {
                Text("Когда теряет актуальность:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = enaAmberText())
                scenario.invalidationConditions.forEach { Text("• $it", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun OfMetricRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun ofFmt(fmt: String, v: Double): String =
    if (!v.isFinite()) "—" else runCatching { String.format(Locale.US, fmt, v) }.getOrDefault("—")

private fun ofFmtPrice(p: Double): String = when {
    !p.isFinite() || p <= 0.0 -> "—"
    p >= 1000.0 -> ofFmt("%,.1f", p)
    p >= 10.0 -> ofFmt("%,.2f", p)
    else -> ofFmt("%.4f", p)
}

private fun ofFmtQty(q: Double): String = when {
    !q.isFinite() -> "—"
    q >= 1000.0 -> ofFmt("%.1f", q)
    q >= 10.0 -> ofFmt("%.2f", q)
    else -> ofFmt("%.3f", q)
}

private fun ofFmtSignedQty(q: Double): String = when {
    !q.isFinite() -> "—"
    kotlin.math.abs(q) >= 1000.0 -> ofFmt("%+.1f", q)
    kotlin.math.abs(q) >= 10.0 -> ofFmt("%+.2f", q)
    else -> ofFmt("%+.3f", q)
}
