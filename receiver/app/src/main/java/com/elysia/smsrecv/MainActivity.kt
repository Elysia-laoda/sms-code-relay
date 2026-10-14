package com.elysia.smsrecv

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var llRecords: LinearLayout
    private lateinit var etPort: EditText
    private lateinit var etToken: EditText
    private lateinit var etPrefix: EditText
    private lateinit var etHideSec: EditText
    private lateinit var swOverlay: Switch
    private lateinit var etMailUser: EditText
    private lateinit var etMailPass: EditText
    private lateinit var etMailInterval: EditText
    private lateinit var swMail: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLogs = findViewById(R.id.tvLogs)
        llRecords = findViewById(R.id.llRecords)
        etPort = findViewById(R.id.etPort)
        etToken = findViewById(R.id.etToken)
        etPrefix = findViewById(R.id.etPrefix)
        etHideSec = findViewById(R.id.etHideSec)
        swOverlay = findViewById(R.id.swOverlay)
        etMailUser = findViewById(R.id.etMailUser)
        etMailPass = findViewById(R.id.etMailPass)
        etMailInterval = findViewById(R.id.etMailInterval)
        swMail = findViewById(R.id.swMail)

        loadFromPrefs()
        wire()

        // 打开界面就顺手把接收服务拉起来（短信通道不依赖它，但 HTTP 端口需要）
        if (Prefs.get().serviceEnabled() && !RecvService.running) {
            RecvService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun loadFromPrefs() {
        val p = Prefs.get()
        etPort.setText(p.port().toString())
        etToken.setText(p.token())
        etPrefix.setText(p.smsPrefix())
        etHideSec.setText(p.overlayAutoHideSec().toString())
        swOverlay.isChecked = p.overlayEnabled()
        etMailUser.setText(p.mailUser())
        etMailPass.setText(p.mailPass())
        etMailInterval.setText(p.mailIntervalSec().toString())
        swMail.isChecked = p.mailEnabled()
    }

    private fun savePrefs(showToast: Boolean) {
        val p = Prefs.get()
        p.setPort(etPort.text.toString().toIntOrNull() ?: 8788)
        p.setToken(etToken.text.toString().trim())
        p.setSmsPrefix(etPrefix.text.toString().ifBlank { Prefs.DEFAULT_PREFIX })
        p.setOverlayAutoHideSec(etHideSec.text.toString().toIntOrNull() ?: 20)
        p.setOverlayEnabled(swOverlay.isChecked)
        p.setMailUser(etMailUser.text.toString().trim())
        p.setMailPass(etMailPass.text.toString().trim())
        p.setMailIntervalSec(etMailInterval.text.toString().toIntOrNull() ?: 20)
        p.setMailEnabled(swMail.isChecked)
        if (showToast) Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }

    private fun wire() {
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            savePrefs(true)
            RecvService.start(this)
            refreshAll()
        }
        findViewById<Button>(R.id.btnPerms).setOnClickListener {
            val need = ArrayList<String>()
            if (!hasPerm(Manifest.permission.RECEIVE_SMS)) need.add(Manifest.permission.RECEIVE_SMS)
            if (!hasPerm(Manifest.permission.READ_SMS)) need.add(Manifest.permission.READ_SMS)
            if (need.isEmpty()) Toast.makeText(this, "短信权限已授予", Toast.LENGTH_SHORT).show()
            else requestPermissions(need.toTypedArray(), REQ_SMS)
        }
        findViewById<Button>(R.id.btnOverlayPerm).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
            } else {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
                }.onFailure { Toast.makeText(this, "这台机器没有悬浮窗设置入口", Toast.LENGTH_LONG).show() }
            }
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            val ok = runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:" + packageName)))
            }.isSuccess
            if (!ok) runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            savePrefs(false)
            RecvService.start(this)
            tvStatus.postDelayed({ refreshAll() }, 700)
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            RecvService.stop(this)
            tvStatus.postDelayed({ refreshAll() }, 400)
        }
        findViewById<Button>(R.id.btnCopyLatest).setOnClickListener {
            val last = Db.get().latest()
            if (last?.code.isNullOrEmpty()) Toast.makeText(this, "还没有收到验证码", Toast.LENGTH_SHORT).show()
            else {
                CopyReceiver.copy(this, last!!.code!!)
                runCatching { Db.get().markCopied(last!!.id) }
            }
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            savePrefs(false)
            SmsIn.injectTest(this, "10086", (100000..999999).random().toString(), "SIM1(自测)", "【自测】您的验证码是 000000，5 分钟内有效。")
            tvStatus.postDelayed({ refreshAll() }, 1200)
        }
        findViewById<Button>(R.id.btnMailNow).setOnClickListener {
            savePrefs(false)
            Toast.makeText(this, "正在收邮件…", Toast.LENGTH_SHORT).show()
            Thread({
                val n = runCatching { MailPoller.pollOnce(this) }
                    .onFailure { Logx.w("手动收信失败：" + it.message) }
                    .getOrDefault(0)
                runOnUiThread {
                    Toast.makeText(this, if (n > 0) "取回 " + n + " 封" else "没有新邮件（或失败，看日志）", Toast.LENGTH_LONG).show()
                    refreshAll()
                }
            }, "manual-mail").start()
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshAll() }
        findViewById<Button>(R.id.btnClearRecords).setOnClickListener {
            Db.get().clearAll(); refreshAll()
        }
        findViewById<Button>(R.id.btnClearLogs).setOnClickListener {
            Db.get().clearLogs(); refreshAll()
        }
    }

    private fun refreshAll() {
        tvStatus.text = buildStatus()
        buildRecords()
        tvLogs.text = Db.get().logs(60).joinToString("\n").ifBlank { "（暂无日志）" }
    }

    private fun buildStatus(): String {
        val p = Prefs.get()
        val sb = StringBuilder()
        sb.append("服务：").append(if (RecvService.running) "运行中" else "未运行")
            .append("（开关 ").append(if (p.serviceEnabled()) "开" else "关").append("）\n")
        sb.append("短信权限：").append(yn(hasPerm(Manifest.permission.RECEIVE_SMS)))
            .append("   悬浮窗权限：").append(yn(Settings.canDrawOverlays(this))).append('\n')
        val nm = getSystemService(NotificationManager::class.java)
        sb.append("通知：").append(yn(nm?.areNotificationsEnabled() == true))
            .append("   电池白名单：").append(yn(isIgnoringBattery())).append('\n')
        sb.append("局域网：").append(NetUtil.summary()).append('\n')
        sb.append("监听：").append(if (p.httpIntake()) "0.0.0.0:" + p.port() else "关")
            .append("   口令：").append(if (p.token().isEmpty()) "无" else "已设")
            .append("   短信前缀：").append(p.smsPrefix()).append('\n')
        val n = Db.get().countSince(System.currentTimeMillis() - 24 * 3600_000L)
        val last = Db.get().latest()
        sb.append("近 24h 收到 ").append(n).append(" 条；最后一条 ")
            .append(if (last == null) "无" else (last.code ?: "-") + "（" + Fmt.ago(last.ts) + "）").append('\n')
        sb.append("邮箱通道：").append(if (p.mailEnabled()) "开" else "关")
            .append("   ").append(if (p.mailUser().isBlank()) "⚠ 没填邮箱" else mask(p.mailUser()))
            .append("   间隔 ").append(p.mailIntervalSec()).append(" 秒\n")
        sb.append("悬浮窗：").append(if (p.overlayEnabled()) "开" else "关")
            .append("（").append(p.overlayAutoHideSec()).append(" 秒后自动消失）")
            .append("   版本 ").append(Build0.VERSION_NAME).append("   日志 tag：SmsRecv")
        return sb.toString()
    }

    private fun buildRecords() {
        llRecords.removeAllViews()
        val list = Db.get().recent(20)
        if (list.isEmpty()) {
            llRecords.addView(TextView(this).apply { text = "（还没有收到验证码）"; textSize = 13f })
            return
        }
        for (r in list) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val info = TextView(this).apply {
                text = (r.code ?: "无码") + "   " + Fmt.time(r.ts) + "   " +
                    (r.simLabel.ifBlank { "-" }) + " " + r.sender + "   [" + r.source + "]\n      " +
                    Fmt.clip(r.body.replace('\n', ' '), 50)
                textSize = 12f
                setPadding(0, 8, 0, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btn = Button(this).apply {
                text = "复制"
                setOnClickListener {
                    CopyReceiver.copy(this@MainActivity, r.code.orEmpty())
                    runCatching { Db.get().markCopied(r.id) }
                }
            }
            row.addView(info)
            row.addView(btn)
            llRecords.addView(row)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SMS) {
            val denied = permissions.filterIndexed { i, _ -> grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED }
            Toast.makeText(this, if (denied.isEmpty()) "已授予" else "被拒绝：" + denied.joinToString(","), Toast.LENGTH_LONG).show()
            refreshAll()
        }
    }

    private fun hasPerm(p: String): Boolean = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun isIgnoringBattery(): Boolean =
        runCatching { getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true }.getOrDefault(false)

    private fun yn(b: Boolean) = if (b) "✓" else "✗"

    private fun mask(s: String): String {
        val at = s.indexOf('@')
        return if (at <= 2) s else s.substring(0, 2) + "***" + s.substring(at)
    }

    companion object {
        private const val REQ_SMS = 200
    }
}
