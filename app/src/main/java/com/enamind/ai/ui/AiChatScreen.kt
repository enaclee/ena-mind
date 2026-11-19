package com.enamind.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enamind.ai.ai.AiStudioClient
import com.enamind.ai.core.AppState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Изолированный свободный ИИ-чат Gemini + OpenAI-совместимые провайдеры.
 *
 * НЕ имеет доступа к NeuralEngine, TrendReasoningEngine, Order Flow или ботам ENA.
 * Поддерживает чистый текстовый ввод и прикрепление фото/файлов для мультимодальных моделей.
 */
@Composable
fun AiChatScreen(
    state: AppState,
    onOpenAiSettings: () -> Unit
) {
    val messages by state.aiChatMessages.collectAsState()
    val busy by state.aiChatBusy.collectAsState()
    val isOpenAi = state.settings.aiProvider == com.enamind.ai.core.Settings.PROVIDER_OPENAI_COMPAT
    val hasKey = if (isOpenAi) state.settings.aiOpenAiApiKey.isNotBlank() else state.settings.aiStudioApiKey.isNotBlank()
    val activeModel = if (isOpenAi) state.settings.aiOpenAiModel else state.settings.aiStudioModel
    val providerTitle = if (isOpenAi) "OpenAI API" else "Gemini"
    var inputText by remember { mutableStateOf("") }
    var attachMenuOpen by remember { mutableStateOf(false) }
    val pendingAttachments = remember { mutableStateListOf<AiStudioClient.ChatAttachment>() }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val uiScope = rememberCoroutineScope()
    val amberText = enaAmberText()
    val greenText = enaGreenText()

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            uiScope.launch {
                val att = state.loadChatAttachment(uri)
                if (att != null) {
                    if (pendingAttachments.size >= 5) {
                        state.toast("Максимум 5 вложений в одном сообщении")
                    } else {
                        pendingAttachments.add(att)
                    }
                }
            }
        }
    }

    val pickDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            uiScope.launch {
                val att = state.loadChatAttachment(uri)
                if (att != null) {
                    if (pendingAttachments.size >= 5) {
                        state.toast("Максимум 5 вложений в одном сообщении")
                    } else {
                        pendingAttachments.add(att)
                    }
                }
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    fun submitMessage() {
        val txt = inputText.trim()
        if ((txt.isNotEmpty() || pendingAttachments.isNotEmpty()) && !busy) {
            val atts = pendingAttachments.toList()
            inputText = ""
            pendingAttachments.clear()
            state.sendAiChatMessage(txt, atts)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("ai_chat_screen")
    ) {
        // Лента сообщений: weight(1f) без внешнего SelectionContainer, чтобы панель ввода никогда не выдавливалась
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { m ->
                AiMessageBubble(
                    m = m,
                    onCopy = {
                        clipboard.setText(AnnotatedString(m.text))
                        state.toast("Скопировано")
                    }
                )
            }
        }

        // Поле ввода, прикрепление фото/файлов и отправка
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ai_chat_input_bar"),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (pendingAttachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        pendingAttachments.forEach { att ->
                            InputChip(
                                selected = true,
                                onClick = { pendingAttachments.removeAll { it.id == att.id } },
                                label = {
                                    Text(
                                        att.displayName,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 150.dp)
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (att.isImage) Icons.Outlined.Image else Icons.Outlined.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Удалить вложение",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        IconButton(
                            onClick = { attachMenuOpen = true },
                            enabled = !busy,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("ai_chat_attach")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AttachFile,
                                contentDescription = "Прикрепить фото или файл",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = attachMenuOpen,
                            onDismissRequest = { attachMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Фото / скриншот") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Image, contentDescription = null)
                                },
                                onClick = {
                                    attachMenuOpen = false
                                    pickImageLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Файл (TXT, JSON, CSV, код, PDF)") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Description, contentDescription = null)
                                },
                                onClick = {
                                    attachMenuOpen = false
                                    pickDocLauncher.launch(
                                        arrayOf(
                                            "text/*",
                                            "application/json",
                                            "application/pdf",
                                            "text/csv",
                                            "*/*"
                                        )
                                    )
                                }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ai_chat_input"),
                        placeholder = {
                            Text(
                                "Сообщение для ИИ…",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                            )
                        },
                        singleLine = false,
                        maxLines = 4,
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submitMessage() })
                    )

                    Spacer(Modifier.width(6.dp))

                    IconButton(
                        onClick = { submitMessage() },
                        enabled = (inputText.isNotBlank() || pendingAttachments.isNotEmpty()) && !busy,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("ai_chat_send"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Отправить в ИИ-чат",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiMessageBubble(
    m: AppState.AiChatMessage,
    onCopy: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (m.fromUser) 48.dp else 0.dp,
                end = if (m.fromUser) 0.dp else 32.dp
            ),
        contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 340.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (m.fromUser) 16.dp else 4.dp,
                bottomEnd = if (m.fromUser) 4.dp else 16.dp
            ),
            color = when {
                m.fromUser -> MaterialTheme.colorScheme.primaryContainer
                m.error -> Color.EnaRed.copy(alpha = 0.12f)
                else -> MaterialTheme.colorScheme.surface
            },
            border = when {
                m.fromUser -> null
                m.error -> BorderStroke(0.8.dp, Color.EnaRed.copy(alpha = 0.5f))
                else -> BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
            }
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (!m.fromUser && m.modelId.isNotBlank()) {
                    Text(
                        text = m.modelId,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = if (m.error) Color.EnaRed else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (m.attachmentLabels.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        m.attachmentLabels.forEach { lbl ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.AttachFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        lbl,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
                if (m.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            m.text,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    SelectionContainer {
                        Text(
                            text = m.text,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = if (m.fromUser) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    val timePart = aiTimeShort(m.ts)
                    val latencyPart = if (!m.fromUser && m.latencyMs > 0L) " · ${m.latencyMs}мс" else ""
                    Text(
                        text = "$timePart$latencyPart",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier.weight(1f)
                    )
                    if (!m.busy && m.text.isNotBlank()) {
                        IconButton(
                            onClick = onCopy,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("ai_copy_msg_${m.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Копировать текст",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun aiTimeShort(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
