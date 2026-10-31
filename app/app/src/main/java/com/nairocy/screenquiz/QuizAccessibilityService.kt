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
import android.widget.Toast
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
    private var dakaOverlay: DakaOverlay? = null
    @Volatile private var lastWindowClass: String? = null
    private var calibMarker: CalibrationMarker? = null
    private var calibHud: CalibrationHud? = null
    private var calibTarget: TapPoint? = null
    // 多点校准：一次摆 N 个准星（打卡的 4 个选项块）
    private val multiTargets = ArrayList<TapPoint>()
    private val multiMarkers = ArrayList<CalibrationMarker>()
    private var multiHud: MultiCalibrationHud? = null
    // 打卡引擎自带轮询：Engine 的轮询只在对战运行态跑；打卡不主动催就收不到帧
    // （学习页静止时无障碍事件不来，DakaEngine 会一直等着）
    private var dakaOffTargetStreak = 0
    private val dakaPollRunnable = object : Runnable {
        override fun run() {
            if (!dakaEngine.running) return
            readAndDispatch()
            handler.postDelayed(this, DAKA_POLL_MS)
        }
    }
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

    /** 打卡词表持久化：逗号分隔，空串＝空表。 */
    private val prefsStore = object : DakaStore {
        override fun loadQueue(): List<String> =
            AppPrefs.get(this@QuizAccessibilityService)
                .getString(AppPrefs.KEY_DAKA_QUEUE, "")
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        override fun saveQueue(queue: List<String>) {
            AppPrefs.get(this@QuizAccessibilityService).edit()
                .putString(AppPrefs.KEY_DAKA_QUEUE, queue.joinToString(","))
                .apply()
        }

        override fun clearQueue() {
            AppPrefs.get(this@QuizAccessibilityService).edit()
                .putString(AppPrefs.KEY_DAKA_QUEUE, "")
                .apply()
        }
    }

    val dakaEngine: DakaEngine by lazy {
        DakaEngine(
            this,
            { word -> EcdictDb.get(this).lookup(word) },
            prefsStore,
            onStopped = { reason ->
                AppPrefs.get(this).edit().putBoolean(AppPrefs.KEY_DAKA_RUNNING, false).apply()
                handler.removeCallbacks(dakaPollRunnable)
                dakaOffTargetStreak = 0
                dakaOverlay?.syncRunning(false)
                LogBus.log("daka=stop reason=$reason")
            }
        ).also { e ->
            e.onProgress = { dakaOverlay?.syncCount(dakaEngine.answered) }
            // 打卡的「继续」与选项块兜底坐标走校准值（与对战引擎同款注入）
            e.tapPoint = { point -> point.resolve(AppPrefs.get(this)) }
            e.screenSize = {
                val b = screenBounds()
                b.width() to b.height()
            }
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
        // 打卡有真实学习副作用：服务重连不自动恢复，只把残留的运行标记清掉
        if (prefs.getBoolean(AppPrefs.KEY_DAKA_RUNNING, false)) {
            prefs.edit().putBoolean(AppPrefs.KEY_DAKA_RUNNING, false).apply()
        }
        if (prefs.getBoolean(AppPrefs.KEY_ENGINE_RUNNING, false)) {
            applySettings()
            engine.start(Engine.Mode.AUTO)
        }
        syncOverlay()
        syncDakaOverlay()
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
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowClass = event.className?.toString()
        }
        overlay?.syncRunning(engine.running)
        scheduleRead(DEBOUNCE_MS)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(readRunnable)
        handler.removeCallbacks(dakaPollRunnable)
    }

    override fun onDestroy() {
        if (engine.running) engine.stop(STOP_SERVICE_DESTROY)
        dakaEngine.stop(STOP_SERVICE_DESTROY)
        handler.removeCallbacks(dakaPollRunnable)
        stopCalibration(save = false)
        stopMultiCalibration(save = false)
        overlay?.hide()
        overlay = null
        dakaOverlay?.hide()
        dakaOverlay = null
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

    /** 按偏好与权限显示/隐藏打卡浮窗；主界面点击入口后调用。 */
    fun syncDakaOverlay() {
        val on = AppPrefs.get(this).getBoolean(AppPrefs.KEY_DAKA_OVERLAY, false)
        if (!on || !Settings.canDrawOverlays(this)) {
            dakaOverlay?.hide()
            dakaOverlay = null
            LogBus.log("daka_overlay=hidden on=$on")
            return
        }
        if (dakaOverlay == null) {
            val ball = DakaOverlay(this)
            ball.onToggle = { toggleDakaEngine() }
            ball.onClose = { closeDakaOverlay() }
            dakaOverlay = ball
            ball.show()
        }
        dakaOverlay?.syncRunning(dakaEngine.running)
        dakaOverlay?.syncCount(dakaEngine.answered)
    }

    /** 长按收起打卡浮窗：关偏好，下次进主界面不会自动弹回。 */
    private fun closeDakaOverlay() {
        AppPrefs.get(this).edit().putBoolean(AppPrefs.KEY_DAKA_OVERLAY, false).apply()
        dakaOverlay?.hide()
        dakaOverlay = null
        LogBus.log("daka_overlay=close")
    }

    // ---------- 点击位置校准 ----------

    /** 当前校准会话（null＝没在摆位）：目标 + 准星中心的整屏比例。 */
    fun calibrationSnapshot(): Triple<TapPoint, Double, Double>? {
        val target = calibTarget ?: return null
        val marker = calibMarker ?: return null
        val (fx, fy) = marker.centerRatio()
        return Triple(target, fx, fy)
    }

    /** 是否有校准会话在进行（单点或多点）：起引擎前统一拦一道。 */
    fun isCalibrating(): Boolean = calibTarget != null || multiMarkers.isNotEmpty()

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

    /**
     * 起一次多点校准：每个目标摆一个准星，确认时统一保存。
     * 与单点校准、两个引擎互斥；失败时返回 false 并记日志。
     */
    fun startMultiCalibration(targets: List<TapPoint>): Boolean {
        if (engine.running || dakaEngine.running) {
            LogBus.log("calib=busy_running")
            return false
        }
        if (!Settings.canDrawOverlays(this)) {
            LogBus.log("calib=no_overlay")
            return false
        }
        if (targets.isEmpty()) return false
        // 清掉可能残留的单点/多点会话
        stopCalibration(save = false)
        stopMultiCalibration(save = false)

        val prefs = AppPrefs.get(this)
        for (target in targets) {
            val (fx, fy) = target.resolve(prefs)
            val marker = CalibrationMarker(this, target, fx, fy)
            multiTargets.add(target)
            multiMarkers.add(marker)
            marker.show()
        }
        val hud = MultiCalibrationHud(this, targets.size, object : MultiCalibrationHud.Callbacks {
            override fun onConfirm() = stopMultiCalibration(save = true)

            override fun onCancel() = stopMultiCalibration(save = false)
        })
        multiHud = hud
        hud.show()
        LogBus.log(
            "calib=multi_start n=${targets.size} " +
                "targets=${targets.joinToString(",") { it.prefKey }}"
        )
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
        // 单点与多点不共存：单点收尾时顺手清掉可能残留的多点会话
        if (multiMarkers.isNotEmpty() || multiHud != null) stopMultiCalibration(save = false)
    }

    /** 结束多点校准；save=true 时逐个把准星中心写成该点的新比例。 */
    private fun stopMultiCalibration(save: Boolean) {
        if (multiMarkers.isEmpty() && multiHud == null) return
        if (save) {
            val prefs = AppPrefs.get(this)
            val b = screenBounds()
            for (i in multiTargets.indices) {
                val target = multiTargets[i]
                val marker = multiMarkers[i]
                val (fx, fy) = marker.centerRatio()
                target.save(prefs, fx, fy)
                LogBus.log(
                    "calib=multi_save target=${target.prefKey} fx=%.4f fy=%.4f px=%d py=%d"
                        .format(fx, fy, (b.width() * fx).toInt(), (b.height() * fy).toInt())
                )
            }
            Toast.makeText(
                this,
                getString(R.string.calib_multi_saved_fmt, multiTargets.size),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            LogBus.log("calib=multi_cancel n=${multiMarkers.size}")
        }
        for (marker in multiMarkers) marker.hide()
        multiMarkers.clear()
        multiTargets.clear()
        multiHud?.hide()
        multiHud = null
    }

    /** 从偏好应用对战类型与循环局数上限。 */
    private fun applySettings() {
        engine.battleType = AppPrefs.battleType(this)
        engine.maxRounds = AppPrefs.maxRounds(this)
    }

    private fun toggleEngine() {
        if (dakaEngine.running) {
            // D5 互斥：打卡与对战同一时刻只允许一个运行
            LogBus.log("battle=busy_daka")
            return
        }
        if (isCalibrating()) {
            // 校准中不许起引擎：注入的手势会砸在准星旁，且摆位还没确认
            LogBus.log("calib=busy_toggle")
            return
        }
        if (engine.running) {
            engine.stop("user")
        } else {
            AppPrefs.get(this).edit().putBoolean(AppPrefs.KEY_ENGINE_RUNNING, true).apply()
            applySettings()
            engine.start(Engine.Mode.AUTO)
        }
        overlay?.syncRunning(engine.running)
        syncOverlayRemaining()
        LogBus.log("overlay=toggle running=${engine.running}")
    }

    /** 打卡浮窗单击：启动/停止打卡；与对战、校准互斥。 */
    private fun toggleDakaEngine() {
        if (engine.running) {
            LogBus.log("daka=busy_battle")
            return
        }
        if (isCalibrating()) {
            LogBus.log("calib=busy_toggle")
            return
        }
        if (dakaEngine.running) {
            dakaEngine.stop("user")
        } else {
            AppPrefs.get(this).edit().putBoolean(AppPrefs.KEY_DAKA_RUNNING, true).apply()
            dakaEngine.start()
            dakaOffTargetStreak = 0
            handler.removeCallbacks(dakaPollRunnable)
            handler.postDelayed(dakaPollRunnable, 0L)
        }
        dakaOverlay?.syncRunning(dakaEngine.running)
        dakaOverlay?.syncCount(dakaEngine.answered)
        LogBus.log("daka=toggle running=${dakaEngine.running}")
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
     * 打卡离页兜底：不在百词斩前台时 readAndDispatch 早退，DakaEngine 收不到帧，
     * 只靠它自己数帧永远停不了 → 这里按轮询次数判离页，约 15s 后停手。
     */
    private fun trackDakaOffTarget() {
        if (!dakaEngine.running) {
            dakaOffTargetStreak = 0
            return
        }
        dakaOffTargetStreak++
        if (dakaOffTargetStreak == 1) LogBus.log("daka=off_target waiting")
        if (dakaOffTargetStreak >= DAKA_OFF_TARGET_LIMIT) {
            dakaOffTargetStreak = 0
            dakaEngine.stop("left_app")
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
            // 不在百词斩页：DakaEngine 一帧都收不到，靠它自己数帧永远停不下来 → 这里补离页计数
            trackDakaOffTarget()
            return
        }
        dakaOffTargetStreak = 0
        val t0 = SystemClock.uptimeMillis()
        val snapshot = try {
            pageReader.read(root, lastWindowClass)
        } catch (t: Throwable) {
            LogBus.log("state=${PageState.UNKNOWN.name} err=read_${t.javaClass.simpleName}")
            return
        }
        perfRead(SystemClock.uptimeMillis() - t0)
        try {
            if (dakaEngine.running) {
                dakaEngine.onPage(snapshot)
            } else if (engine.running) {
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
        private const val DAKA_POLL_MS = 250L          // 打卡轮询周期（与对战 poll 同量级）
        private const val DAKA_OFF_TARGET_LIMIT = 60   // ≈15s：不在百词斩前台就算离页，停手
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
