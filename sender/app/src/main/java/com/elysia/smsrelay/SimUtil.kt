package com.elysia.smsrelay

import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager

/**
 * 双卡归属：短信广播里带的是 subscription id（不是槽位），要自己换算；
 * 国产 ROM 上这个 extra 有时缺失，所以一律留出「未知」这条路，不硬猜。
 */
object SimUtil {

    fun slotOf(ctx: Context, subId: Int): Int {
        if (subId < 0) return -1
        return runCatching { SubscriptionManager.getSlotIndex(subId) }.getOrDefault(-1)
    }

    fun label(ctx: Context, subId: Int): String {
        if (subId < 0) return "SIM?"
        val info = runCatching {
            val sm = ctx.getSystemService(SubscriptionManager::class.java)
            sm?.getActiveSubscriptionInfo(subId)
        }.getOrNull() ?: return "SIM?"
        val idx = info.simSlotIndex + 1
        val carrier = info.carrierName?.toString().orEmpty()
        return if (carrier.isEmpty()) "SIM" + idx else "SIM" + idx + "(" + carrier + ")"
    }

    /** 给状态页看的两行摘要；没授 READ_PHONE_STATE 时只报「读不到」。 */
    fun summary(ctx: Context): String {
        return runCatching {
            val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return "订阅服务不可用"
            val list = sm.activeSubscriptionInfoList ?: return "没有活动 SIM 卡"
            if (list.isEmpty()) return "没有活动 SIM 卡"
            list.joinToString("  |  ") { info ->
                val idx = info.simSlotIndex + 1
                val carrier = info.carrierName?.toString().orEmpty()
                "SIM" + idx + " subId=" + info.subscriptionId + (if (carrier.isEmpty()) "" else " " + carrier)
            }
        }.getOrElse { t -> "读不到（缺 READ_PHONE_STATE？）：" + t.javaClass.simpleName }
    }
}
