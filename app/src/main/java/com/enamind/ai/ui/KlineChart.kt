package com.enamind.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CandlestickChart
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enamind.ai.bot.BotChartOverlay
import com.enamind.ai.bot.TradeSide
import com.enamind.ai.orderflow.ChartOverlayData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * KlineChart (Native Compose Canvas): свечной график OHLCV + объём + живая свеча + Order Flow & Bot overlay.
 */
@Composable
fun KlineChart(
    bars: List<List<Double>>,          // [[ts_ms,o,h,l,c,v],...] старые->новые
    lastBar: List<Double>?,            // живая (незакрытая) свеча из WS kline
    theme: String = "black",
    symbol: String = "",
    intervalLabel: String = "",
    sourceLabel: String = "",
    orderFlowOverlay: ChartOverlayData? = null,
    botOverlay: BotChartOverlay? = null,
    modifier: Modifier = Modifier
) {
    val validBars = remember(bars) { validateBars(bars) }
    val displayBars = remember(validBars, lastBar) {
        validBars?.let { mergeLiveBar(it, lastBar) }
    }

    val seriesKey = remember(bars) {
        "${bars.firstOrNull()?.firstOrNull()?.toLong() ?: 0L}:${bars.size}"
    }
    var panOffsetBars by remember { mutableFloatStateOf(0f) }
    var visibleCount by remember { mutableIntStateOf(55) }
    var inspectedIdx by remember { mutableStateOf<Int?>(null) }

    val firstTs = bars.firstOrNull()?.firstOrNull()?.toLong() ?: 0L
    LaunchedEffect(firstTs) {
        if (firstTs > 0L) {
            panOffsetBars = 0f
            inspectedIdx = null
        }
    }
    LaunchedEffect(bars.isEmpty()) {
        if (bars.isEmpty()) {
            panOffsetBars = 0f
            inspectedIdx = null
        }
    }

    val renderState = when {
        bars.isEmpty() -> "NO_DATA"
        displayBars == null -> "SOURCE_ERROR"
        else -> "OK"
    }

    val isDark = theme != "white"
    val bgTop = if (isDark) Color(0xFF0A0A0A) else Color(0xFFFFFFFF)
    val bgBottom = if (isDark) Color(0xFF000000) else Color(0xFFF7F7F7)
    val gridColor = if (isDark) Color(0x1EFFFFFF) else Color(0x14111111)
    val borderColor = if (isDark) Color(0x3D525252) else Color(0x28111111)
    val labelColor = if (isDark) Color(0xFFA3A3A3) else Color(0xFF525252)
    val bullColor = Color(0xFF00E5A0)
    val bearColor = Color(0xFFFF4D5E)
    val bullVolColor = Color(0x5500E5A0)
    val bearVolColor = Color(0x55FF4D5E)

    val textMeasurer = rememberTextMeasurer()
    val axisTextStyle = TextStyle(
        color = labelColor,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Заголовок карточки графика: символ/ТФ/источник + кнопки масштаба (>=48dp touch targets)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (symbol.isNotBlank()) {
                    Text(
                        "$symbol · $intervalLabel",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (lastBar != null) bullColor.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                if (lastBar != null) bullColor
                                else if (renderState == "OK") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        when {
                            lastBar != null -> "LIVE${if (sourceLabel.isNotBlank()) " · $sourceLabel" else ""}"
                            renderState == "OK" -> "OK (${displayBars?.size ?: 0})"
                            else -> "загрузка"
                        },
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (lastBar != null) bullColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { visibleCount = (visibleCount - 15).coerceAtLeast(18) },
                    modifier = Modifier.testTag("chart_zoom_in")
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = "Приблизить график",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { visibleCount = (visibleCount + 15).coerceAtMost(120) },
                    modifier = Modifier.testTag("chart_zoom_out")
                ) {
                    Icon(
                        Icons.Outlined.Remove,
                        contentDescription = "Отдалить график",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        panOffsetBars = 0f
                        visibleCount = 55
                        inspectedIdx = null
                    },
                    modifier = Modifier.testTag("chart_zoom_reset")
                ) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "Сбросить масштаб графика",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Brush.verticalGradient(listOf(bgTop, bgBottom)))
            ) {
                if (displayBars != null && displayBars.isNotEmpty()) {
                    val total = displayBars.size
                    val count = visibleCount.coerceIn(16, max(16, min(total, 120)))
                    val maxOffset = (total - count).coerceAtLeast(0).toFloat()
                    val clampedOffset = panOffsetBars.coerceIn(0f, maxOffset)
                    val endIdx = (total - clampedOffset.roundToInt()).coerceIn(1, total)
                    val startIdx = (endIdx - count).coerceAtLeast(0)
                    val visible = displayBars.subList(startIdx, endIdx)

                    val selectedBar = inspectedIdx?.let { idx ->
                        displayBars.getOrNull(idx)
                    } ?: displayBars.last()

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("kline_chart_canvas")
                            .pointerInput(seriesKey, total, count) {
                                detectHorizontalDragGestures { change, dragAmount ->
                                    change.consume()
                                    val plotW = (size.width - 56.dp.toPx()).coerceAtLeast(1f)
                                    val barPx = plotW / count.coerceAtLeast(1)
                                    panOffsetBars = (panOffsetBars + dragAmount / barPx).coerceIn(0f, maxOffset)
                                }
                            }
                            .pointerInput(seriesKey, startIdx, visible.size) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        panOffsetBars = 0f
                                        inspectedIdx = null
                                    },
                                    onTap = { offset ->
                                        val plotW = (size.width - 56.dp.toPx()).coerceAtLeast(1f)
                                        if (offset.x <= plotW && visible.isNotEmpty()) {
                                            val rel = ((offset.x / plotW) * visible.size).toInt()
                                                .coerceIn(0, visible.size - 1)
                                            val target = startIdx + rel
                                            inspectedIdx = if (inspectedIdx == target) null else target
                                        } else {
                                            inspectedIdx = null
                                        }
                                    }
                                )
                            }
                    ) {
                        val rightAxisW = 56.dp.toPx()
                        val bottomAxisH = 18.dp.toPx()
                        val topPad = 18.dp.toPx()
                        val plotW = (size.width - rightAxisW).coerceAtLeast(1f)
                        val plotH = (size.height - bottomAxisH - topPad).coerceAtLeast(1f)
                        val candleTop = topPad
                        val candleBottom = topPad + plotH * 0.74f
                        val candleH = (candleBottom - candleTop).coerceAtLeast(1f)
                        val volTop = candleBottom + plotH * 0.05f
                        val volBottom = topPad + plotH
                        val volHMax = (volBottom - volTop).coerceAtLeast(1f)

                        var rawMin = Double.POSITIVE_INFINITY
                        var rawMax = Double.NEGATIVE_INFINITY
                        var maxVol = 0.0
                        for (bar in visible) {
                            if (bar[3] < rawMin) rawMin = bar[3]
                            if (bar[2] > rawMax) rawMax = bar[2]
                            if (bar[5] > maxVol) maxVol = bar[5]
                        }
                        val span = (rawMax - rawMin).let {
                            if (it <= 0.0) max(rawMax * 0.005, 1e-4) else it
                        }
                        val pad = span * 0.08
                        val minP = rawMin - pad
                        val maxP = rawMax + pad
                        val pRange = (maxP - minP).coerceAtLeast(1e-9)
                        val safeMaxVol = maxVol.coerceAtLeast(1e-9)

                        fun priceToY(p: Double): Float {
                            val frac = ((p - minP) / pRange).toFloat().coerceIn(0f, 1f)
                            return candleBottom - frac * candleH
                        }

                        // Разделители осей
                        drawLine(
                            color = borderColor,
                            start = Offset(plotW, 0f),
                            end = Offset(plotW, topPad + plotH),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawLine(
                            color = borderColor,
                            start = Offset(0f, topPad + plotH),
                            end = Offset(size.width, topPad + plotH),
                            strokeWidth = 1.dp.toPx()
                        )

                        // Горизонтальная сетка цен (4 уровня)
                        val gridSteps = 3
                        for (g in 0..gridSteps) {
                            val frac = g.toFloat() / gridSteps
                            val price = minP + frac * pRange
                            val y = candleBottom - frac * candleH
                            drawLine(
                                color = gridColor,
                                start = Offset(0f, y),
                                end = Offset(plotW, y),
                                strokeWidth = 1f
                            )
                            val label = formatAxisPrice(price)
                            val measured = textMeasurer.measure(label, style = axisTextStyle)
                            drawText(
                                textLayoutResult = measured,
                                topLeft = Offset(
                                    x = plotW + 4.dp.toPx(),
                                    y = (y - measured.size.height / 2f).coerceIn(0f, candleBottom - measured.size.height)
                                )
                            )
                        }

                        val slotW = plotW / visible.size.coerceAtLeast(1)
                        val bodyW = (slotW * 0.64f).coerceIn(2f, 12.dp.toPx())
                        val wickW = (slotW * 0.14f).coerceIn(1f, 1.8.dp.toPx())
                        val minBodyH = 1.5.dp.toPx()

                        val timeStep = max(1, visible.size / 4)
                        for (i in visible.indices) {
                            val bar = visible[i]
                            val cx = (i + 0.5f) * slotW

                            if (i > 0 && i % timeStep == 0 && i < visible.size - 2) {
                                drawLine(
                                    color = gridColor,
                                    start = Offset(cx, candleTop),
                                    end = Offset(cx, topPad + plotH),
                                    strokeWidth = 1f
                                )
                                val tLabel = formatBarTime(bar[0].toLong())
                                val tm = textMeasurer.measure(tLabel, style = axisTextStyle)
                                drawText(
                                    textLayoutResult = tm,
                                    topLeft = Offset(
                                        x = (cx - tm.size.width / 2f).coerceIn(2f, plotW - tm.size.width),
                                        y = topPad + plotH + 2.dp.toPx()
                                    )
                                )
                            }

                            val o = bar[1]; val h = bar[2]; val l = bar[3]; val c = bar[4]; val v = bar[5]
                            val isBull = c >= o
                            val color = if (isBull) bullColor else bearColor

                            val yH = priceToY(h)
                            val yL = priceToY(l)
                            val yO = priceToY(o)
                            val yC = priceToY(c)

                            drawLine(
                                color = color,
                                start = Offset(cx, yH),
                                end = Offset(cx, yL),
                                strokeWidth = wickW
                            )

                            val rectTop = min(yO, yC)
                            val rectH = max(kotlin.math.abs(yC - yO), minBodyH)
                            drawRect(
                                color = color,
                                topLeft = Offset(cx - bodyW / 2f, rectTop),
                                size = Size(bodyW, rectH)
                            )

                            val vh = ((v / safeMaxVol).toFloat().coerceIn(0f, 1f)) * volHMax
                            if (vh > 0.5f) {
                                drawRect(
                                    color = if (isBull) bullVolColor else bearVolColor,
                                    topLeft = Offset(cx - bodyW / 2f, volBottom - vh),
                                    size = Size(bodyW, vh)
                                )
                            }
                        }

                        // Текущая цена + плашка справа
                        val lastClose = visible.last()[4]
                        val lastBull = visible.last()[4] >= visible.last()[1]
                        val lastColor = if (lastBull) bullColor else bearColor
                        val lastY = priceToY(lastClose)
                        drawLine(
                            color = lastColor.copy(alpha = 0.75f),
                            start = Offset(0f, lastY),
                            end = Offset(plotW, lastY),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                        )
                        val lastLabel = formatAxisPrice(lastClose)
                        val pillTextColor = if (lastBull) Color(0xFF041C14) else Color.White
                        val lastMeasured = textMeasurer.measure(
                            lastLabel,
                            style = axisTextStyle.copy(color = pillTextColor, fontWeight = FontWeight.Bold)
                        )
                        val pillTop = (lastY - lastMeasured.size.height / 2f - 2.dp.toPx())
                            .coerceIn(0f, candleBottom - lastMeasured.size.height - 4.dp.toPx())
                        drawRect(
                            color = lastColor,
                            topLeft = Offset(plotW + 1.dp.toPx(), pillTop),
                            size = Size(rightAxisW - 2.dp.toPx(), lastMeasured.size.height + 4.dp.toPx())
                        )
                        drawText(
                            textLayoutResult = lastMeasured,
                            topLeft = Offset(plotW + 4.dp.toPx(), pillTop + 2.dp.toPx())
                        )

                        // Order Flow Overlay (уровни S/R, ликвидность DOM, поглощение, атаки, неудачные пробои, события)
                        if (orderFlowOverlay != null) {
                            fun inVisibleRange(p: Double): Boolean = p.isFinite() && p in minP..maxP

                            // Зоны повышенной ликвидности DOM
                            orderFlowOverlay.highLiquidityZones.forEach { (zonePrice, side) ->
                                if (inVisibleRange(zonePrice)) {
                                    val zy = priceToY(zonePrice)
                                    val zoneColor = if (side == "BID") bullColor else bearColor
                                    drawRect(
                                        color = zoneColor.copy(alpha = 0.14f),
                                        topLeft = Offset(0f, (zy - 4.dp.toPx()).coerceAtLeast(candleTop)),
                                        size = Size(plotW, 8.dp.toPx())
                                    )
                                }
                            }

                            // Зоны обнаруженного поглощения
                            orderFlowOverlay.absorptionZones.forEach { absPrice ->
                                if (inVisibleRange(absPrice)) {
                                    val ay = priceToY(absPrice)
                                    drawRect(
                                        color = Color(0xFFF59E0B).copy(alpha = 0.20f),
                                        topLeft = Offset(0f, (ay - 6.dp.toPx()).coerceAtLeast(candleTop)),
                                        size = Size(plotW, 12.dp.toPx())
                                    )
                                    drawLine(
                                        color = Color(0xFFF59E0B).copy(alpha = 0.85f),
                                        start = Offset(0f, ay),
                                        end = Offset(plotW, ay),
                                        strokeWidth = 1.1.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f), 0f)
                                    )
                                }
                            }

                            // Уровни поддержки
                            orderFlowOverlay.supportLevels.forEach { sup ->
                                if (inVisibleRange(sup)) {
                                    val sy = priceToY(sup)
                                    drawLine(
                                        color = bullColor.copy(alpha = 0.72f),
                                        start = Offset(0f, sy),
                                        end = Offset(plotW, sy),
                                        strokeWidth = 1.2.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                                    )
                                }
                            }

                            // Уровни сопротивления
                            orderFlowOverlay.resistanceLevels.forEach { res ->
                                if (inVisibleRange(res)) {
                                    val ry = priceToY(res)
                                    drawLine(
                                        color = bearColor.copy(alpha = 0.72f),
                                        start = Offset(0f, ry),
                                        end = Offset(plotW, ry),
                                        strokeWidth = 1.2.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                                    )
                                }
                            }

                            // Маркеры повторных атак
                            orderFlowOverlay.attackMarkerPrices.forEachIndexed { idx, atkPrice ->
                                if (inVisibleRange(atkPrice)) {
                                    val ay = priceToY(atkPrice)
                                    val mx = (plotW - 14.dp.toPx() - idx * 10.dp.toPx()).coerceAtLeast(8.dp.toPx())
                                    drawCircle(
                                        color = Color(0xFFF59E0B),
                                        radius = 4.dp.toPx(),
                                        center = Offset(mx, ay)
                                    )
                                }
                            }

                            // Места неудачных пробоев
                            orderFlowOverlay.failedBreakoutPrices.forEachIndexed { idx, failPrice ->
                                if (inVisibleRange(failPrice)) {
                                    val fy = priceToY(failPrice)
                                    val mx = (plotW - 20.dp.toPx() - idx * 12.dp.toPx()).coerceAtLeast(10.dp.toPx())
                                    val r = 4.dp.toPx()
                                    drawLine(
                                        color = Color(0xFFFF6D00),
                                        start = Offset(mx - r, fy - r),
                                        end = Offset(mx + r, fy + r),
                                        strokeWidth = 1.8.dp.toPx()
                                    )
                                    drawLine(
                                        color = Color(0xFFFF6D00),
                                        start = Offset(mx - r, fy + r),
                                        end = Offset(mx + r, fy - r),
                                        strokeWidth = 1.8.dp.toPx()
                                    )
                                }
                            }

                            // Отметки подтверждённых событий на оси времени видимых свечей
                            val firstVisTs = visible.first()[0].toLong()
                            val lastVisTs = visible.last()[0].toLong()
                            orderFlowOverlay.confirmedEventMarkers.forEach { (evTs, evKind) ->
                                if (evTs in firstVisTs..lastVisTs) {
                                    val barIdx = visible.indexOfLast { it[0].toLong() <= evTs }.coerceIn(0, visible.lastIndex)
                                    val ex = (barIdx + 0.5f) * slotW
                                    val evColor = if (evKind == "BREAKOUT") bullColor else Color(0xFFA855F7)
                                    drawLine(
                                        color = evColor.copy(alpha = 0.78f),
                                        start = Offset(ex, candleTop),
                                        end = Offset(ex, candleBottom),
                                        strokeWidth = 1.3.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f)
                                    )
                                    drawCircle(
                                        color = evColor,
                                        radius = 4.dp.toPx(),
                                        center = Offset(ex, candleTop + 6.dp.toPx())
                                    )
                                }
                            }
                        }

                        // Trade Bot Overlay (ENTRY, TP MIN, TRAIL FLOOR, TP MAX, STOP / LIQ + маркеры сделок)
                        if (botOverlay != null &&
                            (symbol.isBlank() || botOverlay.symbol.equals(symbol, ignoreCase = true))
                        ) {
                            fun drawBotPriceLine(
                                linePrice: Double?,
                                tagLabel: String,
                                lineColor: Color,
                                dashed: Boolean
                            ) {
                                if (linePrice == null || !linePrice.isFinite() || linePrice !in minP..maxP) return
                                val ly = priceToY(linePrice)
                                drawLine(
                                    color = lineColor.copy(alpha = 0.9f),
                                    start = Offset(0f, ly),
                                    end = Offset(plotW, ly),
                                    strokeWidth = if (dashed) 1.3.dp.toPx() else 1.7.dp.toPx(),
                                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(9f, 5f), 0f) else null
                                )
                                val tm = textMeasurer.measure(
                                    tagLabel,
                                    style = axisTextStyle.copy(color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                )
                                val pillH = tm.size.height + 3.dp.toPx()
                                val pillW = tm.size.width + 8.dp.toPx()
                                val pillTopY = (ly - pillH - 2.dp.toPx()).coerceIn(candleTop, candleBottom - pillH)
                                drawRoundRect(
                                    color = lineColor.copy(alpha = 0.88f),
                                    topLeft = Offset(4.dp.toPx(), pillTopY),
                                    size = Size(pillW, pillH),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                )
                                drawText(
                                    textLayoutResult = tm,
                                    topLeft = Offset(8.dp.toPx(), pillTopY + 1.5.dp.toPx())
                                )
                            }

                            val entrySideLabel = botOverlay.side?.labelRu ?: "POS"
                            drawBotPriceLine(botOverlay.entryPrice, "ENTRY $entrySideLabel", Color(0xFF3B82F6), dashed = false)
                            drawBotPriceLine(botOverlay.tpMinPrice, "TP MIN", Color(0xFF10B981), dashed = true)
                            drawBotPriceLine(botOverlay.trailFloorPrice, "TRAIL FLOOR", Color(0xFFF59E0B), dashed = false)
                            drawBotPriceLine(botOverlay.tpMaxPrice, "TP MAX", Color(0xFF059669), dashed = true)
                            if (botOverlay.stopLossPrice != null) {
                                drawBotPriceLine(botOverlay.stopLossPrice, "STOP LOSS", Color(0xFFEF4444), dashed = true)
                            } else {
                                drawBotPriceLine(botOverlay.estLiquidationPrice, "EST LIQ", Color(0xFFDC2626), dashed = true)
                            }

                            val firstVisTs = visible.first()[0].toLong()
                            val lastVisTs = visible.last()[0].toLong()
                            botOverlay.markers.forEach { mk ->
                                if (mk.tsMs in firstVisTs..lastVisTs && mk.price in minP..maxP) {
                                    val barIdx = visible.indexOfLast { it[0].toLong() <= mk.tsMs }.coerceIn(0, visible.lastIndex)
                                    val mx = (barIdx + 0.5f) * slotW
                                    val my = priceToY(mk.price)
                                    val mkColor = when {
                                        mk.isEntry && mk.side == TradeSide.BUY -> Color(0xFF10B981)
                                        mk.isEntry -> Color(0xFFEF4444)
                                        mk.won == true -> Color(0xFF3B82F6)
                                        else -> Color(0xFFF59E0B)
                                    }
                                    drawCircle(
                                        color = Color.White,
                                        radius = 5.5.dp.toPx(),
                                        center = Offset(mx, my)
                                    )
                                    drawCircle(
                                        color = mkColor,
                                        radius = if (mk.isEntry) 4.dp.toPx() else 3.2.dp.toPx(),
                                        center = Offset(mx, my)
                                    )
                                }
                            }
                        }

                        // Перекрестие при выборе свечи тапом
                        val activeIdx = inspectedIdx
                        if (activeIdx != null && activeIdx in startIdx until endIdx) {
                            val relIdx = activeIdx - startIdx
                            val cx = (relIdx + 0.5f) * slotW
                            val cy = priceToY(displayBars[activeIdx][4])
                            drawLine(
                                color = labelColor.copy(alpha = 0.7f),
                                start = Offset(cx, candleTop),
                                end = Offset(cx, topPad + plotH),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                            )
                            drawLine(
                                color = labelColor.copy(alpha = 0.7f),
                                start = Offset(0f, cy),
                                end = Offset(plotW, cy),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                            )
                        }
                    }

                    // Строка OHLC в левом верхнем углу
                    val o = selectedBar[1]; val h = selectedBar[2]; val l = selectedBar[3]; val c = selectedBar[4]
                    val ohlcColor = if (c >= o) bullColor else bearColor
                    Text(
                        "O ${formatAxisPrice(o)}  H ${formatAxisPrice(h)}  L ${formatAxisPrice(l)}  C ${formatAxisPrice(c)}",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = ohlcColor,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (renderState == "NO_DATA") {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                Icons.Outlined.CandlestickChart,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            when (renderState) {
                                "NO_DATA" -> "Загрузка свечей (Bybit / Binance / OKX)…"
                                "SOURCE_ERROR" -> "Ошибка формата OHLCV: данные не прошли валидацию"
                                else -> "График: $renderState"
                            },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Слияние живой (незакрытой) свечи WS с валидным историческим рядом.
 */
fun mergeLiveBar(validBars: List<List<Double>>, lastBar: List<Double>?): List<List<Double>> {
    if (validBars.isEmpty() || lastBar == null || lastBar.size < 6) return validBars
    if (lastBar.take(6).any { !it.isFinite() }) return validBars
    val ts = lastBar[0]
    val o = lastBar[1]; val h = lastBar[2]; val l = lastBar[3]; val c = lastBar[4]; val v = lastBar[5]
    if (ts <= 1_483_228_800_000.0 || o <= 0 || c <= 0 || h < o || h < c || l > o || l > c || h < l || v < 0) {
        return validBars
    }
    val lastClosedTs = validBars.last()[0]
    return when {
        ts == lastClosedTs -> validBars.dropLast(1) + listOf(lastBar)
        ts > lastClosedTs -> validBars + listOf(lastBar)
        else -> validBars
    }
}

/**
 * Валидация OHLCV (§2): ts возрастает и уникален, OHLC согласованы и > 0,
 * объём >= 0, ts после 2017. Возвращает очищенный список или null.
 */
fun validateBars(rows: List<List<Double>>): List<List<Double>>? {
    if (rows.size < 2) return null
    val out = ArrayList<List<Double>>(rows.size)
    var prevTs = -1.0
    for (r in rows) {
        if (r.size < 6) return null
        if (r.take(6).any { !it.isFinite() }) return null
        val ts = r[0]
        if (ts <= 1_483_228_800_000.0) return null   // до 2017-01-01 — мусор
        if (ts <= prevTs) return null                  // не возрастает/дубль
        val o = r[1]; val h = r[2]; val l = r[3]; val c = r[4]; val v = r[5]
        if (o <= 0 || c <= 0) return null
        if (h < o || h < c || l > o || l > c) return null
        if (h < l) return null
        if (v < 0) return null
        out.add(r)
        prevTs = ts
    }
    return out
}

private fun formatAxisPrice(p: Double): String = when {
    p >= 10_000 -> "%.0f".format(Locale.US, p)
    p >= 1_000 -> "%.1f".format(Locale.US, p)
    p >= 10 -> "%.2f".format(Locale.US, p)
    else -> "%.4f".format(Locale.US, p)
}

private fun formatBarTime(tsMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(tsMs))
