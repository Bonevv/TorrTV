package app.torrtv.torrent

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import app.torrtv.core.AppLog
import app.torrtv.core.CrashInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException

/**
 * Страната на приложението за торент процеса. Всичко се вика от главната нишка.
 * Всеки стрийм има свой процес (t0/t1/t2 се редуват) и той се убива при затваряне.
 */
class TorrentClient(private val app: Application) {

    /** Папката за свалените парчета на текущата сесия (във вътрешната памет или на външен диск). */
    @Volatile
    private var baseDir: File? = null

    class Started(val port: Int, val path: String, val name: String, val size: Long)

    class Live(val stats: EngineStats, val preloaded: Boolean, val preloadPct: Int)

    @Volatile
    var live: Live? = null
        private set

    /** Вика се, когато връзката с торент процеса се скъса неочаквано (напр. native срив). */
    var onDied: ((String) -> Unit)? = null

    private val classes = arrayOf(
        TorrentService0::class.java, TorrentService1::class.java, TorrentService2::class.java
    )
    private var next = (System.currentTimeMillis() % classes.size).toInt()
    private val reply = Messenger(Handler(Looper.getMainLooper()) { m ->
        try {
            onReply(m)
        } catch (e: Throwable) {
            AppLog.e("client", "reply ${m.what}", e)
        }
        true
    })
    private val cleanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var conn: ServiceConnection? = null
    private var service: Messenger? = null
    private var gen = 0
    private var pid = 0

    /** PID на торент процеса (0, ако няма такъв) - за показване на паметта. */
    @Volatile
    var torrentPid = 0
        private set
    private var connectedWait: CompletableDeferred<Unit>? = null
    private var metaWait: CompletableDeferred<List<TFile>>? = null
    private var startedWait: CompletableDeferred<Started>? = null
    private var statusCb: ((Int, Int) -> Unit)? = null

    /** Стартира торент процеса и се свързва с него. */
    suspend fun connect() {
        disconnect()
        gen++
        val myGen = gen
        val cls = classes[next++ % classes.size]
        val wait = CompletableDeferred<Unit>()
        connectedWait = wait
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (conn !== this || binder == null) return
                service = Messenger(binder)
                wait.complete(Unit)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (conn === this && gen == myGen) died("Торент процесът спря неочаквано")
            }

            override fun onBindingDied(name: ComponentName?) {
                if (conn === this && gen == myGen) died("Торент процесът не може да се стартира")
            }

            override fun onNullBinding(name: ComponentName?) {
                if (conn === this && gen == myGen) died("Торент услугата не отговаря")
            }
        }
        conn = c
        val ok = try {
            app.bindService(Intent(app, cls), c, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            conn = null
            throw IOException("Не мога да стартирам торент услугата")
        }
        within(20_000, "Торент услугата не се стартира навреме") { wait.await() }
        AppLog.d("client", "свързан с ${cls.simpleName}")
    }

    /** Отваря magnet линка и връща видео файловете в торента. */
    suspend fun open(magnet: String, profile: Int, base: File, onStatus: (peers: Int, seeds: Int) -> Unit): List<TFile> {
        val d = CompletableDeferred<List<TFile>>()
        baseDir = base
        metaWait = d
        statusCb = onStatus
        post(Proto.C_OPEN) {
            putString("magnet", magnet)
            putInt("profile", profile)
            putString("base", base.path)
        }
        return within(130_000, "Няма отговор от торент услугата") { d.await() }
    }

    /** Започва стрийма на избрания файл; връща къде е локалният сървър. */
    suspend fun play(index: Int, windowMb: Int = 0): Started {
        val d = CompletableDeferred<Started>()
        startedWait = d
        live = null
        post(Proto.C_PLAY) {
            putInt("index", index)
            putInt("window", windowMb)
        }
        return within(40_000, "Файлът не стартира навреме") { d.await() }
    }

    /** withTimeout хвърля CancellationException, който контролерът би погълнал тихо - затова го правим IOException. */
    private suspend fun <T> within(ms: Long, msg: String, block: suspend () -> T): T =
        try {
            withTimeout(ms) { block() }
        } catch (e: TimeoutCancellationException) {
            throw IOException(msg)
        }

    private fun post(what: Int, fill: Bundle.() -> Unit) {
        val s = service ?: throw IOException("Няма връзка с торент услугата")
        try {
            val m = Message.obtain(null, what, gen, 0)
            m.replyTo = reply
            m.data = Bundle().apply(fill)
            s.send(m)
        } catch (e: RemoteException) {
            throw IOException("Торент процесът не отговаря")
        }
    }

    private fun onReply(m: Message) {
        if (m.arg1 != gen) return // остатъчно съобщение от предишен процес
        val b = m.data
        when (m.what) {
            Proto.S_HELLO -> {
                pid = b.getInt("pid")
                torrentPid = pid
            }
            Proto.S_STATUS -> statusCb?.invoke(b.getInt("peers"), b.getInt("seeds"))
            Proto.S_META -> {
                val idx = b.getIntArray("idx") ?: IntArray(0)
                val path = b.getStringArray("path") ?: emptyArray<String>()
                val name = b.getStringArray("name") ?: emptyArray<String>()
                val size = b.getLongArray("size") ?: LongArray(0)
                val off = b.getLongArray("off") ?: LongArray(0)
                val n = minOf(idx.size, path.size, name.size, size.size, off.size)
                metaWait?.complete(List(n) { TFile(idx[it], path[it], name[it], size[it], off[it]) })
            }
            Proto.S_STARTED -> startedWait?.complete(
                Started(b.getInt("port"), b.getString("path") ?: "/video.mkv", b.getString("name") ?: "", b.getLong("size"))
            )
            Proto.S_STATS -> live = Live(
                EngineStats(b.getInt("peers"), b.getInt("seeds"), b.getLong("speed"), b.getInt("filePct"), b.getLong("done")),
                b.getBoolean("pre"), b.getInt("prePct")
            )
            Proto.S_ERROR -> {
                val msg = b.getString("msg") ?: "Грешка в торент услугата"
                val e = IOException(msg)
                val pending = metaWait?.isActive == true || startedWait?.isActive == true
                metaWait?.completeExceptionally(e)
                startedWait?.completeExceptionally(e)
                if (!pending) onDied?.invoke(msg)
            }
        }
    }

    private fun died(why: String) {
        AppLog.d("client", why)
        // След секунда-две Android вече има причината и следата на срива; записваме ги в дневника.
        cleanScope.launch {
            delay(1800)
            CrashInfo.logRecent(app, 3)
        }
        val e = IOException(why)
        val cw = connectedWait
        val mw = metaWait
        val sw = startedWait
        // Отвързваме веднага, за да не рестартира системата умрялата услуга.
        disconnect()
        // Първо контролерът (състояние "грешка" + смяна на режима), после будим чакащите - те вече са отменени.
        onDied?.invoke(why)
        cw?.completeExceptionally(e)
        mw?.completeExceptionally(e)
        sw?.completeExceptionally(e)
    }

    private fun disconnect() {
        val c = conn
        val s = service
        val p = pid
        conn = null
        service = null
        pid = 0
        torrentPid = 0
        connectedWait = null
        metaWait = null
        startedWait = null
        statusCb = null
        live = null
        if (c == null) return
        try {
            s?.send(Message.obtain(null, Proto.C_BYE, gen, 0))
        } catch (_: Exception) {
        }
        try {
            app.unbindService(c)
        } catch (_: Exception) {
        }
        cleanup(p)
    }

    /** Затваря стрийма: убива торент процеса и трие свалените файлове. */
    fun close() {
        gen++ // всичко, което още идва от стария процес, се игнорира
        disconnect()
    }

    private fun cleanup(p: Int) {
        if (p <= 0) return
        cleanScope.launch {
            delay(1500)
            // Ако процесът все още е жив (забил е), убиваме го. Проверяваме името, за да не уцелим чужд PID.
            try {
                val cmd = File("/proc/$p/cmdline").readText().substringBefore('\u0000')
                if (cmd.startsWith(app.packageName + ":t")) Process.killProcess(p)
            } catch (_: Exception) {
            }
            delay(500)
            try {
                File(baseDir ?: File(app.cacheDir, "torrents"), "$p").deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }
}
