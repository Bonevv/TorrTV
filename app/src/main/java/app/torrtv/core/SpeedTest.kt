package app.torrtv.core

import app.torrtv.core.Http.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/** Измерване на скоростта на мрежата и на записа на диска. */
object SpeedTest {

    /** Публични файлове за тест; ако един не отговаря, се опитва следващият. */
    val netUrls = listOf(
        "https://speed.cloudflare.com/__down?bytes=40000000",
        "https://fsn1-speed.hetzner.com/100MB.bin",
        "https://speedtest.tele2.net/100MB.zip",
        "https://proof.ovh.net/files/100Mb.dat"
    )

    fun mbps(bytes: Long, ms: Long): Double = if (ms <= 0) 0.0 else bytes * 8.0 / 1_000_000.0 / (ms / 1000.0)

    /** Мбит/с или null, ако нито един адрес не отговори. */
    suspend fun net(maxMs: Long = 8000, maxBytes: Long = 40L shl 20): Double? = withContext(Dispatchers.IO) {
        for (u in netUrls) {
            val r = try {
                one(u, maxMs, maxBytes)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (r != null && r > 0) return@withContext r
        }
        null
    }

    private suspend fun one(url: String, maxMs: Long, maxBytes: Long): Double? {
        val req = Request.Builder().url(url).header("User-Agent", Http.BROWSER_UA).build()
        Http.client.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) return null
            val ins = resp.body?.byteStream() ?: return null
            val buf = ByteArray(64 * 1024)
            var total = 0L
            var t0 = 0L
            var warmBytes = 0L
            var warmMs = -1L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = ins.read(buf)
                if (n < 0) break
                val now = System.nanoTime() / 1_000_000
                if (t0 == 0L) t0 = now
                total += n
                val el = now - t0
                // Първата секунда не се брои (бавен старт на връзката).
                if (warmMs < 0 && el >= 1000) {
                    warmMs = el
                    warmBytes = total
                }
                if (el >= maxMs || total >= maxBytes) break
            }
            val elapsed = System.nanoTime() / 1_000_000 - t0
            if (total < 256 * 1024 || elapsed <= 0) return null
            return if (warmMs >= 0 && elapsed - warmMs >= 1000) mbps(total - warmBytes, elapsed - warmMs) else mbps(total, elapsed)
        }
    }

    /** MB/с запис в дадената папка или null при грешка (няма права, няма място). */
    suspend fun disk(dir: File, mb: Int = 64, maxMs: Long = 6000): Double? = withContext(Dispatchers.IO) {
        val f = File(dir, "speedtest.tmp")
        try {
            dir.mkdirs()
            val chunk = ByteArray(1 shl 20) { (it * 31).toByte() }
            var written = 0L
            val t0 = System.nanoTime() / 1_000_000
            FileOutputStream(f).use { out ->
                while (written < mb.toLong() * (1 shl 20) && System.nanoTime() / 1_000_000 - t0 < maxMs) {
                    currentCoroutineContext().ensureActive()
                    out.write(chunk)
                    written += chunk.size
                }
                out.fd.sync()
            }
            val ms = System.nanoTime() / 1_000_000 - t0
            if (ms <= 0 || written == 0L) null else written / 1048576.0 / (ms / 1000.0)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            try {
                f.delete()
            } catch (_: Exception) {
            }
        }
    }
}
