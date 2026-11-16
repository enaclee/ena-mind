package com.enamind.ai.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.enamind.ai.core.AppState
import com.enamind.ai.orderflow.GlowColorState

/**
 * ENA Dynamic Market Glow — мягкая световая подсветка нижней панели навигации и блоков по визуальному референсу.
 *
 * Логика в реальном времени (только реальные потоки Order Flow, без декоративных подстановок):
 *  - Зелёный — преимущество покупателей;
 *  - Красный — преимущество продавцов;
 *  - Нейтральный приглушённый свет — неопределённость / паритет / недостаток или потеря данных;
 *  - Яркость — сила преимущества (от слабого до яркого многофакторного подтверждения), при ослаблении затухает;
 *  - Анимация — плавное переливание слева направо, затем обратно справа налево (ping-pong);
 *  - Скорость отражает темп изменения рыночного давления; не блокирует касания и график.
 */
@Composable
fun DynamicMarketGlowPanel(
    state: AppState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val report by state.orderFlow.report.collectAsState()
    val glow = report.glow

    val targetColor = when (glow.colorState) {
        GlowColorState.GREEN -> Color(0xFF10B981)
        GlowColorState.RED -> Color(0xFFEF4444)
        GlowColorState.NEUTRAL -> Color(0xFF64748B)
    }
    val secondaryColor = when (glow.colorState) {
        GlowColorState.GREEN -> Color(0xFF34D399)
        GlowColorState.RED -> Color(0xFFFB7185)
        GlowColorState.NEUTRAL -> Color(0xFF94A3B8)
    }

    val animatedBaseColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 750),
        label = "glow_base_color"
    )
    val animatedEdgeColor by animateColorAsState(
        targetValue = secondaryColor,
        animationSpec = tween(durationMillis = 750),
        label = "glow_edge_color"
    )

    // При нейтральном/пустом состоянии оставляем едва заметный приглушённый контур,
    // при наличии преимущества яркость напрямую отражает силу сигнала (0..1).
    val targetIntensity = when (glow.colorState) {
        GlowColorState.NEUTRAL -> 0.20f
        else -> (0.28f + glow.strength.coerceIn(0f, 1f) * 0.72f)
    }
    val intensity by animateFloatAsState(
        targetValue = targetIntensity,
        animationSpec = tween(durationMillis = 650),
        label = "glow_brightness"
    )

    val speedFactor = glow.speedFactor.coerceIn(0.45f, 2.2f)
    val sweepDurationMs = (3200f / speedFactor).toInt().coerceIn(1300, 4200)

    val infiniteTransition = rememberInfiniteTransition(label = "market_glow_pingpong")
    val sweepPos by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = sweepDurationMs, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse // плавно слева направо, затем обратно справа налево
        ),
        label = "glow_sweep_pos"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("dynamic_market_glow_panel")
    ) {
        // Рассеянная световая аура за и снизу тёмной панели, повторяющая референс
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val cx = w * sweepPos
            val corner = 22.dp.toPx()

            // Мягкое внешнее ореольное свечение внизу и вокруг панели
            val auraCenter = Offset(cx, h * 0.68f)
            val auraRadius = (w * 0.48f).coerceAtLeast(120f)
            drawRoundRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        animatedBaseColor.copy(alpha = 0.36f * intensity),
                        animatedEdgeColor.copy(alpha = 0.16f * intensity),
                        Color.Transparent
                    ),
                    center = auraCenter,
                    radius = auraRadius
                ),
                topLeft = Offset(-6.dp.toPx(), -3.dp.toPx()),
                size = Size(w + 12.dp.toPx(), h + 9.dp.toPx()),
                cornerRadius = CornerRadius(corner + 4.dp.toPx(), corner + 4.dp.toPx())
            )

            // Вторичное затухающее отражение на противоположной стороне панели
            val oppositeX = w * (1f - sweepPos)
            drawRoundRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        animatedEdgeColor.copy(alpha = 0.14f * intensity),
                        Color.Transparent
                    ),
                    center = Offset(oppositeX, h * 0.55f),
                    radius = (w * 0.30f).coerceAtLeast(80f)
                ),
                topLeft = Offset(-2.dp.toPx(), -1.dp.toPx()),
                size = Size(w + 4.dp.toPx(), h + 4.dp.toPx()),
                cornerRadius = CornerRadius(corner, corner)
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp)),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 0.dp
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                content()
                // Светящаяся кромка по периметру и нижнему краю панели, двигающаяся ping-pong
                Canvas(modifier = Modifier.matchParentSize()) {
                    val w = size.width
                    val h = size.height
                    val corner = 22.dp.toPx()
                    val strokeW = (1.1f + 1.1f * intensity).dp.toPx()
                    val spotX = w * sweepPos
                    val halfSpan = (w * 0.38f).coerceAtLeast(90f)
                    val rimBrush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.0f to animatedBaseColor.copy(alpha = 0.14f * intensity),
                            ((spotX - halfSpan) / w).coerceIn(0.01f, 0.98f) to animatedBaseColor.copy(alpha = 0.25f * intensity),
                            (spotX / w).coerceIn(0.02f, 0.98f) to animatedEdgeColor.copy(alpha = 0.92f * intensity),
                            ((spotX + halfSpan) / w).coerceIn(0.03f, 0.99f) to animatedBaseColor.copy(alpha = 0.25f * intensity),
                            1.0f to animatedBaseColor.copy(alpha = 0.14f * intensity)
                        )
                    )
                    drawRoundRect(
                        brush = rimBrush,
                        topLeft = Offset(strokeW / 2f, strokeW / 2f),
                        size = Size(w - strokeW, h - strokeW),
                        cornerRadius = CornerRadius(corner, corner),
                        style = Stroke(width = strokeW)
                    )
                    // Акцентная световая полоска снизу панели
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                animatedBaseColor.copy(alpha = 0.45f * intensity),
                                animatedEdgeColor.copy(alpha = 0.95f * intensity),
                                animatedBaseColor.copy(alpha = 0.45f * intensity),
                                Color.Transparent
                            ),
                            startX = (spotX - halfSpan * 1.1f).coerceAtLeast(0f),
                            endX = (spotX + halfSpan * 1.1f).coerceAtMost(w)
                        ),
                        start = Offset(corner, h - 1.5.dp.toPx()),
                        end = Offset(w - corner, h - 1.5.dp.toPx()),
                        strokeWidth = (1.6f + 1.2f * intensity).dp.toPx()
                    )
                }
            }
        }
    }
}

/**
 * Тонкая световая полоска для верхней карточки состояния ORDER FLOW, синхронизированная с Dynamic Market Glow.
 */
@Composable
fun DynamicMarketGlowStrip(
    state: AppState,
    modifier: Modifier = Modifier
) {
    val report by state.orderFlow.report.collectAsState()
    val glow = report.glow
    val baseColor by animateColorAsState(
        targetValue = when (glow.colorState) {
            GlowColorState.GREEN -> Color(0xFF10B981)
            GlowColorState.RED -> Color(0xFFEF4444)
            GlowColorState.NEUTRAL -> Color(0xFF64748B)
        },
        animationSpec = tween(750),
        label = "strip_color"
    )
    val intensity by animateFloatAsState(
        targetValue = if (glow.colorState == GlowColorState.NEUTRAL) 0.22f
        else (0.30f + glow.strength.coerceIn(0f, 1f) * 0.70f),
        animationSpec = tween(650),
        label = "strip_intensity"
    )
    val speedFactor = glow.speedFactor.coerceIn(0.45f, 2.2f)
    val duration = (3000f / speedFactor).toInt().coerceIn(1200, 4000)
    val infinite = rememberInfiniteTransition(label = "strip_pingpong")
    val pos by infinite.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "strip_pos"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .testTag("dynamic_market_glow_strip")
    ) {
        val w = size.width
        val h = size.height
        val cx = w * pos
        val span = (w * 0.4f).coerceAtLeast(80f)
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    baseColor.copy(alpha = 0.08f * intensity),
                    baseColor.copy(alpha = 0.95f * intensity),
                    baseColor.copy(alpha = 0.08f * intensity)
                ),
                startX = (cx - span).coerceAtLeast(0f),
                endX = (cx + span).coerceAtMost(w)
            ),
            topLeft = Offset(0f, h * 0.25f),
            size = Size(w, h * 0.55f)
        )
    }
}
