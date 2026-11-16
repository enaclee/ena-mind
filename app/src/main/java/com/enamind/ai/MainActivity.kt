package com.enamind.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.delay

/**
 * ENA:
 *   ORDER FLOW — анализ микроструктуры, Footprint, DOM, поглощения и атак уровней;
 *   ИИ ЧАТ     — изолированный универсальный ИИ-чат (Google AI Studio + OpenAI-совместимые ключи);
 *   РЫНОК      — живые данные (Bybit, Binance, OKX, Bitget, Gate, KuCoin), стакан, поток и все контракты;
 *   РАДАР      — Chain Radar, Sentinel, Календарь событий (15 дней), «Разум и боты» (6ч/24ч прогнозы);
 *   НАСТРОЙКИ  — тема (Ночная / Светлая), API-ключи, Память (экспорт JSON/CSV) и реестр сбоев.
 * Нижняя панель навигации оформлена с Dynamic Market Glow в реальном времени.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = EnaApp.get().state
        state.start()
        setContent {
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
                    // statusBarsPadding здесь; navigationBarsPadding внутри DynamicMarketGlowPanel и OnboardingScreen
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
    // 0 ORDER FLOW | 1 ИИ ЧАТ | 2 РЫНОК | 3 РАДАР | 4 НАСТРОЙКИ
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = tab != 0) {
        settingsOpen = false
        tab = 0
    }

    LaunchedEffect(settingsTab, settingsOpen) {
        if (settingsOpen) tab = 4
    }

    val tabs = listOf(
        TabSpec("ORDER FLOW", Icons.Filled.Timeline, Icons.Outlined.Timeline),
        TabSpec("ИИ ЧАТ", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
        TabSpec("РЫНОК", Icons.Filled.BarChart, Icons.Outlined.BarChart),
        TabSpec("РАДАР", Icons.Filled.Radar, Icons.Outlined.Radar),
        TabSpec("НАСТРОЙКИ", Icons.Filled.Settings, Icons.Outlined.Settings)
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
                            tab = 4
                        }
                    )
                    2 -> MarketMonitor(state = state)
                    3 -> RadarSentinelScreen(state = state)
                    else -> SettingsScreen(
                        state = state,
                        onThemeChange = onThemeChange,
                        onClose = { settingsOpen = false; tab = 0 },
                        initialTab = if (settingsOpen) settingsTab else 0
                    )
                }
            }

            // Нижняя панель навигации внутри Dynamic Market Glow (зелёный / красный / нейтральный, ping-pong)
            DynamicMarketGlowPanel(state = state) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    tabs.forEachIndexed { i, t ->
                        val isSelected = tab == i
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { settingsOpen = false; tab = i },
                            modifier = Modifier.testTag("nav_tab_$i"),
                            icon = {
                                Icon(
                                    imageVector = if (isSelected) t.selectedIcon else t.unselectedIcon,
                                    contentDescription = t.label
                                )
                            },
                            label = {
                                Text(
                                    text = t.label,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                            )
                        )
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
