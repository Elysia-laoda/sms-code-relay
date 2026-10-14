package com.elysia.smsrecv

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * 极简 HTTP 接收端：只认 POST /ingest（收 JSON）与 GET /health（探活）。
 * 不引任何第三方库——副机发过来的就是一段 JSON，手工解析 header 足够。
 * 每个连接单独起线程并带 5 秒读超时，避免一个半死连接把服务堵住。
 */
class HttpServer(
    val port: Int,
    private val onIngest: (String, String) -> Pair<Int, String>
) {

    @Volatile
    var running = false
        private set

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start(): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket(port)
            server = ss
            running = true
            acceptThread = Thread({ acceptLoop(ss) }, "smsrecv-http").apply {
                isDaemon = true
                start()
            }
            Logx.i("HTTP 接收端已监听 0.0.0.0:" + port)
            true
        } catch (t: Throwable) {
            Logx.e("HTTP 接收端起不来（端口 " + port + "）", t)
            false
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val sock = try {
                ss.accept()
            } catch (t: Throwable) {
                if (running) Logx.w("accept 失败：" + t.message)
                break
            }
            Thread({ handle(sock) }, "smsrecv-http-conn").apply { isDaemon = true; start() }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 5000
            val input = BufferedInputStream(sock.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1]
            var contentLength = 0
            var token = ""
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim().lowercase()
                    val v = line.substring(idx + 1).trim()
                    if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                    if (k == "x-token") token = v
                }
            }
            val body = if (contentLength > 0) {
                val buf = ByteArray(contentLength.coerceAtMost(256 * 1024))
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            } else ""

            val (status, reply) = when {
                method == "GET" && (path.startsWith("/health") || path == "/") -> 200 to "{\"ok\":true,\"app\":\"sms-recv\"}"
                method == "POST" && path.startsWith("/ingest") -> onIngest(body, token)
                else -> 404 to "{\"ok\":false,\"err\":\"not found\"}"
            }
            respond(sock, status, reply)
        } catch (t: Throwable) {
            Logx.w("处理请求出错：" + t.message)
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun respond(sock: Socket, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 " + status + " " + (if (status == 200) "OK" else "Error") + "\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: " + bytes.size + "\r\n" +
            "Connection: close\r\n\r\n"
        val out: OutputStream = sock.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == 10) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }
}
