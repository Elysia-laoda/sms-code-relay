package com.elysia.smsrelay

import android.util.Base64
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 极简 SMTP（SSL，隐式 465）：EHLO → AUTH LOGIN → MAIL/RCPT → DATA → QUIT。
 * 正文用 base64 编码，省掉点开头转义和行宽限制；主题按 RFC2047 编码，邮件客户端里能正常显示中文。
 */
object Smtp {

    fun send(
        host: String,
        port: Int,
        tls: Boolean,
        user: String,
        pass: String,
        to: String,
        subject: String,
        body: String
    ) {
        // connect 必须带超时：默认无限等待，对端黑洞化时发送线程会永久卡住（队列随之停摆）。
        val raw = java.net.Socket()
        raw.connect(java.net.InetSocketAddress(host, port), 8000)
        raw.soTimeout = 20000
        val sock = if (tls) {
            ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket).apply {
                startHandshake()
            }
        } else raw
        val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
        val writer = OutputStreamWriter(sock.outputStream, Charsets.UTF_8)

        try {
            expect(reader, "220", "连接")
            command(reader, writer, "EHLO smsrelay", "250", "EHLO")
            command(reader, writer, "AUTH LOGIN", "334", "AUTH")
            command(reader, writer, b64(user), "334", "用户名")
            command(reader, writer, b64(pass), "235", "授权码")
            command(reader, writer, "MAIL FROM:<" + user + ">", "250", "MAIL FROM")
            command(reader, writer, "RCPT TO:<" + to + ">", "250", "RCPT TO")
            command(reader, writer, "DATA", "354", "DATA")

            val head = StringBuilder()
            head.append("From: ").append(user).append("\r\n")
            head.append("To: ").append(to).append("\r\n")
            head.append("Subject: =?UTF-8?B?").append(b64(subject)).append("?=\r\n")
            head.append("MIME-Version: 1.0\r\n")
            head.append("Content-Type: text/plain; charset=UTF-8\r\n")
            head.append("Content-Transfer-Encoding: base64\r\n")
            head.append("\r\n")
            head.append(b64(body))
            head.append("\r\n.\r\n")
            writer.write(head.toString())
            writer.flush()
            expect(reader, "250", "投递")

            runCatching { command(reader, writer, "QUIT", "221", "QUIT") }
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun b64(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun command(
        reader: BufferedReader,
        writer: OutputStreamWriter,
        cmd: String,
        expectCode: String,
        what: String
    ) {
        writer.write(cmd + "\r\n")
        writer.flush()
        expect(reader, expectCode, what)
    }

    /** SMTP 多行应答：读到最后一行「3 位码 + 空格」为止。 */
    private fun expect(reader: BufferedReader, code: String, what: String) {
        while (true) {
            val line = reader.readLine() ?: throw IOException(what + "：连接被关闭")
            if (line.length >= 3 && line.startsWith(code) && (line.length == 3 || line[3] == ' ')) return
            if (line.length >= 4 && line[3] == '-') continue
            throw IOException(what + "失败：" + line.take(160))
        }
    }
}
