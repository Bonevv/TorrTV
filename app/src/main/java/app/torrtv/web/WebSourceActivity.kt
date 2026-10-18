package app.torrtv.web

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.torrtv.core.AppLog
import app.torrtv.state.Graph
import java.io.ByteArrayInputStream

/**
 * Гледане от уеб сайт: страницата се отваря в WebView, реклами и изскачащи прозорци се блокират, а курсорът се
 * движи с дистанционното. Щом страницата поиска видео поток (m3u8 / mpd / mp4), той се предава на вградения плеър -
 * без реклами, със същите заглавки и с нашите субтитри. Ако потокът не се улови, видеото си върви в самата страница.
 *
 * Отделен екран, който нищо не споделя с торентите: ако нещо тук се счупи, торентите не се пипат.
 */
class WebSourceActivity : AppCompatActivity() {

    private class Cap(val url: String, val headers: Map<String, String>, val playlist: Boolean)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private var web: WebView? = null
    private lateinit var cursor: CursorView
    private lateinit var bar: TextView
    private lateinit var banner: TextView

    private var sources: List<WebSources.Source> = emptyList()
    private var idx = 0
    private var pageUrl: String? = null
    private var pageDone = false
    private var captured: Cap? = null
    private var countdown = 0
    private var handedOff = false
    private var asked = false
    private var hinted = false
    private var pending: WebPlay.Pending? = null
    private var ownBases: Set<String> = emptySet()
    private var blockedCount = 0

    // курсор
    private var cx = 0f
    private var cy = 0f
    private var hovering = false
    private var okLong = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private val density get() = resources.displayMetrics.density

    private val loadTimeout = Runnable {
        if (!pageDone) {
            AppLog.d("web", "${sources[idx].host}: не се зареди за 25 секунди")
            nextSource("не се зареди")
        }
    }
    private val hideCursor = Runnable { cursor.visibility = View.GONE }
    private val tickCountdown = object : Runnable {
        override fun run() {
            if (countdown <= 0) return
            countdown--
            if (countdown == 0) handOff() else {
                showCountdown()
                ui.postDelayed(this, 1000)
            }
        }
    }
    private val mp4Wait = Runnable {
        // Само видео файл (без плейлист) за 8 секунди: най-вероятно е истинското видео.
        val c = captured
        if (c != null && !c.playlist && !handedOff) onCaptured()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = WebPlay.pending
        if (p == null) {
            finish()
            return
        }
        pending = p
        sources = p.sources
        idx = p.start
        ownBases = sources.map { AdBlock.baseOf(it.host) }.toSet()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        val w = try {
            WebView(this)
        } catch (e: Throwable) {
            AppLog.e("web", "няма WebView", e)
            Toast.makeText(this, "Този телевизор няма WebView, затова уеб сайтовете не могат да се отворят.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        web = w
        w.setBackgroundColor(Color.BLACK)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        // WebView не взима фокус: стрелките движат нашия курсор, а не фокуса в страницата.
        w.isFocusable = false
        w.isFocusableInTouchMode = false
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setGeolocationEnabled(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.userAgentString = DESKTOP_UA
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = client
        w.webChromeClient = chrome
        root.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        cursor = CursorView(this)
        val cs = (34 * density).toInt()
        root.addView(cursor, FrameLayout.LayoutParams(cs, cs))
        bar = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(24, 10, 24, 10)
            background = GradientDrawable().apply { cornerRadius = 40f; setColor(0xB3101521.toInt()) }
        }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(28, 24, 0, 0)
        })
        banner = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(36, 18, 36, 18)
            background = GradientDrawable().apply { cornerRadius = 50f; setColor(0xE6101521.toInt()) }
            visibility = View.GONE
        }
        root.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, 0, 0, 48)
        })

        root.post {
            cx = root.width / 2f
            cy = root.height / 2f
            placeCursor()
            pokeCursor()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> chrome.onHideCustomView()
                    countdown > 0 -> {
                        cancelCountdown()
                        showBanner("Остани в страницата. Задръж OK за менюто и избери „Пусни във вградения плеър“.", 5000)
                    }
                    else -> finish()
                }
            }
        })

        load()
        showBanner("Стрелките движат курсора, OK е клик. Задръж OK за меню (друг сайт, вграден плеър). Назад затваря.", 7000)
    }

    // ---------- Зареждане и смяна на сайт ----------

    private fun load() {
        val w = web ?: return
        val src = sources[idx]
        pageDone = false
        captured = null
        pageUrl = null
        asked = false
        hinted = false
        cancelCountdown()
        ui.removeCallbacks(mp4Wait)
        ui.removeCallbacks(loadTimeout)
        ui.postDelayed(loadTimeout, 25_000)
        updateBar("зарежда…")
        AppLog.d("web", "зареждам ${src.host} (${idx + 1}/${sources.size})")
        w.loadUrl(src.url)
    }

    private fun nextSource(why: String) {
        if (sources.size < 2) {
            if (why.isNotBlank()) Toast.makeText(this, "Няма друг сайт. $why", Toast.LENGTH_LONG).show()
            return
        }
        idx = (idx + 1) % sources.size
        Toast.makeText(this, (if (why.isNotBlank()) "$why → " else "") + "сайт ${idx + 1}/${sources.size}: ${sources[idx].host}", Toast.LENGTH_SHORT).show()
        load()
    }

    private fun updateBar(state: String) {
        bar.text = "${sources[idx].host}  (${idx + 1}/${sources.size})  ·  $state"
    }

    // ---------- Клиент на страницата ----------

    /** Грешките от предишен сайт, които идват със закъснение, не бива да прескачат следващия. */
    private fun isCurrent(r: WebResourceRequest): Boolean =
        AdBlock.baseOf(r.url.host) == AdBlock.baseOf(Uri.parse(sources[idx].url).host)

    private val client = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, r: WebResourceRequest): WebResourceResponse? {
            val host = r.url.host
            if (AdBlock.isBlocked(host)) {
                blockedCount++
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            maybeRecord(r)
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, r: WebResourceRequest): Boolean {
            if (!r.isForMainFrame) return false
            val host = r.url.host ?: return true
            val base = AdBlock.baseOf(host)
            // Главната страница не може да бъде пренасочена към чужд сайт (реклами). Други огледала на източниците са позволени.
            val ok = base in ownBases || AdBlock.baseOf(Uri.parse(pageUrl ?: sources[idx].url).host) == base
            if (!ok) AppLog.d("web", "блокирано пренасочване към $host")
            return !ok
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            pageUrl = url
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (url == null || !url.startsWith("http")) return
            pageDone = true
            ui.removeCallbacks(loadTimeout)
            updateBar("готово · блокирани реклами: $blockedCount")
        }

        override fun onReceivedError(view: WebView, r: WebResourceRequest, e: WebResourceError) {
            if (r.isForMainFrame && isCurrent(r)) {
                AppLog.d("web", "${sources[idx].host}: грешка ${e.errorCode} ${e.description}")
                ui.post { nextSource("не се отвори") }
            }
        }

        override fun onReceivedHttpError(view: WebView, r: WebResourceRequest, e: WebResourceResponse) {
            if (r.isForMainFrame && e.statusCode >= 400 && isCurrent(r)) {
                AppLog.d("web", "${sources[idx].host}: HTTP ${e.statusCode}")
                ui.post { nextSource("сайтът върна грешка ${e.statusCode}") }
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            AppLog.d("web", "страницата беше спряна от системата (малко памет)")
            Toast.makeText(this@WebSourceActivity, "Страницата беше спряна от системата (малко памет).", Toast.LENGTH_LONG).show()
            finish()
            return true
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean = false // без изскачащи прозорци

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            result.cancel()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            result.cancel()
            return true
        }

        override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: android.webkit.JsPromptResult): Boolean {
            result.cancel()
            return true
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (!pageDone && newProgress >= 85) {
                // Достатъчно е заредена - не чакаме реклами и проследяване да „довършат“.
                pageDone = true
                ui.removeCallbacks(loadTimeout)
                updateBar("готово · блокирани реклами: $blockedCount")
            } else if (!pageDone) updateBar("зарежда $newProgress%")
        }

        // Видео на цял екран от страницата
        override fun onShowCustomView(v: View, cb: CustomViewCallback) {
            if (customView != null) {
                cb.onCustomViewHidden()
                return
            }
            customView = v
            customCallback = cb
            web?.visibility = View.INVISIBLE
            root.addView(v, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        override fun onHideCustomView() {
            val v = customView ?: return
            root.removeView(v)
            customView = null
            customCallback?.onCustomViewHidden()
            customCallback = null
            web?.visibility = View.VISIBLE
        }
    }

    // ---------- Прихващане на видео поток ----------

    private fun maybeRecord(r: WebResourceRequest) {
        if (handedOff) return
        val url = r.url.toString()
        val kind = mediaKind(url)
        if (kind == 0) return
        val low = url.lowercase()
        if (SKIP_HINTS.any { low.contains(it) }) return
        val h = HashMap<String, String>()
        r.requestHeaders?.forEach { (k, v) ->
            when {
                k.equals("Referer", true) -> h["Referer"] = v
                k.equals("Origin", true) -> h["Origin"] = v
                k.equals("User-Agent", true) -> h["User-Agent"] = v
            }
        }
        val pu = pageUrl
        if (!h.containsKey("Referer") && pu != null) h["Referer"] = pu
        ui.post {
            if (handedOff) return@post
            val cur = captured
            // Плейлистът е по-добър от видео файл; първият плейлист (главният) се пази.
            if (cur != null && (cur.playlist || kind != 1)) return@post
            AppLog.d("web", "поток: ${if (kind == 1) "плейлист" else "mp4"} от ${r.url.host}")
            captured = Cap(url, h, kind == 1)
            pageDone = true
            ui.removeCallbacks(loadTimeout)
            if (kind == 1) onCaptured() else {
                ui.removeCallbacks(mp4Wait)
                ui.postDelayed(mp4Wait, 8000)
            }
        }
    }

    /** Страницата пусна видео: според настройката питаме, прехвърляме сами или само подсказваме. */
    private fun onCaptured() {
        when (Graph.prefs.webHandoff) {
            1 -> startCountdown()
            2 -> if (!hinted) {
                hinted = true
                showBanner("Видеото е намерено. За вградения плеър задръж OK и избери „Пусни във вградения плеър“.", 6000)
            }
            else -> ask()
        }
    }

    private fun ask() {
        if (asked || handedOff) return
        asked = true
        val stay = {
            showBanner("Оставаш на сайта. Когато поискаш, задръж OK и избери „Пусни във вградения плеър“.", 6000)
        }
        AlertDialog.Builder(this)
            .setTitle("Видеото е намерено")
            .setMessage(
                "От ${sources[idx].host}.\n\nДа се пусне ли във вградения плеър? Там няма реклами, има твоите субтитри " +
                    "(с избор и синхрон) и таймер за заспиване."
            )
            .setPositiveButton("Вграден плеър") { _, _ -> handOff() }
            .setNegativeButton("Остани на сайта") { _, _ -> stay() }
            .setOnCancelListener { stay() }
            .show()
    }

    private fun startCountdown() {
        if (countdown > 0 || handedOff) return
        countdown = 4
        showCountdown()
        ui.removeCallbacks(tickCountdown)
        ui.postDelayed(tickCountdown, 1000)
    }

    private fun showCountdown() {
        showBanner("Видеото е намерено. Пуска се във вградения плеър след $countdown с  ·  Назад - остани в страницата", 0)
    }

    private fun cancelCountdown() {
        countdown = 0
        ui.removeCallbacks(tickCountdown)
        banner.visibility = View.GONE
    }

    /** Предава потока на вградения плеър (със субтитри и със заглавките на страницата) и затваря тази страница. */
    private fun handOff() {
        val c = captured
        val p = pending
        if (c == null || p == null || handedOff) {
            Toast.makeText(this, "Още не е намерено видео - пусни го в самата страница.", Toast.LENGTH_LONG).show()
            return
        }
        handedOff = true
        cancelCountdown()
        val headers = HashMap(c.headers)
        val cookie = try {
            CookieManager.getInstance().getCookie(c.url)
        } catch (_: Exception) {
            null
        }
        if (!cookie.isNullOrBlank() && !headers.containsKey("Cookie")) headers["Cookie"] = cookie
        if (!headers.containsKey("User-Agent")) headers["User-Agent"] = DESKTOP_UA
        AppLog.d("web", "предавам на вградения плеър: ${Uri.parse(c.url).host}")
        Graph.stream.playWeb(c.url, headers, p.req, sources[idx].host)
        finish()
    }

    // ---------- Меню (задръж OK) ----------

    private fun showMenu() {
        val items = ArrayList<String>()
        items.add(if (captured != null) "Пусни във вградения плеър" else "Вграден плеър (още няма намерено видео)")
        items.add("Следващ сайт")
        items.add("Презареди")
        sources.forEachIndexed { i, s -> items.add((if (i == idx) "●  " else "○  ") + s.host) }
        items.add("Затвори")
        AlertDialog.Builder(this)
            .setTitle("Уеб сайт")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> handOff()
                    which == 1 -> nextSource("")
                    which == 2 -> load()
                    which == items.size - 1 -> finish()
                    else -> {
                        idx = which - 3
                        load()
                    }
                }
            }
            .show()
    }

    // ---------- Курсор ----------

    private class CursorView(ctx: Context) : View(ctx) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9B44C.toInt() }
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
        override fun onDraw(c: Canvas) {
            val m = width / 2f
            ring.strokeWidth = width / 12f
            c.drawCircle(m, m, m - ring.strokeWidth, shade)
            c.drawCircle(m, m, m - ring.strokeWidth, ring)
            c.drawCircle(m, m, width / 7f, dot)
        }
    }

    private fun placeCursor() {
        cursor.x = cx - cursor.width.coerceAtLeast((34 * density).toInt()) / 2f
        cursor.y = cy - cursor.height.coerceAtLeast((34 * density).toInt()) / 2f
    }

    private fun pokeCursor() {
        cursor.visibility = View.VISIBLE
        ui.removeCallbacks(hideCursor)
        ui.postDelayed(hideCursor, 6000)
    }

    private fun target(): View? = customView ?: web

    private fun moveCursor(code: Int, repeat: Int) {
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) return
        // Колкото по-дълго държиш бутона, толкова по-бързо върви.
        val step = (24 + minOf(repeat, 15) * 8) * maxOf(1f, w / 1280f)
        var nx = cx
        var ny = cy
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> ny -= step
            KeyEvent.KEYCODE_DPAD_DOWN -> ny += step
            KeyEvent.KEYCODE_DPAD_LEFT -> nx -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> nx += step
            else -> return
        }
        // На края на екрана страницата се превърта.
        val wv = web
        if (customView == null && wv != null) {
            if (ny < 0) { wv.scrollBy(0, ny.toInt()); ny = 0f }
            if (ny > h - 1) { wv.scrollBy(0, (ny - (h - 1)).toInt()); ny = (h - 1).toFloat() }
            if (nx < 0) { wv.scrollBy(nx.toInt(), 0); nx = 0f }
            if (nx > w - 1) { wv.scrollBy((nx - (w - 1)).toInt(), 0); nx = (w - 1).toFloat() }
        }
        cx = nx.coerceIn(0f, (w - 1).toFloat())
        cy = ny.coerceIn(0f, (h - 1).toFloat())
        placeCursor()
        hover(cx, cy)
        pokeCursor()
    }

    /** Истинско движение на мишка: плеърите в страницата показват лентата си. */
    private fun hover(x: Float, y: Float) {
        val t = target() ?: return
        val now = SystemClock.uptimeMillis()
        val pp = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val pc = MotionEvent.PointerCoords().apply { this.x = x; this.y = y }
        val ev = MotionEvent.obtain(
            now, now, if (hovering) MotionEvent.ACTION_HOVER_MOVE else MotionEvent.ACTION_HOVER_ENTER,
            1, arrayOf(pp), arrayOf(pc), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        t.dispatchGenericMotionEvent(ev)
        ev.recycle()
        hovering = true
    }

    /** Клик (допир) на мястото на курсора. */
    private fun tapAt(x: Float, y: Float) {
        val t = target() ?: return
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        t.dispatchTouchEvent(down)
        down.recycle()
        t.postDelayed({
            val up = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            t.dispatchTouchEvent(up)
            up.recycle()
        }, 60)
        pokeCursor()
    }

    private fun togglePlayback() {
        web?.evaluateJavascript(TOGGLE_JS) { v ->
            // Ако няма <video> в самата страница (плеърът е в рамка), чукаме в средата.
            if (v != null && v.contains("none")) {
                val t = target()
                if (t != null) tapAt(t.width / 2f, t.height / 2f)
            }
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) moveCursor(code, e.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down) {
                    if (e.repeatCount == 0) okLong = false
                    else if (e.repeatCount == 6 && !okLong) {
                        okLong = true
                        showMenu()
                    }
                } else if (!okLong) {
                    tapAt(cx, cy)
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (down && e.repeatCount == 0) showMenu()
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (down && e.repeatCount == 0) nextSource("")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down && e.repeatCount == 0) togglePlayback()
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun showBanner(text: String, ms: Long) {
        banner.text = text
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        if (ms > 0) ui.postDelayed(hideBanner, ms)
    }

    private val hideBanner = Runnable { if (countdown == 0) banner.visibility = View.GONE }

    override fun onStop() {
        super.onStop()
        web?.onPause()
    }

    override fun onStart() {
        super.onStart()
        web?.onResume()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        val w = web
        web = null
        if (w != null) {
            try {
                w.stopLoading()
                w.loadUrl("about:blank")
                root.removeView(w)
                w.destroy()
            } catch (_: Throwable) {
            }
        }
        try {
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {
        }
        // Затворена без предаване на поток - нищо не е останало отворено в стрийм контролера.
        super.onDestroy()
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        // Пуска или спира първото видео в страницата.
        private const val TOGGLE_JS =
            "(function(){var v=document.querySelector('video');" +
                "if(v){if(v.paused){v.play();}else{v.pause();}return 'ok';}return 'none';})()"

        // Адреси, които приличат на реклами, субтитри или миниатюри, а не на самото видео.
        private val SKIP_HINTS = listOf("/ads/", "/ad/", "preroll", "adserver", "vast", "tracking", "/subtitle", "thumbnail", "/thumb", "sprite", "preview", "trailer")

        /** 1 = плейлист (.m3u8, .mpd), 2 = видео файл (.mp4), 0 = друго. */
        fun mediaKind(url: String): Int {
            var kind = 0
            var i = url.indexOf('.')
            while (i >= 0) {
                if (url.regionMatches(i, ".m3u8", 0, 5, ignoreCase = true) || url.regionMatches(i, ".mpd", 0, 4, ignoreCase = true)) return 1
                if (url.regionMatches(i, ".mp4", 0, 4, ignoreCase = true)) kind = 2
                i = url.indexOf('.', i + 1)
            }
            return kind
        }
    }
}
