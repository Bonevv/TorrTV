package app.torrtv.web

import app.torrtv.core.Http
import app.torrtv.core.Http.await
import app.torrtv.data.subs.*
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URI

/** Public subtitle tracks from the selected page; never searches or spends another provider's quota. */
class WebSubtitleProvider : SubtitleProvider {
    override val id = "web_track"
    override val name = "Субтитри от сайта"
    override fun configured() = false
    override suspend fun search(q: SubtitleQuery) = emptyList<SubtitleResult>()
    override suspend fun download(r: SubtitleResult, q: SubtitleQuery): List<SubFile> {
        val j = JSONObject(r.ref)
        val url = j.getString("url")
        val referer = j.getString("page")
        require(publicUrl(url) && publicUrl(referer)) { "Невалиден адрес на субтитрите" }
        val req = Request.Builder().url(url).header("Referer", referer).header("User-Agent", Http.BROWSER_UA).build()
        val bytes = Http.client.newCall(req).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Празен субтитър")
            require(body.contentLength() <= 6 * 1024 * 1024) { "Прекалено голям файл" }
            body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (out.size() <= 6 * 1024 * 1024) {
                    val n = input.read(buffer); if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        }
        require(bytes.size <= 6 * 1024 * 1024) { "Прекалено голям файл" }
        val text = SubText.decode(bytes, "bg")
        val ext = when {
            text.trimStart().startsWith("WEBVTT") -> "vtt"
            text.contains("[Script Info]", ignoreCase = true) -> "ass"
            Regex("\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->").containsMatchIn(text) -> "srt"
            else -> throw IOException("Отговорът не е файл със субтитри")
        }
        return listOf(SubFile("site.$ext", text.toByteArray(Charsets.UTF_8)))
    }
    companion object {
        fun publicUrl(s: String): Boolean = try {
            val u = URI(s)
            val h = u.host?.lowercase().orEmpty()
            u.scheme in setOf("https", "http") && u.rawUserInfo == null && h.contains('.') &&
                !h.startsWith("[") && !h.endsWith(".local") && h != "localhost" &&
                !h.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}"))
        } catch (_: Exception) { false }
        fun caption(json: String): SubtitleResult? = try {
            val j = JSONObject(json)
            val lang = j.optString("lang").lowercase()
            val label = j.optString("label")
            if (lang.isNotBlank() && lang !in setOf("bg", "bul", "bg-bg")) null
            else if (lang !in setOf("bg", "bul", "bg-bg") && !Regex("(?iu)българ|bulgarian").containsMatchIn(label)) null
            else if (!publicUrl(j.optString("url")) || !publicUrl(j.optString("page"))) null
            else SubtitleResult("web_track", "Субтитри от сайта", "bg", label.ifBlank { "Български · от сайта" },
                JSONObject().put("url", j.getString("url")).put("page", j.getString("page")).toString(), 0, false, false)
        } catch (_: Exception) { null }
        val probeScript = """
            (function(){
              if(window.__torrtvSubs)return;window.__torrtvSubs=true;
              var seen={},count=0;
              function emit(url,lang,label){
                if(lang&&!/^(bg|bul|bg-bg)${'$'}/i.test(lang))return;
                if(!lang&&!/българ|bulgarian/i.test(label||''))return;
                if(!url||seen[url]||count>=16)return;seen[url]=true;count++;
                console.info('TORRTV_SUB:'+JSON.stringify({url:url,page:location.href,lang:lang||'',label:label||''}));
              }
              function scan(){
                document.querySelectorAll('track[src]').forEach(function(t){emit(t.src,t.srclang,t.label);});
                try {
                  var raw = typeof playerjsSubtitle==='string' ? playerjsSubtitle : '';
                  raw.split(',').forEach(function(part){var m=part.match(/^\[([^\]]+)\](https?:\/\/.+)${'$'}/);if(m)emit(m[2],'',m[1]);});
                }catch(e){}
                try { if(typeof window.jwplayer==='function') {
                  var list=window.jwplayer().getPlaylist()||[];
                  list.forEach(function(item){(item.tracks||[]).forEach(function(t){
                    if(t.kind==='captions'||t.kind==='subtitles')emit(t.file,t.srclang||t.language,t.label);
                  });});
                }}catch(e){}
              }
              window.__torrtvScanSubs=scan;
              var scheduled=false;new MutationObserver(function(){if(scheduled)return;scheduled=true;setTimeout(function(){scheduled=false;scan();},250);}).observe(document,{childList:true,subtree:true});
              setInterval(scan,2000);scan();
            })();
        """.trimIndent()
    }
}
