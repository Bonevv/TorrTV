package app.torrtv.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Малък файлов дневник. Остава и ако процесът бъде убит, така че след срив се вижда докъде е стигнало.
 * Показва се от Настройки → Дневник.
 */
object AppLog {
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private const val MAX = 120_000L

    @Synchronized
    fun init(ctx: Context) {
        try {
            val f = File(ctx.filesDir, "torrtv.log")
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

    @Synchronized
    fun text(maxChars: Int = 6000): String =
        try {
            val f = file
            if (f == null || !f.exists()) "(празен)" else f.readText().takeLast(maxChars)
        } catch (e: Exception) {
            "(грешка при четене: ${e.message})"
        }

    @Synchronized
    fun clear() {
        try {
            file?.writeText("")
        } catch (_: Exception) {
        }
    }
}
