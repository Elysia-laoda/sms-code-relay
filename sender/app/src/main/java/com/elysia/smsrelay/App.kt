package com.elysia.smsrelay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * 只在启动时做两件事：抓住 applicationContext（Db/Logx 要用），建好通知渠道。
 * 通知渠道必须在任何 startForeground 之前建好，否则 Android 8+ 的前台服务会静默失败。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        inst = this
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null) {
            val svc = NotificationChannel(
                CH_SERVICE,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            svc.description = getString(R.string.notif_channel_desc)
            svc.setShowBadge(false)
            nm.createNotificationChannel(svc)

            val alert = NotificationChannel(
                CH_ALERT,
                "转发内容",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            alert.description = "每转发一条短信在这里留一条可见记录（可关）"
            nm.createNotificationChannel(alert)
        }
        Logx.i("App onCreate, version " + Build0.VERSION_NAME)
    }

    companion object {
        const val CH_SERVICE = "relay_service"
        const val CH_ALERT = "relay_alert"

        @Volatile
        private var inst: App? = null

        val app: App
            get() = inst ?: throw IllegalStateException("App 还没起来")

        fun ctx(): android.content.Context = app.applicationContext
    }
}
