package com.nairocy.screenquiz

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.min

/**
 * 悬浮球：中央显示剩余对局数，单击开关引擎、拖动改位置、长按打开主界面。
 * 挂在无障碍服务进程里（该进程由系统常驻，不需要前台服务）。
 */
@SuppressLint("ViewConstructor")
class OverlayBall(private val ctx: Context) : View(ctx) {

    var onToggle: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

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
        // 与准星一致：不吃系统栏 insets，params 即物理屏幕坐标（否则球的位置会整体下移状态栏高度）
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = dp(DIGIT_DP)
    }
    private val path = Path()

    private var running = false

    /** 剩余对局数（null＝尚未同步，退回旧版图形指示）。 */
    private var remaining: Int? = null

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
        onLongPress?.invoke()
    }

    fun show() {
        if (isAttachedToWindow) return
        val dm = resources.displayMetrics
        val margin = dp(MARGIN_DP).toInt()
        val prefs = AppPrefs.get(ctx)
        val fx = prefs.getFloat(AppPrefs.KEY_OVERLAY_X, -1f)
        val fy = prefs.getFloat(AppPrefs.KEY_OVERLAY_Y, -1f)
        params.x = if (fx < 0f) dm.widthPixels - ballPx - margin else (dm.widthPixels * fx).toInt()
        params.y = if (fy < 0f) (dm.heightPixels * DEFAULT_FY).toInt() else (dm.heightPixels * fy).toInt()
        clampParams()
        try {
            wm.addView(this, params)
            LogBus.log("overlay=show x=${params.x} y=${params.y}")
        } catch (t: Throwable) {
            LogBus.log("overlay=add_err ${t.javaClass.simpleName}")
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
        LogBus.log("overlay=state running=$running")
    }

    /** 同步剩余对局数（显示在球中央）。 */
    fun syncRemaining(n: Int) {
        if (n == remaining) return
        remaining = n
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f - dp(1.5f)

        bgPaint.style = Paint.Style.FILL
        bgPaint.color = if (running) COLOR_ON else COLOR_OFF
        bgPaint.alpha = ALPHA
        canvas.drawCircle(cx, cy, r, bgPaint)

        bgPaint.style = Paint.Style.STROKE
        bgPaint.strokeWidth = dp(1.5f)
        bgPaint.color = Color.WHITE
        bgPaint.alpha = 0x55
        canvas.drawCircle(cx, cy, r, bgPaint)

        val rem = remaining
        if (rem != null) {
            // 中央：剩余对局数（运行中亮白，停止时略暗）
            val text = rem.toString()
            digitPaint.color = Color.WHITE
            digitPaint.alpha = if (running) 0xFF else 0xCC
            val tx = cx - digitPaint.measureText(text) / 2f
            val ty = cy - (digitPaint.descent() + digitPaint.ascent()) / 2f
            canvas.drawText(text, tx, ty, digitPaint)
        } else {
            // 尚未同步到数字：手绘描边图形（运行中=停止方块；已停止=播放三角）
            glyphPaint.color = Color.WHITE
            path.reset()
            if (running) {
                val s = dp(7f)
                path.addRoundRect(RectF(cx - s, cy - s, cx + s, cy + s), dp(2.5f), dp(2.5f), Path.Direction.CW)
            } else {
                val s = dp(8f)
                path.moveTo(cx - s * 0.7f, cy - s)
                path.lineTo(cx + s * 0.9f, cy)
                path.lineTo(cx - s * 0.7f, cy + s)
                path.close()
            }
            canvas.drawPath(path, glyphPaint)
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
            .putFloat(AppPrefs.KEY_OVERLAY_X, params.x.toFloat() / dm.widthPixels)
            .putFloat(AppPrefs.KEY_OVERLAY_Y, params.y.toFloat() / dm.heightPixels)
            .apply()
    }

    private fun dp(v: Float): Float = v * density

    companion object {
        private const val BALL_DP = 44f
        private const val DIGIT_DP = 18f
        private const val MARGIN_DP = 12f
        private const val DEFAULT_FY = 0.52f
        private const val LONG_PRESS_MS = 450L
        private const val TAP_MAX_MS = 400L
        private const val ALPHA = 0xD8
        private const val COLOR_ON = 0xFF2E9E5B.toInt()
        private const val COLOR_OFF = 0xFF5A6472.toInt()
    }
}
