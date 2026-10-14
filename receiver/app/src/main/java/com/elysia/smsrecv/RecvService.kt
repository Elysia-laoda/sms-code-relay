package com.elysia.smsrecv

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * 常驻前台服务：维持局域网 HTTP 接收端与常驻通知。
 * 短信通道即使没有它也工作（广播直接拉起进程），但留着它能保证 HTTP 端口一直开着。
 */
class RecvService : Service() {

    private var server: HttpServer? = null
    private var mailThread: Thread? = null

    @Volatile
    private var mailRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(NOTIF_ID, buildNotification("服务已启动"))
        startServer()
        startMailLoop()
        Logx.i("接收服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startServer()
        startMailLoop()
        refresh(this)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        mailRunning = false
        runCatching { server?.stop() }
        server = null
        Logx.i("接收服务已结束")
        super.onDestroy()
    }

    private fun startServer() {
        val prefs = Prefs.get()
        if (!prefs.httpIntake()) {
            runCatching { server?.stop() }
            server = null
            Logx.i("HTTP 接收开关是关的，不监听")
            return
        }
        val port = prefs.port()
        if (server != null && server?.port == port && server?.running == true) return
        runCatching { server?.stop() }
        val s = HttpServer(port) { body, token ->
            val expect = Prefs.get().token()
            if (expect.isNotEmpty() && token != expect) {
                Logx.w("令牌不匹配，拒绝一条推送")
                401 to "{\"ok\":false,\"err\":\"bad token\"}"
            } else {
                try {
                    SmsIn.fromJson(this, body)
                    200 to "{\"ok\":true,\"got\":1}"
                } catch (t: Throwable) {
                    Logx.e("解析推送失败", t)
                    400 to "{\"ok\":false,\"err\":\"bad json\"}"
                }
            }
        }
        if (s.start()) server = s
    }

    /** 邮箱通道：独立线程按间隔拉 POP3（短信通道不受影响）。 */
    private fun startMailLoop() {
        if (!Prefs.get().mailEnabled()) {
            mailRunning = false
            return
        }
        if (mailThread?.isAlive == true && mailRunning) return
        mailRunning = true
        mailThread = Thread({
            Logx.i("邮箱轮询线程启动，间隔 " + Prefs.get().mailIntervalSec() + " 秒")
            while (mailRunning) {
                runCatching { MailPoller.pollOnce(this) }
                    .onFailure { Logx.w("邮箱轮询出错：" + it.message) }
                val iv = Prefs.get().mailIntervalSec().coerceIn(5, 600)
                var slept = 0
                while (mailRunning && slept < iv * 1000) {
                    Thread.sleep(500)
                    slept += 500
                }
            }
            Logx.i("邮箱轮询线程结束")
        }, "smsrecv-mail").apply {
            isDaemon = true
            start()
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, App.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("验证码接收 · 运行中")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.elysia.smsrecv.action.STOP"
        const val NOTIF_ID = 2001

        @Volatile
        var running: Boolean = false
            private set

        fun start(ctx: Context) {
            Prefs.get().setServiceEnabled(true)
            runCatching { ctx.startForegroundService(Intent(ctx, RecvService::class.java)) }
                .onFailure { Logx.w("起服务被拒：" + it.message) }
        }

        fun stop(ctx: Context) {
            Prefs.get().setServiceEnabled(false)
            runCatching { ctx.stopService(Intent(ctx, RecvService::class.java)) }
        }

        /** 刷新常驻通知：端口 + 今日收数。 */
        fun refresh(ctx: Context) {
            if (!running) return
            val prefs = Prefs.get()
            val n = Db.get().countSince(System.currentTimeMillis() - 24 * 3600_000L)
            val ip = NetUtil.lanIpv4()
            val text = if (prefs.httpIntake()) {
                "近 24h 收到 " + n + " 条 · 监听 " + (if (ip.isEmpty()) "无局域网" else ip + ":" + prefs.port())
            } else {
                "近 24h 收到 " + n + " 条 · 只走短信通道"
            }
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif = Notification.Builder(ctx, App.CH_SERVICE)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("验证码接收 · 运行中")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .build()
            runCatching { nm.notify(NOTIF_ID, notif) }
        }
    }
}
