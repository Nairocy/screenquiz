package com.nairocy.screenquiz

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 校准确认条（悬浮窗，挂在无障碍服务上 → 在百词斩页面上也可见可操作）。
 *
 * 与主界面里的卡片不同：它跨应用常驻，用户拖完准星不用切回本应用，直接点「确定」。
 */
@SuppressLint("ViewConstructor")
class CalibrationHud(
    private val ctx: Context,
    private val target: TapPoint,
    private val callbacks: Callbacks
) : LinearLayout(ctx) {

    interface Callbacks {
        fun onNudge(dx: Int, dy: Int)
        fun onTest()
        fun onConfirm()
        fun onCancel()
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = resources.displayMetrics.density
    private val params = WindowManager.LayoutParams(
        dp(320), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private val valueText: TextView
    private val actionViews = ArrayList<View>()

    // 拖动状态（按住本条空白处拖动，位置存比例，下次校准沿用）
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_hud)
        setPadding(dp(14), dp(10), dp(10), dp(10))
        isClickable = true

        valueText = TextView(ctx).apply {
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.accent))
        }

        val row1 = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row1.addView(
            ImageView(ctx).apply {
                setImageResource(R.drawable.ic_drag_handle)
                setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary))
                contentDescription = ctx.getString(R.string.calib_hud_drag)
                val s = dp(24)
                layoutParams = LayoutParams(s, s).apply { marginEnd = dp(4) }
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
        )
        row1.addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.calib_banner_title, ctx.getString(target.labelRes))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        row1.addView(valueText)
        row1.addView(
            ImageView(ctx).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary))
                contentDescription = ctx.getString(R.string.cancel)
                val s = dp(34)
                layoutParams = LayoutParams(s, s)
                setPadding(dp(9), dp(9), dp(9), dp(9))
                setOnClickListener { callbacks.onCancel() }
            }.also { actionViews.add(it) }
        )
        addView(row1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val row2 = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row2.addView(nudgeIcon(R.drawable.ic_nudge_left, R.string.calib_nudge_left, -STEP, 0))
        row2.addView(nudgeIcon(R.drawable.ic_nudge_right, R.string.calib_nudge_right, STEP, 0))
        row2.addView(nudgeIcon(R.drawable.ic_nudge_up, R.string.calib_nudge_up, 0, -STEP))
        row2.addView(nudgeIcon(R.drawable.ic_nudge_down, R.string.calib_nudge_down, 0, STEP))
        row2.addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.calib_nudge_short)
                textSize = 10f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding(dp(4), 0, dp(6), 0)
            }
        )
        row2.addView(android.view.View(ctx), LayoutParams(0, 1, 1f))
        val testBtn = action(R.string.calib_test, accent = false) { callbacks.onTest() }
        testBtn.setOnClickListener {
            callbacks.onTest()
            // 立即反馈：告诉用户这次确实发起了一次注入（命中与否看页面变化）
            testBtn.text = ctx.getString(R.string.calib_test_done)
            testBtn.postDelayed({ testBtn.text = ctx.getString(R.string.calib_test) }, 900L)
        }
        row2.addView(testBtn)
        row2.addView(action(R.string.confirm, accent = true) { callbacks.onConfirm() })
        addView(
            row2,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        )

        addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.calib_hud_hint)
                textSize = 11f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding(0, dp(4), 0, 0)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
    }

    fun show() {
        if (isAttachedToWindow) return
        val b = wm.maximumWindowMetrics.bounds
        params.width = (b.width() - dp(24)).coerceAtLeast(dp(240))
        val prefs = AppPrefs.get(ctx)
        val fx = prefs.getFloat(AppPrefs.KEY_HUD_X, -1f)
        val fy = prefs.getFloat(AppPrefs.KEY_HUD_Y, -1f)
        val saved = fx in 0f..1f && fy in 0f..1f
        if (saved) {
            params.x = (b.left + b.width() * fx).toInt()
            params.y = (b.top + b.height() * fy).toInt()
        } else {
            params.x = b.left + (b.width() - params.width) / 2
            params.y = b.top
        }
        try {
            wm.addView(this, params)
        } catch (t: Throwable) {
            LogBus.log("calib=hud_err ${t.javaClass.simpleName}")
            return
        }
        post {
            if (!saved) {
                // 首次显示贴底居中（高度要等测量完才知道）
                params.y = b.bottom - navBarHeight() - height - dp(16)
            }
            clamp()
            try {
                wm.updateViewLayout(this, params)
            } catch (_: Throwable) {
            }
            LogBus.log(
                "calib=hud_show target=${target.prefKey} x=${params.x} y=${params.y} saved=$saved"
            )
        }
    }

    fun hide() {
        try {
            if (isAttachedToWindow) wm.removeView(this)
        } catch (_: Throwable) {
        }
    }

    fun update(fx: Double, fy: Double) {
        valueText.text = ctx.getString(R.string.calib_value_fmt, fx * 100, fy * 100)
    }

    private fun nudgeIcon(res: Int, cd: Int, dx: Int, dy: Int): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            setColorFilter(ContextCompat.getColor(ctx, R.color.accent))
            contentDescription = ctx.getString(cd)
            val s = dp(30)
            layoutParams = LayoutParams(s, s).apply { marginEnd = dp(1) }
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_stepper)
            setOnClickListener { callbacks.onNudge(dx, dy) }
        }.also { actionViews.add(it) }

    private fun action(res: Int, accent: Boolean, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = ctx.getString(res)
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, if (accent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            setTextColor(
                ContextCompat.getColor(ctx, if (accent) R.color.accent else R.color.text_secondary)
            )
            setPadding(dp(9), dp(8), dp(9), dp(8))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_field)
            minimumHeight = dp(38)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }.also { actionViews.add(it) }

    /** 按住本条空白处拖动（按钮区域仍走正常点击）；位置存比例，下次校准沿用。 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = !hitAction(ev.rawX, ev.rawY)
                downRawX = ev.rawX
                downRawY = ev.rawY
                startX = params.x
                startY = params.y
                if (dragging) {
                    alpha = 0.92f
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> if (dragging) {
                params.x = (startX + ev.rawX - downRawX).toInt()
                params.y = (startY + ev.rawY - downRawY).toInt()
                clamp()
                try {
                    wm.updateViewLayout(this, params)
                } catch (_: Throwable) {
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                alpha = 1f
                savePosition()
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hitAction(rawX: Float, rawY: Float): Boolean {
        val loc = IntArray(2)
        for (v in actionViews) {
            v.getLocationOnScreen(loc)
            if (rawX >= loc[0] && rawX <= loc[0] + v.width &&
                rawY >= loc[1] && rawY <= loc[1] + v.height
            ) {
                return true
            }
        }
        return false
    }

    private fun clamp() {
        val b = wm.maximumWindowMetrics.bounds
        if (width <= 0 || height <= 0) return
        params.x = params.x.coerceIn(b.left, b.right - width)
        params.y = params.y.coerceIn(b.top, b.bottom - height)
    }

    private fun savePosition() {
        val b = wm.maximumWindowMetrics.bounds
        if (b.width() <= 0 || b.height() <= 0) return
        AppPrefs.get(ctx).edit()
            .putFloat(AppPrefs.KEY_HUD_X, params.x.toFloat() / b.width())
            .putFloat(AppPrefs.KEY_HUD_Y, params.y.toFloat() / b.height())
            .apply()
        LogBus.log("calib=hud_move x=${params.x} y=${params.y}")
    }

    private fun navBarHeight(): Int = try {
        wm.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
            .bottom
    } catch (_: Throwable) {
        0
    }

    private fun dp(v: Int): Int = (v * density).toInt()
    companion object {
        private const val STEP = 1
    }
}
