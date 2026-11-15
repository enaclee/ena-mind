package com.enamind.ai.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enamind.ai.core.AppState
import com.enamind.ai.intelligence.RealTimeLeadEngine
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * LiquidTrendIndicator (ТЗ п.7) — нижняя плавная изогнутая панель-волна реального времени.
 *
 * Позиционируется в самом низу приложения (под нижней навигацией),
 * плавно переливается между зелёным (LONG) и красным (SHORT),
 * вспыхивает жёлтым примерно на 1 секунду только при сильных реальных триггерах
 * (крупные киты, всплеск агрессивных сделок, каскад ликвидаций, |Z| >= 2.2σ, спред >= 15 bps),
 * и отображает честный процент точности упреждающей памяти [RealtimePulseVault].
 */
@Composable
fun LiquidTrendIndicator(
    state: AppState,
    modifier: Modifier = Modifier
) {
    val pulse by state.engine.realTimePulse.collectAsState()
    var flashYellow by remember { mutableStateOf(false) }
    var lastFlashAtMs by remember { mutableLongStateOf(0L) }
    var lastTriggerKey by remember { mutableStateOf("") }
    var dialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(pulse.tsMs, pulse.strongTrigger, pulse.triggerReason) {
        val now = System.currentTimeMillis()
        val key = "${pulse.symbol}:${pulse.triggerReason}"
        if (pulse.strongTrigger &&
            pulse.triggerReason.isNotBlank() &&
            (key != lastTriggerKey || now - lastFlashAtMs >= 5_000L)
        ) {
            lastTriggerKey = key
            lastFlashAtMs = now
            flashYellow = true
            delay(1_000L)
            flashYellow = false
        }
    }

    val isLong = pulse.direction != "SHORT"
    val targetColor = when {
        flashYellow -> Color.EnaYellow
        isLong -> Color.EnaGreen
        else -> Color.EnaRed
    }
    val waveColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = if (flashYellow) 220 else 650),
        label = "liquid_wave_color"
    )
    val secondaryColor by animateColorAsState(
        targetValue = when {
            flashYellow -> Color(0xFFFFE082)
            isLong -> Color(0xFF00B4D8)
            else -> Color(0xFFFF758F)
        },
        animationSpec = tween(durationMillis = 650),
        label = "liquid_wave_secondary"
    )

    val infinite = rememberInfiniteTransition(label = "liquid_phase")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    val accLabel = remember(pulse.accuracyPct, pulse.verifiedCount) {
        val acc = pulse.accuracyPct
        when {
            acc != null && acc.isFinite() && pulse.verifiedCount > 0 ->
                "Точность: ${Math.round(acc)}% (${pulse.verifiedCount})"
            else -> "Точность: обучение…"
        }
    }

    val surfaceBg = MaterialTheme.colorScheme.surface
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .background(surfaceBg)
            .navigationBarsPadding()
            .heightIn(min = 50.dp)
            .testTag("liquid_trend_indicator")
            .clickable { dialogOpen = true },
        color = surfaceBg,
        tonalElevation = 2.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            // Плавная изогнутая жидкая волна со свечением и переливом
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas

                // Мягкое фоновое свечение
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            waveColor.copy(alpha = if (flashYellow) 0.22f else 0.12f),
                            waveColor.copy(alpha = if (flashYellow) 0.34f else 0.20f)
                        )
                    )
                )

                val amp = (h * (0.13f + 0.08f * kotlin.math.abs(pulse.score).toFloat())).coerceIn(3f, h * 0.25f)
                val baseLine = h * 0.36f
                val wave1 = Path().apply {
                    val y0 = baseLine + sin(phase) * amp
                    val yMid1 = baseLine + sin(phase + 1.5f) * amp * 1.15f
                    val yMid2 = baseLine - sin(phase + 3.1f) * amp * 1.15f
                    val yEnd = baseLine + sin(phase + 4.7f) * amp
                    moveTo(0f, y0)
                    cubicTo(
                        w * 0.32f, yMid1,
                        w * 0.68f, yMid2,
                        w, yEnd
                    )
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(
                    path = wave1,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            waveColor.copy(alpha = if (flashYellow) 0.42f else 0.26f),
                            secondaryColor.copy(alpha = if (flashYellow) 0.46f else 0.30f),
                            waveColor.copy(alpha = if (flashYellow) 0.42f else 0.26f)
                        )
                    )
                )

                val crest = Path().apply {
                    val y0 = baseLine + sin(phase) * amp
                    val yMid1 = baseLine + sin(phase + 1.5f) * amp * 1.15f
                    val yMid2 = baseLine - sin(phase + 3.1f) * amp * 1.15f
                    val yEnd = baseLine + sin(phase + 4.7f) * amp
                    moveTo(0f, y0)
                    cubicTo(
                        w * 0.32f, yMid1,
                        w * 0.68f, yMid2,
                        w, yEnd
                    )
                }
                // Светящийся гребень
                drawPath(
                    path = crest,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            waveColor.copy(alpha = 0.85f),
                            secondaryColor,
                            waveColor.copy(alpha = 0.85f)
                        ),
                        startX = 0f,
                        endX = w
                    ),
                    style = Stroke(width = 5f, cap = StrokeCap.Round)
                )
                drawLine(
                    color = waveColor.copy(alpha = 0.35f),
                    start = Offset(0f, 0.5f),
                    end = Offset(w, 0.5f),
                    strokeWidth = 1f
                )
            }

            // Компактная информационная панель поверх волны
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = waveColor.copy(alpha = 0.20f),
                    border = BorderStroke(0.8.dp, waveColor.copy(alpha = 0.65f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isLong) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown,
                            contentDescription = if (isLong) "Вектор вверх LONG" else "Вектор вниз SHORT",
                            modifier = Modifier.size(14.dp),
                            tint = waveColor
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (isLong) "LONG" else "SHORT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = waveColor
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (flashYellow && pulse.triggerReason.isNotBlank()) {
                            "⚡ ${pulse.triggerReason}"
                        } else {
                            "${pulse.symbol} · 5–15м · ${(pulse.confidence * 100).toInt()}%"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = pulse.factorsSummary,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.80f),
                    border = BorderStroke(0.8.dp, waveColor.copy(alpha = 0.42f))
                ) {
                    Text(
                        text = accLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

    if (dialogOpen) {
        PulseDetailsDialog(
            pulse = pulse,
            waveColor = waveColor,
            onDismiss = { dialogOpen = false }
        )
    }
}

@Composable
private fun PulseDetailsDialog(
    pulse: RealTimeLeadEngine.RealTimeTrendPulse,
    waveColor: Color,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Реал-тайм тренд · ${pulse.symbol}",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Направление: ${pulse.direction} (${pulse.leadWindowLabel})",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = waveColor
                )
                val scoreTxt = if (pulse.score.isFinite()) String.format(Locale.US, "%+.2f", pulse.score) else "0.00"
                Text(
                    "Индекс потока: $scoreTxt · Уверенность: ${(pulse.confidence * 100).toInt()}%",
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMonoFontFamily
                )
                if (pulse.triggerReason.isNotBlank()) {
                    Text(
                        "Активный триггер: ${pulse.triggerReason}",
                        fontSize = 11.sp,
                        color = Color.EnaYellow,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    "Факторы: ${pulse.factorsSummary}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pulse.factorVotes.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Голоса микро-факторов:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    pulse.factorVotes.forEach { (k, v) ->
                        val c = when (v) {
                            "LONG" -> Color.EnaGreen
                            "SHORT" -> Color.EnaRed
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(k, fontSize = 10.sp, fontFamily = JetBrainsMonoFontFamily, modifier = Modifier.weight(1f))
                            Text(v, fontSize = 10.sp, fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Bold, color = c)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                val accTxt = pulse.accuracyPct?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f%%", it) } ?: "накопление данных"
                val recTxt = pulse.recentAccuracyPct?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"
                Text(
                    "Память RealtimePulseVault: проверено ${pulse.verifiedCount}, общая точность $accTxt, недавняя $recTxt.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Зелёный = рост, красный = снижение, жёлтая вспышка (~1с) = резкий подтверждённый триггер рынка.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}
