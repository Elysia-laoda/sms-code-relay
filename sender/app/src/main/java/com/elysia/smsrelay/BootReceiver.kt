package com.elysia.smsrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 覆盖安装后把服务拉回来。
 * Android 13 上从广播起前台服务是允许的（BOOT_COMPLETED 属于豁免场景），
 * 但真起不来也不报错——看护任务会在 15 分钟内再试。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        Logx.i("开机广播：" + a)
        if (!Prefs.get().serviceEnabled()) {
            Logx.i("设置里是关的，不拉起")
            return
        }
        ServiceController.scheduleWatchdog(context)
        ServiceController.startService(context)
    }
}
