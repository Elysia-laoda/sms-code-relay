package com.elysia.smsrelay

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * 两个 JobScheduler 任务共用这个服务：
 *  · 周期看护（15 分钟）——服务不在了就拉起来；
 *  · 一次性重试——发送失败后退避到点，扫一遍队列。
 * 注意 JobService 的回调在主线程，这里只做「起服务」这种轻动作，所以直接返回 false。
 */
class WatchdogJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val id = params?.jobId ?: -1
        if (!Prefs.get().serviceEnabled()) {
            Logx.i("看护任务触发(job=" + id + ")，但开关是关的")
            return false
        }
        Logx.i("看护任务触发(job=" + id + ")，service running=" + RelayService.running)
        if (RelayService.running) {
            runCatching { startService(android.content.Intent(this, RelayService::class.java)) }
        } else {
            ServiceController.startService(this)
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
