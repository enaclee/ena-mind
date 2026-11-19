package com.enamind.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.enamind.ai.core.Settings
import kotlin.math.*

@Composable
fun RgbThemeDialog(settings: Settings, onClose: () -> Unit) {
    val initial = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(settings.accentColor, it) } }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var saturation by remember { mutableFloatStateOf(initial[1]) }
    var brightness by remember { mutableFloatStateOf(initial[2]) }
    var dark by remember { mutableStateOf(settings.rgbDarkBase) }
    var width by remember { mutableFloatStateOf(1f) }
    fun save() { settings.accentColor = Color.hsv(hue, saturation, brightness).toArgb() }
    fun choose(p: Offset) {
        val dx = p.x - width / 2; val dy = p.y - width / 2
        hue = ((atan2(dy, dx) * 180f / PI.toFloat() + 360f) % 360f)
        saturation = (hypot(dx, dy) / (width / 2)).coerceIn(0f, 1f)
        save()
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("RGB · ваш цвет") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f).onSizeChanged { width = it.width.toFloat() }
                .pointerInput(Unit) { detectTapGestures { choose(it) } }
                .pointerInput(Unit) { detectDragGestures(onDragStart = { choose(it) }) { change, _ -> change.consume(); choose(change.position) } }) {
                for (angle in 0 until 360 step 2) drawArc(Color.hsv(angle.toFloat(), 1f, 1f), angle.toFloat(), 2.5f, true)
                drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), radius = size.width / 2))
                val angle = hue * PI.toFloat() / 180f
                val point = center + Offset(cos(angle), sin(angle)) * (size.width / 2 * saturation)
                drawCircle(Color.Black, 9.dp.toPx(), point, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, 9.dp.toPx(), point, style = Stroke(1.dp.toPx()))
            }
            Text("Яркость")
            Slider(brightness, { brightness = it; save() }, valueRange = .15f..1f)
            Text(String.format("#%06X", settings.accentColor and 0xFFFFFF))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (dark) "Тёмная база" else "Светлая база")
                Switch(dark, { dark = it; settings.rgbDarkBase = it })
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Готово") } })
}
