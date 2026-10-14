package com.elysia.smsrelay

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SinkConfig(
    val type: String,
    val url: String,
    val token: String,
    val dest: String = "",
    val prefix: String = "[转发]",
    val user: String = "",
    val pass: String = ""
)

/**
 * 通道适配器：加一个通道只要实现这个接口 + 在 Sinks.of 里挂一行，别的不动。
 * 现在三条：自建/任意 webhook（POST JSON）、Bark、以及「只在本机留通知」的自测口。
 */
interface SmsSink {
    val name: String
    /** 成功返回一句人可读的回执；失败抛异常（message 会被写进队列的 last_error）。 */
    fun send(ctx: Context, rec: SmsRecord, cfg: SinkConfig): String
}

object Sinks {

    fun of(type: String): SmsSink = when (type) {
        Prefs.SINK_WEBHOOK -> WebhookSink
        Prefs.SINK_BARK -> BarkSink
        Prefs.SINK_SMS -> SmsDirectSink
        Prefs.SINK_EMAIL -> EmailSink
        else -> LocalNotifySink
    }

    /** 统一的转发负载：字段名尽量土，方便对面随便用什么写接收端。 */
    fun payload(ctx: Context, rec: SmsRecord): JSONObject {
        val o = JSONObject()
        o.put("proto", Build0.PROTOCOL)
        o.put("app", "sms-relay")
        o.put("version", Build0.VERSION_NAME)
        o.put("device", Build.MANUFACTURER + " " + Build.MODEL)
        o.put("from", rec.sender)
        o.put("body", rec.body)
        o.put("sim", rec.slot)
        o.put("sim_label", rec.simLabel)
        o.put("sub_id", rec.subId)
        o.put("code", rec.code ?: JSONObject.NULL)
        o.put("ts", rec.ts)
        o.put("ts_text", Fmt.time(rec.ts))
        o.put("received_ms", rec.createdAt)
        return o
    }

    internal fun open(url: String, method: String, timeoutMs: Int): HttpURLConnection {
        val c = (URL(url).openConnection() as HttpURLConnection)
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "sms-relay/" + Build0.VERSION_NAME)
        return c
    }

    internal fun readBody(c: HttpURLConnection, ok: Boolean): String {
        val s = runCatching {
            val stream = if (ok) c.inputStream else c.errorStream
            stream?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        return s.orEmpty()
    }
}

/** 任意 webhook：POST 一段 JSON，可选带一个共享口令头。 */
object WebhookSink : SmsSink {
    override val name = "webhook"

    override fun send(ctx: Context, rec: SmsRecord, cfg: SinkConfig): String {
        if (cfg.url.isBlank()) throw IOException("通道 URL 没填")
        val c = Sinks.open(cfg.url, "POST", 8000)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (cfg.token.isNotBlank()) {
                c.setRequestProperty("X-Token", cfg.token)
                c.setRequestProperty("Authorization", "Bearer " + cfg.token)
            }
            val bytes = Sinks.payload(ctx, rec).toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val http = c.responseCode
            val text = Sinks.readBody(c, http in 200..299)
            if (http !in 200..299) throw IOException("HTTP " + http + " " + text.take(160))
            return "HTTP " + http + (if (text.isBlank()) "" else " " + text.trim().take(80))
        } finally {
            c.disconnect()
        }
    }
}

/** Bark：GET {base}/{title}/{body}，base 里通常已经带 key。 */
object BarkSink : SmsSink {
    override val name = "bark"

    override fun send(ctx: Context, rec: SmsRecord, cfg: SinkConfig): String {
        if (cfg.url.isBlank()) throw IOException("Bark 地址没填")
        val base = cfg.url.trimEnd('/')
        val title = URLEncoder.encode(rec.simLabel.ifBlank { "短信" } + " " + rec.sender, "UTF-8")
        val body = URLEncoder.encode(rec.body, "UTF-8")
        val u = base + "/" + title + "/" + body + "?group=sms-relay&isArchive=1" +
            (if (cfg.token.isNotBlank()) "&key=" + URLEncoder.encode(cfg.token, "UTF-8") else "")
        val c = Sinks.open(u, "GET", 8000)
        try {
            val http = c.responseCode
            val text = Sinks.readBody(c, http in 200..299)
            if (http !in 200..299) throw IOException("HTTP " + http + " " + text.take(160))
            if (text.contains("\"code\":400") || text.contains("\"code\":500")) {
                throw IOException("Bark 返回失败：" + text.take(160))
            }
            return "HTTP " + http + " " + text.trim().take(60)
        } finally {
            c.disconnect()
        }
    }
}

/**
 * 短信直发：把「发件人|验证码|SIM标签|正文」用一条短信发给主机号。
 * 完全不依赖网络、不经过任何第三方；主端按前缀识别。
 */
object SmsDirectSink : SmsSink {
    override val name = "sms"

    override fun send(ctx: Context, rec: SmsRecord, cfg: SinkConfig): String {
        val dest = cfg.dest.trim()
        if (dest.isEmpty()) throw IOException("没填主机号码")
        val text = cfg.prefix + rec.sender + "|" + (rec.code ?: "") + "|" +
            rec.simLabel + "|" + rec.body
        val sm = ctx.getSystemService(android.telephony.SmsManager::class.java)
            ?: android.telephony.SmsManager.getDefault()
        val parts = sm.divideMessage(text)
        if (parts.size > 1) {
            sm.sendMultipartTextMessage(dest, null, parts, null, null)
        } else {
            sm.sendTextMessage(dest, null, text, null, null)
        }
        return "已发出 " + parts.size + " 条短信到 " + mask(dest) + "（" + text.length + " 字）"
    }

    private fun mask(s: String): String =
        if (s.length <= 7) s else s.substring(0, 3) + "****" + s.substring(s.length - 4)
}

/**
 * 邮箱通道：把转发内容用一封邮件发给（自己的）邮箱，主端 POP3 轮询取走。
 * 不走任何第三方中转，也不受「第三方 App 发短信要确认」这类 ROM 策略影响。
 */
object EmailSink : SmsSink {
    override val name = "email"

    override fun send(ctx: Context, rec: SmsRecord, cfg: SinkConfig): String {
        val user = cfg.user.trim()
        val pass = cfg.pass.trim()
        if (user.isEmpty() || pass.isEmpty()) throw IOException("没填发件邮箱或授权码")
        val to = cfg.dest.trim().ifEmpty { user }
        val subject = cfg.prefix + rec.simLabel.ifBlank { "SIM?" } + " " + rec.sender +
            (if (rec.code.isNullOrEmpty()) "" else " 验证码 " + rec.code)
        // 正文与短信通道同格式，主端两个入口共用同一个解析器
        val body = cfg.prefix + rec.sender + "|" + (rec.code ?: "") + "|" + rec.simLabel + "|" + rec.body
        val p = Prefs.get()
        Smtp.send(p.mailHost(), p.mailPort(), p.mailTls(), user, pass, to, subject, body)
        return "已发邮件到 " + mask(to)
    }

    private fun mask(s: String): String {
        val at = s.indexOf('@')
        if (at <= 2) return s
        return s.substring(0, 2) + "***" + s.substring(at)
    }
}

/** 自测口：不往外发，只在本机留一条通知 —— 用于没有服务端时验证整条链路。 */
object LocalNotifySink : SmsSink {
    override val name = "notify"

    override fun send(ctx: Context, rec: SmsRecord, cfg: SinkConfig): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
            ?: throw IOException("没有通知服务")
        val intent = Intent(ctx, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, App.CH_ALERT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(rec.simLabel.ifBlank { "短信" } + " · " + rec.sender)
            .setContentText(rec.body)
            .setStyle(Notification.BigTextStyle().bigText(rec.body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt() + 2000, n)
        return "本机通知已弹（未外发）"
    }
}
