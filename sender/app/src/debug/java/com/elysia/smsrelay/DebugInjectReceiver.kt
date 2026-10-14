package com.elysia.smsrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 仅供开发期使用（只编进 debug 包）：
 *   adb shell am broadcast -a com.elysia.smsrelay.DEBUG_INJECT -n <pkg>/com.elysia.smsrelay.DebugInjectReceiver \
 *       --es sender 10086 --es body "您的验证码是 483920" --ei sub_id -1
 *   adb shell am broadcast -a com.elysia.smsrelay.DEBUG_CONFIG -n <pkg>/com.elysia.smsrelay.DebugInjectReceiver \
 *       --es sink_type webhook --es sink_url http://127.0.0.1:8787/ingest
 */
class DebugInjectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val p = Prefs.get()
        when (intent.action) {
            "com.elysia.smsrelay.DEBUG_CONFIG" -> {
                intent.getStringExtra("sink_type")?.let { p.setSinkType(it) }
                intent.getStringExtra("sink_url")?.let { p.setSinkUrl(it) }
                intent.getStringExtra("sink_token")?.let { p.setSinkToken(it) }
                intent.getStringExtra("sms_dest")?.let { p.setSmsDest(it) }
                intent.getStringExtra("sms_prefix")?.let { p.setSmsPrefix(it) }
                intent.getStringExtra("mail_user")?.let { p.setMailUser(it) }
                intent.getStringExtra("mail_pass")?.let { p.setMailPass(it) }
                intent.getStringExtra("mail_to")?.let { p.setMailTo(it) }
                intent.getStringExtra("mail_host")?.let { p.setMailHost(it) }
                if (intent.hasExtra("mail_port")) p.setMailPort(intent.getIntExtra("mail_port", 465))
                if (intent.hasExtra("mail_tls")) p.setMailTls(intent.getBooleanExtra("mail_tls", true))
                intent.getStringExtra("include")?.let { p.setIncludeKeywords(it) }
                intent.getStringExtra("exclude")?.let { p.setExcludeKeywords(it) }
                if (intent.hasExtra("digits")) p.setDigitsFallback(intent.getBooleanExtra("digits", true))
                Logx.i("debug 配置更新：sink=" + p.sinkType() + " url=" + p.sinkUrl())
            }
            "com.elysia.smsrelay.DEBUG_INJECT" -> {
                val sender = intent.getStringExtra("sender") ?: "10086"
                val body = intent.getStringExtra("body") ?: "【自测】您的验证码是 483920。"
                val subId = intent.getIntExtra("sub_id", -1)
                Ingest.handle(context, sender, body, subId, System.currentTimeMillis(), "debug")
            }
            else -> Logx.w("debug 接收器收到未知 action：" + intent.action)
        }
    }
}
