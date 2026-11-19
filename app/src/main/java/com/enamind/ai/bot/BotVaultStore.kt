package com.enamind.ai.bot

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.enamind.ai.core.StorageGuard
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val Context.botVaultDataStore: DataStore<Preferences> by preferencesDataStore(name = "ena_bot_vault")

/**
 * Хранилище торгового бота и зашифрованных ключей:
 *  - Выделенная изолированная папка `filesDir/enamind/bot_engine/` (конфигурация, статистика по режимам, журнал сделок JSONL)
 *    с атомарной записью `.tmp -> renameTo`.
 *  - 3 НЕЗАВИСИМЫХ слота API-ключей (`bybit_demo`, `bybit_testnet`, `bybit_mainnet`), шифруемых через
 *    Google Tink (AES256_GCM + AndroidKeysetManager) поверх Jetpack DataStore Preferences.
 *  - Ключи НИКОГДА не пишутся в логи и не попадают в открытые JSON-файлы или ZIP-экспорты.
 */
class BotVaultStore private constructor(
    private val botDir: File,
    private val dataStore: DataStore<Preferences>,
    private val aeadProvider: () -> Aead
) {
    private val lock = Any()
    private val cachedSlots = ConcurrentHashMap<BotMode, BybitCredentialSlot>()

    constructor(ctx: Context) : this(
        botDir = StorageGuard.botEngineDir(ctx),
        dataStore = ctx.applicationContext.botVaultDataStore,
        aeadProvider = { buildAndroidAead(ctx.applicationContext) }
    )

    internal constructor(
        botDir: File,
        dataStore: DataStore<Preferences>,
        testAead: Aead
    ) : this(botDir, dataStore, { testAead })

    private val aead: Aead by lazy { aeadProvider() }

    private fun slotPrefKey(mode: BotMode): Preferences.Key<String>? = when (mode) {
        BotMode.BYBIT_DEMO -> stringPreferencesKey("slot_bybit_demo")
        BotMode.BYBIT_TESTNET -> stringPreferencesKey("slot_bybit_testnet")
        BotMode.BYBIT_MAINNET -> stringPreferencesKey("slot_bybit_mainnet")
        BotMode.PAPER -> null
    }

    fun loadCredentialSlot(mode: BotMode): BybitCredentialSlot {
        val prefKey = slotPrefKey(mode) ?: return BybitCredentialSlot()
        cachedSlots[mode]?.let { return it }
        return synchronized(lock) {
            cachedSlots[mode]?.let { return@synchronized it }
            val loaded = runCatching {
                val prefs = runBlocking { dataStore.data.first() }
                val cipherB64 = prefs[prefKey] ?: return@runCatching BybitCredentialSlot()
                val rawBytes = Base64.getDecoder().decode(cipherB64)
                val plain = aead.decrypt(rawBytes, mode.id.toByteArray(Charsets.UTF_8))
                val obj = JSONObject(String(plain, Charsets.UTF_8))
                BybitCredentialSlot(
                    apiKey = obj.optString("k", "").trim(),
                    apiSecret = obj.optString("s", "").trim(),
                    accountLabel = obj.optString("lbl", "").trim()
                )
            }.onFailure {
                Log.w(TAG, "decrypt slot ${mode.id}: failed")
            }.getOrDefault(BybitCredentialSlot())
            cachedSlots[mode] = loaded
            loaded
        }
    }

    fun saveCredentialSlot(mode: BotMode, slot: BybitCredentialSlot) {
        val prefKey = slotPrefKey(mode) ?: return
        val clean = slot.copy(
            apiKey = slot.apiKey.trim(),
            apiSecret = slot.apiSecret.trim(),
            accountLabel = slot.accountLabel.trim()
        )
        synchronized(lock) {
            cachedSlots[mode] = clean
            val json = JSONObject()
                .put("k", clean.apiKey)
                .put("s", clean.apiSecret)
                .put("lbl", clean.accountLabel)
                .toString()
            val cipherBytes = aead.encrypt(json.toByteArray(Charsets.UTF_8), mode.id.toByteArray(Charsets.UTF_8))
            val b64 = Base64.getEncoder().encodeToString(cipherBytes)
            runBlocking {
                dataStore.edit { prefs ->
                    prefs[prefKey] = b64
                }
            }
        }
    }

    fun clearCredentialSlot(mode: BotMode) {
        val prefKey = slotPrefKey(mode) ?: return
        synchronized(lock) {
            cachedSlots.remove(mode)
            runBlocking {
                dataStore.edit { prefs ->
                    prefs.remove(prefKey)
                }
            }
        }
    }

    fun loadConfig(): BotConfig = synchronized(lock) {
        val file = File(botDir, FILE_CONFIG)
        if (!file.exists()) return@synchronized BotConfig()
        return@synchronized runCatching {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            val maxTr = o.optInt("maxTranches", 3).coerceIn(1, 4)
            val weightsArr = o.optJSONArray("trancheWeightsPct")
            val parsedWeights = if (weightsArr != null && weightsArr.length() == maxTr) {
                (0 until weightsArr.length()).map { weightsArr.optInt(it, 100 / maxTr).coerceAtLeast(1) }
            } else {
                BotConfig.defaultWeightsFor(maxTr)
            }
            BotConfig(
                mode = BotMode.fromId(o.optString("mode")),
                symbol = o.optString("symbol", "BTCUSDT").ifBlank { "BTCUSDT" },
                capitalUsdt = o.optDouble("capitalUsdt", 50.0).coerceIn(5.0, 100_000.0),
                leverage = o.optInt("leverage", 10).coerceIn(1, 125),
                marginMode = MarginMode.fromName(o.optString("marginMode")),
                targetBasis = TargetBasis.fromName(o.optString("targetBasis")),
                directionPolicy = DirectionPolicy.fromName(o.optString("directionPolicy")),
                minNetProfitPct = o.optDouble("minNetProfitPct", 0.5).coerceIn(0.15, 10.0),
                maxProfitPct = o.optDouble("maxProfitPct", 3.5).coerceIn(0.3, 50.0),
                trailingGivebackPct = o.optDouble("trailingGivebackPct", 0.90).coerceIn(0.05, 5.0),
                maxHoldWaitMin = o.optInt("maxHoldWaitMin", 30).coerceIn(1, 720),
                stopLossEnabled = o.optBoolean("stopLossEnabled", false),
                stopLossPct = o.optDouble("stopLossPct", 1.5).coerceIn(0.2, 40.0),
                serverTrailingGuardEnabled = o.optBoolean("serverTrailingGuardEnabled", true),
                dailyMaxLossPct = o.optDouble("dailyMaxLossPct", 3.0).coerceIn(0.5, 50.0),
                confirmSignalSec = o.optInt("confirmSignalSec", 4).coerceIn(1, 30),
                requireGlowConsensus = o.optBoolean("requireGlowConsensus", false),
                requireRadarNotFlat = o.optBoolean("requireRadarNotFlat", false),
                allowCounterAbsorption = o.optBoolean("allowCounterAbsorption", false),
                hudEnabled = o.optBoolean("hudEnabled", false),
                hudDisplayMode = HudDisplayMode.fromName(o.optString("hudDisplayMode")),
                timeoutBehavior = TimeoutBehavior.fromName(o.optString("timeoutBehavior")),
                paperStartBalanceUsdt = o.optDouble("paperStartBalanceUsdt", 1000.0).coerceIn(50.0, 1_000_000.0),
                enacoreBridge = o.optBoolean("enacoreBridge", false),
                instrumentCategory = o.optString("instrumentCategory", "linear").takeIf { it in setOf("linear", "spot", "inverse") } ?: "linear",
                instrumentVenue = o.optString("instrumentVenue", "Bybit"),
                entryDeltaPct = o.optDouble("entryDeltaPct", 14.0).coerceIn(1.0, 90.0),
                entryOfi = o.optDouble("entryOfi", 0.16).coerceIn(0.01, 1.0),
                maxSpreadPct = o.optDouble("maxSpreadPct", 0.15).coerceIn(0.01, 1.0),
                executionPreference = com.enamind.ai.bot.strategy.OrderExecutionPreference.fromName(o.optString("executionPreference")),
                makerFallbackPolicy = MakerFallbackPolicy.fromName(o.optString("makerFallbackPolicy")),
                maxTranches = maxTr,
                trancheWeightsPct = parsedWeights,
                scaleStepPct = o.optDouble("scaleStepPct", 0.28).coerceIn(0.08, 5.0),
                useAtrScaleStep = o.optBoolean("useAtrScaleStep", true),
                peelPartialEnabled = o.optBoolean("peelPartialEnabled", true),
                allowUnlimitedHold = o.optBoolean("allowUnlimitedHold", false),
                stopLossBasis = StopLossBasis.fromName(o.optString("stopLossBasis")),
                stopLossAtrMult = o.optDouble("stopLossAtrMult", 2.0).coerceIn(0.5, 8.0)
            )
        }.getOrDefault(BotConfig())
    }

    fun saveConfig(cfg: BotConfig) = synchronized(lock) {
        botDir.mkdirs()
        val o = JSONObject()
            .put("mode", cfg.mode.id)
            .put("symbol", cfg.symbol)
            .put("capitalUsdt", cfg.capitalUsdt)
            .put("leverage", cfg.leverage)
            .put("marginMode", cfg.marginMode.name)
            .put("targetBasis", cfg.targetBasis.name)
            .put("directionPolicy", cfg.directionPolicy.name)
            .put("minNetProfitPct", cfg.minNetProfitPct)
            .put("maxProfitPct", cfg.maxProfitPct)
            .put("trailingGivebackPct", cfg.trailingGivebackPct)
            .put("maxHoldWaitMin", cfg.maxHoldWaitMin)
            .put("stopLossEnabled", cfg.stopLossEnabled)
            .put("stopLossPct", cfg.stopLossPct)
            .put("serverTrailingGuardEnabled", cfg.serverTrailingGuardEnabled)
            .put("dailyMaxLossPct", cfg.dailyMaxLossPct)
            .put("confirmSignalSec", cfg.confirmSignalSec)
            .put("requireGlowConsensus", cfg.requireGlowConsensus)
            .put("requireRadarNotFlat", cfg.requireRadarNotFlat)
            .put("allowCounterAbsorption", cfg.allowCounterAbsorption)
            .put("hudEnabled", cfg.hudEnabled)
            .put("hudDisplayMode", cfg.hudDisplayMode.name)
            .put("timeoutBehavior", cfg.timeoutBehavior.name)
            .put("paperStartBalanceUsdt", cfg.paperStartBalanceUsdt)
            .put("enacoreBridge", cfg.enacoreBridge)
            .put("instrumentCategory", cfg.instrumentCategory).put("instrumentVenue", cfg.instrumentVenue)
            .put("entryDeltaPct", cfg.entryDeltaPct).put("entryOfi", cfg.entryOfi).put("maxSpreadPct", cfg.maxSpreadPct)
            .put("executionPreference", cfg.executionPreference.name)
            .put("makerFallbackPolicy", cfg.makerFallbackPolicy.name)
            .put("maxTranches", cfg.maxTranches)
            .put("trancheWeightsPct", JSONArray(cfg.normalizedTrancheWeights()))
            .put("scaleStepPct", cfg.scaleStepPct)
            .put("useAtrScaleStep", cfg.useAtrScaleStep)
            .put("peelPartialEnabled", cfg.peelPartialEnabled)
            .put("allowUnlimitedHold", cfg.allowUnlimitedHold)
            .put("stopLossBasis", cfg.stopLossBasis.name)
            .put("stopLossAtrMult", cfg.stopLossAtrMult)
        atomicWrite(File(botDir, FILE_CONFIG), o.toString(2))
    }

    fun loadStatsByMode(): Map<BotMode, ModeWinrateStats> = synchronized(lock) {
        val defaults = BotMode.entries.associateWith { ModeWinrateStats(it) }.toMutableMap()
        val file = File(botDir, FILE_STATS)
        if (!file.exists()) return@synchronized defaults
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            for (m in BotMode.entries) {
                val obj = root.optJSONObject(m.id) ?: continue
                defaults[m] = ModeWinrateStats(
                    mode = m,
                    totalTrades = obj.optInt("totalTrades", 0),
                    wins = obj.optInt("wins", 0),
                    losses = obj.optInt("losses", 0),
                    winratePct = obj.optDouble("winratePct", 0.0),
                    totalNetPnlUsdt = obj.optDouble("totalNetPnlUsdt", 0.0),
                    dailyRealizedLossUsdt = obj.optDouble("dailyRealizedLossUsdt", 0.0),
                    lastDayUtc = obj.optString("lastDayUtc", "")
                )
            }
        }
        return@synchronized defaults
    }

    fun saveStatsByMode(stats: Map<BotMode, ModeWinrateStats>) = synchronized(lock) {
        botDir.mkdirs()
        val root = JSONObject()
        for ((m, s) in stats) {
            root.put(
                m.id,
                JSONObject()
                    .put("totalTrades", s.totalTrades)
                    .put("wins", s.wins)
                    .put("losses", s.losses)
                    .put("winratePct", s.winratePct)
                    .put("totalNetPnlUsdt", s.totalNetPnlUsdt)
                    .put("dailyRealizedLossUsdt", s.dailyRealizedLossUsdt)
                    .put("lastDayUtc", s.lastDayUtc)
            )
        }
        atomicWrite(File(botDir, FILE_STATS), root.toString(2))
    }

    fun loadTrades(limit: Int = 80): List<BotTradeRecord> = synchronized(lock) {
        val file = File(botDir, FILE_TRADES)
        if (!file.exists()) return@synchronized emptyList()
        return@synchronized runCatching {
            file.readLines(Charsets.UTF_8)
                .mapNotNull { line ->
                    val t = line.trim()
                    if (t.isEmpty()) null else parseTrade(JSONObject(t))
                }
                .takeLast(limit)
        }.getOrDefault(emptyList())
    }

    fun appendTrade(record: BotTradeRecord) = synchronized(lock) {
        botDir.mkdirs()
        val existing = loadTrades(MAX_TRADES_HISTORY - 1) + record
        val text = existing.joinToString("\n") { tradeToJson(it).toString() } + "\n"
        atomicWrite(File(botDir, FILE_TRADES), text)
    }

    private fun tradeToJson(t: BotTradeRecord): JSONObject = JSONObject()
        .put("id", t.id)
        .put("mode", t.mode.id)
        .put("symbol", t.symbol)
        .put("side", t.side.name)
        .put("qty", t.qty)
        .put("entryPrice", t.entryPrice)
        .put("exitPrice", t.exitPrice)
        .put("leverage", t.leverage)
        .put("openedAtMs", t.openedAtMs)
        .put("closedAtMs", t.closedAtMs)
        .put("grossPnlUsdt", t.grossPnlUsdt)
        .put("feesUsdt", t.feesUsdt)
        .put("netPnlUsdt", t.netPnlUsdt)
        .put("netPnlPct", t.netPnlPct)
        .put("exitReason", t.exitReason)
        .put("won", t.won)

    private fun parseTrade(o: JSONObject): BotTradeRecord? {
        val id = o.optString("id")
        val sym = o.optString("symbol")
        if (id.isBlank() || sym.isBlank()) return null
        return BotTradeRecord(
            id = id,
            mode = BotMode.fromId(o.optString("mode")),
            symbol = sym,
            side = TradeSide.fromName(o.optString("side")),
            qty = o.optDouble("qty", 0.0),
            entryPrice = o.optDouble("entryPrice", 0.0),
            exitPrice = o.optDouble("exitPrice", 0.0),
            leverage = o.optInt("leverage", 1),
            openedAtMs = o.optLong("openedAtMs", 0L),
            closedAtMs = o.optLong("closedAtMs", 0L),
            grossPnlUsdt = o.optDouble("grossPnlUsdt", 0.0),
            feesUsdt = o.optDouble("feesUsdt", 0.0),
            netPnlUsdt = o.optDouble("netPnlUsdt", 0.0),
            netPnlPct = o.optDouble("netPnlPct", 0.0),
            exitReason = o.optString("exitReason", ""),
            won = o.optBoolean("won", false)
        )
    }

    fun exportTradesJson(): String = synchronized(lock) {
        val trades = loadTrades(MAX_TRADES_HISTORY)
        val arr = JSONArray()
        trades.forEach { arr.put(tradeToJson(it)) }
        arr.toString(2)
    }

    private fun atomicWrite(target: File, content: String) {
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { e ->
            Log.w(TAG, "atomicWrite ${target.name}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BotVaultStore"
        private const val FILE_CONFIG = "bot_config.json"
        private const val FILE_STATS = "bot_stats_by_mode.json"
        private const val FILE_TRADES = "bot_trades.jsonl"
        private const val MAX_TRADES_HISTORY = 500
        private const val TINK_KEYSET_NAME = "ena_bot_master_aead"
        private const val TINK_PREFS_NAME = "ena_bot_tink_keyset"
        private const val MASTER_KEY_URI = "android-keystore://ena_bot_master_key"

        private fun buildAndroidAead(appCtx: Context): Aead {
            AeadConfig.register()
            val keysetHandle = AndroidKeysetManager.Builder()
                .withSharedPref(appCtx, TINK_KEYSET_NAME, TINK_PREFS_NAME)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
            @Suppress("DEPRECATION")
            return keysetHandle.getPrimitive(Aead::class.java)
        }
    }
}
