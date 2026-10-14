package com.elysia.smsrelay

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 前台服务 + 两个 JobScheduler 任务（看护 / 退避重试）。
 * Android 12+ 从后台起前台服务会被拦，所以起服务一律 try/catch：
 * 起不来就靠队列 + 看护任务兜底，不把异常抛给广播接收器。
 */
object ServiceController {

    const val JOB_WATCHDOG = 1001
    const val JOB_RETRY = 1002
    const val WATCHDOG_PERIOD_MS = 15 * 60 * 1000L

    fun start(ctx: Context) {
        Prefs.get().setServiceEnabled(true)
        startService(ctx)
    }

    /** 起服务（幂等）。 */
    fun startService(ctx: Context) {
        runCatching {
            ctx.startForegroundService(Intent(ctx, RelayService::class.java))
        }.onFailure { Logx.w("startForegroundService 被拒：" + it.javaClass.simpleName + " " + it.message) }
    }

    /** 服务已经在跑就给一脚，让它立刻扫队列；没在跑就起。 */
    fun pokeOrStart(ctx: Context) {
        if (RelayService.running) {
            runCatching { ctx.startService(Intent(ctx, RelayService::class.java)) }
                .onFailure { startService(ctx) }
        } else {
            startService(ctx)
        }
    }

    fun stop(ctx: Context) {
        Prefs.get().setServiceEnabled(false)
        cancelJobs(ctx)
        runCatching { ctx.stopService(Intent(ctx, RelayService::class.java)) }
        Logx.i("服务已停止（设置里已关掉自启）")
    }

    private fun cancelJobs(ctx: Context) {
        runCatching { ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_WATCHDOG) }
        runCatching { ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_RETRY) }
    }

    /** 15 分钟一次的看护：服务不在了就拉起来（JobScheduler 的最小周期就是 15 分钟）。 */
    fun scheduleWatchdog(ctx: Context) {
        runCatching {
            val job = JobInfo.Builder(JOB_WATCHDOG, ComponentName(ctx, WatchdogJobService::class.java))
                .setPeriodic(WATCHDOG_PERIOD_MS)
                .setPersisted(true)
                .setRequiresDeviceIdle(false)
                .build()
            ctx.getSystemService(JobScheduler::class.java)?.schedule(job)
        }.onFailure { Logx.w("看护任务排不上：" + it.message) }
    }

    /** 发送失败后的一次性重试，指数退避由调用方算好延迟。 */
    fun scheduleRetry(ctx: Context, delayMs: Long) {
        runCatching {
            val job = JobInfo.Builder(JOB_RETRY, ComponentName(ctx, WatchdogJobService::class.java))
                .setMinimumLatency(delayMs.coerceAtLeast(1000L))
                .setOverrideDeadline(delayMs.coerceAtLeast(1000L) + 60_000L)
                .setPersisted(true)
                .build()
            ctx.getSystemService(JobScheduler::class.java)?.schedule(job)
        }.onFailure { Logx.w("重试任务排不上：" + it.message) }
    }
}
