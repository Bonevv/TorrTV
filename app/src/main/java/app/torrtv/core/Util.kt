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

fun stripAccents(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

/** Нормализира домейн, въведен от потребителя (с или без https://). */
fun baseUrl(raw: String, default: String): String {
    var s = raw.trim()
    if (s.isEmpty()) s = default
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
    return s.trimEnd('/')
}

object Magnets {
    val trackers = listOf(
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

    fun build(hash: String, name: String): String {
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(hash)
        if (name.isNotBlank()) sb.append("&dn=").append(enc(name))
        for (t in trackers) sb.append("&tr=").append(enc(t))
        return sb.toString()
    }

    /** Добавя стандартни тракери, ако magnet линкът няма такива. */
    fun ensureTrackers(magnet: String): String {
        if (magnet.contains("&tr=") || magnet.contains("?tr=")) return magnet
        val sb = StringBuilder(magnet)
        for (t in trackers) sb.append("&tr=").append(enc(t))
        return sb.toString()
    }

    /** Връща info hash като 40 hex символа (малки букви) или null. */
    fun infoHash(magnet: String): String? {
        val m = Regex("xt=urn:btih:([A-Za-z0-9]+)").find(magnet) ?: return null
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
