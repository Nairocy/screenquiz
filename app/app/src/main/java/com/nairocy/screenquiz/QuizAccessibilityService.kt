package com.nairocy.screenquiz

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.random.Random

class QuizAccessibilityService : AccessibilityService(), UiOps {

    private val handler = Handler(Looper.getMainLooper())
    private var readDueSince = 0L
    private val readRunnable = Runnable {
        readDueSince = 0L
        readAndDispatch()
    }
    private val pageReader by lazy { PageReader(EcdictDb.get(this)) }
    private var overlay: OverlayBall? = null
    private var calibMarker: CalibrationMarker? = null
    private var calibHud: CalibrationHud? = null
    private var calibTarget: TapPoint? = null
    private var readCount = 0L
    private var readSumMs = 0L
    private var readMaxMs = 0L
    val engine by lazy {
        Engine(this, EcdictDb.get(this), UserDict.get(this), onStopped = { reason ->
            if (reason !in KEEP_RUNNING_REASONS) {
                AppPrefs.get(this).edit().putBoolean(AppPrefs.KEY_ENGINE_RUNNING, false).apply()
            }
        }).also { e ->
            e.onProgress = { syncOverlayRemaining() }
            // 三个画布坐标点走校准值（未校准＝默认常量）
            e.tapPoint = { point -> point.resolve(AppPrefs.get(this)) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val previous = instance
        if (previous != null && previous !== this) {
            previous.engine.stop(STOP_SERVICE_REPLACED)
        }
        instance = this
        val prefs = AppPrefs.get(this)
        if (prefs.getBoolean(AppPrefs.KEY_ENGINE_RUNNING, false)) {
            val mode = if (prefs.getBoolean(AppPrefs.KEY_AUTO_MODE, false)) {
                Engine.Mode.AUTO
            } else {
                Engine.Mode.OBSERVE
            }
            applySettings()
            engine.start(mode)
        }
        syncOverlay()
        warmUp()
        handler.postDelayed(readRunnable, DEBOUNCE_MS)
    }

    /** 预热词表（首次装机要解包 4.7MB asset，别让首题吃冷启动开销）。 */
    private fun warmUp() {
        Thread {
            try {
                EcdictDb.get(this).lookup(WARMUP_WORD)
                UserDict.get(this).count()
            } catch (_: Throwable) {
            }
        }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        overlay?.syncRunning(engine.running)
        scheduleRead(DEBOUNCE_MS)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(readRunnable)
    }

    override fun onDestroy() {
        if (engine.running) engine.stop(STOP_SERVICE_DESTROY)
        stopCalibration(save = false)
        overlay?.hide()
        overlay = null
        readDueSince = 0L
        handler.removeCallbacks(readRunnable)
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---------- 悬浮球 ----------

    /** 按偏好与权限显示/隐藏悬浮球；主界面改动开关后调用。 */
    fun syncOverlay() {
        val on = AppPrefs.get(this).getBoolean(AppPrefs.KEY_OVERLAY, true)
        if (!on || !Settings.canDrawOverlays(this)) {
            overlay?.hide()
            overlay = null
            LogBus.log("overlay=hidden on=$on")
            return
        }
        if (overlay == null) {
            val ball = OverlayBall(this)
            ball.onToggle = { toggleEngine() }
            ball.onLongPress = { openApp() }
            overlay = ball
            ball.show()
        }
        overlay?.syncRunning(engine.running)
        syncOverlayRemaining()
    }

    /** 同步悬浮球中央的剩余对局数（非运行态显示计划局数）。 */
    fun syncOverlayRemaining() {
        val remaining = if (engine.running) engine.remainingRounds else AppPrefs.maxRounds(this)
        overlay?.syncRemaining(remaining)
    }

    // ---------- 点击位置校准 ----------

    /** 当前校准会话（null＝没在摆位）：目标 + 准星中心的整屏比例。 */
    fun calibrationSnapshot(): Triple<TapPoint, Double, Double>? {
        val target = calibTarget ?: return null
        val marker = calibMarker ?: return null
        val (fx, fy) = marker.centerRatio()
        return Triple(target, fx, fy)
    }

    /**
     * 起一次校准：准星出现在该点当前生效坐标处（已校准值或默认值）。
     * 引擎必须在停止态——跑局中改坐标会让本局状态错乱。
     */
    fun startCalibration(target: TapPoint): Boolean {
        if (engine.running) return false
        if (!Settings.canDrawOverlays(this)) return false
        stopCalibration(save = false)
        val (fx, fy) = target.resolve(AppPrefs.get(this))
        val marker = CalibrationMarker(this, target, fx, fy)
        calibTarget = target
        calibMarker = marker
        marker.onMoved = { syncCalibHud() }
        marker.show()
        val hud = CalibrationHud(this, target, object : CalibrationHud.Callbacks {
            override fun onNudge(dx: Int, dy: Int) = nudgeCalibration(dx, dy)

            override fun onTest() {
                testCalibrationTap()
            }

            override fun onConfirm() = stopCalibration(save = true)

            override fun onCancel() = stopCalibration(save = false)
        })
        calibHud = hud
        hud.show()
        syncCalibHud()
        LogBus.log("calib=start target=${target.prefKey} fx=%.3f fy=%.3f".format(fx, fy))
        return true
    }

    /** 像素级微调准星（悬浮确认条上的方向键）。 */
    fun nudgeCalibration(dxPx: Int, dyPx: Int) {
        calibMarker?.nudge(dxPx, dyPx)
        syncCalibHud()
    }

    /** 「试一下」：按当前准星中心真点一次（不保存），直接验证是否命中目标按钮。 */
    fun testCalibrationTap(): Boolean {
        val marker = calibMarker ?: return false
        val b = screenBounds()
        val (fx, fy) = marker.centerRatio()
        val px = b.left + (b.width() * fx).toInt()
        val py = b.top + (b.height() * fy).toInt()
        // 只改 FLAG_NOT_TOUCHABLE 时 relayout 异步生效，注入的手势常被准星自己吞掉（真机实测十次九次没响应）
        // → 直接把准星窗口移除，点完再放回原中心
        marker.hide()
        handler.postDelayed({
            val ok = tap(px, py)
            LogBus.log("calib=test px=$px py=$py ok=$ok")
            handler.postDelayed({ calibMarker?.showAt(fx, fy) }, 350L)
        }, 120L)
        return true
    }

    private fun syncCalibHud() {
        val marker = calibMarker ?: return
        val (fx, fy) = marker.centerRatio()
        calibHud?.update(fx, fy)
    }

    /** 结束校准；save=true 时把准星中心写成该点的新比例。 */
    fun stopCalibration(save: Boolean) {
        val target = calibTarget
        val marker = calibMarker
        if (save && target != null && marker != null) {
            val (fx, fy) = marker.centerRatio()
            target.save(AppPrefs.get(this), fx, fy)
            val b = screenBounds()
            LogBus.log(
                "calib=save target=${target.prefKey} fx=%.4f fy=%.4f px=%d py=%d".format(
                    fx, fy, (b.width() * fx).toInt(), (b.height() * fy).toInt()
                )
            )
        } else if (target != null) {
            LogBus.log("calib=cancel target=${target.prefKey}")
        }
        marker?.hide()
        calibHud?.hide()
        calibHud = null
        calibMarker = null
        calibTarget = null
    }

    /** 从偏好应用对战类型与循环局数上限。 */
    private fun applySettings() {
        engine.battleType = AppPrefs.battleType(this)
        engine.maxRounds = AppPrefs.maxRounds(this)
    }

    private fun toggleEngine() {
        if (calibTarget != null) {
            // 校准中不许起引擎：注入的手势会砸在准星旁，且摆位还没确认
            LogBus.log("calib=busy_toggle")
            return
        }
        if (engine.running) {
            engine.stop("user")
        } else {
            val mode = if (AppPrefs.get(this).getBoolean(AppPrefs.KEY_AUTO_MODE, false)) {
                Engine.Mode.AUTO
            } else {
                Engine.Mode.OBSERVE
            }
            AppPrefs.get(this).edit().putBoolean(AppPrefs.KEY_ENGINE_RUNNING, true).apply()
            engine.setMode(mode)
            applySettings()
            engine.start(mode)
        }
        overlay?.syncRunning(engine.running)
        syncOverlayRemaining()
        LogBus.log("overlay=toggle running=${engine.running}")
    }

    private fun openApp() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * 事件驱动的读页调度：按 DEBOUNCE_MS 防抖，但整体推迟不超过 READ_DEFER_CAP_MS——
     * 事件风暴（动画/计时器持续刷新）下旧实现会把读页无限推后，答题被拖慢。
     */
    private fun scheduleRead(delayMs: Long) {
        val now = SystemClock.uptimeMillis()
        if (readDueSince == 0L) readDueSince = now
        val capLeft = readDueSince + READ_DEFER_CAP_MS - now
        val delay = minOf(delayMs, capLeft).coerceAtLeast(0L)
        handler.removeCallbacks(readRunnable)
        handler.postDelayed(readRunnable, delay)
    }

    private fun readAndDispatch() {
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != TARGET_PACKAGE) {
            return
        }
        val t0 = SystemClock.uptimeMillis()
        val snapshot = try {
            pageReader.read(root)
        } catch (t: Throwable) {
            LogBus.log("state=${PageState.UNKNOWN.name} err=read_${t.javaClass.simpleName}")
            return
        }
        perfRead(SystemClock.uptimeMillis() - t0)
        try {
            if (engine.running) {
                engine.onPage(snapshot)
            } else {
                LogBus.log(snapshot.logLine())
            }
        } catch (t: Throwable) {
            LogBus.log("state=${snapshot.state.name} err=engine_${t.javaClass.simpleName}")
        }
    }

    /** 读页耗时埋点：每 PERF_EVERY_READS 次读输出一行均值/峰值。 */
    private fun perfRead(ms: Long) {
        readCount++
        readSumMs += ms
        if (ms > readMaxMs) readMaxMs = ms
        if (readCount % PERF_EVERY_READS == 0L) {
            LogBus.log("perf reads=$readCount avg_ms=${readSumMs / readCount} max_ms=$readMaxMs")
        }
    }

    // ---------- UiOps ----------

    override fun requestRead() {
        readDueSince = 0L
        handler.removeCallbacks(readRunnable)
        handler.postDelayed(readRunnable, 0L)
    }

    override fun postDelayed(delayMs: Long, runnable: Runnable) {
        handler.postDelayed(runnable, delayMs)
    }

    override fun removeCallbacks(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }

    override fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, text) ?: return false
        val container = findClickableContainer(root, node)
        if (container != null) return container.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        // 无障碍树退化时（WebView 虚拟节点丢 clickable）取不到容器 → 直接点文本自身中心
        val b = Rect()
        node.getBoundsInScreen(b)
        if (b.isEmpty) return false
        LogBus.log("click=text_fallback xy=${b.centerX()},${b.centerY()}")
        return tap(b.centerX(), b.centerY())
    }

    override fun clickOption(option: OptionNode): Boolean {
        // 手势优先：真机实测选项行不响应 ACTION_CLICK，坐标注入可靠
        val box = option.containerBounds ?: option.bounds
        val x = box.centerX() + JITTER.nextInt(-JITTER_PX, JITTER_PX + 1)
        val y = box.centerY() + JITTER.nextInt(-JITTER_PX, JITTER_PX + 1)
        val byGesture = tap(x, y)
        LogBus.log("click=gesture xy=$x,$y box=${box.toShortString()} ok=$byGesture")
        if (byGesture) return true
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, option.text) ?: return false
        val container = findClickableContainer(root, node) ?: return false
        val byAction = container.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        LogBus.log("click=action ok=$byAction")
        return byAction
    }

    override fun clickRatio(x: Double, y: Double): Boolean {
        // 与校准准星共用同一坐标系（整屏最大窗口，含系统栏），否则校准会与点击错位
        val b = screenBounds()
        return tap(
            b.left + (b.width() * x).toInt(),
            b.top + (b.height() * y).toInt()
        )
    }

    private fun screenBounds(): Rect {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = wm.maximumWindowMetrics.bounds
        if (b.width() > 0 && b.height() > 0) return b
        val dm = resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    override fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    // ---------- helpers ----------

    private fun findNodeByText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (norm(node.text) == text) return node
        for (i in 0 until node.childCount) {
            val found = findNodeByText(node.getChild(i), text)
            if (found != null) return found
        }
        return null
    }

    private fun findClickableContainer(
        root: AccessibilityNodeInfo,
        target: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val targetBounds = Rect()
        target.getBoundsInScreen(targetBounds)
        var best: AccessibilityNodeInfo? = null
        var bestOverlap = 0L
        var bestArea = Long.MAX_VALUE
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isClickable) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    val overlap = overlapArea(bounds, targetBounds)
                    val area = bounds.width().toLong() * bounds.height().toLong()
                    if (overlap > bestOverlap || (overlap == bestOverlap && overlap > 0 && area < bestArea)) {
                        best = node
                        bestOverlap = overlap
                        bestArea = area
                    }
                }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
            }
        }
        walk(root)
        return best
    }

    private fun norm(cs: CharSequence?): String =
        cs?.toString()?.replace(INVISIBLE_RE, "")?.trim() ?: ""

    private fun overlapArea(a: Rect, b: Rect): Long {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (w > 0 && h > 0) w.toLong() * h.toLong() else 0L
    }

    private fun tap(x: Int, y: Int): Boolean {
        // 注入期间让悬浮球忽略触摸：贴着球的误点不会误触开关
        overlay?.ignoreTouchUntil = SystemClock.uptimeMillis() + INJECT_GUARD_MS
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val DEBOUNCE_MS = 60L
        private const val READ_DEFER_CAP_MS = 200L
        private const val PERF_EVERY_READS = 20L
        private const val WARMUP_WORD = "warmup"
        private const val TARGET_PACKAGE = "com.jiongji.andriod.card"
        private const val JITTER_PX = 6
        private const val TAP_DURATION_MS = 60L
        private const val INJECT_GUARD_MS = 250L
        private const val STOP_SERVICE_DESTROY = "service_destroy"
        private const val STOP_SERVICE_REPLACED = "service_replaced"
        private val KEEP_RUNNING_REASONS = setOf(STOP_SERVICE_DESTROY, STOP_SERVICE_REPLACED)

        private val JITTER = Random.Default

        private val INVISIBLE_RE = Regex("[\\u200b-\\u200f\\u202a-\\u202e\\ufeff]")

        @Volatile
        var instance: QuizAccessibilityService? = null
            private set
    }
}
