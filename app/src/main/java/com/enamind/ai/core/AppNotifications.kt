package com.enamind.ai.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.enamind.ai.MainActivity
import com.enamind.ai.orderflow.DataFreshness
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class AppNotifications(private val ctx: Context, private val app: AppState) {
    private val last = ConcurrentHashMap<String, Long>()
    private val started = AtomicBoolean(false)
    fun post(kind: String, title: String, text: String) {
        if (!app.settings.notificationEnabled(kind)) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val now = System.currentTimeMillis()
        if (!kind.startsWith("bot_") && now - (last[kind] ?: 0) < 10000) return
        last[kind] = now
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("enaverse_$kind", CHANNELS[kind] ?: kind, NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, kind.hashCode(), Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { nm.notify(kind.hashCode(), NotificationCompat.Builder(ctx, "enaverse_$kind")
            .setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).build()) }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        app.appScope.launch {
            var key = ""; var reference = 0.0; var absorption = false; var awo = ""; var stale = false
            var previousBotError: String? = null
            var lastClosedTrade = app.botEngine.uiState.value.recentTrades.lastOrNull()?.id
            while (isActive) {
                val instrument = app.activeInstrument.value
                val market = app.market.value; val flow = app.orderFlow.report.value; val core = app.enacore.state.value
                if (key != instrument.key) { key = instrument.key; reference = 0.0; absorption = false; awo = ""; stale = false }
                val price = market.price ?: 0.0
                if (price > 0 && reference > 0 && abs(price/reference-1) >= .01) {
                    post("price", "${instrument.symbol} · движение цены", "Изменение более 1% от предыдущей отметки")
                    reference = price
                } else if (reference == 0.0) reference = price
                if (flow.absorption.confirmed && !absorption && flow.freshness == DataFreshness.LIVE)
                    post("flow", "${instrument.symbol} · Order Flow", flow.absorption.side.labelRu)
                absorption = flow.absorption.confirmed
                if (core.verdict != awo && core.verdict.startsWith("CONFIRM") && core.isUsable(instrument, System.currentTimeMillis()))
                    post("core", "${instrument.symbol} · ${core.verdict}", core.reason)
                awo = core.verdict
                val nowStale = flow.freshness == DataFreshness.STALE || flow.freshness == DataFreshness.DESYNC
                if (nowStale && !stale) post("network", "${instrument.symbol} · потеря свежести", flow.freshnessWarning ?: "Проверьте сеть")
                stale = nowStale
                val bot = app.botEngine.uiState.value
                if (!bot.lastError.isNullOrBlank() && bot.lastError != previousBotError)
                    post("bot_stop", "Бот · ошибка", bot.lastError!!)
                previousBotError = bot.lastError
                bot.recentTrades.lastOrNull()?.let { trade ->
                    if (trade.id != lastClosedTrade &&
                        (trade.exitReason.contains("стоп", true) || trade.exitReason.contains("SL", true) || trade.exitReason.contains("трал", true)))
                        post("bot_stop", "${trade.symbol} · защитный выход", trade.exitReason)
                    lastClosedTrade = trade.id
                }
                delay(1000)
            }
        }
    }
    companion object {
        val CHANNELS = linkedMapOf("price" to "Цена и волатильность", "flow" to "Поглощения Order Flow",
            "bot_trade" to "Открытие и закрытие позиций", "bot_stop" to "Стопы и ошибки бота",
            "bot_timeout" to "Таймаут фоновой работы", "core" to "Сигналы AWO ENΛCORE", "network" to "Сеть и рассинхронизация")
    }
}
