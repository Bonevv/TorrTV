package app.torrtv.torrent

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import app.torrtv.core.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Съобщенията между приложението и торент процеса (Messenger + Bundle, без AIDL). */
object Proto {
    // приложение -> услуга
    const val C_OPEN = 1
    const val C_PLAY = 2
    const val C_BYE = 3

    // услуга -> приложение
    const val S_HELLO = 10
    const val S_STATUS = 11
    const val S_META = 12
    const val S_STARTED = 13
    const val S_STATS = 14
    const val S_ERROR = 15
}

/**
 * Целият торент двигател (libtorrent) живее в ОТДЕЛЕН процес. Ако native кодът се срине,
 * пада само този процес - приложението остава живо и показва грешка вместо да изхвърля потребителя.
 * Процесът се убива след всеки стрийм, така всеки нов опит започва с чисто състояние.
 */
open class TorrentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper()) { m ->
        try {
            handle(m)
        } catch (e: Throwable) {
            AppLog.e("tsvc", "handle ${m.what}", e)
        }
        true
    }
    private val messenger = Messenger(handler)

    @Volatile
    private var client: Messenger? = null

    @Volatile
    private var gen = 0
    private var engine: TorrentEngine? = null
    private var server: LocalServer? = null
    private var meta: MetaSession? = null
    private var openJob: Job? = null
    private var playJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.d("tsvc", "услугата стартира, pid ${Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        AppLog.d("tsvc", "клиентът се откачи - край на процеса")
        die()
        return false
    }

    override fun onDestroy() {
        die()
        super.onDestroy()
    }

    private fun die() {
        try {
            openJob?.cancel()
            playJob?.cancel()
        } catch (_: Throwable) {
        }
        Process.killProcess(Process.myPid())
    }

    private fun send(what: Int, fill: Bundle.() -> Unit = {}) {
        val c = client ?: return
        try {
            val m = Message.obtain(null, what, gen, 0)
            m.data = Bundle().apply(fill)
            c.send(m)
        } catch (e: Throwable) {
            AppLog.d("tsvc", "send $what: ${e.message}")
        }
    }

    private fun handle(m: Message) {
        when (m.what) {
            Proto.C_OPEN -> {
                client = m.replyTo
                gen = m.arg1
                send(Proto.S_HELLO) { putInt("pid", Process.myPid()) }
                open(m.data.getString("magnet") ?: "", m.data.getInt("profile"), m.data.getString("base"))
            }
            Proto.C_PLAY -> play(m.data.getInt("index"), m.data.getInt("window"))
            Proto.C_BYE -> {
                AppLog.d("tsvc", "команда за край")
                die()
            }
        }
    }

    private fun open(magnet: String, profile: Int, base: String?) {
        openJob?.cancel()
        AppLog.d("tsvc", "отваряне, режим $profile")
        val eng = engine ?: TorrentEngine(File(base ?: File(cacheDir, "torrents").path, "${Process.myPid()}"), profile).also { engine = it }
        openJob = scope.launch {
            try {
                val m = eng.openMagnet(magnet) { peers, seeds ->
                    send(Proto.S_STATUS) {
                        putInt("peers", peers)
                        putInt("seeds", seeds)
                    }
                }
                meta = m
                val fs = m.files
                send(Proto.S_META) {
                    putIntArray("idx", IntArray(fs.size) { fs[it].index })
                    putStringArray("path", Array(fs.size) { fs[it].path })
                    putStringArray("name", Array(fs.size) { fs[it].name })
                    putLongArray("size", LongArray(fs.size) { fs[it].size })
                    putLongArray("off", LongArray(fs.size) { fs[it].offset })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.e("tsvc", "open", e)
                send(Proto.S_ERROR) { putString("msg", e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun play(index: Int, windowMb: Int) {
        val m = meta
        val eng = engine
        if (m == null || eng == null) {
            send(Proto.S_ERROR) { putString("msg", "Няма отворен торент") }
            return
        }
        playJob?.cancel()
        playJob = scope.launch {
            try {
                AppLog.d("tsvc", "старт на стрийма, файл №$index")
                val s = eng.startStream(m, index, windowMb)
                AppLog.d("tsvc", "стриймът е създаден")
                val srv = server ?: LocalServer().also {
                    it.start()
                    server = it
                }
                val ext = s.file.name.substringAfterLast('.', "mkv").lowercase()
                val path = "/video.$ext"
                srv.set(path, s)
                AppLog.d("tsvc", "файл ${s.file.name} (${s.file.size / 1048576} MB), порт ${srv.port}")
                send(Proto.S_STARTED) {
                    putInt("port", srv.port)
                    putString("path", path)
                    putString("name", s.file.name)
                    putLong("size", s.file.size)
                }
                // Статистика към приложението (по-рядко, когато вече е буфериран началото).
                var n = 0
                while (isActive) {
                    val st = s.stats()
                    val pre = s.isPreloaded()
                    val pct = s.preloadPercent()
                    send(Proto.S_STATS) {
                        putInt("peers", st.peers)
                        putInt("seeds", st.seeds)
                        putLong("speed", st.speed)
                        putInt("filePct", st.filePercent)
                        putLong("done", st.totalDone)
                        putBoolean("pre", pre)
                        putInt("prePct", pct)
                    }
                    if (n++ % 6 == 0) AppLog.d("tsvc", "буфер $pct%, пиъри ${st.peers}, ↓ ${st.speed / 1024} KB/s")
                    delay(if (pre) 1500 else 500)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.e("tsvc", "play", e)
                send(Proto.S_ERROR) { putString("msg", e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}

// Три имена на процеса се редуват: следващият стрийм никога не среща още умиращия процес на предишния.
class TorrentService0 : TorrentService()

class TorrentService1 : TorrentService()

class TorrentService2 : TorrentService()
