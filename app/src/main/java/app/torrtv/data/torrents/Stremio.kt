package app.torrtv.data.torrents

import app.torrtv.core.Http
import app.torrtv.core.Magnets
import app.torrtv.core.Prefs
import app.torrtv.core.enc
import app.torrtv.core.str
import app.torrtv.data.MediaType
import org.json.JSONObject
import java.util.Locale

/**
 * Чисти текстови помощници за Stremio addon-ите (Torrentio, TorrentsDB и подобни).
 * Такъв addon отговаря на  {адрес}/stream/movie/{imdb}.json  и  {адрес}/stream/series/{imdb}:{сезон}:{епизод}.json
 * със списък "streams"; всеки има infoHash и поле "title" от няколко реда, например:
 *   Името на торента
 *   👤 253 💾 18.45 GB ⚙️ knaben
 */
object StremioText {
    class Info(val name: String, val seeders: Int, val size: Long, val provider: String)

    private val seedsRe = Regex("👤\\s*(\\d+)")
    private val sizeRe = Regex("💾\\s*([0-9][0-9.,]*\\s*[KMGT]?i?B)", RegexOption.IGNORE_CASE)
    private val providerRe = Regex("⚙️?\\s*(\\S+)")

    fun parse(title: String): Info {
        val lines = title.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val statIdx = lines.indexOfFirst { it.contains("👤") || it.contains("💾") || it.contains("⚙") }
        val name = (if (statIdx >= 0) lines.take(statIdx) else lines).firstOrNull().orEmpty()
        val stat = if (statIdx >= 0) lines[statIdx] else ""
        val seeders = seedsRe.find(stat)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val size = sizeRe.find(stat)?.groupValues?.get(1)?.let { parseSize(it) } ?: 0L
        val provider = providerRe.find(stat)?.groupValues?.get(1).orEmpty()
        return Info(name, seeders, size, provider)
    }

    private val known = mapOf(
        "thepiratebay" to "The Pirate Bay", "piratebay" to "The Pirate Bay", "tpb" to "The Pirate Bay",
        "yts" to "YTS", "eztv" to "EZTV", "nyaasi" to "Nyaa (аниме)", "nyaa" to "Nyaa (аниме)",
        "knaben" to "Knaben", "torrentscsv" to "Torrents-CSV", "torrents-csv" to "Torrents-CSV",
        "1337x" to "1337x", "torrentgalaxy" to "TorrentGalaxy", "kickasstorrents" to "KickassTorrents",
        "magnetdl" to "MagnetDL", "limetorrent" to "LimeTorrents", "limetorrents" to "LimeTorrents",
        "rarbg" to "RARBG", "horriblesubs" to "HorribleSubs", "tokyotosho" to "TokyoTosho", "anidex" to "AniDex",
        "rutor" to "Rutor", "rutracker" to "RuTracker", "comando" to "Comando", "bludv" to "BluDV",
        "torrent9" to "Torrent9", "ilcorsaronero" to "ilCorSaRoNeRo", "mejortorrent" to "MejorTorrent",
        "wolfmax4k" to "Wolfmax4K", "cinecalidad" to "Cinecalidad", "1lou" to "1LOU", "solidtorrents" to "SolidTorrents",
        "bitsearch" to "BitSearch", "torlock" to "Torlock", "zooqle" to "Zooqle"
    )

    /** Името на сайта както го показваме (и както се казват нашите собствени източници, за да се събират в един филтър). */
    fun site(provider: String): String {
        val p = provider.trim()
        if (p.isEmpty()) return ""
        known[p.lowercase(Locale.US)]?.let { return it }
        return if (p == p.lowercase(Locale.US)) p.replaceFirstChar { it.uppercase() } else p
    }

    private val hashRe = Regex("[0-9a-f]{40}")

    /**
     * Прави резултат от един stream. titleField/nameField/filename са текстовете от JSON-а;
     * extraTrackers са адресите от "sources" ("tracker:udp://…"). Връща null, ако потокът не е торент.
     */
    fun result(
        q: SearchQuery, addon: String, infoHash: String, titleField: String, nameField: String,
        filename: String, extraTrackers: List<String>
    ): TorrentResult? {
        val hash = infoHash.trim().lowercase(Locale.US)
        if (!hashRe.matches(hash)) return null
        val info = parse(titleField)
        val title = info.name.ifBlank { filename }
        if (title.isBlank()) return null
        val site = site(info.provider).ifBlank { addon }
        val magnet = StringBuilder(Magnets.build(hash, title))
        for (t in extraTrackers.take(10)) {
            val u = t.trim()
            if (u.isNotEmpty() && !Magnets.trackers.contains(u)) magnet.append("&tr=").append(enc(u))
        }
        var r = Quality.make(q, title, hash, magnet.toString(), info.seeders, 0, info.size, site)
        if (r.quality == "SD") {
            // Името на торента не казва качеството - вторият ред на "name" често го казва ("2160p", "4k HDR").
            val ql = Quality.of(nameField.substringAfter('\n', ""))
            if (ql != "SD") r = r.copy(quality = ql)
        }
        // Addon-ът вече е избрал правилния епизод; ако името не го казва (напр. чуждо заглавие), това е пакет.
        if (q.type == MediaType.TV && Relevance.tvMatch(q, title) != 1) r = r.copy(pack = true)
        return r.copy(via = addon)
    }
}

/** Всеки Stremio addon, който дава торенти (infoHash). Работи само с IMDb номер. */
class StremioAddon(override val id: String, override val name: String, private val baseFn: () -> String) : TorrentSource {
    constructor(id: String, name: String, base: String) : this(id, name, { base })

    /** Адресът се чете при всяко търсене: услугата може да го смени, без да се рестартира приложението. */
    private val base: String
        get() {
            val raw = baseFn()
            return normalizeBase(raw) ?: raw.trim().trimEnd('/')
        }

    override fun supports(q: SearchQuery): Boolean {
        if (q.imdbId.isNullOrBlank()) return false
        return q.type == MediaType.MOVIE || (q.season != null && q.episode != null)
    }

    override suspend fun search(q: SearchQuery): List<TorrentResult> {
        val imdb = q.imdbId ?: return emptyList()
        val path = if (q.type == MediaType.MOVIE) "movie/$imdb" else "series/$imdb:${q.season}:${q.episode}"
        val arr = JSONObject(Http.get("$base/stream/$path.json")).optJSONArray("streams") ?: return emptyList()
        val out = ArrayList<TorrentResult>()
        for (i in 0 until minOf(arr.length(), 150)) {
            val o = arr.optJSONObject(i) ?: continue
            val trackers = ArrayList<String>()
            o.optJSONArray("sources")?.let { s ->
                for (k in 0 until s.length()) {
                    val v = s.optString(k, "")
                    if (v.startsWith("tracker:")) trackers.add(v.removePrefix("tracker:"))
                }
            }
            val r = StremioText.result(
                q, name, o.str("infoHash"), o.str("title"), o.str("name"),
                o.optJSONObject("behaviorHints")?.str("filename").orEmpty(), trackers
            ) ?: continue
            out.add(r)
        }
        return out
    }

    companion object {
        /** Адресът на addon-а без "/manifest.json" и без наклонена черта накрая; null, ако не прилича на адрес. */
        fun normalizeBase(raw: String): String? {
            var s = raw.trim()
            if (s.startsWith("stremio://", true)) s = "https://" + s.substring(10)
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) return null
            s = s.substringBefore('#').substringBefore('?')
            if (s.endsWith("/manifest.json", true)) s = s.dropLast(14)
            s = s.trimEnd('/')
            return s.takeIf { it.length > 9 }
        }

        /** Един адрес на ред; празните, коментарите (#) и невалидните се прескачат. */
        fun parseList(text: String): List<String> =
            text.lines().map { it.trim() }.filter { !it.startsWith("#") }
                .mapNotNull { normalizeBase(it) }.distinct()

        fun hostOf(base: String): String =
            base.substringAfter("://").substringBefore('/').removePrefix("www.")

        /** Свои addon-и от настройките (порт 62000). Вградените TorrentsDB и Torrentio не се дублират. */
        fun custom(prefs: Prefs, builtInHosts: Set<String>): List<StremioAddon> =
            parseList(prefs.addonsSrc).filter { hostOf(it) !in builtInHosts }.map {
                val h = hostOf(it)
                StremioAddon("addon_" + h, h, it)
            }
    }
}
