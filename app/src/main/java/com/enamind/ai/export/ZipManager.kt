package com.enamind.ai.export

import android.content.Context
import com.enamind.ai.core.PythonBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Экспорт/импорт ENA-MIND ZIP. Перенос накопленной системы на другое устройство
 * или восстановление после переустановки.
 *
 * В архив входят: скилл (SKILL.md/SKILL_PATCH.md/SOURCES_LIVE.md/README.md/scripts/,
 * конфиги-примеры — из assets/skill, актуализированные override-скриптами, если
 * скилл обновлялся импортом), рабочие данные (journal.csv, forecasts.csv,
 * hypotheses.csv, config.json, alerts.json), сводная память, история версий,
 * дата экспорта. Секретные API-ключи НЕ включаются никогда: они хранятся только
 * в приватных настройках приложения и в архив не попадают.
 */
object ZipManager {

    private const val MAX_ENTRY = 8L * 1024 * 1024
    private val SKILL_TOP = listOf("SKILL.md", "SKILL_PATCH.md", "SOURCES_LIVE.md", "README.md")

    /** Список файлов скилла в assets (для Settings > Skills). */
    fun listSkillFiles(ctx: Context): JSONObject {
        val out = JSONArray()
        fun walk(dir: String) { // dir — путь относительно assets/skill/
            val list = ctx.assets.list("skill/$dir".trimEnd('/')) ?: return
            for (name in list) {
                val rel = if (dir.isEmpty()) name else "$dir/$name"
                if (name.contains('.')) {
                    if (name.endsWith(".py") || name.endsWith(".md") ||
                        name.endsWith(".json") || name.endsWith(".txt")
                    ) {
                        val size = try {
                            ctx.assets.open("skill/$rel").use { it.available().toLong() }
                        } catch (_: Exception) { 0L }
                        out.put(JSONObject().put("path", rel).put("size", size).put("origin", "apk"))
                    }
                } else {
                    walk(rel) // подкаталог
                }
            }
        }
        walk("")
        // override-скрипты (импортированное обновление скилла)
        val dir = File(File(ctx.filesDir, "enamind"), "scripts")
        if (dir.isDirectory) {
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".py") }?.forEach {
                out.put(JSONObject().put("path", "scripts/${it.name}").put("size", it.length()).put("origin", "update"))
            }
        }
        val version = try {
            val txt = ctx.assets.open("skill/SKILL.md").use { it.readBytes().toString(Charsets.UTF_8) }
            Regex("""(?m)^#\s+ENA-MIND\s+v([\d.]+)""").find(txt)?.groupValues?.get(1) ?: "6.0"
        } catch (_: Exception) { "6.0" }
        return JSONObject().put("files", out).put("version", version)
    }

    /** Создать экспортный ZIP; вернуть путь и состав (архив подписывается ZipCrypto). */
    fun export(ctx: Context, appVersion: String): JSONObject {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val out = File(dir, "ENA-MIND_export_$stamp.zip")
        val added = JSONArray()
        val hashes = java.util.TreeMap<String, String>() // path -> sha256 (для манифеста)
        try {
            // единый проход: байты считаются в памяти (для хэша) и пишутся в zip
            val entryBytes = HashMap<String, ByteArray>()
            ZipOutputStream(FileOutputStream(out)).use { zip ->
                fun put(path: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry("ena-mind/$path"))
                    zip.write(bytes)
                    zip.closeEntry()
                    entryBytes[path] = bytes
                    added.put(path)
                }
                fun addText(path: String, text: String) = put(path, text.toByteArray(Charsets.UTF_8))
                fun addAsset(rel: String) {
                    try {
                        put(rel, ctx.assets.open("skill/$rel").use { it.readBytes() })
                    } catch (_: Exception) {
                    }
                }
                fun addFile(rel: String, f: File) {
                    if (!f.isFile) return
                    put(rel, f.readBytes())
                }
                // 1) скилл из assets (+ override-скрипты поверх)
                SKILL_TOP.forEach { addAsset(it) }
                addAsset("data/config.example.json")
                addAsset("data/alerts.example.json")
                addAsset("data/README.txt")
                val listed = listSkillFiles(ctx).optJSONArray("files") ?: JSONArray()
                val overrides = File(File(ctx.filesDir, "enamind"), "scripts")
                for (i in 0 until listed.length()) {
                    val f = listed.getJSONObject(i)
                    val p = f.optString("path")
                    if (!p.startsWith("scripts/") || !p.endsWith(".py")) continue
                    val ov = File(overrides, p.removePrefix("scripts/"))
                    if (ov.isFile) addFile(p, ov) else addAsset(p)
                }
                // 2) рабочие данные (Python data dir — реальная ФС)
                val data = PythonBridge.exportDataFiles()
                val arr = data.optJSONArray("files") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    addFile("data/${f.optString("path")}", File(f.optString("file")))
                }
                // 2b) папка самообучения Kotlin (TrendMemoryVault: наблюдения, веса ботов, сводка)
                val vaultDir = File(File(ctx.filesDir, "enamind"), "learning_vault")
                if (vaultDir.isDirectory) {
                    listOf("trend_observations.jsonl", "bot_weights.json", "learning_summary.json").forEach { name ->
                        addFile("learning_vault/$name", File(vaultDir, name))
                    }
                }
                // 2c) память упреждающего индикатора (RealtimePulseVault)
                val pulseDir = File(File(ctx.filesDir, "enamind"), "realtime_pulse_vault")
                if (pulseDir.isDirectory) {
                    listOf("pulse_observations.jsonl", "pulse_weights.json", "pulse_summary.json").forEach { name ->
                        addFile("realtime_pulse_vault/$name", File(pulseDir, name))
                    }
                }
                // 2d) история самодиагностики сбоев
                val faultsFile = File(File(ctx.filesDir, "enamind"), "fault_history.jsonl")
                if (faultsFile.isFile) {
                    addFile("fault_history.jsonl", faultsFile)
                }
                // 3) сводная память + диагностика движка + квантовый снимок + метаданные
                addText("memory_summary.json", PythonBridge.memorySummary().toString(2))
                try {
                    val diag = PythonBridge.callEngine("engine_diagnostics").toString(2)
                    addText("engine_diagnostics.json", diag)
                } catch (_: Exception) {
                }
                try {
                    val intel = com.enamind.ai.EnaApp.get().state.engine.intelligence.value
                    if (intel != null) {
                        addText("quant_engine_snapshot.json", intel.toEngineJson().toString(2))
                    }
                } catch (_: Exception) {
                }
                // Краш-репорт CrashGuard — если приложение падало, причина едет в архив
                try {
                    val crash = File(File(ctx.filesDir, "enamind"), "crash_report.log")
                    if (crash.isFile) addText("crash_report.log", crash.readText().take(20_000))
                } catch (_: Exception) {
                }
                addText(
                    "version_history.json",
                    JSONArray()
                        .put(JSONObject().put("version", appVersion).put("skill", skillVersion(ctx))
                            .put("exported_at", System.currentTimeMillis())
                            .put("note", "ENA-MIND: Bybit/Binance/OKX multi-feed, QuantMathKernel + Walk-Forward OOS, ClockSync, LearningVault"))
                        .toString(2)
                )
                addText(
                    "export_meta.json",
                    JSONObject()
                        .put("exported_at", System.currentTimeMillis())
                        .put(
                            "exported_at_iso",
                            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
                        )
                        .put("app_version", appVersion)
                        .put("skill_version", skillVersion(ctx))
                        .put("files", added)
                        .put("secrets_included", false)
                        .toString(2)
                )
                // 4) Подпись — манифест хэшей всех записей + ECDSA-подпись устройства
                for ((p, b) in entryBytes) hashes[p] = ZipCrypto.sha256(b)
                val manifest = JSONObject()
                    .put("alg", ZipCrypto.ALG)
                    .put("created", System.currentTimeMillis())
                    .put("files", JSONObject(hashes as Map<*, *>))
                    .toString(2)
                val sig = ZipCrypto.sign(manifest.toByteArray(Charsets.UTF_8))
                if (sig != null) {
                    sig.put("manifest_b64", java.util.Base64.getEncoder()
                        .encodeToString(manifest.toByteArray(Charsets.UTF_8)))
                    zip.putNextEntry(ZipEntry(ZipCrypto.SIGNATURE_ENTRY))
                    zip.write(sig.toString(2).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            return JSONObject().put("ok", true).put("path", out.absolutePath).put("size", out.length())
                .put("files", added)
                .put("signed", hashes.isNotEmpty())
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("error", e.message ?: "export failed")
        }
    }

    fun skillVersion(ctx: Context): String = listSkillFiles(ctx).optString("version", "6.0")

    /**
     * Предварительная проверка ZIP БЕЗ установки — для диалога доверия в UI.
     * Возвращает статус подписи: signed+trusted / signed+unknown(отпечаток) /
     * unsigned (старый формат), число скриптов и версию скилла.
     */
    fun inspectTrust(zipFile: File): JSONObject {
        try {
            var signature: JSONObject? = null
            var scripts = 0
            var dataFiles = 0
            var version: String? = null
            ZipInputStream(FileInputStream(zipFile)).use { zin ->
                var entry: ZipEntry? = zin.nextEntry
                while (entry != null) {
                    val name = entry.name.replace("\\", "/")
                    if (!entry.isDirectory && !name.contains("..")) {
                        val base = name.removePrefix("ena-mind/").removePrefix("market-mind/")
                        when {
                            name == ZipCrypto.SIGNATURE_ENTRY ->
                                signature = JSONObject(readCapped(zin) ?: "{}")
                            name.endsWith(".py") && name.substringAfterLast('/')
                                    .let { it != name } // внутри каталога scripts/
                                -> scripts++
                            name.substringAfterLast('/') in DATA_FILES || base in VAULT_FILES -> dataFiles++
                        }
                        if (version == null && name.endsWith("SKILL.md")) {
                            val txt = readCapped(zin) ?: ""
                            version = Regex("""(?m)^#\s+ENA-MIND\s+v([\d.]+)""")
                                .find(txt)?.groupValues?.get(1)
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            val status: String
            var fingerprint: String? = null
            if (signature == null) {
                status = "unsigned"
            } else {
                val v = ZipCrypto.verify(signature!!)
                if (!v.optBoolean("ok")) {
                    return JSONObject().put("ok", false).put("error", v.optString("error"))
                }
                fingerprint = v.optString("fingerprint")
                status = if (v.optBoolean("trusted")) "trusted" else "unknown"
            }
            return JSONObject()
                .put("ok", true)
                .put("status", status)
                .put("fingerprint", fingerprint)
                .put("scripts", scripts)
                .put("data_files", dataFiles)
                .put("version", version)
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("error", e.message ?: "inspect failed")
        }
    }

    private val DATA_FILES = listOf(
        "journal.csv", "forecasts.csv", "hypotheses.csv", "config.json", "alerts.json",
        "forecast_memory_v7.jsonl", "agent_weights_v7.json", "model_v7.json",
        "onchain_events.csv", "onchain_views.csv", "dominance_history.json", "defillama_history.json"
    )
    private val VAULT_FILES = setOf(
        "learning_vault/trend_observations.jsonl",
        "learning_vault/bot_weights.json",
        "learning_vault/learning_summary.json",
        "realtime_pulse_vault/pulse_observations.jsonl",
        "realtime_pulse_vault/pulse_weights.json",
        "realtime_pulse_vault/pulse_summary.json"
    )

    /**
     * Импорт ZIP. Восстанавливает рабочие данные (journal, forecasts, hypotheses,
     * config, alerts) и, если в архиве есть python-скрипты, устанавливает
     * обновление скилла (override-каталог).
     *
     * Проверка подписи ZipCrypto:
     * - подпись не сходится -> отказ всегда;
     * - подписана чужим ключом или без подписи -> требует confirmed=true
     *   (UI показывает диалог доверия с отпечатком ключа).
     */
    fun import(ctx: Context, zipFile: File, confirmed: Boolean = false): JSONObject {
        val restored = JSONArray()
        val scripts = HashMap<String, String>()
        var docs = 0
        var signature: JSONObject? = null
        val entryHashes = HashMap<String, String>() // относительный путь -> sha256
        val fileContents = HashMap<String, Pair<String, String>>() // путь -> (kind, content)
        try {
            // проход 1: читаем всё, проверяем подпись
            ZipInputStream(FileInputStream(zipFile)).use { zin ->
                var entry: ZipEntry? = zin.nextEntry
                while (entry != null) {
                    val name = entry.name.replace("\\", "/")
                    if (!entry.isDirectory && !name.contains("..")) {
                        val content = readCapped(zin)
                        if (content != null) {
                            val base = name
                                .removePrefix("ena-mind/")
                                .removePrefix("market-mind/")
                            if (name == ZipCrypto.SIGNATURE_ENTRY) {
                                signature = JSONObject(content)
                            } else {
                                entryHashes[base] = ZipCrypto.sha256(content.toByteArray(Charsets.UTF_8))
                                when {
                                    base in DATA_FILES -> fileContents[base] = "data" to content
                                    base in VAULT_FILES -> fileContents[base] = "vault" to content
                                    base == "SKILL.md" -> fileContents[base] = "skillmd" to content
                                    base.startsWith("scripts/") && base.endsWith(".py") -> {
                                        val py = base.removePrefix("scripts/")
                                        if (!py.contains("/")) fileContents[base] = "script" to content
                                    }
                                    base.endsWith(".md") -> { docs++; fileContents[base] = "doc" to content }
                                }
                            }
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            // проверка подписи
            if (signature != null) {
                val v = ZipCrypto.verify(signature!!)
                if (!v.optBoolean("ok")) {
                    return JSONObject().put("ok", false)
                        .put("error", "ОТКАЗ: ${v.optString("error")} — импорт запрещён")
                }
                // сверяем хэши реальных записей с манифестом подписи
                val manifest = JSONObject(
                    String(java.util.Base64.getDecoder().decode(signature!!.optString("manifest_b64")))
                )
                val mfiles = manifest.optJSONObject("files") ?: JSONObject()
                var mismatch: String? = null
                for (path in mfiles.keys()) {
                    if (mfiles.optString(path) != entryHashes[path]) {
                        mismatch = path; break
                    }
                }
                if (mismatch != null) {
                    return JSONObject().put("ok", false)
                        .put("error", "ОТКАЗ: содержимое не совпадает с подписью ($mismatch)")
                }
                if (!v.optBoolean("trusted") && !confirmed) {
                    return JSONObject().put("ok", false).put("need_confirm", true)
                        .put("status", "unknown")
                        .put("fingerprint", v.optString("fingerprint"))
                        .put("error", "архив подписан неизвестным ключом — нужно подтверждение")
                }
            } else if (!confirmed) {
                return JSONObject().put("ok", false).put("need_confirm", true)
                    .put("status", "unsigned")
                    .put("error", "архив без подписи (сторонний или старый формат) — нужно подтверждение")
            }
            // проход 2 (по данным в памяти): применение
            var vaultRestored = false
            var pulseVaultRestored = false
            val vaultPrimary = File(File(ctx.filesDir, "enamind"), "learning_vault")
            val vaultMirror = ctx.getExternalFilesDir("ENA-MIND-Memory")
            val pulsePrimary = File(File(ctx.filesDir, "enamind"), "realtime_pulse_vault")
            val pulseMirror = ctx.getExternalFilesDir("ENA-RealtimePulse-Memory")
            for ((path, kindContent) in fileContents) {
                val (kind, content) = kindContent
                when (kind) {
                    "data" -> {
                        PythonBridge.importDataFile(path, content)
                        restored.put(path)
                    }
                    "vault" -> {
                        val isPulse = path.startsWith("realtime_pulse_vault/")
                        val fileName = if (isPulse) path.removePrefix("realtime_pulse_vault/") else path.removePrefix("learning_vault/")
                        val targetPrimary = if (isPulse) pulsePrimary else vaultPrimary
                        val targetMirror = if (isPulse) pulseMirror else vaultMirror
                        if (fileName.isNotEmpty() && !fileName.contains("/") && !fileName.contains("..")) {
                            targetPrimary.mkdirs()
                            File(targetPrimary, fileName).writeText(content, Charsets.UTF_8)
                            targetMirror?.let { md ->
                                md.mkdirs()
                                File(md, fileName).writeText(content, Charsets.UTF_8)
                            }
                            restored.put(path)
                            if (isPulse) pulseVaultRestored = true else vaultRestored = true
                        }
                    }
                    "script" -> scripts[path.removePrefix("scripts/")] = content
                    // SKILL.md едет в override — версионный страж отличит новый архив от старого
                    "skillmd" -> scripts[path] = content
                }
            }
            if (vaultRestored) {
                try {
                    com.enamind.ai.EnaApp.get().state.engine.memoryVault.reload()
                } catch (_: Exception) {
                }
            }
            if (pulseVaultRestored) {
                try {
                    com.enamind.ai.EnaApp.get().state.engine.pulseVault.reload()
                } catch (_: Exception) {
                }
            }
            var skillUpdated: JSONObject? = null
            if (scripts.isNotEmpty()) skillUpdated = PythonBridge.installSkillUpdate(ctx, scripts)
            return JSONObject()
                .put("ok", true)
                .put("restored", restored)
                .put("skill_scripts", scripts.size)
                .put("skill_docs", docs)
                .put("skill_update", skillUpdated)
                .put("signature", if (signature != null) "verified" else "skipped")
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("error", e.message ?: "import failed")
        }
    }

    /** Разбор ZIP скилла без установки (экран Skills: состав и версия). */
    fun inspect(zipFile: File): JSONObject {
        val files = JSONArray()
        var version: String? = null
        try {
            ZipInputStream(FileInputStream(zipFile)).use { zin ->
                var entry: ZipEntry? = zin.nextEntry
                while (entry != null) {
                    val name = entry.name.replace("\\", "/")
                    if (!entry.isDirectory && !name.contains("..")) {
                        files.put(name)
                        if (version == null && name.endsWith("SKILL.md")) {
                            val txt = readCapped(zin) ?: ""
                            version = Regex("""(?m)^#\s+ENA-MIND\s+v([\d.]+)""").find(txt)?.groupValues?.get(1)
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            return JSONObject().put("ok", true).put("files", files).put("version", version)
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("error", e.message ?: "inspect failed")
        }
    }

    /** Читает запись, отбрасывая слишком большие (> MAX_ENTRY) — защита от zip-бомб. */
    private fun readCapped(zin: ZipInputStream): String? {
        return try {
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            var tooBig = false
            while (true) {
                val n = zin.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_ENTRY) {
                    tooBig = true
                    break
                }
                buf.write(chunk, 0, n)
            }
            if (tooBig) null else buf.toString("UTF-8")
        } catch (e: Exception) {
            null
        }
    }
}
