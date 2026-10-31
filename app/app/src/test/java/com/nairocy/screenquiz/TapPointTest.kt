package com.nairocy.screenquiz

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TapPoint 的存取与回落逻辑（真机校准值的唯一写入路径）。 */
class TapPointTest {

    @Test
    fun unsetFallsBackToDefault() {
        val prefs = FakePrefs()
        for (point in TapPoint.values()) {
            assertFalse(point.isCalibrated(prefs))
            val (x, y) = point.resolve(prefs)
            assertEquals(point.defaultX, x, 1e-9)
            assertEquals(point.defaultY, y, 1e-9)
        }
    }

    @Test
    fun savedValueWins() {
        val prefs = FakePrefs()
        TapPoint.PK.save(prefs, 0.2024, 0.5312)
        assertTrue(TapPoint.PK.isCalibrated(prefs))
        val (x, y) = TapPoint.PK.resolve(prefs)
        assertEquals(0.2024, x, 1e-6)
        assertEquals(0.5312, y, 1e-6)
    }

    @Test
    fun clearRestoresDefault() {
        val prefs = FakePrefs()
        TapPoint.EXIT.save(prefs, 0.3, 0.4)
        TapPoint.EXIT.clear(prefs)
        assertFalse(TapPoint.EXIT.isCalibrated(prefs))
        val (x, y) = TapPoint.EXIT.resolve(prefs)
        assertEquals(TapPoint.EXIT.defaultX, x, 1e-9)
        assertEquals(TapPoint.EXIT.defaultY, y, 1e-9)
    }

    @Test
    fun edgeValuesTreatedAsAbsent() {
        val prefs = FakePrefs()
        // 贴边/越界几乎一定是误拖或写坏，回落默认比照点更安全
        listOf(0f, -0.2f, 1f, 1.5f).forEach { bad ->
            TapPoint.MATCH_BACK.save(prefs, bad.toDouble(), bad.toDouble())
            assertFalse("bad=$bad", TapPoint.MATCH_BACK.isCalibrated(prefs))
            val (x, y) = TapPoint.MATCH_BACK.resolve(prefs)
            assertEquals(TapPoint.MATCH_BACK.defaultX, x, 1e-9)
            assertEquals(TapPoint.MATCH_BACK.defaultY, y, 1e-9)
        }
    }

    /** 只实现 TapPoint 用到的 float 读写，其余按需抛错以保证测试不走偏。 */
    private class FakePrefs : SharedPreferences {
        private val values = HashMap<String, Any>()

        override fun getAll(): MutableMap<String, *> = values

        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float =
            values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(values)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }

    private class FakeEditor(private val values: HashMap<String, Any>) : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            if (value == null) values.remove(key) else values[key] = value
        }

        override fun putStringSet(key: String, value: MutableSet<String>?): SharedPreferences.Editor =
            apply { if (value == null) values.remove(key) else values[key] = value }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
            values[key] = value
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
            values[key] = value
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
            values[key] = value
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
            values[key] = value
        }

        override fun remove(key: String): SharedPreferences.Editor = apply { values.remove(key) }

        override fun clear(): SharedPreferences.Editor = apply { values.clear() }

        override fun commit(): Boolean = true

        override fun apply() = Unit
    }
}
