package com.enamind.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enamind.ai.core.AppState
import com.enamind.ai.core.PythonBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * NEURAL PATH v8.1 (ТЗ §24 v3, §39 v4): визуализация РЕАЛЬНОГО пути
 * вычислений A→J из mm_graph. Источник истины — execution engine:
 * топология и статусы приходят из engine_run_graph, статичная карта —
 * без ложной активности (§24.2 no fake activity).
 *
 * Монохромный стиль (§39): чёрный фон, белые/серые узлы, свечение только
 * для активного пути, никаких радужных карточек и эмодзи.
 */
@Composable
fun NeuralPathScreen(state: AppState) {
    var graphResult by remember { mutableStateOf<JSONObject?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detailNode by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // заголовок + кнопка запуска
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("NEURAL PATH · граф исполнения A→B", fontSize = 12.sp,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                if (running) return@TextButton
                running = true; error = null
                Thread {
                    val r = PythonBridge.callEngine("engine_run_graph", state.market.value.symbol, 40.0)
                    graphResult = r
                    running = false
                }.apply { isDaemon = true }.start()
            }) {
                Text(if (running) "выполняется…" else "ЗАПУСТИТЬ ПРОГОН", fontSize = 11.sp,
                    fontWeight = FontWeight.Bold)
            }
        }

        error?.let {
            Text(it, fontSize = 9.sp, color = Color(0xFFFF4D5E),
                modifier = Modifier.padding(horizontal = 8.dp))
        }

        val scroll = rememberScrollState()
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 8.dp)) {
            if (graphResult == null) {
                Text(
                    "статичная карта архитектуры — анимация только при реальном прогоне\n\n" +
                        "Путь: ДАННЫЕ → ВАЛИДАЦИЯ → ПРИЗНАКИ → ПАРАЛЛЕЛЬНЫЕ СПЕЦИАЛИСТЫ →\n" +
                        "СОВЕТЫ КОНТУРОВ → ADVERSARIAL REVIEW → МЕТА-СОВЕТ → ПРОГНОЗ →\n" +
                        "ВЫВОД → LEDGER ПАМЯТИ. Нажмите «ЗАПУСТИТЬ ПРОГОН» — узлы получат\n" +
                        "реальные статусы выполненного анализа (§24.2: без ложной активности).",
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StaticTopology()
            } else {
                val r = graphResult!!
                val budgetLeft = r.optDouble("budget_left_sec", 0.0).let { if (it.isFinite()) it else 0.0 }
                Text(
                    String.format(
                        java.util.Locale.US,
                        "run %s · %dмс · бюджет остаток %.1fs",
                        r.optString("run_id").takeLast(12), r.optInt("elapsed_ms"), budgetLeft
                    ),
                    fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                RunTopology(r, onNodeClick = { detailNode = it })
                // трассировка
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Text("TRACE (последние события)", fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                r.optJSONArray("trace")?.let { tr ->
                    val start = maxOf(0, tr.length() - 14)
                    for (i in start until tr.length()) {
                        val e = tr.optJSONObject(i) ?: continue
                        Text(
                            "${e.optString("node_id")}: ${e.optString("from")} → ${e.optString("to")} ${e.optString("detail")}",
                            fontSize = 7.sp, fontFamily = FontFamily.Monospace,
                            color = when (e.optString("to")) {
                                "completed" -> Color(0xFF00C896)
                                "failed", "cancelled" -> Color(0xFFFF4D5E)
                                "degraded", "skipped" -> Color(0xFFFFB86C)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                // evidence
                r.optJSONArray("evidence")?.let { ev ->
                    if (ev.length() > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text("EVIDENCE STORE (${ev.length()})", fontSize = 9.sp,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        for (i in 0 until minOf(ev.length(), 8)) {
                            val e = ev.optJSONObject(i) ?: continue
                            val q = e.optDouble("quality", 0.0).let { if (it.isFinite()) it else 0.0 }
                            Text(
                                String.format(
                                    java.util.Locale.US,
                                    "%s [%s] q=%.2f: %s",
                                    e.optString("evidence_id"), e.optString("source_id"),
                                    q, e.optString("claim").take(70)
                                ),
                                fontSize = 7.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // детали узла (§24.3: что делает, результат, ошибка)
    detailNode?.let { nid ->
        val nodeInfo = graphResult?.optJSONObject("nodes")?.optJSONObject(nid)
        AlertDialog(
            onDismissRequest = { detailNode = null },
            title = { Text(nid, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    Text("статус: %s".format(nodeInfo?.optString("status") ?: "?"),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("тип: %s".format(nodeInfo?.optString("type") ?: "?"),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    nodeInfo?.optString("error")?.takeIf { it.isNotEmpty() }?.let {
                        Text("ошибка: $it", fontSize = 10.sp, color = Color(0xFFFF4D5E))
                    }
                    val res = graphResult?.optJSONObject("results")?.optJSONObject(nid)
                    res?.let { prettyNodeResult(it) }
                }
            },
            confirmButton = { TextButton(onClick = { detailNode = null }) { Text("Закрыть") } }
        )
    }
}

@Composable
private fun prettyNodeResult(res: JSONObject) {
    Spacer(Modifier.height(6.dp))
    Text("результат:", fontSize = 10.sp, fontWeight = FontWeight.Bold)
    val keys = res.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val v = res.opt(k)
        if (v is JSONObject || v is JSONArray) continue // вложенности не распечатываем целиком
        Text("  $k = $v", fontSize = 8.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Статичная топология (idle): те же узлы, без ложных статусов (§24.2). */
@Composable
private fun StaticTopology() {
    val layers = listOf(
        "A INTAKE" to listOf("exchange rest/ws", "on-chain", "news", "local store"),
        "B QUALITY" to listOf("schema", "timestamps", "dedup", "stale", "coverage"),
        "C FEATURES" to listOf("ohlcv", "vol/ATR", "structure", "orderflow", "deriv"),
        "D SPECIALISTS" to listOf("market×18", "trend×6", "onchain×10"),
        "E COUNCILS" to listOf("market", "onchain", "trend"),
        "F CROSS-EXAM" to listOf("bull case", "bear case", "contradictions"),
        "G META-COUNCIL" to listOf("fusion", "on-chain review", "calibration"),
        "H FORECAST" to listOf("6ч p(u/f/d)", "24h outlook", "scenarios"),
        "I OUTPUT" to listOf("compact result", "trace"),
        "J LEDGER" to listOf("snapshot", "feedback")
    )
    LayersView(layers) { null }
}

/** Реальная топология прогона: статусы из результата. */
@Composable
private fun RunTopology(r: JSONObject, onNodeClick: (String) -> Unit) {
    val nodes = r.optJSONObject("nodes") ?: return
    // группа узлов по слоям графа
    val layers = listOf(
        "A INTAKE" to listOf("A_INTAKE"),
        "B QUALITY" to listOf("B_QUALITY"),
        "C FEATURES" to listOf("C_FEATURES"),
        "D SPECIALISTS" to listOf("D_MARKET", "D_TREND", "D_ONCHAIN"),
        "E COUNCILS" to listOf("E_COUNCILS"),
        "F CROSS-EXAM" to listOf("F_CROSS"),
        "G META-COUNCIL" to listOf("G_META"),
        "H FORECAST" to listOf("H_FORECAST"),
        "I OUTPUT" to listOf("I_OUTPUT"),
        "J LEDGER" to listOf("J_LEDGER")
    )
    LayersView(layers) { nid ->
        nodes.optJSONObject(nid)?.optString("status")
    }
}

/**
 * Слои слева направо в горизонтальном скролле (§24.1): крупный блок — контур,
 * маленькие узлы внутри; тонкие связи (стрелки ▸). Статусы — только реальные.
 */
@Composable
private fun LayersView(layers: List<Pair<String, List<String>>>, statusOf: (String) -> String?) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        layers.forEachIndexed { li, (title, ids) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    RoundedCornerShape(10.dp)
                ).padding(6.dp)
            ) {
                Text(title, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                ids.forEach { id ->
                    val st = statusOf(id)
                    val (bg, fg) = when (st) {
                        "completed" -> Color(0xFF00C896).copy(alpha = 0.18f) to Color(0xFF00C896)
                        "running" -> Color(0xFFFFFFFF).copy(alpha = 0.12f) to Color.White
                        "degraded" -> Color(0xFFFFB86C).copy(alpha = 0.15f) to Color(0xFFFFB86C)
                        "failed", "cancelled" -> Color(0xFFFF4D5E).copy(alpha = 0.15f) to Color(0xFFFF4D5E)
                        "skipped" -> Color.Gray.copy(alpha = 0.12f) to Color.Gray
                        null -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                        else -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        nodeIdLabel(id),
                        fontSize = 7.sp, fontFamily = FontFamily.Monospace,
                        color = fg,
                        modifier = Modifier
                            .background(bg, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
            if (li < layers.size - 1) {
                Text("▸", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun nodeIdLabel(id: String): String = when (id) {
    "A_INTAKE" -> "intake"
    "B_QUALITY" -> "quality"
    "C_FEATURES" -> "features"
    "D_MARKET" -> "market×18"
    "D_TREND" -> "trend×6"
    "D_ONCHAIN" -> "onchain×10"
    "E_COUNCILS" -> "councils"
    "F_CROSS" -> "cross-exam"
    "G_META" -> "meta+review"
    "H_FORECAST" -> "forecast"
    "I_OUTPUT" -> "output+trace"
    "J_LEDGER" -> "ledger"
    else -> id
}
