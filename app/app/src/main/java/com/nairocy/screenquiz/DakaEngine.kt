package com.nairocy.screenquiz

import android.os.SystemClock

/** 词表持久化出口（生产用 SharedPreferences，单测用内存 fake）。 */
interface DakaStore {
    fun loadQueue(): List<String>
    fun saveQueue(queue: List<String>)
    fun clearQueue()
}

/**
 * PLAN-daka §4.2：每日单词打卡状态机。
 * 只负责学习会话页自动作答，识别到结束页立即停手（绝不点「完成今日计划」/「打卡」）。
 */
class DakaEngine(
    private val ui: UiOps,
    private val lookup: (String) -> String?,
    private val store: DakaStore,
    private val onStopped: ((String) -> Unit)? = null
) {

    enum class Mode { AUTO }

    @Volatile
    var running: Boolean = false
        private set

    var answered: Int = 0
        private set

    var onProgress: (() -> Unit)? = null

    /** 校准坐标解析器（Service 注入；为空时用内置常量，单测不注入也能跑）。 */
    var tapPoint: ((TapPoint) -> Pair<Double, Double>)? = null

    /** 屏幕像素尺寸（Service 注入；仅用于判断节点 bounds 是否已落到屏幕外）。 */
    var screenSize: (() -> Pair<Int, Int>)? = null

    // 本轮词表：① 新学的词按顺序入队；③ 英文选项靠它锚定正确答案
    private val queue = LinkedHashSet<String>()
    // ③ 英文题已消费的队列词（多个候选时按队列顺序取下一个）
    private val reviewedWords = HashSet<String>()

    // 题内幂等与补点
    private val clickedKeys = HashSet<String>()
    private val abandonedKeys = HashSet<String>()
    private val countedKeys = HashSet<String>()
    private val clickAttempts = HashMap<String, Int>()
    private val lastTarget = HashMap<String, OptionNode>()
    private val lastIndex = HashMap<String, Int>()
    // 详解页幂等（同一单词只点一次「继续」）
    private val detailDone = HashSet<String>()

    private var currentKey: String? = null
    private var readyKey: String? = null
    private var questionSeenAt = 0L
    private var settleRunnable: Runnable? = null
    private val pendingRetaps = HashMap<String, Runnable>()
    private val pendingRunnables = ArrayList<Runnable>()

    private var firstFrameSeen = false
    private var noAnswerStreak = 0        // 判定失败的重读计数（转场帧容忍，别一帧判死）
    private var lastDetailAt = 0L         // 详解页最近一次点击时刻（时间冷却）
    private var nonStudyStreak = 0
    private var unknownStreak = 0
    private var runStartedAt = 0L

    fun start() {
        if (running) return
        reset()
        // 允许跨次续用：上次非 done 停止保留的队列在这里读回
        for (word in store.loadQueue()) {
            val trimmed = word.trim()
            if (trimmed.isEmpty()) continue
            if (queue.any { it.equals(trimmed, ignoreCase = true) }) continue
            queue.add(trimmed)
        }
        running = true
        runStartedAt = SystemClock.uptimeMillis()
        LogBus.log("daka=start queue=${queue.size}")
        notifyProgress()
    }

    fun stop(reason: String) {
        if (!running) return
        running = false
        clearPending()
        // 本轮结束才清词表；中断（leave_study / no_answer / limit 等）保留，下次续用
        if (reason == REASON_DONE) {
            queue.clear()
            store.clearQueue()
        }
        LogBus.log("daka=stop reason=$reason answered=$answered queue=${queue.size}")
        onStopped?.invoke(reason)
        notifyProgress()
    }

    fun onPage(snapshot: PageSnapshot) {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        if (runStartedAt != 0L && now - runStartedAt > MAX_RUN_MS) {
            LogBus.log("daka=stop reason=limit ms=${now - runStartedAt}")
            stop(REASON_LIMIT)
            return
        }
        // 首帧保护（D3）：从③开始说明缺①的词表，不猜，直接请求用户从第一题开始
        if (!firstFrameSeen) {
            firstFrameSeen = true
            if (snapshot.state == PageState.STUDY_QUESTION &&
                snapshot.options.isNotEmpty() &&
                snapshot.options.all { EN_RE.matches(it.text.trim()) } &&
                queue.isEmpty()
            ) {
                LogBus.log("daka=fail reason=no_queue hint=从学习页第一题开始")
                stop(REASON_NO_QUEUE)
                return
            }
        }

        when (snapshot.state) {
            PageState.STUDY_QUESTION -> {
                resetStreaks()
                handleQuestion(snapshot)
            }
            PageState.STUDY_DETAIL -> {
                resetStreaks()
                leaveQuestion()
                handleDetail(snapshot)
            }
            PageState.STUDY_DONE -> {
                resetStreaks()
                leaveQuestion()
                stop(REASON_DONE)
            }
            PageState.UNKNOWN -> {
                leaveQuestion()
                nonStudyStreak = 0
                unknownStreak++
                if (unknownStreak >= MAX_UNKNOWN_FRAMES) {
                    stop(REASON_ERR)
                }
            }
            else -> {
                // 对战页 / 桌面等：不动作，连续约 6s（250ms 轮询）后判定用户已离开学习页
                leaveQuestion()
                unknownStreak = 0
                nonStudyStreak++
                if (nonStudyStreak >= MAX_NON_STUDY_FRAMES) {
                    stop(REASON_LEFT_STUDY)
                }
            }
        }
    }

    /** 答题页：400ms 等页面稳定 → 判定 → 点击；同 key 只补点有限次数。 */
    private fun handleQuestion(snapshot: PageSnapshot) {
        val word = snapshot.word
        val options = snapshot.options
        val key = (word ?: "") + KEY_SEP + options.joinToString(KEY_SEP) { it.text }

        if (key != currentKey) {
            // 新题：取消上一题的等待/补点，重新计时
            cancelSettle()
            cancelRetaps()
            currentKey = key
            readyKey = null
            questionSeenAt = SystemClock.uptimeMillis()
            settleRunnable = post(QUESTION_SETTLE_MS) {
                if (currentKey != key) return@post
                readyKey = key
                ui.requestRead()
            }
            return
        }

        if (readyKey != key) {
            // postDelayed 兜底：万一回调丢失，按首帧时间到达 400ms 后也放行
            val sinceSeen = SystemClock.uptimeMillis() - questionSeenAt
            if (questionSeenAt == 0L || sinceSeen < QUESTION_SETTLE_MS) return
            readyKey = key
        }

        if (key in abandonedKeys) return
        if (key in clickedKeys) {
            if ((clickAttempts[key] ?: 0) >= MAX_ATTEMPTS) {
                abandon(key, word)
                return
            }
            val last = lastTarget[key] ?: return
            // 页面可能重排过，补点优先用本帧的新 bounds，文本对不上才退回旧节点
            val target = options.firstOrNull { it.text == last.text } ?: last
            scheduleRetap(key, word, target)
            return
        }
        if (options.isEmpty()) return

        val decision = decide(snapshot)
        if (decision == null) {
            // 转场/混合帧：详解页刚切走或「继续」还没渲染时，整页会被读成「有选项但没题面」，
            // 一帧判死会在第 5、6 题处误停（真机实测）。先重读几次，仍无解才停手。
            noAnswerStreak++
            if (noAnswerStreak < MAX_NO_ANSWER_REREAD) {
                LogBus.log(
                    "daka=q word=${word ?: "?"} n=${options.size}" +
                        " err=no_answer reread=$noAnswerStreak"
                )
                post(REREAD_DELAY_MS) { ui.requestRead() }
                return
            }
            // 红线：绝不随机猜，重读也无解才停手
            LogBus.log("daka=fail reason=no_answer word=${word ?: "?"} n=${options.size}")
            stop(REASON_NO_ANSWER)
            return
        }
        noAnswerStreak = 0
        lastTarget[key] = decision.target
        val index = options.indexOfFirst { it.text == decision.target.text }
        lastIndex[key] = index
        val clicked = clickTarget(key, word, decision.target, index)
        // ① 新学的词进队列；② 复习词重复不入队（跨次续用时也能补齐词表）
        if (!word.isNullOrBlank()) enqueue(word)
        LogBus.log(
            "daka q word=${word ?: "?"} n=${options.size} pick=${decision.target.text}" +
                " why=${decision.why} best=${Matcher.fmtScore(decision.best)}" +
                " margin=${Matcher.fmtScore(decision.margin)} clicked=$clicked answered=$answered"
        )
    }

    /** 判定层：英文选项走本轮词表交集，中文选项走 ECDICT + Matcher；失败返回 null（不猜）。 */
    private fun decide(snapshot: PageSnapshot): Decision? {
        val options = snapshot.options
        if (options.all { EN_RE.matches(it.text.trim()) }) {
            return decideByQueue(options)
        }
        return decideByDict(snapshot.word, options)
    }

    private fun decideByQueue(options: List<OptionNode>): Decision? {
        val order = queue.map { it.trim().lowercase() }
        if (order.isEmpty()) return null
        val orderSet = order.toHashSet()
        val hits = options.filter { it.text.trim().lowercase() in orderSet }
        if (hits.isEmpty()) return null

        val chosen = if (hits.size == 1) {
            hits[0]
        } else {
            val hitSet = hits.map { it.text.trim().lowercase() }.toHashSet()
            val picked = order.firstOrNull { it in hitSet && it !in reviewedWords }
                ?: order.firstOrNull { it in hitSet }
                ?: return null
            hits.first { it.text.trim().lowercase() == picked }
        }
        reviewedWords.add(chosen.text.trim().lowercase())
        // 词表锚定是确定性命中，best/margin 固定 1.0，日志格式与 dict 路径保持一致
        return Decision(chosen, "queue", 1.0, 1.0)
    }

    private fun decideByDict(word: String?, options: List<OptionNode>): Decision? {
        if (word.isNullOrBlank()) return null
        val translation = lookup(word) ?: return null
        val result = Matcher.pick(translation, word, options.map { it.text }) ?: return null
        if (result.best <= 0.0) return null
        val target = options.firstOrNull { it.text == result.scores[0].option } ?: return null
        return Decision(target, "dict", result.best, result.margin)
    }

    /** 同 key 页面未变：300ms 后补点，最多 3 次（含首次）。 */
    private fun scheduleRetap(key: String, word: String?, target: OptionNode) {
        if (key in abandonedKeys || pendingRetaps.containsKey(key)) return
        val runnable = post(RETAP_DELAY_MS) {
            pendingRetaps.remove(key)
            if (currentKey != key || key in abandonedKeys) return@post
            clickTarget(key, word, target, lastIndex[key] ?: -1)
        }
        pendingRetaps[key] = runnable
    }

    private fun clickTarget(key: String, word: String?, target: OptionNode, index: Int): Boolean {
        if (key in abandonedKeys) return false
        val attempts = (clickAttempts[key] ?: 0) + 1
        clickAttempts[key] = attempts
        clickedKeys.add(key)
        val clicked = clickOptionOrFallback(target, index)
        // answered 只统计「题目」：首次点中才 +1，补点不重复计数
        if (clicked && countedKeys.add(key)) {
            answered++
            notifyProgress()
        }
        if (attempts >= MAX_ATTEMPTS) {
            abandon(key, word)
        }
        return clicked
    }

    /**
     * 正常点节点中心；无障碍树退化（bounds 为空或中心落到屏幕外）时，
     * 按选项索引映射到四块校准坐标兜底。校准从未摆过也有默认常量，可点。
     */
    private fun clickOptionOrFallback(target: OptionNode, index: Int): Boolean {
        val box = target.containerBounds ?: target.bounds
        if (index !in 0..3 || !isDegenerate(box)) return ui.clickOption(target)
        val resolver = tapPoint ?: return ui.clickOption(target)
        val point = OPT_POINTS[index]
        val (fx, fy) = resolver(point)
        LogBus.log("daka=click by=calib idx=$index")
        return ui.clickRatio(fx, fy)
    }

    /** bounds 退化：空矩形，或中心已经不在屏幕内（树里给了越界坐标）。 */
    private fun isDegenerate(box: android.graphics.Rect): Boolean {
        if (box.isEmpty) return true
        val size = screenSize?.invoke() ?: return false
        val (w, h) = size
        if (w <= 0 || h <= 0) return false
        return box.centerX() < 0 || box.centerY() < 0 ||
            box.centerX() >= w || box.centerY() >= h
    }

    private fun abandon(key: String, word: String?) {
        if (!abandonedKeys.add(key)) return
        LogBus.log("daka=stuck word=${word ?: "?"} n=${clickAttempts[key] ?: 0}")
    }

    /** ① 新学 / ② 复习的词入队并落盘；保序去重（大小写不敏感）。 */
    private fun enqueue(word: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        if (queue.any { it.equals(trimmed, ignoreCase = true) }) return
        queue.add(trimmed)
        store.saveQueue(queue.toList())
    }

    /**
     * 详解页：点「继续」，点不到用本机实测比例兜底。
     * 幂等不能只靠 word——详解页读到的常是词根/派生词（elbow→bow、tackle→tack、
     * attraction→at），键一变就会连点两次「继续」（真机实测）。以时间冷却为主判据。
     */
    private fun handleDetail(snapshot: PageSnapshot) {
        val key = snapshot.word ?: "?"
        val now = nowMs()
        if (now - lastDetailAt < DETAIL_COOLDOWN_MS) return
        lastDetailAt = now
        detailDone.add(key)
        val byNode = ui.clickText("继续")
        val by = if (byNode) {
            "node"
        } else {
            val point = tapPoint?.invoke(TapPoint.DAKA_CONTINUE)
            if (point != null) {
                ui.clickRatio(point.first, point.second)
                "calib"
            } else {
                // 单测/未注入时回落到原实测常量
                ui.clickRatio(CONTINUE_X, CONTINUE_Y)
                "coord"
            }
        }
        LogBus.log("daka d word=$key by=$by")
        post(DETAIL_READ_DELAY_MS) { ui.requestRead() }
    }

    /** 离开答题页：取消上一题所有延迟动作，防止补点落到详解页/下一题上。 */
    private fun leaveQuestion() {
        cancelSettle()
        cancelRetaps()
        currentKey = null
        readyKey = null
    }

    private fun resetStreaks() {
        nonStudyStreak = 0
        unknownStreak = 0
    }

    /**
     * 单调时钟（毫秒）。不用 SystemClock：JVM 单测里它是 stub 恒返回 0，
     * 时间差判据（详解页冷却）会退化成「永远在冷却」。
     */
    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private fun post(delayMs: Long, action: () -> Unit): Runnable {
        val runnable = object : Runnable {
            override fun run() {
                pendingRunnables.remove(this)
                if (running) action()
            }
        }
        pendingRunnables.add(runnable)
        ui.postDelayed(delayMs, runnable)
        return runnable
    }

    private fun cancel(runnable: Runnable) {
        ui.removeCallbacks(runnable)
        pendingRunnables.remove(runnable)
    }

    private fun cancelSettle() {
        settleRunnable?.let { cancel(it) }
        settleRunnable = null
    }

    private fun cancelRetaps() {
        for (runnable in pendingRetaps.values) cancel(runnable)
        pendingRetaps.clear()
    }

    private fun clearPending() {
        for (runnable in pendingRunnables.toList()) ui.removeCallbacks(runnable)
        pendingRunnables.clear()
        pendingRetaps.clear()
        settleRunnable = null
    }

    private fun reset() {
        clearPending()
        queue.clear()
        reviewedWords.clear()
        clickedKeys.clear()
        abandonedKeys.clear()
        countedKeys.clear()
        clickAttempts.clear()
        lastTarget.clear()
        lastIndex.clear()
        detailDone.clear()
        currentKey = null
        readyKey = null
        questionSeenAt = 0L
        firstFrameSeen = false
        nonStudyStreak = 0
        unknownStreak = 0
        runStartedAt = 0L
        answered = 0
    }

    private fun notifyProgress() {
        onProgress?.invoke()
    }

    private data class Decision(
        val target: OptionNode,
        val why: String,
        val best: Double,
        val margin: Double
    )

    companion object {
        private const val KEY_SEP = "\u0001"
        private const val QUESTION_SETTLE_MS = 400L
        private const val RETAP_DELAY_MS = 300L
        private const val MAX_ATTEMPTS = 3
        private const val DETAIL_READ_DELAY_MS = 300L
        private const val DETAIL_COOLDOWN_MS = 1_000L   // 详解页两次「继续」的最小间隔
        private const val REREAD_DELAY_MS = 400L        // 判定失败后的重读间隔（等转场结束）
        private const val MAX_NO_ANSWER_REREAD = 3      // 连续 3 次仍判不出才停手
        // 250ms 轮询 × 60 ≈ 15s：从点浮窗到真正进学习页要留够切页时间
        private const val MAX_NON_STUDY_FRAMES = 60
        private const val MAX_UNKNOWN_FRAMES = 60
        private const val MAX_RUN_MS = 40 * 60_000L
        private const val CONTINUE_X = 0.826
        private const val CONTINUE_Y = 0.955
        // 列表顺序即视觉顺序：左上、右上、左下、右下（见 PLAN-daka §二）
        private val OPT_POINTS = arrayOf(
            TapPoint.DAKA_OPT_TL,
            TapPoint.DAKA_OPT_TR,
            TapPoint.DAKA_OPT_BL,
            TapPoint.DAKA_OPT_BR
        )

        private const val REASON_DONE = "done"
        private const val REASON_NO_QUEUE = "no_queue"
        private const val REASON_NO_ANSWER = "no_answer"
        private const val REASON_LEFT_STUDY = "left_study"
        private const val REASON_ERR = "err"
        private const val REASON_LIMIT = "limit"

        private val EN_RE = Regex("^[A-Za-z][A-Za-z'\\- ]*$")
    }
}
