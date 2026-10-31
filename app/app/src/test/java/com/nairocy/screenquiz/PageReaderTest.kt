package com.nairocy.screenquiz

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 学习页（百词斩每日单词打卡）识别；数据取自 1080×2460 真机 dump。 */
class PageReaderTest {

    private val studyClass = "com.baicizhan.main.study.StudySessionActivity"

    @Test
    fun newWordQuestionIsStudyQuestion() {
        val nodes = mutableListOf(
            text("remark", 44, 1105, 1036, 1236),
            text("n. 言论", 88, 1786, 234, 1847),
            text("v. 剩余；逗留；保持；仍需去做", 606, 1756, 992, 1878),
            text("n. 集市；市场；商店", 88, 2075, 474, 2197),
            text("v. 使想起", 606, 2105, 796, 2166)
        )
        nodes += optionContainers()
        nodes += image("斩词", 265, 2306, 397, 2438)

        val snapshot = PageReader.analyze(nodes, studyClass)

        assertEquals(PageState.STUDY_QUESTION, snapshot.state)
        assertEquals("remark", snapshot.word)
        assertEquals(4, snapshot.options.size)
        assertEquals(
            setOf("n. 言论", "v. 剩余；逗留；保持；仍需去做", "n. 集市；市场；商店", "v. 使想起"),
            snapshot.options.map { it.text }.toSet()
        )
    }

    @Test
    fun reviewQuestionWithoutExampleIsStudyQuestion() {
        val nodes = mutableListOf(
            text("dislike", 44, 772, 207, 903),
            text("n. 言论", 88, 1786, 234, 1847),
            text("v. 剩余；逗留；保持；仍需去做", 606, 1756, 992, 1878),
            text("n. 集市；市场；商店", 88, 2075, 474, 2197),
            text("v. 使想起", 606, 2105, 796, 2166)
        )
        nodes += optionContainers()
        nodes += image("斩词", 265, 2306, 397, 2438)

        // windowClass 缺失时靠 desc 特征兜底
        val snapshot = PageReader.analyze(nodes, null)

        assertEquals(PageState.STUDY_QUESTION, snapshot.state)
        assertEquals("dislike", snapshot.word)
        assertEquals(4, snapshot.options.size)
    }

    @Test
    fun pictureQuestionWithEnglishOptionsHasNoWord() {
        val nodes = mutableListOf(
            image("播放发音", 66, 1500, 180, 1600),
            box(66, 1200, 1014, 1600, desc = "展开媒体", clickable = false),
            text("dislodge", 88, 1786, 296, 1847),
            text("alike", 606, 1786, 784, 1847),
            text("dislike", 88, 2105, 296, 2166),
            text("unlike", 606, 2105, 784, 2166)
        )
        nodes += optionContainers()

        val snapshot = PageReader.analyze(nodes, studyClass)

        assertEquals(PageState.STUDY_QUESTION, snapshot.state)
        assertNull(snapshot.word)
        assertEquals(4, snapshot.options.size)
        assertEquals(
            setOf("dislodge", "alike", "dislike", "unlike"),
            snapshot.options.map { it.text }.toSet()
        )
    }

    @Test
    fun detailPageIsStudyDetail() {
        val nodes = listOf(
            text("dislike", 66, 177, 1014, 364),
            text("继续", 748, 2284, 1036, 2416),
            image("Previous", 66, 2306, 180, 2438),
            image("Next", 180, 2306, 294, 2438),
            image("Kill", 294, 2306, 408, 2438),
            image("AI深学", 408, 2306, 600, 2438)
        )

        val snapshot = PageReader.analyze(nodes, studyClass)

        assertEquals(PageState.STUDY_DETAIL, snapshot.state)
    }

    @Test
    fun donePageIsStudyDone() {
        val nodes = listOf(
            text("完成1/1组!", 66, 500, 1014, 600),
            text("继续拼写", 66, 2306, 523, 2438),
            text("完成今日计划", 556, 2306, 1014, 2438),
            text("remark 注意到", 88, 900, 900, 1000),
            text("dislike 不喜欢", 88, 1100, 900, 1200)
        )

        val snapshot = PageReader.analyze(nodes, studyClass)

        assertEquals(PageState.STUDY_DONE, snapshot.state)
    }

    @Test
    fun battlePageIsNotStudyPage() {
        val nodes = mutableListOf(
            text("单词对战", 400, 100, 680, 160),
            text("apple", 44, 500, 1036, 630),
            text("angle", 88, 1786, 296, 1847),
            text("ample", 606, 1786, 784, 1847),
            text("amble", 88, 2105, 296, 2166),
            text("ankle", 606, 2105, 784, 2166)
        )
        nodes += optionContainers()

        val snapshot = PageReader.analyze(nodes, "com.baicizhan.main.battle.BattleActivity")

        assertEquals(PageState.HUB, snapshot.state)
        assertFalse(snapshot.state.name.startsWith("STUDY"))
        assertEquals("apple", snapshot.word)
    }

    /** 结束页工具栏已消失（没有「斩词」desc），且 windowClass 可能取不到——仍要判成 STUDY_DONE。 */
    @Test
    fun donePageWithoutWindowClassIsStudyDone() {
        val nodes = listOf(
            text("完成2/1组!", 370, 143, 710, 247),
            image("隐藏释义", 39, 130, 170, 261),
            image("正确", 208, 605, 238, 635),
            text("elbow", 69, 554, 200, 685),
            text("n. 肘", 77, 663, 175, 717),
            text("继续拼写", 209, 2343, 381, 2401),
            text("下一组", 721, 2343, 850, 2401)
        )

        val snapshot = PageReader.analyze(nodes, null)

        assertEquals(PageState.STUDY_DONE, snapshot.state)
    }

    /** 顶部「上一词」导航（via: prep. 通过）含中文，不能被当成第 5 个选项。 */
    @Test
    fun previousWordNavigationIsNotAnOption() {
        val nodes = mutableListOf(
            box(159, 204, 599, 336),                       // 导航条容器：440×132（高宽比 0.3，不是选项格子）
            text("via:  prep. 通过", 159, 244, 599, 287),
            text("remark", 44, 1105, 1036, 1236),
            text("n. 言论", 88, 1786, 234, 1847),
            text("v. 剩余；逗留；保持；仍需去做", 606, 1756, 992, 1878),
            text("n. 集市；市场；商店", 88, 2075, 474, 2197),
            text("v. 使想起", 606, 2105, 796, 2166)
        )
        nodes += optionContainers()

        val snapshot = PageReader.analyze(nodes, studyClass)

        assertEquals(PageState.STUDY_QUESTION, snapshot.state)
        assertEquals(4, snapshot.options.size)
        assertFalse(snapshot.options.any { it.text.contains("通过") })
    }

    private fun optionContainers(): List<RawNode> = listOf(
        box(33, 1668, 529, 1965),
        box(551, 1668, 1047, 1965),
        box(33, 1987, 529, 2284),
        box(551, 1987, 1047, 2284)
    )

    private fun text(value: String, left: Int, top: Int, right: Int, bottom: Int): RawNode =
        RawNode("android.widget.TextView", value, "", false, rect(left, top, right, bottom))

    private fun image(desc: String, left: Int, top: Int, right: Int, bottom: Int): RawNode =
        RawNode("android.widget.ImageView", "", desc, false, rect(left, top, right, bottom))

    private fun box(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        desc: String = "",
        clickable: Boolean = true
    ): RawNode =
        RawNode("android.view.View", "", desc, clickable, rect(left, top, right, bottom))

    // 本地单测里 android.graphics.Rect 的方法都是 stub（返回 0/false），
    // 但 public 字段可用：构造空对象后直接赋值，供 PageReader 的纯字段计算使用
    private fun rect(left: Int, top: Int, right: Int, bottom: Int): Rect =
        Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
}
