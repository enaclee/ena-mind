package com.enamind.ai.ai

import com.enamind.ai.core.FaultMonitor
import com.enamind.ai.core.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Изолированный универсальный ИИ-клиент ENA:
 *  - Google AI Studio (Gemini 3.5 Flash-Lite / актуальные модели через v1beta API + загрузка списка моделей по ключу);
 *  - OpenAI-совместимый API (OpenAI, OpenRouter, DeepSeek, Groq, Together и любые совместимые endpoint'ы).
 *
 * Никак не вмешивается в расчёты рынка, Order Flow и ботов ENA.
 * Секретные ключи никогда не пишутся в логи и ошибки.
 */
object AiStudioClient {

    data class ModelPreset(
        val id: String,
        val title: String,
        val badge: String,
        val description: String
    )

    val FREE_MODELS = listOf(
        ModelPreset(
            id = "gemini-3.5-flash-lite",
            title = "Gemini 3.5 Flash-Lite",
            badge = "Рекомендуется · Быстрая",
            description = "Актуальная сверхбыстрая модель Google AI Studio по умолчанию с высоким запасом запросов."
        ),
        ModelPreset(
            id = "gemini-3.5-flash",
            title = "Gemini 3.5 Flash",
            badge = "Умная · Сбалансированная",
            description = "Актуальная мультизадачная модель для развёрнутых аналитических ответов и диалогов."
        ),
        ModelPreset(
            id = "gemini-3.5-pro",
            title = "Gemini 3.5 Pro",
            badge = "Максимальное качество",
            description = "Глубокое рассуждение по сложным вопросам, если доступно на вашем тарифе AI Studio."
        )
    )

    data class ChatTurn(
        val fromUser: Boolean,
        val text: String
    )

    data class AiReply(
        val ok: Boolean,
        val text: String,
        val latencyMs: Long = 0L,
        val error: String? = null
    )

    data class AvailableModel(
        val id: String,
        val displayName: String
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun sendChat(
        apiKey: String,
        modelId: String,
        history: List<ChatTurn>,
        userPrompt: String,
        systemInstruction: String = DEFAULT_SYSTEM_PROMPT,
        provider: String = Settings.PROVIDER_GEMINI,
        openAiBaseUrl: String = Settings.DEFAULT_OPENAI_BASE_URL,
        openAiKey: String = "",
        openAiModel: String = Settings.DEFAULT_OPENAI_MODEL
    ): AiReply {
        return if (provider == Settings.PROVIDER_OPENAI_COMPAT) {
            sendOpenAiCompatChat(
                baseUrl = openAiBaseUrl,
                apiKey = openAiKey.ifBlank { apiKey },
                modelId = openAiModel.ifBlank { modelId },
                history = history,
                userPrompt = userPrompt,
                systemInstruction = systemInstruction
            )
        } else {
            sendGeminiChat(
                apiKey = apiKey,
                modelId = modelId,
                history = history,
                userPrompt = userPrompt,
                systemInstruction = systemInstruction
            )
        }
    }

    private fun sendGeminiChat(
        apiKey: String,
        modelId: String,
        history: List<ChatTurn>,
        userPrompt: String,
        systemInstruction: String
    ): AiReply {
        val cleanKey = apiKey.trim()
        val rawModel = modelId.trim()
        val cleanModel = if (rawModel.isBlank() || Settings.isDeprecatedGeminiModel(rawModel)) {
            Settings.DEFAULT_AI_MODEL
        } else {
            rawModel
        }
        if (cleanKey.isBlank()) {
            return AiReply(
                ok = false,
                text = "",
                error = "Ключ Google AI Studio не указан. Откройте Настройки → API и вставьте ключ."
            )
        }

        val t0 = System.currentTimeMillis()
        val contents = JSONArray()
        history.takeLast(14).forEach { turn ->
            if (turn.text.isNotBlank()) {
                contents.put(
                    JSONObject()
                        .put("role", if (turn.fromUser) "user" else "model")
                        .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
                )
            }
        }
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
        )

        val payload = JSONObject().apply {
            if (systemInstruction.isNotBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                )
            }
            put("contents", contents)
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.7)
                    .put("maxOutputTokens", 2048)
            )
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$cleanKey"
        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val apiMsg = sanitizeError(
                        runCatching {
                            JSONObject(raw).optJSONObject("error")?.optString("message")
                        }.getOrNull().orEmpty(),
                        cleanKey
                    )
                    val readable = when (resp.code) {
                        400 -> "Некорректный запрос или имя модели ($cleanModel): $apiMsg"
                        401, 403 -> "Неверный или ограниченный API-ключ AI Studio ($apiMsg)"
                        404 -> "Модель '$cleanModel' не найдена в Google AI Studio ($apiMsg)"
                        429 -> "Превышен лимит запросов Google AI Studio (HTTP 429). Попробуйте позже."
                        else -> "HTTP ${resp.code}: ${apiMsg.ifBlank { sanitizeError(raw.take(180), cleanKey) }}"
                    }
                    FaultMonitor.recordFault(
                        component = "AI Studio ($cleanModel)",
                        kind = FaultMonitor.Kind.NETWORK,
                        severity = FaultMonitor.Severity.WARNING,
                        message = readable,
                        recovery = "Изолированный чат; расчёты ENA не затронуты"
                    )
                    return AiReply(ok = false, text = "", latencyMs = ms, error = readable)
                }

                val root = JSONObject(raw)
                val candidates = root.optJSONArray("candidates")
                val parts = candidates?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                val sb = StringBuilder()
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val t = parts.optJSONObject(i)?.optString("text").orEmpty()
                        if (t.isNotBlank()) {
                            if (sb.isNotEmpty()) sb.append("\n")
                            sb.append(t)
                        }
                    }
                }
                val answer = sb.toString().trim()
                if (answer.isBlank()) {
                    return AiReply(ok = false, text = "", latencyMs = ms, error = "Модель вернула пустой ответ.")
                }
                FaultMonitor.markHealthy("AI Studio ($cleanModel)")
                AiReply(ok = true, text = answer, latencyMs = ms)
            }
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t0
            val safeMsg = sanitizeError(e.message ?: "таймаут", cleanKey)
            val err = "Ошибка сети при обращении к AI Studio: $safeMsg"
            FaultMonitor.recordFault(
                component = "AI Studio ($cleanModel)",
                kind = FaultMonitor.Kind.NETWORK,
                severity = FaultMonitor.Severity.WARNING,
                message = err,
                recovery = "Изолированный чат; расчёты ENA не затронуты"
            )
            AiReply(ok = false, text = "", latencyMs = ms, error = err)
        }
    }

    private fun sendOpenAiCompatChat(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        history: List<ChatTurn>,
        userPrompt: String,
        systemInstruction: String
    ): AiReply {
        val cleanKey = apiKey.trim()
        val cleanBase = baseUrl.trim().trimEnd('/').ifBlank { Settings.DEFAULT_OPENAI_BASE_URL }
        val cleanModel = modelId.trim().ifBlank { Settings.DEFAULT_OPENAI_MODEL }

        if (cleanKey.isBlank()) {
            return AiReply(
                ok = false,
                text = "",
                error = "API-ключ OpenAI-совместимого провайдера не указан. Откройте Настройки → API."
            )
        }
        if (!cleanBase.startsWith("http://") && !cleanBase.startsWith("https://")) {
            return AiReply(
                ok = false,
                text = "",
                error = "Укажите корректный Base URL (https://...) для OpenAI-совместимого API."
            )
        }

        val t0 = System.currentTimeMillis()
        val messages = JSONArray()
        if (systemInstruction.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemInstruction))
        }
        history.takeLast(14).forEach { turn ->
            if (turn.text.isNotBlank()) {
                messages.put(
                    JSONObject()
                        .put("role", if (turn.fromUser) "user" else "assistant")
                        .put("content", turn.text)
                )
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", userPrompt))

        val payload = JSONObject()
            .put("model", cleanModel)
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", 2048)

        val endpoint = if (cleanBase.endsWith("/chat/completions")) cleanBase else "$cleanBase/chat/completions"
        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $cleanKey")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val apiMsg = sanitizeError(
                        runCatching {
                            JSONObject(raw).optJSONObject("error")?.optString("message")
                        }.getOrNull().orEmpty(),
                        cleanKey
                    )
                    val readable = "HTTP ${resp.code} ($cleanModel): ${apiMsg.ifBlank { sanitizeError(raw.take(180), cleanKey) }}"
                    FaultMonitor.recordFault(
                        component = "AI ($cleanModel)",
                        kind = FaultMonitor.Kind.NETWORK,
                        severity = FaultMonitor.Severity.WARNING,
                        message = readable,
                        recovery = "Изолированный чат; расчёты ENA не затронуты"
                    )
                    return AiReply(ok = false, text = "", latencyMs = ms, error = readable)
                }

                val root = JSONObject(raw)
                val choice0 = root.optJSONArray("choices")?.optJSONObject(0)
                val answer = choice0?.optJSONObject("message")?.optString("content")
                    ?.trim()
                    .orEmpty()
                if (answer.isBlank()) {
                    return AiReply(ok = false, text = "", latencyMs = ms, error = "Модель вернула пустой ответ.")
                }
                FaultMonitor.markHealthy("AI ($cleanModel)")
                AiReply(ok = true, text = answer, latencyMs = ms)
            }
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t0
            val safeMsg = sanitizeError(e.message ?: "ошибка сети", cleanKey)
            val err = "Ошибка сети AI-провайдера: $safeMsg"
            AiReply(ok = false, text = "", latencyMs = ms, error = err)
        }
    }

    /**
     * Загрузка актуального списка поддерживающих generateContent моделей по ключу Google AI Studio.
     */
    fun fetchGeminiModels(apiKey: String): Pair<List<AvailableModel>, String?> {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return emptyList<AvailableModel>() to "Укажите ключ Google AI Studio"
        return try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=$cleanKey")
                .build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return emptyList<AvailableModel>() to "HTTP ${resp.code}: не удалось загрузить список моделей"
                }
                val models = JSONObject(raw).optJSONArray("models") ?: return emptyList<AvailableModel>() to null
                val out = ArrayList<AvailableModel>()
                for (i in 0 until models.length()) {
                    val m = models.optJSONObject(i) ?: continue
                    val fullName = m.optString("name") // e.g., models/gemini-3.5-flash-lite
                    val id = fullName.removePrefix("models/").trim()
                    if (!id.startsWith("gemini-") || Settings.isDeprecatedGeminiModel(id)) continue
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var supportsChat = false
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.optString(j) == "generateContent") supportsChat = true
                        }
                    }
                    if (supportsChat) {
                        out.add(AvailableModel(id = id, displayName = m.optString("displayName", id)))
                    }
                }
                out.sortedBy { it.id } to null
            }
        } catch (e: Exception) {
            emptyList<AvailableModel>() to sanitizeError(e.message ?: "ошибка сети", cleanKey)
        }
    }

    fun buildOpenAiPayloadForTest(
        modelId: String,
        history: List<ChatTurn>,
        userPrompt: String
    ): JSONObject {
        val messages = JSONArray()
        history.forEach { turn ->
            messages.put(JSONObject().put("role", if (turn.fromUser) "user" else "assistant").put("content", turn.text))
        }
        messages.put(JSONObject().put("role", "user").put("content", userPrompt))
        return JSONObject()
            .put("model", modelId)
            .put("messages", messages)
    }

    fun testConnection(
        apiKey: String,
        modelId: String,
        provider: String = Settings.PROVIDER_GEMINI,
        openAiBaseUrl: String = Settings.DEFAULT_OPENAI_BASE_URL,
        openAiKey: String = "",
        openAiModel: String = Settings.DEFAULT_OPENAI_MODEL
    ): AiReply {
        return sendChat(
            apiKey = apiKey,
            modelId = modelId,
            history = emptyList(),
            userPrompt = "Ответь одним коротким предложением на русском: связь с ENA установлена.",
            systemInstruction = "",
            provider = provider,
            openAiBaseUrl = openAiBaseUrl,
            openAiKey = openAiKey,
            openAiModel = openAiModel
        )
    }

    private fun sanitizeError(msg: String, secret: String): String {
        return if (secret.isNotBlank() && msg.contains(secret)) msg.replace(secret, "***") else msg
    }

    private const val DEFAULT_SYSTEM_PROMPT =
        "Ты — свободный интеллектуальный собеседник внутри аналитического терминала ENA. " +
            "Отвечай ясно, по делу и на языке пользователя (обычно на русском). " +
            "Ты полностью изолирован от торгового расчёта и не управляешь ордерами."
}
