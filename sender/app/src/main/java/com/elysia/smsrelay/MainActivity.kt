package com.elysia.smsrelay

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** 一个页面管全部：状态 → 权限 → 规则 → 通道 → 自测 → 记录/日志。 */
class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvRecords: TextView
    private lateinit var tvLogs: TextView
    private lateinit var etInclude: EditText
    private lateinit var etExclude: EditText
    private lateinit var etUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var etTestBody: EditText
    private lateinit var swDigits: Switch
    private lateinit var cbNotifyEach: CheckBox
    private lateinit var rbWebhook: RadioButton
    private lateinit var rbBark: RadioButton
    private lateinit var rbNotify: RadioButton
    private lateinit var rbSms: RadioButton
    private lateinit var etSmsDest: EditText
    private lateinit var rbEmail: RadioButton
    private lateinit var etMailUser: EditText
    private lateinit var etMailPass: EditText
    private lateinit var etMailTo: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvRecords = findViewById(R.id.tvRecords)
        tvLogs = findViewById(R.id.tvLogs)
        etInclude = findViewById(R.id.etInclude)
        etExclude = findViewById(R.id.etExclude)
        etUrl = findViewById(R.id.etUrl)
        etToken = findViewById(R.id.etToken)
        etTestBody = findViewById(R.id.etTestBody)
        swDigits = findViewById(R.id.swDigits)
        cbNotifyEach = findViewById(R.id.cbNotifyEach)
        rbWebhook = findViewById(R.id.rbWebhook)
        rbBark = findViewById(R.id.rbBark)
        rbNotify = findViewById(R.id.rbNotify)
        rbSms = findViewById(R.id.rbSms)
        etSmsDest = findViewById(R.id.etSmsDest)
        rbEmail = findViewById(R.id.rbEmail)
        etMailUser = findViewById(R.id.etMailUser)
        etMailPass = findViewById(R.id.etMailPass)
        etMailTo = findViewById(R.id.etMailTo)

        loadFromPrefs()
        wire()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    // ---- 设置读写 ----

    private fun loadFromPrefs() {
        val p = Prefs.get()
        etInclude.setText(p.includeKeywords())
        etExclude.setText(p.excludeKeywords())
        etUrl.setText(p.sinkUrl())
        etToken.setText(p.sinkToken())
        etSmsDest.setText(p.smsDest())
        etMailUser.setText(p.mailUser())
        etMailPass.setText(p.mailPass())
        etMailTo.setText(p.mailTo())
        swDigits.isChecked = p.digitsFallback()
        cbNotifyEach.isChecked = p.notifyEach()
        when (p.sinkType()) {
            Prefs.SINK_BARK -> rbBark.isChecked = true
            Prefs.SINK_NOTIFY -> rbNotify.isChecked = true
            Prefs.SINK_SMS -> rbSms.isChecked = true
            Prefs.SINK_EMAIL -> rbEmail.isChecked = true
            else -> rbWebhook.isChecked = true
        }
        if (etTestBody.text.isNullOrBlank()) {
            etTestBody.setText("【自测】您的验证码是 483920，5 分钟内有效，请勿泄露。")
        }
    }

    private fun saveToPrefs(showToast: Boolean) {
        val p = Prefs.get()
        p.setIncludeKeywords(etInclude.text.toString())
        p.setExcludeKeywords(etExclude.text.toString())
        p.setSinkUrl(etUrl.text.toString().trim())
        p.setSinkToken(etToken.text.toString().trim())
        p.setDigitsFallback(swDigits.isChecked)
        p.setNotifyEach(cbNotifyEach.isChecked)
        p.setSmsDest(etSmsDest.text.toString().trim())
        p.setMailUser(etMailUser.text.toString().trim())
        p.setMailPass(etMailPass.text.toString().trim())
        p.setMailTo(etMailTo.text.toString().trim())
        p.setSinkType(
            when {
                rbBark.isChecked -> Prefs.SINK_BARK
                rbNotify.isChecked -> Prefs.SINK_NOTIFY
                rbSms.isChecked -> Prefs.SINK_SMS
                rbEmail.isChecked -> Prefs.SINK_EMAIL
                else -> Prefs.SINK_WEBHOOK
            }
        )
        if (showToast) Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
    }

    // ---- 事件 ----

    private fun wire() {
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveToPrefs(true)
            refreshAll()
        }
        findViewById<Button>(R.id.btnPerms).setOnClickListener { requestPerms() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryWhitelist() }
        findViewById<Button>(R.id.btnAppDetail).setOnClickListener { openAppDetails() }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            saveToPrefs(false)
            ServiceController.start(this)
            Toast.makeText(this, "已请求启动服务", Toast.LENGTH_SHORT).show()
            tvStatus.postDelayed({ refreshAll() }, 800)
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            ServiceController.stop(this)
            Toast.makeText(this, "服务已停止（自启也关了）", Toast.LENGTH_SHORT).show()
            tvStatus.postDelayed({ refreshAll() }, 400)
        }
        findViewById<Button>(R.id.btnInject).setOnClickListener {
            saveToPrefs(false)
            val body = etTestBody.text.toString().ifBlank {
                "【自测】您的验证码是 " + (100000..999999).random() + "，5 分钟内有效。"
            }
            RelayService.inject(this, "10086", body, -1)
            Toast.makeText(this, "已注入，1~2 秒后看记录", Toast.LENGTH_SHORT).show()
            tvStatus.postDelayed({ refreshAll() }, 1800)
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshAll() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            Db.get().clearLogs()
            refreshAll()
        }
    }

    // ---- 状态 ----

    private fun refreshAll() {
        tvStatus.text = buildStatus()
        tvRecords.text = buildRecords()
        tvLogs.text = buildLogs()
    }

    private fun buildStatus(): String {
        val p = Prefs.get()
        val sb = StringBuilder()
        sb.append("服务：").append(if (RelayService.running) "运行中" else "未运行")
            .append("（开关 ").append(if (p.serviceEnabled()) "开" else "关").append("）\n")
        sb.append("通道：").append(p.sinkType()).append(" → ").append(channelSummary(p)).append('\n')
        sb.append("收短信：").append(yn(hasPerm(Manifest.permission.RECEIVE_SMS)))
            .append("   发短信：").append(yn(hasPerm(Manifest.permission.SEND_SMS)))
            .append("   电话(双卡)：").append(yn(hasPerm(Manifest.permission.READ_PHONE_STATE))).append('\n')
        val nm = getSystemService(NotificationManager::class.java)
        sb.append("通知权限：").append(yn(nm?.areNotificationsEnabled() == true))
            .append("   电池白名单：").append(yn(isIgnoringBattery())).append('\n')
        sb.append("系统：Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(") ")
            .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("双卡：").append(SimUtil.summary(this)).append('\n')

        val now = System.currentTimeMillis()
        sb.append("队列：待发 ").append(Db.get().pendingCount(p.maxAttempts())).append(" 条")
            .append("   近 24h：收 ").append(Db.get().receivedSince(now - 24 * 3600_000L))
            .append(" / 转 ").append(Db.get().forwardedSince(now - 24 * 3600_000L))
            .append("   最后转发 ").append(Fmt.ago(Db.get().lastForwardedAt())).append('\n')
        sb.append("规则：包含 ").append(p.includeList().size).append(" 条 / 排除 ")
            .append(p.excludeList().size).append(" 条，数字兜底 ")
            .append(if (p.digitsFallback()) "开" else "关")
            .append("   重试窗口 ").append(p.retryWindowMin()).append(" 分钟\n")
        sb.append("版本 ").append(Build0.VERSION_NAME)
            .append("   日志 tag：SmsRelay")
        return sb.toString()
    }

    private fun buildRecords(): String {
        val list = Db.get().recent(30)
        if (list.isEmpty()) return "（还没有记录）"
        val sb = StringBuilder()
        for (r in list) {
            sb.append('#').append(r.id).append(' ').append(Fmt.time(r.createdAt)).append(' ')
                .append(if (r.simLabel.isBlank()) "SIM?" else r.simLabel).append(' ')
                .append(r.sender).append(' ').append(r.state)
            val code = r.code
            if (code != null) sb.append(" code=").append(code)
            if (!r.matched) sb.append(" (").append(r.matchReason).append(')')
            val err = r.lastError
            if (err != null) sb.append(" ！").append(Fmt.clip(err, 40))
            sb.append('\n').append("      ").append(Fmt.clip(r.body.replace('\n', ' '), 70)).append('\n')
        }
        return sb.toString()
    }

    private fun buildLogs(): String {
        val logs = Db.get().logs(60)
        return if (logs.isEmpty()) "（暂无日志）" else logs.joinToString("\n")
    }

    // ---- 权限与跳转 ----

    private fun hasPerm(p: String): Boolean =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val list = ArrayList<String>()
        list.add(Manifest.permission.RECEIVE_SMS)
        list.add(Manifest.permission.READ_SMS)
        list.add(Manifest.permission.READ_PHONE_STATE)
        list.add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) list.add("android.permission.POST_NOTIFICATIONS")
        val missing = list.filter { !hasPerm(it) }
        if (missing.isEmpty()) {
            Toast.makeText(this, "权限都齐了", Toast.LENGTH_SHORT).show()
            return
        }
        requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val denied = permissions.filterIndexed { i, _ -> grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED }
            Toast.makeText(
                this,
                if (denied.isEmpty()) "权限已授予" else "还有未授予：" + denied.joinToString(","),
                Toast.LENGTH_LONG
            ).show()
            refreshAll()
        }
    }

    private fun isIgnoringBattery(): Boolean =
        runCatching {
            getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        }.getOrDefault(false)

    private fun requestBatteryWhitelist() {
        val ok = runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:" + packageName))
            )
        }.isSuccess
        if (!ok) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { Toast.makeText(this, "这台机器没有电池优化设置入口，去系统设置里手动加白名单", Toast.LENGTH_LONG).show() }
        }
    }

    private fun openAppDetails() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + packageName))
            )
        }.onFailure { Toast.makeText(this, "打不开应用详情页", Toast.LENGTH_SHORT).show() }
    }

    private fun yn(b: Boolean) = if (b) "✓" else "✗"

    /** 通道自检：sms 通道看的是目标号码（以前这里错看了 webhook 地址，导致「填了却显示未填」）。 */
    private fun channelSummary(p: Prefs): String = when (p.sinkType()) {
        Prefs.SINK_SMS -> {
            val d = p.smsDest()
            if (d.isBlank()) "⚠ 没填主机号码！" else maskPhone(d) + "（前缀 " + p.smsPrefix() + "）"
        }
        Prefs.SINK_BARK -> maskUrl(p.sinkUrl())
        Prefs.SINK_EMAIL -> {
            val u = p.mailUser()
            if (u.isBlank()) "⚠ 没填发件邮箱！" else maskMail(u) + " → " + (p.mailTo().ifBlank { "自己" })
        }
        Prefs.SINK_NOTIFY -> "只在本机留通知"
        else -> maskUrl(p.sinkUrl())
    }

    private fun maskMail(s: String): String {
        val at = s.indexOf('@')
        return if (at <= 2) s else s.substring(0, 2) + "***" + s.substring(at)
    }

    private fun maskPhone(s: String): String =
        if (s.length <= 7) s else s.substring(0, 3) + "****" + s.substring(s.length - 4)

    private fun maskUrl(u: String): String {
        if (u.isBlank()) return "（未填）"
        return if (u.length <= 24) u else u.substring(0, 24) + "…"
    }

    companion object {
        private const val REQ_PERMS = 100
    }
}
