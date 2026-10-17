package app.torrtv.torrent

import app.torrtv.core.AppLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Малък HTTP сървър само за това устройство (127.0.0.1).
 * Дава видео потока от торента (с поддръжка на Range, за да работи превъртането)
 * и свалените субтитри - така всеки плеър (вграден или външен) може да ги зареди.
 */
class LocalServer {

    interface Content {
        val length: Long
        val mime: String

        /** Блокира, докато данните са налични. Връща -1 при край или затворен поток. */
        fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int
    }

    class BytesContent(private val bytes: ByteArray, override val mime: String) : Content {
        override val length: Long get() = bytes.size.toLong()
        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (pos >= bytes.size) return -1
            val n = min(len.toLong(), bytes.size - pos).toInt()
            System.arraycopy(bytes, pos.toInt(), buf, off, n)
            return n
        }
    }

    private val routes = ConcurrentHashMap<String, Content>()
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null

    @Volatile
    var port: Int = 0
        private set

    @Synchronized
    fun start() {
        if (server != null) return
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        port = s.localPort
        pool.execute {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    pool.execute { handle(c) }
                } catch (e: IOException) {
                    break
                }
            }
        }
    }

    fun set(path: String, c: Content) {
        routes[path] = c
    }

    fun clearRoutes() {
        routes.clear()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun simple(out: OutputStream, code: Int, text: String) {
        val body = text.toByteArray()
        out.write(
            ("HTTP/1.1 $code $text\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            val input = BufferedInputStream(sock.getInputStream())
            val out = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
            val reqLine = readLine(input) ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = try {
                URLDecoder.decode(parts[1].substringBefore('?'), "UTF-8")
            } catch (e: Exception) {
                parts[1].substringBefore('?')
            }
            var range: String? = null
            while (true) {
                val l = readLine(input) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0 && l.substring(0, i).equals("range", ignoreCase = true)) range = l.substring(i + 1).trim()
            }
            val c = routes[path]
            AppLog.d("http", "$method $path range=${range ?: "-"}")
            if (c == null) {
                simple(out, 404, "Not Found")
                return
            }
            val len = c.length
            var start = 0L
            var end = len - 1
            var partial = false
            val rg = range
            if (rg != null && rg.startsWith("bytes=")) {
                val spec = rg.removePrefix("bytes=").split(",")[0].trim()
                val a = spec.substringBefore('-')
                val b = spec.substringAfter('-', "")
                if (a.isEmpty()) {
                    start = max(0L, len - (b.toLongOrNull() ?: 0L))
                } else {
                    start = a.toLongOrNull() ?: 0L
                    if (b.isNotEmpty()) end = min(end, b.toLongOrNull() ?: end)
                }
                partial = true
                if (start >= len || start > end) {
                    out.write(
                        ("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$len\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    out.flush()
                    return
                }
            }
            val count = end - start + 1
            val head = StringBuilder()
            head.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            head.append("Content-Type: ").append(c.mime).append("\r\n")
            head.append("Accept-Ranges: bytes\r\n")
            head.append("Content-Length: ").append(count).append("\r\n")
            if (partial) head.append("Content-Range: bytes $start-$end/$len\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray())
            out.flush()
            if (method == "HEAD") return

            val buf = ByteArray(64 * 1024)
            var pos = start
            var remain = count
            var sent = 0L
            try {
            while (remain > 0) {
                val n = c.read(pos, buf, 0, min(buf.size.toLong(), remain).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                out.flush()
                pos += n
                remain -= n
                sent += n
            }
            } finally {
                if (sent > 0 || remain > 0) AppLog.d("http", "край $path: изпратени ${sent / 1024} KB от ${count / 1024} KB")
            }
        } catch (e: Exception) {
            // клиентът е затворил връзката (превъртане, спиране) - нормално
            AppLog.d("http", "връзката затворена: ${e.javaClass.simpleName} ${e.message ?: ""}")
        } finally {
            try {
                sock.close()
            } catch (e: Exception) {
            }
        }
    }
}
