package com.elysia.smsrelay

import android.content.Context

/**
 * 全部设置都在这里，默认值就是「开箱能用」的那一套：
 * 只转发含验证码类关键词或 4~8 位数字的短信，通道默认先落在本机通知（不外发）。
 */
class Prefs private constructor(ctx: Context) {

    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---- 服务开关 ----
    fun serviceEnabled(): Boolean = sp.getBoolean(KEY_SERVICE, true)
    fun setServiceEnabled(v: Boolean) = sp.edit().putBoolean(KEY_SERVICE, v).apply()

    // ---- 规则 ----
    fun includeKeywords(): String = sp.getString(KEY_INCLUDE, DEFAULT_INCLUDE)!!
    fun setIncludeKeywords(v: String) = sp.edit().putString(KEY_INCLUDE, v).apply()

    fun excludeKeywords(): String = sp.getString(KEY_EXCLUDE, DEFAULT_EXCLUDE)!!
    fun setExcludeKeywords(v: String) = sp.edit().putString(KEY_EXCLUDE, v).apply()

    fun digitsFallback(): Boolean = sp.getBoolean(KEY_DIGITS, true)
    fun setDigitsFallback(v: Boolean) = sp.edit().putBoolean(KEY_DIGITS, v).apply()

    fun includeList(): List<String> = splitLines(includeKeywords())
    fun excludeList(): List<String> = splitLines(excludeKeywords())

    // ---- 通道 ----
    fun sinkType(): String = sp.getString(KEY_SINK_TYPE, SINK_NOTIFY)!!
    fun setSinkType(v: String) = sp.edit().putString(KEY_SINK_TYPE, v).apply()

    fun sinkUrl(): String = sp.getString(KEY_SINK_URL, "")!!
    fun setSinkUrl(v: String) = sp.edit().putString(KEY_SINK_URL, v).apply()

    fun sinkToken(): String = sp.getString(KEY_SINK_TOKEN, "")!!
    fun setSinkToken(v: String) = sp.edit().putString(KEY_SINK_TOKEN, v).apply()

    /** 短信直发通道的目标号码（主机号）。 */
    fun smsDest(): String = sp.getString(KEY_SMS_DEST, "")!!
    fun setSmsDest(v: String) = sp.edit().putString(KEY_SMS_DEST, v).apply()

    /** 转发短信前缀，主端按它识别。 */
    fun smsPrefix(): String = sp.getString(KEY_SMS_PREFIX, DEFAULT_SMS_PREFIX)!!
    fun setSmsPrefix(v: String) = sp.edit().putString(KEY_SMS_PREFIX, v).apply()

    /** 邮箱通道：发件邮箱（QQ 邮箱地址）与授权码。 */
    fun mailUser(): String = sp.getString(KEY_MAIL_USER, "")!!
    fun setMailUser(v: String) = sp.edit().putString(KEY_MAIL_USER, v).apply()

    fun mailPass(): String = sp.getString(KEY_MAIL_PASS, "")!!
    fun setMailPass(v: String) = sp.edit().putString(KEY_MAIL_PASS, v).apply()

    /** 收件邮箱；留空表示发给自己。 */
    fun mailTo(): String = sp.getString(KEY_MAIL_TO, "")!!
    fun setMailTo(v: String) = sp.edit().putString(KEY_MAIL_TO, v).apply()

    /** SMTP 服务器（默认 QQ 邮箱；可改，便于自建或联调）。 */
    fun mailHost(): String = sp.getString(KEY_MAIL_HOST, "smtp.qq.com")!!
    fun setMailHost(v: String) = sp.edit().putString(KEY_MAIL_HOST, v).apply()

    fun mailPort(): Int = sp.getInt(KEY_MAIL_PORT, 465)
    fun setMailPort(v: Int) = sp.edit().putInt(KEY_MAIL_PORT, v).apply()

    /** 是否用隐式 TLS（QQ 邮箱为真；本机联调可关）。 */
    fun mailTls(): Boolean = sp.getBoolean(KEY_MAIL_TLS, true)
    fun setMailTls(v: Boolean) = sp.edit().putBoolean(KEY_MAIL_TLS, v).apply()

    fun notifyEach(): Boolean = sp.getBoolean(KEY_NOTIFY_EACH, true)
    fun setNotifyEach(v: Boolean) = sp.edit().putBoolean(KEY_NOTIFY_EACH, v).apply()

    // ---- 重试 ----
    fun retryWindowMin(): Int = sp.getInt(KEY_RETRY_WINDOW, 10)
    fun setRetryWindowMin(v: Int) = sp.edit().putInt(KEY_RETRY_WINDOW, v).apply()

    fun maxAttempts(): Int = sp.getInt(KEY_MAX_ATTEMPTS, 12)
    fun setMaxAttempts(v: Int) = sp.edit().putInt(KEY_MAX_ATTEMPTS, v).apply()

    companion object {
        const val SINK_WEBHOOK = "webhook"
        const val SINK_BARK = "bark"
        const val SINK_NOTIFY = "notify"
        const val SINK_SMS = "sms"
        const val SINK_EMAIL = "email"

        const val DEFAULT_SMS_PREFIX = "[转发]"

        const val DEFAULT_INCLUDE = "验证码\n校验码\n动态码\nverification code"
        const val DEFAULT_EXCLUDE = "退订\n营销\n推广\n优惠\n活动\n抽奖\n积分\n回T退订"

        private const val KEY_SERVICE = "service_enabled"
        private const val KEY_INCLUDE = "include_keywords"
        private const val KEY_EXCLUDE = "exclude_keywords"
        private const val KEY_DIGITS = "digits_fallback"
        private const val KEY_SINK_TYPE = "sink_type"
        private const val KEY_SINK_URL = "sink_url"
        private const val KEY_SINK_TOKEN = "sink_token"
        private const val KEY_SMS_DEST = "sms_dest"
        private const val KEY_SMS_PREFIX = "sms_prefix"
        private const val KEY_MAIL_USER = "mail_user"
        private const val KEY_MAIL_PASS = "mail_pass"
        private const val KEY_MAIL_TO = "mail_to"
        private const val KEY_MAIL_HOST = "mail_host"
        private const val KEY_MAIL_PORT = "mail_port"
        private const val KEY_MAIL_TLS = "mail_tls"
        private const val KEY_NOTIFY_EACH = "notify_each"
        private const val KEY_RETRY_WINDOW = "retry_window_min"
        private const val KEY_MAX_ATTEMPTS = "max_attempts"

        @Volatile
        private var inst: Prefs? = null

        fun get(): Prefs = inst ?: synchronized(this) {
            inst ?: Prefs(App.ctx()).also { inst = it }
        }

        private fun splitLines(s: String): List<String> =
            s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
