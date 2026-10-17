package app.torrtv.data.subs

import app.torrtv.core.Http
import app.torrtv.core.Prefs
import app.torrtv.data.MediaType
import app.torrtv.data.torrents.Relevance
import com.github.junrar.Junrar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
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
    val hearing: Boolean
) {
    val uid: String get() = "$provider|$ref"
}

class SubFile(val name: String, val bytes: ByteArray)

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
            val re = Regex("(?i)s0*$season ?e0*$episode(?!\\d)|\\b${season}x0*$episode(?!\\d)|[ ._-]0*$episode[ ._-]")
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

    class Local(val file: File, val lang: String, val label: String, val mime: String)

    private val dir: File get() = File(cacheDir, "subs").also { it.mkdirs() }

    suspend fun fetch(r: SubtitleResult, q: SubtitleQuery): Local? = withContext(Dispatchers.IO) {
        val provider = providers.firstOrNull { it.id == r.provider } ?: return@withContext null
        val files = provider.download(r, q)
        val f = Archives.pick(files, q.season, q.episode) ?: return@withContext null
        val text = SubText.decode(f.bytes, r.lang)
        val ext = f.name.substringAfterLast('.', "srt").lowercase(Locale.US).let {
            if (it in setOf("ass", "ssa", "vtt", "srt")) it else "srt"
        }
        val out = File(dir, "${(r.uid + q.season + q.episode).hashCode().toUInt().toString(16)}.$ext")
        out.writeText(text, Charsets.UTF_8)
        val mime = when (ext) {
            "ass", "ssa" -> "text/x-ssa"
            "vtt" -> "text/vtt"
            else -> "application/x-subrip"
        }
        Local(out, r.lang, "${Langs.short(r.lang)} · ${r.providerName}", mime)
    }

    fun clear() {
        dir.deleteRecursively()
    }
}

/** Подрежда субтитрите: езици по ред на предпочитание, после съвпадение с релийза, после доверени и сваляния. */
object SubRank {
    private fun tokens(s: String): Set<String> =
        Relevance.norm(s).split(' ').filter { it.length >= 3 }.toSet()

    fun rank(all: List<SubtitleResult>, langOrder: List<String>, releaseHint: String?): List<SubtitleResult> {
        val hint = if (releaseHint == null) emptySet() else tokens(releaseHint)
        fun score(r: SubtitleResult): Int {
            val match = if (hint.isEmpty()) 0 else tokens(r.release).count { it in hint }
            return match * 50 + (if (r.trusted) 20 else 0) + minOf(r.downloads, 5000) / 100 - (if (r.hearing) 10 else 0)
        }
        return all.sortedWith(
            compareBy<SubtitleResult> {
                val i = langOrder.indexOf(it.lang)
                if (i < 0) 99 else i
            }.thenByDescending { score(it) }
        )
    }
}
