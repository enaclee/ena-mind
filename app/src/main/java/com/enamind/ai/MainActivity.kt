package com.enamind.ai

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.enamind.ai.ui.EnacoreScreen
import com.enamind.ai.ui.ContractPickerDialog
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

            val appearanceRevision by state.settings.appearanceRevision.collectAsState()
            val appliedTheme = remember(appearanceRevision) { state.settings.theme }
            EnaMindTheme(appliedTheme, state.settings.accentColor, state.settings.rgbDarkBase) {
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


@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MainScaffold(state: AppState, toast: String?, onThemeChange: (Settings.Theme) -> Unit,
                         settingsTab: Int, onOpenSettingsTab: (Int) -> Unit) {
    val labels = listOf("ORDER FLOW", "ENΛCORE", "ИИ ЧАТ", "БОТ", "РЫНОК", "РАДАР", "НАСТРОЙКИ")
    val icons = listOf(Icons.Outlined.Timeline, Icons.Outlined.AutoAwesome, Icons.Outlined.AutoAwesome,
        Icons.Outlined.SmartToy, Icons.Outlined.BarChart, Icons.Outlined.Radar, Icons.Outlined.Settings)
    val pager = rememberPagerState(pageCount = { labels.size })
    val nav = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val instrument by state.activeInstrument.collectAsState()
    val ime = WindowInsets.isImeVisible
    var picker by remember { mutableStateOf(false) }
    BackHandler(pager.currentPage != 0) { scope.launch { pager.animateScrollToPage(0) } }
    LaunchedEffect(pager.currentPage) {
        nav.animateScrollToItem(pager.currentPage)
        val info = nav.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == pager.currentPage }
        if (item != null) nav.animateScrollToItem(pager.currentPage,
            -((info.viewportEndOffset - info.viewportStartOffset - item.size) / 2).coerceAtLeast(0))
    }
    if (picker) ContractPickerDialog(state, instrument.symbol, {}, { picker = false })
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            if (!ime) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("ENΛVERSE", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = { picker = true }) {
                    Text("${instrument.symbol} · ${instrument.category}", fontSize = 11.sp)
                }
            }
            HorizontalPager(state = pager, beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("main_pager")) { page ->
                when (page) {
                    0 -> OrderFlowScreen(state)
                    1 -> EnacoreScreen(state)
                    2 -> AiChatScreen(state, onOpenAiSettings = { scope.launch { pager.animateScrollToPage(6) } })
                    3 -> TradeBotScreen(state)
                    4 -> MarketMonitor(state)
                    5 -> RadarSentinelScreen(state)
                    6 -> SettingsScreen(state, onThemeChange = onThemeChange, onClose = { scope.launch { pager.animateScrollToPage(0) } }, initialTab = settingsTab)
                }
            }
            if (!ime) DynamicMarketGlowPanel(state) {
                LazyRow(state = nav, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(labels) { index, label ->
                        val chosen = pager.currentPage == index
                        Surface(shape = RoundedCornerShape(16.dp), color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                            Column(Modifier.widthIn(min = 84.dp).heightIn(min = 60.dp)
                                .clickable { scope.launch { pager.animateScrollToPage(index) } }
                                .semantics { selected = chosen; role = Role.Tab }
                                .testTag("nav_tab_$index").padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(icons[index], contentDescription = null, modifier = Modifier.size(21.dp),
                                    tint = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        toast?.let { Surface(Modifier.align(Alignment.BottomCenter).padding(20.dp, 100.dp),
            shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.inverseSurface) {
            Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
        } }
    }
}
