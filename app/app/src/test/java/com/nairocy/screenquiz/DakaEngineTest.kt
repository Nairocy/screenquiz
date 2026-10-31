package com.nairocy.screenquiz

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** DakaEngine 单测：fake UiOps + 内存 DakaStore + fake 词典，覆盖判定/队列/幂等/停机。 */
class DakaEngineTest {

    private val remarkTranslation = "n. 评论；言论；注意\nv. 谈论；评论"
    private val dimTranslation = "adj. 暗淡的；模糊的\nv. 使变暗"

    private val remarkOptions = listOf(
        "n. 言论",
        "v. 剩余；逗留；保持；仍需去做",
        "n. 集市；市场；商店",
        "v. 使想起"
    )
    private val englishOptions = listOf("dislodge", "alike", "dislike", "unlike")

    @Before
    fun setUp() {
        LogBus.clear()
    }

    @Test
    fun chineseQuestionPicksByDictOnce() {
        val ui = FakeUiOps()
        val store = FakeDakaStore()
        val engine = DakaEngine(
            ui,
            { word -> if (word == "remark") remarkTranslation else null },
            store
        )
        engine.start()

        settleAndFeed(engine, ui, question("remark", remarkOptions))

        assertEquals(1, ui.optionClicks.size)
        assertEquals("n. 言论", ui.optionClicks[0].text)
        assertEquals(1, engine.answered)
        assertEquals(listOf("remark"), store.saved)
        assertTrue(LogBus.snapshot().any { it.contains("why=dict") })
        assertTrue(LogBus.snapshot().any { it.contains("answered=1") })
    }

    @Test
    fun englishQuestionPicksQueueHit() {
        val ui = FakeUiOps()
        val store = FakeDakaStore(listOf("remark", "dislike", "dim"))
        val engine = DakaEngine(ui, { null }, store)
        engine.start()

        settleAndFeed(engine, ui, question(null, englishOptions))

        assertEquals(1, ui.optionClicks.size)
        assertEquals("dislike", ui.optionClicks[0].text)
        assertEquals(1, engine.answered)
        assertTrue(LogBus.snapshot().any { it.contains("why=queue") })
    }

    @Test
    fun englishQuestionWithEmptyQueueStopsNoQueue() {
        val ui = FakeUiOps()
        val store = FakeDakaStore()
        var stopped: String? = null
        val engine = DakaEngine(ui, { null }, store, onStopped = { stopped = it })
        engine.start()

        engine.onPage(question(null, englishOptions))

        assertFalse(engine.running)
        assertEquals("no_queue", stopped)
        assertTrue(ui.optionClicks.isEmpty())
        assertEquals(0, ui.requestReads)
    }

    @Test
    fun detailClicksContinueOnlyOnce() {
        val ui = FakeUiOps()
        val engine = DakaEngine(ui, { null }, FakeDakaStore())
        engine.start()

        val detail = PageSnapshot(PageState.STUDY_DETAIL, "dislike")
        engine.onPage(detail)
        engine.onPage(detail)

        assertEquals(listOf("继续"), ui.clickTexts)
        assertTrue(ui.ratioClicks.isEmpty())
    }

    @Test
    fun doneStopsWithoutClicking() {
        val ui = FakeUiOps()
        val store = FakeDakaStore(listOf("remark"))
        var stopped: String? = null
        val engine = DakaEngine(ui, { null }, store, onStopped = { stopped = it })
        engine.start()

        engine.onPage(PageSnapshot(PageState.STUDY_DONE))

        assertFalse(engine.running)
        assertEquals("done", stopped)
        assertTrue(ui.optionClicks.isEmpty())
        assertTrue(ui.clickTexts.isEmpty())
        assertTrue(ui.ratioClicks.isEmpty())
        assertTrue(store.cleared)
    }

    @Test
    fun sameQuestionRetapsWithinLimitAndNewQuestionClicksAgain() {
        val ui = FakeUiOps()
        val engine = DakaEngine(
            ui,
            { word ->
                when (word) {
                    "remark" -> remarkTranslation
                    "dim" -> dimTranslation
                    else -> null
                }
            },
            FakeDakaStore()
        )
        engine.start()

        val first = question("remark", remarkOptions)
        settleAndFeed(engine, ui, first)
        assertEquals(1, ui.optionClicks.size)

        // 同 key 连喂：每次补点都要手动跑 300ms 延迟任务
        engine.onPage(first)
        ui.runDelayed(300L)
        engine.onPage(first)
        ui.runDelayed(300L)
        engine.onPage(first)
        ui.runDelayed(300L)

        assertEquals(3, ui.optionClicks.size)
        assertEquals(1, LogBus.snapshot().count { it.contains("daka=stuck") })

        val second = question(
            "dim",
            listOf("adj. 暗淡的", "v. 使想起", "n. 集市；市场；商店", "n. 言论")
        )
        settleAndFeed(engine, ui, second)

        assertEquals(4, ui.optionClicks.size)
        assertEquals("adj. 暗淡的", ui.optionClicks.last().text)
    }

    @Test
    fun missingDictStopsWithoutGuessing() {
        val ui = FakeUiOps()
        var stopped: String? = null
        val engine = DakaEngine(ui, { null }, FakeDakaStore(), onStopped = { stopped = it })
        engine.start()

        // 新语义：判定失败先重读（容忍转场帧），连续 MAX_NO_ANSWER_REREAD 次才停手
        repeat(4) { settleAndFeed(engine, ui, question("remark", remarkOptions)) }

        assertFalse(engine.running)
        assertEquals("no_answer", stopped)
        assertTrue(ui.optionClicks.isEmpty())
        assertTrue(LogBus.snapshot().any { it.contains("err=no_answer reread=") })
    }

    @Test
    fun nonStudyFramesStopLeftStudy() {
        val ui = FakeUiOps()
        var stopped: String? = null
        val engine = DakaEngine(ui, { null }, FakeDakaStore(), onStopped = { stopped = it })
        engine.start()

        // MAX_NON_STUDY_FRAMES = 60：前 59 帧仍在跑，第 60 帧停
        repeat(59) { engine.onPage(PageSnapshot(PageState.HOME)) }
        assertTrue(engine.running)

        engine.onPage(PageSnapshot(PageState.HOME))

        assertFalse(engine.running)
        assertEquals("left_study", stopped)
    }

    @Test
    fun unknownFramesStopErr() {
        val ui = FakeUiOps()
        var stopped: String? = null
        val engine = DakaEngine(ui, { null }, FakeDakaStore(), onStopped = { stopped = it })
        engine.start()

        // MAX_UNKNOWN_FRAMES = 60：前 59 帧仍在跑，第 60 帧停
        repeat(59) { engine.onPage(PageSnapshot(PageState.UNKNOWN)) }
        assertTrue(engine.running)

        engine.onPage(PageSnapshot(PageState.UNKNOWN))

        assertFalse(engine.running)
        assertEquals("err", stopped)
    }

    @Test
    fun queueSurvivesNonDoneStopAndReloads() {
        val store = FakeDakaStore()
        val ui1 = FakeUiOps()
        val engine1 = DakaEngine(
            ui1,
            { word -> if (word == "remark") remarkTranslation else null },
            store
        )
        engine1.start()
        settleAndFeed(engine1, ui1, question("remark", remarkOptions))
        engine1.stop("user")
        assertEquals(listOf("remark"), store.loadQueue())

        val ui2 = FakeUiOps()
        val engine2 = DakaEngine(ui2, { null }, store)
        engine2.start()
        settleAndFeed(engine2, ui2, question(null, listOf("remark", "dislike", "dim", "alike")))

        assertEquals("remark", ui2.optionClicks.single().text)
    }

    /** 首次进题会挂 400ms 重读；测试里手动跑掉该任务，再喂同一帧触发判定。 */
    private fun settleAndFeed(engine: DakaEngine, ui: FakeUiOps, snapshot: PageSnapshot) {
        engine.onPage(snapshot)
        ui.runDelayed(400L)
        engine.onPage(snapshot)
    }

    private fun question(word: String?, options: List<String>): PageSnapshot =
        PageSnapshot(
            PageState.STUDY_QUESTION,
            word,
            options.map { OptionNode(it, Rect()) }
        )

    private class FakeDakaStore(initial: List<String> = emptyList()) : DakaStore {
        var saved: List<String>? = null
            private set
        var cleared = false
            private set
        private var queue = initial.toList()

        override fun loadQueue(): List<String> = queue

        override fun saveQueue(queue: List<String>) {
            this.queue = queue.toList()
            saved = this.queue
        }

        override fun clearQueue() {
            queue = emptyList()
            cleared = true
        }
    }

    private class FakeUiOps : UiOps {
        private data class Delayed(val delayMs: Long, val runnable: Runnable)

        private val delayed = ArrayList<Delayed>()
        val clickTexts = ArrayList<String>()
        val optionClicks = ArrayList<OptionNode>()
        val ratioClicks = ArrayList<Pair<Double, Double>>()
        var clickTextResult = true
        var clickOptionResult = true
        var requestReads = 0

        override fun clickText(text: String): Boolean {
            clickTexts.add(text)
            return clickTextResult
        }

        override fun clickOption(option: OptionNode): Boolean {
            optionClicks.add(option)
            return clickOptionResult
        }

        override fun clickRatio(x: Double, y: Double): Boolean {
            ratioClicks.add(x to y)
            return true
        }

        override fun pressBack(): Boolean = true

        override fun requestRead() {
            requestReads++
        }

        override fun postDelayed(delayMs: Long, runnable: Runnable) {
            delayed.add(Delayed(delayMs, runnable))
        }

        override fun removeCallbacks(runnable: Runnable) {
            delayed.removeAll { it.runnable === runnable }
        }

        fun runDelayed(delayMs: Long) {
            val due = delayed.filter { it.delayMs == delayMs }
            delayed.removeAll(due)
            for (task in due) task.runnable.run()
        }
    }
}
