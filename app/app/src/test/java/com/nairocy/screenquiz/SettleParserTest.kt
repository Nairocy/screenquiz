package com.nairocy.screenquiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettleParserTest {

    private val sample = listOf(
        "萘乙酸",
        "803",
        "1823879522",
        "975",
        "camaraderie 同志之爱, 友情",
        "fowl 鸡, 家禽",
        "perjury 伪证, 伪誓",
        "mischance 不幸, 不幸的事",
        "crucify 折磨",
        "disabuse 使省悟, 解迷惑",
        "dazzle 使眼花, 耀眼",
        "学习得分+12分",
        "返回",
        "分享战绩",
        "奖励 20"
    )

    @Test
    fun parsesRealSettleSequence() {
        val rows = SettleParser.parse(sample)
        assertEquals(7, rows.size)
        assertEquals(
            listOf("camaraderie", "fowl", "perjury", "mischance", "crucify", "disabuse", "dazzle"),
            rows.map { it.word }
        )
        assertEquals(SettleRow("camaraderie", "同志之爱, 友情"), rows[0])
        assertEquals(SettleRow("crucify", "折磨"), rows[4])
        assertEquals(SettleRow("dazzle", "使眼花, 耀眼"), rows[6])
    }

    @Test
    fun splitsPhraseRowAtFirstCjk() {
        val rows = SettleParser.parse(listOf("knock sb. off one's feet 使某人双脚离地"))
        assertEquals(1, rows.size)
        assertEquals("knock sb. off one's feet", rows[0].word)
        assertEquals("使某人双脚离地", rows[0].answer)
    }

    @Test
    fun ignoresNonWordRows() {
        val rows = SettleParser.parse(
            listOf("萘乙酸", "803", "1823879522", "975", "学习得分+12分", "返回", "分享战绩", "奖励 20")
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun dedupesByWordKeepingFirst() {
        val rows = SettleParser.parse(
            listOf("apple 苹果", "banana 香蕉", "apple 苹果（重复）")
        )
        assertEquals(listOf("apple", "banana"), rows.map { it.word })
        assertEquals("苹果", rows[0].answer)
    }

    @Test
    fun rejectsWordWithDigitsOrBadPrefix() {
        val rows = SettleParser.parse(
            listOf("803 第八百零三", "a 一", "+abc 释义", "中文开头 释义")
        )
        assertTrue(rows.isEmpty())
    }
}
