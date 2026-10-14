package com.elysia.smsrecv

import android.content.Context
import android.os.Build
import android.telephony.SmsMessage
import org.json.JSONObject

/**
 * 所有入口的收口：短信直发（SMS_RECEIVED）与局域网直推（HTTP）都汇到这里，
 * 落库 → 弹通知 → 视设置弹悬浮窗。
 */
object SmsIn {

    fun fromBroadcast(ctx: Context, msgs: Array<SmsMessage>) {
        val first = msgs.first()
        val sender = first.originatingAddress.orEmpty()
        // 长短信会被拆成多条，必须拼接
        val body = msgs.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        fromForwardText(ctx, sender, body, first.timestampMillis)
    }

    /** 副机发来的转发短信格式：[转发]发件人|验证码|SIM标签|正文（自测也走这里，保证测的是真解析）。 */
    fun fromForwardText(ctx: Context, smsSender: String, body: String, ts: Long) {
        val sender = smsSender
        val prefix = Prefs.get().smsPrefix()
        if (!body.startsWith(prefix)) {
            Logx.d("短信不是转发格式，忽略（来自 " + sender + "）：" + Fmt.clip(body, 40))
            return
        }
        val rest = body.substring(prefix.length).trim()
        val parts = rest.split("|")
        val rec = CodeRecord(
            ts = if (ts > 0) ts else System.currentTimeMillis(),
            source = "sms",
            sender = parts.getOrNull(0)?.trim().orEmpty().ifBlank { sender },
            code = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
            simLabel = parts.getOrNull(2)?.trim().orEmpty(),
            body = parts.getOrNull(3)?.trim().orEmpty().ifBlank { rest },
            device = "副机",
            raw = body
        )
        deliver(ctx, rec)
    }

    /** 副机 webhook 直推过来的 JSON（字段与副端 payload 对齐）。 */
    fun fromJson(ctx: Context, json: String) {
        val o = JSONObject(json)
        val rec = CodeRecord(
            ts = o.optLong("ts", System.currentTimeMillis()),
            source = "http",
            sender = o.optString("from", ""),
            simLabel = o.optString("sim_label", ""),
            code = o.optString("code", "").takeIf { it.isNotEmpty() && it != "null" },
            body = o.optString("body", ""),
            device = o.optString("device", ""),
            raw = json
        )
        deliver(ctx, rec)
    }

    /** 自测：直接造一条记录走完整展示链路。 */
    fun injectTest(ctx: Context, sender: String, code: String, simLabel: String, body: String) {
        deliver(
            ctx,
            CodeRecord(
                ts = System.currentTimeMillis(),
                source = "test",
                sender = sender,
                simLabel = simLabel,
                code = code,
                body = body,
                device = "本机自测",
                raw = body
            )
        )
    }

    fun deliver(ctx: Context, rec: CodeRecord) {
        val id = Db.get().insert(rec)
        rec.id = id
        Logx.i(
            "收到验证码 [" + rec.source + "] code=" + (rec.code ?: "-") +
                " 来自=" + rec.sender + " " + rec.simLabel + " #" + id
        )
        Notifier.showCode(ctx, rec)
        if (Prefs.get().overlayEnabled()) {
            if (android.provider.Settings.canDrawOverlays(ctx)) {
                Overlay.show(ctx, rec, Prefs.get().overlayAutoHideSec())
            } else {
                Logx.w("没有悬浮窗权限，跳过悬浮显示")
            }
        }
        RecvService.refresh(ctx)
    }
}
