package com.enamind.ai.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enamind.ai.ai.AiStudioClient
import com.enamind.ai.core.AppState
import com.enamind.ai.core.CrashGuard
import com.enamind.ai.core.FaultMonitor
import com.enamind.ai.core.PythonBridge
import com.enamind.ai.core.Settings
import com.enamind.ai.db.AnalysisEntity
import com.enamind.ai.db.ForecastEntity
import com.enamind.ai.export.ZipManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Настройки ENA v9.0 (вкладка нижней навигации): Тема / API (+ Google AI Studio) / Скилл /
 * История / Память (6ч + реал-тайм) / Сбои (Watchdog).
 * Режим чисто аналитический (SHADOW), без приватных ключей бирж.
 */
@Composable
fun SettingsScreen(
    state: AppState,
    onThemeChange: (Settings.Theme) -> Unit,
    onClose: () -> Unit,
    initialTab: Int = 0
) {
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, 5)) }
    LaunchedEffect(initialTab) {
        tab = initialTab.coerceIn(0, 5)
    }

    val summary by FaultMonitor.summary.collectAsState()

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Настройки",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "ENA · режим SHADOW",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("settings_done_button")
            ) {
                Text("Готово ✓", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }

        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            divider = {
                HorizontalDivider(thickness = 0.75.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        ) {
            val tabNames = listOf(
                "Тема",
                "API",
                "Скилл",
                "История",
                "Память",
                if (summary.activeFaults > 0) "Сбои (${summary.activeFaults})" else "Сбои"
            )
            tabNames.forEachIndexed { i, name ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    modifier = Modifier.testTag("settings_tab_$i"),
                    text = {
                        Text(
                            name,
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 12.sp,
                            fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (i == 5 && summary.activeFaults > 0 && tab != i) Color.EnaYellow
                            else Color.Unspecified,
                            maxLines = 1
                        )
                    }
                )
            }
        }

        when (tab) {
            0 -> ThemeTab(state, onThemeChange)
            1 -> ApiTab(state)
            2 -> SkillsTab(state)
            3 -> HistoryTab(state)
            4 -> MemoryTab(state)
            else -> FaultsTab(state)
        }
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun settingsChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
)

@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
    unfocusedContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.55f)
)

// ---------------------------------------------------------
// ТЕМА
// ---------------------------------------------------------
@Composable
private fun ThemeTab(state: AppState, onThemeChange: (Settings.Theme) -> Unit) {
    var theme by remember { mutableStateOf(state.settings.theme) }
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsCard {
            SectionTitle("Тема оформления")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = theme == Settings.Theme.BLACK,
                    onClick = {
                        theme = Settings.Theme.BLACK
                        state.settings.theme = theme
                        onThemeChange(theme)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = settingsChipColors(),
                    label = { Text("Ночная (AI Dark)", style = MaterialTheme.typography.labelLarge) }
                )
                FilterChip(
                    selected = theme == Settings.Theme.WHITE,
                    onClick = {
                        theme = Settings.Theme.WHITE
                        state.settings.theme = theme
                        onThemeChange(theme)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = settingsChipColors(),
                    label = { Text("Светлая", style = MaterialTheme.typography.labelLarge) }
                )
            }
            Text(
                "Ночная тема в стиле Google AI Studio снижает нагрузку на глаза и экономит заряд на OLED-экранах.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsCard {
            SectionTitle("Движок (фон)")
            var bg by remember { mutableStateOf(state.settings.bgEngine) }
            val notifPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) {
                state.settings.bgEngine = true
                state.engine.setBackgroundMode(true)
                bg = true
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Накапливать память в фоне",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = bg,
                    onCheckedChange = { on ->
                        bg = on
                        if (on) {
                            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                    ctx, android.Manifest.permission.POST_NOTIFICATIONS
                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                state.settings.bgEngine = true
                                state.engine.setBackgroundMode(true)
                            }
                        } else {
                            state.settings.bgEngine = false
                            state.engine.setBackgroundMode(false)
                        }
                    }
                )
            }
            Text(
                "Вкл: foreground-сервис, запись 6-часовых прогнозов и ленты реального времени продолжаются в свёрнутом приложении — память и калибровка растут быстрее.\n" +
                    "MIUI / HyperOS: при необходимости отключите оптимизацию батареи для ENA в системных настройках.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------
// API (+ изолированное подключение Google AI Studio)
// ---------------------------------------------------------
@Composable
private fun ApiTab(state: AppState) {
    val uiScope = rememberCoroutineScope()

    var aiKey by remember { mutableStateOf(state.settings.aiStudioApiKey) }
    var aiKeyVisible by remember { mutableStateOf(false) }
    var aiModel by remember { mutableStateOf(state.settings.aiStudioModel) }
    var aiTesting by remember { mutableStateOf(false) }
    var aiTestResult by remember { mutableStateOf<AiStudioClient.AiReply?>(null) }

    var newsKey by remember { mutableStateOf(state.settings.newsApiKey) }
    var bybitUrl by remember { mutableStateOf(state.settings.bybitBaseUrl) }
    var results by remember { mutableStateOf<List<Triple<String, String, String>>?>(null) }
    var checking by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Google AI Studio (изолированный свободный ИИ-чат)
        SettingsCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                SectionTitle("Google AI Studio (свободный ИИ-чат)")
            }
            Text(
                "Полностью изолированное подключение для вкладки «ИИ ЧАТ». Собеседник НЕ имеет доступа к квантовому движку, ботам и прогнозам ENA, поэтому не может повлиять на расчёты.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = aiKey,
                onValueChange = {
                    aiKey = it
                    state.settings.aiStudioApiKey = it.trim()
                },
                label = { Text("API-ключ Google AI Studio") },
                placeholder = { Text("AIza…", fontFamily = JetBrainsMonoFontFamily) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_studio_key_input"),
                visualTransformation = if (aiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(
                        onClick = { aiKeyVisible = !aiKeyVisible },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = if (aiKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (aiKeyVisible) "Скрыть ключ" else "Показать ключ"
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = settingsFieldColors()
            )

            Text(
                "Бесплатные быстрые модели с большим дневным лимитом:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AiStudioClient.FREE_MODELS.forEach { preset ->
                    FilterChip(
                        selected = aiModel == preset.id,
                        onClick = {
                            aiModel = preset.id
                            state.settings.aiStudioModel = preset.id
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("ai_model_chip_${preset.id}"),
                        colors = settingsChipColors(),
                        label = {
                            Text(
                                preset.title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (aiModel == preset.id) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    )
                }
            }

            val activePreset = AiStudioClient.FREE_MODELS.firstOrNull { it.id == aiModel }
            if (activePreset != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            activePreset.badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            activePreset.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            OutlinedTextField(
                value = aiModel,
                onValueChange = {
                    aiModel = it
                    if (it.isNotBlank()) state.settings.aiStudioModel = it.trim()
                },
                label = { Text("ID модели AI Studio (можно ввести свою)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_studio_model_input"),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = settingsFieldColors()
            )

            Button(
                onClick = {
                    aiTesting = true
                    aiTestResult = null
                    val keySnapshot = aiKey.trim()
                    val modelSnapshot = aiModel.trim()
                    uiScope.launch {
                        val reply = withContext(Dispatchers.IO) {
                            AiStudioClient.testConnection(keySnapshot, modelSnapshot)
                        }
                        aiTestResult = reply
                        aiTesting = false
                    }
                },
                enabled = !aiTesting,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("ai_studio_test_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (aiTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (aiTesting) "ПРОВЕРКА AI STUDIO…" else "Проверить ИИ-подключение",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            aiTestResult?.let { rep ->
                val ok = rep.ok
                val tint = if (ok) Color.EnaGreen else Color.EnaRed
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = tint.copy(alpha = 0.12f),
                    border = BorderStroke(0.8.dp, tint.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (ok) "Подключено · ${rep.latencyMs}мс" else "Ошибка подключения",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = tint
                        )
                        Text(
                            text = if (ok) rep.text else (rep.error ?: "Неизвестная ошибка"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // 2. Публичные потоки и авто-резервирование бирж
        SettingsCard {
            SectionTitle("Публичные потоки и авто-резервирование")
            Text(
                "Движок использует Bybit V5, Binance Vision (свечи, aggTrades, стакан), OKX, Coinbase и Kraken, а также Deribit, CoinGecko, DefiLlama и блокчейн-ончейн. " +
                    "Если Bybit заблокирован в регионе, данные автоматически переключаются на резервные биржи без остановки графика и чата.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = bybitUrl,
                onValueChange = { bybitUrl = it; state.settings.bybitBaseUrl = it },
                label = { Text("Base URL Bybit V5") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = settingsFieldColors()
            )
            OutlinedTextField(
                value = newsKey,
                onValueChange = { newsKey = it; state.settings.newsApiKey = it },
                label = { Text("Ключ NewsAPI (опционально — RSS работают без ключа)") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = settingsFieldColors()
            )
            Button(
                onClick = {
                    checking = true
                    results = null
                    state.checkAllApis { r ->
                        val out = mutableListOf<Triple<String, String, String>>()
                        for (i in 0 until r.length()) {
                            val res = r.getJSONObject(i)
                            out.add(
                                Triple(
                                    res.optString("name"),
                                    res.optString("status", if (res.optBoolean("ok")) "OK" else "FAIL"),
                                    res.optString("detail")
                                )
                            )
                        }
                        results = out
                        checking = false
                    }
                },
                enabled = !checking,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("api_check_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (checking) "ПРОВЕРКА API…" else "Проверить биржевые API",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        results?.let { list ->
            val okCount = list.count { (_, st, _) -> st == "OK" || st == "FALLBACK" }
            val warnCount = list.count { (_, st, _) -> st == "RATE_LIMIT" || st == "SLOW" }
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SectionTitle("Состояние источников")
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = (if (okCount >= list.size / 2) Color.EnaGreen else Color.EnaYellow).copy(alpha = 0.16f)
                    ) {
                        Text(
                            "$okCount/${list.size} онлайн" + if (warnCount > 0) " · $warnCount огранич." else "",
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            color = if (okCount >= list.size / 2) Color.EnaGreen else Color.EnaYellow,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                list.forEachIndexed { idx, (name, st, detail) ->
                    if (idx > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    }
                    val (badgeText, badgeColor) = when (st) {
                        "OK" -> "OK" to Color.EnaGreen
                        "FALLBACK" -> "РЕЗЕРВ" to MaterialTheme.colorScheme.primary
                        "RATE_LIMIT" -> "ЛИМИТ" to Color.EnaYellow
                        "SLOW" -> "МЕДЛЕННО" to Color.EnaYellow
                        "UNAVAILABLE" -> "НЕДОСТУПЕН" to MaterialTheme.colorScheme.onSurfaceVariant
                        else -> "СБОЙ" to Color.EnaRed
                    }
                    val subtitle = when (st) {
                        "RATE_LIMIT" -> "лимит запросов — повторите через минуту ($detail)"
                        "SLOW" -> "отвечает медленно ($detail)"
                        "UNAVAILABLE" -> "недоступен — используется резерв ($detail)"
                        else -> detail
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badgeColor.copy(alpha = 0.14f)
                        ) {
                            Text(
                                badgeText,
                                fontSize = 10.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }

        Text(
            "Проверка идёт параллельно и не дольше 15 секунд. Все ключи (включая AI Studio) зашифрованы Android Keystore и хранятся только на устройстве.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

// ---------------------------------------------------------
// SKILLS
// ---------------------------------------------------------
@Composable
private fun SkillsTab(state: AppState) {
    val ctx = LocalContext.current
    val uiScope = rememberCoroutineScope()
    var files by remember { mutableStateOf<JSONObject?>(null) }
    var selftest by remember { mutableStateOf<String?>(null) }
    var zipInfo by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var pendingZip by remember { mutableStateOf<File?>(null) }
    var trustInfo by remember { mutableStateOf<JSONObject?>(null) }
    var crashLog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        files = withContext(Dispatchers.IO) { ZipManager.listSkillFiles(ctx) }
    }

    fun doImport(confirmed: Boolean) {
        val tmp = pendingZip
        if (tmp == null) {
            trustInfo = null
            return
        }
        uiScope.launch {
            val (res, updatedFiles) = withContext(Dispatchers.IO) {
                ZipManager.import(ctx, tmp, confirmed) to ZipManager.listSkillFiles(ctx)
            }
            zipInfo = if (res.optBoolean("ok")) {
                "Импорт завершён: данные ${res.optJSONArray("restored")}, " +
                    "скриптов скилла ${res.optInt("skill_scripts")}, " +
                    "подпись: ${res.optString("signature", "—")}"
            } else {
                "ошибка импорта: ${res.optString("error")}"
            }
            files = updatedFiles
            trustInfo = null
        }
    }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            uiScope.launch {
                val tmp = File(ctx.cacheDir, "import.zip")
                val trust = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                    ZipManager.inspectTrust(tmp)
                }
                if (!trust.optBoolean("ok")) {
                    zipInfo = "ОТКАЗ: ${trust.optString("error")}"
                    return@launch
                }
                pendingZip = tmp
                if (trust.optString("status") == "trusted") doImport(true)
                else trustInfo = trust
            }
        }
    }

    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) {
            uiScope.launch {
                val info = withContext(Dispatchers.IO) {
                    val res = ZipManager.export(ctx, com.example.BuildConfig.VERSION_NAME)
                    if (res.optBoolean("ok")) {
                        val src = File(res.optString("path"))
                        ctx.contentResolver.openOutputStream(uri)?.use { os -> src.inputStream().use { it.copyTo(os) } }
                        "Экспортировано: ${src.length()} байт (прогнозы 6ч/24ч, память реал-тайм, веса ботов, журнал сбоев; без ключей)"
                    } else {
                        "ошибка экспорта: ${res.optString("error")}"
                    }
                }
                zipInfo = info
                exporting = false
            }
        } else {
            exporting = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsCard {
            SectionTitle("ENA Skill (Python-ядро v9.0)")
            files?.let { f ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Версия: v${f.optString("version")}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Модулей: ${f.optJSONArray("files")?.length() ?: 0}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        uiScope.launch {
                            val r = withContext(Dispatchers.IO) { PythonBridge.runSelftests() }
                            selftest = if (r.optBoolean("ok")) {
                                "ALL PASS: ${r.optInt("pass")}/${r.optInt("pass") + r.optInt("fail")}"
                            } else {
                                "FAIL: ${r.optInt("fail")} модулей\n${r.optJSONObject("results")}"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Самотест", fontSize = 11.sp) }

                OutlinedButton(
                    onClick = {
                        uiScope.launch {
                            selftest = withContext(Dispatchers.IO) { ZipManager.listSkillFiles(ctx).toString(2) }
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Манифест", fontSize = 11.sp) }

                OutlinedButton(
                    onClick = { pickZip.launch(arrayOf("application/zip")) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Импорт ZIP", fontSize = 11.sp) }
            }
            Text(
                "В архив попадают: история 6ч/24ч прогнозов (data + learning_vault), память реального времени (realtime_pulse_vault), веса 11 ботов, журнал сбоев (fault_history) и код стратегий (без API-ключей).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    exporting = true
                    saveZip.launch("ENA_export.zip")
                },
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    if (exporting) "Экспорт…" else "Экспортировать ENA ZIP",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            zipInfo?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            selftest?.let {
                Text(
                    "Тесты: $it",
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SettingsCard {
            SectionTitle("Файлы скилла")
            files?.optJSONArray("files")?.let { arr ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        val isUpdate = f.optString("origin") == "update"
                        Text(
                            "${f.optString("path")} — ${f.optLong("size")} б ${if (isUpdate) "[обновление]" else ""}",
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = if (isUpdate) Color.EnaGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        SettingsCard {
            SectionTitle("Диагностика (CrashGuard и Кэш)")
            var cacheNote by remember { mutableStateOf("Кэш: ${com.enamind.ai.core.StorageGuard.cacheSizeBytes(ctx) / 1024} КБ (чистится автоматически при запуске)") }
            Text(
                "При критическом сбое стек ошибки сохраняется локально. Очистка кэша удаляет только временные файлы и не трогает папки самообучения.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                cacheNote,
                fontSize = 11.sp,
                fontFamily = JetBrainsMonoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { crashLog = CrashGuard.lastReport(ctx) ?: "падений не зафиксировано" },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Отчёт сбоев", fontSize = 11.sp) }
                OutlinedButton(
                    onClick = {
                        val rep = com.enamind.ai.core.StorageGuard.clearTemporaryCache(ctx)
                        cacheNote = "Очищено ${rep.deletedCacheFiles} файлов (${rep.freedBytes / 1024} КБ); опыт сохранён"
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Очистить кэш", fontSize = 11.sp) }
                OutlinedButton(
                    onClick = { CrashGuard.clear(ctx); crashLog = "падений не зафиксировано" },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Сброс лога", fontSize = 11.sp) }
            }
            crashLog?.let {
                Text(
                    it.take(2500),
                    fontSize = 9.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    trustInfo?.let { t ->
        val unknown = t.optString("status") == "unknown"
        AlertDialog(
            onDismissRequest = { trustInfo = null },
            title = {
                Text(
                    if (unknown) "Неизвестная подпись" else "Архив без подписи",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (unknown) {
                        Text(
                            "ZIP подписан ключом ДРУГОГО устройства (ECDSA P-256).\n" +
                                "Отпечаток:\n${t.optString("fingerprint")}",
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFontFamily
                        )
                        Text(
                            "Скриптов Python: ${t.optInt("scripts")} · файлов данных: ${t.optInt("data_files")}",
                            fontSize = 11.sp
                        )
                        Text(
                            "Импорт выполнит этот Python-код внутри приложения. Продолжайте, только если доверяете архиву.",
                            fontSize = 11.sp,
                            color = Color.EnaYellow
                        )
                    } else {
                        Text(
                            "В архиве НЕТ подписи.\n" +
                                "Скриптов Python: ${t.optInt("scripts")} · файлов данных: ${t.optInt("data_files")}",
                            fontSize = 11.sp
                        )
                        Text(
                            "Целостность не подтверждена. Продолжайте, только если уверены в источнике.",
                            fontSize = 11.sp,
                            color = Color.EnaRed
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { doImport(true) }) { Text("Импортировать") }
            },
            dismissButton = {
                TextButton(onClick = { trustInfo = null; pendingZip = null }) { Text("Отмена") }
            }
        )
    }
}

// ---------------------------------------------------------
// ИСТОРИЯ
// ---------------------------------------------------------
@Composable
private fun HistoryTab(state: AppState) {
    val uiScope = rememberCoroutineScope()
    var analyses by remember { mutableStateOf<List<AnalysisEntity>>(emptyList()) }
    var forecasts by remember { mutableStateOf<List<ForecastEntity>>(emptyList()) }
    var mode by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val (aList, fList) = withContext(Dispatchers.IO) {
            state.db.analysisDao().last(50) to state.db.forecastDao().last(100)
        }
        analyses = aList
        forecasts = fList
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = mode == 0,
                onClick = { mode = 0 },
                modifier = Modifier.heightIn(min = 48.dp),
                colors = settingsChipColors(),
                label = { Text("Анализы (${analyses.size})") }
            )
            FilterChip(
                selected = mode == 1,
                onClick = { mode = 1 },
                modifier = Modifier.heightIn(min = 48.dp),
                colors = settingsChipColors(),
                label = { Text("Прогнозы (${forecasts.size})") }
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    uiScope.launch {
                        state.verifyOldForecasts()
                        delay(1500)
                        forecasts = withContext(Dispatchers.IO) { state.db.forecastDao().last(100) }
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Проверить", style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(
            "Прогнозы (6ч / 24ч) сохраняются автоматически и проверяются по факту после истечения горизонта (✓ совпало / ✗ мимо).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode == 0) {
                items(analyses, key = { it.id }) { a ->
                    SettingsCard {
                        Text(
                            "${a.symbol} ${a.interval} · ${a.regime ?: "?"} · ${timeShort(a.ts)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = JetBrainsMonoFontFamily
                        )
                        Text(
                            a.headline,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                }
            } else {
                items(forecasts, key = { it.fid }) { f ->
                    SettingsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (f.hit) {
                                    1 -> "✓"
                                    0 -> "✗"
                                    else -> "…"
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (f.hit) {
                                    1 -> Color.EnaGreen
                                    0 -> Color.EnaRed
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.width(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${f.symbol} ${f.side.uppercase()} ${Math.round(f.confidence * 100)}% · ${f.regime ?: "?"} · ${f.ts}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontFamily = JetBrainsMonoFontFamily
                                )
                                val moveVal = f.movePct?.takeIf { it.isFinite() }
                                val hRounded = if (f.horizonH.isFinite()) Math.round(f.horizonH) else 24L
                                val extra = if (f.hit != null && moveVal != null) {
                                    "факт: ${String.format(Locale.US, "%+.2f", moveVal)}% за ${hRounded}ч"
                                } else {
                                    "проверка через ${hRounded}ч · gate ${f.gateStatus ?: "-"}"
                                }
                                Text(
                                    extra,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------
// ENA MEMORY (6ч + Реал-тайм пульс + Python опыт)
// ---------------------------------------------------------
@Composable
private fun MemoryTab(state: AppState) {
    val uiScope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<JSONObject?>(null) }
    var journal by remember { mutableStateOf<JSONObject?>(null) }
    var feedback by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        stats = withContext(Dispatchers.IO) { PythonBridge.forecastStats() }
    }

    fun reload() {
        uiScope.launch {
            val (s, j, f) = withContext(Dispatchers.IO) {
                Triple(
                    PythonBridge.forecastStats(),
                    PythonBridge.journalStats(),
                    PythonBridge.feedbackReport()
                )
            }
            stats = s
            journal = j
            feedback = f
        }
    }

    val intel by state.engine.intelligence.collectAsState()
    val pulse by state.engine.realTimePulse.collectAsState()
    val vaultSnap = intel?.learning ?: remember(stats) { state.engine.memoryVault.snapshot() }
    val pulseSnap = remember(pulse.tsMs, pulse.verifiedCount, stats) {
        state.engine.pulseVault.snapshot()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsCard {
            SectionTitle("Папка самообучения (горизонт 6ч)")
            MemoryStat("Папка", vaultSnap.folderLabel)
            MemoryStat("Записано 6ч-наблюдений", "${vaultSnap.totalRecorded}")
            MemoryStat("Проверено по факту", "${vaultSnap.verifiedCount}")
            vaultSnap.hitRatePct?.takeIf { it.isFinite() }?.let {
                MemoryStat("Точность ботов (6ч)", "${Math.round(it)}%")
            }
            Text(
                "Папка создаётся автоматически в защищённом хранилище приложения и не удаляется при системной очистке кэша.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsCard {
            SectionTitle("Упреждающий пульс реального времени (5–15м)")
            MemoryStat("Папка", pulseSnap.folderName)
            MemoryStat("Снимков в реальном времени", "${pulseSnap.totalRecorded}")
            MemoryStat("Проверено по графику", "${pulseSnap.verifiedCount}")
            MemoryStat(
                "Точность (общая)",
                pulseSnap.accuracyPct?.takeIf { it.isFinite() }?.let {
                    String.format(Locale.US, "%.1f%%", it)
                } ?: "накопление данных"
            )
            MemoryStat(
                "Точность (последние 40)",
                pulseSnap.recentAccuracyPct?.takeIf { it.isFinite() }?.let {
                    String.format(Locale.US, "%.1f%%", it)
                } ?: "—"
            )
            if (pulseSnap.factorWeights.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Text(
                    "Веса микро-факторов:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                pulseSnap.factorWeights.forEach { (factor, w) ->
                    MemoryStat(
                        "  $factor",
                        if (w.isFinite()) String.format(Locale.US, "×%.2f", w) else "×1.00"
                    )
                }
            }
        }

        SettingsCard {
            SectionTitle("ENA MEMORY — накопленный опыт")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        busy = true
                        state.verifyOldForecasts()
                        state.refreshRadarNow()
                        uiScope.launch {
                            delay(2500)
                            stats = withContext(Dispatchers.IO) { PythonBridge.forecastStats() }
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Проверить старые", fontSize = 11.sp) }

                OutlinedButton(
                    onClick = { reload() },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Обновить статистику", fontSize = 11.sp) }
            }
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }

        stats?.let { s ->
            SettingsCard {
                SectionTitle("Прогнозы (проверенные по факту)")
                val checked = s.optJSONObject("checked")
                if (checked == null || checked.optInt("n", 0) == 0) {
                    Text(
                        "Проверенных прогнозов пока нет — они появятся после истечения горизонтов.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    MemoryStat("Всего проверено", "${checked.optInt("n")}")
                    val hitRate = checked.optDouble("hit_rate", Double.NaN)
                    if (hitRate.isFinite()) {
                        MemoryStat("Hit-rate", "${Math.round(hitRate * 100)}%")
                    }
                    checked.optDouble("mean_confidence", Double.NaN).takeIf { it.isFinite() }?.let {
                        MemoryStat("Средняя уверенность", "${Math.round(it * 100)}%")
                    }
                    checked.optDouble("overconfidence", Double.NaN).takeIf { it.isFinite() }?.let {
                        MemoryStat("Завышение уверенности", "${Math.round(it * 100)} пп")
                    }
                    MemoryStat("Ожидают проверки", "${s.optInt("unchecked", 0)}")
                }
                val byRegime = s.optJSONObject("by_regime")
                if (byRegime != null && byRegime.length() > 0) {
                    Text("По режимам:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    val keys = byRegime.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = byRegime.optJSONObject(k) ?: continue
                        val hr = v.optDouble("hit_rate", Double.NaN)
                        val oc = v.optDouble("overconfidence", Double.NaN)
                        Text(
                            "  $k: n=${v.optInt("n")}, hit=${if (hr.isFinite()) "${Math.round(hr * 100)}%" else "—"}" +
                                (if (v.has("overconfidence") && oc.isFinite()) ", завышение=${Math.round(oc * 100)}пп" else ""),
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                val notes = s.optJSONArray("notes")
                if (notes != null && notes.length() > 0) {
                    Text("Наблюдения:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    for (i in 0 until notes.length()) {
                        Text(
                            "• ${notes.optString(i)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        journal?.let { j ->
            SettingsCard {
                SectionTitle("Журнал")
                val edge = j.optJSONObject("EDGE_compliant_only")
                if (edge == null || edge.optInt("n", 0) == 0) {
                    Text(
                        "Журнал пуст.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    MemoryStat("Compliant-записей", "${edge.optInt("n")}")
                    edge.optDouble("winrate", Double.NaN).takeIf { it.isFinite() }?.let {
                        MemoryStat("Winrate", "${Math.round(it * 100)}%")
                    }
                    edge.optDouble("avgR", Double.NaN).takeIf { it.isFinite() }?.let {
                        MemoryStat("Средний R", String.format(Locale.US, "%.2f", it))
                    }
                    MemoryStat("Вердикт", j.optString("verdict"))
                }
            }
        }

        feedback?.let { f ->
            SettingsCard {
                SectionTitle("Обратная связь (p_hat)")
                Text(
                    if (f.optString("status") == "OK") {
                        "p_hat = ${f.optDouble("p_hat")} (n=${f.optJSONObject("calibration")?.optInt("n") ?: 0})"
                    } else {
                        f.optString("msg")
                    },
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMonoFontFamily
                )
            }
        }

        Text(
            "Память влияет на калибровку уверенности при n≥10. Правила Risk Gate остаются неизменными.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

// ---------------------------------------------------------
// СБОИ (сторожевые агенты самодиагностики FaultMonitor)
// ---------------------------------------------------------
@Composable
private fun FaultsTab(state: AppState) {
    val events by FaultMonitor.events.collectAsState()
    val summary by FaultMonitor.summary.collectAsState()
    val uiScope = rememberCoroutineScope()
    var filter by remember { mutableIntStateOf(0) } // 0 Все | 1 Ошибки | 2 Сеть | 3 Боты
    var auditing by remember { mutableStateOf(false) }

    val filtered = remember(events, filter) {
        when (filter) {
            1 -> events.filter {
                it.severity == FaultMonitor.Severity.ERROR || it.severity == FaultMonitor.Severity.CRITICAL
            }
            2 -> events.filter {
                it.kind == FaultMonitor.Kind.NETWORK || it.kind == FaultMonitor.Kind.WEBSOCKET
            }
            3 -> events.filter {
                it.kind == FaultMonitor.Kind.AGENT || it.kind == FaultMonitor.Kind.PYTHON || it.kind == FaultMonitor.Kind.ENGINE
            }
            else -> events
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Статус-карточка сторожевых агентов
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val healthColor = when {
                    summary.criticalCount > 0 -> Color.EnaRed
                    summary.activeFaults > 0 -> Color.EnaYellow
                    else -> Color.EnaGreen
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(healthColor)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (summary.healthy && summary.activeFaults == 0) "Система в норме"
                        else "Активных сбоев: ${summary.activeFaults}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Исправно узлов: ${summary.workingComponents} · Проблемных: ${summary.failingComponents.size} · В журнале: ${summary.totalLogged}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = "Самодиагностика ENA",
                    tint = healthColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (summary.failingComponents.isNotEmpty()) {
                Text(
                    "Деградация: ${summary.failingComponents.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.EnaYellow,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        auditing = true
                        uiScope.launch {
                            withContext(Dispatchers.IO) { FaultMonitor.runAudit(state) }
                            auditing = false
                        }
                    },
                    enabled = !auditing,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("faults_audit_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    if (auditing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Проверить сейчас", style = MaterialTheme.typography.labelLarge)
                }

                OutlinedButton(
                    onClick = { FaultMonitor.clear() },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("faults_clear_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Очистить", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // Фильтры журнала
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Все", "Ошибки", "Сеть", "Боты").forEachIndexed { idx, label ->
                FilterChip(
                    selected = filter == idx,
                    onClick = { filter = idx },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = settingsChipColors(),
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = Color.EnaGreen,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        "Сбоев по выбранному фильтру не зафиксировано",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id }) { ev ->
                    val sevColor = when (ev.severity) {
                        FaultMonitor.Severity.CRITICAL -> Color.EnaRed
                        FaultMonitor.Severity.ERROR -> Color.EnaRed
                        FaultMonitor.Severity.WARNING -> Color.EnaYellow
                        FaultMonitor.Severity.INFO -> MaterialTheme.colorScheme.primary
                    }
                    SettingsCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (ev.resolved) Color.EnaGreen else sevColor)
                            )
                            Text(
                                text = ev.component,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (ev.resolved) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color.EnaGreen.copy(alpha = 0.16f)
                                ) {
                                    Text(
                                        "РЕШЕНО",
                                        fontSize = 9.sp,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.EnaGreen,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = sevColor.copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        ev.kind.labelRu,
                                        fontSize = 9.sp,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        color = sevColor,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = ev.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = ev.recovery.ifBlank { "Мониторинг активен" },
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = timeShort(ev.tsMs),
                                fontSize = 10.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(
        t,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun MemoryStat(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            fontFamily = JetBrainsMonoFontFamily
        )
    }
}

private fun timeShort(ts: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ts))
