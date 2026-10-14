package com.elysia.smsrecv

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        inst = this
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null) {
            // 验证码通道：必须是 HIGH，才能在游戏全屏时弹横幅
            val code = NotificationChannel(CH_CODE, getString(R.string.notif_channel_code), NotificationManager.IMPORTANCE_HIGH)
            code.description = "收到验证码时的高优先级提醒"
            code.enableVibration(true)
            nm.createNotificationChannel(code)

            val svc = NotificationChannel(CH_SERVICE, getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW)
            svc.description = "常驻通知，保持局域网接收服务存活"
            svc.setShowBadge(false)
            nm.createNotificationChannel(svc)
        }
        Logx.i("App onCreate, version " + Build0.VERSION_NAME)
    }

    companion object {
        const val CH_CODE = "code_alert"
        const val CH_SERVICE = "recv_service"

        @Volatile
        private var inst: App? = null

        val app: App get() = inst ?: throw IllegalStateException("App 还没起来")

        fun ctx(): android.content.Context = app.applicationContext
    }
}
