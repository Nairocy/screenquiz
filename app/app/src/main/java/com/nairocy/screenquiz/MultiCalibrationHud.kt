package com.nairocy.screenquiz

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
 * 多点校准确认条（悬浮窗）：一次摆 N 个准星，统一确认保存。
 *
 * 与单点 [CalibrationHud] 的区别：没有微调/试一下，只保留「确定/取消」；
 * 默认停靠屏幕顶部——四个选项块与「继续」都在屏幕下半部，停在底部会挡住准星。
 */
@SuppressLint("ViewConstructor")
class MultiCalibrationHud(
    private val ctx: Context,
    private val count: Int,
    private val callbacks: Callbacks
) : LinearLayout(ctx) {

    interface Callbacks {
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
        // 与准星同一坐标系：不清 insets 会让窗口被系统下压一个状态栏高度
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

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

        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            ImageView(ctx).apply {
                setImageResource(R.drawable.ic_drag_handle)
                setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary))
                contentDescription = ctx.getString(R.string.calib_hud_drag)
                val s = dp(24)
                layoutParams = LayoutParams(s, s).apply { marginEnd = dp(4) }
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
        )
        row.addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.calib_multi_title, count)
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            action(R.string.confirm, accent = true) { callbacks.onConfirm() }
        )
        row.addView(
            ImageView(ctx).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary))
                contentDescription = ctx.getString(R.string.cancel)
                val s = dp(34)
                layoutParams = LayoutParams(s, s)
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_stepper)
                setPadding(dp(9), dp(9), dp(9), dp(9))
                setOnClickListener { callbacks.onCancel() }
            }.also { actionViews.add(it) }
        )
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.calib_multi_hint)
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
        val fx = prefs.getFloat(AppPrefs.KEY_MULTI_HUD_X, -1f)
        val fy = prefs.getFloat(AppPrefs.KEY_MULTI_HUD_Y, -1f)
        val saved = fx in 0f..1f && fy in 0f..1f
        if (saved) {
            params.x = (b.left + b.width() * fx).toInt()
            params.y = (b.top + b.height() * fy).toInt()
        } else {
            params.x = b.left + (b.width() - params.width) / 2
            // 默认贴顶，避开屏幕下半部的 4 个选项块与「继续」
            params.y = b.top + statusBarHeight() + dp(12)
        }
        try {
            wm.addView(this, params)
        } catch (t: Throwable) {
            LogBus.log("calib=multi_hud_err ${t.javaClass.simpleName}")
            return
        }
        post {
            clamp()
            try {
                wm.updateViewLayout(this, params)
            } catch (_: Throwable) {
            }
            LogBus.log("calib=multi_hud_show x=${params.x} y=${params.y} saved=$saved")
        }
    }

    fun hide() {
        try {
            if (isAttachedToWindow) wm.removeView(this)
        } catch (_: Throwable) {
        }
    }

    private fun action(res: Int, accent: Boolean, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = ctx.getString(res)
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(
                typeface,
                if (accent) Typeface.BOLD else Typeface.NORMAL
            )
            setTextColor(
                ContextCompat.getColor(ctx, if (accent) R.color.on_accent else R.color.text_secondary)
            )
            setPadding(dp(9), dp(8), dp(9), dp(8))
            background = if (accent) {
                solidBox(R.color.brick)
            } else {
                ContextCompat.getDrawable(ctx, R.drawable.bg_stepper)
            }
            minimumHeight = dp(38)
            setOnClickListener { onClick() }
        }.also { actionViews.add(it) }

    private fun solidBox(colorRes: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(ContextCompat.getColor(ctx, colorRes))
        setStroke(dp(2), ContextCompat.getColor(ctx, R.color.outline))
    }

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
            .putFloat(AppPrefs.KEY_MULTI_HUD_X, params.x.toFloat() / b.width())
            .putFloat(AppPrefs.KEY_MULTI_HUD_Y, params.y.toFloat() / b.height())
            .apply()
        LogBus.log("calib=multi_hud_move x=${params.x} y=${params.y}")
    }

    private fun statusBarHeight(): Int = try {
        wm.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
            .top
    } catch (_: Throwable) {
        0
    }

    private fun dp(v: Int): Int = (v * density).toInt()
}
