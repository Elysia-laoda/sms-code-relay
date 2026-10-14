package com.elysia.smsrecv

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteOpenHelper

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, VERSION) {

    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE code(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ts INTEGER NOT NULL," +
                "source TEXT NOT NULL DEFAULT ''," +
                "sender TEXT NOT NULL DEFAULT ''," +
                "sim_label TEXT NOT NULL DEFAULT ''," +
                "code TEXT," +
                "body TEXT NOT NULL DEFAULT ''," +
                "device TEXT NOT NULL DEFAULT ''," +
                "copied INTEGER NOT NULL DEFAULT 0," +
                "raw TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE INDEX idx_code_ts ON code(ts)")
        db.execSQL("CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, level TEXT, msg TEXT)")
        createMailSeen(db)
    }

    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createMailSeen(db)
    }

    private fun createMailSeen(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS mail_seen(uidl TEXT PRIMARY KEY, ts INTEGER NOT NULL)")
    }

    /** 这封邮件是不是已经处理过了（POP3 的 UIDL 去重）。 */
    @Synchronized
    fun hasMailUidl(uidl: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM mail_seen WHERE uidl=? LIMIT 1", arrayOf(uidl)).use { c ->
            return c.moveToFirst()
        }
    }

    @Synchronized
    fun markMailUidl(uidl: String) {
        val v = ContentValues().apply {
            put("uidl", uidl)
            put("ts", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("mail_seen", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun insert(r: CodeRecord): Long {
        val v = ContentValues().apply {
            put("ts", if (r.ts > 0) r.ts else System.currentTimeMillis())
            put("source", r.source)
            put("sender", r.sender)
            put("sim_label", r.simLabel)
            put("code", r.code)
            put("body", r.body)
            put("device", r.device)
            put("copied", 0)
            put("raw", r.raw.take(2000))
        }
        return writableDatabase.insert("code", null, v)
    }

    @Synchronized
    fun recent(limit: Int): List<CodeRecord> {
        val out = ArrayList<CodeRecord>()
        readableDatabase.rawQuery("SELECT * FROM code ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out.add(map(c))
        }
        return out
    }

    @Synchronized
    fun latest(): CodeRecord? {
        readableDatabase.rawQuery("SELECT * FROM code ORDER BY id DESC LIMIT 1", null).use { c ->
            return if (c.moveToNext()) map(c) else null
        }
    }

    @Synchronized
    fun countSince(since: Long): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM code WHERE ts>=?", arrayOf(since.toString())).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    @Synchronized
    fun markCopied(id: Long) {
        val v = ContentValues().apply { put("copied", 1) }
        writableDatabase.update("code", v, "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun clearAll() {
        writableDatabase.delete("code", null, null)
    }

    @Synchronized
    fun log(level: String, msg: String) {
        val v = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("level", level)
            put("msg", msg)
        }
        writableDatabase.insert("log", null, v)
        writableDatabase.execSQL("DELETE FROM log WHERE id NOT IN (SELECT id FROM log ORDER BY id DESC LIMIT 400)")
    }

    @Synchronized
    fun logs(limit: Int): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT ts, level, msg FROM log ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out.add(Fmt.time(c.getLong(0)) + " [" + c.getString(1) + "] " + c.getString(2))
        }
        return out
    }

    @Synchronized
    fun clearLogs() {
        writableDatabase.delete("log", null, null)
    }

    private fun map(c: Cursor): CodeRecord = CodeRecord(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        ts = c.getLong(c.getColumnIndexOrThrow("ts")),
        source = c.getString(c.getColumnIndexOrThrow("source")) ?: "",
        sender = c.getString(c.getColumnIndexOrThrow("sender")) ?: "",
        simLabel = c.getString(c.getColumnIndexOrThrow("sim_label")) ?: "",
        code = c.getString(c.getColumnIndexOrThrow("code")),
        body = c.getString(c.getColumnIndexOrThrow("body")) ?: "",
        device = c.getString(c.getColumnIndexOrThrow("device")) ?: "",
        copied = c.getInt(c.getColumnIndexOrThrow("copied")) == 1,
        raw = c.getString(c.getColumnIndexOrThrow("raw")) ?: ""
    )

    companion object {
        private const val NAME = "smsrecv.db"
        private const val VERSION = 2

        @Volatile
        private var inst: Db? = null

        fun get(): Db = inst ?: synchronized(this) {
            inst ?: Db(App.ctx()).also { inst = it }
        }
    }
}
