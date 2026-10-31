package com.nairocy.screenquiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 日志分级保留策略：[LogBus] 关键行 150 / 噪音行 20，且保持时间顺序 */
class LogBusTest {

    @Before
    fun setUp() {
        LogBus.clear()
    }

    @Test
    fun noiseLinesCappedAt20KeyLineSurvives() {
        repeat(30) { LogBus.log("perf reads=$it avg_ms=1 max_ms=2") }
        LogBus.log("state=IN_GAME word=abandon pick=x clicked=true retaps=0 latency_ms=99")
        val lines = LogBus.snapshot()
        assertEquals(21, lines.size)
        assertTrue(lines.last().contains("latency_ms=99"))
        assertTrue(lines.first().contains("reads=10"))
    }

    @Test
    fun retapLinesAreNoiseButFirstClickIsKey() {
        LogBus.log("state=IN_GAME word=abandon retaps=0 latency_ms=1836")
        repeat(8) { LogBus.log("state=IN_GAME word=abandon retaps=${it + 1} latency_ms=${2000 + it}") }
        repeat(30) { LogBus.log("click=gesture xy=1,$it ok=true") }
        val lines = LogBus.snapshot()
        // 首个命中（retaps=0）留下来；补点行与 click=gesture 挤在 20 条噪音额度里
        assertEquals(1 + 20, lines.size)
        assertTrue(lines.first().contains("retaps=0"))
    }

    @Test
    fun keyLinesCappedAt150KeepingNewest() {
        repeat(200) { LogBus.log("round=$it") }
        val lines = LogBus.snapshot()
        assertEquals(150, lines.size)
        assertEquals("round=50", lines.first())
        assertEquals("round=199", lines.last())
    }

    @Test
    fun orderAcrossKeyAndNoiseIsPreserved() {
        LogBus.log("engine=start mode=AUTO")
        LogBus.log("perf reads=1 avg_ms=1 max_ms=1")
        LogBus.log("state=SETTLE round=1")
        assertEquals(
            listOf("engine=start mode=AUTO", "perf reads=1 avg_ms=1 max_ms=1", "state=SETTLE round=1"),
            LogBus.snapshot()
        )
    }

    @Test
    fun clearEmpties() {
        LogBus.log("state=HUB")
        LogBus.clear()
        assertTrue(LogBus.snapshot().isEmpty())
    }
}
