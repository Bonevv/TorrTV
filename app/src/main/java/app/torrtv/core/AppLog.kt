package app.torrtv.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Малък файлов дневник. Остава и ако процесът бъде убит, така че след срив се вижда докъде е стигнало.
 * Основният процес пише в torrtv.log, а торент процесът (отделен, за да не влачи приложението при срив) - в torrent.log.
 * Показва се от Настройки → Дневник.
 */
object AppLog {
    private var file: File? = null
    private var dir: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private const val MAX = 120_000L
    private const val MAIN = "torrtv.log"
    private const val TORRENT = "torrent.log"

    @Synchronized
    fun init(ctx: Context, torrentProcess: Boolean = false) {
        try {
            dir = ctx.filesDir
            val f = File(ctx.filesDir, if (torrentProcess) TORRENT else MAIN)
            if (f.exists() && f.length() > MAX) {
                val tail = f.readText().takeLast((MAX / 2).toInt())
                f.writeText(tail)
            }
            file = f
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun d(tag: String, msg: String) {
        try {
            file?.appendText("${fmt.format(Date())} [$tag] $msg\n")
        } catch (_: Exception) {
        }
    }

    fun e(tag: String, msg: String, t: Throwable) {
        d(tag, "$msg: ${t.javaClass.simpleName}: ${t.message}")
        try {
            val trace = t.stackTraceToString().lines().take(14).joinToString("\n")
            d(tag, trace)
        } catch (_: Exception) {
        }
    }

    private fun tail(f: File, n: Int): String =
        try {
            if (f.exists()) f.readText().takeLast(n).trim() else ""
        } catch (e: Exception) {
            "(грешка при четене: ${e.message})"
        }

    /** Края на двата дневника: приложението и (ако има) торент процесът. */
    @Synchronized
    fun text(maxChars: Int = 6000): String {
        val d = dir ?: return "(празен)"
        val main = tail(File(d, MAIN), maxChars * 6 / 10)
        val tor = tail(File(d, TORRENT), maxChars * 4 / 10)
        val sb = StringBuilder()
        if (main.isNotEmpty()) sb.append(main)
        if (tor.isNotEmpty()) sb.append("\n— торент процес —\n").append(tor)
        return if (sb.isEmpty()) "(празен)" else sb.toString()
    }

    @Synchronized
    fun clear() {
        try {
            val d = dir ?: return
            File(d, MAIN).writeText("")
            File(d, TORRENT).delete()
        } catch (_: Exception) {
        }
    }
}
