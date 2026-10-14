package com.elysia.smsrecv

import android.content.Context
import android.util.Base64
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 邮箱通道的接收端：POP3（SSL，qq 邮箱 995）拉一轮新邮件。
 * 只处理副端发来的转发邮件（正文是 base64 的 [转发]发件人|验证码|SIM|正文），
 * 处理完就 DELE 删掉，并用 UIDL 记住见过的邮件，避免重复弹码。
 */
object MailPoller {

    // 默认走 QQ 邮箱；设置里可改（自建/联调用）。

    /** 拉一轮，返回处理了几封。 */
    fun pollOnce(ctx: Context): Int {
        val p = Prefs.get()
        val user = p.mailUser().trim()
        val pass = p.mailPass().trim()
        if (user.isEmpty() || pass.isEmpty()) {
            Logx.w("邮箱通道没配置完整，跳过轮询")
            return 0
        }

        var handled = 0
        // 注意：createSocket(host, port) 的 TCP connect 没有超时，一旦对端黑洞化（例如隧道还在、
        // 监听器重启），线程会永久卡死且不报错。所以先带超时 connect，再包 SSL。
        val raw = java.net.Socket()
        raw.connect(java.net.InetSocketAddress(p.mailHost(), p.mailPort()), 8000)
        raw.soTimeout = 20000
        val sock = if (p.mailTls()) {
            ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, p.mailHost(), p.mailPort(), true) as SSLSocket).apply {
                startHandshake()
            }
        } else raw
        val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
        val writer = OutputStreamWriter(sock.outputStream, Charsets.UTF_8)

        try {
            expectOk(reader.readLine(), "连接")
            expectOk(cmd(reader, writer, "USER " + user), "USER")
            expectOk(cmd(reader, writer, "PASS " + pass), "PASS")

            val stat = cmd(reader, writer, "STAT")
            expectOk(stat, "STAT")
            val count = stat.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            if (count == 0) {
                runCatching { cmd(reader, writer, "QUIT") }
                return 0
            }

            for (i in 1..count) {
                val uidl = runCatching { uidlOf(reader, writer, i) }.getOrNull()
                if (uidl != null && Db.get().hasMailUidl(uidl)) continue    // 见过就跳过：既不重看，也不删
                val raw = runCatching { retrieve(reader, writer, i) }.getOrNull() ?: continue
                val payload = extractPayload(raw)
                val ours = payload != null && payload.startsWith(Prefs.get().smsPrefix())
                if (!ours) {
                    // 关键：不是我们发的邮件，只记「见过」，**绝不删除**——邮箱是用户的，不是我们的。
                    Logx.d("非转发邮件，跳过（不删）：" + Fmt.clip(subjectOf(raw), 40))
                    if (uidl != null) Db.get().markMailUidl(uidl)
                    continue
                }
                SmsIn.fromForwardText(ctx, "邮箱", payload!!, System.currentTimeMillis())
                if (uidl != null) Db.get().markMailUidl(uidl)
                handled++
                // 只有确认是我们自己发出的那封，才取走即删，避免下轮重复弹码
                runCatching { cmd(reader, writer, "DELE " + i) }
            }
            runCatching { cmd(reader, writer, "QUIT") }
        } finally {
            runCatching { sock.close() }
        }
        if (handled > 0) Logx.i("邮箱通道取回 " + handled + " 封")
        return handled
    }

    private fun uidlOf(reader: BufferedReader, writer: OutputStreamWriter, idx: Int): String? {
        val r = cmd(reader, writer, "UIDL " + idx)
        if (!r.startsWith("+OK")) return null
        val parts = r.split(" ")
        return if (parts.size >= 3) parts[2].trim() else null
    }

    /** RETR：状态行 + 多行正文，直到单独一行 "." */
    private fun retrieve(reader: BufferedReader, writer: OutputStreamWriter, idx: Int): String? {
        writer.write("RETR " + idx + "\r\n")
        writer.flush()
        val status = reader.readLine() ?: return null
        if (!status.startsWith("+OK")) return null
        val sb = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            if (line == ".") break
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    /** 取主题行（只用于日志）。 */
    private fun subjectOf(raw: String): String {
        for (l in raw.split('\n')) {
            val t = l.trim()
            if (t.isEmpty()) break
            if (t.lowercase().startsWith("subject:")) return decodeRfc2047(t.substring(8).trim())
        }
        return "(无主题)"
    }

    /** 主题里的中文是 RFC2047 编码的，解出来只为看日志。 */
    private fun decodeRfc2047(s: String): String {
        val m = Regex("=\\?UTF-8\\?B\\?([A-Za-z0-9+/=]+)\\?=").find(s) ?: return s
        return runCatching { String(Base64.decode(m.groupValues[1], Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(s)
    }

    /** 从原始邮件里取出正文：优先按 base64 解，否则原样。 */
    private fun extractPayload(raw: String): String? {
        val lines = raw.split('\n')
        var i = 0
        var base64 = false
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.isEmpty()) {
                i++
                break
            }
            if (l.lowercase().startsWith("content-transfer-encoding:") && l.lowercase().contains("base64")) base64 = true
            i++
        }
        val bodyLines = ArrayList<String>()
        var j = i
        while (j < lines.size) {
            val l = lines[j]
            if (l == "." ) break
            bodyLines.add(l)
            j++
        }
        val body = bodyLines.joinToString("")
        if (!base64) return body.trim().ifEmpty { null }
        return runCatching {
            String(Base64.decode(body.trim(), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun cmd(reader: BufferedReader, writer: OutputStreamWriter, c: String): String {
        writer.write(c + "\r\n")
        writer.flush()
        return reader.readLine() ?: throw IOException("POP3 连接被关闭（" + c.substringBefore(' ') + "）")
    }

    private fun expectOk(line: String?, what: String) {
        if (line == null) throw IOException(what + "：连接被关闭")
        if (!line.startsWith("+OK")) throw IOException(what + "失败：" + line.take(160))
    }
}
