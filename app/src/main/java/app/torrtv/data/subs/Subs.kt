package app.torrtv.data.subs

import app.torrtv.core.Http
import app.torrtv.core.Prefs
import app.torrtv.data.MediaType
import app.torrtv.data.torrents.Relevance
import com.github.junrar.Junrar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class SubtitleQuery(
    val type: MediaType,
    val title: String,
    val altTitles: List<String>,
    val year: String,
    val imdbId: String?,
    val tmdbId: Int,
    val season: Int?,
    val episode: Int?,
    val langs: List<String>
) {
    val cacheKey: String get() = "${type.path}|$tmdbId|$season|$episode|${langs.joinToString(",")}"
}

data class SubtitleResult(
    val provider: String,      // id на доставчика
    val providerName: String,
    val lang: String,          // bg, en ...
    val release: String,
    val ref: String,
    val downloads: Int,
    val trusted: Boolean,
    val hearing: Boolean,
    val fileName: String = ""
) {
    val uid: String get() = "$provider|$ref"

    /** Разчетеното издание (кешира се - сметка по име се прави веднъж). */
    val fx: Release by lazy { Release.parse(release) }

    val names: List<String> by lazy { listOf(release, fileName).map { it.trim() }.filter { it.isNotEmpty() }.distinct() }

    /** Отделните имена на издания (един ред - едно издание), разчетени веднъж. */
    val parsed: List<Release> by lazy { lines.map { Release.parse(it) } }
    private val lines: List<String> by lazy {
        names.flatMap { it.lines() }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** Име на един ред - най-пълното от наличните (не се съкращава: показва се цялото). */
    val displayName: String by lazy {
        val best = lines.indices.maxByOrNull { parsed[it].facets }?.let { lines[it] }
        best ?: "(без име на издание)"
    }
}

class SubFile(val name: String, val bytes: ByteArray)

/** Кратко и разбираемо съобщение за неуспех при субтитри (вместо суров HTTP текст). */
fun subErrorText(e: Throwable): String {
    if (e is Http.HttpError) return when (e.code) {
        406, 429 -> "дневният лимит е свършил, пробвай утре или друг източник"
        401, 403 -> "грешен ключ или парола"
        404, 410 -> "файлът вече го няма"
        in 500..599 -> "сайтът не работи в момента"
        else -> "грешка ${e.code}"
    }
    if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException) return "няма връзка със сайта"
    val m = e.message.orEmpty().replace(Regex("^(OpenSubtitles|Subdl|SubSource|Subs\\.sab\\.bz): "), "")
    return if (m.isBlank()) e.javaClass.simpleName else m.take(70)
}

interface SubtitleProvider {
    val id: String
    val name: String
    fun configured(): Boolean
    suspend fun search(q: SubtitleQuery): List<SubtitleResult>
    suspend fun download(r: SubtitleResult, q: SubtitleQuery): List<SubFile>
}

object Langs {
    /** Езиците, които могат да се включат от Настройки. */
    val choices: List<Pair<String, String>> = listOf(
        "bg" to "Български", "en" to "English", "ru" to "Русский", "tr" to "Türkçe",
        "el" to "Ελληνικά", "ro" to "Română", "sr" to "Srpski", "de" to "Deutsch"
    )

    private val byName = mapOf(
        "bulgarian" to "bg", "english" to "en", "russian" to "ru", "turkish" to "tr",
        "greek" to "el", "romanian" to "ro", "serbian" to "sr", "german" to "de"
    )

    val englishNames = mapOf(
        "bg" to "bulgarian", "en" to "english", "ru" to "russian", "tr" to "turkish",
        "el" to "greek", "ro" to "romanian", "sr" to "serbian", "de" to "german"
    )

    fun norm(raw: String): String {
        val s = raw.trim().lowercase(Locale.US)
        byName[s]?.let { return it }
        return s.substringBefore('-').substringBefore('_').take(2)
    }

    fun short(code: String): String = code.uppercase(Locale.US)
    fun label(code: String): String = choices.firstOrNull { it.first == code }?.second ?: code.uppercase(Locale.US)
}

object Archives {
    private val subExt = setOf("srt", "ass", "ssa", "vtt")

    fun extract(bytes: ByteArray, tmpDir: File): List<SubFile> {
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return unzip(bytes)
        if (bytes.size > 7 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'a'.code.toByte() &&
            bytes[2] == 'r'.code.toByte()
        ) return unrar(bytes, tmpDir)
        return listOf(SubFile("sub.srt", bytes))
    }

    private fun unzip(bytes: ByteArray): List<SubFile> {
        val out = ArrayList<SubFile>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val ext = e.name.substringAfterLast('.', "").lowercase(Locale.US)
                if (ext !in subExt) continue
                val data = z.readBytes()
                if (data.size in 1..(6 * 1024 * 1024)) out.add(SubFile(e.name.substringAfterLast('/'), data))
            }
        }
        return out
    }

    private fun unrar(bytes: ByteArray, tmp: File): List<SubFile> {
        tmp.mkdirs()
        val stamp = System.nanoTime()
        val rar = File(tmp, "x$stamp.rar")
        val dest = File(tmp, "x${stamp}_out")
        val out = ArrayList<SubFile>()
        try {
            rar.writeBytes(bytes)
            dest.mkdirs()
            Junrar.extract(rar, dest)
            dest.walkTopDown().filter { it.isFile }.forEach { f ->
                val ext = f.extension.lowercase(Locale.US)
                if (ext in subExt && f.length() in 1..(6L * 1024 * 1024)) out.add(SubFile(f.name, f.readBytes()))
            }
        } finally {
            rar.delete()
            dest.deleteRecursively()
        }
        return out
    }

    fun pick(files: List<SubFile>, season: Int?, episode: Int?): SubFile? {
        if (files.isEmpty()) return null
        if (files.size == 1) return files[0]
        if (season != null && episode != null) {
            val re = app.torrtv.core.cachedRegex("(?i)s0*$season ?e0*$episode(?!\\d)|\\b${season}x0*$episode(?!\\d)|[ ._-]0*$episode[ ._-]")
            files.firstOrNull { re.containsMatchIn(it.name) }?.let { return it }
        }
        return files.maxByOrNull { it.bytes.size }
    }
}

object SubText {
    /** Субтитрите често са в windows-1251 - превръщаме всичко в UTF-8 текст. */
    fun decode(bytes: ByteArray, lang: String): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        try {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return dec.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
        }
        val cs = when (lang) {
            "bg", "ru", "sr", "mk", "uk" -> "windows-1251"
            "el" -> "windows-1253"
            "tr" -> "windows-1254"
            "ro", "pl", "cs", "hu" -> "windows-1250"
            else -> "windows-1252"
        }
        return String(bytes, Charset.forName(cs))
    }
}

class SubtitleStore(private val cacheDir: File, private val providers: List<SubtitleProvider>) {

    class Local(val file: File, val lang: String, val label: String, val mime: String, val uid: String = "")

    private val dir: File get() = File(cacheDir, "subs").also { it.mkdirs() }
    private val lock = Mutex()
    private val extensions = listOf("srt", "ass", "ssa", "vtt")

    suspend fun fetch(r: SubtitleResult, q: SubtitleQuery): Local? = withContext(Dispatchers.IO) {
        // Един и същ файл не се сваля два пъти и при едновременни заявки.
        lock.withLock {
            val key = listOf(r.uid, r.lang, q.type.path, q.tmdbId, q.season, q.episode).joinToString("|")
            val stem = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val cached = extensions.map { File(dir, "$stem.$it") }.firstOrNull { it.isFile && it.length() > 0 }
            if (cached != null) {
                cached.setLastModified(System.currentTimeMillis())
                return@withContext local(cached, r)
            }
            val provider = providers.firstOrNull { it.id == r.provider } ?: return@withContext null
            val files = provider.download(r, q)
            val f = Archives.pick(files, q.season, q.episode) ?: return@withContext null
            val text = SubText.decode(f.bytes, r.lang)
            val ext = f.name.substringAfterLast('.', "srt").lowercase(Locale.US).let {
                if (it in setOf("ass", "ssa", "vtt", "srt")) it else "srt"
            }
            if (text.isBlank()) return@withContext null
            val out = File(dir, "$stem.$ext")
            val tmp = File.createTempFile(stem, ".part", dir)
            try {
                tmp.writeText(text, Charsets.UTF_8)
                check(tmp.renameTo(out)) { "Неуспешен запис на субтитрите" }
            } finally {
                tmp.delete()
            }
            local(out, r)
        }
    }

    private fun local(file: File, r: SubtitleResult): Local {
        val mime = when (file.extension) {
            "ass", "ssa" -> "text/x-ssa"
            "vtt" -> "text/vtt"
            else -> "application/x-subrip"
        }
        // Името на изданието е в етикета, за да се вижда в менюто на плеъра за кой филм е всеки субтитър.
        return Local(file, r.lang, "${Langs.short(r.lang)} · ${r.displayName}", mime, r.uid)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock { dir.deleteRecursively() }
    }

    /** Само когато няма активен поток; запазва най-скоро използваните файлове. */
    suspend fun trimToSize(maxBytes: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            val files = dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
            var size = files.sumOf { it.length() }
            for (f in files) {
                if (size <= maxBytes) break
                val bytes = f.length()
                if (f.delete()) size -= bytes
            }
        }
    }
}

/** Колко добре един субтитър пасва на избрания торент (по издание: източник, резолюция, група). */
enum class Fit { EXACT, SIMILAR, OTHER, UNKNOWN }

/** Отличителните части от името на изданието - те определят дали времената на субтитъра ще паснат на звука. */
class Release(val res: String, val src: String, val codec: String, val groups: Set<String>, val editions: Set<String> = emptySet()) {
    val facets: Int get() = listOf(res, src, codec).count { it.isNotEmpty() } + (if (groups.isNotEmpty()) 1 else 0)

    companion object {
        private val knownGroups = setOf(
            "yts", "yify", "rarbg", "ntb", "flux", "evo", "ion10", "galaxy", "galaxytv", "psa", "sparks", "geckos",
            "amiable", "cmrg", "fgt", "ethel", "playweb", "tgx", "mkvcinema", "tigole", "qxr", "joy", "pahe", "bone",
            "dimension", "lol", "mixed", "avs", "kogi", "ntg", "btn", "cakes", "strife", "tepes", "edith", "smurf",
            "hone", "ctrlhd", "decibel", "sigma", "framestor", "etrg", "megusta", "eztv", "ettv",
            "ion", "shortbrehd", "rbg", "ganool", "tomdoc", "kingdom", "vxt", "swty"
        )
        private val notGroup = setOf(
            "x264", "x265", "h264", "h265", "hevc", "avc", "aac", "ac3", "dts", "mp4", "mkv", "avi", "srt", "sub", "subs",
            "bg", "en", "eng", "bul", "bluray", "webrip", "webdl", "web", "hdtv", "dvdrip", "bdrip", "brrip", "remux",
            "dl", "rip", "ray", "hdr", "dv", "atmos", "1080p", "720p", "2160p", "480p", "hdrip", "proper", "repack", "extended", "unrated", "multi", "dual",
            "amzn", "nf", "dsnp", "atvp", "hmax"
        )
        private val tail = Regex("-\\s*\\[?([A-Za-z0-9]{2,12})\\]?\\s*(?:\\.[A-Za-z0-9]{2,4})?\\s*$")

        fun parse(name: String): Release {
            val n = app.torrtv.data.torrents.Relevance.norm(name)
            val tokens = n.split(' ').filter { it.isNotEmpty() }
            val tk = tokens.toSet()
            val res = when {
                "2160p" in tk || "4k" in tk || "uhd" in tk -> "2160p"
                "1080p" in tk || "1080i" in tk -> "1080p"
                "720p" in tk -> "720p"
                "480p" in tk -> "480p"
                else -> ""
            }
            val src = when {
                "cam" in tk || "hdcam" in tk || "camrip" in tk || "telesync" in tk || "hdts" in tk || "ts" in tk ||
                    "telecine" in tk || "tc" in tk -> "cam"
                "bluray" in tk || ("blu" in tk && "ray" in tk) || "bdrip" in tk || "brrip" in tk || "remux" in tk -> "bd"
                "web" in tk || "webrip" in tk || "webdl" in tk || "hdrip" in tk -> "web"
                "hdtv" in tk || "pdtv" in tk || "tvrip" in tk -> "tv"
                "dvdrip" in tk || "dvd" in tk || "dvdscr" in tk -> "dvd"
                else -> ""
            }
            val codec = when {
                "x265" in tk || "hevc" in tk || "h265" in tk -> "hevc"
                "x264" in tk || "h264" in tk || "avc" in tk -> "avc"
                "av1" in tk -> "av1"
                else -> ""
            }
            val groups = HashSet<String>()
            // Думи като Joy и Dimension може да са заглавието, а не групата на изданието.
            val technical = setOf("2160p", "1080p", "1080i", "720p", "480p", "4k", "uhd", "web", "webdl",
                "webrip", "hdtv", "bluray", "blu", "bdrip", "brrip", "dvdrip", "x264", "x265", "h264", "h265", "hevc", "avc", "av1")
            val releaseAt = tokens.indexOfFirst { it in technical }
            if (releaseAt >= 0) tokens.drop(releaseAt).filterTo(groups) { it in knownGroups }
            tail.find(name.trim())?.groupValues?.get(1)?.lowercase(Locale.US)?.let {
                if (it !in notGroup && !it.all { c -> c.isDigit() }) groups.add(it)
            }
            val editions = tk.intersect(setOf("extended", "unrated", "uncut", "theatrical", "imax")) +
                if (Regex("\\bdirector ?s? cut\\b").containsMatchIn(n)) setOf("directors-cut") else emptySet()
            return Release(res, src, codec, groups, editions)
        }
    }
}

/** Какъв субтитър е избрал зрителят за това заглавие: език, доставчик и групи на изданието. */
class SubPref(val lang: String, val provider: String, val groups: Set<String>)

/** Подрежда субтитрите: езици по ред на предпочитание, после съвпадение с изданието, после доверени и сваляния. */
object SubRank {

    fun fit(sub: Release, tor: Release): Fit {
        if (sub.facets == 0 || tor.facets == 0) return Fit.UNKNOWN
        val groupMatch = sub.groups.any { it in tor.groups }
        val srcSame = sub.src.isNotEmpty() && sub.src == tor.src
        val srcDiff = sub.src.isNotEmpty() && tor.src.isNotEmpty() && sub.src != tor.src
        if (srcDiff) return Fit.OTHER
        // Субтитър, който сам казва, че е за друга версия, е друго издание. Субтитър без дума за версия
        // не е доказано различен, но и не е сигурен: най-много "подобни".
        val editionsDiffer = sub.editions != tor.editions
        if (editionsDiffer && sub.editions.isNotEmpty()) return Fit.OTHER
        val r = when {
            groupMatch && srcSame -> Fit.EXACT
            groupMatch || srcSame -> Fit.SIMILAR
            else -> Fit.UNKNOWN
        }
        return if (editionsDiffer && r == Fit.EXACT) Fit.SIMILAR else r
    }

    private fun bestRelease(sub: SubtitleResult, tor: Release): Release =
        sub.parsed.minByOrNull { fit(it, tor).ordinal } ?: sub.fx

    fun fit(sub: SubtitleResult, torrentTitle: String?): Fit =
        if (torrentTitle == null) Fit.UNKNOWN else Release.parse(torrentTitle).let { fit(bestRelease(sub, it), it) }

    fun reason(sub: SubtitleResult, torrentTitle: String?): String {
        val base = reasonBase(sub, torrentTitle)
        if (torrentTitle == null) return base
        val tor = Release.parse(torrentTitle)
        val rel = bestRelease(sub, tor)
        return if (rel.editions != tor.editions && rel.editions.isEmpty() && fit(rel, tor) != Fit.OTHER)
            "$base Видеото е за специална версия (extended / режисьорска), а в името на субтитрите я няма."
        else base
    }

    private fun reasonBase(sub: SubtitleResult, torrentTitle: String?): String {
        if (torrentTitle == null) return "Няма данни за изданието на видеото."
        val tor = Release.parse(torrentTitle)
        val rel = bestRelease(sub, tor)
        val shared = rel.groups.intersect(tor.groups).sorted().joinToString(", ")
        return when (fit(rel, tor)) {
            Fit.EXACT -> "Същата група ($shared) и източник (${sourceName(rel.src)}). Синхронът не е гарантиран."
            Fit.SIMILAR -> if (shared.isNotEmpty()) "Същата група ($shared), но липсват данни за източника."
                else "Същият източник (${sourceName(rel.src)}); няма потвърдена обща група."
            Fit.OTHER -> if (rel.editions != tor.editions) "Субтитрите са за друга версия (extended / режисьорска) от видеото."
                else "Различни източници: ${sourceName(rel.src)} / ${sourceName(tor.src)}."
            Fit.UNKNOWN -> "Недостатъчно данни за сравнение. Еднаква резолюция или кодек не доказват синхрон."
        }
    }

    private fun sourceName(src: String): String = when (src) {
        "web" -> "WEB-DL / WEBRip"
        "bd" -> "BluRay"
        "tv" -> "TV"
        "dvd" -> "DVD"
        "cam" -> "CAM / TS"
        else -> "неизвестен"
    }

    /** Най-доброто съвпадение измежду субтитрите (за белега на торента в списъка). */
    fun bestFit(subs: List<SubtitleResult>, torrentTitle: String): Fit {
        if (subs.isEmpty()) return Fit.UNKNOWN
        val tor = Release.parse(torrentTitle)
        var best = Fit.UNKNOWN
        var any = false
        for (r in subs) {
            val f = fit(bestRelease(r, tor), tor)
            if (f == Fit.EXACT) return Fit.EXACT
            if (f == Fit.UNKNOWN) continue
            any = true
            if (best == Fit.UNKNOWN || (f == Fit.SIMILAR && best == Fit.OTHER)) best = f
        }
        return if (any) best else Fit.UNKNOWN
    }

    /**
     * [prefer] - предишният ръчен избор за това заглавие: неговият език се слага пръв, а субтитри от същия
     * доставчик и същата група получават малък бонус (по-малък от точното съвпадение с изданието).
     */
    fun rank(all: List<SubtitleResult>, langOrder: List<String>, releaseHint: String?, prefer: SubPref? = null): List<SubtitleResult> {
        val tor = releaseHint?.let { Release.parse(it) }
        val order = if (prefer != null && prefer.lang in langOrder) listOf(prefer.lang) + (langOrder - prefer.lang) else langOrder
        fun score(r: SubtitleResult): Int {
            val f = if (tor == null) Fit.UNKNOWN else fit(bestRelease(r, tor), tor)
            val fitScore = when (f) {
                Fit.EXACT -> 300
                Fit.SIMILAR -> 120
                Fit.UNKNOWN -> 0
                Fit.OTHER -> -60
            }
            var bonus = 0
            if (prefer != null) {
                if (r.lang == prefer.lang && r.provider == prefer.provider) bonus += 40
                if (r.fx.groups.any { it in prefer.groups }) bonus += 40
            }
            return fitScore + bonus + (if (r.trusted) 20 else 0) + minOf(r.downloads, 5000) / 100 - (if (r.hearing) 10 else 0)
        }
        return all.sortedWith(
            compareBy<SubtitleResult> {
                val i = order.indexOf(it.lang)
                if (i < 0) 99 else i
            }.thenByDescending { score(it) }
        )
    }
}
