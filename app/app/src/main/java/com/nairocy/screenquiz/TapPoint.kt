package com.nairocy.screenquiz

import android.content.SharedPreferences

/**
 * 只能按坐标点的画布目标（无障碍树里没有文本节点可点）。
 *
 * 不同屏占比下这些元素在整屏上的比例会漂移（底部导航/画布拉伸各不相同），
 * 所以每个点都支持用「悬浮准星」校准；校准值以整屏比例（0–1）存 prefs，
 * 未校准则回落到默认常量，老行为不变。
 */
enum class TapPoint(
    val prefKey: String,
    val defaultX: Double,
    val defaultY: Double,
    val labelRes: Int
) {
    /** 百词斩首页左下「PK」交叉剑入口（首页地图是画布渲染，无文本节点）。 */
    PK("tap_pk", 0.139, 0.501, R.string.calib_target_pk),

    /** 对战页左上角「X」＝完全退出重进（它左边的「<」只回退一层，是嵌套累积之源）。 */
    EXIT("tap_exit", 0.152, 0.065, R.string.calib_target_exit),

    /** 匹配中页面左上角「<」＝放弃匹配返回。 */
    MATCH_BACK("tap_match_back", 0.071, 0.065, R.string.calib_target_match_back);

    private val keyX: String get() = prefKey + "_x"
    private val keyY: String get() = prefKey + "_y"

    /** 生效坐标（整屏比例）：已校准用校准值，否则用默认常量。 */
    fun resolve(prefs: SharedPreferences): Pair<Double, Double> {
        val x = prefs.getFloat(keyX, ABSENT)
        val y = prefs.getFloat(keyY, ABSENT)
        return if (valid(x) && valid(y)) x.toDouble() to y.toDouble() else defaultX to defaultY
    }

    fun isCalibrated(prefs: SharedPreferences): Boolean =
        valid(prefs.getFloat(keyX, ABSENT)) && valid(prefs.getFloat(keyY, ABSENT))

    /** 写入校准值（整屏比例，0–1）。 */
    fun save(prefs: SharedPreferences, x: Double, y: Double) {
        prefs.edit().putFloat(keyX, x.toFloat()).putFloat(keyY, y.toFloat()).apply()
    }

    /** 丢掉校准值，回到默认常量。 */
    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(keyX).remove(keyY).apply()
    }

    companion object {
        private const val ABSENT = -1f

        /** 贴边/越界值视为无效：留下的极端值几乎一定是误拖，回默认更安全。 */
        private fun valid(v: Float) = v > 0.0005f && v < 0.9995f

        const val BAICIZHAN_PACKAGE = "com.jiongji.andriod.card"
    }
}
