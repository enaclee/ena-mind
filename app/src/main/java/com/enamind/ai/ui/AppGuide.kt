package com.enamind.ai.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.enamind.ai.core.AppState
import com.enamind.ai.core.AppNotifications

@Composable
fun NotificationSettings(state: AppState) {
    val ctx = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending?.let { state.settings.setNotificationEnabled(it, granted) }; pending = null; revision++
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Уведомления", style = MaterialTheme.typography.titleLarge)
        AppNotifications.CHANNELS.forEach { (id, title) ->
            val enabled = remember(revision) { state.settings.notificationEnabled(id) }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, Modifier.weight(1f))
                Switch(enabled, { on ->
                    if (on && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        pending = id; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else { state.settings.setNotificationEnabled(id, on); revision++ }
                })
            }
        }
        Text("Сигналы отправляются только при смене состояния. Постоянный статус работающего фонового сервиса регулируется Android.")
        OutlinedButton(onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }) { Text("Настройки уведомлений Android") }
    }
}

val APP_GUIDE = linkedMapOf(
    "ORDER FLOW" to "Свечи, Footprint, стакан, поглощения и борьба за уровни. Зелёный контур означает давление покупателей, красный — продавцов. Серая подсветка означает отсутствие подтверждения или свежих данных. Источники: Bybit и доступные сопоставимые потоки Binance/OKX.",
    "ENΛCORE" to "Независимое Python-ядро получает свечи, сделки и стакан от Kotlin. 12 агентов измеряют поток, ликвидность, тренд и межбиржевой контекст. Матрица 5×4 показывает историческую частоту достижения Net-целей в пределах 15–120 минут. Малые выборки отмечены звёздочкой. AWO проверяет свежесть, спред и историческую поддержку; VETO запрещает новый вход.",
    "ИИ ЧАТ" to "Свободный диалог с фото и документами. Провайдер, модель и ключ выбираются в Настройках → API. ИИ-чат не управляет торговыми расчётами и не отправляет ордера.",
    "БОТ" to "В ручном режиме использует заданные Delta, OFI, спред, фильтры Order Flow и Радара. При включённом мосте ENΛCORE получает разрешение AWO, направление, Net-цель и горизонт. Paper имитирует исполнение; Demo, Testnet и Mainnet используют отдельные ключи. Реальное исполнение текущего клиента ограничено Bybit Linear; прочие категории доступны для анализа. Проверьте комиссии, TP/SL, трал, транши и поведение при таймауте. Возможны убытки и проскальзывание.",
    "РЫНОК" to "Поиск по доступному каталогу биржи, категории Все / Крипто / Металлы и RWA / Спот / Инверсные / Избранное. Выбор меняет инструмент во всех блоках и очищает прежний поток. Сводка сравнивает доступные котировки Bybit, Binance, OKX, Bitget, Gate и KuCoin; цена на другой площадке не гарантирует доступность исполнения здесь.",
    "РАДАР" to "Python-аналитика и Kotlin-сенсоры дают контекст волатильности, ликвидности, сети и календаря на 15 дней. Радар не заменяет проверку свежести реального стакана и не гарантирует прогноз.",
    "НАСТРОЙКИ" to "Ночная, светлая и RGB-тема; API и три слота Bybit; экспорт памяти JSON/CSV; журнал сбоев; уведомления и справочник. Плавающие окна добавляются в ENΛCORE: перемещение за шапку, изменение размера за угол, поворот и прозрачность через кнопку настройки. Android может остановить фон по системному лимиту; параметры окон сохраняются."
)

const val PRIVACY_POLICY = """Политика конфиденциальности ENΛVERSE
Разработчик: enaclee. Контакт по данным: https://t.me/enaclee. Редакция: 18 ноября 2026 года.

Приложение не создаёт учётную запись на сервере разработчика и не отправляет ему аналитику использования или персональные данные. Настройки, рыночный кэш, сделки и журналы сбоев хранятся на устройстве. API-ключи сохраняются локально в зашифрованном хранилище с использованием Android Keystore; они используются только для выбранного сервиса.

Для получения рынка приложение напрямую обращается к публичным API бирж и источников новостей/макроданных. Провайдеры получают IP-адрес и технические параметры запроса. При запуске торговых функций Bybit получает подписанные запросы к вашему счёту. При отправке сообщения в ИИ-чат выбранный вами провайдер получает текст, историю диалога и только выбранные вами вложения. Эти организации обрабатывают данные по своим политикам; серверы могут находиться за пределами вашей страны. Не отправляйте чужие персональные данные без законного основания.

Разрешения: интернет — запросы к источникам; уведомления — выбранные события; отображение поверх приложений — плавающие окна; фоновый сервис и удержание процессора — поддержание активной аналитики/бота. Фото и документы выбираются системным средством выбора, без доступа ко всей медиатеке. Разрешения можно отозвать в Android.

Кэш автоматически очищается по правилам хранения приложения; настройки и сохранённая история остаются до их удаления. Очистить локальные данные можно в Android → Приложения → ENΛVERSE → Хранилище → Очистить данные, либо удалив приложение. Сначала остановите бота и проверьте открытые позиции на бирже. Экспортированные вами файлы необходимо удалить отдельно. Удаление данных приложения не отменяет ордера и не удаляет историю у биржи или ИИ-провайдера; обращайтесь к соответствующему сервису.

Вы можете запросить уточнение состава и целей обработки, исправление или удаление относящихся к вам данных через указанный контакт, в том числе при реализации прав по ФЗ №152-ФЗ. Разработчик не имеет доступа к локальным ключам и данным без вашего отдельного предоставления. Для публикации в магазине эта политика должна быть доступна также по публичному URL, а декларация Data safety — соответствовать фактической сборке и подключённым сервисам.
"""

@Composable
fun AppGuideScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Справочник ENΛVERSE 1.0", style = MaterialTheme.typography.titleLarge)
        APP_GUIDE.forEach { (name, text) -> Card { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(name, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(6.dp)); Text(text) } } }
        SettingsFooter()
    }
}

@Composable
fun SettingsFooter() {
    val ctx = LocalContext.current
    var privacy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text("© 2027 enaclee. All rights reserved. Made with the Aistudio tool.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/enaclee"))) }) { Text("↗ Telegram · @enaclee") }
        TextButton(onClick = { privacy = !privacy }) { Text("Политика конфиденциальности") }
        if (privacy) Text(PRIVACY_POLICY, style = MaterialTheme.typography.bodySmall)
    }
}
