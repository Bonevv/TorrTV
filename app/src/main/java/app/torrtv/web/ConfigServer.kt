package app.torrtv.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.torrtv.core.Prefs
import app.torrtv.data.Remote
import app.torrtv.state.Graph
import app.torrtv.data.torrents.StremioAddon
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Малка уеб страница, която се отваря от телефона (в същата Wi-Fi мрежа): оттам се въвеждат API ключовете и се
 * пише търсене за телевизора, без да се чука с дистанционното. Работи само докато е отворен екранът "Настройки"
 * или "Търсене" и изисква ПИН, показан на телевизора. ПИН-ът е един и същ, докато приложението е пуснато;
 * след пет грешни опита се сменя и за минута не се приемат опити (виж PinGuard).
 */
class ConfigServer(private val prefs: Prefs) {

    companion object {
        const val PORT = 62000
    }

    /** Портът на страницата (0, докато сървърът не е пуснат). Състояние, за да се опресни екранът веднага щом тръгне. */
    var port: Int by mutableStateOf(0)
        private set

    private val guard = PinGuard { (1000 + SecureRandom().nextInt(9000)).toString() }

    /** ПИН-ът, който се показва на телевизора. Сменя се след пет грешни опита, затова е състояние на екрана. */
    var pin: String by mutableStateOf(guard.pin)
        private set

    /** Вика се, когато от телефона е изпратена дума за търсене (от нишка на сървъра). */
    @Volatile
    var onSearch: ((String) -> Unit)? = null

    /** Последните търсения - за бързи бутони на страницата. */
    @Volatile
    var recent: () -> List<String> = { emptyList() }

    /** Приложението е свързано с услугата за настройки (тогава ключовете не се въвеждат от тук). Променя се само в проверките. */
    @Volatile
    var serviceOn: Boolean = Remote.enabled

    /** Има ли вход в профила. Променя се само в проверките. */
    @Volatile
    var loggedIn: () -> Boolean = { Graph.sync.loggedIn }

    /** Дистанционното за плеъра; има стойност само докато върви филм и е позволено. */
    @Volatile
    var remote: PlayerRemote? = null

    /** Вход с номер и ПИН на профила от телефона. Връща "" при успех, иначе текста на грешката. */
    @Volatile
    var onLogin: ((String, String) -> String)? = null

    private var server: ServerSocket? = null
    private var users = 0
    private val pool = Executors.newCachedThreadPool()

    /** Всяко отваряне на екран, който го иска, вика start(), а затварянето - stop(); сървърът работи, докато има поне един. */
    @Synchronized
    fun start() {
        users++
        if (server != null) return
        // Постоянен порт 62000, за да е лесно да се запомни адресът; ако е зает - друг свободен.
        val s = try {
            ServerSocket(PORT)
        } catch (e: IOException) {
            try {
                ServerSocket(0)
            } catch (e2: IOException) {
                return // няма как да се пусне; екранът просто не показва адрес, а stop() ще изравни броя
            }
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
        if (users > 0) users--
        if (users > 0) return
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
            var ctype = "text/html; charset=utf-8"
            if (method == "POST" && path in setOf("/save", "/search", "/login", "/state", "/ctl")) {
                val buf = ByteArray(minOf(len, 65536))
                var got = 0
                while (got < buf.size) {
                    val n = input.read(buf, got, buf.size - got)
                    if (n < 0) break
                    got += n
                }
                val form = parseForm(String(buf, 0, got, Charsets.UTF_8))
                when (path) {
                    "/search" -> html = search(form)
                    "/login" -> html = login(form)
                    "/state" -> {
                        ctype = "application/json; charset=utf-8"
                        html = stateJson(form)
                    }
                    "/ctl" -> {
                        ctype = "application/json; charset=utf-8"
                        html = ctl(form)
                    }
                    else -> html = save(form)
                }
            } else {
                html = page(null, true)
            }
            val body = html.toByteArray(Charsets.UTF_8)
            val out = sock.getOutputStream()
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: $ctype\r\nCache-Control: no-store\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
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

    /** Проверява ПИН-а; връща съобщение за грешка (за страницата) или null, ако е верен. */
    private fun checkPin(form: Map<String, String>): String? {
        val now = System.currentTimeMillis()
        val r = guard.check(form["pin"], now)
        pin = guard.pin
        return when (r) {
            PinGuard.Result.OK -> null
            PinGuard.Result.WRONG -> {
                val n = guard.triesLeft()
                "Грешен ПИН. Провери го на екрана на телевизора. " + if (n == 1) "Остава 1 опит." else "Остават $n опита."
            }
            PinGuard.Result.LOCKED -> {
                val s = guard.lockLeftSec(now)
                "Твърде много грешни опити. ПИН-ът е сменен - виж новия на телевизора и опитай пак след $s сек."
            }
        }
    }

    private fun q(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c < ' ' -> sb.append(' ')
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    private fun snapJson(): String {
        val r = remote ?: return "{\"active\":false}"
        val s = r.snap()
        return "{\"active\":true,\"title\":${q(s.title)},\"pos\":${s.posMs},\"dur\":${s.durMs},\"playing\":${s.playing},\"off\":${s.offsetMs},\"subs\":${s.subsOn}}"
    }

    /** Състоянието на филма за страницата (иска ПИН, но не брои успешните проверки за грешки). */
    private fun stateJson(form: Map<String, String>): String {
        checkPin(form)?.let { return "{\"err\":\"pin\",\"msg\":${q(it)}}" }
        return snapJson()
    }

    private fun ctl(form: Map<String, String>): String {
        checkPin(form)?.let { return "{\"err\":\"pin\",\"msg\":${q(it)}}" }
        val cmd = form["cmd"].orEmpty()
        val arg = form["arg"]?.toLongOrNull() ?: 0L
        if (cmd in PlayerRemote.COMMANDS) remote?.act(cmd, arg.coerceIn(-3_600_000L, 3_600_000L))
        return snapJson()
    }

    /** Вход от телефона: ПИН-ът на телевизора плюс номер и ПИН на профила. */
    private fun login(form: Map<String, String>): String {
        checkPin(form)?.let { return page(it, false) }
        if (!serviceOn || loggedIn()) return page("Вече си влязъл.", true)
        val num = form["num"].orEmpty().trim()
        val spin = form["spin"].orEmpty().trim()
        if (num.length < 3 || spin.length < 4) return page("Напиши номера и ПИН-а си.", false)
        val err = onLogin?.invoke(num.take(8), spin.take(12)) ?: "Не се получи. Опитай от телевизора."
        return if (err.isEmpty()) page("Готово, влязъл си. Телевизорът продължава сам.", true) else page(err, false)
    }

    /** Дума за търсене от телефона: праща се към телевизора, който отваря търсенето. */
    private fun search(form: Map<String, String>): String {
        checkPin(form)?.let { return page(it, false) }
        val q = form["q"].orEmpty().trim()
        if (q.length < 2) return page("Напиши поне 2 букви.", false)
        onSearch?.invoke(q.take(80))
        return page("Изпратено на телевизора: „${q.take(80)}“", true)
    }

    private fun save(form: Map<String, String>): String {
        checkPin(form)?.let { return page(it, false) }
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
        // Свои торент addon-и: същото правило - записваме само ако е променено; "-" ги изтрива.
        run {
            val v = form["addons"]?.trim().orEmpty()
            if (v.isNotEmpty()) {
                val now = StremioAddon.parseList(prefs.addonsSrc).joinToString("\n")
                val norm = StremioAddon.parseList(v).joinToString("\n")
                if (v == "-" || norm != now) {
                    prefs.set("addons", if (v == "-") "-" else norm)
                    count++
                }
            }
        }
        for (name in Prefs.FIELD_NAMES) {
            if (name.startsWith("web_") || name == "addons") continue
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
        return page(if (count == 0) "Нищо не е променено - всички полета са празни." else if (count == 1) "Запазено." else "Запазени са $count полета.", count > 0)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun desc(text: String) = if (text.isBlank()) "" else "<div class='desc'>$text</div>"

    private fun field(name: String, label: String, desc: String = ""): String {
        val state = if (prefs.isSet(name)) "<span class='ok'>✓ записан</span>" else "<span class='no'>не е зададен</span>"
        return "<label>${esc(label)} $state</label>${desc(desc)}<input name='$name' autocomplete='off' autocapitalize='off' spellcheck='false' placeholder='${if (prefs.isSet(name)) "остави празно, за да не се променя" else ""}'>"
    }

    private fun area(
        name: String, label: String, lines: List<String>, custom: Boolean,
        none: String = "стандартни", placeholder: String = "", desc: String = ""
    ): String {
        val state = if (custom) "<span class='ok'>✓ свои</span>" else "<span class='no'>$none</span>"
        return "<label>${esc(label)} $state</label>${desc(desc)}<textarea name='$name' spellcheck='false' autocapitalize='off' placeholder='${esc(placeholder)}' style='min-height:150px'>${esc(lines.joinToString("\n"))}</textarea>"
    }

    private fun link(url: String, text: String) = "<a href='$url' target='_blank' rel='noopener'>$text</a>"

    private fun attr(s: String) = esc(s).replace("'", "&#39;").replace("\"", "&quot;")

    /** Полето за търсене: думата отива на телевизора и там се отварят резултатите. */
    private fun searchForm(): String {
        val sb = StringBuilder()
        sb.append("<form method='post' action='/search'>")
        sb.append("<div class='desc'>Напиши името и то се появява на телевизора. Така не се чука с дистанционното.</div>")
        sb.append("<input id='q' name='q' autocomplete='off' autocapitalize='off' spellcheck='false' placeholder='Филм или сериал'>")
        val last = try { recent() } catch (e: Exception) { emptyList() }
        if (last.isNotEmpty()) {
            sb.append("<div class='chips'>")
            for (s in last) sb.append("<a class='chip' href='#' data-q='").append(attr(s)).append("' onclick=\"document.getElementById('q').value=this.getAttribute('data-q');return false\">").append(esc(s)).append("</a>")
            sb.append("</div>")
        }
        sb.append("<label>ПИН от телевизора</label><input name='pin' inputmode='numeric' autocomplete='off' placeholder='4 цифри'>")
        sb.append("<div style='height:12px'></div><button type='submit'>Търси на телевизора</button></form>")
        return sb.toString()
    }

    /** Сгъваема секция. more = текст, който се вижда само след „Покажи повече“. */
    private fun more(summary: String, body: String): String =
        "<details class='more'><summary>$summary</summary><div class='box'>$body</div></details>"

    private fun section(title: String, open: Boolean, body: String): String =
        "<details class='sec'${if (open) " open" else ""}><summary>$title</summary><div class='sbody'>$body</div></details>"

    private fun loginForm(): String =
        "<form method='post' action='/login'>" +
            "<div class='desc'>Номерът и ПИН-ът са от този, който ти даде приложението.</div>" +
            "<label>Номер</label><input name='num' inputmode='numeric' autocomplete='off' placeholder='номер'>" +
            "<label>ПИН</label><input name='spin' type='password' inputmode='numeric' autocomplete='off' placeholder='ПИН на профила'>" +
            "<label>ПИН от телевизора</label><div class='desc'>Виж го на екрана на телевизора.</div><input name='pin' inputmode='numeric' autocomplete='off' placeholder='4 цифри'>" +
            "<div style='height:12px'></div><button type='submit'>Вход</button></form>"

    /** Дистанционно за филма: показва се само докато върви филм и е позволено от настройките. */
    private fun controlBox(): String =
        "<div id='ctl'><div id='ctinfo' class='desc'>Търси се филм…</div>" +
            "<div id='ctbtn' style='display:none'>" +
            "<div class='row3'><button onclick=\"c('seek',-60000)\">−1 мин</button><button onclick=\"c('seek',-10000)\">−10 с</button>" +
            "<button onclick=\"c('toggle',0)\" id='ctpp'>Пауза</button><button onclick=\"c('seek',10000)\">+10 с</button><button onclick=\"c('seek',60000)\">+1 мин</button></div>" +
            "<div class='desc' style='margin-top:12px'>Субтитри <span id='ctoff'></span> (+ закъснява, − избързва)</div>" +
            "<div class='row3'><button onclick=\"c('suboff',-500)\">−0,5</button><button onclick=\"c('suboff',-100)\">−0,1</button>" +
            "<button onclick=\"c('suboff0',0)\">0</button><button onclick=\"c('suboff',100)\">+0,1</button><button onclick=\"c('suboff',500)\">+0,5</button></div>" +
            "<div class='row3' style='margin-top:10px'><button onclick=\"c('subtoggle',0)\" id='ctsub'>Субтитри</button><button class='stop' onclick=\"if(confirm('Да се спре ли филмът?'))c('stop',0)\">Спри филма</button></div></div>" +
            "<div id='ctpinbox' style='display:none'><label>ПИН от телевизора</label><input id='ctpininp' inputmode='numeric' autocomplete='off' placeholder='4 цифри'>" +
            "<div style='height:10px'></div><button type='button' onclick='setPin()'>Свържи</button></div></div>" +
            "<script>(function(){var pin='';try{pin=localStorage.getItem('tvpin')||''}catch(e){}" +
            "var info=document.getElementById('ctinfo'),btn=document.getElementById('ctbtn'),pb=document.getElementById('ctpinbox'),timer=null;" +
            "function post(path,data,cb){var x=new XMLHttpRequest();x.open('POST',path);x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');" +
            "x.onload=function(){try{cb(JSON.parse(x.responseText))}catch(e){}};x.send('pin='+encodeURIComponent(pin)+data)}" +
            "function f(ms){ms=Math.max(0,Math.floor(ms/1000));var h=Math.floor(ms/3600),m=Math.floor(ms%3600/60),s=ms%60;return(h?h+':'+(m<10?'0':''):'')+m+':'+(s<10?'0':'')+s}" +
            "function show(j){if(j.err){stop();btn.style.display='none';pb.style.display='block';info.textContent=j.msg||'Въведи ПИН-а от телевизора.';return}" +
            "pb.style.display='none';if(!j.active){btn.style.display='none';info.textContent='Сега не върви филм.';return}" +
            "btn.style.display='block';info.textContent=j.title+'  '+f(j.pos)+' / '+f(j.dur);" +
            "document.getElementById('ctpp').textContent=j.playing?'Пауза':'Пусни';" +
            "document.getElementById('ctoff').textContent=(j.off>=0?'+':'')+(j.off/1000).toFixed(1)+' с';" +
            "document.getElementById('ctsub').textContent=j.subs?'Изключи субтитрите':'Включи субтитрите'}" +
            "function poll(){if(!pin){return}post('/state','',show)}" +
            "function stop(){if(timer){clearInterval(timer);timer=null}}" +
            "function start(){stop();poll();timer=setInterval(poll,2000)}" +
            "window.c=function(cmd,arg){post('/ctl','&cmd='+cmd+'&arg='+arg,show)};" +
            "window.setPin=function(){var v=document.getElementById('ctpininp').value.trim();if(!v)return;pin=v;try{localStorage.setItem('tvpin',v)}catch(e){}start()};" +
            "if(pin){start()}else{pb.style.display='block';info.textContent='Въведи ПИН-а от телевизора.'}})()</script>"

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
        sb.append(".desc{color:#98a2b8;font-size:13px;margin:0 0 6px}")
        sb.append(".row3{display:flex;gap:6px;margin-top:6px}.row3 button{flex:1;padding:12px 4px;font-size:15px;border-radius:10px}button.stop{background:#3a1620;color:#ff8c98}")
        sb.append(".chips{margin:10px 0 0}.chip{display:inline-block;margin:0 6px 6px 0;padding:6px 12px;border-radius:50px;background:#1d2536;color:#f3f5fa;text-decoration:none;font-size:14px}")
        sb.append("</style></head><body><h1>Torr<b>TV</b></h1><div class='sub'>Настройки от телефона</div>")
        if (msg != null) sb.append("<div class='msg ").append(if (ok) "good" else "bad").append("'>").append(esc(msg)).append("</div>")

        // Скрива ПИН-а на телефона в самия браузър, за да не се пише пак (само в този браузър).
        val pinScript = "<script>try{var p=localStorage.getItem('tvpin');var f=document.querySelectorAll('input[name=pin]');" +
            "if(p){for(var i=0;i<f.length;i++)f[i].value=p}" +
            "var fs=document.querySelectorAll('form');for(var j=0;j<fs.length;j++){fs[j].addEventListener('submit',function(){" +
            "var e=this.querySelector('input[name=pin]');if(e&&e.value)localStorage.setItem('tvpin',e.value)})}}catch(x){}</script>"

        // Приложението е свързано с услугата, но още няма вход: само вход.
        if (serviceOn && !loggedIn()) {
            sb.append(section("Вход", true, loginForm()))
            sb.append(pinScript).append("</body></html>")
            return sb.toString()
        }

        // ---------- Търсене ----------
        sb.append(section("Търсене на филм", true, searchForm()))
        sb.append(section("Дистанционно", true, controlBox()))

        // ---------- Упътване ----------
        sb.append(section("Как се ползва", true,
            "<div class='step'><b>1</b><div>На телевизора отвори <code>Настройки</code> или <code>Търсене</code> - там е ПИН-ът от 4 цифри. Страницата работи само докато единият от двата екрана е отворен.</div></div>" +
                "<div class='step'><b>2</b><div>Попълни само полетата, които искаш да смениш. Празните не се променят.</div></div>" +
                "<div class='step'><b>3</b><div>Напиши ПИН-а най-долу и натисни <code>Запази</code>.</div></div>" +
                more("Ако не стане",
                    "<ul><li>Телефонът и телевизорът трябва да са на една и съща Wi-Fi мрежа.</li>" +
                        "<li>Отвори точно адреса от телевизора. Ако порт 62000 е зает, там ще пише друг порт.</li>" +
                        "<li>Ако пише „Грешен ПИН“, виж го пак на екрана. ПИН-ът е един и същ, докато приложението е пуснато; след пет грешни опита се сменя и за минута не се приема нищо.</li>" +
                        "<li>Всичко се пази само на телевизора. Ключовете се изпращат единствено към самите услуги (TMDB, OpenSubtitles и т.н.).</li></ul>")))

        if (!serviceOn) {
        sb.append("<form method='post' action='/save'>")

        // ---------- Ключове ----------
        sb.append("<details class='sec' open><summary>Ключове</summary><div class='sbody'>")
        sb.append("<p>Описанията и субтитрите идват от външни сайтове и всеки иска свой безплатен ключ. Ако едно поле вече е записано, остави го.</p>")

        sb.append(field("tmdb_key", "TMDB ключ", "От тук идват описанията, плакатите и актьорите. Без него не се зареждат филми и сериали."))
        sb.append(more("Откъде да го взема",
            "<ol><li>Направи безплатен акаунт в ${link("https://www.themoviedb.org/signup", "themoviedb.org")} и потвърди имейла.</li>" +
                "<li>Отвори ${link("https://www.themoviedb.org/settings/api", "Settings → API")} и поискай ключ (<i>Request an API Key</i>, тип <i>Developer</i>).</li>" +
                "<li>Попълни краткия формуляр. За лична употреба е достатъчно, адресът на сайта може да е какъвто и да е.</li>" +
                "<li>Копирай <b>API Key</b> - около 32 знака, букви и цифри.</li></ol>"))

        sb.append(field("os_key", "OpenSubtitles - API ключ", "Ключът на приложението за OpenSubtitles. Взима се от същия акаунт като потребителя и паролата отдолу."))
        sb.append(field("os_user", "OpenSubtitles - потребител", "Името на акаунта ти в opensubtitles.com (не в по-старото opensubtitles.org)."))
        sb.append(field("os_pass", "OpenSubtitles - парола", "Паролата на акаунта. Трябва, за да се свалят субтитрите. Остава само на телевизора."))
        sb.append(more("Откъде да го взема",
            "<ol><li>Направи безплатен акаунт в ${link("https://www.opensubtitles.com", "opensubtitles.com")} и потвърди имейла. Потребителят и паролата са от него.</li>" +
                "<li>Влез, натисни профила си горе вдясно → <b>API consumers</b> → <b>New consumer</b>.</li>" +
                "<li>Име: каквото искаш (например TorrTV). „Allow anonymous“ остави включено, „Under development“ - изключено. Запази.</li>" +
                "<li>Копирай получения <b>API key</b> в полето по-горе.</li></ol>" +
                "<p>Безплатният акаунт има дневен лимит за сваляне. Когато се изчерпи, останалите източници продължават да работят.</p>"))

        sb.append(field("subdl_key", "Subdl - API ключ", "Още един източник на субтитри. Безплатният ключ дава около 2000 заявки на ден."))
        sb.append(more("Откъде да го взема",
            "<ol><li>Регистрирай се и влез в ${link("https://subdl.com/panel/login", "панела на Subdl")}.</li>" +
                "<li>Отвори ${link("https://subdl.com/panel/api", "subdl.com/panel/api")} - там е ключът („Get your free API key“).</li>" +
                "<li>Копирай го в полето. Започва със <code>subdl_</code>.</li></ol>"))

        sb.append(field("subsource_key", "SubSource - API ключ", "Трети източник на субтитри. Колкото повече източници, толкова по-вероятно е да има субтитър за твоята версия на филма."))
        sb.append(more("Откъде да го взема",
            "<ol><li>Регистрирай се в ${link("https://subsource.net", "subsource.net")} и влез.</li>" +
                "<li>В акаунта отвори раздела <b>API</b> („API &amp; Plans“) и създай ключ. Повече има на ${link("https://subsource.net/api-docs", "subsource.net/api-docs")}.</li>" +
                "<li>Копирай ключа в полето.</li></ol>"))
        sb.append("</div></details>")

        // ---------- Уеб сайтове ----------
        sb.append("<details class='sec'><summary>Уеб сайтове за гледане</summary><div class='sbody'>")
        sb.append("<p>Ако торентът няма сийдъри, филмът може да се гледа онлайн от сайт - бутонът „Уеб сайтове“ е в страницата на филма. Тук са адресите, които се пробват: първият е основен, а останалите са резервни и се опитват, ако той не се отвори.</p>")
        sb.append(area("web_movie", "Адреси за филми", WebSources.templates(prefs, false), prefs.webMovieSrc.isNotBlank(),
            desc = "По един адрес на ред. Първият е основен, останалите се пробват поред."))
        sb.append(area("web_tv", "Адреси за сериали", WebSources.templates(prefs, true), prefs.webTvSrc.isNotBlank(),
            desc = "Същото, но за сериали. В адреса влизат и сезонът, и епизодът."))
        sb.append(more("Как се пише адрес",
            "<p>Всеки ред е шаблон - адрес, в който приложението заменя част от текста с данните за избрания филм:</p>" +
                "<ul><li><code>{tmdb}</code> - номерът на филма в TMDB (винаги го има)</li>" +
                "<li><code>{imdb}</code> - номерът в IMDb, напр. <code>tt0133093</code> (ако шаблонът го иска, а филмът няма такъв, редът се прескача)</li>" +
                "<li><code>{season}</code> и <code>{episode}</code> - сезон и епизод (само за сериали)</li></ul>" +
                "<p>Пример за филм: <code>https://пример.com/embed/movie/{tmdb}</code><br>Пример за сериал: <code>https://пример.com/embed/tv/{tmdb}/{season}/{episode}</code></p>" +
                "<ul><li>Редовете, които не започват с <code>http</code> или започват с <code>#</code>, се пропускат - така можеш да си пишеш бележки.</li>" +
                "<li>Ако сайт спре да работи, изтрий реда му. За нов сайт добави ред.</li>" +
                "<li>Напиши само <code>-</code> (тире), за да върнеш стандартните адреси.</li>" +
                "<li>Как изглежда адресът зависи от самия сайт. Виж го при един филм и сложи <code>{tmdb}</code> или <code>{imdb}</code> на мястото на номера.</li>" +
                "<li>Сайтовете избираш ти и ти отговаряш за тях. Приложението само отваря адреса и блокира рекламите.</li></ul>"))
        sb.append("</div></details>")

        // ---------- Торент addon-и ----------
        sb.append("<details class='sec'><summary>Допълнителни торент addon-и</summary><div class='sbody'>")
        sb.append("<p>Вградени са <b>TorrentsDB</b> и <b>Torrentio</b>. Те търсят в десетки сайтове (1337x, TorrentGalaxy, YTS, EZTV…) и в списъка за филтриране се виждат поотделно. Тук можеш да добавиш още.</p>")
        sb.append(area("addons", "Допълнителни addon-и", StremioAddon.parseList(prefs.addonsSrc), prefs.addonsSrc.isNotBlank(), "няма", "https://…/manifest.json",
            "Адреси на Stremio addon-и, по един на ред. Всеки добавя още сайтове за търсене."))
        sb.append(more("Как се добавя addon",
            "<ol><li>Отвори страницата на addon-а (например ${link("https://torrentsdb.com", "torrentsdb.com")}), настрой каквото искаш и стигни до бутона <b>Install</b>.</li>" +
                "<li>Копирай адреса на бутона: на телефон задръж пръст върху <b>Install</b> и избери „Копирай линка“, на компютър - десен клик → <i>Copy link</i>. Прилича на <code>https://…/manifest.json</code> или <code>stremio://…</code>.</li>" +
                "<li>Постави го тук, по един на ред, и натисни <b>Запази</b>. Става и със <code>/manifest.json</code> накрая, и без него.</li></ol>" +
                "<ul><li>Нужни са addon-и, които дават <b>торенти</b> (магнитни линкове). Тези, които връщат само готови линкове от платени услуги (debrid), се пропускат.</li>" +
                "<li>Addon-ите търсят по IMDb номер. Ако филмът няма такъв, не се ползват.</li>" +
                "<li>Ако адресът съдържа лични ключове (някои addon-и ги слагат там), те остават само на телевизора.</li>" +
                "<li>Напиши само <code>-</code>, за да изтриеш всички свои addon-и. Всеки сайт може да се изключи и от Настройки → Торент източници.</li>" +
                "<li>Кой сайт е дал дадения торент се вижда в списъка на филма и може да се филтрира.</li></ul>"))
        sb.append("</div></details>")

        // ---------- Домейни ----------
        sb.append("<details class='sec'><summary>Адреси на торент сайтове</summary><div class='sbody'>")
        sb.append("<p>Торент сайтовете често сменят адреса си. Ако някой спре да дава резултати, сложи новия му домейн тук. Празното поле значи вградения адрес.</p>")
        sb.append(field("dom_apibay", "The Pirate Bay (apibay)", "Домейн за търсене в The Pirate Bay. По подразбиране: apibay.org."))
        sb.append(field("dom_yts", "YTS", "Домейн за филми от YTS. По подразбиране се пробват yts.mx, yts.lt и yts.am."))
        sb.append(field("dom_eztv", "EZTV", "Домейн за сериали от EZTV. По подразбиране се пробват няколко известни адреса."))
        sb.append(field("dom_nyaa", "Nyaa", "Домейн за аниме от Nyaa. По подразбиране: nyaa.si."))
        sb.append(more("Подробности", "<p>Пиши само домейна, например <code>yts.mx</code> (<code>https://</code> не е нужно). За да видиш кой сайт работи, отвори на телевизора Настройки → Диагностика → „Провери източниците“.</p>"))
        sb.append("</div></details>")

        // ---------- Всичко наведнъж ----------
        sb.append("<details class='sec'><summary>Всичко наведнъж</summary><div class='sbody'>")
        sb.append("<p>Задава няколко полета наведнъж. Всеки ред е във вида <code>име=стойност</code>, например <code>tmdb_key=…</code>. Каквото не е споменато, не се променя.</p>")
        sb.append("<textarea name='bulk' spellcheck='false' placeholder='tmdb_key=...&#10;subdl_key=...'></textarea>")
        sb.append(more("Имена на полетата", "<p>" + Prefs.FIELD_NAMES.filter { !it.startsWith("web_") }.joinToString(" · ") { "<code>$it</code>" } + "</p><p>Адресите на уеб сайтовете се сменят само от собствените им полета по-горе.</p>"))
        sb.append("</div></details>")

        sb.append("<div class='savebar'><label style='margin-top:0'>ПИН от телевизора</label><div class='desc'>Четирите цифри от Настройки на телевизора. Така никой друг в мрежата не може да променя нищо.</div><input name='pin' inputmode='numeric' autocomplete='off' placeholder='4 цифри'><div style='height:10px'></div><button type='submit'>Запази</button></div>")
        sb.append("</form>")
        }
        sb.append(pinScript)
        sb.append("</body></html>")
        return sb.toString()
    }
}
