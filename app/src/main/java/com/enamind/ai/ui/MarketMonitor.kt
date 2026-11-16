package com.enamind.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enamind.ai.core.AppState
import com.enamind.ai.core.FeedStatus
import com.enamind.ai.core.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * РЫНОК — экран мониторинга:
 * карточки выбора любых контрактов биржи + избранного и таймфрейма, сводка цены, живой график OHLCV,
 * карточки тренда и прогнозов 6ч + 24ч, мульти-биржевая паутина, Order Flow + стакан, новости.
 */
@Composable
fun MarketMonitor(state: AppState) {
    val market by state.market.collectAsState()
    val engineResult by state.engine.result.collectAsState()
    val intel by state.engine.intelligence.collectAsState()
    val klines by state.klines.collectAsState()
    val lastBar by state.lastBar.collectAsState()
    val ivLabel = Settings.INTERVALS.firstOrNull { it.first == market.interval }?.second ?: market.interval

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Выбор пары и таймфрейма (раздельные аккуратные полосы)
        SymbolAndIntervalSelector(state, market)

        // Hero-карточка цены, 24ч, F&G и прокручиваемых метрик
        HeroMarketCard(state, market)

        // Живой свечной график OHLCV + объём
        KlineChart(
            bars = klines,
            lastBar = lastBar,
            theme = if (state.settings.theme == Settings.Theme.WHITE) "white" else "black",
            symbol = market.symbol,
            intervalLabel = ivLabel,
            sourceLabel = market.feedSource
        )

        // Тренд, логика, мульти-биржевой срез и прогнозы 6ч / 24ч
        TrendAndForecastCard(engineResult, intel)

        // Order Flow + Стакан
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OrderFlowPanel(market, modifier = Modifier.weight(1f))
            OrderBookPanel(market, modifier = Modifier.weight(1f))
        }

        // Новости рынка
        NewsCard(market)

        // Статусы подключений (честные по темам)
        ConnectionCard(state)
    }
}

@Composable
private fun SymbolAndIntervalSelector(state: AppState, market: AppState.MarketUi) {
    val favorites by state.favoriteSymbols.collectAsState()
    var showPicker by remember { mutableStateOf(false) }

    val quickList = remember(favorites, market.symbol) {
        (listOf(market.symbol) + favorites.toList() + Settings.SYMBOLS).distinct()
    }

    if (showPicker) {
        ContractPickerDialog(
            state = state,
            currentSymbol = market.symbol,
            onSelect = { state.changeSymbol(it) },
            onDismiss = { showPicker = false }
        )
    }

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
                onClick = { showPicker = true },
                modifier = Modifier.testTag("market_all_contracts_button"),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "Выбрать любой контракт",
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = {
                    Text(
                        "Все пары",
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = chipColors
            )
            quickList.forEach { sym ->
                key(sym) {
                    FilterChip(
                        selected = market.symbol == sym,
                        onClick = { state.changeSymbol(sym) },
                        modifier = Modifier.testTag("market_sym_$sym"),
                        label = {
                            Text(
                                sym.removeSuffix("USDT"),
                                style = MaterialTheme.typography.labelMedium,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        colors = chipColors
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Settings.INTERVALS.forEach { (iv, label) ->
                key(iv) {
                    FilterChip(
                        selected = market.interval == iv,
                        onClick = { state.changeInterval(iv) },
                        modifier = Modifier.testTag("market_iv_$iv"),
                        label = { Text(label, style = MaterialTheme.typography.labelMedium, fontSize = 11.sp) },
                        colors = chipColors
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroMarketCard(state: AppState, market: AppState.MarketUi) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            market.symbol,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        ) {
                            Text(
                                market.feedSource,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val safeChg = market.change24hPct?.takeIf { it.isFinite() }
                        Text(
                            market.price?.takeIf { it.isFinite() && it > 0 }?.let { formatHeroPrice(it) } ?: "—",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                (safeChg ?: 0.0) >= 0 -> Color.EnaGreen
                                else -> Color.EnaRed
                            }
                        )
                        safeChg?.let { chg ->
                            Spacer(Modifier.width(10.dp))
                            val c = if (chg >= 0) Color.EnaGreen else Color.EnaRed
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = c.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    mmFmt("%+.2f%%", chg),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = c,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                market.fng?.let { fngVal ->
                    val fngColor = when {
                        fngVal >= 75 -> Color.EnaRed
                        fngVal <= 25 -> Color.EnaGreen
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                "F&G $fngVal",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = fngColor
                            )
                            Text(
                                market.fngClassification ?: "",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                IconButton(
                    onClick = {
                        state.reloadKlines()
                        state.refreshMarketStats()
                    },
                    modifier = Modifier.testTag("market_refresh_button")
                ) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "Обновить свечи и метрики",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Горизонтальная полоса ключевых метрик
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val safeFund = market.fundingPct?.takeIf { it.isFinite() }
                val safeOi24 = market.oiChange24hPct?.takeIf { it.isFinite() }
                val safeLsr = market.lsr?.takeIf { it.isFinite() }
                val safeLsrPct = market.lsrPct?.takeIf { it.isFinite() }
                StatPill("Объём 24ч", compact(market.volume24h))
                StatPill(
                    "Funding",
                    safeFund?.let { mmFmt("%+.4f%%", it) } ?: "—",
                    if ((safeFund ?: 0.0) > 0) Color.EnaRed else Color.EnaGreen
                )
                StatPill("OI", compact(market.openInterest))
                StatPill(
                    "OI 24ч",
                    safeOi24?.let { mmFmt("%+.2f%%", it) } ?: "—",
                    if ((safeOi24 ?: 0.0) >= 0) Color.EnaGreen else Color.EnaRed
                )
                StatPill(
                    "LSR",
                    safeLsr?.let {
                        mmFmt("%.2f", it) + (safeLsrPct?.let { p -> " (п${p.toInt()})" } ?: "")
                    } ?: "—",
                    when {
                        (safeLsrPct ?: 50.0) >= 90 -> Color.EnaRed
                        (safeLsrPct ?: 50.0) <= 10 -> Color.EnaGreen
                        else -> null
                    }
                )
                val l1 = market.liq1hUsd?.takeIf { it.isFinite() } ?: 0.0
                val l24 = market.liq24hUsd?.takeIf { it.isFinite() } ?: 0.0
                StatPill("Ликв. 1ч", if (l1 > 0) "$${compact(l1)}" else "$0")
                StatPill("Ликв. 24ч", if (l24 > 0) "$${compact(l24)}" else "$0")
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, color: Color? = null) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = color ?: MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun TrendAndForecastCard(
    result: org.json.JSONObject?,
    intel: com.enamind.ai.intelligence.UnifiedIntelligenceReport?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val trend = result?.optJSONObject("trend")
            if (trend == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TREND", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("движок прогревается…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val bias = trend.optString("bias", "NEUTRAL / RANGE")
                val strength = trend.optInt("strength", 0)
                val entryQ = trend.optString("entry_quality", "—")
                val risk = trend.optString("risk", "—")
                val conf = trend.optDouble("confidence", 0.0).let { if (it.isFinite()) it else 0.0 }
                val biasColor = when {
                    bias.contains("BULLISH") -> Color.EnaGreen
                    bias.contains("BEARISH") -> Color.EnaRed
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("TREND", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(bias, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = biasColor)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "сила $strength/100 · вход $entryQ · риск $risk · ${Math.round(conf * 100)}%",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Горизонт 6 часов (основной краткосрочный прогноз)
            val o6 = intel?.outlook6h
            val py6 = result?.optJSONObject("outlook_6h") ?: result?.optJSONObject("outlook6h")
            val p6Up = o6?.pUp ?: py6?.optDouble("p_up", Double.NaN) ?: intel?.trendLogic?.pUp ?: Double.NaN
            val p6Flat = o6?.pFlat ?: py6?.optDouble("p_flat", Double.NaN) ?: intel?.trendLogic?.pFlat ?: Double.NaN
            val p6Down = o6?.pDown ?: py6?.optDouble("p_down", Double.NaN) ?: intel?.trendLogic?.pDown ?: Double.NaN
            val exp6 = o6?.expectedMovePct ?: py6?.optDouble("expected_move_pct", Double.NaN) ?: intel?.trendLogic?.expectedMovePct ?: 0.0
            if (p6Up.isFinite() && p6Flat.isFinite() && p6Down.isFinite()) {
                OutlookHorizonRow(
                    label = "6ч",
                    pUp = p6Up,
                    pFlat = p6Flat,
                    pDown = p6Down,
                    expMove = exp6,
                    badge = result?.optJSONObject("forecast")?.optString("council_status").orEmpty().ifBlank {
                        if (intel?.trendLogic?.conflicts?.isEmpty() == true) "AGREE" else "CAUTION"
                    }
                )
            }

            // Горизонт 24 часа
            val o24 = intel?.outlook24h
            val py24 = result?.optJSONObject("outlook_24h") ?: result?.optJSONObject("outlook24h")
            val p24Up = o24?.pUp ?: py24?.optDouble("p_up", Double.NaN) ?: Double.NaN
            val p24Flat = o24?.pFlat ?: py24?.optDouble("p_flat", Double.NaN) ?: Double.NaN
            val p24Down = o24?.pDown ?: py24?.optDouble("p_down", Double.NaN) ?: Double.NaN
            val exp24 = o24?.expectedMovePct ?: py24?.optDouble("expected_move_pct", 0.0) ?: 0.0
            if (p24Up.isFinite() && p24Flat.isFinite() && p24Down.isFinite()) {
                OutlookHorizonRow(
                    label = "24ч",
                    pUp = p24Up,
                    pFlat = p24Flat,
                    pDown = p24Down,
                    expMove = exp24,
                    badge = "сутки"
                )
            }

            intel?.let { rep ->
                val venueSuffix = if (rep.venues.activeVenues > 0) {
                    val velTxt = rep.venues.binanceTradeVelPerSec?.takeIf { it.isFinite() }?.let { mmFmt(" · %.1f сд/с", it) } ?: ""
                    " · Биржи: ${rep.venues.activeVenues}$velTxt"
                } else ""
                Text(
                    "Фаза: ${rep.trendLogic.phase} · Деньги: ${rep.chainRadar.moneyFlowBias} · Sentinel: ${rep.sentinelEvents.size}$venueSuffix",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun OutlookHorizonRow(
    label: String,
    pUp: Double,
    pFlat: Double,
    pDown: Double,
    expMove: Double,
    badge: String
) {
    val u = if (pUp.isFinite()) pUp.coerceIn(0.0, 1.0) else 0.0
    val fl = if (pFlat.isFinite()) pFlat.coerceIn(0.0, 1.0) else 0.0
    val dn = if (pDown.isFinite()) pDown.coerceIn(0.0, 1.0) else 0.0
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text("${Math.round(u * 100)}%↑", fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = Color.EnaGreen, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(8.dp))
        Text("${Math.round(fl * 100)}%→", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(8.dp))
        Text("${Math.round(dn * 100)}%↓", fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = Color.EnaRed, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.weight(1f))
        Text(
            "${mmFmt("%+.2f%%", expMove)} · $badge",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    val sum = (u + fl + dn).coerceAtLeast(1e-6)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (u > 0) Box(Modifier.weight((u / sum).toFloat()).fillMaxHeight().background(Color.EnaGreen))
        if (fl > 0) Box(Modifier.weight((fl / sum).toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
        if (dn > 0) Box(Modifier.weight((dn / sum).toFloat()).fillMaxHeight().background(Color.EnaRed))
    }
}

@Composable
private fun OrderFlowPanel(market: AppState.MarketUi, modifier: Modifier = Modifier) {
    val t = market.tape
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text("ORDER FLOW", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            if (t == null || t.n == 0) {
                Text("ожидание ленты сделок…", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow("Сделок", "${t.n}")
                FlowRow("Покупки", mmFmt("%.2f", t.buyQty), Color.EnaGreen)
                FlowRow("Продажи", mmFmt("%.2f", t.sellQty), Color.EnaRed)
                FlowRow("CVD", mmFmt("%+.2f", t.cvd), if (t.cvd.isFinite() && t.cvd >= 0) Color.EnaGreen else Color.EnaRed)
                FlowRow("Дельта", mmFmt("%+.1f%%", t.deltaPct), if (t.deltaPct.isFinite() && t.deltaPct >= 0) Color.EnaGreen else Color.EnaRed)
                if (t.divergence) {
                    Text("⚠ дивергенция цена/CVD", fontSize = 9.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun FlowRow(label: String, value: String, color: Color? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            value, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = color ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun OrderBookPanel(market: AppState.MarketUi, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("СТАКАН", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            val bids = market.bids
            val asks = market.asks
            if (asks.isEmpty() && bids.isEmpty()) {
                Text("ожидание книги заявок…", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val maxQty = maxOf(
                    (bids.maxOfOrNull { it.qty } ?: 0.0),
                    (asks.maxOfOrNull { it.qty } ?: 0.0)
                ).coerceAtLeast(1e-9)
                asks.take(6).reversed().forEach { lvl ->
                    BookRow(price = lvl.price, qty = lvl.qty, maxQty = maxQty, isBid = false)
                }
                Spacer(Modifier.height(3.dp))
                bids.take(6).forEach { lvl ->
                    BookRow(price = lvl.price, qty = lvl.qty, maxQty = maxQty, isBid = true)
                }
                val b = bids.sumOf { if (it.qty.isFinite()) it.qty else 0.0 }
                val a = asks.sumOf { if (it.qty.isFinite()) it.qty else 0.0 }
                if (b + a > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "дисбаланс ${mmFmt("%+.1f%%", 100 * (b - a) / (b + a))}",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BookRow(price: Double, qty: Double, maxQty: Double, isBid: Boolean) {
    val safeQty = if (qty.isFinite() && qty >= 0.0) qty else 0.0
    val frac = (safeQty / maxQty).coerceIn(0.0, 1.0)
    Box(modifier = Modifier.fillMaxWidth().height(15.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth(frac.toFloat())
                .fillMaxSize()
                .background(
                    if (isBid) Color.EnaGreen.copy(alpha = 0.18f) else Color.EnaRed.copy(alpha = 0.18f),
                    RoundedCornerShape(3.dp)
                )
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatPrice(price), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                color = if (isBid) Color.EnaGreen else Color.EnaRed,
                modifier = Modifier.weight(1f)
            )
            Text(
                mmFmt("%.3f", safeQty), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NewsCard(market: AppState.MarketUi) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("НОВОСТИ", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(market.newsStatus, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val n = market.news
                for (i in 0 until n.length()) {
                    val item = n.optJSONObject(i) ?: continue
                    val imp = item.optDouble("impact", 0.0).let { if (it.isNaN()) 0.0 else it }
                    val mark = when {
                        imp > 0 -> "▲"
                        imp < 0 -> "▼"
                        else -> "•"
                    }
                    val itemKey = item.optString("url").ifBlank { "$i:${item.optString("title")}" }
                    key(itemKey) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Text(
                                "$mark ${item.optString("source")}: ${item.optString("title")} (${timeShort(item.optLong("published_ts"))})",
                                fontSize = 10.sp,
                                maxLines = 1,
                                color = when (mark) {
                                    "▲" -> Color.EnaGreen
                                    "▼" -> Color.EnaRed
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(state: AppState) {
    val topics by state.feedTopics.collectAsState()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (topics.isEmpty()) {
                TopicDotPlaceholder("WS ${state.market.value.symbol}")
            } else {
                topics.entries.sortedBy { it.key.ordinal }.forEach { (kind, t) ->
                    key(kind) {
                        TopicDot(kind, t)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicDotPlaceholder(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TopicDot(kind: FeedStatus.Kind, t: FeedStatus.Topic) {
    val color = when (t.status) {
        FeedStatus.LIVE -> Color.EnaGreen
        FeedStatus.STALE -> enaAmberText()
        FeedStatus.ERROR, FeedStatus.DISCONNECTED -> Color.EnaRed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (kind) {
        FeedStatus.Kind.TICKER -> "цена"
        FeedStatus.Kind.TRADES -> "лента"
        FeedStatus.Kind.BOOK -> "стакан"
        FeedStatus.Kind.KLINE -> "свечи"
        FeedStatus.Kind.LIQUIDATION -> "ликв."
    }
    val note = when {
        t.status == FeedStatus.LIVE && t.ageSec >= 0 -> "$label ${t.ageSec}с"
        t.status == FeedStatus.CONNECTED_NO_DATA -> "$label ожидание"
        t.status == FeedStatus.CONNECTING -> "$label подключение"
        t.status == FeedStatus.STALE -> "$label ${t.ageSec}с"
        t.status == FeedStatus.ERROR -> "$label ошибка"
        t.status == FeedStatus.DISCONNECTED -> "$label переподкл."
        else -> label
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            note,
            fontSize = 10.sp,
            color = when (t.status) {
                FeedStatus.LIVE -> Color.EnaGreen
                FeedStatus.STALE -> enaAmberText()
                FeedStatus.ERROR, FeedStatus.DISCONNECTED -> Color.EnaRed
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private fun mmFmt(fmt: String, value: Double, fallback: String = "—"): String {
    if (!value.isFinite()) return fallback
    return runCatching { String.format(Locale.US, fmt, value) }.getOrDefault(fallback)
}

private fun formatHeroPrice(p: Double): String {
    if (!p.isFinite() || p <= 0.0) return "—"
    return when {
        p >= 1000 -> mmFmt("%,.1f", p)
        p >= 10 -> mmFmt("%,.2f", p)
        else -> mmFmt("%.4f", p)
    }
}

private fun formatPrice(p: Double): String {
    if (!p.isFinite() || p <= 0.0) return "—"
    return when {
        p >= 1000 -> mmFmt("%.1f", p)
        p >= 10 -> mmFmt("%.2f", p)
        else -> mmFmt("%.4f", p)
    }
}

private fun compact(v: Double?): String {
    if (v == null || !v.isFinite() || v == 0.0) return "—"
    val av = kotlin.math.abs(v)
    return when {
        av >= 1e9 -> mmFmt("%.2fB", v / 1e9)
        av >= 1e6 -> mmFmt("%.2fM", v / 1e6)
        av >= 1e3 -> mmFmt("%.1fK", v / 1e3)
        else -> mmFmt("%.2f", v)
    }
}

private fun timeShort(ts: Long): String =
    if (ts <= 0) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
