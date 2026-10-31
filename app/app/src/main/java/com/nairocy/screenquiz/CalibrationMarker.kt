package com.nairocy.screenquiz

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 校准准星：悬浮在百词斩之上的可拖动定位点（挂在无障碍服务上，不需要前台服务）。
 *
 * 视觉：双层描边圆环 + 四向十字，**中心留空**（能看见下面的按钮）；
 * 拖动只平移窗口，中心比例由 [centerRatio] 实时换算，主界面点「确定」时才写入 prefs。
 */
@SuppressLint("ViewConstructor")
class CalibrationMarker(
    private val ctx: Context,
    private val target: TapPoint,
    startFx: Double,
    startFy: Double
) : View(ctx) {

    private var startFx = startFx
    private var startFy = startFy

    var onMoved: (() -> Unit)? = null

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = resources.displayMetrics.density
    private val widthPx = dp(WIDTH_DP).toInt()
    private val heightPx = dp(HEIGHT_DP).toInt()
    private val ringPx = dp(RING_DP).toInt()

    private val params = WindowManager.LayoutParams(
        widthPx, heightPx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // ⚠️ 默认 fitTypes=STATUS_BARS|NAVIGATION_BARS 会让系统把窗口父容器压到状态栏下沿
        // （MEIZU 122px / Redmi 122px），params.y 与 dispatchGesture 的屏幕坐标就差了状态栏高度
        // → 准星看得见的位置比保存值低一截，点击落在准星上方。清零后 params 即物理屏幕坐标。
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(9.5f)
        color = Color.WHITE
        setShadowLayer(dp(2f), 0f, dp(1f), 0xCC000000.toInt())
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false

    fun show() {
        if (isAttachedToWindow) return
        val b = bounds()
        val targetX = b.left + b.width() * startFx
        val targetY = b.top + b.height() * startFy
        params.x = (targetX - widthPx / 2.0).roundToInt()
        params.y = (targetY - ringPx / 2.0).roundToInt()
        try {
            wm.addView(this, params)
            // 部分 ROM 无视 fitInsetsTypes 仍平移窗口 → 以「实际屏幕位置」二段校正到目标点
            post { alignCenterTo(targetX, targetY) }
            LogBus.log(
                "calib=marker_show target=${target.prefKey} x=${params.x} y=${params.y} " +
                    "screen=${b.width()}x${b.height()} fx=%.4f fy=%.4f".format(startFx, startFy)
            )
        } catch (t: Throwable) {
            LogBus.log("calib=marker_err ${t.javaClass.simpleName}")
        }
    }

    fun hide() {
        try {
            if (isAttachedToWindow) wm.removeView(this)
        } catch (_: Throwable) {
        }
    }

    /** 当前准星中心在整屏上的比例（按窗口在屏幕上的真实位置算，不吃 insets 偏移）。 */
    fun centerRatio(): Pair<Double, Double> {
        val b = bounds()
        val (cx, cy) = centerPx()
        val fx = ((cx - b.left) / b.width()).coerceIn(0.0, 1.0)
        val fy = ((cy - b.top) / b.height()).coerceIn(0.0, 1.0)
        return fx to fy
    }

    /** 准星中心在屏幕上的真实像素位置（getLocationOnScreen 含窗口实际偏移）。 */
    private fun centerPx(): Pair<Double, Double> {
        val loc = IntArray(2)
        if (isAttachedToWindow) {
            getLocationOnScreen(loc)
        } else {
            loc[0] = params.x
            loc[1] = params.y
        }
        return (loc[0] + widthPx / 2.0) to (loc[1] + ringPx / 2.0)
    }

    /** 把窗口挪到「中心落在 (cx,cy)」的位置（用实际屏幕位置算差量，兼容 insets 平移）。 */
    private fun alignCenterTo(cx: Double, cy: Double) {
        if (!isAttachedToWindow) return
        val (curX, curY) = centerPx()
        val dx = (cx - curX).roundToInt()
        val dy = (cy - curY).roundToInt()
        if (dx == 0 && dy == 0) return
        params.x += dx
        params.y += dy
        try {
            wm.updateViewLayout(this, params)
        } catch (_: Throwable) {
        }
        LogBus.log("calib=marker_align dx=$dx dy=$dy")
    }

    /** 像素级微调（手指拖动后再对缝用）。位移量在两个坐标系下等价，无需换算。 */
    fun nudge(dxPx: Int, dyPx: Int) {
        params.x += dxPx
        params.y += dyPx
        try {
            wm.updateViewLayout(this, params)
        } catch (_: Throwable) {
        }
        clampToScreen()
        invalidate()
        onMoved?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = ringPx / 2f
        val r = ringPx / 2f - dp(RING_STROKE_DP) - dp(2f)

        // 外环：黑底 + 白线（浅色/深色页面都看得见）
        ringPaint.color = 0x99000000.toInt()
        ringPaint.strokeWidth = dp(RING_STROKE_DP + 2.2f)
        canvas.drawCircle(cx, cy, r, ringPaint)
        ringPaint.color = 0xFFF2F5FA.toInt()
        ringPaint.strokeWidth = dp(RING_STROKE_DP)
        canvas.drawCircle(cx, cy, r, ringPaint)

        // 四向十字：从中心留空处向外，中心一圈留空以看见目标
        ringPaint.strokeWidth = dp(CROSS_STROKE_DP + 2.0f)
        ringPaint.color = 0x99000000.toInt()
        drawCross(canvas, cx, cy)
        ringPaint.strokeWidth = dp(CROSS_STROKE_DP)
        ringPaint.color = 0xFFF2F5FA.toInt()
        drawCross(canvas, cx, cy)

        // 中心点：极小，只用来对缝
        dotPaint.style = Paint.Style.FILL
        dotPaint.color = 0x99000000.toInt()
        canvas.drawCircle(cx, cy, dp(CENTER_DOT_DP + 1f), dotPaint)
        dotPaint.color = 0xFFF2F5FA.toInt()
        canvas.drawCircle(cx, cy, dp(CENTER_DOT_DP), dotPaint)

        // 实时百分比（定位时看趋势；精确值在主界面确认条上）
        val (fx, fy) = centerRatio()
        val label = String.format(Locale.US, "%.1f / %.1f", fx * 100, fy * 100)
        canvas.drawText(label, width / 2f, ringPx + dp(15f), labelPaint)
    }

    private fun drawCross(canvas: Canvas, cx: Float, cy: Float) {
        val inner = dp(CROSS_INNER_DP)
        val outer = dp(CROSS_OUTER_DP)
        canvas.drawLine(cx - outer, cy, cx - inner, cy, ringPaint)
        canvas.drawLine(cx + inner, cy, cx + outer, cy, ringPaint)
        canvas.drawLine(cx, cy - outer, cx, cy - inner, ringPaint)
        canvas.drawLine(cx, cy + inner, cx, cy + outer, ringPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = params.x
                startY = params.y
                dragged = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragged && (abs(dx) > dp(2f) || abs(dy) > dp(2f))) dragged = true
                if (dragged) {
                    params.x = (startX + dx).toInt()
                    params.y = (startY + dy).toInt()
                    try {
                        wm.updateViewLayout(this, params)
                    } catch (_: Throwable) {
                    }
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragged) {
                    clampToScreen()
                    onMoved?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    /** 隐藏后按新中心重新显示（测试点击用：窗口必须真正移除，否则注入的手势会被准星自己吞掉）。 */
    fun showAt(fx: Double, fy: Double) {
        startFx = fx
        startFy = fy
        show()
    }

    /** 测试点击期间让点击穿透准星（部分 ROM 上 flags 更新不及时，优先用 hide/showAt）。 */
    fun setPenetrable(on: Boolean) {
        params.flags = if (on) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try {
            wm.updateViewLayout(this, params)
        } catch (_: Throwable) {
        }
    }

    private fun clampToScreen() {
        if (!isAttachedToWindow) {
            val b = bounds()
            params.x = params.x.coerceIn(b.left, b.right - widthPx)
            params.y = params.y.coerceIn(b.top, b.bottom - heightPx)
            return
        }
        val b = bounds()
        val (cx, cy) = centerPx()
        val minX = b.left + widthPx / 2.0
        val maxX = b.right - widthPx / 2.0
        val minY = b.top.toDouble()   // 允许拖到屏幕最顶（上半圈可能被状态栏压住，靠数值/微调对缝）
        val maxY = b.bottom - (heightPx - ringPx / 2.0)
        val nx = cx.coerceIn(minX, maxX)
        val ny = cy.coerceIn(minY, maxY)
        if (nx != cx || ny != cy) alignCenterTo(nx, ny)
    }

    private fun bounds(): Rect {
        val b = wm.maximumWindowMetrics.bounds
        if (b.width() > 0 && b.height() > 0) return b
        // 极端兜底（不该走到）：退回 displayMetrics
        val dm = resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun dp(v: Float): Float = v * density

    companion object {
        private const val WIDTH_DP = 52f
        private const val HEIGHT_DP = 74f
        private const val RING_DP = 52f
        private const val RING_STROKE_DP = 1.2f
        private const val CROSS_STROKE_DP = 1.0f
        private const val CROSS_INNER_DP = 6f
        private const val CROSS_OUTER_DP = 17f
        private const val CENTER_DOT_DP = 1.3f
    }
}
