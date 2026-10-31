package com.nairocy.screenquiz

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * 打卡浮窗：中央显示「打卡」或已答题数，单击开关打卡引擎、拖动改位置、长按收起。
 * 与对战悬浮球完全独立（位置/开关各自存 prefs），可同时显示。
 */
@SuppressLint("ViewConstructor")
class DakaOverlay(private val ctx: Context) : View(ctx) {

    var onToggle: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    /** 引擎注入手势的截止时间：此刻之前到达的触摸一律忽略（防止自己点到自己）。 */
    @Volatile
    var ignoreTouchUntil = 0L

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = resources.displayMetrics.density
    private val ballPx = dp(BALL_DP).toInt()
    private val params = WindowManager.LayoutParams(
        ballPx, ballPx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // 与准星/对战球一致：不吃系统栏 insets，params 即物理屏幕坐标（否则整体下移状态栏高度）
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private var running = false
    private var answered = 0

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false
    private var longFired = false
    private var downAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val longRunnable = Runnable {
        longFired = true
        onClose?.invoke()
    }

    fun show() {
        if (isAttachedToWindow) return
        val dm = resources.displayMetrics
        val margin = dp(MARGIN_DP).toInt()
        val prefs = AppPrefs.get(ctx)
        val fx = prefs.getFloat(AppPrefs.KEY_DAKA_OVERLAY_X, -1f)
        val fy = prefs.getFloat(AppPrefs.KEY_DAKA_OVERLAY_Y, -1f)
        params.x = if (fx < 0f) dm.widthPixels - ballPx - margin else (dm.widthPixels * fx).toInt()
        params.y = if (fy < 0f) (dm.heightPixels * DEFAULT_FY).toInt() else (dm.heightPixels * fy).toInt()
        clampParams()
        try {
            wm.addView(this, params)
            LogBus.log("daka_overlay=show x=${params.x} y=${params.y}")
        } catch (t: Throwable) {
            LogBus.log("daka_overlay=add_err ${t.javaClass.simpleName}")
        }
    }

    fun hide() {
        try {
            if (isAttachedToWindow) wm.removeView(this)
        } catch (_: Throwable) {
        }
    }

    fun syncRunning(r: Boolean) {
        if (r == running) return
        running = r
        invalidate()
        LogBus.log("daka_overlay=state running=$running")
    }

    /** 同步已答题数（运行中显示在球中央）。 */
    fun syncCount(n: Int) {
        if (n == answered) return
        answered = n
        if (running) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val inset = dp(1f)
        val left = inset
        val top = inset
        val right = w - inset
        val bottom = h - inset

        fillPaint.color = ContextCompat.getColor(
            ctx,
            if (running) R.color.brick else R.color.surface_card
        )
        canvas.drawRect(left, top, right, bottom, fillPaint)
        strokePaint.color = ContextCompat.getColor(ctx, R.color.outline)
        canvas.drawRect(left, top, right, bottom, strokePaint)

        textPaint.color = ContextCompat.getColor(
            ctx,
            if (running) R.color.on_accent else R.color.text_primary
        )
        if (running) {
            textPaint.textSize = dp(DIGIT_DP)
            val fm = textPaint.fontMetrics
            val baseline = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(answered.toString(), cx, baseline, textPaint)
        } else {
            textPaint.textSize = dp(LABEL_DP)
            val fm = textPaint.fontMetrics
            val baseline = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText("打卡", cx, baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (SystemClock.uptimeMillis() < ignoreTouchUntil) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = params.x
                startY = params.y
                dragged = false
                longFired = false
                downAt = SystemClock.uptimeMillis()
                handler.postDelayed(longRunnable, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragged && (abs(dx) > dp(6f) || abs(dy) > dp(6f))) {
                    dragged = true
                    handler.removeCallbacks(longRunnable)
                }
                if (dragged) {
                    params.x = (startX + dx).toInt()
                    params.y = (startY + dy).toInt()
                    clampParams()
                    try {
                        wm.updateViewLayout(this, params)
                    } catch (_: Throwable) {
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longRunnable)
                if (dragged) {
                    savePosition()
                } else if (!longFired && SystemClock.uptimeMillis() - downAt < TAP_MAX_MS) {
                    onToggle?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longRunnable)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun clampParams() {
        val dm = resources.displayMetrics
        val m = dp(MARGIN_DP).toInt()
        params.x = params.x.coerceIn(m, dm.widthPixels - ballPx - m)
        params.y = params.y.coerceIn(m, dm.heightPixels - ballPx - m)
    }

    private fun savePosition() {
        val dm = resources.displayMetrics
        AppPrefs.get(ctx).edit()
            .putFloat(AppPrefs.KEY_DAKA_OVERLAY_X, params.x.toFloat() / dm.widthPixels)
            .putFloat(AppPrefs.KEY_DAKA_OVERLAY_Y, params.y.toFloat() / dm.heightPixels)
            .apply()
    }

    private fun dp(v: Float): Float = v * density

    companion object {
        private const val BALL_DP = 44f
        private const val LABEL_DP = 11f
        private const val DIGIT_DP = 18f
        private const val MARGIN_DP = 12f
        // 对战球默认 0.52，打卡球错开避免重叠
        private const val DEFAULT_FY = 0.62f
        private const val LONG_PRESS_MS = 450L
        private const val TAP_MAX_MS = 400L
    }
}
