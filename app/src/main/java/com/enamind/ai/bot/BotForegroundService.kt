package com.enamind.ai.bot

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.enamind.ai.EnaApp
import com.enamind.ai.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground-сервис торгового бота ENΛVERSE (android:foregroundServiceType="dataSync").
 *
 * Гарантии надёжности:
 *  - Удерживает PARTIAL_WAKE_LOCK только пока бот запущен и освобождает его при остановке;
 *  - Обновляет постоянное уведомление текущим режимом, парой, Net PnL и Winrate %;
 *  - Имеет кнопку экстренной остановки и закрытия позиции прямо в шторке уведомлений;
 *  - Корректно обрабатывает Android 15 Service.onTimeout(startId, fgsType) для dataSync:
 *    отправляет высокоприоритетное уведомление «Сервис остановлен системой» и выполняет
 *    выбранную пользователем политику (оставить под защитой биржи или закрыть по рынку).
 */
class BotForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EMERGENCY_CLOSE -> {
                runCatching {
                    EnaApp.get().state.botEngine.stopAndCloseMarket("Остановлен из уведомления")
                }
                releaseWakeLock()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val initialState = runCatching { EnaApp.get().state.botEngine.uiState.value }.getOrElse { BotUiState() }
        val notif = buildOngoingNotification(initialState)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        startStateObserver()
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val botEngine = runCatching { EnaApp.get().state.botEngine }.getOrNull()
        val hasPos = botEngine?.uiState?.value?.activePosition != null
        val cfg = botEngine?.uiState?.value?.config ?: BotConfig()

        val body = when {
            !hasPos -> "Android 15 завершил 6-часовую сессию сервиса dataSync. Открытых позиций не было."
            cfg.timeoutBehavior == TimeoutBehavior.CLOSE_MARKET ->
                "Android 15 завершил фоновый сервис по таймауту (6ч). Позиция ${cfg.symbol} закрывается по рынку."
            else ->
                "Android 15 завершил фоновый сервис по таймауту (6ч). Позиция ${cfg.symbol} оставлена под защитой биржи."
        }
        EnaApp.get().state.notifications.post("bot_timeout", "Сервис остановлен системой", body)
        botEngine?.handleAndroid15ServiceTimeout()
        releaseWakeLock()
        stopSelf()
    }

    override fun onDestroy() {
        collectJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startStateObserver() {
        collectJob?.cancel()
        collectJob = serviceScope.launch {
            val engine = runCatching { EnaApp.get().state.botEngine }.getOrNull() ?: return@launch
            engine.uiState.collectLatest { st ->
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildOngoingNotification(st))
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "enamind:TradeBotWakeLock").apply {
                setReferenceCounted(false)
                // Лимит 6 часов — соответствует системному бюджету dataSync
                acquire(6L * 3600L * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun createChannels() {
        createChannels(this)
    }

    private fun buildOngoingNotification(st: BotUiState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, BotForegroundService::class.java).setAction(ACTION_EMERGENCY_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stats = st.activeModeStats
        val wrPart = if (stats.totalTrades > 0) "WR ${"%.0f%%".format(Locale.US, stats.winratePct)}" else "WR —"
        val pos = st.activePosition
        val title = "ENΛVERSE · Бот · ${st.config.mode.shortLabel} · ${st.config.symbol}"
        val text = if (pos != null) {
            "${pos.side.labelRu} ${String.format(Locale.US, "%+.2f%%", pos.netPnlPct)} (${String.format(Locale.US, "%+.2f", pos.netPnlUsdt)} USDT) · $wrPart"
        } else {
            "${st.statusText} · $wrPart"
        }

        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп и закрыть", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ONGOING = "ena_trade_bot"
        private const val CHANNEL_ALERTS = "ena_trade_bot_alerts"
        private const val NOTIFICATION_ID = 72
        private const val ALERT_NOTIFICATION_ID = 73
        private const val ACTION_STOP = "com.enamind.ai.BOT_STOP"
        private const val ACTION_EMERGENCY_CLOSE = "com.enamind.ai.BOT_STOP_CLOSE"

        fun createChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ONGOING,
                    "Торговый бот ENΛVERSE",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Постоянный статус работы бота, пары, PnL и Winrate"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Критические уведомления бота ENΛVERSE",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Уведомления об остановке сервиса Android 15 и закрытии позиций"
                }
            )
        }

        fun postHighPriorityAlert(ctx: Context, title: String, body: String) {
            runCatching {
                createChannels(ctx)
                val openIntent = PendingIntent.getActivity(
                    ctx,
                    12,
                    Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val n = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(openIntent)
                    .build()
                val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(ALERT_NOTIFICATION_ID, n)
            }
        }

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, BotForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, BotForegroundService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
