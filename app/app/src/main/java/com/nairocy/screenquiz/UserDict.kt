package com.nairocy.screenquiz

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

data class WordRow(val word: String, val translation: String)

/**
 * 用户词典层（PLAN-v2-learning §3.1）：独立 sqlite 文件，存百词斩措辞的释义。
 * 写入来源：manual（手动纠错）/ settle（每局结算对账）。
 */
class UserDict private constructor(private val context: Context) {

    data class Row(
        val word: String,
        val translation: String,
        val source: String,
        val updatedAt: Long
    )

    private var db: SQLiteDatabase? = null

    val dbFile: File
        get() = File(context.filesDir, DB_NAME)

    private fun ensureDb(): SQLiteDatabase {
        synchronized(this) {
            db?.let { return it }
            return SQLiteDatabase
                .openOrCreateDatabase(dbFile.absolutePath, null)
                .also {
                    it.execSQL(
                        "CREATE TABLE IF NOT EXISTS user_dict (" +
                            "word TEXT PRIMARY KEY, " +
                            "translation TEXT NOT NULL, " +
                            "source TEXT NOT NULL, " +
                            "updated_at INTEGER NOT NULL)"
                    )
                    db = it
                }
        }
    }

    fun upsert(word: String, translation: String, source: String) {
        val database = ensureDb()
        val key = word.lowercase()
        val current = existingSource(database, key)
        if (current != null && priorityOf(current) >= priorityOf(source)) return
        database.execSQL(
            "INSERT OR REPLACE INTO user_dict(word, translation, source, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf(key, translation, source, System.currentTimeMillis())
        )
    }

    fun upsertAll(rows: List<WordRow>, source: String) {
        if (rows.isEmpty()) return
        val database = ensureDb()
        val now = System.currentTimeMillis()
        val newPriority = priorityOf(source)
        database.beginTransaction()
        try {
            val existing = HashMap<String, String>()
            database.rawQuery("SELECT word, source FROM user_dict", null).use { cursor ->
                while (cursor.moveToNext()) {
                    existing[cursor.getString(0)] = cursor.getString(1)
                }
            }
            val statement = database.compileStatement(
                "INSERT OR REPLACE INTO user_dict(word, translation, source, updated_at) VALUES (?, ?, ?, ?)"
            )
            for (row in rows) {
                val key = row.word.lowercase()
                val current = existing[key]
                if (current != null && priorityOf(current) >= newPriority) continue
                statement.clearBindings()
                statement.bindString(1, key)
                statement.bindString(2, row.translation)
                statement.bindString(3, source)
                statement.bindLong(4, now)
                statement.executeInsert()
                existing[key] = source
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun existingSource(database: SQLiteDatabase, word: String): String? {
        database.rawQuery(
            "SELECT source FROM user_dict WHERE word = ?",
            arrayOf(word)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun get(word: String): String? {
        val database = ensureDb()
        database.rawQuery(
            "SELECT translation FROM user_dict WHERE word = ?",
            arrayOf(word.lowercase())
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun count(): Int {
        val database = ensureDb()
        database.rawQuery("SELECT COUNT(*) FROM user_dict", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /** 删除一条（主界面「已学习词条」抽屉里长按纠错用） */
    fun delete(word: String) {
        val database = ensureDb()
        database.execSQL("DELETE FROM user_dict WHERE word = ?", arrayOf(word.lowercase()))
    }

    /** 原样恢复一条（删除的撤销用；保留原 source/updated_at，不被优先级规则改写） */
    fun restore(row: Row) {
        val database = ensureDb()
        database.execSQL(
            "INSERT OR REPLACE INTO user_dict(word, translation, source, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf(row.word.lowercase(), row.translation, row.source, row.updatedAt)
        )
    }

    fun all(): List<Row> {
        val database = ensureDb()
        database.rawQuery(
            "SELECT word, translation, source, updated_at FROM user_dict ORDER BY word",
            null
        ).use { cursor ->
            val rows = ArrayList<Row>(cursor.count)
            while (cursor.moveToNext()) {
                rows.add(Row(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
            }
            return rows
        }
    }

    // ---------- 导出 / 导入（db 文件即原生格式，无损往返） ----------

    data class ImportReport(val fileRows: Int, val written: Int)

    /**
     * 导出一份**一致的**快照到 outFile（VACUUM INTO：不会读到写一半的页）。
     * VACUUM INTO 不吃绑定参数、且目标已存在会失败 → 先删；异常时退化为字节拷贝。
     */
    fun snapshot(outFile: File): File {
        val database = ensureDb()
        if (outFile.exists()) outFile.delete()
        try {
            database.execSQL("VACUUM INTO '" + outFile.absolutePath.replace("'", "''") + "'")
            if (outFile.exists() && outFile.length() > 0L) return outFile
        } catch (t: Throwable) {
            // 落到下面的字节拷贝
        }
        dbFile.inputStream().use { input -> outFile.outputStream().use { input.copyTo(it) } }
        return outFile
    }

    /** 校验外部 db：结构正确返回其行数，否则 null（不修改本库） */
    fun inspect(file: File): Int? = try {
        openSource(file).use { it.count }
    } catch (t: Throwable) {
        null
    }

    /** 合并导入：逐行按优先级规则写入（现有行优先级 >= 来者 → 不覆盖） */
    fun mergeFrom(file: File): ImportReport {
        val database = ensureDb()
        openSource(file).use { src ->
            val existing = HashMap<String, String>()
            database.rawQuery("SELECT word, source FROM user_dict", null).use { cursor ->
                while (cursor.moveToNext()) existing[cursor.getString(0)] = cursor.getString(1)
            }
            var written = 0
            database.beginTransaction()
            try {
                val statement = database.compileStatement(INSERT_SQL)
                src.db.rawQuery(SELECT_SQL, null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val word = cursor.getString(0)?.trim()?.lowercase().orEmpty()
                        val translation = cursor.getString(1)?.trim().orEmpty()
                        if (word.isEmpty() || translation.isEmpty()) continue
                        val source = cursor.getString(2)?.trim().orEmpty().ifEmpty { SOURCE_IMPORT }
                        val at = cursor.getLong(3)
                        val current = existing[word]
                        if (current != null && priorityOf(current) >= priorityOf(source)) continue
                        bindRow(statement, word, translation, source, at)
                        existing[word] = source
                        written++
                    }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            return ImportReport(src.count, written)
        }
    }

    /** 覆盖导入：清空后整体替换（不做优先级过滤，文件说了算） */
    fun replaceFrom(file: File): ImportReport {
        val database = ensureDb()
        openSource(file).use { src ->
            var written = 0
            database.beginTransaction()
            try {
                database.execSQL("DELETE FROM user_dict")
                val statement = database.compileStatement(INSERT_SQL)
                src.db.rawQuery(SELECT_SQL, null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val word = cursor.getString(0)?.trim()?.lowercase().orEmpty()
                        val translation = cursor.getString(1)?.trim().orEmpty()
                        if (word.isEmpty() || translation.isEmpty()) continue
                        val source = cursor.getString(2)?.trim().orEmpty().ifEmpty { SOURCE_IMPORT }
                        bindRow(statement, word, translation, source, cursor.getLong(3))
                        written++
                    }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            return ImportReport(src.count, written)
        }
    }

    private fun bindRow(statement: android.database.sqlite.SQLiteStatement, word: String, translation: String, source: String, at: Long) {
        statement.clearBindings()
        statement.bindString(1, word)
        statement.bindString(2, translation)
        statement.bindString(3, source)
        statement.bindLong(4, if (at > 0L) at else System.currentTimeMillis())
        statement.executeInsert()
    }

    private class Source(val db: SQLiteDatabase, val count: Int) : Closeable {
        override fun close() {
            try {
                db.close()
            } catch (t: Throwable) {
                // 忽略
            }
        }
    }

    /** 只读打开外部 db 并校验结构（表 user_dict + 四个必需列） */
    private fun openSource(file: File): Source {
        val src = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            var hasTable = false
            src.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='user_dict'", null
            ).use { cursor -> hasTable = cursor.moveToFirst() }
            if (!hasTable) throw IllegalArgumentException("no user_dict table")
            val columns = HashSet<String>()
            src.rawQuery("PRAGMA table_info(user_dict)", null).use { cursor ->
                while (cursor.moveToNext()) columns.add(cursor.getString(1))
            }
            for (need in REQUIRED_COLUMNS) {
                if (need !in columns) throw IllegalArgumentException("missing column $need")
            }
            val count = src.rawQuery("SELECT COUNT(*) FROM user_dict", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            return Source(src, count)
        } catch (t: Throwable) {
            try {
                src.close()
            } catch (ignored: Throwable) {
                // 忽略
            }
            throw t
        }
    }

    companion object {
        private const val DB_NAME = "user_dict.db"
        private const val SOURCE_IMPORT = "import"
        private const val INSERT_SQL =
            "INSERT OR REPLACE INTO user_dict(word, translation, source, updated_at) VALUES (?, ?, ?, ?)"
        private const val SELECT_SQL =
            "SELECT word, translation, source, updated_at FROM user_dict"
        private val REQUIRED_COLUMNS = listOf("word", "translation", "source", "updated_at")

        // 来源优先级：低优先级不得覆盖高优先级既有行（历史 wrongbook 行按 0 处理，可被 settle 覆盖）
        private val PRIORITY = mapOf("manual" to 4, "settle" to 3)

        private fun priorityOf(source: String): Int = PRIORITY[source] ?: 0

        @Volatile
        private var instance: UserDict? = null

        fun get(context: Context): UserDict {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: UserDict(context.applicationContext).also { instance = it }
            }
        }
    }
}
