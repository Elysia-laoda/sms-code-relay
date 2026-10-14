package com.elysia.smsrecv

import android.content.Context

class Prefs private constructor(ctx: Context) {

    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun serviceEnabled(): Boolean = sp.getBoolean(K_SVC, true)
    fun setServiceEnabled(v: Boolean) = sp.edit().putBoolean(K_SVC, v).apply()

    fun smsIntake(): Boolean = sp.getBoolean(K_SMS, true)
    fun setSmsIntake(v: Boolean) = sp.edit().putBoolean(K_SMS, v).apply()

    fun httpIntake(): Boolean = sp.getBoolean(K_HTTP, true)
    fun setHttpIntake(v: Boolean) = sp.edit().putBoolean(K_HTTP, v).apply()

    fun port(): Int = sp.getInt(K_PORT, 8788)
    fun setPort(v: Int) = sp.edit().putInt(K_PORT, v).apply()

    fun token(): String = sp.getString(K_TOKEN, "")!!
    fun setToken(v: String) = sp.edit().putString(K_TOKEN, v).apply()

    fun smsPrefix(): String = sp.getString(K_PREFIX, DEFAULT_PREFIX)!!
    fun setSmsPrefix(v: String) = sp.edit().putString(K_PREFIX, v).apply()

    fun overlayEnabled(): Boolean = sp.getBoolean(K_OVERLAY, true)
    fun setOverlayEnabled(v: Boolean) = sp.edit().putBoolean(K_OVERLAY, v).apply()

    fun overlayAutoHideSec(): Int = sp.getInt(K_HIDE, 20)
    fun setOverlayAutoHideSec(v: Int) = sp.edit().putInt(K_HIDE, v).apply()

    fun headsUp(): Boolean = sp.getBoolean(K_HEADSUP, true)
    fun setHeadsUp(v: Boolean) = sp.edit().putBoolean(K_HEADSUP, v).apply()

    /** 邮箱通道（与副端的邮箱通道配对）：POP3 拉取。 */
    fun mailEnabled(): Boolean = sp.getBoolean(K_MAIL_ON, false)
    fun setMailEnabled(v: Boolean) = sp.edit().putBoolean(K_MAIL_ON, v).apply()

    fun mailUser(): String = sp.getString(K_MAIL_USER, "")!!
    fun setMailUser(v: String) = sp.edit().putString(K_MAIL_USER, v).apply()

    fun mailPass(): String = sp.getString(K_MAIL_PASS, "")!!
    fun setMailPass(v: String) = sp.edit().putString(K_MAIL_PASS, v).apply()

    fun mailIntervalSec(): Int = sp.getInt(K_MAIL_INT, 20)
    fun setMailIntervalSec(v: Int) = sp.edit().putInt(K_MAIL_INT, v).apply()

    fun mailHost(): String = sp.getString(K_MAIL_HOST, "pop.qq.com")!!
    fun setMailHost(v: String) = sp.edit().putString(K_MAIL_HOST, v).apply()

    fun mailPort(): Int = sp.getInt(K_MAIL_PORT, 995)
    fun setMailPort(v: Int) = sp.edit().putInt(K_MAIL_PORT, v).apply()

    fun mailTls(): Boolean = sp.getBoolean(K_MAIL_TLS, true)
    fun setMailTls(v: Boolean) = sp.edit().putBoolean(K_MAIL_TLS, v).apply()

    companion object {
        const val DEFAULT_PREFIX = "[转发]"

        private const val K_SVC = "service_enabled"
        private const val K_SMS = "sms_intake"
        private const val K_HTTP = "http_intake"
        private const val K_PORT = "http_port"
        private const val K_TOKEN = "http_token"
        private const val K_PREFIX = "sms_prefix"
        private const val K_OVERLAY = "overlay_enabled"
        private const val K_HIDE = "overlay_hide_sec"
        private const val K_HEADSUP = "heads_up"
        private const val K_MAIL_ON = "mail_enabled"
        private const val K_MAIL_USER = "mail_user"
        private const val K_MAIL_PASS = "mail_pass"
        private const val K_MAIL_INT = "mail_interval_sec"
        private const val K_MAIL_HOST = "mail_host"
        private const val K_MAIL_PORT = "mail_port"
        private const val K_MAIL_TLS = "mail_tls"

        @Volatile
        private var inst: Prefs? = null

        fun get(): Prefs = inst ?: synchronized(this) {
            inst ?: Prefs(App.ctx()).also { inst = it }
        }
    }
}
