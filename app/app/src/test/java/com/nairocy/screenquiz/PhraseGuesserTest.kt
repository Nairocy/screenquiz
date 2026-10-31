package com.nairocy.screenquiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseGuesserTest {

    private val rawTranslations = mapOf(
        "chest" to "n. 胸, 胸部, 衣柜, 箱子\\n[医] 胸, 胸廓",
        "drawer" to "n. 抽屉, 开票人\\n[计] 抽屉",
        "knock" to "n. 敲, 敲打, 敲门\\nv. 敲击, 互撞, 攻击",
        "off" to "a. 关着的, 不再生效的, 处于...境况的, 休假的, 空闲的\\n" +
            "adv. 走开, ...掉, ...下, 休息, 出发, 隔断\\n" +
            "prep. 离开, 脱落, 不在从事......, 在...之外\\n" +
            "vi. 离开, 滚开\\nvt. 杀死\\nn. 关闭状态\\n[计] 关闭, 清屏命令"
    )

    private val provider: (String) -> List<String> = { token ->
        Matcher.normTerms(rawTranslations[token])
    }

    @Test
    fun tokenizeKeepsContentWordsAndDropsStopwords() {
        assertEquals(
            listOf("knock", "off", "feet"),
            PhraseGuesser.tokenize("knock sb. off one's feet")
        )
    }

    @Test
    fun chestOfDrawersRealEcdictOrdering() {
        val options = listOf("抽屉柜", "衣柜", "书架", "手推车")
        val result = PhraseGuesser.guess("a chest of drawers", options, provider)
        assertEquals(1.0, result.scores.first { it.option == "衣柜" }.score, 1e-9)
        assertEquals(0.9, result.scores.first { it.option == "抽屉柜" }.score, 1e-9)
        assertEquals("衣柜", result.picked)
    }

    @Test
    fun knockPhraseLiftOptionScoresAboveZero() {
        val options = listOf("使某人双脚离地", "赶快", "双脚站立", "匆忙离开")
        val result = PhraseGuesser.guess("knock sb. off one's feet", options, provider)
        val lift = result.scores.first { it.option == "使某人双脚离地" }
        assertTrue("lift=${lift.score} table=${result.scores}", lift.score > 0.0)
    }
}
