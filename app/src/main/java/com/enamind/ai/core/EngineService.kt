package com.enamind.ai.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.enamind.ai.MainActivity

/**
 * Foreground-сервис фонового анализатора ENA.
 *
 * Поддерживает непрерывную сборку рыночных потоков Order Flow, проверку
 * исторических событий (1с/5с/15с/30с), 6ч/24ч контур и 15-дневный календарь.
 * Без WakeLock — экономия батареи важнее максимальной частоты циклов.
 */
class EngineService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Движок ENA",
                NotificationManager.IMPORTANCE_LOW // без звука: постоянный статус, не спам
            ).apply {
                description = "Постоянный статус ENA (реальное время + память 24ч)"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("ENA · анализ активен")
            .setContentText("Order Flow & Absorption · 6ч/24ч аналитика")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ena_engine"
        private const val NOTIFICATION_ID = 71
        private const val ACTION_STOP = "com.enamind.ai.ENGINE_STOP"

        /** Запуск фонового режима (из foreground-контекста приложения). */
        fun start(ctx: Context) {
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(Intent(ctx, EngineService::class.java))
                } else {
                    ctx.startService(Intent(ctx, EngineService::class.java))
                }
            } catch (_: Exception) {
                // не роняем приложение из-за сервиса (пользователь мог отозвать
                // разрешение уведомлений и т.п.) — движок продолжит в обычном режиме
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.startService(
                    Intent(ctx, EngineService::class.java).setAction(ACTION_STOP)
                )
            } catch (_: Exception) {
            }
        }
    }
}
