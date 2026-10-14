package com.elysia.smsrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * 只做三件事：取数据、算规则落盘、踢一下前台服务。
 * 广播接收器有 10 秒时限，所以这里绝不做网络。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (msgs.isNullOrEmpty()) {
            Logx.w("SMS_RECEIVED 里没有消息体")
            return
        }
        // 双卡归属：广播带的是 subscription id；国产 ROM 上可能缺失，缺失就记 -1
        val subId = intent.getIntExtra("subscription", -1)
        runCatching { Ingest.fromBroadcast(context, msgs, subId, "sms") }
            .onFailure { Logx.e("处理短信广播失败", it) }
    }
}
