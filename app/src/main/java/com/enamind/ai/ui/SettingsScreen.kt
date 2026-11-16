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
import com.enamind.ai.core.FaultMonitor
import com.enamind.ai.core.Settings
import com.enamind.ai.orderflow.OrderFlowEventVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Настройки ENA: Тема / API (Google AI Studio + OpenAI-совместимые ключи) / Память (экспорт JSON + CSV) / Сбои.
 */
@Composable
fun SettingsScreen(
    state: AppState,
    onThemeChange: (Settings.Theme) -> Unit,
    onClose: () -> Unit,
    initialTab: Int = 0
) {
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    LaunchedEffect(initialTab) {
        tab = initialTab.coerceIn(0, 3)
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
                    "ENA · аналитический терминал",
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
                            color = if (i == 3 && summary.activeFaults > 0 && tab != i) Color.EnaYellow
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
            2 -> MemoryTab(state)
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
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("theme_dark_button"),
                    colors = settingsChipColors(),
                    label = { Text("Ночная", style = MaterialTheme.typography.labelLarge) }
                )
                FilterChip(
                    selected = theme == Settings.Theme.WHITE,
                    onClick = {
                        theme = Settings.Theme.WHITE
                        state.settings.theme = theme
                        onThemeChange(theme)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("theme_light_button"),
                    colors = settingsChipColors(),
                    label = { Text("Светлая", style = MaterialTheme.typography.labelLarge) }
                )
            }
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
                    "Накапливать аналитику в фоне",
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
        }
    }
}

// ---------------------------------------------------------
// API (+ универсальное подключение AI Studio & OpenAI-совместимых API)
// ---------------------------------------------------------
@Composable
private fun ApiTab(state: AppState) {
    val uiScope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(state.settings.aiProvider) }

    var aiKey by remember { mutableStateOf(state.settings.aiStudioApiKey) }
    var aiKeyVisible by remember { mutableStateOf(false) }
    var aiModel by remember { mutableStateOf(state.settings.aiStudioModel) }
    var fetchedModels by remember { mutableStateOf<List<AiStudioClient.AvailableModel>>(emptyList()) }
    var modelsLoading by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }

    var openAiBaseUrl by remember { mutableStateOf(state.settings.aiOpenAiBaseUrl) }
    var openAiKey by remember { mutableStateOf(state.settings.aiOpenAiApiKey) }
    var openAiKeyVisible by remember { mutableStateOf(false) }
    var openAiModel by remember { mutableStateOf(state.settings.aiOpenAiModel) }

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
        // 1. Универсальный AI-клиент (изолирован от расчётов ENA)
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
                SectionTitle("Универсальный ИИ-чат (изолирован)")
            }
            Text(
                "Собеседник во вкладке «ИИ ЧАТ» не имеет доступа к квантовому движку и Order Flow. Выберите Google AI Studio или любой OpenAI-совместимый API.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = provider == Settings.PROVIDER_GEMINI,
                    onClick = {
                        provider = Settings.PROVIDER_GEMINI
                        state.settings.aiProvider = Settings.PROVIDER_GEMINI
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("ai_provider_gemini"),
                    colors = settingsChipColors(),
                    label = { Text("Google AI Studio", fontSize = 11.5.sp) }
                )
                FilterChip(
                    selected = provider == Settings.PROVIDER_OPENAI_COMPAT,
                    onClick = {
                        provider = Settings.PROVIDER_OPENAI_COMPAT
                        state.settings.aiProvider = Settings.PROVIDER_OPENAI_COMPAT
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("ai_provider_openai"),
                    colors = settingsChipColors(),
                    label = { Text("OpenAI-совместимый", fontSize = 11.5.sp) }
                )
            }

            if (provider == Settings.PROVIDER_GEMINI) {
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
                    "Актуальные модели Gemini 3.5 (устаревшие модели отключены):",
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

                OutlinedButton(
                    onClick = {
                        modelsLoading = true
                        modelsError = null
                        val keySnapshot = aiKey.trim()
                        uiScope.launch {
                            val (list, err) = withContext(Dispatchers.IO) {
                                AiStudioClient.fetchGeminiModels(keySnapshot)
                            }
                            fetchedModels = list
                            modelsError = err
                            modelsLoading = false
                        }
                    },
                    enabled = !modelsLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("ai_fetch_models_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (modelsLoading) "Загрузка списка моделей…" else "Загрузить доступные модели по API-ключу")
                }

                modelsError?.let { err ->
                    Text(err, fontSize = 11.sp, color = Color.EnaYellow)
                }

                if (fetchedModels.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        fetchedModels.forEach { m ->
                            FilterChip(
                                selected = aiModel == m.id,
                                onClick = {
                                    aiModel = m.id
                                    state.settings.aiStudioModel = m.id
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                                colors = settingsChipColors(),
                                label = { Text(m.id, fontSize = 11.sp) }
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
                    label = { Text("ID модели AI Studio") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_studio_model_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors()
                )
            } else {
                OutlinedTextField(
                    value = openAiBaseUrl,
                    onValueChange = {
                        openAiBaseUrl = it
                        state.settings.aiOpenAiBaseUrl = it.trim()
                    },
                    label = { Text("Base URL (OpenAI / OpenRouter / Groq / DeepSeek)") },
                    placeholder = { Text("https://api.openai.com/v1", fontFamily = JetBrainsMonoFontFamily) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_openai_baseurl_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors()
                )
                OutlinedTextField(
                    value = openAiKey,
                    onValueChange = {
                        openAiKey = it
                        state.settings.aiOpenAiApiKey = it.trim()
                    },
                    label = { Text("API-ключ (Bearer)") },
                    placeholder = { Text("sk-…", fontFamily = JetBrainsMonoFontFamily) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_openai_key_input"),
                    visualTransformation = if (openAiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(
                            onClick = { openAiKeyVisible = !openAiKeyVisible },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = if (openAiKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (openAiKeyVisible) "Скрыть ключ" else "Показать ключ"
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors()
                )
                OutlinedTextField(
                    value = openAiModel,
                    onValueChange = {
                        openAiModel = it
                        if (it.isNotBlank()) state.settings.aiOpenAiModel = it.trim()
                    },
                    label = { Text("ID модели (например, gpt-4o-mini, deepseek-chat)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_openai_model_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors()
                )
            }

            Button(
                onClick = {
                    aiTesting = true
                    aiTestResult = null
                    val provSnapshot = provider
                    val keySnapshot = aiKey.trim()
                    val modelSnapshot = aiModel.trim()
                    val oaiUrl = openAiBaseUrl.trim()
                    val oaiKey = openAiKey.trim()
                    val oaiModel = openAiModel.trim()
                    uiScope.launch {
                        val reply = withContext(Dispatchers.IO) {
                            AiStudioClient.testConnection(
                                apiKey = keySnapshot,
                                modelId = modelSnapshot,
                                provider = provSnapshot,
                                openAiBaseUrl = oaiUrl,
                                openAiKey = oaiKey,
                                openAiModel = oaiModel
                            )
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
                    if (aiTesting) "ПРОВЕРКА AI…" else "Проверить ИИ-подключение",
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
// ПАМЯТЬ (только экспорт истории Order Flow & Absorption в JSON и CSV)
// ---------------------------------------------------------
@Composable
private fun MemoryTab(state: AppState) {
    val ctx = LocalContext.current
    val uiScope = rememberCoroutineScope()
    var pendingBundle by remember { mutableStateOf<OrderFlowEventVault.ExportBundle?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }

    val saveCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        val bundle = pendingBundle
        if (uri != null && bundle != null) {
            uiScope.launch {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(bundle.csvText.toByteArray(Charsets.UTF_8))
                    }
                }
                exportStatus = "CSV сохранён (${bundle.eventCount} событий поглощения/атак)"
            }
        }
    }

    val saveJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val bundle = pendingBundle
        if (uri != null && bundle != null) {
            uiScope.launch {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(bundle.jsonText.toByteArray(Charsets.UTF_8))
                    }
                }
                exportStatus = "JSON сохранён (${bundle.eventCount} событий) · выберите файл для CSV…"
                saveCsvLauncher.launch("ena_order_flow_history.csv")
            }
        } else if (bundle != null) {
            saveCsvLauncher.launch("ena_order_flow_history.csv")
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
            SectionTitle("Экспорт истории Order Flow")
            Text(
                "Выгрузка реальных зафиксированных событий поглощения, атак уровней, реакций цены через 1с / 5с / 15с / 30с и эмпирической статистики разворотов и пробоев в форматах JSON и CSV.\n\n" +
                    "Позволяет проверить точность гипотез на реальном рынке, провести независимый бэктест и понять, какие признаки работают лучше на выбранных парах (без API-ключей и персональных данных).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = {
                    val bundle = state.exportOrderFlowHistoryBundle()
                    pendingBundle = bundle
                    exportStatus = "Подготовлено событий: ${bundle.eventCount}"
                    saveJsonLauncher.launch("ena_order_flow_history.json")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("memory_download_history_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    "Скачать историю",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            exportStatus?.let { st ->
                Text(
                    st,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
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
