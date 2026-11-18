package com.enamind.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun BotParameterLabel(label: String, explanation: String = botParameterExplanation(label)) {
    var open by remember { mutableStateOf(false) }
    Row {
        Text(label)
        TextButton(onClick = { open = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), contentPadding = PaddingValues(0.dp)) { Text("?") }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = { Text(explanation) },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Готово") } })
}

internal fun botParameterExplanation(label: String): String = when {
    label.contains("капитал", true) || label.contains("USDT") -> "Сумма маржи, выделенная боту. Объём позиции зависит от плеча. Учитываются доступный баланс, минимальный размер ордера и комиссия."
    label.contains("плеч", true) -> "Множитель позиции относительно маржи. Увеличивает прибыль и убыток, приближает ликвидацию. На споте без займа используется 1×."
    label.contains("марж", true) -> "Изолированная маржа ограничивает обеспечение позиции; кросс использует доступное обеспечение счёта. UTA и Classic имеют разные API-настройки."
    label.contains("транш", true) || label.contains("шаг", true) -> "Количество и веса последовательных входов. Добор разрешён только при сохранении направления, достаточном отступе и отсутствии встречного каскада ликвидаций."
    label.contains("Stop", true) || label.contains("SL") -> "Стоп ограничивает убыток. Процент зависит от выбранной базы: изменение цены, ROE или множитель ATR. Для реальной позиции защита отправляется на биржу."
    label.contains("трал", true) || label.contains("отступ", true) -> "После достижения минимальной чистой прибыли подтягивает защиту за максимумом. Отступ должен покрывать спред и рыночный шум."
    label.contains("прибыл", true) || label.contains("цель", true) || label.contains("Take", true) -> "Цель после расчётных комиссий. При мосте ENΛCORE цель и предельный горизонт фиксируются при входе; матрица использует движение цены, а не ROE."
    label.contains("Maker", true) || label.contains("исполн", true) -> "PostOnly размещает лимитный ордер без немедленного снятия ликвидности. Market исполняется по доступным ценам и может дать проскальзывание. Fallback определяет действие после таймаута Maker."
    label.contains("Android", true) || label.contains("тайм", true) -> "Android ограничивает суммарную фоновую работу dataSync. При системном таймауте приложение останавливает сервис и применяет выбранное поведение для позиции. Биржевой стоп работает независимо от приложения."
    label.contains("API", true) || label.contains("Key", true) || label.contains("Secret", true) -> "Ключи сохраняются локально в зашифрованном виде. Demo, Testnet и Mainnet имеют отдельные слоты. Не включайте право вывода средств."
    else -> "Параметр применяется к текущему инструменту. Проверьте результат в Paper перед использованием на бирже. Если включён ENΛCORE, разрешение на вход выдаётся только после проверки AWO."
}

@Composable
internal fun ManualThresholdField(label: String, value: Double, help: String, onValue: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(value = text, onValueChange = { text = it; it.replace(',', '.').toDoubleOrNull()?.takeIf { n -> n.isFinite() }?.let(onValue) },
        label = { BotParameterLabel(label, help) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
