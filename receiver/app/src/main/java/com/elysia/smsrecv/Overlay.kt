package com.elysia.smsrecv

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮窗：游戏全屏时把验证码盖在画面上。
 * 可拖动、可点「复制」、点「×」关闭；默认 N 秒后自动消失（N 可在设置里改，0 = 不自动消失）。
 */
object Overlay {

    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val hideTick = Runnable { hide() }

    fun show(ctx: Context, rec: CodeRecord, autoHideSec: Int) {
        handler.post { showInternal(ctx, rec, autoHideSec) }
    }

    fun hide() {
        handler.post { hideInternal() }
    }

    private fun hideInternal() {
        handler.removeCallbacks(hideTick)
        val v = root ?: return
        runCatching {
            val wm = App.ctx().getSystemService(WindowManager::class.java)
            wm?.removeView(v)
        }
        root = null
        params = null
    }

    private fun showInternal(ctx: Context, rec: CodeRecord, autoHideSec: Int) {
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        hideInternal()

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 18), dp(ctx, 12), dp(ctx, 18), dp(ctx, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 18).toFloat()
                setColor(Color.parseColor("#E6000000"))
                setStroke(dp(ctx, 1), Color.parseColor("#66FFFFFF"))
            }
        }

        val codeText = TextView(ctx).apply {
            text = rec.code ?: "转发短信"
            setTextColor(Color.WHITE)
            textSize = if (rec.code.isNullOrEmpty()) 18f else 32f
            setPadding(0, 0, 0, dp(ctx, 4))
        }
        val infoText = TextView(ctx).apply {
            text = (if (rec.simLabel.isBlank()) "" else rec.simLabel + " ") + rec.sender + "\n" + Fmt.clip(rec.body, 48)
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 12f
        }

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val copyBtn = Button(ctx).apply {
            text = "复制"
            setOnClickListener {
                CopyReceiver.copy(ctx, rec.code.orEmpty())
                runCatching { Db.get().markCopied(rec.id) }
                hideInternal()
            }
        }
        val closeBtn = Button(ctx).apply {
            text = "×"
            setOnClickListener { hideInternal() }
        }
        row.addView(copyBtn)
        row.addView(closeBtn)

        panel.addView(codeText)
        panel.addView(infoText)
        panel.addView(row)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(ctx, 24)
            y = dp(ctx, 160)
        }

        // 拖动
        panel.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = lp.x; startY = lp.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = startX + (e.rawX - downX).toInt()
                        lp.y = startY + (e.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(panel, lp) }
                        return true
                    }
                }
                return false
            }
        })

        runCatching { wm.addView(panel, lp) }
            .onSuccess {
                root = panel
                params = lp
                Logx.i("悬浮窗已显示：" + (rec.code ?: "-"))
                if (autoHideSec > 0) {
                    handler.removeCallbacks(hideTick)
                    handler.postDelayed(hideTick, autoHideSec * 1000L)
                }
            }
            .onFailure { Logx.e("悬浮窗添加失败", it) }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
