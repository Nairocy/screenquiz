package com.nairocy.screenquiz

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 端侧日志总线：分级保留，防长跑刷屏。
 * - 关键行（state=/round=/stop/reconcile 等）最多 [MAX_KEY_LINES] 条
 * - 噪音行（perf / click=gesture / 补点 retaps>0）最多 [MAX_NOISE_LINES] 条
 * - 引擎每次启动前调用 [clear]（Engine.start 内）
 */
object LogBus {

    const val TAG = "ScreenQuiz"
    private const val MAX_KEY_LINES = 150
    private const val MAX_NOISE_LINES = 20

    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(List<String>) -> Unit>()
    private val retapRe = Regex("""retaps=(\d+)""")

    fun log(line: String) {
        synchronized(lines) {
            if (lines.isNotEmpty() && lines.last() == line) return
            Log.i(TAG, line)
            lines.addLast(line)
            trimLocked()
        }
        notifyListeners()
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
        notifyListeners()
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun addListener(listener: (List<String>) -> Unit) {
        listeners.add(listener)
        listener(snapshot())
    }

    fun removeListener(listener: (List<String>) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val snapshot = snapshot()
        for (listener in listeners) {
            listener(snapshot)
        }
    }

    private fun isNoise(line: String): Boolean {
        if (line.contains("perf ") || line.contains("click=gesture")) return true
        val retaps = retapRe.find(line)?.groupValues?.get(1)
        return retaps != null && retaps != "0"   // 首次命中是有效信息；补点行是噪音
    }

    private fun trimLocked() {
        var noise = 0
        for (l in lines) if (isNoise(l)) noise++
        dropOldest(noise - MAX_NOISE_LINES) { isNoise(it) }
        dropOldest(lines.size - noise - MAX_KEY_LINES) { !isNoise(it) }
    }

    /** 从队首（最旧）开始删掉 [excess] 条满足 [pred] 的行 */
    private fun dropOldest(excess: Int, pred: (String) -> Boolean) {
        var left = excess
        if (left <= 0) return
        val iterator = lines.iterator()
        while (left > 0 && iterator.hasNext()) {
            if (pred(iterator.next())) {
                iterator.remove()
                left--
            }
        }
    }
}
