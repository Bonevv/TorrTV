package app.torrtv.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Разчита следата (tombstone), която Android оставя при native срив, и я превръща в няколко четими реда:
 * сигнал, адрес и най-вече стекът на нишката, която се е сринала (коя библиотека и функция).
 */
object CrashInfo {

    private class Pb(val b: ByteArray, var p: Int, val end: Int) {
        fun more() = p < end

        fun varint(): Long {
            var r = 0L
            var s = 0
            while (true) {
                if (p >= end || s > 63) throw IllegalArgumentException("varint")
                val x = b[p++].toInt()
                r = r or ((x and 0x7f).toLong() shl s)
                if (x and 0x80 == 0) break
                s += 7
            }
            return r
        }
    }

    private class Field(val no: Int, val wire: Int, val num: Long, val off: Int, val len: Int)

    private fun fields(b: ByteArray, start: Int, end: Int): List<Field> {
        val r = Pb(b, start, end)
        val out = ArrayList<Field>()
        while (r.more()) {
            val tag = r.varint()
            val no = (tag shr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> out.add(Field(no, 0, r.varint(), 0, 0))
                1 -> {
                    if (r.p + 8 > end) throw IllegalArgumentException("fixed64")
                    r.p += 8
                    out.add(Field(no, 1, 0, 0, 0))
                }
                2 -> {
                    val len = r.varint().toInt()
                    if (len < 0 || r.p + len > end) throw IllegalArgumentException("len")
                    out.add(Field(no, 2, 0, r.p, len))
                    r.p += len
                }
                5 -> {
                    if (r.p + 4 > end) throw IllegalArgumentException("fixed32")
                    r.p += 4
                    out.add(Field(no, 5, 0, 0, 0))
                }
                else -> throw IllegalArgumentException("wire")
            }
            if (no <= 0) throw IllegalArgumentException("field")
        }
        return out
    }

    private fun str(b: ByteArray, f: Field): String = String(b, f.off, f.len, Charsets.UTF_8)

    private fun hex(v: Long): String = "0x" + java.lang.Long.toHexString(v)

    /** Редове за дневника; при неразпознат формат - само намерените имена на библиотеки. */
    fun summarize(bytes: ByteArray): List<String> {
        try {
            val parsed = parseTombstone(bytes)
            if (parsed.isNotEmpty()) return parsed
        } catch (_: Exception) {
        }
        return scanStrings(bytes)
    }

    private fun parseTombstone(b: ByteArray): List<String> {
        val top = fields(b, 0, b.size)
        val lines = ArrayList<String>()
        val tid = top.firstOrNull { it.no == 6 && it.wire == 0 }?.num?.toInt() ?: -1

        // Сигналът: подсъобщение, чието поле 2 е низ от вида SIGSEGV.
        for (f in top) {
            if (f.wire != 2 || f.len < 4 || f.len > 400) continue
            val sub = try {
                fields(b, f.off, f.off + f.len)
            } catch (_: Exception) {
                continue
            }
            val name = sub.firstOrNull { it.no == 2 && it.wire == 2 }?.let { str(b, it) } ?: continue
            if (!Regex("SIG[A-Z0-9]{2,8}").matches(name)) continue
            val code = sub.firstOrNull { it.no == 4 && it.wire == 2 }?.let { str(b, it) } ?: ""
            val addr = sub.firstOrNull { it.no == 9 && it.wire == 0 }?.let { " адрес ${hex(it.num)}" } ?: ""
            lines.add("$name $code$addr".trim())
            break
        }
        top.firstOrNull { it.no == 14 && it.wire == 2 }?.let {
            val m = str(b, it).trim()
            if (m.isNotEmpty()) lines.add("съобщение: ${m.take(120)}")
        }

        // Нишките: map<uint32, Thread> е поле 16; всяка нишка има id (1), име (2) и кадри на стека (4).
        var shown = 0
        for (f in top) {
            if (f.no != 16 || f.wire != 2) continue
            val entry = fields(b, f.off, f.off + f.len)
            val tf = entry.firstOrNull { it.no == 2 && it.wire == 2 } ?: continue
            val th = fields(b, tf.off, tf.off + tf.len)
            val id = th.firstOrNull { it.no == 1 && it.wire == 0 }?.num?.toInt() ?: -1
            if (tid >= 0 && id != tid) continue
            val tname = th.firstOrNull { it.no == 2 && it.wire == 2 }?.let { str(b, it) } ?: ""
            lines.add("нишка $id $tname")
            var n = 0
            for (fr in th) {
                if (fr.no != 4 || fr.wire != 2) continue
                val x = fields(b, fr.off, fr.off + fr.len)
                val rel = x.firstOrNull { it.no == 1 && it.wire == 0 }?.num ?: 0L
                val fn = x.firstOrNull { it.no == 4 && it.wire == 2 }?.let { str(b, it) } ?: ""
                val off = x.firstOrNull { it.no == 5 && it.wire == 0 }?.num ?: 0L
                val file = x.firstOrNull { it.no == 6 && it.wire == 2 }?.let { str(b, it).substringAfterLast('/') } ?: ""
                lines.add("#$n $file ${if (fn.isNotEmpty()) "$fn+$off" else ""} @${hex(rel)}".replace("  ", " ").take(150))
                if (++n >= 14) break
            }
            shown++
            break
        }
        return if (shown == 0 && lines.isEmpty()) emptyList() else lines
    }

    private fun scanStrings(bytes: ByteArray): List<String> {
        val text = String(bytes, 0, minOf(bytes.size, 300_000), Charsets.ISO_8859_1)
        return Regex("[\\x20-\\x7e]{8,}").findAll(text).map { it.value }
            .filter { it.contains(".so") || it.contains("SIG") || it.contains("bort") }
            .distinct().take(16).map { it.take(150) }.toList()
    }

    /** Последният вече записан срив (време) - за да не се повтарят старите при всяко стартиране. */
    private fun lastSeen(ctx: Context): Long =
        try {
            java.io.File(ctx.filesDir, "exit.seen").readText().trim().toLong()
        } catch (_: Exception) {
            0L
        }

    private fun saveSeen(ctx: Context, ts: Long) {
        try {
            java.io.File(ctx.filesDir, "exit.seen").writeText(ts.toString())
        } catch (_: Exception) {
        }
    }

    /** Записва в дневника причината и стека за последните излезли процеси (само веднъж за всеки). */
    @Synchronized
    fun logRecent(ctx: Context, count: Int) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            val since = lastSeen(ctx)
            var newest = since
            // API връща най-новите първи; пишем по хронология, та последното в дневника да е най-новото.
            for (x in am.getHistoricalProcessExitReasons(ctx.packageName, 0, count).sortedBy { it.timestamp }) {
                if (x.timestamp <= since) continue
                if (x.timestamp > newest) newest = x.timestamp
                val why = when (x.reason) {
                    1 -> "EXIT_SELF"
                    2 -> "SIGNALED"
                    3 -> "LOW_MEMORY"
                    4 -> "CRASH"
                    5 -> "CRASH_NATIVE"
                    6 -> "ANR"
                    7 -> "INITIALIZATION_FAILURE"
                    8 -> "PERMISSION_CHANGE"
                    9 -> "EXCESSIVE_RESOURCE_USAGE"
                    10 -> "USER_REQUESTED"
                    11 -> "USER_STOPPED"
                    12 -> "DEPENDENCY_DIED"
                    13 -> "OTHER"
                    else -> "UNKNOWN(${x.reason})"
                }
                val name = x.processName.substringAfter(':', "основен")
                val head = "[$name] ${fmt.format(Date(x.timestamp))}: $why (status ${x.status}) ${x.description ?: ""}".trim()
                AppLog.d("app", head)
                // Стекът е най-важното нещо, което търсим; нормалните излизания и убиванията от нас не го пишат.
                if (x.reason == 5 || x.reason == 4 || x.reason == 3 || x.reason == 6 || x.reason == 9) {
                    AppLog.crash(head)
                    if (x.reason == 5) {
                        try {
                            val bytes = x.traceInputStream?.use { it.readBytes() }
                            if (bytes != null) summarize(bytes).forEach { AppLog.crash(it) }
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            if (newest > since) saveSeen(ctx, newest)
        } catch (_: Exception) {
        }
    }
}
