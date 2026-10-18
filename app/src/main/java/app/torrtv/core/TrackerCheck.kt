package app.torrtv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Проверка дали UDP тракерите отговарят. Праща се първата стъпка от протокола на тракерите (BEP 15, „connect“):
 * жив тракер връща 16 байта със същия номер на заявката. Нищо не се тегли и нищо не се обявява.
 */
object TrackerCheck {

    class Result(val url: String, val ok: Boolean, val ms: Long, val note: String)

    /** Пакетът „connect“: магическото число, действие 0 и случаен номер на заявката. */
    fun connectPacket(txId: Int): ByteArray {
        val b = ByteBuffer.allocate(16)
        b.putLong(0x41727101980L)
        b.putInt(0)
        b.putInt(txId)
        return b.array()
    }

    /** Верен отговор: поне 16 байта, действие 0 и същият номер на заявката. */
    fun validReply(buf: ByteArray, len: Int, txId: Int): Boolean {
        if (len < 16) return false
        val b = ByteBuffer.wrap(buf, 0, len)
        return b.getInt(0) == 0 && b.getInt(4) == txId
    }

    /** "udp://host:port/announce" -> (host, port); null за всичко друго. */
    fun parse(url: String): Pair<String, Int>? {
        val u = url.trim()
        if (!u.startsWith("udp://", ignoreCase = true)) return null
        val hostPort = u.substring(6).substringBefore('/')
        if (hostPort.isEmpty()) return null
        val i = hostPort.lastIndexOf(':')
        if (i <= 0) return null
        val port = hostPort.substring(i + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return hostPort.substring(0, i) to port
    }

    /** Пробва два пъти (UDP губи пакети). [timeoutMs] е за всеки опит. */
    suspend fun check(url: String, timeoutMs: Int = 3000, tries: Int = 2): Result = withContext(Dispatchers.IO) {
        val hp = parse(url) ?: return@withContext Result(url, false, 0, "не е UDP адрес")
        val t0 = System.currentTimeMillis()
        val addr = try {
            InetAddress.getByName(hp.first)
        } catch (e: Exception) {
            return@withContext Result(url, false, System.currentTimeMillis() - t0, "адресът не се намира")
        }
        var note = "не отговаря"
        repeat(tries) {
            val tx = Random.nextInt()
            try {
                DatagramSocket().use { sock ->
                    sock.soTimeout = timeoutMs
                    val out = connectPacket(tx)
                    sock.send(DatagramPacket(out, out.size, InetSocketAddress(addr, hp.second)))
                    val buf = ByteArray(64)
                    val pk = DatagramPacket(buf, buf.size)
                    sock.receive(pk)
                    if (validReply(buf, pk.length, tx)) {
                        return@withContext Result(url, true, System.currentTimeMillis() - t0, "ok")
                    }
                    note = "странен отговор"
                }
            } catch (e: java.net.SocketTimeoutException) {
                note = "не отговаря"
            } catch (e: Exception) {
                note = e.message ?: "грешка"
            }
        }
        Result(url, false, System.currentTimeMillis() - t0, note)
    }

    /** Всички наведнъж; редът на резултатите е като на адресите. */
    suspend fun checkAll(urls: List<String>): List<Result> = coroutineScope {
        urls.map { async { check(it) } }.awaitAll()
    }

    /** "udp://tracker.opentrackr.org:1337/announce" -> "tracker.opentrackr.org" (за показване). */
    fun shortName(url: String): String = parse(url)?.first ?: url
}
