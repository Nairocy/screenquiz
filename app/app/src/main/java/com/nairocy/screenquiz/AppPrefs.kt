package com.nairocy.screenquiz

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {
    private const val NAME = "screenquiz"
    const val KEY_AUTO_MODE = "auto_mode"
    const val KEY_BATTLE_TYPE = "battle_type"
    const val KEY_MAX_ROUNDS = "max_rounds"
    const val KEY_ENGINE_RUNNING = "engine_running"
    const val KEY_OVERLAY = "overlay"
    const val KEY_OVERLAY_X = "overlay_x"
    const val KEY_OVERLAY_Y = "overlay_y"
    const val KEY_HUD_X = "calib_hud_x"
    const val KEY_HUD_Y = "calib_hud_y"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 读对战类型：只认 practice，其余（含空/非法）一律回到 rank。 */
    fun battleType(context: Context): String {
        val value = get(context).getString(KEY_BATTLE_TYPE, Engine.BATTLE_RANK)
        return if (value == Engine.BATTLE_PRACTICE) Engine.BATTLE_PRACTICE else Engine.BATTLE_RANK
    }

    /** 读循环局数：空/非法/<1 → 默认 10。 */
    fun maxRounds(context: Context): Int {
        val value = get(context).getInt(KEY_MAX_ROUNDS, Engine.DEFAULT_MAX_ROUNDS)
        return if (value < 1) Engine.DEFAULT_MAX_ROUNDS else value
    }

    /** EditText 文本转循环局数：空/非法/<1 → 默认 10。 */
    fun parseMaxRounds(text: String?): Int {
        val value = text?.trim()?.toIntOrNull() ?: return Engine.DEFAULT_MAX_ROUNDS
        return if (value < 1) Engine.DEFAULT_MAX_ROUNDS else value
    }
}
