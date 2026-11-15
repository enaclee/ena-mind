package com.enamind.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AutoGraph
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enamind.ai.core.AppState
import com.enamind.ai.core.PythonBridge
import com.enamind.ai.core.Settings
import com.enamind.ai.intelligence.SentinelSeverity
import com.enamind.ai.intelligence.TrendReasoningEngine
import com.enamind.ai.intelligence.UnifiedIntelligenceReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ЧАТ С ДВИЖКОМ v8.3:
 * - верхняя панель активной пары с переключателем монет, ценой и источником потока;
 * - мгновенный расчёт по требованию (`engine.computeNow()`) с Kotlin-резервом ботов;
 * - логика тренда, Chain Radar и Crypto Sentinel прямо в диалоге;
 * - визуальные шкалы вероятностей P(UP) / P(FLAT) / P(DOWN) внутри сообщений.
 */
@Composable
fun EngineChatScreen(state: AppState) {
    val market by state.market.collectAsState()
    val running by state.engine.running.collectAsState()
    val engineResult by state.engine.result.collectAsState()
    val intel by state.engine.intelligence.collectAsState()
    val messages by state.chatMessages.collectAsState()
    val uiScope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var symbolMenuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // ---------- Верхняя панель активного актива и статуса движка ----------
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
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Surface(
                        modifier = Modifier
                            .testTag("chat_symbol_chip")
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { symbolMenuOpen = true },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.AutoGraph,
                                contentDescription = "Выбрать актив",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                market.symbol,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                Icons.Outlined.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = symbolMenuOpen,
                        onDismissRequest = { symbolMenuOpen = false }
                    ) {
                        Settings.SYMBOLS.forEach { sym ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        sym,
                                        fontWeight = if (sym == market.symbol) FontWeight.Bold else FontWeight.Normal,
                                        color = if (sym == market.symbol) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    symbolMenuOpen = false
                                    state.changeSymbol(sym)
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            market.price?.let { formatChatPrice(it) } ?: "—",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        market.change24hPct?.takeIf { it.isFinite() }?.let { chg ->
                            Spacer(Modifier.width(6.dp))
                            val chgColor = if (chg >= 0) Color.EnaGreen else Color.EnaRed
                            Text(
                                fmtD("%+.2f%%", chg),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = chgColor
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isReady = engineResult?.optBoolean("ok") == true || intel != null
                        val hasLiveExchange = market.wsConnected || state.engine.binanceConnected || state.engine.okxConnected
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(
                                    when {
                                        busy -> Color.EnaYellow
                                        running -> Color.EnaGreen
                                        isReady -> Color.EnaGreen
                                        hasLiveExchange -> Color.EnaGreen
                                        else -> MaterialTheme.colorScheme.primary
                                    },
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            when {
                                busy -> "считаю ответ…"
                                running -> "анализ · ${market.feedSource}"
                                isReady -> "готов · ${market.feedSource}"
                                hasLiveExchange -> "поток · ${market.feedSource}"
                                else -> "подключение · ${market.feedSource}"
                            },
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Быстрый бейдж режима
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        "SHADOW",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // ---------- Лента сообщений (выделение текста + копирование кнопкой) ----------
        SelectionContainer(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { m ->
                    MessageBubble(
                        m = m,
                        onCopy = {
                            clipboard.setText(AnnotatedString(m.text))
                            state.toast("Скопировано")
                        }
                    )
                }
            }
        }

        // ---------- Горизонтальная лента быстрых команд ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CommandChip("⚡ Прогноз 6ч", "cmd_outlook6") {
                runCommand(state, uiScope, "outlook6", null, busy) { busy = it }
            }
            CommandChip("⏱ Прогноз 24ч", "cmd_outlook24") {
                runCommand(state, uiScope, "outlook24", null, busy) { busy = it }
            }
            CommandChip("📈 Тренд", "cmd_trend") {
                runCommand(state, uiScope, "trend", null, busy) { busy = it }
            }
            CommandChip("🧠 Логика", "cmd_logic") {
                runCommand(state, uiScope, "logic", null, busy) { busy = it }
            }
            CommandChip("🛰 Радар", "cmd_radar") {
                runCommand(state, uiScope, "radar", null, busy) { busy = it }
            }
            CommandChip("🚨 Страж", "cmd_sentinel") {
                runCommand(state, uiScope, "sentinel", null, busy) { busy = it }
            }
            CommandChip("🔬 Агенты", "cmd_agents") {
                runCommand(state, uiScope, "agents", null, busy) { busy = it }
            }
            CommandChip("📋 Сводка", "cmd_summary") {
                runCommand(state, uiScope, "summary", null, busy) { busy = it }
            }
            CommandChip("🌐 Ончейн", "cmd_onchain") {
                runCommand(state, uiScope, "onchain", null, busy) { busy = it }
            }
            CommandChip("🧭 Путь A→Б", "cmd_graph") {
                runCommand(state, uiScope, "graph", null, busy) { busy = it }
            }
            CommandChip("🛡 Совет", "cmd_council") {
                runCommand(state, uiScope, "council", null, busy) { busy = it }
            }
            CommandChip("🧹 Очистить", "cmd_clear") {
                state.clearChatMessages()
            }
        }

        // ---------- Поле ввода сообщения + кнопка отправки ----------
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_field"),
                    placeholder = {
                        Text(
                            "Спросите: прогноз, тренд, ETH, ончейн…",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.background,
                        unfocusedContainerColor = MaterialTheme.colorScheme.background
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val txt = inputText.trim()
                            if (txt.isNotEmpty() && !busy) {
                                inputText = ""
                                submitFreeText(state, uiScope, txt, busy) { busy = it }
                            }
                        }
                    )
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val txt = inputText.trim()
                        if (txt.isNotEmpty() && !busy) {
                            inputText = ""
                            submitFreeText(state, uiScope, txt, busy) { busy = it }
                        }
                    },
                    enabled = inputText.isNotBlank() && !busy,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("chat_send_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Отправить сообщение",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Пузырь сообщения с кнопкой копирования и опциональной полосой вероятностей UP/FLAT/DOWN. */
@Composable
private fun MessageBubble(m: AppState.ChatMessage, onCopy: () -> Unit) {
    val probs = remember(m.text) { extractProbabilities(m.text) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (m.fromUser) 48.dp else 0.dp,
                end = if (m.fromUser) 0.dp else 36.dp
            ),
        contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 340.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (m.fromUser) 16.dp else 4.dp,
                bottomEnd = if (m.fromUser) 4.dp else 16.dp
            ),
            color = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
            border = if (m.fromUser) null else BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (m.title.isNotEmpty()) {
                    Text(
                        m.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (m.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            m.text,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        m.text,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = if (m.fromUser) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        fontFamily = if (m.text.startsWith("•") || m.text.contains("P(")) JetBrainsMonoFontFamily
                        else InterFontFamily
                    )
                    if (probs != null) {
                        Spacer(Modifier.height(8.dp))
                        ProbabilityBar(probs.first, probs.second, probs.third)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        timeShort(m.ts),
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier.weight(1f)
                    )
                    if (!m.busy && m.text.isNotBlank()) {
                        IconButton(
                            onClick = onCopy,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("chat_copy_msg_${m.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Копировать сообщение",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbabilityBar(upPct: Int, flatPct: Int, downPct: Int) {
    val total = (upPct + flatPct + downPct).coerceAtLeast(1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (upPct > 0) {
            Box(
                modifier = Modifier
                    .weight(upPct.toFloat() / total)
                    .fillMaxHeight()
                    .background(Color.EnaGreen)
            )
        }
        if (flatPct > 0) {
            Box(
                modifier = Modifier
                    .weight(flatPct.toFloat() / total)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outline)
            )
        }
        if (downPct > 0) {
            Box(
                modifier = Modifier
                    .weight(downPct.toFloat() / total)
                    .fillMaxHeight()
                    .background(Color.EnaRed)
            )
        }
    }
}

private fun extractProbabilities(text: String): Triple<Int, Int, Int>? {
    val regex = Regex("""P\(UP\)\s*(\d+)%\s*·\s*P\(FLAT\)\s*(\d+)%\s*·\s*P\(DOWN\)\s*(\d+)%""")
    val m = regex.find(text) ?: return null
    val up = m.groupValues[1].toIntOrNull() ?: return null
    val flat = m.groupValues[2].toIntOrNull() ?: return null
    val down = m.groupValues[3].toIntOrNull() ?: return null
    return Triple(up, flat, down)
}

private fun timeShort(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

@Composable
private fun CommandChip(label: String, tag: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .testTag(tag)
            .clip(CircleShape)
            .clickable { onClick() },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

// ================================================================
// МАРШРУТИЗАТОР ТЕКСТОВЫХ ВОПРОСОВ И КОМАНД
// ================================================================

object ChatIntentRouter {
    fun detectSymbolMention(raw: String): String? {
        val low = raw.lowercase(Locale.getDefault())
        return when {
            Regex("""\bbtc(usdt)?\b|биткоин|биток""").containsMatchIn(low) -> "BTCUSDT"
            Regex("""\beth(usdt)?\b|эфир""").containsMatchIn(low) -> "ETHUSDT"
            Regex("""\bsol(usdt)?\b|солан""").containsMatchIn(low) -> "SOLUSDT"
            Regex("""\bxrp(usdt)?\b|рипл""").containsMatchIn(low) -> "XRPUSDT"
            Regex("""\bbnb(usdt)?\b|бинанс""").containsMatchIn(low) -> "BNBUSDT"
            Regex("""\bsui(usdt)?\b""").containsMatchIn(low) -> "SUIUSDT"
            Regex("""\bavax(usdt)?\b|авакс""").containsMatchIn(low) -> "AVAXUSDT"
            Regex("""\bdoge(usdt)?\b|дог""").containsMatchIn(low) -> "DOGEUSDT"
            Regex("""\bop(usdt)?\b|оптимизм""").containsMatchIn(low) -> "OPUSDT"
            Regex("""\blink(usdt)?\b|чейнлинк""").containsMatchIn(low) -> "LINKUSDT"
            else -> null
        }
    }

    fun resolveCommand(raw: String): String {
        val low = raw.lowercase(Locale.getDefault())
        return when {
            low.contains("помощ") || low.contains("команд") || low == "help" || low == "?" -> "help"
            low.contains("радар") || low.contains("кит") || low.contains("стейбл") || low.contains("деньг") || low.contains("radar") -> "radar"
            low.contains("страж") || low.contains("аномал") || low.contains("взлом") || low.contains("разблок") || low.contains("sentinel") || low.contains("угроз") -> "sentinel"
            low.contains("логик") || low.contains("разум") || low.contains("почему") || low.contains("причин") || low.contains("боты") -> "logic"
            low.contains("ончейн") || low.contains("onchain") || low.contains("mempool") || low.contains("блок") -> "onchain"
            low.contains("24") || low.contains("сутки") || low.contains("день") || low.contains("outlook") -> "outlook24"
            low.contains("6ч") || low.contains("6 час") || low.contains("6час") -> "outlook6"
            low.contains("путь") || low.contains("граф") || low.contains("a->б") || low.contains("a→б") || low.contains("цепоч") -> "graph"
            low.contains("агент") || low.contains("лес") || low.contains("веточ") -> "agents"
            low.contains("совет") || low.contains("риск") || low.contains("конфликт") || low.contains("council") -> "council"
            low.contains("тренд") || low.contains("структур") || low.contains("trend") -> "trend"
            low.contains("новост") || low.contains("сми") || low.contains("rss") -> "news"
            low.contains("цен") || low.contains("курс") || low.contains("стакан") || low.contains("поток") || low.contains("cvd") || low.contains("lsr") || low.contains("фандинг") -> "market"
            low.contains("прогноз") || low.contains("сигнал") || low.contains("куда") || low.contains("рост") || low.contains("паден") -> "outlook6"
            else -> "summary"
        }
    }
}

private fun submitFreeText(
    state: AppState,
    uiScope: CoroutineScope,
    rawText: String,
    busyNow: Boolean,
    setBusy: (Boolean) -> Unit
) {
    val cmd = ChatIntentRouter.resolveCommand(rawText)
    runCommand(state, uiScope, cmd, rawText, busyNow, setBusy)
}

private fun runCommand(
    state: AppState,
    uiScope: CoroutineScope,
    command: String,
    userDisplayOverride: String?,
    busyNow: Boolean,
    setBusy: (Boolean) -> Unit
) {
    if (busyNow) return
    setBusy(true)
    val labels = mapOf(
        "outlook6" to "⚡ Прогноз 6 часов",
        "forecast" to "⚡ Прогноз 6 часов",
        "trend" to "📈 Тренд",
        "logic" to "🧠 Логика и разум",
        "radar" to "🛰 Chain Radar",
        "sentinel" to "🚨 Crypto Sentinel",
        "agents" to "🔬 Агенты",
        "onchain" to "🌐 Ончейн",
        "outlook24" to "⏱ Прогноз 24 часа",
        "graph" to "🧭 Путь A→Б",
        "council" to "🛡 Совет",
        "summary" to "📋 Полная сводка",
        "market" to "💹 Живой рынок",
        "news" to "📰 Новости",
        "help" to "❓ Команды чата"
    )
    val userText = userDisplayOverride ?: labels[command] ?: command
    state.appendChatMessage(
        AppState.ChatMessage(
            id = state.nextChatMsgId(),
            fromUser = true,
            text = userText
        )
    )
    val mentionedSym = userDisplayOverride?.let { ChatIntentRouter.detectSymbolMention(it) }
    val switchSymbol = mentionedSym != null && mentionedSym != state.market.value.symbol
    if (switchSymbol && mentionedSym != null) {
        state.changeSymbol(mentionedSym)
    }

    val placeholderId = state.nextChatMsgId()
    state.appendChatMessage(
        AppState.ChatMessage(
            id = placeholderId,
            fromUser = false,
            title = "ENA · ${mentionedSym ?: state.market.value.symbol}",
            text = if (switchSymbol) "переключаюсь на $mentionedSym и считаю…" else "собираю свежие данные и считаю…",
            busy = true
        )
    )
    uiScope.launch {
        val answer = withContext(Dispatchers.IO) {
            try {
                if (switchSymbol) delay(600)
                formatAnswer(command, state)
            } catch (e: Exception) {
                "Ошибка движка: ${e.message ?: "неизвестная"}\nПопробуйте ещё раз — фоновый конвейер не остановлен."
            }
        }
        state.updateChatMessage(placeholderId, answer)
        setBusy(false)
    }
}

/** Собрать текст ответа по команде (с авто-вычислением и свежим пересчётом прогнозов 6ч/24ч). */
private suspend fun formatAnswer(command: String, state: AppState): String {
    val symbol = state.market.value.symbol
    return when (command) {
        "help" -> helpText(symbol)
        "market" -> marketText(state)
        "news" -> {
            state.ensureFreshNews(maxAgeMs = 15_000L, force = true)
            val intel = try {
                state.engine.refreshIntelligence(forceRadar = false)
            } catch (_: Exception) {
                state.engine.intelligence.value
            }
            newsText(state, intel)
        }
        "radar" -> {
            state.ensureFreshNews(maxAgeMs = 60_000L, force = false)
            val intel = state.engine.refreshIntelligence(forceRadar = true)
            radarText(intel)
        }
        "sentinel" -> {
            state.ensureFreshNews(maxAgeMs = 60_000L, force = false)
            val intel = state.engine.refreshIntelligence(forceRadar = true)
            sentinelText(intel, state.market.value)
        }
        "logic" -> {
            val intel = state.engine.refreshIntelligence(forceRadar = false)
            logicText(intel)
        }
        "outlook6", "forecast" -> {
            val fresh = state.requestFreshOutlook(6)
            outlookText(fresh, defaultHours = 6)
        }
        "outlook24" -> {
            val fresh = state.requestFreshOutlook(24)
            outlookText(fresh, defaultHours = 24)
        }
        "onchain" -> {
            if (!PythonBridge.isReady()) state.engine.computeNow()
            val r = PythonBridge.callEngine("engine_refresh_onchain", symbol)
            if (r.optBoolean("ok")) onchainText(r) else radarText(state.engine.refreshIntelligence(false))
        }
        "graph" -> {
            val res = state.engine.computeNow()
            if (res == null || !res.optBoolean("ok")) {
                return "Не удалось загрузить историю свечей для графа A→Б ($symbol). Проверьте интернет-соединение."
            }
            val r = PythonBridge.callEngine("engine_run_graph", symbol, 45.0)
            if (r.optBoolean("ok")) graphText(r) else logicText(state.engine.refreshIntelligence(false))
        }
        else -> {
            val r = state.engine.computeNow()
            val intel = state.engine.intelligence.value
            if (r == null || !r.optBoolean("ok")) {
                if (intel != null && intel.symbol == symbol) {
                    return when (command) {
                        "logic" -> logicText(intel)
                        "trend" -> logicText(intel)
                        "agents" -> botsText(intel)
                        else -> unifiedSummaryText(intel, state.market.value)
                    }
                }
                val issues = r?.optJSONObject("data")?.optJSONArray("issues")?.let { arr ->
                    (0 until arr.length()).joinToString("; ") { arr.optString(it) }
                } ?: r?.optString("error")
                return "Движок не смог получить свечи для $symbol (${issues ?: "нет ответа от Bybit/Binance/OKX"}). Проверьте сеть во вкладке Настройки → API."
            }
            when (command) {
                "trend" -> {
                    val t = r.optJSONObject("trend")
                    if (t != null && t.optString("status") == "OK") {
                        trendText(t, intel)
                    } else if (intel != null) {
                        logicText(intel)
                    } else {
                        "Тренд-слой ещё не рассчитан (нужно ≥30 баров истории)."
                    }
                }
                "agents" -> if (r.has("agents") && (r.optJSONArray("agents")?.length() ?: 0) > 6) agentsText(r) else if (intel != null) botsText(intel) else agentsText(r)
                "council" -> councilText(r)
                else -> summaryText(r, state.market.value, intel)
            }
        }
    }
}

// ---------------- форматтеры отчётов (безопасны к NaN/Inf и локали) ----------------

private fun fmtD(fmt: String, v: Double, fallback: String = "—"): String {
    if (!v.isFinite()) return fallback
    return runCatching { String.format(Locale.US, fmt, v) }.getOrDefault(fallback)
}

private fun formatChatPrice(p: Double): String {
    if (!p.isFinite() || p <= 0.0) return "—"
    return when {
        p >= 1000 -> fmtD("%,.1f", p)
        p >= 10 -> fmtD("%,.2f", p)
        else -> fmtD("%.4f", p)
    }
}

private fun pct(x: Double): String {
    if (!x.isFinite()) return "0%"
    return "${Math.round(x.coerceIn(0.0, 1.0) * 100)}%"
}

private fun helpText(symbol: String): String =
    "Команды чата ENA ($symbol):\n\n" +
        "• «Прогноз 6ч» — свежий расчёт вероятностей P(UP/FLAT/DOWN) на 6 часов\n" +
        "• «Прогноз 24ч» — свежий расчёт сценариев и диапазона на сутки\n" +
        "• «Тренд» — мультитаймфрейм 1м–4ч и структура HH/HL\n" +
        "• «Логика» — цепочка рассуждений и фаза тренда ботов\n" +
        "• «Радар» — Chain Radar (киты, стейблкоины, мемпул BTC)\n" +
        "• «Страж» — Crypto Sentinel (аномалии, всплески, события)\n" +
        "• «Агенты» — голоса ветвей и ботов\n" +
        "• «Сводка» — единый отчёт: цена, тренд, деньги и риски\n" +
        "• Название монеты («ETH», «SOL», «BNB», «LINK»…) переключает актив."

private fun marketText(state: AppState): String {
    val m = state.market.value
    val sb = StringBuilder("Живой рынок ${m.symbol} (${m.feedSource}):\n\n")
    sb.append("Цена: ").append(m.price?.let { formatChatPrice(it) } ?: "—")
    m.change24hPct?.takeIf { it.isFinite() }?.let { sb.append(" (").append(fmtD("%+.2f%%", it)).append(" за 24ч)") }
    sb.append("\n")
    m.fundingPct?.takeIf { it.isFinite() }?.let { sb.append("Funding: ").append(fmtD("%+.4f%%", it)).append("\n") }
    m.openInterest?.takeIf { it.isFinite() }?.let { sb.append("Open Interest: ").append(fmtD("%.2f", it)).append("\n") }
    m.lsr?.takeIf { it.isFinite() }?.let { l ->
        sb.append("LSR толпы: ").append(fmtD("%.2f", l))
        m.lsrPct?.takeIf { it.isFinite() }?.let { sb.append(" (перцентиль ").append(fmtD("%.0f%%", it)).append(")") }
        sb.append("\n")
    }
    m.fng?.let { sb.append("Fear & Greed: $it (${m.fngClassification ?: "—"})\n") }
    m.tape?.let { t ->
        if (t.n > 0) {
            sb.append("Order Flow (${t.n} сд.): CVD ").append(fmtD("%+.2f", t.cvd))
                .append(", дельта ").append(fmtD("%+.1f%%", t.deltaPct)).append("\n")
        }
    }
    return sb.toString().trim()
}

private fun newsText(state: AppState, intel: UnifiedIntelligenceReport? = null): String {
    val m = state.market.value
    val arr = m.news
    if (arr.length() == 0) return "Новостная лента сейчас пуста (${m.newsStatus})."
    val sb = StringBuilder("Свежие новости (${m.newsStatus}):\n\n")
    for (i in 0 until minOf(arr.length(), 8)) {
        val it = arr.optJSONObject(i) ?: continue
        val imp = it.optDouble("impact", 0.0).let { v -> if (v.isFinite()) v else 0.0 }
        val icon = when {
            imp > 0.1 -> "▲"
            imp < -0.1 -> "▼"
            else -> "•"
        }
        val ts = it.optLong("published_ts", 0L)
        val tsLabel = if (ts > 0L) {
            " (" + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts)) + ")"
        } else ""
        sb.append("$icon [${it.optString("source")}$tsLabel] ${it.optString("title")}\n")
    }
    val newsAlerts = intel?.sentinelEvents?.filter {
        it.category == com.enamind.ai.intelligence.SentinelCategory.SECURITY_EXPLOIT ||
            it.category == com.enamind.ai.intelligence.SentinelCategory.TOKEN_UNLOCK ||
            it.category == com.enamind.ai.intelligence.SentinelCategory.REGULATION_MACRO
    }.orEmpty()
    if (newsAlerts.isNotEmpty()) {
        sb.append("\nТриггеры Crypto Sentinel из новостей:\n")
        newsAlerts.take(3).forEach { ev ->
            sb.append("• [${ev.category.labelRu}] ${ev.title}\n")
        }
    }
    return sb.toString().trim()
}

private fun summaryText(r: JSONObject, m: AppState.MarketUi, intel: UnifiedIntelligenceReport?): String {
    val sb = StringBuilder("Сводка ${m.symbol}")
    m.price?.let { sb.append(" · ${formatChatPrice(it)}") }
    m.change24hPct?.takeIf { it.isFinite() }?.let { sb.append(" (${fmtD("%+.2f%%", it)})") }
    sb.append(" · [${m.feedSource}]\n\n")

    val o6 = r.optJSONObject("outlook_6h") ?: r.optJSONObject("outlook6h")
    if (o6 != null && o6.optDouble("p_up", Double.NaN).isFinite()) {
        sb.append("Прогноз 6ч: ").append(o6.optString("side", "FLAT")).append("\n")
        sb.append("P(UP) ").append(pct(o6.optDouble("p_up")))
            .append(" · P(FLAT) ").append(pct(o6.optDouble("p_flat")))
            .append(" · P(DOWN) ").append(pct(o6.optDouble("p_down"))).append("\n\n")
    } else {
        r.optJSONObject("prediction")?.let { p ->
            val f = p.optJSONObject("features") ?: JSONObject()
            sb.append("Прогноз 6ч: ").append(p.optString("side"))
                .append(" (увер. ").append(pct(p.optDouble("confidence"))).append(")\n")
            sb.append("P(UP) ").append(pct(f.optDouble("p_up")))
                .append(" · P(FLAT) ").append(pct(f.optDouble("p_flat")))
                .append(" · P(DOWN) ").append(pct(f.optDouble("p_down"))).append("\n\n")
        }
    }
    r.optJSONObject("trend")?.takeIf { it.optString("status") == "OK" }?.let { t ->
        sb.append("Тренд: ").append(t.optString("bias"))
            .append(" (сила ").append(t.optInt("strength")).append("/100, вход ")
            .append(t.optString("entry_quality")).append(")\n")
    }
    intel?.let {
        sb.append("Фаза ботов: ").append(it.trendLogic.phase)
            .append(" (согласие ").append(it.trendLogic.agreementPct).append("%)\n")
        sb.append("Chain Radar: ").append(it.chainRadar.moneyFlowBias).append(" — ")
            .append(it.chainRadar.preChartAnswer).append("\n")
        if (it.venues.activeVenues > 0) {
            val vel = it.venues.binanceTradeVelPerSec?.let { v -> fmtD("%.1f сд/с", v) } ?: "—"
            sb.append("Биржи (${it.venues.activeVenues}): ").append(it.venues.venueNames.joinToString("+"))
                .append(" · скорость потока ").append(vel).append("\n")
        }
        if (it.sentinelEvents.isNotEmpty()) {
            sb.append("Sentinel: ").append(it.sentinelEvents.first().title).append("\n")
        }
    }
    r.optJSONObject("council")?.let { c ->
        sb.append("Совет: ").append(c.optString("status")).append("\n")
    }
    val o24 = (r.optJSONObject("outlook_24h") ?: r.optJSONObject("outlook24h"))
    o24?.takeIf { it.optString("status") != "INSUFFICIENT" && it.optDouble("expected_move_pct", Double.NaN).isFinite() }?.let { o ->
        val exp = fmtD("%+.2f%%", o.optDouble("expected_move_pct"))
        val lo = fmtD("%+.2f%%", o.optDouble("range_low_pct"))
        val hi = fmtD("%+.2f%%", o.optDouble("range_high_pct"))
        sb.append("Горизонт 24ч: ожидание $exp (диапазон $lo…$hi)")
    }
    return sb.toString().trim()
}

private fun unifiedSummaryText(intel: UnifiedIntelligenceReport, m: AppState.MarketUi): String {
    val l = intel.trendLogic
    val sb = StringBuilder("Сводка ${intel.symbol}")
    intel.price?.let { sb.append(" · ${formatChatPrice(it)}") }
    sb.append(" · [${m.feedSource}]\n\n")
    sb.append("P(UP) ").append(pct(l.pUp))
        .append(" · P(FLAT) ").append(pct(l.pFlat))
        .append(" · P(DOWN) ").append(pct(l.pDown)).append("\n\n")
    sb.append("Фаза: ").append(l.phase).append(" (сила ").append(l.strength).append("/100)\n")
    sb.append("Деньги до графика: ").append(intel.chainRadar.preChartAnswer).append("\n")
    if (intel.sentinelEvents.isNotEmpty()) {
        sb.append("\nSentinel: ").append(intel.sentinelEvents.first().title)
    }
    return sb.toString().trim()
}

private fun logicText(intel: UnifiedIntelligenceReport): String {
    val l = intel.trendLogic
    val sb = StringBuilder("Логика и разум (${intel.symbol}):\n\n")
    sb.append("P(UP) ").append(pct(l.pUp))
        .append(" · P(FLAT) ").append(pct(l.pFlat))
        .append(" · P(DOWN) ").append(pct(l.pDown)).append("\n\n")
    sb.append("Фаза: ").append(l.phase).append(" (").append(l.biasLabel).append(", сила ").append(l.strength).append("/100)\n")
    sb.append("Согласие ботов: ").append(l.agreementPct).append("% · Вход: ").append(l.entryQuality).append(" · Риск: ").append(l.riskLevel).append("\n\n")
    l.causalChain.forEach { st ->
        sb.append("${st.index}) ${st.observation}\n")
        sb.append("   → Вывод: ${st.conclusion}\n")
    }
    val q = intel.quant
    val adxS = "%.1f".format(Locale.US, q.adx)
    val erS = "%.2f".format(Locale.US, q.efficiencyRatio)
    val r2S = "%.2f".format(Locale.US, q.regressionR2)
    sb.append("\nДанные Grade ${q.dataGrade} · ADX $adxS · ER $erS · R² $r2S")
    if (q.walkForwardSamples >= 6 && q.walkForwardHitPct != null && q.brierScore != null) {
        val wfH = "%.0f%%".format(Locale.US, q.walkForwardHitPct)
        val wfB = "%.2f".format(Locale.US, q.brierScore)
        val calS = "%.2f".format(Locale.US, q.calibrationFactor)
        sb.append("\nOOS-история (${q.walkForwardSamples} окон): точность $wfH · Brier $wfB · калибр. ×$calS")
    }
    if (q.clockSynced) {
        sb.append("\nЧасы биржи: ${q.clockSource} (сдвиг ${q.clockOffsetMs}мс)")
    }
    if (l.conflicts.isNotEmpty()) {
        sb.append("\nКонфликты:\n")
        l.conflicts.forEach { sb.append("⚠ $it\n") }
    }
    return sb.toString().trim()
}

private fun radarText(intel: UnifiedIntelligenceReport): String {
    val cr = intel.chainRadar
    val sb = StringBuilder("CHAIN RADAR · ${cr.symbol} (${cr.moneyFlowBias}):\n\n")
    sb.append("Что происходит с деньгами до графика:\n")
    sb.append(cr.preChartAnswer).append("\n\n")
    sb.append("• Киты (≥$100K): ${cr.whaleTradesCount} сд., +$${TrendReasoningEngine.compactUsd(cr.whaleBuyUsd)} / -$${TrendReasoningEngine.compactUsd(cr.whaleSellUsd)}\n")
    cr.stablecoinsTotalUsd?.let { st ->
        val d = cr.stablecoinsChange7dPct?.let { "%+.2f%% за 7д".format(Locale.US, it) } ?: ""
        sb.append("• Стейблкоины: $${TrendReasoningEngine.compactUsd(st)} $d\n")
    }
    cr.feeFastSat?.let { f ->
        sb.append("• BTC сеть: $f sat/vB" + (cr.mempoolTxCount?.let { " · $it tx в мемпуле" } ?: "") + "\n")
    }
    if (cr.btcLargeTransfers.isNotEmpty()) {
        sb.append("• Крупные переводы ≥50 BTC: ${cr.btcLargeTransfers.size} шт.\n")
    }
    return sb.toString().trim()
}

private fun sentinelText(intel: UnifiedIntelligenceReport, market: AppState.MarketUi? = null): String {
    val evs = intel.sentinelEvents
    val sb = StringBuilder()
    if (evs.isEmpty()) {
        sb.append("CRYPTO SENTINEL (${intel.symbol}):\n\nАномалий не обнаружено — волатильность свечей, объём, спред бирж и новостная лента в норме.")
    } else {
        sb.append("CRYPTO SENTINEL · обнаружено событий: ${evs.size}\n\n")
        evs.take(6).forEach { ev ->
            val mark = when (ev.severity) {
                SentinelSeverity.CRITICAL -> "🔴"
                SentinelSeverity.WARNING -> "🟡"
                SentinelSeverity.INFO -> "⚪"
            }
            sb.append("$mark [${ev.category.labelRu}] ${ev.title}\n${ev.detail}\n\n")
        }
    }
    if (market != null) {
        sb.append("\nНовостной фон: ${market.newsStatus}")
        if (market.news.length() > 0) {
            val top = market.news.optJSONObject(0)
            if (top != null) {
                sb.append("\nСвежий заголовок: [${top.optString("source")}] ${top.optString("title")}")
            }
        }
    }
    return sb.toString().trim()
}

private fun botsText(intel: UnifiedIntelligenceReport): String {
    val sb = StringBuilder("Вычислительные боты (${intel.bots.size}):\n\n")
    intel.bots.forEach { b ->
        val wStr = intel.learning.botWeights[b.id]?.let { " · вес ×${"%.2f".format(Locale.US, it)}" } ?: ""
        sb.append("• ${b.name}: ${b.bias.name} (${(b.confidence * 100).toInt()}%$wStr)\n  ${b.summary}\n")
    }
    val learn = intel.learning
    if (learn.totalRecorded > 0) {
        val hr = learn.hitRatePct?.let { " · точность ${"%.0f%%".format(Locale.US, it)}" } ?: ""
        sb.append("\nПамять самообучения (${learn.folderLabel}): записей ${learn.totalRecorded}, проверено ${learn.verifiedCount}$hr.")
    }
    return sb.toString().trim()
}

private fun forecastText(r: JSONObject, m: AppState.MarketUi): String {
    val p = r.optJSONObject("prediction") ?: return "Прогноз недоступен"
    val f = p.optJSONObject("features") ?: JSONObject()
    val sb = StringBuilder()
    sb.append("P(UP) ").append(pct(f.optDouble("p_up")))
        .append(" · P(FLAT) ").append(pct(f.optDouble("p_flat")))
        .append(" · P(DOWN) ").append(pct(f.optDouble("p_down"))).append("\n\n")
    m.price?.let { sb.append("Текущая цена: ").append(formatChatPrice(it)).append(" (${m.feedSource})\n") }
    sb.append("Сторона: ").append(p.optString("side")).append("\n")
    sb.append("Уверенность: ").append(pct(p.optDouble("confidence"))).append("\n")
    val em = f.optDouble("expected_move_pct", Double.NaN)
    if (em.isFinite()) sb.append("Ожидание: ").append(fmtD("%+.2f%%", em)).append("\n")
    val s = f.optDouble("sigma_6h_pct", Double.NaN)
    if (s.isFinite()) sb.append("σ 6ч: ").append(fmtD("%.2f%%", s)).append("\n")
    val d = r.optJSONObject("data")
    if (d != null) sb.append("\nКачество данных: грейд ").append(d.optString("grade"))
        .append(" (источников ").append(d.optInt("n_available")).append("/").append(d.optInt("n_total")).append(")")
    p.optJSONArray("invalidate")?.let {
        if (it.length() > 0) {
            sb.append("\n\nПрогноз отменяется, если:")
            for (i in 0 until it.length()) sb.append("\n• ").append(it.optString(i))
        }
    }
    return sb.toString()
}

private fun trendText(t: JSONObject, intel: UnifiedIntelligenceReport?): String {
    if (t.optString("status") != "OK") {
        return intel?.let { logicText(it) }
            ?: "Тренд-слой: недостаточно данных (${t.optString("reason", "нужно больше истории")})"
    }
    val sb = StringBuilder()
    sb.append("Направление: ").append(t.optString("bias"))
        .append(" (сила ").append(t.optInt("strength")).append("/100)\n")
    intel?.let {
        sb.append("Фаза: ").append(it.trendLogic.phase).append(" (согласие ботов ").append(it.trendLogic.agreementPct).append("%)\n")
    }
    sb.append("Качество входа: ").append(t.optString("entry_quality"))
        .append(" · Риск: ").append(t.optString("risk")).append("\n")
    sb.append("Уверенность: ").append(pct(t.optDouble("confidence"))).append("\n\n")
    sb.append("Масштабы:\n")
    t.optJSONObject("mtf")?.let { mtf ->
        listOf("micro" to "1–5м", "short" to "15м–1ч", "mid" to "4ч+").forEach { (g, label) ->
            val d = mtf.optJSONObject(g) ?: return@forEach
            if (d.optString("status") == "OK") {
                sb.append("• ").append(label).append(" (").append(d.optString("tf")).append("): ")
                    .append(d.optString("direction")).append(" ").append(d.optInt("strength")).append("\n")
            }
        }
    }
    t.optJSONObject("structure")?.takeIf { it.optString("status") == "OK" }?.let { st ->
        sb.append("\nСтруктура: ").append(when (st.optString("structure")) {
            "HH_HL" -> "растущая (HH/HL)"; "LH_LL" -> "падающая (LH/LL)"; else -> st.optString("structure")
        }).append("\n")
        st.optJSONArray("events")?.let { ev ->
            for (i in 0 until ev.length()) sb.append("• ").append(ev.optString(i)).append("\n")
        }
    }
    intel?.trendLogic?.causalChain?.take(2)?.let { steps ->
        if (steps.isNotEmpty()) {
            sb.append("\nЛогика:\n")
            steps.forEach { sb.append("• ").append(it.conclusion).append("\n") }
        }
    }
    val c = t.optJSONArray("conflicts")
    if (c != null && c.length() > 0) {
        sb.append("\nПротиворечия: ")
        for (i in 0 until c.length()) sb.append(c.optString(i)).append("; ")
    }
    sb.append("\n\nТренд ≠ качество входа ≠ риск — три разных вопроса.")
    return sb.toString()
}

private fun agentsText(r: JSONObject): String {
    val agents = r.optJSONArray("agents") ?: return "Лес агентов пуст"
    val sb = StringBuilder("Лес агентов (").append(agents.length()).append(" ветвей):\n\n")
    for (i in 0 until agents.length()) {
        val a = agents.optJSONObject(i) ?: continue
        val mark = when (a.optString("status")) {
            "OK" -> if (a.optString("side") == "LONG") "🟢" else if (a.optString("side") == "SHORT") "🔴" else "⚪"
            "INSUFFICIENT" -> "▫️"
            else -> "▪️"
        }
        sb.append(mark).append(" ").append(agentShort(a.optString("agent")))
            .append(" ").append(a.optString("side"))
        val conf = a.optDouble("confidence", 0.0)
        if (conf.isFinite() && conf > 0) sb.append(" (").append(fmtD("%.2f", conf)).append(")")
        sb.append("\n")
    }
    val web = r.optJSONObject("council")
    sb.append("\nСовет: ").append(web?.optString("status") ?: "—")
    web?.optString("note")?.takeIf { it.isNotEmpty() }?.let { sb.append(" — ").append(it) }
    return sb.toString()
}

private fun agentShort(name: String): String = when (name) {
    "PRICE_ANALYST" -> "Цена"
    "ORDER_FLOW_ANALYST" -> "Поток ордеров"
    "LIQUIDITY_ANALYST" -> "Ликвидность"
    "DERIVATIVES_ANALYST" -> "Деривативы"
    "CROSS_EXCHANGE_ANALYST" -> "Кросс-биржа"
    "BTC_CORRELATION_ANALYST" -> "Корреляция BTC"
    "VOLATILITY_ANALYST" -> "Волатильность"
    "SENTIMENT_ANALYST" -> "Сентимент"
    "STATISTICAL_AI" -> "Стат-модель"
    "LIQUIDATION_CASCADE" -> "Каскады ликв."
    "ANOMALY_DETECTOR" -> "Аномалии"
    "OPTIONS_ANALYST" -> "Опционы"
    "ONCHAIN_ANALYST" -> "Ончейн"
    "DEFI_FLOW_ANALYST" -> "Потоки DeFi"
    "BASIS_ANALYST" -> "Базис"
    "WHALE_WATCH" -> "Киты"
    "SESSION_ANALYST" -> "Сессии"
    "DOMINANCE_ANALYST" -> "Доминирование"
    else -> name
}

private fun onchainText(r: JSONObject): String {
    if (!r.optBoolean("ok")) {
        return "Ончейн-контур: ошибка — ${r.optString("error", "источник недоступен")}\n" +
            "Это не влияет на основной прогноз: движок работает без второго контура."
    }
    val sb = StringBuilder("Ончейн-контур (OCIE): ").append(r.optString("state")).append("\n\n")
    val src = r.optJSONObject("sources")
    if (src != null && src.length() > 0) {
        sb.append("Источники:\n")
        val keys = mutableListOf<String>()
        src.keys().forEach { keys.add(it) }
        keys.forEach { k ->
            val st = src.optString(k)
            val mark = when (st) {
                "AVAILABLE" -> "🟢"; "STALE" -> "🟡"; "INSUFFICIENT" -> "🟠"; else -> "⚪"
            }
            sb.append(mark).append(" ").append(k).append(": ").append(st).append("\n")
        }
    }
    val n = r.optInt("n_events_stored", 0)
    if (n > 0) sb.append("\nСохранено событий: ").append(n)
    sb.append("\n\nНапоминание: ончейн-данные — контекст, не сигнал покупки/продажи.")
    return sb.toString()
}

internal fun outlookText(o: JSONObject, defaultHours: Int = 24): String {
    val h = o.optInt("horizon_hours", o.optInt("horizon_h", defaultHours)).let { if (it > 0) it else defaultHours }
    if (!o.optBoolean("ok", true) || o.optString("status") == "INSUFFICIENT") {
        val reason = o.optString("reason", o.optString("error", "нет достаточной истории свечей"))
        return "Прогноз ${h}ч: недостаточно данных ($reason)"
    }
    val titleHorizon = if (h == 6) "6 часов" else if (h == 24) "24 часа" else "${h}ч"
    val sb = StringBuilder("Свежий прогноз ($titleHorizon):\n\n")
    sb.append("P(UP) ").append(pct(o.optDouble("p_up")))
        .append(" · P(FLAT) ").append(pct(o.optDouble("p_flat")))
        .append(" · P(DOWN) ").append(pct(o.optDouble("p_down"))).append("\n\n")
    o.optString("side").takeIf { it.isNotBlank() }?.let {
        sb.append("Вектор: ").append(it).append("\n")
    }
    val thr = fmtD("%.2f", o.optDouble("threshold_pct"))
    val exp = fmtD("%+.2f%%", o.optDouble("expected_move_pct"))
    val lo = fmtD("%+.2f%%", o.optDouble("range_low_pct"))
    val hi = fmtD("%+.2f%%", o.optDouble("range_high_pct"))
    val sig = fmtD("%.2f%%", o.optDouble("sigma_pct", o.optDouble("sigma_${h}h_pct", Double.NaN)))
    sb.append("Порог движения: ±").append(thr).append("%\n")
    sb.append("Ожидание: ").append(exp)
    if (sig != "—") sb.append(" · σ").append("${h}ч ").append(sig)
    sb.append("\n")
    // Избегаем `%: ` внутри format() — безопасная сборка без UnknownFormatConversionException
    sb.append("Диапазон 80%: ").append(lo).append("…").append(hi)
    val loPx = o.optDouble("range_low_price", Double.NaN)
    val hiPx = o.optDouble("range_high_price", Double.NaN)
    if (loPx.isFinite() && hiPx.isFinite() && loPx > 0 && hiPx > 0) {
        sb.append(" (").append(formatChatPrice(loPx)).append(" … ").append(formatChatPrice(hiPx)).append(")")
    }
    sb.append("\n")

    o.optJSONObject("scenarios")?.let { sc ->
        sb.append("\nСценарии:\n")
        val baseObj = sc.optJSONObject("base")
        val bullObj = sc.optJSONObject("bull")
        val bearObj = sc.optJSONObject("bear")
        sb.append("• базовый: ").append(fmtD("%+.2f%%", baseObj?.optDouble("move_pct") ?: Double.NaN))
            .append(" (p=").append(fmtD("%.2f", baseObj?.optDouble("prob") ?: Double.NaN)).append(")\n")
        sb.append("• бычий: ").append(fmtD("%+.2f%%", bullObj?.optDouble("move_pct") ?: Double.NaN))
            .append(" (p=").append(fmtD("%.2f", bullObj?.optDouble("prob") ?: Double.NaN)).append(")\n")
        sb.append("• медвежий: ").append(fmtD("%+.2f%%", bearObj?.optDouble("move_pct") ?: Double.NaN))
            .append(" (p=").append(fmtD("%.2f", bearObj?.optDouble("prob") ?: Double.NaN)).append(")\n")
    }

    o.optJSONArray("drivers")?.let { dr ->
        if (dr.length() > 0) {
            sb.append("\nДрайверы и факты:\n")
            for (i in 0 until minOf(dr.length(), 4)) {
                val obj = dr.optJSONObject(i)
                if (obj != null) {
                    sb.append("• ").append(obj.optString("title"))
                    if (obj.optBoolean("is_rumor")) sb.append(" [слух]")
                    if (obj.optInt("n_copies") > 1) sb.append(" [x").append(obj.optInt("n_copies")).append(" копий]")
                    sb.append("\n")
                } else {
                    val str = dr.optString(i)
                    if (str.isNotBlank()) sb.append("• ").append(str).append("\n")
                }
            }
        }
    }

    o.optJSONArray("invalidation")?.let { inv ->
        if (inv.length() > 0) {
            sb.append("\nУсловия отмены:\n")
            for (i in 0 until minOf(inv.length(), 3)) {
                val item = inv.optString(i)
                if (item.isNotBlank()) sb.append("• ").append(item).append("\n")
            }
        }
    }

    val calObj = o.optJSONObject("calibration")
    if (calObj != null) {
        sb.append("\nКалибровка: ").append(calObj.optString("label"))
            .append(" (проверено ").append(calObj.optInt("n_checked")).append(")")
    } else {
        val calStr = o.optString("calibration")
        if (calStr.isNotBlank()) sb.append("\nКалибровка: ").append(calStr)
    }
    return sb.toString().trim()
}

private fun graphText(r: JSONObject): String {
    if (!r.optBoolean("ok")) {
        return "Путь A→Б: не завершён — ${r.optString("error", "критический узел недоступен")}\n" +
            "Так бывает при плохой связи: основные данные всё равно собираются."
    }
    val nodes = r.optJSONObject("nodes") ?: return "Путь A→Б: нет данных узлов"
    val sb = StringBuilder("Путь рассуждения A→Б (").append(r.optString("run_id").takeLast(8))
        .append(", ").append(r.optInt("elapsed_ms")).append(" мс):\n\n")
    listOf(
        "A_INTAKE" to "Данные", "B_QUALITY" to "Валидация", "C_FEATURES" to "Признаки",
        "D_MARKET" to "Рынок", "D_TREND" to "Тренд", "D_ONCHAIN" to "Ончейн",
        "E_COUNCILS" to "Советы", "F_CROSS" to "Проверка кейсов", "G_META" to "Мета-совет",
        "H_FORECAST" to "Прогноз", "I_OUTPUT" to "Вывод", "J_LEDGER" to "Память"
    ).forEach { (id, label) ->
        val st = nodes.optJSONObject(id)?.optString("status") ?: "—"
        val mark = when (st) {
            "completed" -> "✅"; "degraded" -> "🟡"; "skipped" -> "⏭"; "failed", "cancelled" -> "❌"; else -> "⬜"
        }
        sb.append(mark).append(" ").append(label).append(": ").append(st).append("\n")
    }
    val ev = r.optJSONArray("evidence")
    if (ev != null && ev.length() > 0) sb.append("\nДоказательств собрано: ").append(ev.length())
    return sb.toString()
}

private fun councilText(r: JSONObject): String {
    val c = r.optJSONObject("council") ?: return "Совет не собран"
    val sb = StringBuilder("Совет движка: ").append(c.optString("status")).append("\n")
    c.optString("note")?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append("\n") }
    val contra = c.optJSONArray("contradictions")
    if (contra != null && contra.length() > 0) {
        sb.append("\nПротиворечия:\n")
        for (i in 0 until contra.length()) sb.append("⚠ ").append(contra.optString(i)).append("\n")
    }
    c.optJSONObject("onchain_review")?.let { oc ->
        sb.append("\nОнчейн-проверка: ").append(oc.optString("state"))
        oc.optString("note")?.takeIf { it.isNotEmpty() }?.let { sb.append(" — ").append(it) }
    }
    sb.append("\n\nРежим SHADOW: совет аналитический, ордера не выставляются.")
    return sb.toString()
}
