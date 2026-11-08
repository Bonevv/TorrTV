package app.torrtv.web

import java.net.URI

/** Catalogue pages are discovery inputs. They are never a visible playback destination. */
object BgPlayerPresentation {
    private val catalogues = setOf("filmi9.com", "vsichkifilmi.net", "filmi2k.com", "zenoplay.to", "filmifen.com", "filmizt.com", "filmizip.com")

    fun catalogue(url: String): Boolean = try {
        val u = URI(url)
        u.host?.lowercase(java.util.Locale.ROOT)?.removePrefix("www.") in catalogues &&
            !Regex("^/(?:embed|player|e)(?:/|\\.|$)", RegexOption.IGNORE_CASE).containsMatchIn(u.path.orEmpty())
    } catch (_: Exception) { true }

    fun source(host: String, page: String, embed: String, bgAudio: Boolean = true): WebSources.Source {
        val playable = WebSubtitleProvider.publicUrl(embed) && !catalogue(embed) && !AdBlock.isBlocked(URI(embed).host)
        val direct = playable && Regex("\\.(?:m3u8|mpd|mp4)(?:[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(embed)
        return WebSources.Source(host, if (playable && !direct) embed else "", bgAudio = bgAudio, direct = false,
            headers = mapOf("Referer" to page.substringBefore('#')), playerOnly = true,
            discoveryPage = if (playable && !direct) "" else page, originalPlayer = true)
    }

    fun alternatives(sources: List<WebSources.Source>, selected: WebSources.Source): List<WebSources.Source> =
        sources.filter { !it.direct && (!selected.playerOnly || it.playerOnly && (!selected.bgAudio || it.bgAudio)) }

    /** A minimal frame preserves the movie Referer required by some players, without any site layout. */
    fun shell(url: String): String {
        require(WebSubtitleProvider.publicUrl(url) && !catalogue(url))
        val src = url.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        return """<!doctype html><html data-torrtv-player-shell><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body,iframe{margin:0;border:0;width:100%;height:100%;overflow:hidden;background:#000}iframe{position:fixed;inset:0}</style></head><body><iframe src="$src" allow="autoplay; fullscreen; encrypted-media; picture-in-picture" allowfullscreen referrerpolicy="unsafe-url"></iframe></body></html>"""
    }
}
