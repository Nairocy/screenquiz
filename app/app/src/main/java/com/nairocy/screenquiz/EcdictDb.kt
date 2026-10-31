package com.nairocy.screenquiz

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

class EcdictDb private constructor(private val context: Context) {

    private var db: SQLiteDatabase? = null

    val dbFile: File
        get() = File(context.filesDir, DB_NAME)

    private fun ensureDb(): SQLiteDatabase {
        synchronized(this) {
            db?.let { return it }
            val target = dbFile
            val expected = assetLength()
            if (!target.exists() || target.length() != expected) {
                copyAsset(target)
            }
            return SQLiteDatabase
                .openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .also { db = it }
        }
    }

    private fun assetLength(): Long = try {
        context.assets.openFd(ASSET_NAME).use { it.length }
    } catch (t: Throwable) {
        -1L
    }

    private fun copyAsset(target: File) {
        val dir = target.parentFile ?: return
        dir.mkdirs()
        val tmp = File(dir, "$DB_NAME.tmp")
        context.assets.open(ASSET_NAME).use { input ->
            tmp.outputStream().use { output ->
                input.copyTo(output, 64 * 1024)
            }
        }
        if (target.exists() && !target.delete()) {
            throw IllegalStateException("cannot replace ${target.absolutePath}")
        }
        if (!tmp.renameTo(target)) {
            throw IllegalStateException("cannot rename ${tmp.absolutePath}")
        }
    }

    fun lookup(word: String): String? {
        val key = word.lowercase()
        UserDict.get(context).get(key)?.let { return it }
        val database = ensureDb()
        val sql = "SELECT translation FROM dict WHERE word = ?"
        database.rawQuery(sql, arrayOf(key)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun count(): Int {
        val database = ensureDb()
        database.rawQuery("SELECT COUNT(*) FROM dict", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    companion object {
        private const val DB_NAME = "ecdict.db"
        private const val ASSET_NAME = "ecdict.db"

        @Volatile
        private var instance: EcdictDb? = null

        fun get(context: Context): EcdictDb {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: EcdictDb(context.applicationContext).also { instance = it }
            }
        }
    }
}
