package com.enamind.ai.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Настройки ENA. Секреты (ключи API / универсальные AI-ключи) хранятся
 * ТОЛЬКО здесь, в приватном хранилище приложения, зашифрованными через Keystore,
 * и НИКОГДА не попадают в экспортируемые файлы или открытые логи.
 */
class Settings(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("enamind", Context.MODE_PRIVATE)

    private fun getSecret(key: String): String {
        val v = sp.getString(key, "") ?: ""
        return if (v.startsWith("enc:")) CryptoBox.decrypt(v.removePrefix("enc:")) else v
    }

    private fun setSecret(key: String, value: String) {
        sp.edit().putString(key, if (value.isBlank()) "" else "enc:" + CryptoBox.encrypt(value)).apply()
    }

    var theme: Theme
        get() = if (sp.getString("theme", "black") == "white") Theme.WHITE else Theme.BLACK
        set(v) = sp.edit().putString("theme", if (v == Theme.WHITE) "white" else "black").apply()

    var newsApiKey: String
        get() = getSecret("news_api_key")
        set(v) = setSecret("news_api_key", v)

    var bybitBaseUrl: String
        get() = sp.getString("bybit_base_url", DEFAULT_BYBIT_URL) ?: DEFAULT_BYBIT_URL
        set(v) = sp.edit().putString("bybit_base_url", v).apply()

    var bybitApiKey: String
        get() = getSecret("bybit_api_key")
        set(v) = setSecret("bybit_api_key", v)

    var bybitApiSecret: String
        get() = getSecret("bybit_api_secret")
        set(v) = setSecret("bybit_api_secret", v)

    // ---------------- Универсальный AI-чат (Google AI Studio + OpenAI-совместимые) ----------------

    /** Выбранный провайдер: PROVIDER_GEMINI | PROVIDER_OPENAI_COMPAT. */
    var aiProvider: String
        get() = sp.getString("ai_provider", PROVIDER_GEMINI) ?: PROVIDER_GEMINI
        set(v) = sp.edit().putString(
            "ai_provider",
            if (v == PROVIDER_OPENAI_COMPAT) PROVIDER_OPENAI_COMPAT else PROVIDER_GEMINI
        ).apply()

    /** Ключ Google AI Studio (зашифрован Keystore). */
    var aiStudioApiKey: String
        get() = getSecret("ai_studio_api_key")
        set(v) = setSecret("ai_studio_api_key", v)

    /** Активная модель Google AI Studio; старые отключённые версии мигрируют на gemini-3.5-flash-lite. */
    var aiStudioModel: String
        get() {
            val raw = (sp.getString("ai_studio_model", DEFAULT_AI_MODEL) ?: DEFAULT_AI_MODEL).trim()
            return if (raw.isBlank() || isDeprecatedGeminiModel(raw)) DEFAULT_AI_MODEL else raw
        }
        set(v) {
            val clean = v.trim()
            val resolved = if (clean.isBlank() || isDeprecatedGeminiModel(clean)) DEFAULT_AI_MODEL else clean
            sp.edit().putString("ai_studio_model", resolved).apply()
        }

    /** Универсальный OpenAI-совместимый endpoint (напр., OpenAI, OpenRouter, Groq, DeepSeek, Together). */
    var aiOpenAiBaseUrl: String
        get() = sp.getString("ai_openai_base_url", DEFAULT_OPENAI_BASE_URL) ?: DEFAULT_OPENAI_BASE_URL
        set(v) = sp.edit().putString("ai_openai_base_url", v.trim().ifBlank { DEFAULT_OPENAI_BASE_URL }).apply()

    /** Ключ OpenAI-совместимого API (зашифрован Keystore). */
    var aiOpenAiApiKey: String
        get() = getSecret("ai_openai_api_key")
        set(v) = setSecret("ai_openai_api_key", v)

    /** ID модели для OpenAI-совместимого API. */
    var aiOpenAiModel: String
        get() = sp.getString("ai_openai_model", DEFAULT_OPENAI_MODEL) ?: DEFAULT_OPENAI_MODEL
        set(v) = sp.edit().putString("ai_openai_model", v.trim().ifBlank { DEFAULT_OPENAI_MODEL }).apply()

    var symbol: String
        get() = sp.getString("symbol", "BTCUSDT") ?: "BTCUSDT"
        set(v) = sp.edit().putString("symbol", v.trim().uppercase()).apply()

    var interval: String
        get() = sp.getString("interval", "60") ?: "60"
        set(v) = sp.edit().putString("interval", v).apply()

    /** Избранные контракты пользователя. */
    var favoriteSymbols: Set<String>
        get() = sp.getStringSet("favorite_symbols", null)?.map { it.uppercase() }?.toSet()
            ?: setOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT")
        set(v) = sp.edit().putStringSet("favorite_symbols", v.map { it.uppercase() }.toSet()).apply()

    /** Время последней успешной синхронизации Календаря событий (15-дневная автоматизация). */
    var calendarLastSyncMs: Long
        get() = sp.getLong("calendar_last_sync_ms", 0L)
        set(v) = sp.edit().putLong("calendar_last_sync_ms", v).apply()

    var calendarLastError: String
        get() = sp.getString("calendar_last_error", "") ?: ""
        set(v) = sp.edit().putString("calendar_last_error", v).apply()

    var bgEngine: Boolean
        get() = sp.getBoolean("bg_engine", false)
        set(v) = sp.edit().putBoolean("bg_engine", v).apply()

    var alertsEnabled: Boolean
        get() = sp.getBoolean("alerts_enabled", false)
        set(v) = sp.edit().putBoolean("alerts_enabled", v).apply()

    var onboardingDone: Boolean
        get() = sp.getBoolean("onboarding_done", false)
        set(v) = sp.edit().putBoolean("onboarding_done", v).apply()

    enum class Theme { BLACK, WHITE }

    companion object {
        const val DEFAULT_BYBIT_URL = "https://api.bybit.com"
        const val DEFAULT_AI_MODEL = "gemini-3.5-flash-lite"
        const val PROVIDER_GEMINI = "gemini"
        const val PROVIDER_OPENAI_COMPAT = "openai_compat"
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"

        val SYMBOLS = listOf(
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "BNBUSDT",
            "SUIUSDT", "AVAXUSDT", "DOGEUSDT", "OPUSDT", "LINKUSDT"
        )
        val INTERVALS = listOf(
            "1" to "1м", "5" to "5м", "15" to "15м", "30" to "30м",
            "60" to "1ч", "240" to "4ч", "D" to "1Д"
        )

        fun isDeprecatedGeminiModel(modelId: String): Boolean {
            val m = modelId.trim().lowercase()
            return m.startsWith("gemini-1.") ||
                m.startsWith("gemini-2.0") ||
                m.startsWith("gemini-2.5") ||
                m == "gemini-pro"
        }
    }
}
