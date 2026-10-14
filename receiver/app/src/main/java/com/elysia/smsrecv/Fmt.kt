package com.elysia.smsrecv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    private val full = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun time(ts: Long): String = if (ts <= 0) "-" else full.format(Date(ts))

    fun ago(ts: Long): String {
        if (ts <= 0) return "从未"
        val d = System.currentTimeMillis() - ts
        return when {
            d < 60_000 -> (d / 1000).toString() + " 秒前"
            d < 3600_000 -> (d / 60_000).toString() + " 分钟前"
            d < 86400_000 -> (d / 3600_000).toString() + " 小时前"
            else -> (d / 86400_000).toString() + " 天前"
        }
    }

    fun clip(s: String, n: Int): String = if (s.length <= n) s else s.substring(0, n) + "…"
}
