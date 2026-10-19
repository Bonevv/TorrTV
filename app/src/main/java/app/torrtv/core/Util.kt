package app.torrtv.core

import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

// org.json връща "null" като низ за JSON null - тези помощници са безопасни.
fun JSONObject.str(name: String): String = if (isNull(name)) "" else optString(name, "")
fun JSONObject.intOr(name: String, def: Int = 0): Int = if (isNull(name)) def else optInt(name, def)
fun JSONObject.long(name: String, def: Long = 0L): Long = if (isNull(name)) def else optLong(name, def)
fun JSONObject.dbl(name: String, def: Double = 0.0): Double = if (isNull(name)) def else optDouble(name, def)

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return if (i >= 3) String.format(Locale.US, "%.2f %s", v, units[i])
    else String.format(Locale.US, "%.0f %s", v, units[i])
}

fun formatSpeed(bytesPerSec: Long): String = formatSize(bytesPerSec).replace("—", "0 B") + "/s"

fun pad2(n: Int): String = n.toString().padStart(2, '0')

/** "Свършва в 22:41": кога ще свърши филмът, ако се гледа без спиране оттук нататък. Празно, ако не се знае колко остава. */
fun endsAtText(nowMs: Long, leftMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
    if (leftMs <= 0) return ""
    val c = java.util.Calendar.getInstance(zone)
    c.timeInMillis = nowMs + leftMs
    return "Свършва в " + pad2(c.get(java.util.Calendar.HOUR_OF_DAY)) + ":" + pad2(c.get(java.util.Calendar.MINUTE))
}

/** Колко отдавна е станало нещо: "току-що", "преди 5 мин.", "преди 3 ч.", "вчера", "преди 4 дни". */
fun agoText(ms: Long): String {
    val m = (ms / 60_000L).coerceAtLeast(0L)
    return when {
        m < 1 -> "току-що"
        m < 60 -> "преди $m мин."
        m < 24 * 60 -> "преди ${m / 60} ч."
        else -> {
            val d = m / (24 * 60)
            if (d == 1L) "вчера" else "преди $d дни"
        }
    }
}

private val ACCENTS = Regex("\\p{Mn}+")
private val BTIH = Regex("xt=urn:btih:([A-Za-z0-9]+)")
private val regexMemo = java.util.concurrent.ConcurrentHashMap<String, Regex>()

fun stripAccents(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(ACCENTS, "")

/**
 * Регулярен израз, който зависи от сезон / епизод. Един и същ се ползва за всички резултати от търсенето,
 * вместо да се компилира наново за всеки от тях.
 */
fun cachedRegex(pattern: String): Regex {
    if (regexMemo.size > 80) regexMemo.clear()
    return regexMemo.getOrPut(pattern) { Regex(pattern) }
}

/** Нормализира домейн, въведен от потребителя (с или без https://). */
fun baseUrl(raw: String, default: String): String {
    var s = raw.trim()
    if (s.isEmpty()) s = default
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
    return s.trimEnd('/')
}

object Magnets {
    private val defaultTrackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://open.stealth.si:80/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://opentracker.i2p.rocks:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://p4p.arenabg.com:1337/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://open.tracker.cl:1337/announce"
    )

    /** Тракерите, които се добавят към линковете: стандартните или свои от услугата за настройки. */
    @Volatile
    var trackers: List<String> = defaultTrackers
        private set

    /** Свой списък (празният връща стандартните). */
    fun setCustom(list: List<String>) {
        trackers = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { defaultTrackers }
    }

    fun build(hash: String, name: String): String {
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(hash)
        if (name.isNotBlank()) sb.append("&dn=").append(enc(name))
        for (t in trackers) sb.append("&tr=").append(enc(t))
        return sb.toString()
    }

    /**
     * Тракери, които проверката в Диагностика е намерила за мъртви. Задава се при старт и след всяка проверка;
     * [ensureTrackers] ги маха от линка. Ако не отговарят всички, няма UDP връзка и не се изключва нищо.
     */
    @Volatile
    var dead: Set<String> = emptySet()
        private set

    fun setDead(urls: Set<String>) {
        dead = if (urls.size >= trackers.size) emptySet() else urls.map { norm(it) }.toSet()
    }

    private fun norm(u: String) = u.trim().trimEnd('/').lowercase(Locale.US)

    /**
     * Подготвя линка за торент двигателя: маха познатите мъртви тракери и добавя нашите, които още ги няма
     * (без повторения). Много линкове идват с един-два тракера, често умрели - така пиърите се намират по-бързо.
     */
    fun ensureTrackers(magnet: String): String {
        val q = magnet.indexOf('?')
        if (q < 0) return magnet
        val skip = dead
        val keep = ArrayList<String>()
        val have = HashSet<String>()
        for (p in magnet.substring(q + 1).split('&')) {
            if (p.isEmpty()) continue
            if (p.startsWith("tr=")) {
                val u = try {
                    java.net.URLDecoder.decode(p.substring(3), "UTF-8")
                } catch (e: Exception) {
                    p.substring(3)
                }
                val n = norm(u)
                if (n in skip || !have.add(n)) continue
            }
            keep.add(p)
        }
        for (t in trackers) {
            val n = norm(t)
            if (n !in skip && have.add(n)) keep.add("tr=" + enc(t))
        }
        return magnet.substring(0, q + 1) + keep.joinToString("&")
    }

    /** Връща info hash като 40 hex символа (малки букви) или null. */
    fun infoHash(magnet: String): String? {
        val m = BTIH.find(magnet) ?: return null
        val h = m.groupValues[1]
        return when (h.length) {
            40 -> h.lowercase(Locale.US)
            32 -> base32ToHex(h)
            else -> null
        }
    }

    private fun base32ToHex(s: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0
        var value = 0
        val out = StringBuilder()
        for (ch in s.uppercase(Locale.US)) {
            val idx = alphabet.indexOf(ch)
            if (idx < 0) return null
            value = (value shl 5) or idx
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.append(String.format(Locale.US, "%02x", (value shr bits) and 0xFF))
                value = value and ((1 shl bits) - 1)
            }
        }
        return if (out.length == 40) out.toString() else null
    }
}
