package app.torrtv.torrent

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.TorrentAlert
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class TFile(val index: Int, val path: String, val name: String, val size: Long, val offset: Long)

data class EngineStats(
    val peers: Int,
    val seeds: Int,
    val speed: Long,
    val filePercent: Int,
    val totalDone: Long
)

class MetaSession internal constructor(
    val hash: String,
    internal val handle: TorrentHandle,
    internal val info: TorrentInfo,
    val files: List<TFile>
)

class TorrentEngine(private val ctx: Context) {

    companion object {
        val VIDEO_EXT = setOf("mkv", "mp4", "avi", "mov", "m4v", "ts", "m2ts", "wmv", "webm", "mpg", "mpeg", "flv")
        const val HEAD_BYTES = 12L * 1024 * 1024
        const val TAIL_BYTES = 3L * 1024 * 1024

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts", "m2ts" -> "video/mp2t"
            else -> "video/*"
        }
    }

    private val lock = Any()
    private var sm: SessionManager? = null
    private var active = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val baseDir: File get() = File(ctx.cacheDir, "torrents").also { it.mkdirs() }

    private fun session(): SessionManager = synchronized(lock) {
        val cur = sm
        if (cur != null && cur.isRunning) {
            cur
        } else {
            val s = SessionManager(false)
            val sp = SettingsPack()
            sp.connectionsLimit(160)
            s.start(SessionParams(sp))
            sm = s
            s
        }
    }

    /** Свързва се с торента по magnet линк и изчаква метаданните (списъка с файлове). */
    suspend fun openMagnet(magnet: String, onStatus: (peers: Int, seeds: Int) -> Unit): MetaSession =
        withContext(Dispatchers.IO) {
            val hex = app.torrtv.core.Magnets.infoHash(magnet)
                ?: throw IllegalArgumentException("Невалиден magnet линк")
            val s = session()
            synchronized(lock) { active++ }
            val done = CompletableDeferred<TorrentHandle>()
            val listener = object : AlertListener {
                override fun types(): IntArray = intArrayOf(
                    AlertType.ADD_TORRENT.swig(),
                    AlertType.METADATA_RECEIVED.swig(),
                    AlertType.TORRENT_ERROR.swig()
                )

                override fun alert(alert: Alert<*>) {
                    val ta = alert as? TorrentAlert<*> ?: return
                    val h = ta.handle()
                    if (!h.isValid) return
                    if (!h.infoHash().toHex().equals(hex, ignoreCase = true)) return
                    when (alert.type()) {
                        AlertType.METADATA_RECEIVED -> done.complete(h)
                        AlertType.ADD_TORRENT -> {
                            val ti = h.torrentFile()
                            if (ti != null && ti.isValid) done.complete(h)
                        }
                        AlertType.TORRENT_ERROR -> done.completeExceptionally(IOException("Грешка в торента"))
                        else -> {
                        }
                    }
                }
            }
            s.addListener(listener)
            try {
                // Последователно теглене, но НЕ теглим нищо, докато не изберем файл.
                s.download(
                    magnet, baseDir,
                    TorrentFlags.SEQUENTIAL_DOWNLOAD.or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD)
                )
                val handle = withTimeout(100_000) {
                    while (!done.isCompleted) {
                        try {
                            val h = s.find(Sha1Hash.parseHex(hex))
                            if (h != null && h.isValid) {
                                val st = h.status()
                                onStatus(st.numPeers(), st.numSeeds())
                            }
                        } catch (e: Exception) {
                        }
                        delay(500)
                    }
                    done.await()
                }
                val ti = handle.torrentFile() ?: throw IOException("Няма метаданни")
                val fs = ti.files()
                val list = ArrayList<TFile>()
                for (i in 0 until fs.numFiles()) {
                    if (fs.padFileAt(i)) continue
                    val path = fs.filePath(i)
                    val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
                    if (ext !in VIDEO_EXT) continue
                    list.add(TFile(i, path, fs.fileName(i), fs.fileSize(i), fs.fileOffset(i)))
                }
                MetaSession(hex, handle, ti, list)
            } catch (e: Throwable) {
                try {
                    val h = s.find(Sha1Hash.parseHex(hex))
                    if (h != null && h.isValid) s.remove(h, SessionHandle.DELETE_FILES)
                } catch (_: Exception) {
                }
                released()
                if (e is kotlinx.coroutines.TimeoutCancellationException)
                    throw IOException("Няма достатъчно пиъри - не успях да получа данните за торента")
                throw e
            } finally {
                s.removeListener(listener)
            }
        }

    fun startStream(meta: MetaSession, fileIndex: Int): StreamSession {
        val f = meta.files.first { it.index == fileIndex }
        return StreamSession(this, meta, f)
    }

    internal fun remove(meta: MetaSession) {
        try {
            val s = sm
            if (s != null && s.isRunning && meta.handle.isValid) s.remove(meta.handle, SessionHandle.DELETE_FILES)
        } catch (_: Exception) {
        }
        released()
    }

    private fun released() {
        val idle = synchronized(lock) {
            if (active > 0) active--
            active == 0
        }
        if (idle) {
            // Ако няма активен торент 2 минути - спираме сесията (спестява мрежа и батерия).
            scope.launch {
                delay(120_000)
                val stop = synchronized(lock) { active == 0 }
                if (stop) {
                    val s = synchronized(lock) {
                        val x = sm
                        sm = null
                        x
                    }
                    try {
                        s?.stop()
                    } catch (_: Exception) {
                    }
                    cleanDisk()
                }
            }
        }
    }

    fun cleanDisk() {
        try {
            baseDir.listFiles()?.forEach { it.deleteRecursively() }
        } catch (_: Exception) {
        }
    }
}

class StreamSession internal constructor(
    private val engine: TorrentEngine,
    private val meta: MetaSession,
    val file: TFile
) : LocalServer.Content {

    private val h = meta.handle
    private val ti = meta.info
    private val pieceLen = ti.pieceLength()
    private val firstPiece = (file.offset / pieceLen).toInt()
    private val lastPiece = ((file.offset + file.size - 1) / pieceLen).toInt()
    private val head: List<Int>
    private val tail: List<Int>
    private var raf: RandomAccessFile? = null
    private var lastWindow = -1000

    @Volatile
    var closed = false
        private set

    override val length: Long get() = file.size
    override val mime: String get() = TorrentEngine.mimeFor(file.name)

    init {
        val prios = Array(ti.numFiles()) { Priority.IGNORE }
        prios[file.index] = Priority.DEFAULT
        h.prioritizeFiles(prios)
        h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        h.resume()

        val headCount = max(2, ((TorrentEngine.HEAD_BYTES + pieceLen - 1) / pieceLen).toInt())
        val tailCount = max(1, ((TorrentEngine.TAIL_BYTES + pieceLen - 1) / pieceLen).toInt())
        head = (firstPiece..min(lastPiece, firstPiece + headCount - 1)).toList()
        tail = (max(firstPiece, lastPiece - tailCount + 1)..lastPiece).toList()
        // Началото (за старта) и краят (MP4/MKV индексите) са най-спешни.
        head.forEachIndexed { i, p -> h.setPieceDeadline(p, 100 + i * 200) }
        tail.forEachIndexed { i, p -> h.setPieceDeadline(p, 150 + i * 200) }
    }

    private fun have(p: Int): Boolean = try {
        h.havePiece(p)
    } catch (e: Exception) {
        throw IOException("Торентът е спрян")
    }

    fun isPreloaded(): Boolean = try {
        (head + tail).all { h.havePiece(it) }
    } catch (e: Exception) {
        false
    }

    fun preloadPercent(): Int {
        val all = (head + tail).distinct()
        if (all.isEmpty()) return 100
        val have = try {
            all.count { h.havePiece(it) }
        } catch (e: Exception) {
            0
        }
        return have * 100 / all.size
    }

    fun stats(): EngineStats {
        return try {
            val st = h.status()
            val prog = h.fileProgress()
            val done = if (file.index < prog.size) prog[file.index] else 0L
            EngineStats(
                st.numPeers(), st.numSeeds(), st.downloadRate().toLong(),
                if (file.size > 0) (done * 100 / file.size).toInt() else 0, st.totalDone()
            )
        } catch (e: Exception) {
            EngineStats(0, 0, 0, 0, 0)
        }
    }

    /** Подрежда следващите ~24 MB след позицията на плеъра като най-спешни. */
    private fun prioritize(posInFile: Long) {
        val p0 = ((file.offset + posInFile) / pieceLen).toInt()
        if (p0 == lastWindow) return
        val window = max(6, min(40, ((24L * 1024 * 1024) / pieceLen).toInt()))
        try {
            if (kotlin.math.abs(p0 - lastWindow) > window) h.clearPieceDeadlines()
            for (i in 0 until window) {
                val p = p0 + i
                if (p > lastPiece) break
                if (!h.havePiece(p)) h.setPieceDeadline(p, 100 + i * 120)
            }
        } catch (_: Exception) {
        }
        lastWindow = p0
    }

    private fun waitPiece(p: Int) {
        while (!have(p)) {
            if (closed) throw IOException("closed")
            try {
                Thread.sleep(60)
            } catch (e: InterruptedException) {
                throw IOException("interrupted")
            }
        }
    }

    private fun openRaf(): RandomAccessFile {
        raf?.let { return it }
        synchronized(this) {
            raf?.let { return it }
            val f = File(engine.baseDir, file.path)
            var tries = 0
            while (!f.exists()) {
                if (closed) throw IOException("closed")
                if (++tries > 200) throw IOException("файлът не е създаден")
                Thread.sleep(50)
            }
            val r = RandomAccessFile(f, "r")
            raf = r
            return r
        }
    }

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (closed) return -1
        if (pos >= file.size) return -1
        prioritize(pos)
        val abs = file.offset + pos
        val piece = (abs / pieceLen).toInt()
        // Четем най-много до края на текущото парче - така чакаме само едно парче наведнъж.
        val pieceEndInFile = (piece + 1).toLong() * pieceLen - file.offset
        val n = min(min(len.toLong(), file.size - pos), pieceEndInFile - pos).toInt()
        waitPiece(piece)
        val r = openRaf()
        var attempts = 0
        while (true) {
            val total = synchronized(r) {
                r.seek(pos)
                var t = 0
                while (t < n) {
                    val got = r.read(buf, off + t, n - t)
                    if (got <= 0) break
                    t += got
                }
                t
            }
            if (total > 0) return total
            if (closed || ++attempts > 100) return -1
            Thread.sleep(50)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            raf?.close()
        } catch (_: Exception) {
        }
        engine.remove(meta)
    }
}
