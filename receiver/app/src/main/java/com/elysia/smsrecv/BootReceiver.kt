package com.elysia.smsrecv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        Logx.i("系统广播：" + a)
        if (!Prefs.get().serviceEnabled()) {
            Logx.i("接收服务开关是关的，不拉起")
            return
        }
        RecvService.start(context)
    }
}
