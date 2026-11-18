package com.enamind.ai.core

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Мост к Python-ядру ENA-MIND (Chaquopy). Все вызовы тяжелые (сеть/анализ) —
 * выполнять вне main-потока. Результаты — JSON-строки (честный контракт с Python).
 */
object PythonBridge {

    private val started = AtomicBoolean(false)
    private val initialised = AtomicBoolean(false)

    /** Последняя ошибка инициализации (для баннера на экране движка и диагностики). */
    @Volatile
    var lastInitError: String? = null
        private set

    /** Java-колбэк прогресса узлов: Python вызывает onNode(json) при смене статуса. */
    fun interface NodeCallback {
        fun onNode(json: String)
    }

    @Synchronized
    fun init(ctx: Context): JSONObject {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(ctx.applicationContext))
            }
            val dataDir = File(ctx.filesDir, "enamind").apply { mkdirs() }
            // Передаём версию скилла APK — версионный страж в chain_bridge.init
            // игнорирует override-скрипты старее APK (конфликт старых импортов)
            val r = JSONObject(
                Python.getInstance()
                    .getModule("chain_bridge")
                    .callAttr("init", dataDir.absolutePath, apkSkillVersion(ctx))
                    .toString()
            )
            if (r.optBoolean("ok")) {
                lastInitError = null
                initialised.set(true)
            } else {
                lastInitError = r.optString("error", "init failed")
            }
            return r
        } catch (e: PyException) {
            lastInitError = "python: ${e.message}"
            return err("python: ${e.message}")
        } catch (e: LinkageError) {
            lastInitError = "native: ${e.message}"
            return err("native: ${e.message}")
        } catch (e: Exception) {
            lastInitError = e.message ?: e.javaClass.simpleName
            return err(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Версия скилла, упакованного в APK (assets/skill/SKILL.md: «# ENA-MIND vX.Y»). */
    fun apkSkillVersion(ctx: Context): String = try {
        val txt = ctx.assets.open("skill/SKILL.md").use { it.readBytes().toString(Charsets.UTF_8) }
        Regex("""(?m)^#\s+ENA-MIND\s+v([\d.]+)""").find(txt)?.groupValues?.get(1) ?: "8.0"
    } catch (_: Exception) {
        "8.0"
    }

    /** ВЫЗЫВАТЬ ТОЛЬКО ИЗ ФОНОВОГО ПОТОКА: init может занять секунды (старт CPython + импорт 21 модуля). */
    fun isReady(): Boolean = initialised.get()

    private fun call(name: String, vararg args: Any?): JSONObject {
        if (!initialised.get()) return err("python not initialised")
        return try {
            val r = Python.getInstance().getModule("chain_bridge")
                .callAttr(name, *args).toString()
            JSONObject(r)
        } catch (e: PyException) {
            err("python: ${e.message}")
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Полная цепочка A->Б. cb получает статусы узлов в реальном времени. */
    fun runChain(
        symbol: String, interval: String, newsJson: String,
        liqJson: String, planJson: String?, budget: Double, cb: NodeCallback?
    ): JSONObject {
        if (!initialised.get()) return err("python not initialised")
        return try {
            val r = Python.getInstance().getModule("chain_bridge")
                .callAttr(
                    "run_chain", symbol, interval, newsJson,
                    planJson ?: "null", liqJson, budget, cb, true
                ).toString()
            JSONObject(r)
        } catch (e: PyException) {
            err("python: ${e.message}")
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    fun explain(resultJson: String): String = try {
        Python.getInstance().getModule("chain_bridge")
            .callAttr("explain", resultJson).toString()
    } catch (e: Exception) {
        "отчёт недоступен: ${e.message}"
    }

    fun verifyForecasts(): JSONObject = call("verify_forecasts")
    fun forecastStats(): JSONObject = call("forecast_stats")
    fun forecastRecords(): JSONObject = call("forecast_records")
    fun journalStats(): JSONObject = call("journal_stats")
    fun feedbackReport(): JSONObject = call("feedback_report")
    fun memorySummary(): JSONObject = call("memory_summary")
    fun snapshot(symbol: String, budget: Double, liqJson: String): JSONObject =
        call("snapshot", symbol, budget, liqJson)

    fun klines(symbol: String, interval: String, limit: Int): JSONObject =
        call("klines", symbol, interval, limit)

    fun runSelftests(): JSONObject = call("run_selftests")
    fun skillFiles(): JSONObject = call("skill_files")
    fun exportDataFiles(): JSONObject = call("export_data_files")

    // ================= NEURAL FORECAST ENGINE =================

    /** Полный прогон движка по живому срезу (каждые ~5с из NeuralEngine). */
    fun engineFeed(symbol: String, fastJson: String, generation: Long = -1): String = try {
        Python.getInstance().getModule("chain_bridge")
            .callAttr("engine_feed", symbol, fastJson, generation).toString()
    } catch (e: PyException) {
        JSONObject().put("ok", false).put("error", "python: ${e.message}").toString()
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName).toString()
    }

    /** Тяжёлый канал: свечи по таймфрейму в движок. */
    fun engineSetKlines(symbol: String, tf: String, rowsJson: String, generation: Long = -1): JSONObject = try {
        JSONObject(
            Python.getInstance().getModule("chain_bridge")
                .callAttr("engine_set_klines", symbol, tf, rowsJson, generation).toString()
        )
    } catch (e: PyException) {
        err("python: ${e.message}")
    } catch (e: Exception) {
        err(e.message ?: e.javaClass.simpleName)
    }

    /** Вызов engine_* функции моста по имени (статус/память/журнал/бэкфиллы). */
    fun callEngine(name: String, vararg args: Any?): JSONObject {
        if (!initialised.get()) return err("python not initialised")
        return try {
            val r = Python.getInstance().getModule("chain_bridge")
                .callAttr(name, *args).toString()
            JSONObject(r)
        } catch (e: PyException) {
            err("python: ${e.message}")
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    fun engineStatus(): JSONObject = callEngine("engine_status")
    fun engineMemory(): JSONObject = callEngine("engine_memory")
    fun engineLog(n: Int = 50): JSONObject = callEngine("engine_log", n)
    fun engineForecastHistory(n: Int = 50): JSONObject = callEngine("engine_forecast_history", n)

    fun importDataFile(name: String, content: String): JSONObject =
        call("import_data_file", name, content)

    /** Импорт обновления скилла: .py и SKILL.md кладём в filesDir/enamind/scripts.
     *  SKILL.md нужен версионному стражу (init отличит старый override от нового). */
    fun installSkillUpdate(ctx: Context, scripts: Map<String, String>): JSONObject {
        val dir = File(File(ctx.filesDir, "enamind"), "scripts")
        dir.mkdirs()
        return try {
            scripts.forEach { (name, content) ->
                val safe = name.endsWith(".py") || name == "SKILL.md"
                if (safe && !name.contains("..") && !name.contains("/")) {
                    File(dir, name).writeText(content)
                }
            }
            // переинициализация: override-каталог встаёт первым в sys.path
            initialised.set(false)
            init(ctx)
        } catch (e: LinkageError) {
            err("native: ${e.message}")
        } catch (e: Exception) {
            err(e.message ?: "install failed")
        }
    }

    private fun err(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)
}
