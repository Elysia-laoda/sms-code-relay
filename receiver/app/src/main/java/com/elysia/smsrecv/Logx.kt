package com.elysia.smsrecv

import android.util.Log

/** logcat + 本地库双写（日志只留最近 400 条）。 */
object Logx {
    private const val TAG = "SmsRecv"

    fun d(msg: String) = write("D", msg)
    fun i(msg: String) = write("I", msg)
    fun w(msg: String) = write("W", msg)
    fun e(msg: String, t: Throwable? = null) = write("E", msg + (t?.let { " | " + it.javaClass.simpleName + ": " + it.message } ?: ""))

    private fun write(level: String, msg: String) {
        when (level) {
            "E" -> Log.e(TAG, msg)
            "W" -> Log.w(TAG, msg)
            "I" -> Log.i(TAG, msg)
            else -> Log.d(TAG, msg)
        }
        runCatching { Db.get().log(level, msg) }
    }
}
