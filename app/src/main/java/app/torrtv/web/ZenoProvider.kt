package app.torrtv.web

import app.torrtv.core.Http
import app.torrtv.core.Http.await
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup

object ZenoProvider {
    fun bgPlayers(body: String): List<String> {
        val j = JSONObject(body)
        if (!j.optBoolean("success", true)) return emptyList()
        val a = j.optJSONObject("data")?.optJSONArray("providers") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o=a.optJSONObject(i) ?: return@mapNotNull null
            val url=o.optString("url")
            if (o.optString("group").lowercase() == "bg" && o.optBoolean("enabled",true) && WebSubtitleProvider.publicUrl(url)) url else null
        }.distinct()
    }
    suspend fun hasBg(page: String, html: String): Boolean {
        val u = java.net.URI(page)
        if (u.scheme != "https" || u.host !in setOf("zenoplay.to", "www.zenoplay.to")) return false
        val doc=Jsoup.parse(html,page)
        val postId=doc.selectFirst("[data-post-id]")?.attr("data-post-id").orEmpty()
        val token=doc.selectFirst("[data-player-token]")?.attr("data-player-token").orEmpty()
        if (!postId.matches(Regex("\\d+")) || token.isBlank()) return false
        val request=Request.Builder().url("https://zenoplay.to/wp-admin/admin-ajax.php")
            .header("Referer",page).header("Origin","https://zenoplay.to")
            .header("User-Agent",Http.BROWSER_UA).header("X-Requested-With","XMLHttpRequest")
            .post(FormBody.Builder().add("action","zenoplay_player_sources").add("post_id",postId).add("token",token).build()).build()
        return Http.client.newCall(request).await().use { r ->
            r.isSuccessful && bgPlayers(r.body?.string().orEmpty()).isNotEmpty()
        }
    }
}
