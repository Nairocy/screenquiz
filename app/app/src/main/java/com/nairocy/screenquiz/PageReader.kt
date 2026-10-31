package com.nairocy.screenquiz

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

enum class PageState { SETTLE, WORDBOOK, MATCHING, IN_GAME, PRACTICE, HUB, HOME, TRANSITION, UNKNOWN }

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

private data class RawNode(
    val className: String,
    val text: String,
    val clickable: Boolean,
    val bounds: Rect
)

class PageReader(private val ecdict: EcdictDb) {

    fun read(root: AccessibilityNodeInfo?): PageSnapshot {
        val nodes = ArrayList<RawNode>(512)
        collect(root, nodes, 0)
        return analyze(nodes)
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<RawNode>, depth: Int) {
        if (node == null || depth > MAX_DEPTH) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        out.add(
            RawNode(
                className = node.className?.toString() ?: "",
                text = normalize(node.text?.toString()),
                clickable = node.isClickable,
                bounds = bounds
            )
        )
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, depth + 1)
        }
    }

    private fun analyze(nodes: List<RawNode>): PageSnapshot {
        val textNodes = nodes.filter { it.className.contains("TextView") && it.text.isNotEmpty() }
        val joined = nodes.mapNotNull { it.text.ifEmpty { null } }.joinToString(" ")
        val clickBounds = nodes.filter { it.clickable && !it.bounds.isEmpty }.map { it.bounds }

        val options = ArrayList<OptionNode>()
        val seen = HashSet<String>()
        for (node in textNodes) {
            val text = node.text
            if (text in EXCLUDE) continue
            if (NUM_RE.matcher(text).matches()) continue
            if (!CJK_RE.matcher(text).find()) continue
            if (!seen.add(text)) continue
            val cx = node.bounds.centerX()
            val cy = node.bounds.centerY()
            // 可点容器是「锦上添花」不是必需：正常时能取到更贴身的容器；
            // 但 WebView 虚拟节点树退化时（clickable 丢失，见 STATUS 坑4）容器会整片消失，
            // 若因此丢弃选项，整局就会读不到选项 → 无法作答。故容器缺失也照样当选项。
            // 重叠最大、面积最小者胜（同重叠时取更贴身的容器；原先排序写反会选到整屏包裹层）
            val container = clickBounds
                .filter { !it.isEmpty && Rect.intersects(it, node.bounds) }
                .sortedWith(
                    compareByDescending<Rect> { overlapArea(it, node.bounds) }
                        .thenBy { it.width().toLong() * it.height().toLong() }
                )
                .firstOrNull()
            options.add(OptionNode(text, node.bounds, container?.let { Rect(it) }))
        }

        var word: String? = null
        var bestHeight = 0
        for (node in textNodes) {
            val text = node.text
            if (text.length < 2 || text in EXCLUDE) continue
            if (DIGIT_RE.matcher(text).find()) continue
            if (!EN_RE.matcher(text).matches()) continue
            val height = node.bounds.height()
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
                val height = node.bounds.height()
                if (height <= bestHeight) continue
                bestHeight = height
                word = text
            }
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

    private fun normalize(raw: String?): String {
        if (raw == null) return ""
        return raw.replace(INVISIBLE, "").trim()
    }

    private fun overlapArea(a: Rect, b: Rect): Long {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (w > 0 && h > 0) w.toLong() * h.toLong() else 0L
    }

    companion object {
        private const val MAX_DEPTH = 60

        private val INVISIBLE = Regex("[\\u200b-\\u200f\\u202a-\\u202e\\ufeff]")
        private val EN_RE = Pattern.compile("^[A-Za-z][A-Za-z'\\- ]*$")
        private val PHRASE_RE = Pattern.compile("^[A-Za-z][A-Za-z'\\u2019.\\- ]*$")
        private val NUM_RE = Pattern.compile("^[+\\-]?\\d+$")
        private val DIGIT_RE = Pattern.compile("\\d")
        private val CJK_RE = Pattern.compile("[\\u4e00-\\u9fff]")

        private val EXCLUDE = setOf(
            "第", "题", "返回", "分享战绩", "奖励", "学习得分", "开始对战吧",
            "随机匹配", "好友对战", "全部战绩", "本周战绩", "练习赛", "排行榜",
            "错词本", "单词对战", "最后一题", "双倍得分"
        )
    }
}
