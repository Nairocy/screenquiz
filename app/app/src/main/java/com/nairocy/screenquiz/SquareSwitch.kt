package com.nairocy.screenquiz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * 方正主义方形开关：2dp 墨边方框 + 18dp 方块滑块，选中时滑块滑到右侧。
 *
 * 为什么不用 Material Switch：它是圆形 thumb，是整套方正主义里唯一的圆角件。
 * 本控件全程 drawRect，颜色从资源取（自动跟随深浅主题）。
 *
 * 注意：**程序里设置 [isChecked] 不会触发 [onCheckedChanged]**，
 * 只有用户点击才会回调 —— 避免初始化时把「恢复上次状态」当成一次用户操作。
 */
class SquareSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 开关状态；程序赋值静默生效。 */
    var isChecked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animateTo(if (value) 1f else 0f)
            invalidate()
        }

    /** 仅用户点击时回调。 */
    var onCheckedChanged: ((Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 滑块位置 0（左）~1（右），由动画驱动。 */
    private var progress = 0f
    private var animator: android.animation.ValueAnimator? = null

    private var downX = 0f
    private var downY = 0f
    private var cancelled = false

    init {
        isClickable = true
        // 不抢焦点：ScrollView 里一个可聚焦控件被点击后，系统可能把它滚进可视区 → 整页跳一下
        isFocusable = false
    }

    // 尺寸由布局的 46dp × 26dp 决定，不需要自定义测量（少一层测量逻辑，减少布局抖动风险）

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = dp(1f)
        val track = android.graphics.RectF(inset, inset, w - inset, h - inset)
        val checked = progress > 0.5f

        // 轨道
        trackPaint.style = Paint.Style.FILL
        trackPaint.color = ContextCompat.getColor(
            context,
            if (checked) R.color.brick else R.color.surface_inset
        )
        canvas.drawRect(track, trackPaint)

        // 墨边
        strokePaint.color = ContextCompat.getColor(context, R.color.outline)
        canvas.drawRect(track, strokePaint)

        // 滑块（18dp 方块）
        val thumbSize = dp(18f)
        val pad = dp(3f)
        val travel = (w - pad * 2 - thumbSize).coerceAtLeast(0f)
        val left = pad + travel * progress
        val top = (h - thumbSize) / 2f
        thumbPaint.color = ContextCompat.getColor(
            context,
            if (checked) R.color.paper else R.color.text_secondary
        )
        canvas.drawRect(
            android.graphics.RectF(left, top, left + thumbSize, top + thumbSize),
            thumbPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                cancelled = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // 手指移出控件范围较远 → 视为取消
                if (event.x < -dp(12f) || event.x > width + dp(12f) ||
                    event.y < -dp(12f) || event.y > height + dp(12f)
                ) {
                    cancelled = true
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!cancelled) {
                    isChecked = !isChecked
                    onCheckedChanged?.invoke(isChecked)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelled = true
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        // 尊重系统「动画缩放」设置：关掉动画时直接到终态
        val scale = try {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        } catch (_: Throwable) {
            1f
        }
        if (scale == 0f) {
            progress = target
            return
        }
        animator = android.animation.ValueAnimator.ofFloat(progress, target).apply {
            duration = DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun dp(v: Float): Float = v * density

    companion object {
        private const val DURATION_MS = 160L
    }
}
