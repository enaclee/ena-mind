package com.enamind.ai

import android.app.Application
import com.enamind.ai.core.AppState
import com.enamind.ai.core.PythonBridge
import com.enamind.ai.db.AppDatabase
import kotlinx.coroutines.launch

/**
 * ENA-MIND AI. Точка инициализации: Room, Python (Chaquopy), глобальное состояние.
 */
class EnaApp : Application() {

    lateinit var state: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        // Страховочная сетка — любой краш пишется в файл и виден в Диагностике
        com.enamind.ai.core.CrashGuard.install(this)
        // Самодиагностика и реестр сбоев ENA
        com.enamind.ai.core.FaultMonitor.init(this)
        // Чистим оборванные .tmp файлы и протухший кэш до старта БД/Python
        com.enamind.ai.core.StorageGuard.sanitizeOnStartup(this)
        val db = AppDatabase.build(this)
        state = AppState(this, db)
        instance = this
        state.appScope.launch {
            com.enamind.ai.core.StorageGuard.pruneDatabase(db)
            state.engine.initVault()
        }
        // Python инициализируем в фоне: старт Chaquopy занимает 1-2 секунды
        state.appScope.launch { PythonBridge.init(this@EnaApp) }
    }

    companion object {
        @Volatile
        private var instance: EnaApp? = null

        fun get(): EnaApp = requireNotNull(instance) { "EnaApp not created" }
    }
}
