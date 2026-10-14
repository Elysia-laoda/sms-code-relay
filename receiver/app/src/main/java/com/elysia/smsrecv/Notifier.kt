package com.elysia.smsrecv

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifier {

    /** 高优先级（横幅）通知：游戏全屏时也能盖出来，带一键复制。 */
    fun showCode(ctx: Context, rec: CodeRecord) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val id = (System.currentTimeMillis() % 100000).toInt() + 1000

        val copyPi = PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, CopyReceiver::class.java)
                .setAction("com.elysia.smsrecv.COPY")
                .putExtra(CopyReceiver.EXTRA_CODE, rec.code.orEmpty())
                .putExtra(CopyReceiver.EXTRA_ID, rec.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (rec.code.isNullOrEmpty()) "收到一条转发短信" else "验证码 " + rec.code
        val text = (if (rec.simLabel.isBlank()) "" else rec.simLabel + " ") + rec.sender + " · " + Fmt.clip(rec.body, 60)

        val b = Notification.Builder(ctx, App.CH_CODE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(title + "\n" + text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_MAX)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openPi)
        if (!rec.code.isNullOrEmpty()) {
            b.addAction(Notification.Action.Builder(null, "复制验证码", copyPi).build())
        }
        nm.notify(id, b.build())
        Logx.i("已弹通知：" + title)
    }
}
