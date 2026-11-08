package app.torrtv.web

import app.torrtv.core.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

object WebApiClient {
    private val headers = mapOf("Referer" to "https://nextgencloudfabric.com/", "Origin" to "https://nextgencloudfabric.com")
    fun isApi(url: String): Boolean = try {
        val u = java.net.URI(url)
        u.scheme == "https" && u.host == "streamdata.vaplayer.ru" && u.path == "/api.php"
    } catch (_: Exception) { false }
    fun parse(body: String): List<WebSources.Source> {
        val j = JSONObject(body)
        if (j.has("status_code") && j.optString("status_code") != "200") return emptyList()
        val d = j.optJSONObject("data") ?: return emptyList()
        val urls = d.optJSONArray("stream_urls") ?: return emptyList()
        return (0 until minOf(urls.length(), 12)).mapNotNull { i ->
            val u = urls.optString(i)
            if (!WebSubtitleProvider.publicUrl(u)) null
            else WebSources.Source("VAPlayer", u, false, true,
                headers,
                d.optString("file_name"))
        }.distinctBy { it.url }
    }
    suspend fun resolve(urls: List<String>): List<WebSources.Source> {
        val out = ArrayList<WebSources.Source>()
        for (u in urls.filter { isApi(it) }.distinct().take(1)) {
            try { out.addAll(withTimeout(10_000) { parse(Http.get(u, headers)) }) }
            catch (e: CancellationException) { if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e }
            catch (_: Exception) { }
        }
        return out
    }
}
