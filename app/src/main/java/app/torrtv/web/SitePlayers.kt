package app.torrtv.web

import app.torrtv.core.AppLog
import app.torrtv.core.Http
import app.torrtv.core.Http.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/**
 * Превръща плеърите, които български сайт изброява, в адреси за рамката на плеъра.
 *
 * Някои сайтове крият плеъра зад собствен адрес, който само пренасочва (FilmiZT `/filmi/0-0-3-…`, Filmi2K `/?rtp=…`).
 * Адрес на каталога никога не се отваря като плеър, затова това пренасочване се проследява тук. Плеъри, за които
 * самият хостинг казва, че файлът липсва, отпадат още преди пускането. При мрежова грешка адресът остава какъвто е:
 * непроверен не значи мъртъв.
 */
object SitePlayers {
    private val noRedirects by lazy { Http.client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    suspend fun resolve(page: String, urls: List<String>): List<String> = coroutineScope {
        urls.distinct().take(8).map { url ->
            async {
                try {
                    withTimeout(6_000) { resolveOne(page, url) }
                } catch (ex: CancellationException) {
                    if (ex is TimeoutCancellationException) url else throw ex
                } catch (ex: Exception) {
                    AppLog.w("web", "плеърът не е проверен: $url", ex)
                    url
                }
            }
        }.awaitAll().filterNotNull().distinct()
    }

    private suspend fun resolveOne(page: String, url: String): String? {
        if (!WebSubtitleProvider.publicUrl(url)) return null
        var target = url
        if (BgPlayerPresentation.catalogue(url)) {
            val next = redirect(url, page)
            if (next == null || BgPlayerPresentation.catalogue(next)) {
                AppLog.d("web", "плеърът на сайта не води към видео: $url → ${next ?: "-"}")
                return null
            }
            target = next
        }
        if (!WebSubtitleProvider.publicUrl(target) || AdBlock.isBlocked(URI(target).host)) return null
        if (missing(target)) {
            AppLog.d("web", "хостингът казва, че файлът липсва: $target")
            return null
        }
        return target
    }

    private suspend fun redirect(url: String, referer: String): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", Http.BROWSER_UA)
            .header("Referer", referer.substringBefore('#'))
            .build()
        return noRedirects.newCall(req).await().use { r ->
            if (r.isRedirect) r.header("Location")?.let { URI(url).resolve(it.trim()).toString() } else null
        }
    }

    /**
     * Хостинги със собствен API, който казва дали файлът съществува. videoshar.uns.bio (плеър 1 на FilmiZT) отговаря
     * `{"ok":false,"error":"not found"}` с 404 за премахнати файлове, а страницата му показва само „maintenance“.
     */
    private suspend fun missing(url: String): Boolean {
        val u = URI(url)
        val host = u.host?.lowercase().orEmpty()
        val file = u.fragment.orEmpty().trim()
        if (!host.endsWith("uns.bio") || file.isEmpty()) return false
        val api = "https://$host/api/public/resolve?file1=" + URLEncoder.encode(file, "UTF-8")
        val req = Request.Builder().url(api).header("User-Agent", Http.BROWSER_UA).header("Referer", url).build()
        return Http.client.newCall(req).await().use { r ->
            val json = try { JSONObject(r.body?.string().orEmpty()) } catch (_: Exception) { null }
            r.code == 404 || json != null && json.has("ok") && !json.optBoolean("ok")
        }
    }
}
