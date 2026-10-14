package com.elysia.smsrecv

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** 通知栏与悬浮窗上的「复制」都走这里。 */
class CopyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
        val id = intent.getLongExtra(EXTRA_ID, 0)
        if (code.isBlank()) return
        copy(context, code)
        if (id > 0) runCatching { Db.get().markCopied(id) }
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_ID = "id"

        fun copy(context: Context, text: String) {
            runCatching {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("验证码", text))
                Toast.makeText(context, "已复制：" + text, Toast.LENGTH_SHORT).show()
                Logx.i("已复制验证码：" + text)
            }.onFailure { Logx.w("复制失败：" + it.message) }
        }
    }
}
