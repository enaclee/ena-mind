package com.enamind.ai.core

import android.content.Context
import android.util.Log
import com.enamind.ai.db.AppDatabase
import java.io.File

/**
 * StorageGuard — автоматическая гигиена кэша, временных файлов и БД Room.
 *
 * Зачем: при долгой ночной работе фонового сервиса (EngineService) временный
 * кэш, оборванные .tmp-файлы при заморозке процесса MIUI/Android и таблицы Room
 * не должны расти бесконечно или вызывать падения при утреннем открытии.
 *
 * Важно: никогда НЕ трогает filesDir/enamind/learning_vault и внешнюю папку
 * ENA-MIND-Memory — накопленный опыт самообучения сохраняется всегда.
 */
object StorageGuard {

    private const val TAG = "StorageGuard"
    private const val MAX_CACHE_BYTES = 12L * 1024L * 1024L // 12 MB
    private const val STALE_CACHE_MS = 24L * 3600_000L

    data class HygieneReport(
        val deletedTmpFiles: Int,
        val deletedCacheFiles: Int,
        val freedBytes: Long,
        val remainingCacheBytes: Long
    )

    fun sanitizeOnStartup(ctx: Context, nowMs: Long = System.currentTimeMillis()): HygieneReport {
        var deletedTmp = 0
        var deletedCache = 0
        var freed = 0L

        // 1. Удаляем битые/незавершённые .tmp файлы после резкого убийства процесса ночью
        val enamindDir = File(ctx.filesDir, "enamind")
        if (enamindDir.isDirectory) {
            deletedTmp += deleteTmpRecursive(enamindDir)
        }
        ctx.getExternalFilesDir("ENA-MIND-Memory")?.takeIf { it.isDirectory }?.let {
            deletedTmp += deleteTmpRecursive(it)
        }

        // 2. Чистим протухшие временные файлы кэша (>24ч), не трогая data-файлы
        val cacheDirs = listOfNotNull(ctx.cacheDir, ctx.codeCacheDir, ctx.externalCacheDir)
        for (dir in cacheDirs) {
            if (!dir.isDirectory) continue
            val (cDel, cFreed) = pruneStaleInDir(dir, nowMs - STALE_CACHE_MS)
            deletedCache += cDel
            freed += cFreed
        }

        // 3. Если кэш всё равно раздулся >12 МБ — подрезаем самые старые временные файлы
        var remaining = cacheDirs.sumOf { dirSize(it) }
        if (remaining > MAX_CACHE_BYTES) {
            val allFiles = cacheDirs
                .flatMap { collectFiles(it) }
                .sortedBy { it.lastModified() }
            for (f in allFiles) {
                if (remaining <= MAX_CACHE_BYTES / 2) break
                val len = f.length()
                if (f.delete()) {
                    deletedCache++
                    freed += len
                    remaining = (remaining - len).coerceAtLeast(0L)
                }
            }
        }

        return HygieneReport(deletedTmp, deletedCache, freed, remaining)
    }

    /** Безопасная ручная очистка временного кэша (не затрагивает память и прогнозы). */
    fun clearTemporaryCache(ctx: Context): HygieneReport {
        var deleted = 0
        var freed = 0L
        val cacheDirs = listOfNotNull(ctx.cacheDir, ctx.codeCacheDir, ctx.externalCacheDir)
        for (dir in cacheDirs) {
            if (!dir.isDirectory) continue
            for (f in collectFiles(dir)) {
                val len = f.length()
                if (f.delete()) {
                    deleted++
                    freed += len
                }
            }
        }
        val remaining = cacheDirs.sumOf { dirSize(it) }
        return HygieneReport(0, deleted, freed, remaining)
    }

    fun cacheSizeBytes(ctx: Context): Long =
        listOfNotNull(ctx.cacheDir, ctx.codeCacheDir, ctx.externalCacheDir).sumOf { dirSize(it) }

    suspend fun pruneDatabase(db: AppDatabase, nowMs: Long = System.currentTimeMillis()) {
        try {
            db.newsDao().prune(nowMs - 3L * 24L * 3600_000L)
            db.analysisDao().pruneOld(keep = 120)
            db.apiCheckDao().pruneOld(keep = 240)
        } catch (e: Exception) {
            Log.w(TAG, "pruneDatabase: ${e.message}")
        }
    }

    private fun deleteTmpRecursive(root: File): Int {
        var count = 0
        root.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                if (f.name != "__pycache__") count += deleteTmpRecursive(f)
            } else if (f.name.endsWith(".tmp") || f.name == "import.zip") {
                if (f.delete()) count++
            }
        }
        return count
    }

    private fun pruneStaleInDir(dir: File, cutoffMs: Long): Pair<Int, Long> {
        var count = 0
        var freed = 0L
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                val (c, fr) = pruneStaleInDir(f, cutoffMs)
                count += c
                freed += fr
                if (f.listFiles()?.isEmpty() == true) f.delete()
            } else if (f.lastModified() in 1L until cutoffMs || f.name.endsWith(".tmp")) {
                val len = f.length()
                if (f.delete()) {
                    count++
                    freed += len
                }
            }
        }
        return count to freed
    }

    private fun collectFiles(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        val out = ArrayList<File>()
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) out += collectFiles(f) else out += f
        }
        return out
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.isDirectory) return 0L
        var sum = 0L
        dir.listFiles()?.forEach { f ->
            sum += if (f.isDirectory) dirSize(f) else f.length()
        }
        return sum
    }
}
