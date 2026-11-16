package com.enamind.ai.bot

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.enamind.ai.MainActivity
import java.util.Locale
import kotlin.math.abs

/**
 * Плавающий HUD-индикатор торгового бота поверх других окон (SYSTEM_ALERT_WINDOW).
 *
 * По требованиям пользователя:
 *  - Можно включить заранее ДО запуска бота для подготовки;
 *  - Режим отображения: «Только при сворачивании (поверх других окон)» (по умолчанию) или «Всегда»;
 *  - Всегда отображает активную пару, режим, статус / Net PnL и Winrate %;
 *  - Подстраивается под дизайн ENA (тёмная обсидиановая капсула со светящимся переливом по контуру);
 *  - Цвет свечения жёстко зависит от Winrate % активного режима:
 *      < 50%     -> красный (#EF4444)
 *      50%..65%  -> жёлтый  (#F59E0B)
 *      > 65%     -> зелёный (#10B981)
 *      нет сделок -> нейтральный (#64748B)
 */
class BotFloatingOverlayManager(
    private val ctx: Context,
    private val onStopAndCloseRequested: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager? by lazy {
        ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    private var rootContainer: GlowingHudContainer? = null
    private var pairTextView: TextView? = null
    private var statusTextView: TextView? = null
    private var winrateBadgeView: TextView? = null
    private var actionsRow: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var lastState: BotUiState = BotUiState()
    private var appInForeground: Boolean = true
    private var expanded: Boolean = false

    fun setAppForeground(inForeground: Boolean) {
        if (appInForeground == inForeground) return
        appInForeground = inForeground
        mainHandler.post { syncVisibilityAndContent() }
    }

    fun updateState(state: BotUiState) {
        lastState = state
        mainHandler.post { syncVisibilityAndContent() }
    }

    fun hideNow() {
        mainHandler.post { detachOverlay() }
    }

    private fun syncVisibilityAndContent() {
        val cfg = lastState.config
        val shouldShow = cfg.hudEnabled &&
            canDrawOverlays(ctx) &&
            (cfg.hudDisplayMode == HudDisplayMode.ALWAYS || !appInForeground)

        if (!shouldShow) {
            detachOverlay()
            return
        }
        ensureAttached()
        bindState(lastState)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureAttached() {
        if (rootContainer != null) return
        val wm = windowManager ?: return

        val container = GlowingHudContainer(ctx).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvPair = TextView(ctx).apply {
            setTextColor(0xFFECEEF2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            maxLines = 1
        }
        val tvWr = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
            maxLines = 1
        }
        topRow.addView(tvPair)
        topRow.addView(tvWr)

        val tvStatus = TextView(ctx).apply {
            setTextColor(0xFF949BA8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setPadding(0, dp(2), 0, 0)
            maxLines = 1
        }

        val rowActions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = if (expanded) View.VISIBLE else View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        val btnOpen = TextView(ctx).apply {
            text = "ENA"
            setTextColor(0xFF8AB4F8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setOnClickListener {
                expanded = false
                actionsRow?.visibility = View.GONE
                val launch = Intent(ctx, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                runCatching { ctx.startActivity(launch) }
            }
        }
        val btnStop = TextView(ctx).apply {
            text = "СТОП И ЗАКРЫТЬ"
            setTextColor(0xFFEF4444.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(5), dp(4), dp(5))
            setOnClickListener {
                expanded = false
                actionsRow?.visibility = View.GONE
                onStopAndCloseRequested()
            }
        }
        rowActions.addView(btnOpen)
        rowActions.addView(btnStop)

        col.addView(topRow)
        col.addView(tvStatus)
        col.addView(rowActions)
        container.addView(col)

        val typeFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(96)
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        container.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX).toInt()
                    val dy = (ev.rawY - touchY).toInt()
                    if (abs(dx) > 6 || abs(dy) > 6) moved = true
                    lp.x = (startX - dx).coerceAtLeast(0)
                    lp.y = (startY + dy).coerceAtLeast(dp(24))
                    runCatching { wm.updateViewLayout(container, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        expanded = !expanded
                        rowActions.visibility = if (expanded) View.VISIBLE else View.GONE
                    }
                    true
                }
                else -> false
            }
        }

        val attached = runCatching {
            wm.addView(container, lp)
            true
        }.getOrDefault(false)

        if (attached) {
            rootContainer = container
            pairTextView = tvPair
            statusTextView = tvStatus
            winrateBadgeView = tvWr
            actionsRow = rowActions
            layoutParams = lp
            container.startGlow()
        }
    }

    private fun bindState(st: BotUiState) {
        val stats = st.activeModeStats
        val zone = stats.glowZone
        val glowColor = resolveHudGlowColorInt(stats)
        val pos = st.activePosition

        pairTextView?.text = "${st.config.symbol} · ${st.config.mode.shortLabel}"
        winrateBadgeView?.apply {
            text = if (stats.totalTrades > 0) {
                "WR ${"%.0f%%".format(Locale.US, stats.winratePct)}"
            } else {
                "WR —"
            }
            setTextColor(glowColor)
        }

        val statusTxt = when {
            pos != null -> "${pos.side.labelRu} ${String.format(Locale.US, "%+.2f%%", pos.netPnlPct)} (${String.format(Locale.US, "%+.2f", pos.netPnlUsdt)} $)"
            st.running -> "ПОИСК ВХОДА · ${st.config.leverage}x"
            else -> "ГОТОВ К ЗАПУСКУ"
        }
        statusTextView?.text = statusTxt
        rootContainer?.setGlowAppearance(glowColor, isStrong = zone != HudGlowZone.NEUTRAL && st.running)
    }

    private fun detachOverlay() {
        val c = rootContainer ?: return
        c.stopGlow()
        runCatching { windowManager?.removeViewImmediate(c) }
        rootContainer = null
        pairTextView = null
        statusTextView = null
        winrateBadgeView = null
        actionsRow = null
        layoutParams = null
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private class GlowingHudContainer(ctx: Context) : FrameLayout(ctx) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEE0C0E14.toInt()
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.8f, ctx.resources.displayMetrics)
        }
        private val radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, ctx.resources.displayMetrics)
        private val rect = RectF()
        private var glowColor: Int = COLOR_NEUTRAL
        private var sweepFraction: Float = 0.2f
        private var animator: ValueAnimator? = null

        init {
            setWillNotDraw(false)
        }

        fun setGlowAppearance(color: Int, isStrong: Boolean) {
            glowColor = color
            borderPaint.strokeWidth = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                if (isStrong) 2.2f else 1.6f,
                resources.displayMetrics
            )
            invalidate()
        }

        fun startGlow() {
            if (animator != null) return
            animator = ValueAnimator.ofFloat(0.1f, 0.9f).apply {
                duration = 2200L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    sweepFraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopGlow() {
            animator?.cancel()
            animator = null
        }

        override fun onDraw(canvas: Canvas) {
            val inset = borderPaint.strokeWidth / 2f + 1f
            rect.set(inset, inset, width - inset, height - inset)
            canvas.drawRoundRect(rect, radius, radius, bgPaint)

            val spot = width * sweepFraction
            val span = (width * 0.45f).coerceAtLeast(60f)
            val dim = (glowColor and 0x00FFFFFF) or 0x55000000
            val bright = (glowColor and 0x00FFFFFF) or 0xFF000000.toInt()
            borderPaint.shader = LinearGradient(
                (spot - span).coerceAtLeast(0f),
                0f,
                (spot + span).coerceAtMost(width.toFloat().coerceAtLeast(1f)),
                height.toFloat(),
                intArrayOf(dim, bright, dim),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, borderPaint)
            super.onDraw(canvas)
        }
    }

    companion object {
        const val COLOR_RED: Int = 0xFFEF4444.toInt()
        const val COLOR_YELLOW: Int = 0xFFF59E0B.toInt()
        const val COLOR_GREEN: Int = 0xFF10B981.toInt()
        const val COLOR_NEUTRAL: Int = 0xFF64748B.toInt()

        fun resolveHudGlowColorInt(stats: ModeWinrateStats): Int = when (stats.glowZone) {
            HudGlowZone.RED -> COLOR_RED
            HudGlowZone.YELLOW -> COLOR_YELLOW
            HudGlowZone.GREEN -> COLOR_GREEN
            HudGlowZone.NEUTRAL -> COLOR_NEUTRAL
        }

        fun canDrawOverlays(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true

        fun openOverlayPermissionSettings(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
        }
    }
}
