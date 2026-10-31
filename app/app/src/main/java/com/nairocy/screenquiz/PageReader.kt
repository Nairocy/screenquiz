package com.nairocy.screenquiz

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

enum class PageState { SETTLE, WORDBOOK, MATCHING, IN_GAME, PRACTICE, HUB, HOME, TRANSITION,
    STUDY_QUESTION, STUDY_DETAIL, STUDY_DONE, UNKNOWN }

data class OptionNode(
    val text: String,
    val bounds: Rect,
    val containerBounds: Rect? = null
)

data class PageSnapshot(
    val state: PageState,
    val word: String? = null,
    val options: List<OptionNode> = emptyList(),
    val settleRows: List<SettleRow> = emptyList(),
    val nums: List<String> = emptyList()   // 探针：页面上的数字文本（HUD 分数/倒计时）
) {
    fun logLine(): String {
        val sb = StringBuilder("state=").append(state.name)
        word?.let { sb.append(" word=").append(it) }
        if (word != null || options.isNotEmpty()) {
            sb.append(" options=[")
            options.forEachIndexed { index, option ->
                if (index > 0) sb.append("|")
                sb.append(option.text)
            }
            sb.append("]")
        }
        return sb.toString()
    }
}

internal data class RawNode(
    val className: String,
    val text: String,
    val desc: String,
    val clickable: Boolean,
    val bounds: Rect
)

class PageReader(private val ecdict: EcdictDb) {

    fun read(root: AccessibilityNodeInfo?, windowClass: String? = null): PageSnapshot {
        val nodes = ArrayList<RawNode>(512)
        collect(root, nodes, 0)
        return analyze(nodes, windowClass)
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<RawNode>, depth: Int) {
        if (node == null || depth > MAX_DEPTH) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        out.add(
            RawNode(
                className = node.className?.toString() ?: "",
                text = normalize(node.text?.toString()),
                desc = normalize(node.contentDescription?.toString()),
                clickable = node.isClickable,
                bounds = bounds
            )
        )
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, depth + 1)
        }
    }

    private fun normalize(raw: String?): String {
        if (raw == null) return ""
        return raw.replace(INVISIBLE, "").trim()
    }

    companion object {
        private const val MAX_DEPTH = 60

        private val INVISIBLE = Regex("[\\u200b-\\u200f\\u202a-\\u202e\\ufeff]")
        private val EN_RE = Pattern.compile("^[A-Za-z][A-Za-z'\\- ]*$")
        private val PHRASE_RE = Pattern.compile("^[A-Za-z][A-Za-z'\\u2019.\\- ]*$")
        private val NUM_RE = Pattern.compile("^[+\\-]?\\d+$")
        private val DIGIT_RE = Pattern.compile("\\d")
        private val CJK_RE = Pattern.compile("[\\u4e00-\\u9fff]")
        private val STUDY_DONE_RE = Pattern.compile("完成\\s*\\d+\\s*/\\s*\\d+\\s*组")

        private val EXCLUDE = setOf(
            "第", "题", "返回", "分享战绩", "奖励", "学习得分", "开始对战吧",
            "随机匹配", "好友对战", "全部战绩", "本周战绩", "练习赛", "排行榜",
            "错词本", "单词对战", "最后一题", "双倍得分"
        )

        internal fun analyze(nodes: List<RawNode>, windowClass: String? = null): PageSnapshot {
            val textNodes = nodes.filter { it.className.contains("TextView") && it.text.isNotEmpty() }
            val joined = nodes.mapNotNull { it.text.ifEmpty { null } }.joinToString(" ")
            val clickBounds = nodes.filter { it.clickable && !rectIsEmpty(it.bounds) }.map { it.bounds }

            val options = ArrayList<OptionNode>()
            val seen = HashSet<String>()
            for (node in textNodes) {
                if (!isChineseOptionText(node.text)) continue
                if (!seen.add(node.text)) continue
                // 可点容器是「锦上添花」不是必需：正常时能取到更贴身的容器；
                // 但 WebView 虚拟节点树退化时（clickable 丢失，见 STATUS 坑4）容器会整片消失，
                // 若因此丢弃选项，整局就会读不到选项 → 无法作答。故容器缺失也照样当选项。
                // 重叠最大、面积最小者胜（同重叠时取更贴身的容器；原先排序写反会选到整屏包裹层）
                options.add(optionNode(node, clickBounds))
            }

            var word: String? = null
            var bestHeight = 0
            for (node in textNodes) {
                val text = node.text
                if (text.length < 2 || text in EXCLUDE) continue
                if (DIGIT_RE.matcher(text).find()) continue
                if (!EN_RE.matcher(text).matches()) continue
                val height = rectHeight(node.bounds)
                if (height <= bestHeight) continue
                bestHeight = height
                word = text
            }
            if (word == null) {
                for (node in textNodes) {
                    val text = node.text
                    if (text.length < 2 || text in EXCLUDE) continue
                    if (DIGIT_RE.matcher(text).find()) continue
                    if (!PHRASE_RE.matcher(text).matches()) continue
                    val height = rectHeight(node.bounds)
                    if (height <= bestHeight) continue
                    bestHeight = height
                    word = text
                }
            }

            // 学习页判定：Activity 名 + 特征文本/desc 三重保险，必须排在原有 when 之前。
            // ⚠️ 结束页没有任何「斩词/重播发音」desc（工具栏已消失，只剩「隐藏释义/正确/本组奖励」），
            // 若只认 desc+Activity，windowClass 一旦没取到就会把结束页读成对战 IN_GAME（真机实测 25 题后误停）。
            val allDesc = nodes.mapNotNull { it.desc.ifEmpty { null } }.joinToString(" ")
            val isStudy = windowClass?.contains("StudySessionActivity") == true ||
                allDesc.contains("斩词") || allDesc.contains("重播发音") || allDesc.contains("播放发音") ||
                joined.contains("继续拼写") || joined.contains("完成今日计划") ||
                STUDY_DONE_RE.matcher(joined).find()
            if (isStudy) {
                val studyOptions = studyOptions(textNodes, clickBounds)
                val state = when {
                    joined.contains("完成今日计划") || STUDY_DONE_RE.matcher(joined).find() ->
                        PageState.STUDY_DONE
                    textNodes.any { it.text == "继续" } -> PageState.STUDY_DETAIL
                    studyOptions.size >= 4 -> PageState.STUDY_QUESTION
                    else -> PageState.UNKNOWN
                }
                // 复习B（图片/听音）页面上没有英文题面，若把英文选项当成最大的英文节点，
                // 现有 word 定位会误取选项文本 → 这里对 STUDY_QUESTION 做一次回退
                val studyWord = if (state == PageState.STUDY_QUESTION &&
                    studyOptions.any { it.text == word }
                ) {
                    null
                } else {
                    word
                }
                return PageSnapshot(
                    state,
                    studyWord,
                    if (state == PageState.STUDY_QUESTION) studyOptions else emptyList()
                )
            }

            val state = when {
                joined.contains("返回") && joined.contains("分享战绩") -> PageState.SETTLE
                joined.contains("单词数量：") -> PageState.WORDBOOK
                joined.contains("等待对手") -> PageState.MATCHING
                joined.contains("单词对战") -> PageState.HUB
                joined.contains("随机匹配") && joined.contains("好友对战") -> PageState.PRACTICE
                joined.contains("一起背") -> PageState.HOME   // 百词斩首页（地图页：入口全为画布渲染，无文本节点）
                word != null && options.size >= 3 -> PageState.IN_GAME
                options.size >= 3 -> PageState.TRANSITION
                else -> PageState.UNKNOWN
            }
            val settleRows = if (state == PageState.SETTLE) {
                SettleParser.parse(textNodes.map { it.text })
            } else {
                emptyList()
            }
            // 探针：题内页面上 <=6 位的纯数字（HUD 分数/倒计时），用于测「点击被 App 接受」的时刻
            val nums = if (state == PageState.IN_GAME || state == PageState.TRANSITION) {
                textNodes.map { it.text }.filter { it.length <= 6 && NUM_RE.matcher(it).matches() }
            } else {
                emptyList()
            }
            return PageSnapshot(state, word, options, settleRows = settleRows, nums = nums)
        }

        /**
         * 学习页选项收集。
         * 必须先按「选项格子」过滤：页面顶部还有「上一词」导航（`via: prep. 通过`），
         * 它同样是中文 TextView，不过滤就会变成第 5 个选项（W1 交付时实测暴露）。
         * 格子判据只看形状，与分辨率无关：宽够大、高宽比 0.45~0.9（实测 496×297 ≈ 0.6；
         * 顶部导航 440×132 ≈ 0.3 被排除），并排除整屏包裹层；每个文本取「包含它的最小格子」。
         */
        private fun studyOptions(textNodes: List<RawNode>, clickBounds: List<Rect>): List<OptionNode> {
            val slots = optionSlots(textNodes, clickBounds)
            if (slots.size >= 4) {
                val inSlots = ArrayList<OptionNode>()
                val seenInSlot = HashSet<String>()
                for (node in textNodes) {
                    val text = node.text
                    if (text.isEmpty() || text in EXCLUDE) continue
                    if (NUM_RE.matcher(text).matches()) continue
                    val slot = slots
                        .filter { rectContains(it, node.bounds) }
                        .minByOrNull { rectArea(it) } ?: continue
                    if (!seenInSlot.add(text)) continue
                    inSlots.add(OptionNode(text, node.bounds, copyBounds(slot)))
                }
                if (inSlots.size >= 4) return inSlots
            }
            // 无障碍树退化（拿不到选项格子）时退回按文本特征收集
            val options = ArrayList<OptionNode>()
            val seen = HashSet<String>()
            for (node in textNodes) {
                if (!isChineseOptionText(node.text)) continue
                if (!seen.add(node.text)) continue
                options.add(optionNode(node, clickBounds))
            }
            // ①② 用中文释义、③ 用英文近形词，两种题型互斥：
            // 中文选项已经凑够 4 个就不收英文，避免把中文题里的英文题面/例句误当选项
            if (options.size >= 4) return options
            for (node in textNodes) {
                val text = node.text
                if (text.length < 2 || text in EXCLUDE) continue
                if (!EN_RE.matcher(text).matches()) continue
                val container = bestContainer(clickBounds, node.bounds) ?: continue
                // 英文选项必须是可点容器里的子文本，否则无法与题面区分
                if (!rectContains(container, node.bounds)) continue
                if (!seen.add(text)) continue
                options.add(OptionNode(text, node.bounds, copyBounds(container)))
            }
            return options
        }

        /** 选项格子候选：宽 ≥200、高宽比 0.45~0.9、且**只装一个选项文本**（整屏/半屏包裹层会装多个 → 排除）。 */
        private fun optionSlots(textNodes: List<RawNode>, clickBounds: List<Rect>): List<Rect> {
            val texts = textNodes.filter {
                it.text.isNotEmpty() && it.text !in EXCLUDE && !NUM_RE.matcher(it.text).matches()
            }
            val out = ArrayList<Rect>()
            val seen = HashSet<String>()
            for (r in clickBounds) {
                val w = rectWidth(r)
                val h = rectHeight(r)
                if (w < 200 || h <= 0) continue
                val ratio = h.toDouble() / w
                if (ratio < 0.45 || ratio > 0.9) continue
                if (texts.count { rectContains(r, it.bounds) } != 1) continue
                val key = "${r.left},${r.top},${r.right},${r.bottom}"
                if (seen.add(key)) out.add(r)
            }
            return out
        }

        private fun isChineseOptionText(text: String): Boolean =
            text !in EXCLUDE && !NUM_RE.matcher(text).matches() && CJK_RE.matcher(text).find()

        private fun optionNode(node: RawNode, clickBounds: List<Rect>): OptionNode {
            val container = bestContainer(clickBounds, node.bounds)
            return OptionNode(node.text, node.bounds, container?.let { copyBounds(it) })
        }

        private fun bestContainer(clickBounds: List<Rect>, bounds: Rect): Rect? =
            clickBounds
                .filter { !rectIsEmpty(it) && overlapArea(it, bounds) > 0 }
                .sortedWith(
                    compareByDescending<Rect> { overlapArea(it, bounds) }
                        .thenBy { rectArea(it) }
                )
                .firstOrNull()

        private fun overlapArea(a: Rect, b: Rect): Long {
            val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            return if (w > 0 && h > 0) w.toLong() * h.toLong() else 0L
        }

        private fun rectArea(r: Rect): Long = rectWidth(r).toLong() * rectHeight(r).toLong()

        private fun rectWidth(r: Rect): Int = r.right - r.left

        private fun rectHeight(r: Rect): Int = r.bottom - r.top

        private fun rectIsEmpty(r: Rect): Boolean = r.left >= r.right || r.top >= r.bottom

        private fun rectContains(outer: Rect, inner: Rect): Boolean =
            !rectIsEmpty(outer) && !rectIsEmpty(inner) &&
                outer.left <= inner.left && outer.top <= inner.top &&
                outer.right >= inner.right && outer.bottom >= inner.bottom

        private fun copyBounds(r: Rect): Rect = Rect().apply {
            left = r.left
            top = r.top
            right = r.right
            bottom = r.bottom
        }
    }
}
