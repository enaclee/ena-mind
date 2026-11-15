package com.enamind.ai.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enamind.ai.core.AppState

/**
 * OnboardingScreen v8.2: первый запуск с честным объяснением SHADOW-режима
 * и осознанным включением опциональных уведомлений и фонового сервиса.
 */
@Composable
fun OnboardingScreen(state: AppState, onDone: () -> Unit) {
    var notifEnabled by remember { mutableStateOf(false) }
    var notifAsked by remember { mutableStateOf(false) }
    var bgEnabled by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifAsked = true
        notifEnabled = granted
        state.settings.alertsEnabled = granted
        if (!granted) {
            state.toast("уведомления выключены — анализ работает; включить можно в Настройках")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "ENA",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            ) {
                Text(
                    "SHADOW v9.0",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        Text(
            "Аналитика крипторынка · только чтение публичных потоков (без торговли и приватных ключей)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Section(
            title = "1 · Публичные потоки и мульти-биржи",
            body = "ENA получает свечи, стакан, агрессивную ленту, funding, OI и ончейн-метрики с Bybit V5, Binance Vision, OKX, Coinbase и Kraken. Если одна из площадок недоступна в регионе, движок автоматически переключается на резервный поток."
        )

        Section(
            title = "2 · Уведомления об алертах (по желанию)",
            body = "Если включите алерты, Android 13+ покажет системный запрос уведомлений. Отказ не влияет на чат, график и прогнозы."
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Алерты рынка", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = notifEnabled,
                    onCheckedChange = { want ->
                        if (want) {
                            if (Build.VERSION.SDK_INT >= 33 && !notifAsked) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                notifEnabled = true
                                state.settings.alertsEnabled = true
                            }
                        } else {
                            notifEnabled = false
                            state.settings.alertsEnabled = false
                        }
                    }
                )
            }
        }

        Section(
            title = "3 · Фоновое накопление памяти (по желанию)",
            body = "Непрерывный мониторинг работает через явный foreground-сервис с постоянным уведомлением. Вы можете включить или выключить его позже в Настройках."
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Фоновый движок", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = bgEnabled,
                    onCheckedChange = { on ->
                        bgEnabled = on
                        state.settings.bgEngine = on
                        state.engine.setBackgroundMode(on)
                    }
                )
            }
        }

        Section(
            title = "4 · Xiaomi HyperOS / MIUI (опционально)",
            body = "Для стабильной фоновой работы можно вручную отключить ограничение батареи в настройках системы."
        )

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                state.settings.onboardingDone = true
                onDone()
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Начать анализ", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = {
                state.settings.onboardingDone = true
                onDone()
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Пропустить (без алертов и фона)", fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Section(title: String, body: String, extra: (@Composable () -> Unit)? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )
            extra?.let {
                Spacer(Modifier.height(10.dp))
                it()
            }
        }
    }
}
