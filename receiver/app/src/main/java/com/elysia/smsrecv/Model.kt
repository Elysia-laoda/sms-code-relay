package com.elysia.smsrecv

/** 一条收到的验证码（来源可能是短信直发、局域网直推，或本地自测）。 */
data class CodeRecord(
    var id: Long = 0,
    var ts: Long = 0,
    var source: String = "",
    var sender: String = "",
    var simLabel: String = "",
    var code: String? = null,
    var body: String = "",
    var device: String = "",
    var copied: Boolean = false,
    var raw: String = ""
) {
    val title: String
        get() = (if (code.isNullOrEmpty()) "无码" else code!!) + "  ←  " + (if (simLabel.isBlank()) "SIM?" else simLabel) + " " + sender
}
