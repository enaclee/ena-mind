package com.enamind.ai.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enamind.ai.core.AppState

/**
 * Первый запуск ENA:
 *  - Шаг 0: обязательный дисклеймер ATTENTION; кнопка Confirm активируется после прочтения.
 *  - Шаг 1: выдача разрешений (уведомления, фон, переход в системные настройки) и Отказ от ответственности.
 */
@Composable
fun OnboardingScreen(state: AppState, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var notifEnabled by remember { mutableStateOf(state.settings.alertsEnabled) }
    var notifAsked by remember { mutableStateOf(false) }
    var bgEnabled by remember { mutableStateOf(state.settings.bgEngine) }

    BackHandler(enabled = step == 1) {
        step = 0
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifAsked = true
        notifEnabled = granted
        state.settings.alertsEnabled = granted
        if (!granted) {
            state.toast("Уведомления выключены — анализ работает; включить можно в системных настройках")
        }
    }

    if (step == 0) {
        val scrollState = rememberScrollState()
        val reachedBottom by remember {
            derivedStateOf {
                !scrollState.canScrollForward ||
                    scrollState.value >= (scrollState.maxValue - 24).coerceAtLeast(0)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = "Важное предупреждение",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Text(
                    text = "ATTENTION",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "Пожалуйста, внимательно прочитайте перед началом работы с ENA",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "ENA может быть не для вас, если:",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val points = listOf(
                        "1. Вы испытываете эмоциональную нестабильность.",
                        "2. Склонны к азартным играм или зависимому поведению.",
                        "3. Испытываете трудности с самоконтролем и дисциплиной.",
                        "4. Воспринимаете трейдинг как игру или способ быстро заработать."
                    )
                    points.forEach { item ->
                        Text(
                            text = item,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    Text(
                        text = "ENA — инструмент для изучения рынка, анализа трендов и понимания движения цен, а не способ гарантированного заработка.",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "Трейдинг сопряжён с финансовыми рисками. Используйте приложение ответственно и не торгуйте средствами, которые не можете позволить себе потерять.",
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            if (!reachedBottom) {
                Text(
                    text = "Пролистайте текст до конца, чтобы активировать кнопку подтверждения",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
            }

            Button(
                onClick = { step = 1 },
                enabled = reachedBottom,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("onboarding_confirm_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Confirm", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "ENΛVERSE · Разрешения и ответственность",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Настройте системные разрешения и подтвердите понимание принципов работы инструмента",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OnboardingSection(
                title = "• Разрешения для приложения",
                body = "Рекомендуется самостоятельно перейти в системные настройки Android и выдать приложению все необходимые разрешения. Приложение не собирает и не передаёт ваши личные данные."
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Notifications,
                        contentDescription = "Уведомления",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Уведомления об алертах рынка",
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = notifEnabled,
                        modifier = Modifier.testTag("onboarding_notif_switch"),
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

                Spacer(Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = "Фоновый анализ",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Непрерывная работа в фоне (foreground-сервис)",
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = bgEnabled,
                        modifier = Modifier.testTag("onboarding_bg_switch"),
                        onCheckedChange = { on ->
                            bgEnabled = on
                            state.settings.bgEngine = on
                            state.engine.setBackgroundMode(on)
                        }
                    )
                }

                Spacer(Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", ctx.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { ctx.startActivity(intent) }
                            .onFailure {
                                state.toast("Откройте Настройки → Приложения → ENA вручную")
                            }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("onboarding_open_system_settings_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = "Открыть системные настройки",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Открыть системные настройки приложения",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            OnboardingSection(
                title = "• Отказ от ответственности",
                body = "ENA — это аналитический и образовательный инструмент для наблюдения за стаканом, лентой сделок, поглощением ликвидности и структурой рынка, а не источник гарантированного заработка денег.\n\n" +
                    "Ни один алгоритм не даёт 100% гарантии результата. Финансовый итог всегда зависит от вашего опыта, системного обучения, глубокого понимания механики рынка, управления рисками и личной торговой дисциплины."
            )

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = {
                    state.settings.onboardingDone = true
                    onDone()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("onboarding_start_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Начать анализ", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = {
                    state.settings.alertsEnabled = false
                    state.settings.bgEngine = false
                    state.engine.setBackgroundMode(false)
                    state.settings.onboardingDone = true
                    onDone()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("onboarding_skip_alerts_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Продолжить без алертов", fontSize = 13.sp)
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun OnboardingSection(
    title: String,
    body: String,
    extra: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            extra?.let {
                Spacer(Modifier.height(12.dp))
                it()
            }
        }
    }
}
