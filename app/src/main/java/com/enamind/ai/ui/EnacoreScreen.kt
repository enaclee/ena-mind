package com.enamind.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enamind.ai.core.AppState
import com.enamind.ai.core.CoreCell
import com.enamind.ai.overlay.WidgetOverlayService
import java.util.Locale

@Composable
fun EnacoreScreen(state: AppState) {
    val core by state.enacore.state.collectAsState()
    val instrument by state.activeInstrument.collectAsState()
    var longSide by remember { mutableStateOf(true) }
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().testTag("enacore_screen"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("ENΛCORE", style = MaterialTheme.typography.headlineSmall)
            Text("${instrument.symbol} · ${instrument.category} · 12 агентов", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(core.verdict, color = if (core.verdict == "VETO") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Text(core.reason)
                    LinearProgressIndicator(progress = { ((core.pressure + 1) / 2).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("Давление ${String.format(Locale.US, "%+.0f%%", core.pressure * 100)} · спред ${core.spreadPct?.let { String.format(Locale.US, "%.3f%%", it) } ?: "—"}")
                    Text("Бары ${core.bars} · сделки ${core.trades} · OI ${core.oiSamples}\nСтакан ${core.bookAgeMs} мс · лента ${core.tradeAgeMs} мс · события ${core.events}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(longSide, { longSide = true }, label = { Text("Long") }, modifier = Modifier.heightIn(min = 48.dp))
                FilterChip(!longSide, { longSide = false }, label = { Text("Short") }, modifier = Modifier.heightIn(min = 48.dp))
            }
            ProbabilityMatrix(if (longSide) core.longCells else core.shortCells)
            Text("Net-движение цены после комиссии. Частота достижения цели по истории; не вероятность прибыльной сделки. * — меньше 20 независимых наблюдений, AWO не подтверждает вход.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        items(core.agents.chunked(2).size) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                core.agents.chunked(2)[row].forEach { a ->
                    Card(Modifier.weight(1f)) { Column(Modifier.padding(12.dp)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall)
                        Text(if (a.confidence == 0.0) "Нет данных" else String.format(Locale.US, "%+.0f%%", a.score * 100))
                        Text("Вес ${a.weight} · уверенность ${(a.confidence * 100).toInt()}%", fontSize = 10.sp)
                    } }
                }
            }
        }
        item {
            Text("Плавающие виджеты", style = MaterialTheme.typography.titleMedium)
            WidgetOverlayService.KINDS.forEach { (id, label) ->
                OutlinedButton(onClick = { WidgetOverlayService.show(ctx, id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("+ $label") }
            }
            Text("Перемещение — за заголовок, размер — за нижний угол; угол и прозрачность — в настройках окна. Окна сохраняются при сворачивании приложения.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ProbabilityMatrix(cells: List<CoreCell>) {
    Column(Modifier.fillMaxWidth().testTag("core_probability_matrix"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row { Text("Net", Modifier.weight(1f)); listOf(15, 30, 60, 120).forEach { Text("${it}м", Modifier.weight(1f), fontSize = 11.sp) } }
        listOf(.5, .75, 1.0, 1.25, 1.5).forEach { target ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$target%", Modifier.weight(1f).padding(vertical = 12.dp), fontSize = 11.sp)
                listOf(15, 30, 60, 120).forEach { horizon ->
                    val cell = cells.firstOrNull { it.target == target && it.horizon == horizon }
                    val p = cell?.probability
                    val color = if (p == null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary.copy(alpha = (.08 + .32 * p).toFloat())
                    var detail by remember(target, horizon) { mutableStateOf(false) }
                    Surface(onClick = { detail = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(8.dp), color = color) {
                        Box(Modifier.padding(8.dp)) { Text(if (p == null) "—" else "${(p * 100).toInt()}%${if (cell?.calibrated == true) "" else "*"}", fontSize = 11.sp) }
                    }
                    if (detail) AlertDialog(onDismissRequest = { detail = false }, title = { Text("Net $target% · ${horizon}м") },
                        text = { Text("Независимых исторических окон: ${cell?.samples ?: 0}. Учтена комиссия входа и выхода. Стоп, проскальзывание и возможный убыток до достижения цели не входят в эту частоту.") },
                        confirmButton = { TextButton(onClick = { detail = false }) { Text("Готово") } })
                }
            }
        }
    }
}
