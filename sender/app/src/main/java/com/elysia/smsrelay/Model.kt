package com.elysia.smsrelay

/** 一条收到的短信（含未命中规则的；未命中的只留在本地库，不发出）。 */
data class SmsRecord(
    var id: Long = 0,
    var ts: Long = 0,
    var sender: String = "",
    var body: String = "",
    var slot: Int = -1,          // 0/1 = SIM 槽位，-1 = 未知
    var subId: Int = -1,
    var simLabel: String = "",
    var code: String? = null,
    var matched: Boolean = false,
    var matchReason: String = "",
    var forwarded: Boolean = false,
    var attempts: Int = 0,
    var nextAttemptAt: Long = 0,
    var lastError: String? = null,
    var sink: String? = null,
    var response: String? = null,
    var forwardedAt: Long = 0,
    var createdAt: Long = 0
) {
    val state: String
        get() = when {
            forwarded -> "已转发"
            !matched -> "未命中"
            attempts > 0 -> "重试中(" + attempts + ")"
            else -> "待发"
        }
}

/** 规则判定结果。 */
data class Decision(val forward: Boolean, val reason: String, val code: String?)
