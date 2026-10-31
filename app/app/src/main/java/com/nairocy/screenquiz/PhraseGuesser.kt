package com.nairocy.screenquiz

/**
 * 蓝本 §3.2 D2b：多词/短语题的兜底猜。
 * 纯 JVM 逻辑，不依赖 Android，可直接跑本地单测。
 */
object PhraseGuesser {

    const val REASON_PHRASE = "phrase_partial"
    const val REASON_RANDOM = "random"

    data class GuessResult(
        val tokens: List<String>,
        val missed: List<String>,
        val picked: String?,
        val score: Double,
        val reason: String,
        val scores: List<Matcher.ScoredOption>
    )

    private val SPLIT_RE = Regex("[^A-Za-z]+")

    private val STOPWORDS = setOf(
        "sb", "sth", "one", "someone", "somebody", "something",
        "the", "a", "an", "of", "to"
    )

    /** 按「非字母」切分 → 丢弃空串、单字母、虚词（大小写不敏感），保留实词。 */
    fun tokenize(word: String): List<String> {
        val out = ArrayList<String>()
        for (raw in SPLIT_RE.split(word)) {
            if (raw.isEmpty()) continue
            val token = raw.lowercase()
            if (token.length < 2) continue
            if (token in STOPWORDS) continue
            out.add(token)
        }
        return out
    }

    /**
     * 词块 = 各 token 的 termProvider 结果合并（miss 记入 [GuessResult.missed]）；
     * 最高分 > 0 → picked=最高分选项（reason=phrase_partial）；否则 picked=null（调用方随机）。
     */
    fun guess(
        word: String,
        options: List<String>,
        termProvider: (String) -> List<String>
    ): GuessResult {
        val tokens = tokenize(word)
        val terms = ArrayList<String>()
        val missed = ArrayList<String>()
        for (token in tokens) {
            val hit = lookupTerms(token, termProvider)
            if (hit.isEmpty()) missed.add(token) else terms.addAll(hit)
        }
        val block = terms.distinct()
        val scores = options
            .map { Matcher.ScoredOption(it, Matcher.scoreOption(it, block)) }
            .sortedByDescending { it.score }
        val best = scores.firstOrNull()?.score ?: 0.0
        val picked = if (best > 0.0) scores[0].option else null
        return GuessResult(
            tokens = tokens,
            missed = missed,
            picked = picked,
            score = if (picked != null) best else 0.0,
            reason = if (picked != null) REASON_PHRASE else REASON_RANDOM,
            scores = scores
        )
    }

    /** 先查原 token，miss 时去尾 s/es 再试一次（蓝本 §3.2 可选步骤，覆盖 drawers→drawer）。 */
    private fun lookupTerms(token: String, termProvider: (String) -> List<String>): List<String> {
        termProvider(token).let { if (it.isNotEmpty()) return it }
        for (suffix in SUFFIXES) {
            if (!token.endsWith(suffix)) continue
            val base = token.dropLast(suffix.length)
            if (base.length < 2) continue
            val hit = termProvider(base)
            if (hit.isNotEmpty()) return hit
        }
        return emptyList()
    }

    private val SUFFIXES = listOf("s", "es")
}
