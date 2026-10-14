package com.elysia.smsrelay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 常驻前台服务：负责把队列里的短信按通道发出去。
 * 设计上「接收」与「上报」是解耦的——接收器只落盘，真正的网络动作全在这里，
 * 失败就退避重试，超过有效窗口才判死。
 */
class RelayService : Service() {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "relay-send") }
    private val draining = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val retryTick = Runnable { drain("服务内定时重试") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(NOTIF_ID, buildNotification("服务已启动，等短信"))
        ServiceController.scheduleWatchdog(this)
        Logx.i("前台服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INJECT -> {
                val sender = intent.getStringExtra(EXTRA_SENDER).orEmpty().ifBlank { "10086" }
                val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
                val subId = intent.getIntExtra(EXTRA_SUB_ID, -1)
                Ingest.handle(this, sender, body, subId, System.currentTimeMillis(), "自测")
            }
            ACTION_STOP -> {
                Logx.i("收到停止指令")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        drain("onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(retryTick)
        Logx.i("前台服务已结束")
        super.onDestroy()
    }

    // ---- 发送 ----

    private fun drain(reason: String) {
        val prefs = Prefs.get()
        if (!prefs.serviceEnabled()) {
            Logx.i("已关服务开关，跳过发送（" + reason + "）")
            return
        }
        if (!draining.compareAndSet(false, true)) return

        worker.execute {
            val wake = (getSystemService(PowerManager::class.java))
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smsrelay:send")
            runCatching { wake?.acquire(90_000L) }
            try {
                val windowMin = prefs.retryWindowMin()
                val maxAttempts = prefs.maxAttempts()
                val expired = Db.get().expireStale(windowMin, maxAttempts)
                if (expired > 0) Logx.w("有 " + expired + " 条超出 " + windowMin + " 分钟窗口，已放弃")

                val queue = Db.get().pending(20, windowMin, maxAttempts)
                if (queue.isEmpty()) {
                    updateNotification()
                    return@execute
                }
                val cfg = SinkConfig(
                    prefs.sinkType(),
                    prefs.sinkUrl().trim(),
                    prefs.sinkToken().trim(),
                    prefs.smsDest().trim(),
                    prefs.smsPrefix(),
                    prefs.mailUser().trim(),
                    prefs.mailPass().trim()
                )
                val sink = Sinks.of(cfg.type)
                Logx.i("开始发送 " + queue.size + " 条，通道=" + sink.name + "（" + reason + "）")

                var ok = 0
                var fail = 0
                for (rec in queue) {
                    try {
                        val resp = sink.send(this, rec, cfg)
                        Db.get().markForwarded(rec.id, sink.name, resp)
                        ok++
                        Logx.i("已转发 #" + rec.id + " via " + sink.name + "：" + resp)
                    } catch (t: Throwable) {
                        val attempts = rec.attempts + 1
                        val delay = backoffMs(attempts)
                        Db.get().markFailed(rec.id, attempts, System.currentTimeMillis() + delay, describe(t))
                        fail++
                        Logx.w(
                            "转发失败 #" + rec.id + " 第" + attempts + "次：" + describe(t) +
                                "，" + (delay / 1000) + " 秒后重试"
                        )
                    }
                }
                updateNotification()
                // 退避还没到点的那些条目不会被 pending() 选中，所以这里要单独问一次「还有没有待发」。
                val nextAt = Db.get().nextPendingAt(windowMin, maxAttempts)
                if (nextAt > 0) {
                    val delay = (nextAt - System.currentTimeMillis()).coerceIn(3_000L, 10 * 60_000L)
                    main.removeCallbacks(retryTick)
                    main.postDelayed(retryTick, delay)
                    ServiceController.scheduleRetry(this, delay)
                    Logx.i("还有待发，安排 " + (delay / 1000) + " 秒后重试（服务内定时 + JobScheduler 双保险）")
                }
                Logx.i("本轮结束：成功 " + ok + "，失败 " + fail)
            } catch (t: Throwable) {
                Logx.e("发送循环异常", t)
            } finally {
                runCatching { if (wake?.isHeld == true) wake.release() }
                draining.set(false)
            }
        }
    }

    /** 30s、60s、120s……最多 10 分钟。 */
    private fun backoffMs(attempts: Int): Long {
        val base = 30_000L shl (attempts - 1).coerceIn(0, 5)
        return base.coerceAtMost(10 * 60_000L)
    }

    private fun describe(t: Throwable): String =
        (t.javaClass.simpleName + ": " + (t.message ?: "")).take(200)

    // ---- 通知 ----

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, App.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("短信转发 · 运行中")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification() {
        val prefs = Prefs.get()
        val pending = Db.get().pendingCount(prefs.maxAttempts())
        val today = Db.get().forwardedSince(System.currentTimeMillis() - 24 * 3600_000L)
        val last = Db.get().lastForwardedAt()
        val text = if (pending > 0) {
            "待发 " + pending + " 条 · 近 24h 已转 " + today + " 条"
        } else {
            "近 24h 已转 " + today + " 条 · 最后 " + Fmt.ago(last)
        }
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_INJECT = "com.elysia.smsrelay.action.INJECT"
        const val ACTION_STOP = "com.elysia.smsrelay.action.STOP"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_BODY = "body"
        const val EXTRA_SUB_ID = "sub_id"
        const val NOTIF_ID = 1001

        @Volatile
        var running: Boolean = false
            private set

        /** 界面自测用：把一条假短信喂进与真实广播完全相同的处理链。 */
        fun inject(ctx: Context, sender: String, body: String, subId: Int) {
            val i = Intent(ctx, RelayService::class.java)
                .setAction(ACTION_INJECT)
                .putExtra(EXTRA_SENDER, sender)
                .putExtra(EXTRA_BODY, body)
                .putExtra(EXTRA_SUB_ID, subId)
            runCatching { ctx.startForegroundService(i) }
                .onFailure { Logx.w("自测注入起服务失败：" + it.message) }
        }
    }
}
