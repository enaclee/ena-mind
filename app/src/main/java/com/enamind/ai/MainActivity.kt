package com.enamind.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.enamind.ai.core.AppState
import com.enamind.ai.core.Settings
import com.enamind.ai.ui.AiChatScreen
import com.enamind.ai.ui.DynamicMarketGlowPanel
import com.enamind.ai.ui.EnaMindTheme
import com.enamind.ai.ui.MarketMonitor
import com.enamind.ai.ui.OnboardingScreen
import com.enamind.ai.ui.OrderFlowScreen
import com.enamind.ai.ui.RadarSentinelScreen
import com.enamind.ai.ui.SettingsScreen
import com.enamind.ai.ui.TradeBotScreen
import kotlinx.coroutines.delay

/**
 * ENA:
 *   0 ORDER FLOW — анализ микроструктуры, Footprint, DOM, поглощения и уровней бота;
 *   1 ИИ ЧАТ     — изолированный универсальный ИИ-чат с прикреплением фото и файлов;
 *   2 БОТ        — торговый бот ENA (Paper + Bybit Demo / Testnet / Mainnet + плавающий HUD);
 *   3 РЫНОК      — живые данные (Bybit, Binance, OKX, Bitget, Gate, KuCoin), стакан и все контракты;
 *   4 РАДАР      — Chain Radar, Sentinel, Календарь событий (15 дней), «Разум и боты» (6ч/24ч);
 *   5 НАСТРОЙКИ  — тема, API-ключи, Память (экспорт JSON/CSV) и реестр сбоев.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = EnaApp.get().state
        state.start()
        setContent {
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> state.botOverlayManager.setAppForeground(true)
                        Lifecycle.Event.ON_STOP -> state.botOverlayManager.setAppForeground(false)
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            var themeSetting by remember { mutableStateOf(state.settings.theme) }
            var settingsTab by remember { mutableIntStateOf(0) }
            var onboardingDone by remember { mutableStateOf(state.settings.onboardingDone) }
            val toast by state.toast.collectAsState()

            LaunchedEffect(toast) {
                if (toast != null) {
                    delay(2500)
                    state.toast(null)
                }
            }

            EnaMindTheme(themeSetting) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                        if (!onboardingDone) {
                            OnboardingScreen(state) { onboardingDone = true }
                        } else {
                            MainScaffold(
                                state = state,
                                toast = toast,
                                onThemeChange = { themeSetting = it },
                                settingsTab = settingsTab,
                                onOpenSettingsTab = { settingsTab = it }
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class TabSpec(
    val label: String,
    val compactLabel: String,
    val weight: Float,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@Composable
private fun MainScaffold(
    state: AppState,
    toast: String?,
    onThemeChange: (Settings.Theme) -> Unit,
    settingsTab: Int,
    onOpenSettingsTab: (Int) -> Unit
) {
    // 0 ORDER FLOW | 1 ИИ ЧАТ | 2 БОТ | 3 РЫНОК | 4 РАДАР | 5 НАСТРОЙКИ
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = tab != 0) {
        settingsOpen = false
        tab = 0
    }

    LaunchedEffect(settingsTab, settingsOpen) {
        if (settingsOpen) tab = 5
    }

    val tabs = listOf(
        TabSpec("ORDER FLOW", "ORDER FLOW", 1.38f, Icons.Filled.Timeline, Icons.Outlined.Timeline),
        TabSpec("ИИ ЧАТ", "ИИ ЧАТ", 0.94f, Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
        TabSpec("БОТ", "БОТ", 0.86f, Icons.Filled.SmartToy, Icons.Outlined.SmartToy),
        TabSpec("РЫНОК", "РЫНОК", 0.92f, Icons.Filled.BarChart, Icons.Outlined.BarChart),
        TabSpec("РАДАР", "РАДАР", 0.92f, Icons.Filled.Radar, Icons.Outlined.Radar),
        TabSpec("НАСТРОЙКИ", "НАСТР.", 0.98f, Icons.Filled.Settings, Icons.Outlined.Settings)
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (tab) {
                    0 -> OrderFlowScreen(state = state)
                    1 -> AiChatScreen(
                        state = state,
                        onOpenAiSettings = {
                            onOpenSettingsTab(1)
                            settingsOpen = true
                            tab = 5
                        }
                    )
                    2 -> TradeBotScreen(state = state)
                    3 -> MarketMonitor(state = state)
                    4 -> RadarSentinelScreen(state = state)
                    else -> SettingsScreen(
                        state = state,
                        onThemeChange = onThemeChange,
                        onClose = { settingsOpen = false; tab = 0 },
                        initialTab = if (settingsOpen) settingsTab else 0
                    )
                }
            }

            // Нижняя панель навигации внутри Dynamic Market Glow (адаптивно под 360dp, без обрезания ORDER FLOW)
            DynamicMarketGlowPanel(state = state) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val isCompactWidth = maxWidth < 380.dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        tabs.forEachIndexed { i, t ->
                            val isSelected = tab == i
                            val itemColor = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val interaction = remember { MutableInteractionSource() }
                            Column(
                                modifier = Modifier
                                    .weight(t.weight)
                                    .widthIn(min = 48.dp)
                                    .height(58.dp)
                                    .minimumInteractiveComponentSize()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(
                                        interactionSource = interaction,
                                        indication = ripple(bounded = true),
                                        onClick = { settingsOpen = false; tab = i }
                                    )
                                    .semantics {
                                        selected = isSelected
                                        role = Role.Tab
                                    }
                                    .testTag("nav_tab_$i"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                                            else Color.Transparent
                                        )
                                        .padding(horizontal = 10.dp, vertical = 3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) t.selectedIcon else t.unselectedIcon,
                                        contentDescription = t.label,
                                        tint = itemColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (isCompactWidth) t.compactLabel else t.label,
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = itemColor,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip
                                )
                            }
                        }
                    }
                }
            }
        }

        toast?.let { msg ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 124.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 4.dp
            ) {
                Text(
                    msg,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

