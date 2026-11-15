package com.enamind.ai.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Настройки ENA v9.0. Секреты (API-ключи Bybit / NewsAPI / Google AI Studio) хранятся
 * ТОЛЬКО здесь, в приватном хранилище приложения, зашифрованными через Android Keystore,
 * и НИКОГДА не попадают в экспортируемые ZIP.
 *
 * Изолированный ИИ-чат Google AI Studio использует свой ключ и модель и никак не
 * вмешивается в квантовый движок и прогнозы рынка ENA.
 */
class Settings(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("enamind", Context.MODE_PRIVATE)

    /** Секрет: на диске "enc:<base64>" (Keystore AES/GCM); legacy-значения читаются как есть. */
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

    // ---------------- приватные API Bybit (трейд-панель, ключи пользователя) ----------------

    /** Ключи Bybit пользователя — ТОЛЬКО на устройстве (зашифрованы в Keystore),
     *  только на api.bybit.com, в экспортируемые ZIP не попадают никогда. */
    var bybitApiKey: String
        get() = getSecret("bybit_api_key")
        set(v) = setSecret("bybit_api_key", v)

    var bybitApiSecret: String
        get() = getSecret("bybit_api_secret")
        set(v) = setSecret("bybit_api_secret", v)

    // ---------------- Google AI Studio (изолированный свободный ИИ-чат) ----------------

    /** Ключ Google AI Studio пользователя — зашифрован Keystore, в ZIP не попадает. */
    var aiStudioApiKey: String
        get() = getSecret("ai_studio_api_key")
        set(v) = setSecret("ai_studio_api_key", v)

    /** Выбранная бесплатная или пользовательская модель AI Studio. */
    var aiStudioModel: String
        get() = sp.getString("ai_studio_model", DEFAULT_AI_MODEL) ?: DEFAULT_AI_MODEL
        set(v) = sp.edit().putString("ai_studio_model", v.trim().ifBlank { DEFAULT_AI_MODEL }).apply()

    var symbol: String
        get() = sp.getString("symbol", "BTCUSDT") ?: "BTCUSDT"
        set(v) = sp.edit().putString("symbol", v).apply()

    var interval: String
        get() = sp.getString("interval", "60") ?: "60"
        set(v) = sp.edit().putString("interval", v).apply()

    /**
     * v7.1: фоновое накопление памяти движка. true — при работе приложения держится
     * foreground-сервис (уведомление), конвейер и запись памяти продолжаются, когда
     * приложение свёрнуто; false — движок живёт только пока приложение на экране.
     * v8.1 (ТЗ §3): по умолчанию FALSE — фоновый режим включается только явным
     * решением пользователя на онбординге/в настройках, а не молчаливым стартом.
     */
    var bgEngine: Boolean
        get() = sp.getBoolean("bg_engine", false)
        set(v) = sp.edit().putBoolean("bg_engine", v).apply()

    /** v8.1 (ТЗ §3): алерты включаются пользователем; отказ не ломает анализ. */
    var alertsEnabled: Boolean
        get() = sp.getBoolean("alerts_enabled", false)
        set(v) = sp.edit().putBoolean("alerts_enabled", v).apply()

    /** v8.1: онбординг пройден (показываем один раз, не при каждом запуске). */
    var onboardingDone: Boolean
        get() = sp.getBoolean("onboarding_done", false)
        set(v) = sp.edit().putBoolean("onboarding_done", v).apply()

    enum class Theme { BLACK, WHITE }

    companion object {
        const val DEFAULT_BYBIT_URL = "https://api.bybit.com"
        const val DEFAULT_AI_MODEL = "gemini-2.5-flash-lite"
        /** v9.0+: расширенный watchlist из 10 ликвидных активов. */
        val SYMBOLS = listOf(
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "BNBUSDT",
            "SUIUSDT", "AVAXUSDT", "DOGEUSDT", "OPUSDT", "LINKUSDT"
        )
        val INTERVALS = listOf(
            "1" to "1м", "5" to "5м", "15" to "15м", "30" to "30м",
            "60" to "1ч", "240" to "4ч", "D" to "1Д"
        )
    }
}
