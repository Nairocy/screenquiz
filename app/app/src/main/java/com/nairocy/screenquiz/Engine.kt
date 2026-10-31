package com.nairocy.screenquiz

import android.os.SystemClock
import kotlin.random.Random

/** Engine 执行动作的出口，由无障碍服务实现；单测可替换。 */
interface UiOps {
    fun clickText(text: String): Boolean
    fun clickOption(option: OptionNode): Boolean
    fun clickRatio(x: Double, y: Double): Boolean
    fun pressBack(): Boolean
    fun requestRead()
    fun postDelayed(delayMs: Long, runnable: Runnable)
    fun removeCallbacks(runnable: Runnable)
}

/**
 * PLAN-p2 §6 状态机：HUB → PRACTICE → MATCHING → IN_GAME → SETTLE → HUB。
 * OBSERVE 只判定记日志，AUTO 才允许点击。
 */
class Engine(
    private val ui: UiOps,
    private val ecdict: EcdictDb,
    private val userDict: UserDict,
    private val random: Random = Random.Default,
    private val onStopped: ((String) -> Unit)? = null
) {

    enum class Mode { OBSERVE, AUTO }

    @Volatile
    var running = false
        private set

    @Volatile
    var mode = Mode.OBSERVE
        private set

    var maxRounds = DEFAULT_MAX_ROUNDS
    var maxAnswersPerRound = 7
    var battleType: String = BATTLE_RANK

    /**
     * 画布坐标点解析器（校准值优先，未校准回落默认常量）。
     * 由无障碍服务注入读 prefs；单测留空即用默认值。
     */
    var tapPoint: ((TapPoint) -> Pair<Double, Double>)? = null

    /** 本轮剩余局数（悬浮球显示用）。 */
    val remainingRounds: Int
        get() = (maxRounds - rounds).coerceAtLeast(0)

    /** 进度回调（剩余局数变化/起停时触发；悬浮球用）。 */
    var onProgress: (() -> Unit)? = null

    private var prevState: PageState? = null
    private var rounds = 0
    private var answers = 0
    private val answeredWords = HashSet<String>()
    private val clickedKeys = HashSet<String>()
    private val retryKeys = HashSet<String>()
    private val guessedKeys = HashSet<String>()
    private val transitionPicks = HashMap<String, String>()
    private var guessCount = 0
    private val clickAttempts = HashMap<String, Int>()
    private var lastKey: String? = null
    private var rereadWord: String? = null
    private var rereadCount = 0
    private val retapCount = HashMap<String, Int>()
    private val lastClickAt = HashMap<String, Long>()
    private var stableKey: String? = null

    // 防过场混合帧：上一题（已判定）的词与选项
    private var lastDecidedWord: String? = null
    private var lastDecidedOptions: List<String> = emptyList()
    private val mixedStreak = HashMap<String, Int>()
    // 题面首帧时间（latency_ms 埋点用；按词计，混合帧不重置）
    private var questionWord: String? = null
    private var questionSeenAt = 0L
    // 探针：上一帧的页面数字集合（HUD 分数变化 ≈ 点击被 App 接受的时刻）
    private var lastHud: List<String> = emptyList()
    // 词表查询缓存（只缓存命中，未命中不缓存，避免掩盖学习层新词）
    private val translationCache = object : LinkedHashMap<String, String?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean =
            size > DICT_CACHE_MAX
    }

    private var pendingStartAt = 0L
    private var hubClicks = 0
    private var hubClickedAt = 0L
    private var hubTotal = 0
    private var runStartedAt = 0L
    private var lastMatchingAt = 0L
    private var practiceClicks = 0
    private var practiceClickedAt = 0L
    private var matchingSince = 0L
    private var matchingExits = 0
    private var unknownStreak = 0
    private var backs = 0
    private var settleHandled = false
    private var hubFirstSeenAt = 0L
    private var homeClicks = 0
    private var homeClickedAt = 0L
    private var resetExitAt = 0L   // 「退出对战页重进」点 X 的时刻（0=本轮不走该流程）
    private var resetTries = 0
    private val pickByWord = HashMap<String, String>()
    // 题内选择锁定（key -> 选项文本）：匹配失败随机回退时，避免每次补点换选项
    private val pickLock = HashMap<String, String>()
    private var settleReadAttempts = 0
    private var wbStreak = 0
    private var wbBacks = 0

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            ui.requestRead()
            ui.postDelayed(POLL_INTERVAL_MS, this)
        }
    }

    fun start(startMode: Mode) {
        if (running) {
            mode = startMode
            return
        }
        reset()
        mode = startMode
        running = true
        runStartedAt = SystemClock.uptimeMillis()
        LogBus.clear()   // 每次启动清空日志（防长跑堆积）
        LogBus.log(
            "engine=start mode=${mode.name} max_rounds=$maxRounds" +
                " max_answers=$maxAnswersPerRound battle=$battleType" +
                " settle_ms=$QUESTION_SETTLE_MS retap_ms=$RETAP_MS max_retaps=$MAX_RETAPS"
        )
        notifyProgress()
        ui.postDelayed(0L, pollRunnable)
    }

    fun stop(reason: String) {
        if (!running) return
        running = false
        ui.removeCallbacks(pollRunnable)
        pendingStartAt = 0L
        LogBus.log("engine=stop stopped=$reason rounds=$rounds")
        onStopped?.invoke(reason)
        notifyProgress()
    }

    fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        if (running) LogBus.log("engine=mode mode=${mode.name}")
    }

    fun onPage(snapshot: PageSnapshot) {
        if (!running) return
        if (pendingStartAt != 0L &&
            System.currentTimeMillis() - pendingStartAt > START_TIMEOUT_MS &&
            (snapshot.state == PageState.HUB || snapshot.state == PageState.PRACTICE)
        ) {
            LogBus.log("start=fail reason=no_energy")
            stop("no_energy")
            return
        }
        if (runStartedAt != 0L && SystemClock.uptimeMillis() - runStartedAt > MAX_RUN_MS) {
            LogBus.log("engine=stop stopped=runtime_limit ms=${SystemClock.uptimeMillis() - runStartedAt}")
            stop("limit")
            return
        }
        if (snapshot.state != prevState) {
            onStateChanged(snapshot.state)
            prevState = snapshot.state
        }
        when (snapshot.state) {
            PageState.HUB -> handleHub()
            PageState.HOME -> handleHome()
            PageState.PRACTICE -> handlePractice()
            PageState.MATCHING -> handleMatching()
            PageState.IN_GAME -> handleInGame(snapshot)
            PageState.SETTLE -> handleSettle(snapshot)
            PageState.WORDBOOK -> handleWordbook()
            PageState.TRANSITION -> handleTransition(snapshot)
            PageState.UNKNOWN -> handleUnknown()
        }
    }

    private fun onStateChanged(state: PageState) {
        when (state) {
            PageState.HUB -> {
                hubClicks = 0
                hubClickedAt = 0L
                hubFirstSeenAt = 0L
                matchingExits = 0
                backs = 0
            }
            PageState.HOME -> {
                homeClicks = 0
                homeClickedAt = 0L
            }
            PageState.PRACTICE -> {
                practiceClicks = 0
                practiceClickedAt = 0L
            }
            PageState.MATCHING -> {
                matchingSince = 0L
                pendingStartAt = 0L
            }
            PageState.IN_GAME -> pendingStartAt = 0L
            PageState.SETTLE -> {
                settleHandled = false
                resetTries = 0
                resetExitAt = 0L
            }
            else -> Unit
        }
        if (state != PageState.UNKNOWN) unknownStreak = 0
        if (state != PageState.WORDBOOK) {
            wbStreak = 0
            wbBacks = 0
        }
        if (state != PageState.IN_GAME) LogBus.log("state=${state.name}")
    }

    private fun handleHub() {
        if (mode != Mode.AUTO) return
        val now = SystemClock.uptimeMillis()
        if (hubFirstSeenAt == 0L) hubFirstSeenAt = now
        // 回到 Hub 时磁贴仍在入场动画里，立刻点会被吞（原先要干等 3s 才重试）→
        // 先等页面铺开，再用 0.7s 的短间隔快速重试。
        if (now - hubFirstSeenAt < HUB_SETTLE_MS) return
        if (hubClickedAt != 0L && now - hubClickedAt < HUB_WAIT_MS) return
        if (hubClicks == 0) {
            clickHub()
            return
        }
        if (hubClicks < MAX_HUB_TRIES) {
            LogBus.log("state=HUB err=hub_retry n=$hubClicks")
            clickHub()
        } else {
            LogBus.log("state=HUB err=hub_fail")
            stop("err")
        }
    }

    private fun clickHub() {
        hubClicks++
        hubTotal++
        if (hubTotal > MAX_HUB_TOTAL) {
            LogBus.log("state=HUB err=hub_total_limit n=$hubTotal")
            stop("limit")
            return
        }
        hubClickedAt = SystemClock.uptimeMillis()
        if (battleType == BATTLE_PRACTICE) {
            LogBus.log("state=HUB action=click_practice n=$hubClicks")
            ui.clickRatio(HUB_X, HUB_Y)
            return
        }
        if (pendingStartAt == 0L) pendingStartAt = System.currentTimeMillis()
        val byNode = ui.clickText("开始对战吧!")
        LogBus.log("state=HUB action=click_rank n=$hubClicks by=${if (byNode) "node" else "coord"}")
        if (!byNode) ui.clickRatio(RANK_X, RANK_Y)
    }

    /** 百词斩首页（地图页）：入口全是画布渲染（无文本节点）→ 「PK」只能按坐标点，点完进「单词对战」Hub。 */
    private fun handleHome() {
        if (mode != Mode.AUTO) return
        val now = SystemClock.uptimeMillis()
        if (homeClickedAt != 0L && now - homeClickedAt < HOME_RETRY_MS) return
        if (homeClicks >= MAX_HOME_TRIES) {
            LogBus.log("state=HOME err=home_fail")
            stop("err")
            return
        }
        homeClicks++
        homeClickedAt = now
        val (pkX, pkY) = point(TapPoint.PK)
        LogBus.log("state=HOME action=click_pk n=$homeClicks x=%.3f y=%.3f".format(pkX, pkY))
        ui.clickRatio(pkX, pkY)
    }

    /** 取画布坐标点：校准值优先，未校准用默认常量。 */
    private fun point(p: TapPoint): Pair<Double, Double> =
        tapPoint?.invoke(p) ?: (p.defaultX to p.defaultY)

    private fun handlePractice() {
        if (mode != Mode.AUTO) return
        val now = SystemClock.uptimeMillis()
        if (practiceClicks == 0) {
            clickPractice()
        } else if (now - practiceClickedAt >= PRACTICE_RETRY_MS) {
            if (practiceClicks < MAX_PRACTICE_TRIES) {
                LogBus.log("state=PRACTICE err=practice_retry n=$practiceClicks")
                clickPractice()
            } else {
                LogBus.log("state=PRACTICE err=practice_fail")
                stop("err")
            }
        }
    }

    private fun clickPractice() {
        practiceClicks++
        practiceClickedAt = SystemClock.uptimeMillis()
        if (pendingStartAt == 0L) pendingStartAt = System.currentTimeMillis()
        val byNode = ui.clickText("随机匹配")
        LogBus.log("state=PRACTICE action=click_match by=${if (byNode) "node" else "coord"}")
        if (!byNode) ui.clickRatio(PRACTICE_X, PRACTICE_Y)
    }

    private fun handleMatching() {
        if (mode != Mode.AUTO) return
        val now = SystemClock.uptimeMillis()
        lastMatchingAt = now
        if (matchingSince == 0L) {
            matchingSince = now
            return
        }
        if (now - matchingSince <= MATCHING_TIMEOUT_MS) return
        if (matchingExits < MAX_MATCHING_EXITS) {
            matchingExits++
            matchingSince = now
            val (bx, by) = point(TapPoint.MATCH_BACK)
            LogBus.log("state=MATCHING timeout=45s action=exit n=$matchingExits x=%.3f y=%.3f".format(bx, by))
            ui.clickRatio(bx, by)
        } else {
            LogBus.log("state=MATCHING err=matching_timeout")
            stop("err")
        }
    }

    private fun handleInGame(snapshot: PageSnapshot) {
        probeHud(snapshot)
        val word = snapshot.word ?: return
        val options = snapshot.options
        if (options.isEmpty()) return

        if (options.size < OPTIONS_MIN) {
            if (rereadWord != word) {
                rereadWord = word
                rereadCount = 0
            }
            if (rereadCount < REREAD_MAX) {
                rereadCount++
                LogBus.log("state=IN_GAME word=$word options_short=${options.size} reread=$rereadCount")
                ui.postDelayed(REREAD_DELAY_MS) { if (running) ui.requestRead() }
                return
            }
        } else if (rereadWord != null) {
            rereadWord = null
            rereadCount = 0
        }

        val optionTexts = options.map { it.text }
        val key = word + KEY_SEP + optionTexts.joinToString(KEY_SEP)
        val nowMs = SystemClock.uptimeMillis()
        if (questionWord != word) {
            questionWord = word
            questionSeenAt = nowMs
            LogBus.log("state=IN_GAME word=$word first_seen")
        }
        // 过场混合帧：词与选项只更新了一半（实录 motorbike：新词 + 上一题选项；反向同理）→ 不认，等下一帧
        val wordChanged = lastDecidedWord != null && word != lastDecidedWord
        val optionsChanged = optionTexts != lastDecidedOptions
        val mixed = lastDecidedWord != null && wordChanged != optionsChanged
        if (mixed && (mixedStreak[key] ?: 0) < MAX_MIXED_STREAK) {
            mixedStreak[key] = (mixedStreak[key] ?: 0) + 1
            stableKey = null
            LogBus.log("state=IN_GAME word=$word mixed_frame n=${mixedStreak[key]} word_changed=$wordChanged")
            ui.postDelayed(MIXED_REREAD_MS) { if (running) ui.requestRead() }
            return
        }

        // 连续两帧一致再判定
        if (key != stableKey) {
            stableKey = key
            ui.postDelayed(STABLE_REREAD_MS) { if (running) ui.requestRead() }
            return
        }
        // 首点时刻：入场动画前的点击会被吞（实测无害），提前首点 + 补点覆盖开放窗口
        val sinceSeen = SystemClock.uptimeMillis() - questionSeenAt
        if (sinceSeen < QUESTION_SETTLE_MS && key !in clickedKeys) {
            ui.postDelayed(QUESTION_SETTLE_MS - sinceSeen + 20) { if (running) ui.requestRead() }
            return
        }
        val repeat = key == lastKey
        // 已点过但题还在（点空/被动画吞掉）→ 到点补点，不能直接早退
        val retapDue = repeat && key in clickedKeys &&
            (retapCount[key] ?: 0) < MAX_RETAPS &&
            (lastClickAt[key] ?: 0L) != 0L &&
            SystemClock.uptimeMillis() - (lastClickAt[key] ?: 0L) >= RETAP_MS
        if (repeat && key in clickedKeys && !retapDue) {
            scheduleFastReread(key)
            return
        }
        if (repeat && key !in retryKeys && !retapDue) {
            scheduleFastReread(key)
            return
        }

        if (!repeat) lastKey = key
        val isNewWord = answeredWords.add(word)
        if (isNewWord) {
            answers++
            if (answers > maxAnswersPerRound) {
                LogBus.log("state=IN_GAME word=$word stopped=limit answers=$answers")
                stop("limit")
                return
            }
        }

        val locked = pickLock[key]
        val pickText: String
        val detail: String
        if (locked != null) {
            pickText = locked
            detail = " locked=true pick=$pickText"
        } else {
            val translation = lookupCached(word)
            val result = if (translation != null) Matcher.pick(translation, word, optionTexts) else null
            if (result != null) {
                pickText = if (result.best > 0.0) {
                    result.scores[0].option
                } else {
                    options[random.nextInt(options.size)].text
                }
                detail = " pick=$pickText best=${Matcher.fmtScore(result.best)}" +
                    " margin=${Matcher.fmtScore(result.margin)}"
            } else {
                val guess = PhraseGuesser.guess(word, optionTexts) { token ->
                    Matcher.normTerms(lookupCached(token))
                }
                pickText = guess.picked ?: options[random.nextInt(options.size)].text
                detail = " err=no_dict guessed=true reason=${guess.reason}" +
                    " score=${Matcher.fmtScore(guess.score)}" +
                    " tokens=[${guess.tokens.joinToString(",")}] pick=$pickText"
            }
            pickLock[key] = pickText
        }
        val target = options.firstOrNull { it.text == pickText } ?: return

        var clicked = false
        if (mode == Mode.AUTO && (key !in clickedKeys || retapDue)) {
            val clickAt = SystemClock.uptimeMillis()
            val attempts = (clickAttempts[key] ?: 0) + 1
            clickAttempts[key] = attempts
            if (retapDue) retapCount[key] = (retapCount[key] ?: 0) + 1
            clicked = ui.clickOption(target)
            lastClickAt[key] = clickAt
            if (clicked) {
                clickedKeys.add(key)
                retryKeys.remove(key)
            } else if (attempts >= MAX_CLICK_TRIES) {
                clickedKeys.add(key)
                retryKeys.remove(key)
                LogBus.log("state=IN_GAME word=$word err=click_failed n=$attempts")
            } else {
                retryKeys.add(key)
            }
        }
        if (clicked) pickByWord[word] = pickText
        lastDecidedWord = word
        lastDecidedOptions = optionTexts
        if (key in clickedKeys) scheduleFastReread(key)

        LogBus.log(
            "${snapshot.logLine()}$detail clicked=$clicked" +
                " retaps=${retapCount[key] ?: 0}" +
                " latency_ms=${SystemClock.uptimeMillis() - questionSeenAt}"
        )
    }

    /** 探针：页面数字集合变化打点（HUD 分数变化 ≈ 点击被 App 接受的时刻）。 */
    private fun probeHud(snapshot: PageSnapshot) {
        val nums = snapshot.nums
        if (nums == lastHud) return
        lastHud = nums
        LogBus.log(
            "state=IN_GAME word=${snapshot.word} hud=[${nums.joinToString(",")}]" +
                if (questionSeenAt != 0L) " t=${SystemClock.uptimeMillis() - questionSeenAt}" else ""
        )
    }

    /** 等待命中/切题期间：以快节奏重读（细化补点落点，并在切题第一帧即停手）。 */
    private fun scheduleFastReread(key: String) {
        if ((retapCount[key] ?: 0) >= MAX_RETAPS) return
        ui.postDelayed(FAST_REREAD_MS) { if (running) ui.requestRead() }
    }

    private fun handleTransition(snapshot: PageSnapshot) {
        val options = snapshot.options
        if (options.size < TRANSITION_OPTIONS_MIN) return
        val key = options.joinToString(KEY_SEP) { it.text }
        if (key != stableKey) {
            stableKey = key
            ui.postDelayed(STABLE_REREAD_MS) { if (running) ui.requestRead() }
            return
        }

        val repeat = key in guessedKeys
        val nowMs = SystemClock.uptimeMillis()
        val retapDue = repeat && key in clickedKeys &&
            (retapCount[key] ?: 0) < MAX_RETAPS &&
            (lastClickAt[key] ?: 0L) != 0L &&
            nowMs - (lastClickAt[key] ?: 0L) >= RETAP_MS
        if (repeat && key in clickedKeys && !retapDue) {
            scheduleFastReread(key)
            return
        }
        if (repeat && key !in retryKeys && !retapDue) {
            scheduleFastReread(key)
            return
        }

        val pickText: String
        if (!repeat) {
            if (guessCount >= MAX_GUESSES) return
            guessCount++
            pickText = options[random.nextInt(options.size)].text
            transitionPicks[key] = pickText
            guessedKeys.add(key)
        } else {
            pickText = transitionPicks[key] ?: return
        }
        val target = options.firstOrNull { it.text == pickText } ?: return

        var clicked = false
        if (mode == Mode.AUTO) {
            val attempts = (clickAttempts[key] ?: 0) + 1
            clickAttempts[key] = attempts
            if (retapDue) retapCount[key] = (retapCount[key] ?: 0) + 1
            clicked = ui.clickOption(target)
            lastClickAt[key] = nowMs
            if (clicked) {
                clickedKeys.add(key)
                retryKeys.remove(key)
            } else if (attempts >= MAX_CLICK_TRIES) {
                clickedKeys.add(key)
                retryKeys.remove(key)
                LogBus.log("state=TRANSITION err=click_failed n=$attempts")
            } else {
                retryKeys.add(key)
            }
        }
        if (key in clickedKeys) scheduleFastReread(key)
        LogBus.log(
            "state=TRANSITION action=guess pick=$pickText clicked=$clicked" +
                " n=$guessCount retaps=${retapCount[key] ?: 0}"
        )
    }

    private fun handleSettle(snapshot: PageSnapshot) {
        if (settleHandled) {
            // 兜底：本轮走「退出重进」，但点了 X 之后仍停在结算页（X 没生效/不存在）→ 退回常规「返回」
            if (resetExitAt != 0L && resetTries < 2 &&
                SystemClock.uptimeMillis() - resetExitAt >= RESET_FALLBACK_MS
            ) {
                resetTries = 2
                val byNode = ui.clickText("返回")
                LogBus.log("state=SETTLE err=exit_stuck action=click_return by=${if (byNode) "node" else "coord"}")
                if (!byNode) ui.clickRatio(SETTLE_X, SETTLE_Y)
            }
            return
        }
        val rows = snapshot.settleRows
        if (rows.size < SETTLE_MIN_ROWS && settleReadAttempts < SETTLE_MAX_READS) {
            settleReadAttempts++
            LogBus.log("state=SETTLE rows_short=${rows.size} reread=$settleReadAttempts")
            ui.postDelayed(SETTLE_READ_DELAY_MS) { if (running) ui.requestRead() }
            return
        }
        settleHandled = true
        // 启动后第一眼就是结算页（本轮一题未答）＝上一局残留，不计轮
        val residual = answers == 0 && clickedKeys.isEmpty()
        if (!residual) rounds++
        answeredWords.clear()
        answers = 0
        guessCount = 0
        LogBus.log("state=SETTLE round=$rounds" + if (residual) " residual=true" else "")
        reconcile(rows)
        if (mode == Mode.AUTO) {
            // 百词斩对战页是嵌套 WebView：每局多叠一层 → 局数多了会卡/渲染出错。
            // 每 RESET_EVERY_ROUNDS 局（且还要继续跑）就点左上角 X 彻底退出，再从首页「PK」重进。
            if (!residual && rounds < maxRounds && rounds % RESET_EVERY_ROUNDS == 0) {
                resetExitAt = SystemClock.uptimeMillis()
                resetTries = 1
                val (xx, xy) = point(TapPoint.EXIT)
                LogBus.log("state=SETTLE action=click_exit reason=reset rounds=$rounds x=%.3f y=%.3f".format(xx, xy))
                ui.clickRatio(xx, xy)
            } else {
                val byNode = ui.clickText("返回")
                LogBus.log("state=SETTLE action=click_return by=${if (byNode) "node" else "coord"}")
                if (!byNode) ui.clickRatio(SETTLE_X, SETTLE_Y)
            }
        }
        notifyProgress()
        if (rounds >= maxRounds) {
            LogBus.log("state=SETTLE stopped=limit rounds=$rounds")
            stop("limit")
        }
    }

    /** 错词本页：流程外页面（如引擎停在错词本时启动）→ 短暂宽限后返回上一级。 */
    private fun handleWordbook() {
        wbStreak++
        if (wbStreak < WORDBOOK_BACK_STREAK) return
        wbStreak = 0
        if (wbBacks >= MAX_WORDBOOK_BACKS) return
        wbBacks++
        LogBus.log("state=WORDBOOK action=back n=$wbBacks")
        ui.pressBack()
    }

    /** W3 结算对账：结算行答案全量入 user_dict（fire-and-forget），并按本轮点选自算对错。 */
    private fun reconcile(rows: List<SettleRow>) {
        if (rows.isEmpty()) {
            LogBus.log("state=SETTLE rows=0 reconcile=skip")
            return
        }
        Thread {
            try {
                userDict.upsertAll(rows.map { WordRow(it.word, it.answer) }, SOURCE_SETTLE)
            } catch (t: Throwable) {
                LogBus.log("reconcile=write_fail n=${rows.size} err=${t.javaClass.simpleName}")
            }
        }.start()
        var correct = 0
        var wrong = 0
        var none = 0
        for (row in rows) {
            val pick = pickByWord[row.word]
            val ok = pick != null && pick == row.answer
            when {
                pick == null -> none++
                ok -> correct++
                else -> wrong++
            }
            LogBus.log("reconcile word=${row.word} pick=${pick ?: "?"} ok=$ok")
        }
        LogBus.log("state=SETTLE rows=${rows.size} correct=$correct wrong=$wrong none=$none")
        // 对账完清 pick：防跨局陈旧命中（本局没答的词不该拿上局的 pick 判对错）
        pickByWord.clear()
        pickLock.clear()
    }

    private fun notifyProgress() {
        onProgress?.invoke()
    }

    /** 词表查询缓存；只缓存命中，未命中直接透传（学习层随时可能新增词条）。 */
    private fun lookupCached(word: String): String? {
        val key = word.lowercase()
        synchronized(translationCache) {
            val hit = translationCache[key]
            if (hit != null) return hit
        }
        val value = ecdict.lookup(word) ?: return null
        synchronized(translationCache) {
            translationCache[key] = value
        }
        return value
    }

    private fun handleUnknown() {
        unknownStreak++
        if (unknownStreak <= UNKNOWN_STREAK_MAX) return
        if (mode != Mode.AUTO) {
            unknownStreak = 0
            return
        }
        // 匹配流程刚结束的宽限期内不按 BACK：MATCH! 过场/开局加载都在这窗口里
        val now = SystemClock.uptimeMillis()
        if (lastMatchingAt != 0L && now - lastMatchingAt < POST_MATCHING_GRACE_MS) {
            LogBus.log("state=UNKNOWN err=in_match_grace action=wait")
            unknownStreak = 0
            return
        }
        if (backs < MAX_BACKS) {
            backs++
            unknownStreak = 0
            LogBus.log("state=UNKNOWN err=unknown_streak action=back n=$backs")
            ui.pressBack()
        } else {
            LogBus.log("state=UNKNOWN err=unknown_fail")
            stop("err")
        }
    }

    private fun reset() {
        prevState = null
        rounds = 0
        answers = 0
        answeredWords.clear()
        clickedKeys.clear()
        retryKeys.clear()
        guessedKeys.clear()
        transitionPicks.clear()
        guessCount = 0
        clickAttempts.clear()
        retapCount.clear()
        lastClickAt.clear()
        lastKey = null
        stableKey = null
        lastDecidedWord = null
        lastDecidedOptions = emptyList()
        mixedStreak.clear()
        questionWord = null
        questionSeenAt = 0L
        rereadWord = null
        rereadCount = 0
        pendingStartAt = 0L
        hubClicks = 0
        hubClickedAt = 0L
        hubTotal = 0
        lastMatchingAt = 0L
        runStartedAt = 0L
        practiceClicks = 0
        practiceClickedAt = 0L
        matchingSince = 0L
        matchingExits = 0
        unknownStreak = 0
        backs = 0
        settleHandled = false
        pickByWord.clear()
        pickLock.clear()
        settleReadAttempts = 0
        wbStreak = 0
        wbBacks = 0
    }

    companion object {
        private const val KEY_SEP = "\u0001"
        private const val POLL_INTERVAL_MS = 250L
        private const val HUB_SETTLE_MS = 900L     // 回到 Hub 后等磁贴入场动画结束再点（否则首点被吞）
        private const val HUB_WAIT_MS = 700L       // 首点失败后的重试间隔（原 3s：每局凭空多等 3s）
        private const val RESET_EVERY_ROUNDS = 5   // 每 N 局彻底退出对战页重进（对战页逐局嵌套会变卡）
        private const val RESET_FALLBACK_MS = 1_500L  // 点了 X 仍停在结算页 → 退回常规「返回」
        private const val HOME_RETRY_MS = 2_000L
        private const val MAX_HOME_TRIES = 4
        private const val HUB_X = 0.188
        private const val HUB_Y = 0.726
        private const val PRACTICE_X = 0.722
        private const val PRACTICE_Y = 0.819
        private const val SETTLE_X = 0.265
        private const val SETTLE_Y = 0.832
        private const val SETTLE_MIN_ROWS = 5
        private const val SETTLE_MAX_READS = 2
        private const val SETTLE_READ_DELAY_MS = 250L
        private const val SOURCE_SETTLE = "settle"
        private const val OPTIONS_MIN = 4
        private const val REREAD_DELAY_MS = 120L
        private const val REREAD_MAX = 3
        private const val MATCHING_TIMEOUT_MS = 45_000L
        private const val MAX_MATCHING_EXITS = 3
        private const val MAX_HUB_TRIES = 8
        private const val MAX_PRACTICE_TRIES = 3
        private const val PRACTICE_RETRY_MS = 5_000L
        private const val UNKNOWN_STREAK_MAX = 30          // ~15s：MATCH! 过场/开局加载都在此窗口，不许乱按 BACK
        private const val POST_MATCHING_GRACE_MS = 20_000L // 匹配结束后 20s 内不做 BACK 自愈
        private const val MAX_HUB_TOTAL = 120              // 整轮运行内的 Hub 点击数上限（10 局约 25-30 次）
        private const val STABLE_REREAD_MS = 90L  // 连续两帧一致才判定（躲过场混合帧）
        private const val QUESTION_SETTLE_MS = 600L // 首点时刻（入场期点击被吞无害 → 提前首点，靠补点覆盖开放窗口）
        private const val MIXED_REREAD_MS = 60L   // 识破混合帧后的重读间隔
        private const val MAX_MIXED_STREAK = 5    // 同 key 连续被判混合的上限，超过则放行（防死等）
        private const val DICT_CACHE_MAX = 512
        private const val FAST_REREAD_MS = 60L    // 等待命中/切题期间的快节奏重读间隔
        private const val RETAP_MS = 70L          // 补点最小间隔（实测 ~75-85ms；再密会拖慢 App 把窗口推后 ~1.2s）
        private const val MAX_RETAPS = 50         // 单题最多补点次数（覆盖至 ~4.6s）
        private const val TRANSITION_OPTIONS_MIN = 3
        private const val MAX_GUESSES = 10   // 每局保底猜次数上限
        const val BATTLE_RANK = "rank"
        const val BATTLE_PRACTICE = "practice"
        const val DEFAULT_MAX_ROUNDS = 10    // 循环局数默认值
        private const val START_TIMEOUT_MS = 8_000L
        private const val RANK_X = 0.499
        private const val RANK_Y = 0.567
        private const val MAX_RUN_MS = 20 * 60_000L
        private const val MAX_BACKS = 3
        private const val MAX_CLICK_TRIES = 3
        private const val WORDBOOK_BACK_STREAK = 8  // 错词本页停留宽限（读帧计）
        private const val MAX_WORDBOOK_BACKS = 3
    }
}
