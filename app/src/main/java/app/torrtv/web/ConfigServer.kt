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

    private fun link(url: String, text: String) = "<a href='$url' target='_blank' rel='noopener'>$text</a>"

    /** Сгъваема секция. more = текст, който се вижда само след „Покажи повече“. */
    private fun more(summary: String, body: String): String =
        "<details class='more'><summary>$summary</summary><div class='box'>$body</div></details>"

    private fun section(title: String, open: Boolean, body: String): String =
        "<details class='sec'${if (open) " open" else ""}><summary>$title</summary><div class='sbody'>$body</div></details>"

    private fun page(msg: String?, ok: Boolean): String {
        val sb = StringBuilder()
        sb.append("<!doctype html><html lang='bg'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>TorrTV настройки</title><style>")
        sb.append("*{box-sizing:border-box}body{font-family:system-ui,sans-serif;background:#07090e;color:#f3f5fa;margin:0;padding:18px 16px 0;max-width:600px;margin:auto;line-height:1.45}")
        sb.append("h1{font-size:26px;margin:6px 0 2px;letter-spacing:.5px}h1 b{color:#e9b44c}.sub{color:#98a2b8;margin:2px 0 14px}p{color:#b4bccd;margin:6px 0 10px}")
        sb.append("label{display:block;margin:14px 0 4px;font-weight:600;font-size:14px}a{color:#f2d58f}")
        sb.append("input,textarea{width:100%;padding:12px;border-radius:10px;border:1px solid #2a3350;background:#171e2e;color:#fff;font-size:16px}")
        sb.append("textarea{min-height:130px;font-family:monospace;font-size:13px}")
        sb.append("button{width:100%;padding:14px;border:0;border-radius:12px;background:linear-gradient(90deg,#f6cb6b,#d9962b);color:#1a1305;font-size:17px;font-weight:700}")
        sb.append(".savebar{position:sticky;bottom:0;padding:22px 0 14px;background:linear-gradient(rgba(7,9,14,0),#07090e 38%)}")
        sb.append(".ok{color:#3ddc84;font-weight:500}.no{color:#ffb020;font-weight:500}.msg{padding:12px;border-radius:10px;margin:10px 0}")
        sb.append(".good{background:#12301f;color:#3ddc84}.bad{background:#3a1620;color:#ff5c6c}")
        sb.append("details.sec{background:#0e121a;border:1px solid #1d2536;border-radius:14px;margin:12px 0}")
        sb.append("details.sec>summary{padding:14px 16px;font-weight:700;font-size:16px;cursor:pointer;list-style:none}details.sec>summary::-webkit-details-marker{display:none}")
        sb.append("details.sec>summary::after{content:'▾';float:right;color:#e9b44c}details.sec[open]>summary::after{content:'▴'}")
        sb.append(".sbody{padding:0 16px 14px}details.more{margin:10px 0}details.more>summary{color:#e9b44c;font-size:14px;cursor:pointer}")
        sb.append(".box{background:#141a26;border-radius:10px;padding:10px 14px;margin-top:8px;color:#c6cddd;font-size:14px}.box ol,.box ul{margin:6px 0;padding-left:20px}.box li{margin:4px 0}")
        sb.append("code{background:#1d2536;color:#f2d58f;padding:1px 6px;border-radius:6px;font-size:13px}.step{display:flex;gap:10px;margin:8px 0}.step b{flex:none;width:26px;height:26px;border-radius:50%;background:#e9b44c;color:#1a1305;text-align:center;line-height:26px}")
        sb.append(".kicker{color:#98a2b8;font-size:13px;margin-top:2px}")
        sb.append("</style></head><body><h1>Torr<b>TV</b></h1><div class='sub'>Настройки от телефона</div>")
        if (msg != null) sb.append("<div class='msg ").append(if (ok) "good" else "bad").append("'>").append(esc(msg)).append("</div>")

        // ---------- Упътване ----------
        sb.append(section("📖  Как се работи", true,
            "<div class='step'><b>1</b><div>На телевизора отвори <code>Настройки</code> - там се вижда ПИН от 4 цифри. Тази страница работи <i>само докато този екран е отворен</i>.</div></div>" +
                "<div class='step'><b>2</b><div>Попълни само полетата, които искаш да смениш. <i>Празните полета не се променят.</i></div></div>" +
                "<div class='step'><b>3</b><div>Въведи ПИН-а най-долу и натисни <code>Запази</code>. Трябва да излезе зелено „Запазено ✓“.</div></div>" +
                more("Покажи повече - ако не става",
                    "<ul><li>Телефонът и телевизорът трябва да са в <b>една Wi-Fi мрежа</b>.</li>" +
                        "<li>Адресът е точно този от телевизора (<code>http://…:62000</code>). Ако порт 62000 е зает от друга програма, телевизорът показва друг порт - ползвай него.</li>" +
                        "<li>„Грешен ПИН“: ПИН-ът се сменя всеки път, когато отвориш Настройки. Виж го пак на екрана.</li>" +
                        "<li>Записаното остава на телевизора. Ключовете и паролите не излизат никъде другаде освен към самите услуги (TMDB, OpenSubtitles и т.н.).</li>" +
                        "<li>За да върнеш полето към вграденото, не е нужно нищо - просто не го пипай.</li></ul>")))

        sb.append("<form method='post' action='/save'>")

        // ---------- Ключове ----------
        sb.append("<details class='sec' open><summary>🔑  Ключове (филми и субтитри)</summary><div class='sbody'>")
        sb.append("<p>Нужни са, за да се показват плакати и описания и да се намират субтитри. Всички са безплатни.</p>")

        sb.append(field("tmdb_key", "TMDB ключ"))
        sb.append("<div class='kicker'>Информация, плакати и снимки на филмите.</div>")
        sb.append(more("Откъде се взима",
            "<ol><li>Направи безплатен акаунт в ${link("https://www.themoviedb.org/signup", "themoviedb.org")} и потвърди имейла.</li>" +
                "<li>Отвори ${link("https://www.themoviedb.org/settings/api", "Settings → API")} и поискай ключ (<i>Request an API Key</i> → тип <i>Developer</i>).</li>" +
                "<li>Попълни кратката форма (за лична употреба; адресът на сайта може да е произволен).</li>" +
                "<li>Копирай <b>API Key</b> (дългото число с букви, около 32 знака). <i>Не</i> копирай „API Read Access Token“ - той е друго.</li></ol>"))

        sb.append(field("os_key", "OpenSubtitles - API ключ"))
        sb.append(field("os_user", "OpenSubtitles - потребител"))
        sb.append(field("os_pass", "OpenSubtitles - парола"))
        sb.append("<div class='kicker'>Субтитри от OpenSubtitles. Трябват ключ, потребител и парола на един и същ акаунт.</div>")
        sb.append(more("Откъде се взима",
            "<ol><li>Направи безплатен акаунт в ${link("https://www.opensubtitles.com", "opensubtitles.com")} и потвърди имейла. Потребителят и паролата са от този акаунт.</li>" +
                "<li>Влез, натисни иконата си горе вдясно → <b>API consumers</b> → <b>New consumer</b>.</li>" +
                "<li>Име: каквото искаш (например TorrTV). Остави „Allow anonymous“ включено, „Under development“ изключено. Запази.</li>" +
                "<li>Копирай получения <b>API key</b> в полето по-горе.</li></ol>" +
                "<p>Безплатният акаунт има дневен лимит за сваляне на субтитри. Ако лимитът свърши, другите доставчици продължават да работят.</p>"))

        sb.append(field("subdl_key", "Subdl - API ключ"))
        sb.append("<div class='kicker'>Субтитри от Subdl (безплатно, около 2000 заявки на ден).</div>")
        sb.append(more("Откъде се взима",
            "<ol><li>Регистрирай се и влез в ${link("https://subdl.com/panel/login", "панела на Subdl")}.</li>" +
                "<li>Отвори ${link("https://subdl.com/panel/api", "subdl.com/panel/api")} - там е ключът („Get your free API key“).</li>" +
                "<li>Копирай го в полето. Започва с <code>subdl_</code>.</li></ol>"))

        sb.append(field("subsource_key", "SubSource - API ключ"))
        sb.append("<div class='kicker'>Субтитри от SubSource.</div>")
        sb.append(more("Откъде се взима",
            "<ol><li>Регистрирай се в ${link("https://subsource.net", "subsource.net")} и влез.</li>" +
                "<li>В акаунта си отвори раздела <b>API</b> („API &amp; Plans“) и създай ключ. Описание на услугата: ${link("https://subsource.net/api-docs", "subsource.net/api-docs")}.</li>" +
                "<li>Копирай ключа в полето.</li></ol>"))
        sb.append("</div></details>")

        // ---------- Уеб сайтове ----------
        sb.append("<details class='sec'><summary>🌐  Уеб сайтове за гледане</summary><div class='sbody'>")
        sb.append("<p>Когато торентът няма сийдъри, филмът може да се гледа онлайн от сайт (менюто „Откъде?“ → „Уеб сайтове“). Тук са адресите, които се пробват. Първият е основен, останалите са резервни - ако един не се отвори, се опитва следващият.</p>")
        sb.append(area("web_movie", "За филми - по един адрес на ред", WebSources.templates(prefs, false), prefs.webMovieSrc.isNotBlank()))
        sb.append(area("web_tv", "За сериали - по един адрес на ред", WebSources.templates(prefs, true), prefs.webTvSrc.isNotBlank()))
        sb.append(more("Покажи повече - как се пише адрес",
            "<p>Всеки ред е <b>шаблон</b>: адрес, в който приложението заменя специалните части със сведенията за избрания филм:</p>" +
                "<ul><li><code>{tmdb}</code> - номерът на филма в TMDB (има го винаги)</li>" +
                "<li><code>{imdb}</code> - номерът в IMDb, напр. <code>tt0133093</code> (ако шаблонът го иска, а филмът няма, редът се прескача)</li>" +
                "<li><code>{season}</code> и <code>{episode}</code> - сезон и епизод (само за сериали)</li></ul>" +
                "<p>Пример за филм: <code>https://пример.com/embed/movie/{tmdb}</code><br>Пример за сериал: <code>https://пример.com/embed/tv/{tmdb}/{season}/{episode}</code></p>" +
                "<ul><li>Редове, които не започват с <code>http</code>, и редове с <code>#</code> отпред се пренебрегват (удобно за бележки).</li>" +
                "<li>Ако един сайт спре да работи - просто изтрий реда му. Нов сайт - добави ред.</li>" +
                "<li>Напиши само <code>-</code> (тире) в полето, за да върнеш стандартните адреси.</li>" +
                "<li>Формата на адреса зависи от самия сайт; потърси как изглежда адресът му при филм и го замени с части от горния списък.</li>" +
                "<li>Ти избираш източниците и отговаряш за тях. Приложението само отваря адреса и блокира реклами.</li></ul>"))
        sb.append("</div></details>")

        // ---------- Домейни ----------
        sb.append("<details class='sec'><summary>🧲  Адреси на торент сайтове</summary><div class='sbody'>")
        sb.append("<p>Нужни са <b>само</b> ако някой сайт смени адреса си и вече не дава резултати. Иначе остави празно - ползват се вградените.</p>")
        sb.append(field("dom_apibay", "The Pirate Bay (apibay)"))
        sb.append(field("dom_yts", "YTS"))
        sb.append(field("dom_eztv", "EZTV"))
        sb.append(field("dom_nyaa", "Nyaa"))
        sb.append(more("Покажи повече", "<p>Пиши само домейна, например <code>yts.mx</code> (<code>https://</code> не е нужно). Проверка кой сайт работи: на телевизора, Настройки → Диагностика → „Тествай източниците“.</p>"))
        sb.append("</div></details>")

        // ---------- Всичко наведнъж ----------
        sb.append("<details class='sec'><summary>📋  Всичко наведнъж</summary><div class='sbody'>")
        sb.append("<p>Удобно, ако копираш настройките от друго място: по един ред <code>име=стойност</code>.</p>")
        sb.append("<textarea name='bulk' spellcheck='false' placeholder='tmdb_key=...&#10;subdl_key=...'></textarea>")
        sb.append(more("Покажи повече - имена на полетата", "<p>" + Prefs.FIELD_NAMES.filter { !it.startsWith("web_") }.joinToString(" · ") { "<code>$it</code>" } + "</p><p>Адресите на уеб сайтовете се сменят само от собствените им полета по-горе.</p>"))
        sb.append("</div></details>")

        sb.append("<div class='savebar'><label style='margin-top:0'>ПИН от телевизора</label><input name='pin' inputmode='numeric' autocomplete='off' placeholder='4 цифри'><div style='height:10px'></div><button type='submit'>Запази</button></div>")
        sb.append("</form></body></html>")
        return sb.toString()
    }
}
