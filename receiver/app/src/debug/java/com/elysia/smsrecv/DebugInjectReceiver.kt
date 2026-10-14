package com.elysia.smsrecv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 仅开发期使用（只编进 debug 包）：
 *   DEBUG_SMS    模拟副机短信直发：--es body "[转发]10086|483920|SIM1|您的验证码是483920"
 *   DEBUG_HTTP   模拟副机 webhook 直推：--es json "{...}"
 *   DEBUG_TEST   直接造一条验证码
 *   DEBUG_CONFIG 改设置：--es prefix / --ei port / --es token / --ez overlay true
 */
class DebugInjectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val p = Prefs.get()
        when (intent.action) {
            "com.elysia.smsrecv.DEBUG_CONFIG" -> {
                intent.getStringExtra("prefix")?.let { p.setSmsPrefix(it) }
                intent.getStringExtra("token")?.let { p.setToken(it) }
                if (intent.hasExtra("port")) p.setPort(intent.getIntExtra("port", 8788))
                if (intent.hasExtra("overlay")) p.setOverlayEnabled(intent.getBooleanExtra("overlay", true))
                intent.getStringExtra("mail_user")?.let { p.setMailUser(it) }
                intent.getStringExtra("mail_pass")?.let { p.setMailPass(it) }
                intent.getStringExtra("mail_host")?.let { p.setMailHost(it) }
                if (intent.hasExtra("mail_port")) p.setMailPort(intent.getIntExtra("mail_port", 995))
                if (intent.hasExtra("mail_tls")) p.setMailTls(intent.getBooleanExtra("mail_tls", true))
                if (intent.hasExtra("mail_on")) p.setMailEnabled(intent.getBooleanExtra("mail_on", true))
                if (intent.hasExtra("mail_interval")) p.setMailIntervalSec(intent.getIntExtra("mail_interval", 20))
                Logx.i("debug 配置更新：port=" + p.port() + " prefix=" + p.smsPrefix())
                RecvService.start(context)
            }
            "com.elysia.smsrecv.DEBUG_SMS" -> {
                // 走与真实短信完全相同的那条解析路
                val body = intent.getStringExtra("body") ?: (p.smsPrefix() + "10086|483920|SIM1|您的验证码是 483920。")
                val from = intent.getStringExtra("from") ?: "13800000000"
                SmsIn.fromForwardText(context, from, body, System.currentTimeMillis())
            }
            "com.elysia.smsrecv.DEBUG_HTTP" -> {
                val json = intent.getStringExtra("json") ?: "{}"
                SmsIn.fromJson(context, json)
            }
            "com.elysia.smsrecv.DEBUG_TEST" -> {
                SmsIn.injectTest(context, "10086", (100000..999999).random().toString(), "SIM1(自测)", "【自测】您的验证码见上。")
            }
            else -> Logx.w("debug 接收器收到未知 action：" + intent.action)
        }
    }
}
