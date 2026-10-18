package app.torrtv.data.subs

import java.util.Locale

/** Мести всички времена в субтитрите напред (+) или назад (-). Работи за SRT, VTT и ASS/SSA. */
object SubShift {

    // 00:01:02,345  |  01:02.345  |  1:02:03.45
    private val stamp = Regex("(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{2})([,.])(\\d{1,3})")
    private val assTime = Regex("^(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})$")

    private fun fmt(totalMs: Long, sep: Char): String {
        val t = totalMs.coerceAtLeast(0)
        val h = t / 3_600_000
        val m = t / 60_000 % 60
        val s = t / 1000 % 60
        val ms = t % 1000
        return String.format(Locale.US, "%02d:%02d:%02d%c%03d", h, m, s, sep, ms)
    }

    private fun shiftSrtVtt(text: String, offsetMs: Long): String {
        val out = StringBuilder(text.length + 64)
        for (line in text.lineSequence()) {
            if (line.contains("-->")) {
                out.append(stamp.replace(line) { m ->
                    val h = m.groupValues[1].ifEmpty { "0" }.toLong()
                    val mi = m.groupValues[2].toLong()
                    val se = m.groupValues[3].toLong()
                    val frac = m.groupValues[5].padEnd(3, '0').take(3).toLong()
                    fmt(((h * 60 + mi) * 60 + se) * 1000 + frac + offsetMs, m.groupValues[4][0])
                })
            } else {
                out.append(line)
            }
            out.append('\n')
        }
        return out.toString().trimEnd('\n') + "\n"
    }

    private fun shiftAss(text: String, offsetMs: Long): String {
        val out = StringBuilder(text.length + 64)
        for (line in text.lineSequence()) {
            if (line.startsWith("Dialogue:") || line.startsWith("Comment:")) {
                val head = line.substringBefore(':') + ":"
                val rest = line.substring(head.length)
                val parts = rest.split(",", limit = 10).toMutableList()
                if (parts.size >= 10) {
                    for (i in 1..2) {
                        val m = assTime.find(parts[i].trim()) ?: continue
                        val ms = ((m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000 +
                            m.groupValues[4].toLong() * 10 + offsetMs
                        val t = ms.coerceAtLeast(0)
                        parts[i] = String.format(Locale.US, "%d:%02d:%02d.%02d", t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000 / 10)
                    }
                    out.append(head).append(parts.joinToString(","))
                } else out.append(line)
            } else out.append(line)
            out.append('\n')
        }
        return out.toString().trimEnd('\n') + "\n"
    }

    /** Кога свършва последният ред на субтитъра (мс). 0, ако не се намери нито един ред. */
    fun lastEndMs(text: String, ext: String): Long {
        var best = 0L
        if (ext.lowercase(Locale.US) in setOf("ass", "ssa")) {
            for (line in text.lineSequence()) {
                if (!line.startsWith("Dialogue:")) continue
                val parts = line.substring(9).split(",", limit = 10)
                if (parts.size < 10) continue
                val m = assTime.find(parts[2].trim()) ?: continue
                val ms = ((m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000 +
                    m.groupValues[4].toLong() * 10
                if (ms > best) best = ms
            }
            return best
        }
        for (line in text.lineSequence()) {
            if (!line.contains("-->")) continue
            val last = stamp.findAll(line).lastOrNull() ?: continue
            val h = last.groupValues[1].ifEmpty { "0" }.toLong()
            val mi = last.groupValues[2].toLong()
            val se = last.groupValues[3].toLong()
            val frac = last.groupValues[5].padEnd(3, '0').take(3).toLong()
            val ms = ((h * 60 + mi) * 60 + se) * 1000 + frac
            if (ms > best) best = ms
        }
        return best
    }

    /** ext: srt | vtt | ass | ssa. offsetMs > 0 - субтитрите излизат по-късно. */
    fun shift(text: String, ext: String, offsetMs: Long): String {
        if (offsetMs == 0L) return text
        return when (ext.lowercase(Locale.US)) {
            "ass", "ssa" -> shiftAss(text, offsetMs)
            else -> shiftSrtVtt(text, offsetMs)
        }
    }
}
