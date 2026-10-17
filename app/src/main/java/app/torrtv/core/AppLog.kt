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
    private const val CRASH = "crash.log"

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

    private var writes = 0

    @Synchronized
    fun d(tag: String, msg: String) {
        try {
            val f = file ?: return
            f.appendText("${fmt.format(Date())} [$tag] $msg\n")
            // На всеки 200 реда проверяваме размера - дневникът никога не расте безкрайно.
            if (++writes % 200 == 0 && f.length() > MAX) f.writeText(f.readText().takeLast((MAX / 2).toInt()))
        } catch (_: Exception) {
        }
    }

    /** Оставя само последните редове (вика се при автоматичното чистене на кеша). Следата на срива не се пипа. */
    @Synchronized
    fun trim(keepLines: Int = 200) {
        try {
            val d = dir ?: return
            val f = File(d, MAIN)
            if (f.exists()) f.writeText(lastLines(f, keepLines, 400).joinToString("\n") + "\n")
            File(d, TORRENT).delete()
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

    /** Последните n реда на файла, всеки скъсен, за да се вижда повече от дневника на екрана. */
    private fun lastLines(f: File, n: Int, width: Int = 118): List<String> =
        try {
            if (!f.exists()) emptyList()
            else {
                val len = f.length()
                val raf = java.io.RandomAccessFile(f, "r")
                val take = minOf(len, 60_000L).toInt()
                val buf = ByteArray(take)
                raf.seek(len - take)
                raf.readFully(buf)
                raf.close()
                String(buf, Charsets.UTF_8).lines().filter { it.isNotBlank() }.takeLast(n).map { it.take(width) }
            }
        } catch (e: Exception) {
            listOf("(грешка при четене: ${e.message})")
        }

    /** Редове за следата на срива (пише ги само основният процес). Показват се първи в дневника. */
    @Synchronized
    fun crash(line: String) {
        try {
            val d = dir ?: return
            val f = File(d, CRASH)
            if (f.exists() && f.length() > 24_000) f.writeText(f.readText().takeLast(12_000))
            f.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    /**
     * Екранът в Настройки показва само първите ~60 реда, затова най-важното е най-горе:
     * 1) следата на последния срив (коя библиотека/функция), 2) краят на торент процеса, 3) краят на приложението.
     */
    @Synchronized
    fun text(maxLines: Int = 58): String {
        val d = dir ?: return "(празен)"
        val cr = lastLines(File(d, CRASH), 24)
        val tor = lastLines(File(d, TORRENT), 16)
        val room = (maxLines - cr.size - tor.size - 3).coerceAtLeast(10)
        val main = lastLines(File(d, MAIN), room)
        val sb = StringBuilder()
        if (cr.isNotEmpty()) sb.append("— следа на срива —\n").append(cr.joinToString("\n")).append('\n')
        if (tor.isNotEmpty()) sb.append("— торент процес —\n").append(tor.joinToString("\n")).append('\n')
        if (main.isNotEmpty()) sb.append("— приложение —\n").append(main.joinToString("\n"))
        return if (sb.isEmpty()) "(празен)" else sb.toString().trimEnd()
    }

    @Synchronized
    fun clear() {
        try {
            val d = dir ?: return
            File(d, MAIN).writeText("")
            File(d, TORRENT).delete()
            File(d, CRASH).delete()
        } catch (_: Exception) {
        }
    }
}
