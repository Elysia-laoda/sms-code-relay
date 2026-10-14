package com.elysia.smsrelay

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地落盘：收到的短信 + 发送队列 + 运行日志，都在这一张库里。
 * 「失败落盘重试」靠的就是 msg 表里的 forwarded / attempts / next_attempt_at。
 */
class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE msg(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ts INTEGER NOT NULL," +
                "sender TEXT NOT NULL DEFAULT ''," +
                "body TEXT NOT NULL DEFAULT ''," +
                "slot INTEGER NOT NULL DEFAULT -1," +
                "sub_id INTEGER NOT NULL DEFAULT -1," +
                "sim_label TEXT NOT NULL DEFAULT ''," +
                "code TEXT," +
                "matched INTEGER NOT NULL DEFAULT 0," +
                "match_reason TEXT NOT NULL DEFAULT ''," +
                "forwarded INTEGER NOT NULL DEFAULT 0," +
                "attempts INTEGER NOT NULL DEFAULT 0," +
                "next_attempt_at INTEGER NOT NULL DEFAULT 0," +
                "last_error TEXT," +
                "sink TEXT," +
                "response TEXT," +
                "forwarded_at INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE UNIQUE INDEX idx_msg_dedupe ON msg(sender, body, ts)")
        db.execSQL("CREATE INDEX idx_msg_state ON msg(forwarded, matched, created_at)")
        db.execSQL("CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, level TEXT, msg TEXT)")
        db.execSQL("CREATE INDEX idx_log_ts ON log(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 起步，暂无迁移
    }

    /** 返回新行 id；重复投递（同发件人同内容同时刻）返回 -1。 */
    @Synchronized
    fun insert(r: SmsRecord): Long {
        val v = ContentValues().apply {
            put("ts", r.ts)
            put("sender", r.sender)
            put("body", r.body)
            put("slot", r.slot)
            put("sub_id", r.subId)
            put("sim_label", r.simLabel)
            put("code", r.code)
            put("matched", if (r.matched) 1 else 0)
            put("match_reason", r.matchReason)
            put("forwarded", 0)
            put("attempts", 0)
            put("next_attempt_at", 0L)
            put("created_at", if (r.createdAt > 0) r.createdAt else System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("msg", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** 待发队列：命中规则、没发出去、没超次数、退避到点、还在有效窗口内。 */
    @Synchronized
    fun pending(limit: Int, windowMin: Int, maxAttempts: Int): List<SmsRecord> {
        val now = System.currentTimeMillis()
        val since = now - windowMin * 60_000L
        return query(
            "SELECT * FROM msg WHERE matched=1 AND forwarded=0 AND attempts<? AND next_attempt_at<=? " +
                "AND created_at>=? ORDER BY created_at ASC LIMIT ?",
            arrayOf(maxAttempts.toString(), now.toString(), since.toString(), limit.toString())
        )
    }

    /** 下一条待发的退避到点时刻（不管退避是否已到点）；没有待发就返回 0。 */
    @Synchronized
    fun nextPendingAt(windowMin: Int, maxAttempts: Int): Long {
        val since = System.currentTimeMillis() - windowMin * 60_000L
        readableDatabase.rawQuery(
            "SELECT MIN(next_attempt_at) FROM msg WHERE matched=1 AND forwarded=0 AND attempts<? AND created_at>=?",
            arrayOf(maxAttempts.toString(), since.toString())
        ).use { c ->
            if (!c.moveToFirst() || c.isNull(0)) return 0L
            return c.getLong(0)
        }
    }

    @Synchronized
    fun markForwarded(id: Long, sink: String, response: String) {
        val v = ContentValues().apply {
            put("forwarded", 1)
            put("sink", sink)
            put("response", response.take(300))
            put("forwarded_at", System.currentTimeMillis())
            put("last_error", null as String?)
        }
        writableDatabase.update("msg", v, "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun markFailed(id: Long, attempts: Int, nextAt: Long, err: String) {
        val v = ContentValues().apply {
            put("attempts", attempts)
            put("next_attempt_at", nextAt)
            put("last_error", err.take(300))
        }
        writableDatabase.update("msg", v, "id=?", arrayOf(id.toString()))
    }

    /** 超出重试窗口的残留直接判死，免得每次扫队列都白试一遍。 */
    @Synchronized
    fun expireStale(windowMin: Int, maxAttempts: Int): Int {
        val since = System.currentTimeMillis() - windowMin * 60_000L
        val v = ContentValues().apply {
            put("attempts", maxAttempts)
            put("last_error", "超过重试窗口，已放弃")
        }
        return writableDatabase.update(
            "msg", v,
            "matched=1 AND forwarded=0 AND attempts<? AND created_at<?",
            arrayOf(maxAttempts.toString(), since.toString())
        )
    }

    @Synchronized
    fun recent(limit: Int): List<SmsRecord> =
        query("SELECT * FROM msg ORDER BY created_at DESC LIMIT ?", arrayOf(limit.toString()))

    @Synchronized
    fun pendingCount(maxAttempts: Int): Int =
        count2("SELECT COUNT(*) FROM msg WHERE matched=1 AND forwarded=0 AND attempts<?", arrayOf(maxAttempts.toString()))

    @Synchronized
    fun forwardedSince(since: Long): Int =
        count2("SELECT COUNT(*) FROM msg WHERE forwarded=1 AND forwarded_at>=?", arrayOf(since.toString()))

    @Synchronized
    fun receivedSince(since: Long): Int =
        count2("SELECT COUNT(*) FROM msg WHERE created_at>=?", arrayOf(since.toString()))

    @Synchronized
    fun lastForwardedAt(): Long {
        readableDatabase.rawQuery("SELECT MAX(forwarded_at) FROM msg WHERE forwarded=1", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    @Synchronized
    fun clearHistory() {
        writableDatabase.delete("msg", "forwarded=1 OR matched=0", null)
    }

    // ---- 日志 ----

    @Synchronized
    fun log(level: String, msg: String) {
        val v = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("level", level)
            put("msg", msg)
        }
        writableDatabase.insert("log", null, v)
        // 只留最近 400 条
        writableDatabase.execSQL("DELETE FROM log WHERE id NOT IN (SELECT id FROM log ORDER BY id DESC LIMIT 400)")
    }

    @Synchronized
    fun logs(limit: Int): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT ts, level, msg FROM log ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(Fmt.timeShort(c.getLong(0)) + " [" + c.getString(1) + "] " + c.getString(2))
            }
        }
        return out
    }

    @Synchronized
    fun clearLogs() {
        writableDatabase.delete("log", null, null)
    }

    // ---- 内部 ----

    private fun count2(sql: String, args: Array<String>): Int {
        readableDatabase.rawQuery(sql, args).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun query(sql: String, args: Array<String>): List<SmsRecord> {
        val out = ArrayList<SmsRecord>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out.add(map(c))
        }
        return out
    }

    private fun map(c: Cursor): SmsRecord = SmsRecord(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        ts = c.getLong(c.getColumnIndexOrThrow("ts")),
        sender = c.getString(c.getColumnIndexOrThrow("sender")) ?: "",
        body = c.getString(c.getColumnIndexOrThrow("body")) ?: "",
        slot = c.getInt(c.getColumnIndexOrThrow("slot")),
        subId = c.getInt(c.getColumnIndexOrThrow("sub_id")),
        simLabel = c.getString(c.getColumnIndexOrThrow("sim_label")) ?: "",
        code = c.getString(c.getColumnIndexOrThrow("code")),
        matched = c.getInt(c.getColumnIndexOrThrow("matched")) == 1,
        matchReason = c.getString(c.getColumnIndexOrThrow("match_reason")) ?: "",
        forwarded = c.getInt(c.getColumnIndexOrThrow("forwarded")) == 1,
        attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
        nextAttemptAt = c.getLong(c.getColumnIndexOrThrow("next_attempt_at")),
        lastError = c.getString(c.getColumnIndexOrThrow("last_error")),
        sink = c.getString(c.getColumnIndexOrThrow("sink")),
        response = c.getString(c.getColumnIndexOrThrow("response")),
        forwardedAt = c.getLong(c.getColumnIndexOrThrow("forwarded_at")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
    )

    companion object {
        private const val NAME = "smsrelay.db"
        private const val VERSION = 1

        @Volatile
        private var inst: Db? = null

        fun get(): Db = inst ?: synchronized(this) {
            inst ?: Db(App.ctx()).also { inst = it }
        }
    }
}
