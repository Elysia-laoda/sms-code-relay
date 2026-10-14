package com.elysia.smsrelay

import android.content.Context
import android.telephony.SmsMessage

/**
 * 短信入口的唯一收口：广播接收器、开机补发、界面自测都走这里。
 * 顺序是「算规则 → 落盘 → 命中才叫服务」，接收器本身不做网络。
 */
object Ingest {

    fun fromBroadcast(ctx: Context, msgs: Array<SmsMessage>, subId: Int, source: String) {
        val first = msgs.first()
        val sender = first.originatingAddress.orEmpty()
        // 长短信会被拆成多条，这里必须拼接，否则验证码数字会被截断
        val body = msgs.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        handle(ctx, sender, body, subId, first.timestampMillis, source)
    }

    fun handle(ctx: Context, sender: String, body: String, subId: Int, ts: Long, source: String) {
        val prefs = Prefs.get()
        val slot = SimUtil.slotOf(ctx, subId)
        val label = if (slot >= 0) SimUtil.label(ctx, subId) else "SIM?"
        val d = RuleEngine.decide(
            body, sender,
            prefs.includeList(), prefs.excludeList(), prefs.digitsFallback()
        )
        val rec = SmsRecord(
            ts = if (ts > 0) ts else System.currentTimeMillis(),
            sender = sender,
            body = body,
            slot = slot,
            subId = subId,
            simLabel = label,
            code = d.code,
            matched = d.forward,
            matchReason = d.reason,
            createdAt = System.currentTimeMillis()
        )
        val id = Db.get().insert(rec)
        Logx.i(
            "收到[" + source + "] 来自=" + sender + " " + label +
                " 命中=" + d.forward + " (" + d.reason + ")" +
                (if (d.code != null) " code=" + d.code else "") +
                (if (id > 0) " #" + id else " [重复投递，忽略]")
        )
        if (id > 0 && d.forward && prefs.serviceEnabled()) {
            ServiceController.pokeOrStart(ctx)
        }
    }
}
