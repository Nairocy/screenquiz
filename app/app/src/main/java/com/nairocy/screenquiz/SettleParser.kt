package com.nairocy.screenquiz

import java.util.regex.Pattern

data class SettleRow(val word: String, val answer: String)

/** 结算页词表行解析（PLAN-v2-learning §3.4）：从右侧第一个 CJK 字符处切分 word/answer。 */
object SettleParser {

    fun parse(texts: List<String>): List<SettleRow> {
        val rows = LinkedHashMap<String, SettleRow>()
        for (raw in texts) {
            val text = raw.trim()
            val idx = firstCjk(text)
            if (idx <= 0) continue
            val word = text.substring(0, idx).trim()
            val answer = text.substring(idx).trim()
            if (word.length < 2) continue
            if (!WORD_RE.matcher(word).matches()) continue
            if (answer.isEmpty()) continue
            if (!rows.containsKey(word)) rows[word] = SettleRow(word, answer)
        }
        return rows.values.toList()
    }

    private fun firstCjk(text: String): Int {
        for (i in text.indices) {
            if (text[i] in '\u4e00'..'\u9fff') return i
        }
        return -1
    }

    private val WORD_RE = Pattern.compile("^[A-Za-z][A-Za-z'\\u2019.\\- ]*$")
}
