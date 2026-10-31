package com.nairocy.screenquiz

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * proto/answer_engine.py 的 1:1 Kotlin 移植（PLAN-p2 §4）。
 * 纯 JVM 逻辑，不依赖 Android，可直接跑本地单测。
 */
object Matcher {

    data class ScoredOption(val option: String, val score: Double)

    data class PickResult(
        val word: String,
        val pick: String?,
        val best: Double,
        val margin: Double,
        val confident: Boolean,
        val scores: List<ScoredOption>,
        val dictTerms: List<String>
    )

    private val POS_RE = Regex(
        "^\\s*(?:n|v|vt|vi|adj|adv|ad|a|prep|conj|pron|num|art|int|aux|abbr|naut)\\.\\s*",
        RegexOption.IGNORE_CASE
    )
    private val SEP_RE = Regex("[；;，,、/｜|·…—()（）\\[\\]【】「」《》“”\"''’]")
    private val LEAD_RE = Regex("^(的|地|得|使|把|让|被|很|尤指|指|某人|某物|某事物)")
    /** 等价 python `[\\s\\W_]+`（Unicode 词字符：字母+数字，剔除下划线/空白/标点）。 */
    private val NON_WORD_RE = Regex("[^\\p{L}\\p{N}]+")

    /** 释义串 → 中文词块：先按字面 \n/\r 分行，逐行剥词性前缀，再切标点，最后去首部虚词。 */
    fun normTerms(s: String?): List<String> {
        if (s == null) return emptyList()
        val normalized = s.replace("\\n", "\n").replace("\\r", "")
        val out = ArrayList<String>()
        for (line in normalized.split('\n')) {
            for (part in SEP_RE.split(line)) {
                var p = POS_RE.replace(part.trim(), "").trim()
                p = p.trim('。', '.', '～', '~', ':', '：', '*')
                p = LEAD_RE.replaceFirst(p, "")
                if (p.isNotEmpty()) out.add(p)
            }
        }
        return out
    }

    fun bigrams(s: String): Set<String> {
        val clean = NON_WORD_RE.replace(s, "")
        if (clean.isEmpty()) return emptySet()
        if (clean.length < 2) return setOf(clean)
        val out = HashSet<String>(clean.length)
        for (i in 0 until clean.length - 1) {
            out.add(clean.substring(i, i + 2))
        }
        return out
    }

    fun dice(a: String, b: String): Double {
        val ba = bigrams(a)
        val bb = bigrams(b)
        if (ba.isEmpty() || bb.isEmpty()) return 0.0
        var inter = 0
        for (g in ba) if (g in bb) inter++
        return 2.0 * inter / (ba.size + bb.size)
    }

    fun charJac(a: String, b: String): Double {
        val ca = NON_WORD_RE.replace(a, "").toSet()
        val cb = NON_WORD_RE.replace(b, "").toSet()
        if (ca.isEmpty() || cb.isEmpty()) return 0.0
        var inter = 0
        for (c in ca) if (c in cb) inter++
        return inter.toDouble() / (ca.size + cb.size - inter)
    }

    fun termScore(optTerm: String, dictTerm: String): Double {
        if (optTerm == dictTerm) return 1.0
        if (optTerm in dictTerm || dictTerm in optTerm) return 0.9
        return maxOf(dice(optTerm, dictTerm), 0.6 * charJac(optTerm, dictTerm))
    }

    fun scoreOption(opt: String, dictTerms: List<String>): Double {
        val ot = normTerms(opt)
        if (ot.isEmpty() || dictTerms.isEmpty()) return 0.0
        var best = 0.0
        for (o in ot) {
            for (d in dictTerms) {
                val sc = termScore(o, d)
                if (sc > best) best = sc
            }
        }
        return best
    }

    /** 与 python pick() 对齐：字典无词返回 null；全 0 时 pick=null（端侧随机）。 */
    fun pick(dictTranslation: String?, word: String, options: List<String>): PickResult? {
        if (dictTranslation == null) return null
        val dictTerms = normTerms(dictTranslation)
        val scored = options
            .map { ScoredOption(it, scoreOption(it, dictTerms)) }
            .sortedByDescending { it.score }
        val best = scored.firstOrNull()?.score ?: 0.0
        val second = if (scored.size > 1) scored[1].score else 0.0
        return PickResult(
            word = word,
            pick = if (best > 0.0) scored[0].option else null,
            best = best,
            margin = best - second,
            confident = best >= 0.4 && (best - second) >= 0.25,
            scores = scored,
            dictTerms = dictTerms
        )
    }

    /** 日志里 best/margin 的显示格式（1.0 / 0.88 / 0.3） */
    fun fmtScore(v: Double): String {
        val rounded = BigDecimal(v).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros()
        val s = rounded.toPlainString()
        return if (s.contains('.')) s else "$s.0"
    }
}
