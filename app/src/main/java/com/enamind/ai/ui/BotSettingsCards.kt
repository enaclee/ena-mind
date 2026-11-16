package com.enamind.ai.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enamind.ai.bot.BotConfig
import com.enamind.ai.bot.BotFloatingOverlayManager
import com.enamind.ai.bot.BotMode
import com.enamind.ai.bot.BotUiState
import com.enamind.ai.bot.BybitCredentialSlot
import com.enamind.ai.bot.DirectionPolicy
import com.enamind.ai.bot.HudDisplayMode
import com.enamind.ai.bot.HudGlowZone
import com.enamind.ai.bot.MarginMode
import com.enamind.ai.bot.TargetBasis
import com.enamind.ai.bot.TimeoutBehavior
import com.enamind.ai.core.AppState
import kotlin.math.roundToInt

@Composable
internal fun BotModeAndKeysCard(
    state: AppState,
    uiState: BotUiState
) {
    val bot = state.botEngine
    val cfg = uiState.config
    val amber = enaAmberText()
    val green = enaGreenText()

    var apiKeyText by remember { mutableStateOf("") }
    var apiSecretText by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    var paperStartText by remember { mutableStateOf(cfg.paperStartBalanceUsdt.toInt().toString()) }

    LaunchedEffect(cfg.mode) {
        val slot = bot.loadCredentialSlot(cfg.mode)
        apiKeyText = slot.apiKey
        apiSecretText = slot.apiSecret
    }

    BotSectionCard(
        title = "РЕЖИМ И ИЗОЛИРОВАННЫЕ КЛЮЧИ BYBIT",
        subtitle = "3 независимых слота: Demo / Testnet / Mainnet (Tink AES-256)",
        icon = Icons.Outlined.Key
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BotMode.entries.forEach { m ->
                FilterChip(
                    selected = cfg.mode == m,
                    enabled = !uiState.running || cfg.mode == m,
                    onClick = { bot.updateConfig { it.copy(mode = m) } },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("bot_mode_${m.id}"),
                    label = { Text(m.shortLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = botChipColors()
                )
            }
        }

        if (cfg.mode == BotMode.PAPER) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = paperStartText,
                    onValueChange = { txt ->
                        paperStartText = txt
                        txt.toDoubleOrNull()?.let { v ->
                            bot.updateConfig { it.copy(paperStartBalanceUsdt = v) }
                        }
                    },
                    label = { Text("Стартовый депозит Paper (USDT)", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { bot.probeSelectedAccount() },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Обновить", fontSize = 11.sp)
                }
            }
        } else {
            val savedSlot = remember(cfg.mode, uiState.lastCheckNote) { bot.loadCredentialSlot(cfg.mode) }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Слот ${cfg.mode.labelRu} · Ключ: ${savedSlot.maskedKey()}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = if (savedSlot.hasKeys) green else amber
                    )
                    Text(
                        "Сервер: ${cfg.mode.restBaseUrl}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = apiKeyText,
                onValueChange = { apiKeyText = it },
                label = { Text("API Key (${cfg.mode.shortLabel})", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bot_api_key_input")
            )
            OutlinedTextField(
                value = apiSecretText,
                onValueChange = { apiSecretText = it },
                label = { Text("API Secret (${cfg.mode.shortLabel})", fontSize = 11.sp) },
                singleLine = true,
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(
                        onClick = { showSecret = !showSecret },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = if (showSecret) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showSecret) "Скрыть ключ" else "Показать ключ"
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bot_api_secret_input")
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        bot.saveCredentialSlot(
                            cfg.mode,
                            BybitCredentialSlot(apiKey = apiKeyText, apiSecret = apiSecretText)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("bot_save_keys_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Сохранить", fontSize = 11.5.sp)
                }
                OutlinedButton(
                    onClick = {
                        apiKeyText = ""
                        apiSecretText = ""
                        bot.clearCredentialSlot(cfg.mode)
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("bot_clear_keys_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Очистить", fontSize = 11.5.sp)
                }
            }
            OutlinedButton(
                onClick = { bot.probeSelectedAccount() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("bot_probe_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Проверить ключи и баланс (${cfg.mode.shortLabel})", fontSize = 11.5.sp)
            }
        }

        if (uiState.lastCheckNote.isNotBlank()) {
            Text(
                uiState.lastCheckNote,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = if (uiState.exchangeConnected) green else amber
            )
        }
    }
}

@Composable
internal fun BotStrategyRiskCard(
    state: AppState,
    cfg: BotConfig
) {
    val bot = state.botEngine
    val amber = enaAmberText()
    val contract = remember(cfg.symbol) { state.contractCatalog.findContract(cfg.symbol) }
    val maxLev = (contract?.maxLeverage?.toInt() ?: 100).coerceIn(1, 125)

    var capitalText by remember(cfg.capitalUsdt) { mutableStateOf(cfg.capitalUsdt.toInt().toString()) }
    var minProfitText by remember(cfg.minNetProfitPct) { mutableStateOf(cfg.minNetProfitPct.toString()) }
    var maxProfitText by remember(cfg.maxProfitPct) { mutableStateOf(cfg.maxProfitPct.toString()) }
    var trailText by remember(cfg.trailingGivebackPct) { mutableStateOf(cfg.trailingGivebackPct.toString()) }
    var holdMinText by remember(cfg.maxHoldWaitMin) { mutableStateOf(cfg.maxHoldWaitMin.toString()) }
    var slText by remember(cfg.stopLossPct) { mutableStateOf(cfg.stopLossPct.toString()) }
    var dailyLossText by remember(cfg.dailyMaxLossPct) { mutableStateOf(cfg.dailyMaxLossPct.toString()) }
    var levSlider by remember(cfg.leverage) { mutableFloatStateOf(cfg.leverage.toFloat().coerceIn(1f, maxLev.toFloat())) }

    BotSectionCard(
        title = "СТРАТЕГИЯ, КАПИТАЛ И РИСК",
        subtitle = "Net PnL 0.5%–3.5% (после комиссий) · SL отключаем во всех режимах",
        icon = Icons.Outlined.Tune
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = capitalText,
                onValueChange = { t ->
                    capitalText = t
                    t.toDoubleOrNull()?.let { v -> bot.updateConfig { it.copy(capitalUsdt = v) } }
                },
                label = { Text("Капитал (USDT)", fontSize = 11.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = dailyLossText,
                onValueChange = { t ->
                    dailyLossText = t
                    t.toDoubleOrNull()?.let { v -> bot.updateConfig { it.copy(dailyMaxLossPct = v) } }
                },
                label = { Text("Макс. убыток/день %", fontSize = 11.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Кредитное плечо (${cfg.symbol})", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${levSlider.roundToInt()}x (макс ${maxLev}x)",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = levSlider,
                onValueChange = { levSlider = it },
                onValueChangeFinished = { bot.updateConfig { it.copy(leverage = levSlider.roundToInt()) } },
                valueRange = 1f..maxLev.toFloat().coerceAtLeast(2f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MarginMode.entries.forEach { mm ->
                FilterChip(
                    selected = cfg.marginMode == mm,
                    onClick = { bot.updateConfig { it.copy(marginMode = mm) } },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    label = { Text(mm.labelRu, fontSize = 11.sp) },
                    colors = botChipColors()
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TargetBasis.entries.forEach { tb ->
                FilterChip(
                    selected = cfg.targetBasis == tb,
                    onClick = { bot.updateConfig { it.copy(targetBasis = tb) } },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    label = { Text(tb.labelRu, fontSize = 10.5.sp) },
                    colors = botChipColors()
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DirectionPolicy.entries.forEach { dpPolicy ->
                FilterChip(
                    selected = cfg.directionPolicy == dpPolicy,
                    onClick = { bot.updateConfig { it.copy(directionPolicy = dpPolicy) } },
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(dpPolicy.labelRu, fontSize = 11.sp) },
                    colors = botChipColors()
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = minProfitText,
                onValueChange = { t ->
                    minProfitText = t
                    t.toDoubleOrNull()?.let { v -> bot.updateConfig { it.copy(minNetProfitPct = v) } }
                },
                label = { Text("Мин. плюс %", fontSize = 10.5.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = maxProfitText,
                onValueChange = { t ->
                    maxProfitText = t
                    t.toDoubleOrNull()?.let { v -> bot.updateConfig { it.copy(maxProfitPct = v) } }
                },
                label = { Text("Макс. цель %", fontSize = 10.5.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = trailText,
                onValueChange = { t ->
                    trailText = t
                    t.toDoubleOrNull()?.let { v -> bot.updateConfig { it.copy(trailingGivebackPct = v) } }
                },
                label = { Text("Откат трала %", fontSize = 10.5.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = holdMinText,
                onValueChange = { t ->
                    holdMinText = t
                    t.toIntOrNull()?.let { v -> bot.updateConfig { it.copy(maxHoldWaitMin = v) } }
                },
                label = { Text("Ожидание (мин)", fontSize = 10.5.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        BotSwitchRow(
            title = "Биржевой Stop-Loss (можно отключить)",
            subtitle = if (cfg.stopLossEnabled) {
                "Активен SL -${cfg.stopLossPct}% Net"
            } else {
                "Stop-Loss выключен во всех режимах — контроль до ликвидации/ожидания"
            },
            checked = cfg.stopLossEnabled,
            testTag = "bot_sl_switch",
            onCheckedChange = { on -> bot.updateConfig { it.copy(stopLossEnabled = on) } }
        )
        if (!cfg.stopLossEnabled) {
            Text(
                "⚠ Stop-Loss отключён: убыток ограничен только ликвидацией или ручным закрытием!",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = amber
            )
        } else {
            OutlinedTextField(
                value = slText,
                onValueChange = { t ->
                    slText = t
                    t.toDoubleOrNull()?.let { v -> bot.updateConfig { it.copy(stopLossPct = v) } }
                },
                label = { Text("Уровень Stop-Loss (Net %)", fontSize = 11.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        BotSwitchRow(
            title = "Дублировать трал на сервере Bybit",
            subtitle = "При Net >= ${cfg.minNetProfitPct}% ставит защитный trading-stop на бирже",
            checked = cfg.serverTrailingGuardEnabled,
            onCheckedChange = { on -> bot.updateConfig { it.copy(serverTrailingGuardEnabled = on) } }
        )
        BotSwitchRow(
            title = "Консенсус Dynamic Glow",
            subtitle = "Входить только при подтверждённой подсветке (≥40%)",
            checked = cfg.requireGlowConsensus,
            onCheckedChange = { on -> bot.updateConfig { it.copy(requireGlowConsensus = on) } }
        )
        BotSwitchRow(
            title = "Блокировать вход во флете Радара",
            subtitle = "ADX < 18 и Efficiency Ratio < 0.20",
            checked = cfg.requireRadarNotFlat,
            onCheckedChange = { on -> bot.updateConfig { it.copy(requireRadarNotFlat = on) } }
        )
        BotSwitchRow(
            title = "Вход против поглощения",
            subtitle = "По умолчанию выключено (защита от стен)",
            checked = cfg.allowCounterAbsorption,
            onCheckedChange = { on -> bot.updateConfig { it.copy(allowCounterAbsorption = on) } }
        )
    }
}

@Composable
internal fun BotHudAndBackgroundCard(
    state: AppState,
    uiState: BotUiState
) {
    val ctx = LocalContext.current
    val bot = state.botEngine
    val cfg = uiState.config
    val hasOverlayPerm = BotFloatingOverlayManager.canDrawOverlays(ctx)
    val amber = enaAmberText()
    val stats = uiState.activeModeStats

    val zoneColor = when (stats.glowZone) {
        HudGlowZone.RED -> Color.EnaRed
        HudGlowZone.YELLOW -> amber
        HudGlowZone.GREEN -> enaGreenText()
        HudGlowZone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    BotSectionCard(
        title = "ПЛАВАЮЩИЙ HUD И ФОН ANDROID 15",
        subtitle = "Можно включить заранее до запуска бота · цвета по Winrate %",
        icon = Icons.Outlined.Layers
    ) {
        BotSwitchRow(
            title = "Плавающий HUD поверх окон",
            subtitle = "Светится по Winrate (<50% красн · 50–65% жёлт · >65% зел) и показывает пару",
            checked = cfg.hudEnabled,
            testTag = "bot_hud_switch",
            onCheckedChange = { on ->
                if (on && !BotFloatingOverlayManager.canDrawOverlays(ctx)) {
                    BotFloatingOverlayManager.openOverlayPermissionSettings(ctx)
                    state.toast("Разрешите «Поверх других приложений» и включите HUD")
                }
                bot.updateConfig { it.copy(hudEnabled = on) }
            }
        )

        if (!hasOverlayPerm) {
            OutlinedButton(
                onClick = { BotFloatingOverlayManager.openOverlayPermissionSettings(ctx) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text("Разрешить наложение поверх окон", fontSize = 11.5.sp)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudDisplayMode.entries.forEach { mode ->
                FilterChip(
                    selected = cfg.hudDisplayMode == mode,
                    onClick = { bot.updateConfig { it.copy(hudDisplayMode = mode) } },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    label = { Text(mode.labelRu, fontSize = 10.sp) },
                    colors = botChipColors()
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = zoneColor.copy(alpha = 0.12f),
            border = BorderStroke(0.8.dp, zoneColor.copy(alpha = 0.45f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(9.dp).background(zoneColor, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Цвет HUD (${cfg.symbol}): ${
                        when (stats.glowZone) {
                            HudGlowZone.RED -> "Красный (<50%)"
                            HudGlowZone.YELLOW -> "Жёлтый (50–65%)"
                            HudGlowZone.GREEN -> "Зелёный (>65%)"
                            HudGlowZone.NEUTRAL -> "Нейтральный (нет сделок)"
                        }
                    }",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = zoneColor
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            "При таймауте Android 15 (6ч dataSync):",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimeoutBehavior.entries.forEach { tb ->
                FilterChip(
                    selected = cfg.timeoutBehavior == tb,
                    onClick = { bot.updateConfig { it.copy(timeoutBehavior = tb) } },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    label = { Text(tb.labelRu, fontSize = 10.sp) },
                    colors = botChipColors()
                )
            }
        }

        OutlinedButton(
            onClick = {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val fallback = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(intent) }.onFailure { runCatching { ctx.startActivity(fallback) } }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Text("Настройки батареи (без ограничений)", fontSize = 11.sp)
        }
    }
}

@Composable
internal fun BotSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 10.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
        )
    }
}

@Composable
internal fun botChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
)
