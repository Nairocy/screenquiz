package com.nairocy.screenquiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {

    private data class FixtureCase(val word: String, val zh: String, val translation: String, val src: String)

    /** real_cases_zh.tsv = proto/real_cases.jsonl + assets/ecdict.db 的原始 translation（含字面 \n）。 */
    private val fixture: List<FixtureCase> by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("real_cases_zh.tsv")
            ?: error("fixture real_cases_zh.tsv not found")
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val parts = line.split('\t', limit = 4)
                FixtureCase(parts[0], parts[1], parts[2], parts[3])
            }.toList()
        }
    }

    private fun fixtureOf(word: String): FixtureCase =
        fixture.first { it.word == word }

    @Test
    fun neighborhoodPicksNearbyOptions() {
        val r = Matcher.pick(
            fixtureOf("neighborhood").translation,
            "neighborhood",
            listOf("牙齿", "附近,邻近", "死敌,宿敌", "纪念品")
        )!!
        assertEquals("附近,邻近", r.pick)
        assertEquals(1.0, r.best, 1e-9)
        assertEquals(1.0, r.margin, 1e-9)
        assertTrue(r.confident)
    }

    @Test
    fun snakeTermsContainSnakeWithLiteralBackslashN() {
        val translation = fixtureOf("snake").translation
        val terms = Matcher.normTerms(translation)
        assertTrue("dict terms=$terms", terms.contains("蛇"))
        val r = Matcher.pick(translation, "snake", listOf("蛇, 迂回前进"))!!
        assertEquals("蛇, 迂回前进", r.pick)
        assertEquals(1.0, r.best, 1e-9)
    }

    @Test
    fun closedScoresAboveZeroByCharJaccard() {
        val r = Matcher.pick(
            fixtureOf("closed").translation,
            "closed",
            listOf("关着的")
        )!!
        assertTrue("score=${r.best}", r.best > 0.0)
        assertEquals("关着的", r.pick)
    }

    @Test
    fun buildingPicksBuildingCompound() {
        val r = Matcher.pick(
            fixtureOf("building").translation,
            "building",
            listOf("速度, 速率", "医务室, 医院", "建筑物, 大楼", "录像, 录像机")
        )!!
        assertEquals("建筑物, 大楼", r.pick)
        assertEquals(1.0, r.best, 1e-9)
    }

    @Test
    fun realCasesAnswerHitRateAtLeastSixOfSeven() {
        val cases = fixture.filter { it.src == "real" }
        assertEquals(14, cases.size)
        var hits = 0
        for (case in cases) {
            val r = Matcher.pick(case.translation, case.word, listOf(case.zh))
            if (r != null && r.best >= 0.4) hits++
        }
        assertTrue("hits=$hits/${cases.size}", hits.toDouble() / cases.size >= 6.0 / 7.0)
    }
}
