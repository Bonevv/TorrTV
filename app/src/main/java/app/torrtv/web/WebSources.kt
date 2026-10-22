package app.torrtv.web

import android.net.Uri
import app.torrtv.core.Prefs

/**
 * Уеб сайтове, от които може да се гледа, когато торентът няма сийдъри. Всеки адрес е шаблон:
 *   {tmdb} - номер от TMDB, {imdb} - номер от IMDb (tt1234567), {season} и {episode} - за сериали.
 * Основният е първи, останалите са резервни. Могат да се сменят от страницата за телефона (порт 62000).
 */
object WebSources {

    // Взети от проекта tvsites.
    val defaultMovie = listOf(
        "https://vidsrc.sh/embed/movie/{tmdb}",
        "https://vidcore.org/embed/movie/{tmdb}",
        "https://vidlink.pro/movie/{tmdb}",
        "https://vidspark.to/movie/{tmdb}",
        "https://vidsrc.me/embed/movie/{tmdb}",
        "https://vsembed.ru/embed/movie/{tmdb}",
        "https://yapgrid.com/embed/movie/{tmdb}",
        "https://embedwave.cc/embed/movie/{tmdb}",
        "https://embed.vidrift.net/embed/movie/{tmdb}",
        "https://cinesrc.st/embed/movie/{tmdb}",
        "https://vidfast.to/embed/movie/{tmdb}",
        "https://player.videasy.ws/embed/movie/{tmdb}",
        "https://stellar.rip/en/watch/embed/movie/{tmdb}",
        "https://flix.screenscape.me/embed?tmdb={tmdb}&type=movie",
        "https://vidy.st/movie/{tmdb}"
    )

    val defaultTv = listOf(
        "https://vidsrc.sh/embed/tv/{tmdb}/{season}/{episode}",
        "https://vidcore.org/embed/tv/{tmdb}/{season}/{episode}",
        "https://vidlink.pro/tv/{tmdb}/{season}/{episode}",
        "https://vidspark.to/tv/{tmdb}/{season}/{episode}",
        "https://vidsrc.me/embed/tv/{tmdb}/{season}/{episode}",
        "https://vsembed.ru/embed/tv/{tmdb}/{season}/{episode}",
        "https://yapgrid.com/embed/tv/{tmdb}/{season}/{episode}",
        "https://embedwave.cc/embed/tv/{tmdb}/{season}/{episode}",
        "https://embed.vidrift.net/embed/tv/{tmdb}/{season}/{episode}",
        "https://cinesrc.st/embed/tv/{tmdb}?s={season}&e={episode}",
        "https://vidfast.to/embed/tv/{tmdb}/{season}/{episode}",
        "https://player.videasy.ws/embed/tv/{tmdb}/{season}/{episode}",
        "https://stellar.rip/en/watch/embed/tv/{tmdb}-{season}-{episode}",
        "https://flix.screenscape.me/embed?tmdb={tmdb}&type=tv&s={season}&e={episode}",
        "https://vidy.st/tv/{tmdb}/{season}/{episode}"
    )

    /** Един ред - един шаблон; празните редове и коментарите (#) се прескачат. */
    fun parse(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.startsWith("http") && !it.startsWith("#") }

    fun templates(prefs: Prefs, tv: Boolean): List<String> {
        val custom = parse(if (tv) prefs.webTvSrc else prefs.webMovieSrc)
        return custom.ifEmpty { if (tv) defaultTv else defaultMovie }
    }

    class Source(val host: String, val url: String)

    /** Готовите адреси; шаблон, който иска {imdb} без да го имаме, се прескача. */
    fun sources(prefs: Prefs, tv: Boolean, tmdb: Int, imdb: String?, season: Int, episode: Int): List<Source> {
        val out = ArrayList<Source>()
        for (t in templates(prefs, tv)) {
            if (t.contains("{imdb}") && imdb.isNullOrBlank()) continue
            val url = t.replace("{tmdb}", tmdb.toString())
                .replace("{imdb}", imdb.orEmpty())
                .replace("{season}", season.toString())
                .replace("{episode}", episode.toString())
            out.add(Source(hostOf(url), url))
        }
        return out
    }

    fun hostOf(url: String): String =
        try {
            (Uri.parse(url).host ?: url).removePrefix("www.")
        } catch (_: Exception) {
            url
        }
}
