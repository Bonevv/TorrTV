package app.torrtv.torrent

import app.torrtv.core.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
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

/**
 * Работи САМО в отделния процес на торент услугата (TorrentService): ако native кодът на libtorrent
 * се срине, пада само този процес, а приложението остава живо. Процесът се убива след всеки стрийм,
 * затова няма нужда от сложно спиране/повторно ползване на сесията.
 */
class TorrentEngine(val baseDir: File, val profile: Int = 0) {

    /** Режим 0: диск без memory-map + срокове на парчетата; 1: диск по подразбиране + приоритети; 2: диск без memory-map + приоритети. */
    private val posixDisk: Boolean get() = profile != 1
    internal val useDeadlines: Boolean get() = profile == 0


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

    init {
        baseDir.mkdirs()
    }

    private fun session(): SessionManager = synchronized(lock) {
        val cur = sm
        if (cur != null && cur.isRunning) {
            cur
        } else {
            val s = SessionManager(false)
            val sp = SettingsPack()
            sp.connectionsLimit(60)
            sp.maxPeerlistSize(400)
            sp.maxQueuedDiskBytes(4 * 1024 * 1024)
            sp.uploadRateLimit(512 * 1024)
            sp.setEnableDht(true)
            sp.setDhtBootstrapNodes(
                "dht.libtorrent.org:25401,router.bittorrent.com:6881,router.utorrent.com:6881," +
                    "dht.transmissionbt.com:6881,dht.aelitis.com:6881"
            )
            val params = SessionParams(sp)
            // Обикновен дисков вход/изход вместо memory-mapped файлове: по-малко памет и без срив на
            // устройства с проблемни драйвери (препоръчано от самата библиотека за Android).
            if (posixDisk) params.setPosixDiskIO()
            AppLog.d("torrent", "старт на сесията (режим $profile, posix диск=$posixDisk)")
            s.start(params)
            AppLog.d("torrent", "сесията е стартирана")
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
            AppLog.d("torrent", "Отварям magnet $hex")
            val done = CompletableDeferred<Unit>()
            val listener = object : AlertListener {
                override fun types(): IntArray = intArrayOf(
                    AlertType.METADATA_RECEIVED.swig(),
                    AlertType.TORRENT_ERROR.swig()
                )

                override fun alert(alert: Alert<*>) {
                    val ta = alert as? TorrentAlert<*> ?: return
                    // ВАЖНО: handle() на алерта сочи към паметта на самия алерт, която се освобождава щом
                    // този метод приключи. Ползваме го само тук; за всичко останало вземаме собствено копие
                    // със s.find(...). Това беше причината за срива (SIGSEGV в torrent_handle::status).
                    val h = ta.handle()
                    if (!h.isValid) return
                    if (!h.infoHash().toHex().equals(hex, ignoreCase = true)) return
                    when (alert.type()) {
                        AlertType.METADATA_RECEIVED -> done.complete(Unit)
                        AlertType.TORRENT_ERROR -> done.completeExceptionally(IOException("Грешка в торента"))
                        else -> {
                        }
                    }
                }
            }
            s.addListener(listener)
            try {
                // Последователно теглене, но НЕ теглим нищо, докато не изберем файл.
                // Без AUTO_MANAGED: опашката на libtorrent не бива да спира/пуска торента сама.
                s.download(
                    magnet, baseDir,
                    TorrentFlags.SEQUENTIAL_DOWNLOAD.or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD)
                        .and_(TorrentFlags.AUTO_MANAGED.inv())
                )
                AppLog.d("torrent", "торентът е добавен")
                var ticks = 0
                withTimeout(100_000) {
                    while (!done.isCompleted) {
                        try {
                            val h = s.find(Sha1Hash.parseHex(hex))
                            if (h != null && h.isValid) {
                                val st = h.status()
                                onStatus(st.numPeers(), st.numSeeds())
                                // Резервна проверка, ако предупреждението за метаданни е изпуснато
                                if (st.hasMetadata()) done.complete(Unit)
                                if (ticks % 10 == 0) {
                                    AppLog.d("torrent", "чакам метаданни: пиъри ${st.numPeers()}, сийдъри ${st.numSeeds()}, DHT възли ${s.dhtNodes()}")
                                }
                            }
                        } catch (e: Exception) {
                        }
                        ticks++
                        delay(500)
                    }
                    done.await()
                }
                // Собствено копие на handle-а (не това от алерта), което е безопасно да пазим.
                val handle = s.find(Sha1Hash.parseHex(hex))
                if (handle == null || !handle.isValid) throw IOException("Торентът не е намерен в сесията")
                AppLog.d("torrent", "handle е взет със find()")
                var tiTry = handle.torrentFile()
                var waited = 0
                while ((tiTry == null || !tiTry.isValid) && waited++ < 25) {
                    delay(300)
                    tiTry = handle.torrentFile()
                }
                val ti = tiTry ?: throw IOException("Торентът не върна списък с файлове")
                val fs = ti.files()
                val list = ArrayList<TFile>()
                for (i in 0 until fs.numFiles()) {
                    if (fs.padFileAt(i)) continue
                    val path = fs.filePath(i)
                    val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
                    if (ext !in VIDEO_EXT) continue
                    list.add(TFile(i, path, fs.fileName(i), fs.fileSize(i), fs.fileOffset(i)))
                }
                AppLog.d("torrent", "метаданни получени: ${list.size} видео файла")
                MetaSession(hex, handle, ti, list)
            } catch (e: Throwable) {
                AppLog.e("torrent", "openMagnet", e)
                try {
                    s.removeListener(listener)
                } catch (_: Exception) {
                }
                // Нищо не се чисти тук: клиентът затваря целия процес на услугата.
                if (e is kotlinx.coroutines.TimeoutCancellationException)
                    throw IOException("Няма достатъчно пиъри, за да се получат данните на торента. Опитай друг.")
                throw e
            } finally {
                try {
                    s.removeListener(listener)
                } catch (_: Exception) {
                }
            }
        }

    /** windowMb = 0: класически режим (целият файл се тегли); иначе - най-много толкова MB напред от позицията. */
    fun startStream(meta: MetaSession, fileIndex: Int, windowMb: Int = 0): StreamSession {
        val f = meta.files.first { it.index == fileIndex }
        return StreamSession(this, meta, f, useDeadlines, windowMb)
    }
}

class StreamSession internal constructor(
    private val engine: TorrentEngine,
    private val meta: MetaSession,
    val file: TFile,
    private val deadlines: Boolean,
    private val windowMb: Int = 0
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

    /** Само при ограничен режим: кои парчета вече са разрешени за теглене. */
    private val limited = windowMb > 0
    private var allowed = BooleanArray(0)

    @Volatile
    var closed = false
        private set

    override val length: Long get() = file.size
    override val mime: String get() = TorrentEngine.mimeFor(file.name)

    /** Първите няколко извиквания пишат следа в дневника, за да се види на коя native команда е станал срив. */
    private var traced = 0

    private fun trace(step: String) {
        if (traced < 40) {
            traced++
            AppLog.d("ss", step)
        }
    }

    /** Спешно: време (deadline) или най-висок приоритет, според режима. */
    private fun urgent(piece: Int, rank: Int) {
        if (deadlines) h.setPieceDeadline(piece, 100 + rank * 150) else h.piecePriority(piece, Priority.TOP_PRIORITY)
    }

    init {
        trace("init: файлови приоритети")
        val prios = Array(ti.numFiles()) { Priority.IGNORE }
        prios[file.index] = Priority.DEFAULT
        h.prioritizeFiles(prios)
        trace("init: приоритетите са зададени")

        val headCount = max(2, ((TorrentEngine.HEAD_BYTES + pieceLen - 1) / pieceLen).toInt())
        val tailCount = max(1, ((TorrentEngine.TAIL_BYTES + pieceLen - 1) / pieceLen).toInt())
        head = (firstPiece..min(lastPiece, firstPiece + headCount - 1)).toList()
        tail = (max(firstPiece, lastPiece - tailCount + 1)..lastPiece).toList()
        // Началото (за старта) и краят (MP4/MKV индексите) са най-спешни.
        head.forEachIndexed { i, p -> urgent(p, i) }
        trace("init: началото е насрочено (${head.size} парчета)")
        tail.forEachIndexed { i, p -> urgent(p, i + 1) }
        trace("init: краят е насрочен (${tail.size} парчета), деадлайни=$deadlines")
        if (limited) {
            // Всичко извън началото и края е забранено за теглене; прозорецът се отваря напред, докато се гледа.
            allowed = BooleanArray(lastPiece - firstPiece + 1)
            val keep = (head + tail).toSet()
            for (p in firstPiece..lastPiece) {
                if (p in keep) {
                    allowed[p - firstPiece] = true
                    continue
                }
                try {
                    h.piecePriority(p, Priority.IGNORE)
                } catch (_: Exception) {
                    break
                }
            }
            trace("init: ограничен режим, прозорец $windowMb MB")
        }
    }

    /** Отваря за теглене парчетата от p0 до края на прозореца (само ограничен режим). */
    private fun openWindow(p0: Int) {
        if (!limited) return
        val count = max(8, ((windowMb.toLong() * 1024 * 1024) / pieceLen).toInt())
        try {
            for (p in p0..min(lastPiece, p0 + count)) {
                val i = p - firstPiece
                if (i < 0 || allowed[i]) continue
                h.piecePriority(p, Priority.DEFAULT)
                allowed[i] = true
            }
        } catch (_: Exception) {
        }
    }

    private fun have(p: Int): Boolean = try {
        if (closed) throw IOException("closed")
        h.havePiece(p)
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("Торентът е спрян")
    }

    fun isPreloaded(): Boolean = try {
        trace("isPreloaded: havePiece")
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
            trace("stats: status()")
            val st = h.status()
            val total = ti.totalSize()
            trace("stats: готово")
            EngineStats(
                st.numPeers(), st.numSeeds(), st.downloadRate().toLong(),
                if (total > 0) (st.totalDone() * 100 / total).toInt() else 0, st.totalDone()
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
        openWindow(p0)
        try {
            for (i in 0 until window) {
                val p = p0 + i
                if (p > lastPiece) break
                if (!h.havePiece(p)) urgent(p, i)
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
    }
}
