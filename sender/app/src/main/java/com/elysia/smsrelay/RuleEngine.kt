package com.elysia.smsrelay

/**
 * 规则前置过滤：不相关的短信根本不进发送队列（既省流量，也少一次隐私暴露）。
 * 判定顺序刻意做成「先排除、后包含」：命中任何 exclude 一律不转发。
 */
object RuleEngine {

    /** 4~8 位连续数字，且左右不能是数字（避免把 11 位手机号、订单号的长数字当验证码）。 */
    private val CODE = Regex("(?<![0-9])[0-9]{4,8}(?![0-9])")

    /** 常见的「这就是验证码」句式，用来在正文里挑出真正的那串数字。 */
    private val CODE_HINT = Regex("(?i)(verification code|code|验证码|校验码|动态码|口令|密码)[^0-9]{0,12}([0-9]{4,8})")

    fun extractCode(body: String): String? {
        CODE_HINT.find(body)?.let { return it.groupValues[2] }
        return CODE.find(body)?.value
    }

    fun decide(
        body: String,
        sender: String,
        include: List<String>,
        exclude: List<String>,
        digitsFallback: Boolean
    ): Decision {
        val hitExclude = exclude.firstOrNull { contains(body, it) || contains(sender, it) }
        if (hitExclude != null) return Decision(false, "命中排除词:" + hitExclude, null)

        val hitInclude = include.firstOrNull { contains(body, it) || contains(sender, it) }
        val code = extractCode(body)
        if (hitInclude != null) return Decision(true, "命中关键词:" + hitInclude, code)
        if (digitsFallback && code != null) return Decision(true, "含 4~8 位数字:" + code, code)
        return Decision(false, "无关键词且无验证码数字", null)
    }

    private fun contains(hay: String, needle: String): Boolean =
        needle.isNotEmpty() && hay.contains(needle, ignoreCase = true)
}
