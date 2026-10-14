package com.elysia.smsrecv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Prefs.get().smsIntake()) {
            Logx.d("短信接收开关是关的，忽略一条")
            return
        }
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (msgs.isNullOrEmpty()) {
            Logx.w("SMS_RECEIVED 里没有消息体")
            return
        }
        runCatching { SmsIn.fromBroadcast(context, msgs) }
            .onFailure { Logx.e("处理短信广播失败", it) }
    }
}
