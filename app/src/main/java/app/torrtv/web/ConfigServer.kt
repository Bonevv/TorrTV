package app.torrtv.web

import app.torrtv.core.Prefs
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import java.util.Random
import java.util.concurrent.Executors

/**
 * Малка уеб страница, която се отваря от телефона (в същата Wi-Fi мрежа), за да се въведат
 * API ключовете без писане с дистанционното. Работи само докато екранът "Настройки" е отворен
 * и изисква ПИН, показан на телевизора.
 */
class ConfigServer(private val prefs: Prefs) {

    companion object {
        const val PORT = 62000
    }

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var pin: String = ""
        private set

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    @Synchronized
    fun start() {
        if (server != null) return
        pin = (1000 + Random().nextInt(9000)).toString()
        // Постоянен порт 62000, за да е лесно да се запомни адресът; ако е зает - друг свободен.
        val s = try {
            ServerSocket(PORT)
        } catch (e: IOException) {
            ServerSocket(0)
        }
        server = s
        port = s.localPort
        pool.execute {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    pool.execute { handle(c) }
                } catch (e: IOException) {
                    break
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        try {
            server?.close()
        } catch (e: Exception) {
        }
        server = null
    }

    fun addresses(): List<String> {
        val out = ArrayList<String>()
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in Collections.list(ni.inetAddresses)) {
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        val h = a.hostAddress
                        if (h != null) out.add(h)
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 15000
            val input = BufferedInputStream(sock.getInputStream())
            val reqLine = readLine(input) ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            var len = 0
            while (true) {
                val l = readLine(input) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0 && l.substring(0, i).equals("content-length", true)) len = l.substring(i + 1).trim().toIntOrNull() ?: 0
            }
            val html: String
            if (method == "POST" && path == "/save") {
                val buf = ByteArray(minOf(len, 65536))
                var got = 0
                while (got < buf.size) {
                    val n = input.read(buf, got, buf.size - got)
                    if (n < 0) break
                    got += n
                }
                html = save(parseForm(String(buf, 0, got, Charsets.UTF_8)))
            } else {
                html = page(null, true)
            }
            val body = html.toByteArray(Charsets.UTF_8)
            val out = sock.getOutputStream()
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.write(body)
            out.flush()
        } catch (e: Exception) {
        } finally {
            try {
                sock.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun parseForm(body: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (pair in body.split("&")) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            try {
                m[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            } catch (e: Exception) {
            }
        }
        return m
    }

    private fun save(form: Map<String, String>): String {
        if (form["pin"]?.trim() != pin) return page("Грешен ПИН. Виж го на екрана на телевизора.", false)
        var count = 0
        // Уеб адресите: полетата са попълнени със сегашните, затова записваме само ако нещо е променено.
        for ((name, tv) in listOf("web_movie" to false, "web_tv" to true)) {
            val v = form[name]?.trim().orEmpty()
            if (v.isEmpty()) continue
            val now = WebSources.templates(prefs, tv).joinToString("\n")
            val norm = v.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            if (v == "-" || norm != now) {
                prefs.set(name, if (v == "-") "-" else norm)
                count++
            }
        }
        for (name in Prefs.FIELD_NAMES) {
            if (name.startsWith("web_")) continue
            val v = form[name]?.trim().orEmpty()
            if (v.isNotEmpty()) {
                prefs.set(name, v)
                count++
            }
        }
        // Поле "всичко наведнъж": по един ред име=стойност
        val bulk = form["bulk"].orEmpty()
        for (line in bulk.lines()) {
            val i = line.indexOfFirst { it == '=' || it == ':' }
            if (i <= 0) continue
            val k = line.substring(0, i).trim().lowercase().replace('-', '_').replace(' ', '_')
            val v = line.substring(i + 1).trim()
            if (k in Prefs.FIELD_NAMES && v.isNotEmpty()) {
                prefs.set(k, v)
                count++
            }
        }
        return page(if (count == 0) "Нищо не е променено (всички полета са празни)." else "Запазено ✓ ($count)", count > 0)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun field(name: String, label: String): String {
        val state = if (prefs.isSet(name)) "<span class='ok'>✓ записан</span>" else "<span class='no'>не е зададен</span>"
        return "<label>${esc(label)} $state</label><input name='$name' autocomplete='off' autocapitalize='off' spellcheck='false' placeholder='${if (prefs.isSet(name)) "остави празно, за да не се променя" else ""}'>"
    }

    private fun area(name: String, label: String, lines: List<String>, custom: Boolean): String {
        val state = if (custom) "<span class='ok'>✓ свои</span>" else "<span class='no'>стандартни</span>"
        return "<label>${esc(label)} $state</label><textarea name='$name' spellcheck='false' autocapitalize='off' style='min-height:150px'>${esc(lines.joinToString("\n"))}</textarea>"
    }

    private fun page(msg: String?, ok: Boolean): String {
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>TorrTV настройки</title><style>")
        sb.append("body{font-family:system-ui,sans-serif;background:#07090e;color:#f3f5fa;margin:0;padding:18px;max-width:560px;margin:auto}")
        sb.append("h1{font-size:24px;margin:6px 0 2px}p{color:#98a2b8;margin:4px 0 14px}")
        sb.append("label{display:block;margin:14px 0 4px;font-weight:600;font-size:14px}")
        sb.append("input,textarea{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #2a3350;background:#171e2e;color:#fff;font-size:16px}")
        sb.append("textarea{min-height:130px;font-family:monospace;font-size:13px}")
        sb.append("button{margin-top:20px;width:100%;padding:14px;border:0;border-radius:12px;background:linear-gradient(90deg,#f6cb6b,#d9962b);color:#1a1305;font-size:17px;font-weight:700}")
        sb.append(".ok{color:#3ddc84;font-weight:500}.no{color:#ffb020;font-weight:500}.msg{padding:12px;border-radius:10px;margin:10px 0}")
        sb.append(".good{background:#12301f;color:#3ddc84}.bad{background:#3a1620;color:#ff5c6c}details{margin-top:18px}summary{color:#98a2b8}")
        sb.append("</style></head><body><h1>TorrTV</h1><p>Въведи ключовете си веднъж. Празните полета не се променят.</p>")
        if (msg != null) sb.append("<div class='msg ").append(if (ok) "good" else "bad").append("'>").append(esc(msg)).append("</div>")
        sb.append("<form method='post' action='/save'>")
        sb.append("<label>ПИН от телевизора</label><input name='pin' inputmode='numeric' autocomplete='off' placeholder='4 цифри'>")
        sb.append(field("tmdb_key", "TMDB ключ"))
        sb.append(field("os_key", "OpenSubtitles - API ключ"))
        sb.append(field("os_user", "OpenSubtitles - потребител"))
        sb.append(field("os_pass", "OpenSubtitles - парола"))
        sb.append(field("subdl_key", "Subdl - API ключ"))
        sb.append(field("subsource_key", "SubSource - API ключ"))
        sb.append("<details><summary>Всичко наведнъж (name=value на ред)</summary><textarea name='bulk' spellcheck='false' placeholder='tmdb_key=...&#10;os_key=...'></textarea></details>")
        sb.append("<details><summary>Домейни на торент сайтове (само ако някой смени адреса си)</summary>")
        sb.append(field("dom_apibay", "The Pirate Bay (apibay)"))
        sb.append(field("dom_yts", "YTS"))
        sb.append(field("dom_eztv", "EZTV"))
        sb.append(field("dom_nyaa", "Nyaa"))
        sb.append("</details>")
        sb.append("<details><summary>Уеб сайтове за гледане (адреси)</summary>")
        sb.append("<p>По един адрес на ред. Може да ползваш {tmdb}, {imdb}, {season}, {episode}. Първият е основен, другите са резервни. Напиши само тире (-), за да върнеш стандартните.</p>")
        sb.append(area("web_movie", "За филми", WebSources.templates(prefs, false), prefs.webMovieSrc.isNotBlank()))
        sb.append(area("web_tv", "За сериали", WebSources.templates(prefs, true), prefs.webTvSrc.isNotBlank()))
        sb.append("</details><button type='submit'>Запази</button></form></body></html>")
        return sb.toString()
    }
}
