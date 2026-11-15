package com.enamind.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ДВИЖОК (вкладка) — единый движок ENA-MIND v8.0 «ЛЕС И ПАУТИНА»:
 * - ПРОГНОЗ 15 минут (P UP/FLAT/DOWN, сессии с версионированием);
 * - ЛЕС: 18 агентов-ветвей по пластам данных (нажмите для деталей);
 * - ПАУТИНА: связи между агентами, согласованность леса (web_coherence);
 * - ГЛУБОКИЙ АНАЛИЗ A→Б (цепочка v6.1: данные → режим → аналитики → совет →
 *   прогноз → risk gate) с живым прогрессом узлов и audit trail;
 * - watchlist, совет, журнал вычислений, память, история.
 * Режим SHADOW (без торговли). Внешние AI не используются — только движок.
 */
@Composable
fun NeuralEngineScreen(state: AppState, onOpenHistory: () -> Unit = {}) {
    val result by state.engine.result.collectAsState()
    val running by state.engine.running.collectAsState()
    val watch by state.engine.watch.collectAsState()
    val chainNodes by state.chainNodes.collectAsState()
    val chainRunning by state.chainRunning.collectAsState()
    val report by state.lastReport.collectAsState()
    var log by remember { mutableStateOf<JSONObject?>(null) }
    var memory by remember { mutableStateOf<JSONObject?>(null) }
    var history by remember { mutableStateOf<JSONArray?>(null) }
    var detailAgent by remember { mutableStateOf<JSONObject?>(null) }
    var auditOpen by remember { mutableStateOf(false) }

    // журнал/память подтягиваем раз в 12с (не в каждом кадре); Python — только в IO.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                log = com.enamind.ai.core.PythonBridge.engineLog(40)
                memory = com.enamind.ai.core.PythonBridge.engineMemory()
                history = com.enamind.ai.core.PythonBridge.engineForecastHistory(30)
                    .optJSONArray("rows")
            }
            delay(12_000)
        }
    }

    // если Python-ядро не инициализировалось — показываем причину честно, а не пустой экран
    val initError = com.enamind.ai.core.PythonBridge.lastInitError

    Column(modifier = Modifier.fillMaxSize()) {
        // заголовок: статус движка
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ДВИЖОК v9.0 · ЛЕС И ПАУТИНА", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.width(7.dp).height(7.dp)
                .background(if (running) Color(0xFF00C896) else MaterialTheme.colorScheme.onSurfaceVariant,
                    RoundedCornerShape(4.dp)))
            Text(" 5с-цикл · боты + агенты · SHADOW", fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("${result?.optJSONObject("data")?.optString("grade") ?: "—"} · " +
                "${result?.optJSONObject("data")?.optInt("n_available", 0)}/" +
                "${result?.optJSONObject("data")?.optInt("n_total", 0)} источников",
                fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        val scroll = rememberScrollState()
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 8.dp)) {

            // ошибка инициализации Python-ядра (видимая причина, а не тихий пустой экран)
            initError?.let { errText ->
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(Color(0x33FF4D5E), RoundedCornerShape(8.dp))
                    .padding(8.dp)) {
                    Text("⚠ Python-ядро не запустилось — анализ недоступен",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF4D5E))
                    Text(errText, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6)
                    Text("Полный отчёт: Настройки → Диагностика. Приложение при этом работает.",
                        fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
            }

            // ПРОГНОЗ 6 ЧАСОВ
            ForecastPanel(result)

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // TREND INTELLIGENCE — bias/сила/вход/риск раздельно
            Spacer(Modifier.height(6.dp))
            TrendPanel(result?.optJSONObject("trend"))

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // 24H OUTLOOK — отдельный горизонт с вероятностями
            Spacer(Modifier.height(6.dp))
            Outlook24Panel(result?.optJSONObject("outlook_24h") ?: result?.optJSONObject("outlook24h"))

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // v8.1 (ТЗ §6): ON-CHAIN (OCIE) — второй контур
            Spacer(Modifier.height(6.dp))
            OnchainPanel(result?.optJSONObject("onchain"))

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // v8.1 (ТЗ §42): MARKET DISCOVERY — найденные формации
            Spacer(Modifier.height(6.dp))
            DiscoveryPanel(result?.optJSONObject("discovery"))

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ЛЕС: агенты по пластам данных
            Spacer(Modifier.height(6.dp))
            val agents = result?.optJSONArray("agents")
            Text("ЛЕС АГЕНТОВ${if (agents != null) " · ${agents.length()} ветвей" else ""} (нажмите для деталей)",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (agents != null) {
                // группировка по пластам — как кроны деревьев в лесу
                val clusters = linkedMapOf(
                    "МИКРОСТРУКТУРА" to listOf("ORDER_FLOW_ANALYST", "LIQUIDITY_ANALYST", "WHALE_WATCH"),
                    "ЦЕНА И РЕЖИМ" to listOf("PRICE_ANALYST", "VOLATILITY_ANALYST",
                        "BTC_CORRELATION_ANALYST", "SESSION_ANALYST"),
                    "ПОЗИЦИОНИРОВАНИЕ" to listOf("DERIVATIVES_ANALYST", "CROSS_EXCHANGE_ANALYST",
                        "LIQUIDATION_CASCADE", "OPTIONS_ANALYST", "BASIS_ANALYST"),
                    "ВНЕШНИЙ КОНТЕКСТ" to listOf("SENTIMENT_ANALYST", "DOMINANCE_ANALYST"),
                    "ОНЧЕЙН И DEFI" to listOf("ONCHAIN_ANALYST", "DEFI_FLOW_ANALYST"),
                    "СТАТИСТИКА" to listOf("STATISTICAL_AI", "ANOMALY_DETECTOR")
                )
                val byName = HashMap<String, JSONObject>()
                for (i in 0 until agents.length()) {
                    agents.optJSONObject(i)?.let { byName[it.optString("agent")] = it }
                }
                clusters.forEach { (cluster, names) ->
                    Text(cluster, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                    names.forEach { n -> byName[n]?.let { AgentRow(it) { detailAgent = it } } }
                }
            } else {
                // живой таймер прогрева вместо статичной надписи
                var warmSec by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(1_000); warmSec++
                    }
                }
                Text("движок прогревается… ${warmSec}с — грузит свечи 5 таймфреймов и подключает потоки 3 бирж; первый прогноз через ~10-20с",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ПАУТИНА СВЯЗЕЙ
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(6.dp))
            WebPanel(result?.optJSONObject("council")?.optJSONObject("web"))

            // ГЛУБОКИЙ АНАЛИЗ A→Б (цепочка v6.1)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(6.dp))
            DeepAnalysisSection(
                state = state,
                chainNodes = chainNodes,
                chainRunning = chainRunning,
                report = report,
                auditOpen = auditOpen,
                onToggleAudit = { auditOpen = !auditOpen }
            )

            // WATCHLIST
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WATCHLIST · 6ч прогнозы по 10 активам (фон)", fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text("история ▸", fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.clickable { onOpenHistory() })
            }
            val rows = watch.entries.sortedBy { NeuralEngineOrder.indexOf(it.key) }
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { (sym, r) ->
                    val side = r.optString("side")
                    val color = when (side) {
                        "UP" -> Color(0xFF00C896)
                        "DOWN" -> Color(0xFFFF4D5E)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(sym.dropLast(4), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text("${Math.round(r.optDouble("p_up") * 100)}↑ " +
                            "${Math.round(r.optDouble("p_down") * 100)}↓",
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = color)
                        Text(r.optString("grade"), fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // СОВЕТ
            Spacer(Modifier.height(8.dp))
            Text("NEURAL COUNCIL", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            val council = result?.optJSONObject("council")
            council?.let { c ->
                Text("${c.optString("status")} — ${c.optString("note")}", fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                c.optJSONArray("contradictions")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        Text("⚠ ${arr.optString(i)}", fontSize = 9.sp, color = Color(0xFFFFB86C))
                    }
                }
            }

            // ЖУРНАЛ ВЫЧИСЛЕНИЙ
            Spacer(Modifier.height(8.dp))
            Text("ЖУРНАЛ ВЫЧИСЛЕНИЙ", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            log?.optJSONArray("entries")?.let { arr ->
                val start = maxOf(0, arr.length() - 12)
                for (i in start until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    Text("%s [%s] %s".format(timeShort(e.optLong("ts")), e.optString("stage"),
                        e.optString("msg")),
                        fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ПАМЯТЬ: точность моделей
            Spacer(Modifier.height(8.dp))
            Text("ПАМЯТЬ ПРОГНОЗОВ (точность)", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            memory?.let { mem ->
                val st = mem.optJSONObject("stats")
                val checked = st?.optJSONObject("checked")
                if (checked == null || checked.optInt("n", 0) == 0) {
                    Text("проверенных 6-часовых прогнозов пока нет — накапливаются в SHADOW-режиме " +
                        "(первая проверка через 6 часов после запуска)", fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    MemRow("проверено", "${checked.optInt("n")}")
                    MemRow("hit-rate", "${Math.round(checked.optDouble("hit_rate").let { if (it.isFinite()) it else 0.0 } * 100)}%")
                    checked.optDouble("mean_abs_move_pct", Double.NaN).takeIf { it.isFinite() }?.let {
                        MemRow("среднее |движение| 6ч", neFmt("%.3f%%", it))
                    }
                    st.optJSONObject("drift")?.let { d ->
                        val drVal = d.optDouble("drift", 0.0).let { if (it.isFinite()) it * 100 else 0.0 }
                        MemRow("дрейф (последние 50)", neFmt("%+.0fпп", drVal) + if (d.optBoolean("alert")) " ⚠" else "")
                    }
                }
                // точность агентов
                val acc = mem.optJSONObject("agent_accuracy")
                if (acc != null && acc.length() > 0) {
                    val keys = acc.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val a = acc.optJSONObject(k) ?: continue
                        if (a.optInt("n", 0) >= 5) {
                            MemRow("агент $k", "hit ${Math.round(a.optDouble("hit_rate") * 100)}% (n=${a.optInt("n")})")
                        }
                    }
                }
                val w = mem.optJSONObject("weights")
                if (w != null && w.length() > 0) {
                    Text("веса агентов: " + w.toString(), fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val cal = mem.optJSONObject("calibration")
                cal?.optJSONArray("bins")?.let { bins ->
                    if (cal.optString("status") == "OK") {
                        Text("калибровка (заявлено/факт):", fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (i in 0 until bins.length()) {
                            val b = bins.optJSONObject(i)
                            Text("  %s: %.2f → %.2f (n=%d)".format(b.optString("bin"),
                                b.optDouble("claimed"), b.optDouble("observed"), b.optInt("n")),
                                fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // ИСТОРИЯ ПРОГНОЗОВ
            Spacer(Modifier.height(8.dp))
            Text("ИСТОРИЯ ПРОГНОЗОВ 6Ч", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            history?.let { h ->
                if (h.length() == 0) {
                    Text("пусто — первая запись появится при первом расчёте сессии", fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for (i in 0 until minOf(h.length(), 12)) {
                    val r = h.optJSONObject(i) ?: continue
                    val p = r.optJSONObject("p") ?: JSONObject()
                    val hit = when (r.optInt("hit", -1)) {
                        1 -> "✓"; 0 -> "✗"; else -> "…"
                    }
                    val hitColor = when (r.optInt("hit", -1)) {
                        1 -> Color(0xFF00C896); 0 -> Color(0xFFFF4D5E); else -> Color.Gray
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(hit, color = hitColor, fontSize = 12.sp, modifier = Modifier.width(18.dp))
                        Text("%s %s %d↑/%d→/%d↓".format(
                            r.optString("asset").dropLast(4), r.optString("side"),
                            Math.round(p.optDouble("p_up") * 100),
                            Math.round(p.optDouble("p_flat") * 100),
                            Math.round(p.optDouble("p_down") * 100)),
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.weight(1f))
                        if (r.has("actual_return_pct")) {
                            Text("%+.3f%%".format(r.optDouble("actual_return_pct")),
                                fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                color = if (r.optDouble("actual_return_pct") >= 0) Color(0xFF00C896) else Color(0xFFFF4D5E))
                        } else {
                            Text(timeShort((r.optLong("ts") / 1000)), fontSize = 8.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    // детали агента
    detailAgent?.let { a ->
        AlertDialog(
            onDismissRequest = { detailAgent = null },
            title = { Text(a.optString("agent"), fontSize = 13.sp, fontFamily = FontFamily.Monospace) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("статус: %s · сторона: %s · conf %.2f".format(
                        a.optString("status"), a.optString("side"), a.optDouble("confidence")),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    a.optJSONObject("features")?.let { f ->
                        if (f.length() > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text("features:", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            val keys = f.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                Text("  $k = ${f.opt(k)}", fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    SectionList("аргументы:", a.optJSONArray("evidence"))
                    SectionList("противоречия:", a.optJSONArray("contradictions"))
                    SectionList("условия отмены:", a.optJSONArray("invalidate"))
                }
            },
            confirmButton = { TextButton(onClick = { detailAgent = null }) { Text("Закрыть") } }
        )
    }
}

private val NeuralEngineOrder = com.enamind.ai.core.Settings.SYMBOLS

/**
 * ПАУТИНА СВЯЗЕЙ (v8.0): какие связи леса активны, где согласие и конфликты,
 * согласованность леса web_coherence. Именно паутина не даёт агентам «голосовать»
 * изолированно: конфликт паутины гасит уверенность прогноза, согласие усиливает.
 */
@Composable
private fun WebPanel(web: JSONObject?) {
    Text("ПАУТИНА СВЯЗЕЙ", fontSize = 10.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary)
    if (web == null) {
        Text("нет данных — паутина появится после первого прогона леса", fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val active = web.optInt("active", 0)
    val agree = web.optInt("agree", 0)
    val conflict = web.optInt("conflict", 0)
    val coherence = web.optDouble("coherence", Double.NaN)
    Column(modifier = Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
        .padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!coherence.isNaN()) {
                Text("согласованность леса", fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                LinearProgressIndicator(
                    progress = { coherence.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).height(8.dp),
                    color = when {
                        coherence >= 0.8 -> Color(0xFF00C896)
                        coherence < 0.34 -> Color(0xFFFF4D5E)
                        else -> Color(0xFFFFB86C)
                    },
                    trackColor = MaterialTheme.colorScheme.surface
                )
                Spacer(Modifier.width(6.dp))
                Text("%.0f%%".format(coherence * 100), fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    color = when {
                        coherence >= 0.8 -> Color(0xFF00C896)
                        coherence < 0.34 -> Color(0xFFFF4D5E)
                        else -> Color(0xFFFFB86C)
                    })
            } else {
                Text("активных пар агентов меньше двух — согласованность не считается (честно)",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text("связей активно: %d · согласий: %d · конфликтов: %d".format(active, agree, conflict),
            fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // активные связи: согласия зелёным, конфликты красным
        web.optJSONArray("links")?.let { links ->
            for (i in 0 until links.length()) {
                val l = links.optJSONObject(i) ?: continue
                val st = l.optString("status")
                if (st != "AGREE" && st != "CONFLICT") continue
                val sides = l.optJSONArray("sides")
                val color = if (st == "AGREE") Color(0xFF00C896) else Color(0xFFFF4D5E)
                Text("%s %s ↔ %s (%s)%s".format(
                    if (st == "AGREE") "✓" else "⚠",
                    shortAgent(l.optString("a")), shortAgent(l.optString("b")),
                    l.optString("kind"),
                    if (sides != null) " · ${sides.optString(0)}/${sides.optString(1)}" else ""),
                    fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = color)
            }
        }
    }
}

/** Короткое имя агента для паутины: PRICE, FLOW, LIQ, DERIV, … */
private fun shortAgent(name: String): String = when (name) {
    "PRICE_ANALYST" -> "PRICE"
    "ORDER_FLOW_ANALYST" -> "FLOW"
    "LIQUIDITY_ANALYST" -> "LIQ"
    "DERIVATIVES_ANALYST" -> "DERIV"
    "CROSS_EXCHANGE_ANALYST" -> "CROSS"
    "BTC_CORRELATION_ANALYST" -> "BTC-CORR"
    "VOLATILITY_ANALYST" -> "VOL"
    "SENTIMENT_ANALYST" -> "SENT"
    "STATISTICAL_AI" -> "STAT-AI"
    "LIQUIDATION_CASCADE" -> "CASCADE"
    "ANOMALY_DETECTOR" -> "ANOMALY"
    "OPTIONS_ANALYST" -> "OPTIONS"
    "ONCHAIN_ANALYST" -> "ONCHAIN"
    "DEFI_FLOW_ANALYST" -> "DEFI"
    "BASIS_ANALYST" -> "BASIS"
    "WHALE_WATCH" -> "WHALE"
    "SESSION_ANALYST" -> "SESSION"
    "DOMINANCE_ANALYST" -> "DOMIN"
    else -> name.take(8)
}

// ================= v8.1: НОВЫЕ ПАНЕЛИ (ТЗ v2/v5) =================

/**
 * TREND INTELLIGENCE (§13/§18): bias (7 градаций), сила, mtf-выравнивание,
 * entry quality ОТДЕЛЬНО от trend, risk, подтверждения/конфликты.
 */
@Composable
private fun TrendPanel(trend: JSONObject?) {
    Text("TREND INTELLIGENCE", fontSize = 10.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary)
    if (trend == null || trend.optString("status") != "OK") {
        Text(trend?.optString("reason") ?: "тренд-слой прогревается (нужно ≥40 баров на масштабах)",
            fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val bias = trend.optString("bias", "NEUTRAL / RANGE")
    val biasColor = when {
        bias.contains("BULLISH") -> Color(0xFF00C896)
        bias.contains("BEARISH") -> Color(0xFFFF4D5E)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
        .padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(bias, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = biasColor)
            Spacer(Modifier.width(8.dp))
            Text("сила ${trend.optInt("strength")}/100", fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("conf %.0f%%".format(trend.optDouble("confidence") * 100), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(3.dp))
        Text("вход: %s · риск: %s (вход ≠ тренд — три разных вопроса)".format(
            trend.optString("entry_quality"), trend.optString("risk")),
            fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // multi-timeframe выравнивание (§13.1)
        trend.optJSONObject("mtf")?.let { mtf ->
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("micro" to "1-5м", "short" to "15м-1ч", "mid" to "4ч+").forEach { (g, label) ->
                    val d = mtf.optJSONObject(g) ?: return@forEach
                    if (d.optString("status") != "OK") return@forEach
                    val dir = d.optString("direction")
                    val c = when {
                        dir.contains("BULLISH") -> Color(0xFF00C896)
                        dir.contains("BEARISH") -> Color(0xFFFF4D5E)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text("%s:%s(%d)" .format(label, dir.take(4), d.optInt("strength")),
                        fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = c,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp))
                }
            }
        }
        // подтверждения и конфликты
        trend.optJSONArray("confirms")?.let { arr ->
            for (i in 0 until arr.length()) {
                Text("✓ ${arr.optString(i)}", fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace, color = Color(0xFF00C896))
            }
        }
        trend.optJSONArray("conflicts")?.let { arr ->
            for (i in 0 until arr.length()) {
                Text("⚠ ${arr.optString(i)}", fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace, color = Color(0xFFFFB86C))
            }
        }
        trend.optJSONObject("persistence")?.let { p ->
            if (p.optString("status") == "OK") {
                Text("persistence: continue %.0f%% · range %.0f%% · reversal %.0f%%".format(
                    p.optDouble("p_continue") * 100, p.optDouble("p_range") * 100,
                    p.optDouble("p_reversal") * 100),
                    fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 24H OUTLOOK (§16): P(UP)/P(FLAT)/P(DOWN) на фикс. пороге, сценарии,
 * драйверы, калибровка (честная пометка NOT CALIBRATED).
 */
@Composable
private fun Outlook24Panel(o: JSONObject?) {
    Text("24H OUTLOOK", fontSize = 10.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary)
    if (o == null) {
        Text("прогноз 24ч появится после первого расчёта",
            fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (o.optString("status") == "INSUFFICIENT") {
        Text("недостаточно данных для 24ч прогноза: ${o.optString("reason", o.optString("error", ""))}",
            fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val pUp = o.optDouble("p_up", 0.0).let { if (it.isFinite()) it else 0.0 }
    val pFlat = o.optDouble("p_flat", 0.0).let { if (it.isFinite()) it else 0.0 }
    val pDown = o.optDouble("p_down", 0.0).let { if (it.isFinite()) it else 0.0 }
    val expMove = o.optDouble("expected_move_pct", Double.NaN)
    val loPct = o.optDouble("range_low_pct", Double.NaN)
    val hiPct = o.optDouble("range_high_pct", Double.NaN)
    val sigPct = o.optDouble("sigma_24h_pct", o.optDouble("sigma_pct", Double.NaN))
    Column(modifier = Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
        .padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("UP ${Math.round(pUp * 100)}%",
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, color = Color(0xFF00C896))
            Spacer(Modifier.width(8.dp))
            Text("FLAT ${Math.round(pFlat * 100)}%",
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("DOWN ${Math.round(pDown * 100)}%",
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, color = Color(0xFFFF4D5E))
            Spacer(Modifier.weight(1f))
            Text("±${neFmt("%.2f%%", o.optDouble("threshold_pct", Double.NaN))}", fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "ожидание ${neFmt("%+.2f%%", expMove)} · диапазон 80%: ${neFmt("%+.2f%%", loPct)}…${neFmt("%+.2f%%", hiPct)} · σ24ч ${neFmt("%.2f%%", sigPct)}",
            fontSize = 8.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        o.optJSONObject("scenarios")?.let { sc ->
            val bMv = sc.optJSONObject("base")?.optDouble("move_pct", Double.NaN) ?: Double.NaN
            val bPr = sc.optJSONObject("base")?.optDouble("prob", Double.NaN) ?: Double.NaN
            val bullMv = sc.optJSONObject("bull")?.optDouble("move_pct", Double.NaN) ?: Double.NaN
            val bullPr = sc.optJSONObject("bull")?.optDouble("prob", Double.NaN) ?: Double.NaN
            val bearMv = sc.optJSONObject("bear")?.optDouble("move_pct", Double.NaN) ?: Double.NaN
            val bearPr = sc.optJSONObject("bear")?.optDouble("prob", Double.NaN) ?: Double.NaN
            Text(
                "сценарии: base ${sc.optJSONObject("base")?.optString("label") ?: "—"}(${neFmt("%+.2f%%", bMv)}, p=${neFmt("%.2f", bPr)}) · bull ${neFmt("%+.2f%%", bullMv)} (p=${neFmt("%.2f", bullPr)}) · bear ${neFmt("%+.2f%%", bearMv)} (p=${neFmt("%.2f", bearPr)})",
                fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        o.optJSONArray("drivers")?.let { dr ->
            for (i in 0 until minOf(dr.length(), 3)) {
                val d = dr.optJSONObject(i)
                if (d != null) {
                    val dir = d.optString("direction")
                    val c = if (dir == "bullish") Color(0xFF00C896)
                    else if (dir == "bearish") Color(0xFFFF4D5E)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                    val sym = if (dir == "bullish") "▲" else if (dir == "bearish") "▼" else "•"
                    val tag = if (d.optBoolean("is_rumor")) " [слух]" else if (d.optInt("n_copies") > 1) " [x${d.optInt("n_copies")} копий]" else ""
                    Text("$sym ${d.optString("title")}$tag", fontSize = 8.sp, color = c, maxLines = 1)
                } else {
                    val s = dr.optString(i)
                    if (s.isNotBlank()) Text("• $s", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        val calObj = o.optJSONObject("calibration")
        if (calObj != null) {
            Text("калибровка: ${calObj.optString("label")} (проверено ${calObj.optInt("n_checked")})",
                fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                color = if (calObj.optBoolean("calibrated")) Color(0xFF00C896) else Color(0xFFFFB86C))
        } else {
            val calStr = o.optString("calibration")
            if (calStr.isNotBlank()) {
                Text("калибровка: $calStr", fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * ON-CHAIN (OCIE) (§6): состояние контура, источники, агенты, netflow,
 * конфликты с Market Engine. Никаких персональных данных владельцев адресов.
 */
@Composable
private fun OnchainPanel(oc: JSONObject?) {
    Text("ON-CHAIN ENGINE (OCIE)", fontSize = 10.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary)
    if (oc == null) {
        Text("второй контур прогревается…", fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val state = oc.optString("state", "N/A")
    val stateColor = when (state) {
        "CONFIRMED" -> Color(0xFF00C896)
        "CONFLICT" -> Color(0xFFFF4D5E)
        "ONCHAIN_UNAVAILABLE" -> Color(0xFFFFB86C)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
        .padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = stateColor)
            Spacer(Modifier.width(6.dp))
            Text("%s · conf %.0f%% · качество %.0f%%".format(
                oc.optString("direction"), oc.optDouble("confidence") * 100,
                oc.optDouble("quality") * 100),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(oc.optString("note"), fontSize = 8.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // статусы источников (§6: статус каждого источника)
        oc.optJSONObject("sources")?.let { src ->
            val keys = mutableListOf<String>()
            src.keys().forEach { keys.add(it) }
            if (keys.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text("источники: " + keys.joinToString(", ") {
                    "%s=%s".format(it, src.optString(it))
                }, fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // агенты контура (сворачиваемый список)
        val agents = oc.optJSONArray("agents")
        if (agents != null && agents.length() > 0) {
            var expanded by remember { mutableStateOf(false) }
            Text(if (expanded) "▾ агенты (%d)".format(agents.length()) else "▸ агенты (%d) — раскрыть".format(agents.length()),
                fontSize = 9.sp, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 2.dp))
            if (expanded) {
                for (i in 0 until agents.length()) {
                    val a = agents.optJSONObject(i) ?: continue
                    val st = a.optString("status")
                    val dir = a.optString("direction")
                    val c = when {
                        st != "OK" -> MaterialTheme.colorScheme.onSurfaceVariant
                        dir == "LONG" -> Color(0xFF00C896)
                        dir == "SHORT" -> Color(0xFFFF4D5E)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text("%s %-12s %s conf=%.2f".format(
                        when (st) {
                            "OK" -> "✓"; "INSUFFICIENT" -> "○"; else -> "✗"
                        }, a.optString("agent"), dir, a.optDouble("confidence")),
                        fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = c)
                    a.optJSONArray("evidence")?.let { ev ->
                        if (ev.length() > 0 && st == "OK") {
                            Text("   ${ev.optString(0)}", fontSize = 7.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

/**
 * MARKET DISCOVERY (§42): найденные формации с маршрутизацией и приоритетом.
 */
@Composable
private fun DiscoveryPanel(d: JSONObject?) {
    Text("MARKET DISCOVERY", fontSize = 10.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary)
    if (d == null) {
        Text("сканер формаций ждёт свечи 60м/15м…", fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val patterns = d.optJSONArray("patterns")
    if (d.optString("status") != "OK" || patterns == null || patterns.length() == 0) {
        Text(if (d.optString("status") == "INSUFFICIENT") "истории мало для детекции формаций"
             else "заслуживающих внимания формаций нет (честно)",
            fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(modifier = Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
        .padding(8.dp)) {
        for (i in 0 until patterns.length()) {
            val p = patterns.optJSONObject(i) ?: continue
            val dir = p.optString("direction")
            val c = if (dir == "UP") Color(0xFF00C896) else if (dir == "DOWN") Color(0xFFFF4D5E)
            else MaterialTheme.colorScheme.onSurfaceVariant
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("%s %s".format(p.optString("pattern"), if (dir.isNotEmpty()) "($dir)" else ""),
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = c)
                Spacer(Modifier.weight(1f))
                p.optJSONObject("vol_ratio")?.let { }
                if (p.has("vol_ratio")) {
                    Text("vol x%.1f".format(p.optDouble("vol_ratio")), fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace, color = Color(0xFF00C896))
                }
            }
            p.optString("note").takeIf { it.isNotEmpty() }?.let {
                Text(it, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * ГЛУБОКИЙ АНАЛИЗ A→Б — цепочка v6.1: полный прогон по текущей паре с живым
 * прогрессом узлов (данные → проверка → режим → аналитики → совет → прогноз →
 * risk gate) и итоговым отчётом с audit trail. Переехала сюда из удалённого чата.
 */
@Composable
private fun DeepAnalysisSection(
    state: AppState,
    chainNodes: List<AppState.ChainNodeUi>,
    chainRunning: Boolean,
    report: String,
    auditOpen: Boolean,
    onToggleAudit: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("ГЛУБОКИЙ АНАЛИЗ · ЦЕПОЧКА A→Б", fontSize = 10.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.weight(1f))
        if (chainRunning) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.width(12.dp).height(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.width(4.dp))
            Text("прогон…", fontSize = 9.sp, color = MaterialTheme.colorScheme.secondary)
        }
    }
    Text("полный прогон по текущей паре: данные → режим → аналитики → совет → прогноз → risk gate (~30-60с)",
        fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    androidx.compose.material3.Button(
        onClick = { state.runAnalysis() },
        enabled = !chainRunning,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (chainRunning) "ЦЕПОЧКА ИДЁТ…" else "⚡ ЗАПУСТИТЬ ГЛУБОКИЙ АНАЛИЗ")
    }

    // живые узлы цепочки
    if (chainNodes.isNotEmpty()) {
        ChainView(nodes = chainNodes, running = chainRunning)
    }

    // итоговый отчёт + audit trail
    if (report.isNotBlank()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(10.dp)
                )
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ИТОГ ЦЕПОЧКИ", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text(if (auditOpen) "скрыть детали ▲" else "audit trail ▼",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.clickable { onToggleAudit() })
            }
            Spacer(Modifier.height(4.dp))
            Text(report, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface)
            if (auditOpen) {
                AuditTrail(state)
            }
        }
    }
}

/**
 * Визуальная цепочка нейронов: реальные статусы узлов выполнения.
 * DATA INGESTION ✓ / REGIME ✓ / TA ✓ / ... / RISK GATE ⛔ / FINAL OUTPUT ✓
 */
@Composable
private fun ChainView(nodes: List<AppState.ChainNodeUi>, running: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ЦЕПОЧКА A→Б", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            if (running) {
                Spacer(Modifier.width(6.dp))
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.width(10.dp).height(10.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        nodes.forEach { node ->
            NodeRow(node)
            if (node !== nodes.last()) {
                Box(
                    modifier = Modifier
                        .padding(start = 11.dp)
                        .width(1.dp)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                )
            }
        }
    }
}

@Composable
private fun NodeRow(node: AppState.ChainNodeUi) {
    val (color, glyph) = when (node.status) {
        "OK" -> Color(0xFF00C896) to "✓"
        "STOPPED" -> Color(0xFFFF4D5E) to "⛔"
        "SKIPPED" -> Color.Gray to "⊘"
        else -> MaterialTheme.colorScheme.secondary to "●"
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
        Text(glyph, fontSize = 11.sp, color = color, modifier = Modifier.width(16.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(node.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(6.dp))
                Text(node.status, fontSize = 9.sp, color = color)
            }
            if (node.summary.isNotBlank()) {
                Text(node.summary, fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (node.reason.isNotBlank() && node.status == "STOPPED") {
                Text("причина: ${node.reason}", fontSize = 9.sp, color = Color(0xFFFF9E9E), maxLines = 3)
            }
        }
    }
}

/** Подробности всей цепочки: полные результаты узлов (audit). */
@Composable
private fun AuditTrail(state: AppState) {
    val res by state.lastResult.collectAsState()
    val r = res ?: return
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    )
    Text("Полный audit trail:", fontSize = 10.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.secondary)
    val snap = r.optJSONObject("snap")
    val sources = snap?.optJSONObject("sources")
    if (sources != null) {
        Text("Источники: " + sources.toString(), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val decision = r.optJSONObject("decision")
    if (decision != null) {
        Text("Council audit:", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val audit = decision.optJSONArray("audit")
        if (audit != null) {
            for (i in 0 until audit.length()) {
                val a = audit.getJSONObject(i)
                Text("  [${a.optString("stage")}] ${a.optString("check")} → ${a.optString("result").take(120)}",
                    fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val modules = r.optJSONObject("analysts")
    if (modules != null) {
        val keys = modules.keys()
        while (keys.hasNext()) {
            val m = keys.next()
            val v = modules.optJSONObject(m) ?: continue
            Text("$m: ok=${v.optBoolean("ok")} side=${v.optString("side")} p=${v.optDouble("p")} conf=${v.optDouble("confidence")}",
                fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Панель прогноза на 6 часов: три вероятности, ожидание, диапазон, аргументы. */
@Composable
private fun ForecastPanel(result: JSONObject?) {
    val o6 = result?.optJSONObject("outlook_6h") ?: result?.optJSONObject("outlook6h")
    val f = result?.optJSONObject("forecast") ?: o6
    Column(modifier = Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
        .padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ПРОГНОЗ · 6 ЧАСОВ", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            result?.optJSONObject("session")?.let { s ->
                Text("сессия ${s.optString("id").takeLast(8)} · v${s.optInt("version")}" +
                    if (s.optBoolean("is_initial")) " (начальная)" else " (обновление)",
                    fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (f == null) {
            Text("нет данных — движок собирает первый срез (свечи/потоки мульти-бирж)", fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val pUp = f.optDouble("p_up", 0.0).let { if (it.isFinite()) it else 0.0 }
        val pFlat = f.optDouble("p_flat", 0.0).let { if (it.isFinite()) it else 0.0 }
        val pDown = f.optDouble("p_down", 0.0).let { if (it.isFinite()) it else 0.0 }
        ProbBar("P(UP)", pUp, Color(0xFF00C896))
        ProbBar("P(FLAT)", pFlat, MaterialTheme.colorScheme.onSurfaceVariant)
        ProbBar("P(DOWN)", pDown, Color(0xFFFF4D5E))
        Spacer(Modifier.height(4.dp))
        val expMove = f.optDouble("expected_move_pct", Double.NaN)
        val sig6 = f.optDouble("sigma_6h_pct", f.optDouble("sigma_pct", Double.NaN))
        val lo = f.optDouble("range_low", f.optDouble("range_low_pct", Double.NaN))
        val hi = f.optDouble("range_high", f.optDouble("range_high_pct", Double.NaN))
        Text(
            "ожидание ${neFmt("%+.3f%%", expMove)} · σ6ч ${neFmt("%.3f%%", sig6)} · диапазон 80%: [${neFmt("%.4g", lo)} … ${neFmt("%.4g", hi)}]",
            fontSize = 9.sp, fontFamily = FontFamily.Monospace
        )
        Text(
            "сила ${neFmt("%.2f", f.optDouble("signal_strength", Double.NaN))} · неопред. ${neFmt("%.2f", f.optDouble("uncertainty", Double.NaN))} · совет: ${f.optString("council_status", "—")}",
            fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (f.optString("council_status").contains("NO_SIGNAL")) {
            Spacer(Modifier.height(4.dp))
            Text(
                "ℹ NO_SIGNAL = перевеса нет: подтверждённых кластеров сигналов меньше двух. " +
                    "Вероятности выше — И ЕСТЬ ответ движка (обычно с перевесом FLAT и широкой σ).",
                fontSize = 9.sp, color = Color(0xFFFFB86C))
        }
        f.optString("alt_scenario")?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        f.optJSONArray("arguments")?.let { arr ->
            if (arr.length() > 0) {
                Spacer(Modifier.height(4.dp))
                for (i in 0 until minOf(arr.length(), 3)) {
                    Text("• ${arr.optString(i)}", fontSize = 9.sp, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun ProbBar(label: String, p: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(62.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(
            progress = { p.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(8.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surface
        )
        Text("%3.0f%%".format(p * 100), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.width(38.dp),
            color = color)
    }
}

@Composable
private fun AgentRow(a: JSONObject, onClick: () -> Unit) {
    val (mark, color) = when (a.optString("status")) {
        "OK" -> "✓" to when (a.optString("side")) {
            "LONG" -> Color(0xFF00C896)
            "SHORT" -> Color(0xFFFF4D5E)
            else -> MaterialTheme.colorScheme.onSurface
        }
        "INSUFFICIENT" -> "⊘" to MaterialTheme.colorScheme.onSurfaceVariant
        "DISABLED" -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "?" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(mark, color = color, fontSize = 11.sp, modifier = Modifier.width(16.dp))
        Text(a.optString("agent"), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f))
        Text(a.optString("side"), fontSize = 9.sp, color = color,
            fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp))
        Text("%.2f".format(a.optDouble("confidence")), fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionList(title: String, arr: org.json.JSONArray?) {
    if (arr == null || arr.length() == 0) return
    Spacer(Modifier.height(6.dp))
    Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    for (i in 0 until arr.length()) {
        Text("• ${arr.optString(i)}", fontSize = 9.sp)
    }
}

@Composable
private fun MemRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 9.sp, modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

private fun neFmt(fmt: String, value: Double, fallback: String = "—"): String {
    if (!value.isFinite()) return fallback
    return runCatching { String.format(Locale.US, fmt, value) }.getOrDefault(fallback)
}

private fun timeShort(tsSec: Long): String =
    if (tsSec <= 0) "" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(tsSec * 1000))
