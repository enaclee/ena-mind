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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.enamind.ai.core.AppState
import com.enamind.ai.intelligence.BiasDirection
import com.enamind.ai.intelligence.ChainRadarSnapshot
import com.enamind.ai.intelligence.SentinelEvent
import com.enamind.ai.intelligence.SentinelSeverity
import com.enamind.ai.intelligence.TrendLogicSummary
import com.enamind.ai.intelligence.TrendReasoningEngine
import com.enamind.ai.intelligence.UnifiedIntelligenceReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * РАДАР v8.3 — читаемый аналитический блок:
 *   1. CHAIN RADAR — разведка блокчейна (что происходит с деньгами до графика);
 *   2. CRYPTO SENTINEL — система обнаружения аномалий и событий риска;
 *   3. РАЗУМ И БОТЫ — причинно-следственная логика тренда и согласие контуров.
 */
@Composable
fun RadarSentinelScreen(state: AppState) {
    val market by state.market.collectAsState()
    val intel by state.engine.intelligence.collectAsState()
    val refreshing by state.engine.radarRefreshing.collectAsState()
    var filterTab by rememberSaveable { mutableIntStateOf(0) } // 0 Всё | 1 Radar | 2 Sentinel | 3 Разум

    Column(modifier = Modifier.fillMaxSize()) {
        // Верхняя панель экрана
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Разведка и Разум · ${market.symbol}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        intel?.let {
                            "обновлено ${fmtTime(it.computedAtMs)} · ${if (it.pythonEngineOk) "Kotlin + Python" else "Kotlin-боты"}"
                        } ?: "сбор ончейн-потоков и событий…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).padding(end = 4.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = { state.refreshRadarNow() },
                    modifier = Modifier.testTag("radar_refresh_button")
                ) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "Обновить разведку и события",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Фильтр-чипы для удобного чтения
        val chipColors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Всё", "Chain Radar", "Crypto Sentinel", "Разум и боты").forEachIndexed { idx, label ->
                FilterChip(
                    selected = filterTab == idx,
                    onClick = { filterTab = idx },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("radar_filter_$idx"),
                    label = { Text(label, style = MaterialTheme.typography.labelMedium, fontSize = 12.sp) },
                    colors = chipColors
                )
            }
        }

        val current = intel
        if (current == null) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Анализирую потоки капитала, мемпул и аномалии рынка…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (filterTab == 0 || filterTab == 1) {
                    item(key = "chain_radar_block") {
                        ChainRadarCard(current.chainRadar)
                    }
                }
                if (filterTab == 0 || filterTab == 2) {
                    item(key = "crypto_sentinel_block") {
                        CryptoSentinelCard(current.sentinelEvents, market)
                    }
                }
                if (filterTab == 0 || filterTab == 3) {
                    item(key = "logic_reasoner_block") {
                        ReasoningBrainCard(current, current.trendLogic)
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarSectionCard(
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                        icon,
                        contentDescription = title,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(8.dp).size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "$indexLabel. $title",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        subtitle,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = badgeColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        badgeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun ChainRadarCard(radar: ChainRadarSnapshot) {
    val (biasRu, biasColor) = when (radar.moneyFlowBias) {
        "ACCUMULATION" -> "НАКОПЛЕНИЕ" to Color.EnaGreen
        "DISTRIBUTION" -> "РАСПРЕДЕЛЕНИЕ" to Color.EnaRed
        "NEUTRAL" -> "БАЛАНС" to MaterialTheme.colorScheme.primary
        else -> "НЕДОСТАТОЧНО" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    RadarSectionCard(
        indexLabel = "1",
        title = "CHAIN RADAR",
        subtitle = "Разведка блокчейна и потоков капитала",
        icon = Icons.Outlined.Radar,
        badgeText = biasRu,
        badgeColor = biasColor
    ) {
        // Выделенный ответ на главный вопрос
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Главный вопрос: что происходит с деньгами до графика?",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    radar.preChartAnswer,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Ключевые ончейн и китовые метрики
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RadarMetricRow(
                label = "Крупные сделки (≥$100K)",
                value = if (radar.whaleTradesCount > 0) {
                    "${radar.whaleTradesCount} сд. · +$${TrendReasoningEngine.compactUsd(radar.whaleBuyUsd)} / -$${TrendReasoningEngine.compactUsd(radar.whaleSellUsd)}"
                } else "0 сд. в текущем окне",
                valueColor = when {
                    (radar.whaleDeltaPct ?: 0.0) >= 15.0 -> Color.EnaGreen
                    (radar.whaleDeltaPct ?: 0.0) <= -15.0 -> Color.EnaRed
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            RadarMetricRow(
                label = "Стейблкоины (ликвидность)",
                value = radar.stablecoinsTotalUsd?.let { tot ->
                    val d = radar.stablecoinsChange7dPct?.let { "%+.2f%% за 7д".format(Locale.US, it) } ?: ""
                    "$${TrendReasoningEngine.compactUsd(tot)} $d".trim()
                } ?: "недоступно",
                valueColor = when {
                    (radar.stablecoinsChange7dPct ?: 0.0) >= 0.3 -> Color.EnaGreen
                    (radar.stablecoinsChange7dPct ?: 0.0) <= -0.3 -> Color.EnaRed
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            RadarMetricRow(
                label = "Сеть BTC (мемпул / комиссия)",
                value = buildString {
                    append(radar.feeFastSat?.let { "$it sat/vB" } ?: "—")
                    radar.mempoolTxCount?.let { append(" · %,d tx в очереди".format(Locale.US, it)) }
                }
            )
            RadarMetricRow(
                label = "DeFi TVL (DefiLlama)",
                value = radar.defiTotalTvlUsd?.let { "$${TrendReasoningEngine.compactUsd(it)}" } ?: "недоступно"
            )
        }

        if (radar.btcLargeTransfers.isNotEmpty()) {
            Text(
                "Крупные переводы в сети BTC (≥50 BTC)",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                radar.btcLargeTransfers.take(5).forEach { tx ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.background,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "%.1f BTC".format(Locale.US, tx.amountBtc) +
                                        (tx.usdApprox?.let { " (~$${TrendReasoningEngine.compactUsd(it)})" } ?: ""),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "${tx.directionLabel} · ${tx.txHashShort}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                fmtTime(tx.tsSec * 1000L),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Честные статусы источников
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            radar.sources.forEach { (name, st) ->
                val ok = st == "LIVE"
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(6.dp)
                                .background(if (ok) Color.EnaGreen else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(name, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CryptoSentinelCard(events: List<SentinelEvent>, market: AppState.MarketUi) {
    val crit = events.count { it.severity == SentinelSeverity.CRITICAL }
    val warn = events.count { it.severity == SentinelSeverity.WARNING }
    val (badgeTxt, badgeClr) = when {
        crit > 0 -> "$crit КРИТИЧНО" to Color.EnaRed
        warn > 0 -> "$warn ВНИМАНИЕ" to Color.EnaYellow
        events.isNotEmpty() -> "${events.size} СОБЫТ." to MaterialTheme.colorScheme.primary
        else -> "СПОКОЙНО" to Color.EnaGreen
    }

    RadarSectionCard(
        indexLabel = "2",
        title = "CRYPTO SENTINEL",
        subtitle = "Обнаружение аномалий, всплесков, взломов и разблокировок",
        icon = Icons.Outlined.Shield,
        badgeText = badgeTxt,
        badgeColor = badgeClr
    ) {
        if (events.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    "Аномалий не обнаружено: волатильность свечей, объёмы, межбиржевой спред и лента новостей без критических отклонений.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                events.forEach { ev ->
                    val sevColor = when (ev.severity) {
                        SentinelSeverity.CRITICAL -> Color.EnaRed
                        SentinelSeverity.WARNING -> Color.EnaYellow
                        SentinelSeverity.INFO -> MaterialTheme.colorScheme.primary
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.background,
                        border = BorderStroke(1.dp, sevColor.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier.padding(11.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${ev.severity.labelRu} · ${ev.category.labelRu}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = sevColor,
                                    modifier = Modifier.weight(1f)
                                )
                                if (ev.metricBadge.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = sevColor.copy(alpha = 0.14f)
                                    ) {
                                        Text(
                                            ev.metricBadge,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            color = sevColor,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                ev.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                ev.detail,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Источник: ${ev.source} · ${fmtTime(ev.tsMs)}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
            }
        }

        // Живой новостной радар (синхронизирован с Чатом и Мониторингом)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Новостной радар (RSS / СМИ)",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                market.newsStatus,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val newsArr = market.news
        if (newsArr.length() == 0) {
            Text(
                "Заголовки ещё загружаются… Нажмите обновить сверху для принудительного опроса лент.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in 0 until minOf(newsArr.length(), 4)) {
                    val item = newsArr.optJSONObject(i) ?: continue
                    val imp = item.optDouble("impact", 0.0).let { if (it.isNaN()) 0.0 else it }
                    val mark = when {
                        imp > 0.1 -> "▲"
                        imp < -0.1 -> "▼"
                        else -> "•"
                    }
                    val markClr = when (mark) {
                        "▲" -> Color.EnaGreen
                        "▼" -> Color.EnaRed
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val pubTs = item.optLong("published_ts", 0L)
                    val timeStr = if (pubTs > 0L) " · ${fmtTime(pubTs)}" else ""
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
                            Text(
                                "$mark ${item.optString("title")}",
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "${item.optString("source")}$timeStr",
                                fontSize = 9.sp,
                                color = markClr
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningBrainCard(report: UnifiedIntelligenceReport, logic: TrendLogicSummary) {
    val biasColor = when (logic.biasLabel) {
        "BULLISH" -> Color.EnaGreen
        "BEARISH" -> Color.EnaRed
        else -> MaterialTheme.colorScheme.primary
    }

    RadarSectionCard(
        indexLabel = "3",
        title = "РАЗУМ И БОТЫ-АГЕНТЫ",
        subtitle = "Причинно-следственный синтез и вычисление фазы тренда",
        icon = Icons.Outlined.Psychology,
        badgeText = "${logic.biasLabel} · ${logic.strength}/100",
        badgeColor = biasColor
    ) {
        // Сводная фаза и вероятности
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Фаза рынка: ${logic.phase}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Согласие ботов: ${logic.agreementPct}% · Вход: ${logic.entryQuality} · Риск: ${logic.riskLevel}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatLogicProbabilityLine(logic),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    if (logic.pUp > 0) Box(Modifier.weight(logic.pUp.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(Color.EnaGreen))
                    if (logic.pFlat > 0) Box(Modifier.weight(logic.pFlat.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
                    if (logic.pDown > 0) Box(Modifier.weight(logic.pDown.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(Color.EnaRed))
                }
            }
        }

        // Цепочка логических рассуждений
        Text(
            "Цепочка рассуждений движка",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            logic.causalChain.forEach { step ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Шаг ${step.index}. Факт: ${step.observation}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Логика: ${step.mechanism}",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Вывод: ${step.conclusion}",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Что отменит: ${step.invalidation}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (logic.conflicts.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color.EnaYellow.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, Color.EnaYellow.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Обнаружены противоречия:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.EnaYellow)
                    logic.conflicts.forEach { c ->
                        Text("• $c", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        // Время, качество данных и калибровка формул
        val q = report.quant
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Точность данных, время и калибровка",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                val clkTxt = if (q.clockSynced) {
                    val sign = if (q.clockOffsetMs >= 0) "+" else ""
                    "синхр. ${q.clockSource} (${sign}${q.clockOffsetMs}мс · RTT ${q.clockRttMs}мс)"
                } else "локальные часы"
                val qScorePct = (q.dataQualityScore * 100).toInt()
                Text(
                    "Данные: Grade ${q.dataGrade} ($qScorePct%) · Часы: $clkTxt",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val adxS = "%.1f".format(Locale.US, q.adx)
                val erS = "%.2f".format(Locale.US, q.efficiencyRatio)
                val r2S = "%.2f".format(Locale.US, q.regressionR2)
                Text(
                    "Тренд-ядро: ADX $adxS · Кауфман ER $erS · R² $r2S",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val wfTxt = if (q.walkForwardSamples >= 6 && q.walkForwardHitPct != null && q.brierScore != null) {
                    val hS = "%.0f%%".format(Locale.US, q.walkForwardHitPct)
                    val bS = "%.2f".format(Locale.US, q.brierScore)
                    val cS = "%.2f".format(Locale.US, q.calibrationFactor)
                    "История OOS (${q.walkForwardSamples} окон): точность $hS · Brier $bS · калибратор ×$cS"
                } else {
                    "История OOS: недостаточно баров (активен базовый калибратор ×${"%.2f".format(Locale.US, q.calibrationFactor)})"
                }
                Text(
                    wfTxt,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Папка памяти и самообучение
        val learn = report.learning
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Самообучение и папка памяти",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Папка: ${learn.folderLabel} (создаётся сама, не стирается при очистке кэша)",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val venues = report.venues
                if (venues.activeVenues > 0) {
                    val velStr = venues.binanceTradeVelPerSec?.takeIf { it.isFinite() }?.let { " · %.1f сд/с".format(Locale.US, it) } ?: ""
                    Text(
                        "Мульти-биржи (${venues.activeVenues}): ${venues.venueNames.joinToString(" + ")}$velStr",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val hrLabel = learn.hitRatePct?.takeIf { it.isFinite() }?.let { " · точность ${"%.0f%%".format(Locale.US, it)}" } ?: ""
                Text(
                    "Записано наблюдений: ${learn.totalRecorded} · проверено 6ч: ${learn.verifiedCount}$hrLabel",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Голоса вычислительных ботов
        Text(
            "Вычислительные боты (${report.bots.size})",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            report.bots.forEach { bot ->
                val bColor = when (bot.bias) {
                    BiasDirection.UP -> Color.EnaGreen
                    BiasDirection.DOWN -> Color.EnaRed
                    BiasDirection.FLAT -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val bLabel = when (bot.bias) {
                    BiasDirection.UP -> "UP"
                    BiasDirection.DOWN -> "DOWN"
                    BiasDirection.FLAT -> "NEUTRAL"
                }
                val learnedWeight = learn.botWeights[bot.id]
                val weightSuffix = if (learnedWeight != null) {
                    " · вес ×${"%.2f".format(Locale.US, learnedWeight)}"
                } else ""
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                bot.name,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "$bLabel · ${(bot.confidence * 100).toInt()}%$weightSuffix",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = bColor
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            bot.summary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarMetricRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

internal fun formatLogicProbabilityLine(logic: TrendLogicSummary): String {
    val pUp = if (logic.pUp.isFinite()) logic.pUp.coerceIn(0.0, 1.0) else 0.0
    val pFlat = if (logic.pFlat.isFinite()) logic.pFlat.coerceIn(0.0, 1.0) else 0.0
    val upP = (pUp * 100).toInt()
    val flatP = (pFlat * 100).toInt()
    val downP = (100 - upP - flatP).coerceAtLeast(0)
    val expMove = if (logic.expectedMovePct.isFinite()) logic.expectedMovePct else 0.0
    val expStr = "%+.2f%%".format(Locale.US, expMove)
    return "P(UP) $upP% · P(FLAT) $flatP% · P(DOWN) $downP% · ожид. $expStr"
}

private fun fmtTime(ms: Long): String =
    if (ms <= 0L) "—" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
