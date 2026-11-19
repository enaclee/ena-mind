package com.enamind.ai.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.enamind.ai.EnaApp
import com.enamind.ai.MainActivity
import com.enamind.ai.core.Settings.Theme
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.*

/** Independent Android windows; no Activity/View lifecycle or network ownership. */
class WidgetOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windows = linkedMapOf<String, FloatingWindow>()
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val prefs by lazy { getSharedPreferences("enaverse_widgets", MODE_PRIVATE) }
    private val app get() = EnaApp.get().state

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            while (isActive) {
                windows.values.forEach { it.refresh() }
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "close_all") { windows.values.toList().forEach { it.detach() }; windows.clear(); save(); stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Плавающие виджеты", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 81, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val close = PendingIntent.getService(this, 82, Intent(this, WidgetOverlayService::class.java).setAction("close_all"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ENΛVERSE · плавающие виджеты").setContentText("Окна поверх приложений")
            .setContentIntent(open).setOngoing(true).addAction(0, "Закрыть окна", close).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(81, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(81, notification)
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        app.start()
        if (windows.isEmpty()) restore()
        val kind = intent?.getStringExtra("kind")
        if (kind in KINDS.keys && windows.size < 8) addWindow(JSONObject().put("kind", kind).put("id", java.util.UUID.randomUUID().toString()))
        save()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        save()
        app.toast("Фоновая сессия виджетов завершена Android. Расположение сохранено.")
        stopSelf()
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        windows.values.forEach { it.bounds() }; save()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { save(); windows.values.toList().forEach { it.detach() }; windows.clear(); scope.cancel(); super.onDestroy() }

    private fun restore() {
        runCatching {
            val a = JSONArray(prefs.getString("windows", "[]"))
            for (i in 0 until min(8, a.length())) addWindow(a.getJSONObject(i))
        }
    }
    private fun addWindow(o: JSONObject) {
        if (o.optString("kind") !in KINDS) return
        val id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
        if (id in windows) return
        runCatching { FloatingWindow(id, o).also { it.attach(); windows[id] = it } }
            .onFailure { app.toast("Не удалось открыть виджет: проверьте разрешение поверх окон") }
    }
    private fun save() { prefs.edit().putString("windows", JSONArray().also { a -> windows.values.forEach { a.put(it.json()) } }.toString()).apply() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class FloatingWindow(val id: String, o: JSONObject) {
        val kind = o.optString("kind")
        var contentWidth = o.optInt("w", dp(290)).coerceAtLeast(dp(200))
        var contentHeight = o.optInt("h", dp(280)).coerceAtLeast(dp(180))
        var angle = o.optDouble("angle", 0.0).toFloat().takeIf { it.isFinite() } ?: 0f
        var opacity = o.optDouble("opacity", .95).toFloat().coerceIn(.35f, 1f)
        val root = FrameLayout(this@WidgetOverlayService)
        val column = LinearLayout(this@WidgetOverlayService).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        val body = WidgetCanvas(kind).apply { matrixShort = o.optBoolean("short", false) }
        val title = TextView(this@WidgetOverlayService).apply { textSize = 11f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL }
        val panel = LinearLayout(this@WidgetOverlayService).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val lp = WindowManager.LayoutParams(contentWidth, contentHeight,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = o.optInt("x", dp(16)); y = o.optInt("y", dp(80)) }

        init {
            val top = LinearLayout(this@WidgetOverlayService)
            top.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
            top.addView(button("⚙") { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
            top.addView(button("×") { detach(); windows.remove(id); save(); if (windows.isEmpty()) stopSelf() })
            column.addView(top)
            val angleLabel = TextView(this@WidgetOverlayService).apply { text = "Поворот 0°–360°"; setTextColor(Color.GRAY); textSize = 11f }
            panel.addView(angleLabel)
            panel.addView(SeekBar(this@WidgetOverlayService).apply {
                max = 360; progress = angle.toInt()
                setOnSeekBarChangeListener(seek { angle = it.toFloat(); angleLabel.text = "Поворот $it°"; bounds(); save() })
            })
            val snaps = LinearLayout(this@WidgetOverlayService)
            listOf(0, 90, 270).forEach { value -> snaps.addView(button(if (value == 270) "−90°" else "$value°") { angle = value.toFloat(); bounds(); save() }) }
            panel.addView(snaps)
            panel.addView(TextView(this@WidgetOverlayService).apply { text = "Прозрачность"; setTextColor(Color.GRAY); textSize = 11f })
            panel.addView(SeekBar(this@WidgetOverlayService).apply {
                max = 65; progress = ((opacity - .35f) * 100).toInt()
                setOnSeekBarChangeListener(seek { opacity = .35f + it / 100f; column.alpha = opacity; save() })
            })
            if (kind == "matrix") panel.addView(button("Long / Short") { body.matrixShort = !body.matrixShort; body.invalidate(); save() }.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(48)) })
            column.addView(panel)
            column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            val resize = TextView(this@WidgetOverlayService).apply { text = "↘"; textSize = 22f; gravity = Gravity.END; contentDescription = "Изменить размер виджета"; setTextColor(Color.GRAY) }
            column.addView(resize, LinearLayout.LayoutParams(-1, dp(40)))
            root.addView(column, FrameLayout.LayoutParams(contentWidth, contentHeight, Gravity.CENTER))
            touch(title, false); touch(resize, true)
            column.rotation = angle; column.alpha = opacity
        }
        private fun button(label: String, action: () -> Unit) = TextView(this@WidgetOverlayService).apply {
            text = label; gravity = Gravity.CENTER; textSize = 16f; setTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)); setOnClickListener { action() }
        }
        private fun seek(action: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(v: SeekBar?, p: Int, user: Boolean) { if (user) action(p) }
            override fun onStartTrackingTouch(v: SeekBar?) {}
            override fun onStopTrackingTouch(v: SeekBar?) { save() }
        }
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        private fun touch(view: View, resize: Boolean) {
            var startX = 0f; var startY = 0f; var initialX = 0; var initialY = 0; var initialW = 0; var initialH = 0
            view.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startX = e.rawX; startY = e.rawY; initialX = lp.x; initialY = lp.y; initialW = contentWidth; initialH = contentHeight; true }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX-startX; val dy = e.rawY-startY
                        if (resize) {
                            val radians = Math.toRadians(angle.toDouble())
                            contentWidth = (initialW + dx*cos(radians) + dy*sin(radians)).toInt().coerceAtLeast(dp(200))
                            contentHeight = (initialH - dx*sin(radians) + dy*cos(radians)).toInt().coerceAtLeast(dp(180))
                        } else { lp.x = initialX+dx.toInt(); lp.y = initialY+dy.toInt() }
                        bounds(); true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { save(); true }
                    else -> false
                }
            }
        }
        fun attach() { bounds(false); wm.addView(root, lp); refresh() }
        fun detach() { runCatching { wm.removeView(root) } }
        fun bounds(update: Boolean = true) {
            val screen = resources.displayMetrics
            val rad = Math.toRadians(angle.toDouble()); val cs = abs(cos(rad)); val sn = abs(sin(rad))
            val scale = min(1.0, min(screen.widthPixels / (contentWidth*cs+contentHeight*sn), (screen.heightPixels-dp(32)) / (contentWidth*sn+contentHeight*cs)))
            contentWidth = (contentWidth*scale).toInt(); contentHeight = (contentHeight*scale).toInt()
            lp.width = ceil(contentWidth*cs+contentHeight*sn).toInt(); lp.height = ceil(contentWidth*sn+contentHeight*cs).toInt()
            lp.x = lp.x.coerceIn(0, (screen.widthPixels-lp.width).coerceAtLeast(0))
            lp.y = lp.y.coerceIn(0, (screen.heightPixels-lp.height-dp(24)).coerceAtLeast(0))
            column.layoutParams = FrameLayout.LayoutParams(contentWidth, contentHeight, Gravity.CENTER)
            column.rotation = angle
            if (update) runCatching { wm.updateViewLayout(root, lp) }
        }
        fun refresh() {
            val settings = app.settings
            val dark = settings.theme == Theme.BLACK || (settings.theme == Theme.RGB && settings.rgbDarkBase)
            val accent = if (settings.theme == Theme.RGB) settings.accentColor else 0xFF8AB4F8.toInt()
            column.background = GradientDrawable().apply { setColor(if (dark) 0xF20C0E14.toInt() else 0xF2FFFFFF.toInt()); cornerRadius = dp(16).toFloat(); setStroke(dp(2), accent) }
            title.setTextColor(if (dark) Color.WHITE else Color.BLACK)
            title.text = "ENΛVERSE · ${app.activeInstrument.value.symbol}\n${KINDS[kind]}"
            body.dark = dark; body.accent = accent; body.invalidate()
        }
        fun json() = JSONObject().put("id", id).put("kind", kind).put("x", lp.x).put("y", lp.y)
            .put("w", contentWidth).put("h", contentHeight).put("angle", angle).put("opacity", opacity).put("short", body.matrixShort)
    }

    private inner class WidgetCanvas(private val kind: String) : View(this@WidgetOverlayService) {
        var dark = true; var accent = Color.CYAN; var matrixShort = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            paint.textSize = dp(11).toFloat(); paint.typeface = Typeface.MONOSPACE
            paint.color = if (dark) Color.WHITE else Color.BLACK
            val core = app.enacore.state.value; val market = app.market.value
            fun line(text: String, n: Int, color: Int = if (dark) Color.WHITE else Color.BLACK) {
                paint.color = color; c.drawText(text, 4f, dp(17*(n+1)).toFloat(), paint)
            }
            when (kind) {
                "chart" -> {
                    val bars = app.klines.value.takeLast(36)
                    if (bars.isEmpty()) { line("Ожидание свечей", 0); return }
                    val low = bars.minOf { it[3] }; val high = bars.maxOf { it[2] }; val span = (high-low).coerceAtLeast(1e-9)
                    val w = width.toFloat()/bars.size
                    fun y(v: Double) = (height-8-(v-low)/span*(height-24)).toFloat()
                    bars.forEachIndexed { i, b ->
                        paint.color = if (b[4] >= b[1]) 0xFF10B981.toInt() else 0xFFEF4444.toInt()
                        val x = (i+.5f)*w; paint.strokeWidth = 2f
                        c.drawLine(x, y(b[2]), x, y(b[3]), paint)
                        c.drawRect(x-w*.3f, min(y(b[1]), y(b[4])), x+w*.3f, max(y(b[1]), y(b[4]))+1, paint)
                    }
                }
                "matrix" -> {
                    line("${if (matrixShort) "SHORT" else "LONG"} · Net | 15  30  60  120м", 0, accent)
                    (if (matrixShort) core.shortCells else core.longCells).chunked(4).forEachIndexed { i, row -> line("${row.first().target}%  " + row.joinToString(" ") { it.probability?.let { p -> "${(p*100).toInt()}%" } ?: "—" }, i+1) }
                    line(core.verdict, 7, accent)
                }
                "agents" -> core.agents.forEachIndexed { i, a ->
                    paint.color = if (a.confidence == 0.0) Color.GRAY else if (a.score >= 0) 0xFF10B981.toInt() else 0xFFEF4444.toInt()
                    val x = if (i % 2 == 0) 3f else width/2f
                    c.drawText(a.name.take(14), x, dp(28*(i/2)+13).toFloat(), paint)
                    c.drawText(if (a.confidence == 0.0) "—" else "${(a.score*100).toInt()}%", x, dp(28*(i/2)+25).toFloat(), paint)
                }
                "book" -> {
                    line(market.price?.toString() ?: "Нет цены", 0, accent)
                    line("BID                 ASK", 1)
                    for (i in 0 until 5) line("${market.bids.getOrNull(i)?.price ?: "—"}   ${market.asks.getOrNull(i)?.price ?: "—"}", i+2)
                }
                "bot" -> {
                    val bot = app.botEngine.uiState.value
                    line(bot.config.mode.shortLabel, 0, accent); line(bot.config.symbol, 1)
                    line(bot.statusText.take(34), 2)
                    line("Net ${String.format(Locale.US, "%.2f", bot.activePosition?.netPnlUsdt ?: 0.0)} USDT", 3)
                    line("Winrate ${bot.activeModeStats.winratePct.toInt()}%", 4)
                }
            }
        }
    }

    companion object {
        private const val CHANNEL = "enaverse_widgets"
        val KINDS = linkedMapOf("chart" to "Свечной график", "matrix" to "Матрица ENΛCORE", "agents" to "12 агентов", "book" to "Тикер и стакан", "bot" to "HUD бота")
        fun show(ctx: Context, kind: String) {
            if (!Settings.canDrawOverlays(ctx)) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                EnaApp.get().state.toast("Разрешите показ поверх приложений и снова нажмите нужный виджет")
                return
            }
            val intent = Intent(ctx, WidgetOverlayService::class.java).putExtra("kind", kind)
            runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent) }
                .onFailure { EnaApp.get().state.toast("Android не разрешил фоновый запуск виджетов") }
        }
    }
}
