package com.enamind.ai.ai

import com.enamind.ai.core.FaultMonitor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AiStudioClient (ТЗ п.5) — полностью изолированный клиент свободного ИИ-чата
 * через Google AI Studio (Gemini REST API).
 *
 * ВАЖНО: Никак не связан с квантовым движком, ботами или расчётами рынка ENA,
 * чтобы ИИ-собеседник не мог вмешиваться в прогнозы и метрики.
 * Пользователь вводит свой ключ и модель из Google AI Studio во вкладке Настройки.
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
            id = "gemini-2.5-flash-lite",
            title = "Gemini 2.5 Flash-Lite",
            badge = "Бесплатно · Max лимит",
            description = "Максимально быстрые ответы и самый высокий дневной запас токенов/запросов на бесплатном тарифе AI Studio."
        ),
        ModelPreset(
            id = "gemini-2.5-flash",
            title = "Gemini 2.5 Flash",
            badge = "Бесплатно · Умный + быстрый",
            description = "Оптимальный баланс скорости и глубокого рассуждения, полностью доступен на бесплатном тарифе AI Studio."
        ),
        ModelPreset(
            id = "gemini-2.0-flash",
            title = "Gemini 2.0 Flash",
            badge = "Бесплатно · Резерв",
            description = "Стабильная высокоскоростная модель с большим запасом запросов на день."
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
        systemInstruction: String = DEFAULT_SYSTEM_PROMPT
    ): AiReply {
        val cleanKey = apiKey.trim()
        val cleanModel = modelId.trim().ifBlank { FREE_MODELS.first().id }
        if (cleanKey.isBlank()) {
            return AiReply(
                ok = false,
                text = "",
                error = "Ключ Google AI Studio не указан. Откройте Настройки → API / AI Studio и вставьте ключ."
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
                    val apiMsg = runCatching {
                        JSONObject(raw).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty()
                    val readable = when (resp.code) {
                        400 -> "Некорректный запрос или имя модели ($cleanModel): $apiMsg"
                        401, 403 -> "Неверный или заблокированный API-ключ AI Studio ($apiMsg)"
                        404 -> "Модель '$cleanModel' не найдена в Google AI Studio ($apiMsg)"
                        429 -> "Превышен дневной или минутный лимит бесплатных токенов (HTTP 429). Попробуйте позже или переключитесь на gemini-2.5-flash-lite."
                        else -> "HTTP ${resp.code}: ${apiMsg.ifBlank { raw.take(180) }}"
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
            val err = "Ошибка сети при обращении к AI Studio: ${e.message ?: "таймаут"}"
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

    fun testConnection(apiKey: String, modelId: String): AiReply {
        return sendChat(
            apiKey = apiKey,
            modelId = modelId,
            history = emptyList(),
            userPrompt = "Ответь одним коротким предложением на русском: связь с ENA установлена.",
            systemInstruction = ""
        )
    }

    private const val DEFAULT_SYSTEM_PROMPT =
        "Ты — свободный интеллектуальный собеседник внутри приложения ENA. " +
            "Отвечай ясно, по делу и на языке пользователя (обычно на русском). " +
            "Ты не управляешь торговым движком и не имеешь доступа к ордерам."
}
