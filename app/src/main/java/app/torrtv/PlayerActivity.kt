package app.torrtv

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import app.torrtv.core.AppLog
import app.torrtv.core.endsAtText
import app.torrtv.core.formatSpeed
import app.torrtv.data.subs.Fit
import app.torrtv.data.subs.Langs
import app.torrtv.data.subs.SubCheck
import app.torrtv.data.subs.SubRank
import app.torrtv.data.subs.SubShift
import app.torrtv.data.subs.SubtitleResult
import app.torrtv.state.Graph
import app.torrtv.state.Library
import app.torrtv.torrent.ExternalPlayers
import app.torrtv.torrent.StreamController
import app.torrtv.torrent.LocalSub
import kotlin.concurrent.thread

@androidx.annotation.OptIn(UnstableApi::class)
/** Вграденият плеър: ExoPlayer, който чете от локалния HTTP сървър на торент стрийма. */
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var stats: TextView
    private lateinit var syncBar: LinearLayout
    private lateinit var syncValue: TextView
    private var offsetMs = 0L
    private var pbUrl = ""
    private var req: StreamController.Req? = null
    private var tickN = 0
    private var bufferingSince = 0L
    private lateinit var syncPart: LinearLayout
    private lateinit var sleepBtn: TextView
    private lateinit var subsBtn: TextView
    private lateinit var subWarn: TextView
    private lateinit var nextBtn: TextView
    private lateinit var nextInfo: TextView
    private lateinit var seekInfo: TextView
    private lateinit var popInfo: TextView
    private lateinit var popBtn: TextView
    /** Докога трае „Пауза за пуканки“ (elapsedRealtime); 0 = няма пауза. */
    private var popcornUntil = 0L
    private var swallowKey = -1
    private var visible = false
    /** Кой стрийм е бил пуснат при отварянето на този плеър (виж onDestroy). */
    private var myToken = 0
    private var nextLeft = -1 // секунди до автоматичното пускане на следващия епизод; -1 = не тече
    private var nextStarted = false
    private var seekTarget = -1L // натрупана цел при превъртане с ←/→ (-1 = няма)
    private var lastSeekEvent = 0L
    /** Кога свършва последният ред на всеки зареден субтитър (мс, без синхрона) - за проверката по дължина. */
    private val ends = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val warned = HashSet<String>()
    private var sleepIdx = 0
    private var sleepAt = 0L // кога изтича таймера (elapsedRealtime), 0 = изключен
    private var sleepWarned = false
    private val applyOffset = Runnable { applyOffsetNow() }
    private val applySeek = Runnable { applySeekNow() }
    private val hideSeek = Runnable { seekInfo.visibility = View.GONE }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateStats()
            if (++tickN % 10 == 0) saveProgress()
            checkSleep()
            checkPopcorn()
            updateNext()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pb = Graph.stream.playback
        if (pb == null) {
            finish()
            return
        }
        myToken = Graph.stream.token
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Запомнен синхрон за този филм (вече приложен върху субтитрите от контролера).
        offsetMs = Graph.stream.subOffsetMs
        if (offsetMs != 0L) Toast.makeText(this, String.format("Субтитрите са наместени с %+.1f с (запомнено за този филм)", offsetMs / 1000.0), Toast.LENGTH_LONG).show()

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())
        view = PlayerView(this).apply {
            setShowSubtitleButton(true)
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerShowTimeoutMs = 4000
            keepScreenOn = true
        }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applySubStyle()
        stats = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x99000000.toInt())
            textSize = 12f
            setPadding(px(24), px(12), px(24), px(12))
            visibility = View.GONE
        }
        root.addView(stats, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(px(32), px(32), 0, 0)
        })
        nextInfo = overlayText(14f).apply { gravity = Gravity.CENTER }
        root.addView(nextInfo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, 0, 0, px(150))
        })
        seekInfo = overlayText(22f).apply { gravity = Gravity.CENTER }
        root.addView(seekInfo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        popInfo = overlayText(24f).apply { gravity = Gravity.CENTER }
        root.addView(popInfo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        buildSyncBar()
        root.addView(syncBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, px(32), px(32), 0)
        })
        setContentView(root)
        hideBars()

        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
            .setTargetBufferBytes(48 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)
        val builder = ExoPlayer.Builder(this, renderers).setLoadControl(load)
        if (pb.headers.isNotEmpty()) {
            // Поток от уеб страница: тегли се със същите заглавки (Referer, Origin, бисквитки), както от самата страница.
            val props = HashMap(pb.headers)
            val ua = props.remove("User-Agent")
            val http = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)
                .setDefaultRequestProperties(props)
            if (ua != null) http.setUserAgent(ua)
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
        }
        val p = builder.build()
        player = p
        view.player = p

        pbUrl = pb.url
        req = Graph.stream.ui.req
        val item = buildItem(pb.url, pb.subs, 0)
        var tsp = p.trackSelectionParameters.buildUpon()
        audioPref()?.let { tsp = tsp.setPreferredAudioLanguage(it) }
        if (pb.subs.isNotEmpty()) {
            tsp = tsp.setPreferredTextLanguage(pb.subs[0].lang).setSelectUndeterminedTextLanguage(true)
        }
        p.trackSelectionParameters = tsp.build()
        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                refreshWarn()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) checkSubs()
                if (state == Player.STATE_ENDED) {
                    // Изгледано до края: отбелязва се като гледано, а за сериал в "Продължи гледане" остава следващият епизод.
                    Graph.stream.resumeFrom = 0L
                    req?.let { Library.completed(it) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                showPlayError(error)
            }
        })
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
            updateStats()
            val shown = vis == View.VISIBLE
            val hasSubs = Graph.stream.playback?.subs?.isNotEmpty() == true
            if (!shown && syncBar.hasFocus()) view.requestFocus()
            syncPart.visibility = if (hasSubs) View.VISIBLE else View.GONE
            subsBtn.visibility = if (req?.allSubs?.isNotEmpty() == true) View.VISIBLE else View.GONE
            nextBtn.visibility = if (req?.next != null) View.VISIBLE else View.GONE
            syncBar.visibility = if (shown) View.VISIBLE else View.GONE
        })
        // Продължаване от запазеното място (с 5 секунди назад, за да се влезе в сцената).
        val from = Graph.stream.resumeFrom
        if (from > 10_000) {
            p.setMediaItem(item, (from - 5_000).coerceAtLeast(0))
            Toast.makeText(this, "Продължава от ${Library.fmt(from)}", Toast.LENGTH_SHORT).show()
        } else {
            p.setMediaItem(item)
        }
        p.prepare()
        p.playWhenReady = true
        handler.post(tick)
    }


    // ---- Грешка във вградения плеър ----

    private var errorShown = false

    /** Потокът е предаден на външен плеър: той още чете от нашия сървър, затова при излизане не се затваря. */
    private var handedOff = false

    /** Казва каква е вероятната причина и предлага външен плеър, ако на устройството има такъв. */
    private fun showPlayError(e: PlaybackException) {
        if (isFinishing || isDestroyed || errorShown) return
        errorShown = true
        AppLog.d("player", "грешка в плеъра: ${e.errorCodeName}")
        // Кодове 3xxx-5xxx: файлът или кодекът (най-често звукът); 2xxx: връзката.
        val codec = e.errorCode in 3000..5999
        val ext = ExternalPlayers.installed(this)
        val text = if (codec) {
            "Вграденият плеър не може да пусне този файл. Най-често причината е звукът (DTS или TrueHD). " +
                if (ext.isNotEmpty()) "Във външен плеър обикновено работи."
                else "На това устройство няма външен плеър. VLC или MX Player се вземат от магазина за приложения."
        } else {
            "Възпроизвеждането спря. Провери връзката и опитай пак." +
                if (ext.isNotEmpty()) " Може да пробваш и външен плеър." else ""
        }
        val b = AlertDialog.Builder(this)
            .setTitle("Видеото не се пуска")
            .setMessage(text)
            .setNegativeButton("Затвори") { _, _ -> finish() }
            .setNeutralButton("Опитай пак") { _, _ ->
                errorShown = false
                player?.prepare()
            }
            .setOnCancelListener { errorShown = false }
        if (ext.isNotEmpty()) b.setPositiveButton("Външен плеър") { _, _ -> chooseExternal(ext) }
        b.show()
    }

    private fun chooseExternal(list: List<ExternalPlayers.P>) {
        if (list.size == 1) {
            openExternal(list[0])
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Избери плеър")
            .setItems(list.map { it.label }.toTypedArray()) { _, i -> openExternal(list[i]) }
            .setOnCancelListener { errorShown = false }
            .show()
    }

    /** Подава същия поток на външния плеър, от мястото докъдето е стигнато, и затваря вградения плеър. */
    private fun openExternal(p: ExternalPlayers.P) {
        val pb = Graph.stream.playback
        val pos = (player?.currentPosition ?: 0L).coerceAtLeast(0L)
        val ok = pb != null && try {
            ExternalPlayers.launch(this, p.id, pb, if (pos > 10_000) pos else 0L)
        } catch (e: Exception) {
            false
        }
        if (ok) {
            Graph.stream.resumeFrom = pos
            handedOff = true
            finish()
        } else {
            errorShown = false
            Toast.makeText(this, "${p.label} не се отвори.", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildItem(url: String, subsList: List<LocalSub>, defaultIdx: Int): MediaItem {
        val subs = subsList.mapIndexed { i, s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                .setMimeType(
                    when {
                        s.mime.contains("vtt") -> MimeTypes.TEXT_VTT
                        s.mime.contains("ssa") -> MimeTypes.TEXT_SSA
                        else -> MimeTypes.APPLICATION_SUBRIP
                    }
                )
                .setLanguage(s.lang)
                .setLabel(s.label)
                .setSelectionFlags(if (i == defaultIdx) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val b = MediaItem.Builder().setUri(Uri.parse(url)).setSubtitleConfigurations(subs)
        val low = url.lowercase()
        if (low.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        else if (low.contains(".mpd")) b.setMimeType(MimeTypes.APPLICATION_MPD)
        return b.build()
    }

    // ---- Външен вид на субтитрите и език на звука (от Настройки) ----

    /** Размер, оформление и позиция на субтитрите. */
    private fun applySubStyle() {
        val sv = view.subtitleView ?: return
        val pr = Graph.prefs
        val size = floatArrayOf(0.8f, 1f, 1.25f, 1.5f)[pr.subSize]
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * size)
        sv.setApplyEmbeddedFontSizes(false)
        sv.setBottomPaddingFraction(floatArrayOf(0.04f, SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION, 0.16f)[pr.subLift])
        when (pr.subEdge) {
            1 -> sv.setStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
            2 -> sv.setStyle(CaptionStyleCompat(Color.WHITE, 0xB3000000.toInt(), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, null))
            3 -> sv.setStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, null))
            else -> sv.setUserDefaultStyle()
        }
    }

    /** Предпочитан език на звука или null (както е във файла). "orig" е оригиналният език на филма от TMDB. */
    private fun audioPref(): String? = when (val a = Graph.prefs.audioLang) {
        "bg", "en" -> a
        "orig" -> Graph.stream.ui.req?.media?.originalLanguage?.takeIf { it.isNotBlank() }
        else -> null
    }

    /**
     * Размерите на наслагванията (отстъпи, ъгли) са зададени за екран с плътност 2 (1080p телевизор); тук се
     * пренасят към действителната плътност, за да остане съотношението към текста (който е в sp) на всеки телевизор.
     */
    private fun px(v: Int): Int = (v * resources.displayMetrics.density / 2f).toInt()

    private fun overlayText(sp: Float): TextView = TextView(this).apply {
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xB3000000.toInt())
        textSize = sp
        setPadding(px(32), px(16), px(32), px(16))
        visibility = View.GONE
    }

    // ---- Следващ епизод ----

    /** Пуска следващия епизод и затваря плеъра; потокът не се затваря, а се сменя (виж StreamController.playNext). */
    private fun goNext() {
        if (nextStarted) return
        nextStarted = true
        saveProgress()
        if (Graph.stream.playNext()) finish() else nextStarted = false
    }

    /** Когато епизодът свърши: обратно броене и автоматично пускане на следващия (ако е позволено в Настройки). */
    private fun updateNext() {
        val n = req?.next
        val p = player
        if (n == null || p == null || p.playbackState != Player.STATE_ENDED || !Graph.prefs.autoNext) {
            if (nextLeft >= 0) nextLeft = -1
            if (nextInfo.visibility != View.GONE) nextInfo.visibility = View.GONE
            return
        }
        if (nextLeft < 0) nextLeft = 6
        nextInfo.text = "Следва ${n.label}\nПуска се след $nextLeft с  ·  Назад за отказ"
        nextInfo.visibility = View.VISIBLE
        if (nextLeft == 0) goNext() else nextLeft--
    }

    // ---- Превъртане с ←/→ при скрити контроли ----

    /**
     * При скрити контроли ←/→ превърта директно: натискане = 10 с, задържане = все по-бързо.
     * Реалното превъртане става чак след кратка пауза, за да не се препуска потокът на всяка стъпка.
     * Когато контролите се виждат, всичко си работи както досега.
     */
    private fun handleSeekKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false
        val p = player ?: return false
        if (view.isControllerFullyVisible || syncBar.hasFocus()) return false
        if (p.duration == C.TIME_UNSET || p.duration <= 0 || !p.isCurrentMediaItemSeekable) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true // отпускането се поглъща, иначе показва контролите
        val dir = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
        val stepMs = if (event.repeatCount == 0) {
            10_000L
        } else {
            val held = event.eventTime - event.downTime
            val rate = when { // секунди филм за секунда задържане
                held < 1_500 -> 30
                held < 3_000 -> 60
                held < 5_000 -> 120
                else -> 240
            }
            val dt = (event.eventTime - lastSeekEvent).coerceIn(0L, 200L)
            rate * dt
        }
        lastSeekEvent = event.eventTime
        val base = if (seekTarget >= 0) seekTarget else p.currentPosition
        seekTarget = (base + dir * stepMs).coerceIn(0L, (p.duration - 2_000).coerceAtLeast(0L))
        seekInfo.text = (if (dir > 0) "⏩  " else "⏪  ") + Library.fmt(seekTarget) + " / " + Library.fmt(p.duration)
        seekInfo.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeek)
        handler.removeCallbacks(applySeek)
        handler.postDelayed(applySeek, 400)
        return true
    }

    private fun applySeekNow() {
        val t = seekTarget
        seekTarget = -1L
        if (t >= 0) player?.seekTo(t)
        handler.removeCallbacks(hideSeek)
        handler.postDelayed(hideSeek, 1_200)
    }

    // ---- Синхрон на субтитрите ----

    private fun pillBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = px(60).toFloat()
        setColor(if (focused) 0xFFFFFFFF.toInt() else 0xB3101521.toInt())
    }

    private fun pill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setPadding(px(26), px(12), px(26), px(12))
        background = pillBg(false)
        isFocusable = true
        setOnClickListener {
            onClick()
            view.showController()
        }
        setOnFocusChangeListener { v, f ->
            v.background = pillBg(f)
            (v as TextView).setTextColor(if (f) 0xFF080B12.toInt() else 0xFFFFFFFF.toInt())
            if (f) view.showController()
        }
    }

    private fun syncButton(label: String, delta: Long): TextView = pill(label) { changeOffset(delta) }

    private fun buildSyncBar() {
        syncBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            visibility = View.GONE
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Следващ епизод (само за сериал, пуснат от торент).
        nextBtn = pill("Следващ епизод") { goNext() }
        nextBtn.visibility = View.GONE
        topRow.addView(nextBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = px(16)
        })
        // Избор на друг субтитър от всички намерени.
        subsBtn = pill("Субтитри") { openSubPicker() }
        subsBtn.visibility = View.GONE
        topRow.addView(subsBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = px(16)
        })
        // Пауза за пуканки: 5 минути, после филмът продължава сам (или по-рано с произволен бутон).
        popBtn = pill("Пауза за пуканки") { startPopcorn() }
        topRow.addView(popBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = px(16)
        })
        // Таймер за заспиване: натискане сменя 30 → 60 → 90 → 120 мин → изключен.
        sleepBtn = pill("") { cycleSleep() }
        showSleep()
        topRow.addView(sleepBtn)
        syncBar.addView(topRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = px(10)
        })
        // Предупреждение, когато субтитрите не пасват по дължина на филма.
        subWarn = TextView(this).apply {
            setTextColor(0xFFFFB020.toInt())
            textSize = 12f
            gravity = Gravity.END
            maxWidth = (resources.displayMetrics.widthPixels * 0.55f).toInt()
            setPadding(0, 0, 0, px(10))
            visibility = View.GONE
        }
        syncBar.addView(subWarn)
        // Синхронът на субтитрите се вижда само ако има субтитри.
        syncPart = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Синхрон  "
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
        }
        syncValue = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFE9B44C.toInt())
            setPadding(px(24), 0, px(24), 0)
        }
        row.addView(title)
        row.addView(syncButton("−0,5", -500))
        row.addView(syncButton("−0,1", -100))
        row.addView(syncValue)
        row.addView(syncButton("+0,1", 100))
        row.addView(syncButton("+0,5", 500))
        val hint = TextView(this).apply {
            text = "+ закъснява субтитрите, − ги избързва. Запомня се за този филм."
            textSize = 11f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, px(6), 0, 0)
        }
        syncPart.addView(row)
        syncPart.addView(hint)
        syncBar.addView(syncPart)
        showOffset()
    }

    // ---- Пауза за пуканки ----

    private val popcornMs = 5 * 60_000L

    private fun startPopcorn() {
        val p = player ?: return
        p.pause()
        popcornUntil = SystemClock.elapsedRealtime() + popcornMs
        showPopcorn()
        // След клика на бутона лентата с контролите се показва пак - махаме я, за да остане само надписът.
        handler.post { view.hideController() }
    }

    private fun showPopcorn() {
        val left = ((popcornUntil - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)
        popInfo.text = "Пауза за пуканки\nФилмът продължава след ${left / 60}:${String.format("%02d", left % 60)}\nНатисни произволен бутон, за да продължи веднага"
        popInfo.visibility = View.VISIBLE
    }

    /** Приключва паузата. [resume] - дали филмът да продължи (не, ако приложението е излязло от екрана). */
    private fun endPopcorn(resume: Boolean) {
        popcornUntil = 0L
        popInfo.visibility = View.GONE
        if (resume) player?.play()
    }

    private fun checkPopcorn() {
        if (popcornUntil == 0L) return
        if (SystemClock.elapsedRealtime() >= popcornUntil) endPopcorn(visible) else showPopcorn()
    }

    // ---- Таймер за заспиване ----

    private val sleepSteps = intArrayOf(0, 30, 60, 90, 120)

    private fun cycleSleep() {
        sleepIdx = (sleepIdx + 1) % sleepSteps.size
        val m = sleepSteps[sleepIdx]
        sleepAt = if (m == 0) 0L else SystemClock.elapsedRealtime() + m * 60_000L
        sleepWarned = false
        showSleep()
        Toast.makeText(
            this,
            if (m == 0) "Таймерът за заспиване е изключен" else "Ще спре след $m мин",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showSleep() {
        if (sleepAt == 0L) {
            sleepBtn.text = "Таймер за заспиване: изключен"
            return
        }
        val left = ((sleepAt - SystemClock.elapsedRealtime()) / 60_000L + 1).coerceAtLeast(0)
        sleepBtn.text = "Спира след $left мин"
    }

    private fun checkSleep() {
        if (sleepAt == 0L) return
        val left = sleepAt - SystemClock.elapsedRealtime()
        if (left <= 0) {
            // Времето изтече: пауза, запис на мястото и излизане от приложението, за да може телевизорът да заспи.
            saveProgress()
            player?.pause()
            Toast.makeText(this, "Лека нощ", Toast.LENGTH_LONG).show()
            finishAffinity()
            return
        }
        if (left <= 60_000L && !sleepWarned) {
            sleepWarned = true
            Toast.makeText(this, "Спира след 1 минута. Натисни произволен бутон, за да продължи.", Toast.LENGTH_LONG).show()
        }
        if (tickN % 15 == 0 || left <= 60_000L) showSleep()
    }

    /** Всеки бутон в последната минута удължава таймера с 30 минути. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Докато трае паузата за пуканки, първият бутон само я прекратява (и не прави нищо друго).
        if (popcornUntil != 0L && event.action == KeyEvent.ACTION_DOWN) {
            swallowKey = event.keyCode
            endPopcorn(true)
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowKey) {
            swallowKey = -1
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && sleepAt != 0L && event.repeatCount == 0) {
            val left = sleepAt - SystemClock.elapsedRealtime()
            if (left in 1..60_000L) {
                sleepAt = SystemClock.elapsedRealtime() + 30 * 60_000L
                sleepWarned = false
                showSleep()
                Toast.makeText(this, "Таймерът е удължен с 30 минути", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT && req?.next != null) {
            goNext()
            return true
        }
        if (handleSeekKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun showOffset() {
        syncValue.text = String.format("%+.1f с", offsetMs / 1000.0)
    }

    private fun changeOffset(delta: Long) {
        offsetMs = (offsetMs + delta).coerceIn(-30_000L, 30_000L)
        showOffset()
        // Няколко натискания подред се събират в едно презареждане.
        handler.removeCallbacks(applyOffset)
        handler.postDelayed(applyOffset, 700)
    }

    private fun applyOffsetNow() {
        val ms = offsetMs
        val uid = selectedSub()?.uid
        thread(isDaemon = true) {
            val subs = try {
                Graph.stream.shiftSubs(ms)
            } catch (e: Exception) {
                emptyList()
            }
            // Следващия път със същия субтитър за този филм синхронът се прилага сам.
            if (uid != null) Graph.stream.rememberOffset(uid, ms)
            runOnUiThread { reloadSubs(subs) }
        }
    }

    // ---- Избор на субтитър и проверка по дължина ----

    /** Кой от заредените субтитри е включен сега (null - субтитрите са изключени). */
    private fun selectedSub(): LocalSub? {
        val p = player ?: return null
        val subs = Graph.stream.playback?.subs ?: return null
        var label: String? = null
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) if (g.isTrackSelected(i)) label = g.getTrackFormat(i).label
        }
        return subs.firstOrNull { it.label == label }
    }

    /** Чете докъде стигат субтитрите (на заден план) и после проверява избрания. */
    private fun checkSubs() {
        val subs = Graph.stream.playback?.subs ?: return
        val todo = subs.filter { !ends.containsKey(it.uid) }
        if (todo.isEmpty()) {
            refreshWarn()
            return
        }
        thread(isDaemon = true) {
            for (s in todo) {
                ends[s.uid] = try {
                    SubShift.lastEndMs(s.file.readText(), s.file.extension)
                } catch (e: Exception) {
                    0L
                }
            }
            runOnUiThread { refreshWarn() }
        }
    }

    private fun refreshWarn() {
        val p = player
        val sub = selectedSub()
        val dur = p?.duration ?: C.TIME_UNSET
        val end = if (sub != null) ends[sub.uid] else null
        if (sub == null || end == null || dur == C.TIME_UNSET || dur <= 0) {
            subWarn.visibility = View.GONE
            return
        }
        val res = SubCheck.check(end + offsetMs, dur)
        val msg = res.message()
        if (msg.isEmpty()) {
            subWarn.visibility = View.GONE
            return
        }
        subWarn.text = "$msg\nМоже да са за друга версия. Пробвай други от „Субтитри“ или ги намести със „Синхрон“."
        subWarn.visibility = View.VISIBLE
        if (warned.add(sub.uid)) {
            Toast.makeText(this, "$msg Може да са за друга версия - пробвай други от „Субтитри“.", Toast.LENGTH_LONG).show()
        }
    }

    private fun subLine(s: SubtitleResult, fitTitle: String?, loaded: Boolean): String {
        val fit = if (fitTitle == null) "" else when (SubRank.fit(s, fitTitle)) {
            Fit.EXACT -> "✓ "
            Fit.SIMILAR -> "~ "
            Fit.OTHER -> "✗ "
            Fit.UNKNOWN -> "? "
        }
        val tags = ArrayList<String>()
        if (s.trusted) tags.add("доверен")
        if (s.hearing) tags.add("за глухи")
        if (loaded) tags.add("зареден")
        val rel = s.release.ifBlank { "(без име на издание)" }.take(70)
        return Langs.short(s.lang) + "   " + fit + rel + if (tags.isEmpty()) "" else "   [" + tags.joinToString(", ") + "]"
    }

    private fun openSubPicker() {
        val r = req
        val all = r?.allSubs.orEmpty()
        if (r == null || all.isEmpty()) {
            Toast.makeText(this, "Няма намерени субтитри за този филм.", Toast.LENGTH_LONG).show()
            return
        }
        val loaded = Graph.stream.playback?.subs.orEmpty()
        val cur = selectedSub()?.uid
        val items = all.map { subLine(it, r.fitTitle, loaded.any { l -> l.uid == it.uid }) }.toTypedArray()
        val title = if (r.fitTitle != null) "Субтитри   (✓ точно издание · ~ подобно · ✗ друго)" else "Субтитри"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(items, all.indexOfFirst { it.uid == cur }) { d, which ->
                d.dismiss()
                chooseSub(all[which])
            }
            .setNegativeButton("Затвори", null)
            .show()
    }

    /** Сваля избрания субтитър, прилага запомнения му синхрон и презарежда видеото от същото място. */
    private fun chooseSub(r: SubtitleResult) {
        Toast.makeText(this, "Сваляне на субтитрите…", Toast.LENGTH_SHORT).show()
        thread(isDaemon = true) {
            val added = try {
                Graph.stream.addSub(r)
            } catch (e: Exception) {
                null
            }
            if (added == null) {
                runOnUiThread { Toast.makeText(this, "Субтитрите не се свалиха" + Graph.stream.lastSubError.let { if (it.isBlank()) "" else " ($it)" } + ". Пробвай други.", Toast.LENGTH_LONG).show() }
                return@thread
            }
            Graph.stream.rememberSubChoice(r)
            val off = Graph.stream.recallOffset(r.uid)
            val list = if (off != Graph.stream.subOffsetMs) Graph.stream.shiftSubs(off) else added
            runOnUiThread {
                offsetMs = off
                showOffset()
                reloadSubs(list, r.uid)
                Toast.makeText(this, "Субтитри: " + (list.firstOrNull { it.uid == r.uid }?.label ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Презарежда видеото със същите субтитри, но с новото изместване - от същото място. */
    private fun reloadSubs(subs: List<LocalSub>, pickUid: String? = null) {
        val p = player ?: return
        if (subs.isEmpty() || isFinishing) return
        val pos = p.currentPosition
        val play = p.playWhenReady
        // Кой субтитър е включен сега - него запазваме.
        var label: String? = null
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) if (g.isTrackSelected(i)) label = g.getTrackFormat(i).label
        }
        val idx = (if (pickUid != null) subs.indexOfFirst { it.uid == pickUid } else subs.indexOfFirst { it.label == label })
            .let { if (it < 0) 0 else it }
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(subs[idx].lang)
            .build()
        p.setMediaItem(buildItem(pbUrl, subs, idx), pos)
        p.prepare()
        p.playWhenReady = play
    }

    /** Записва докъде е стигнато гледането (за "Продължи гледане"). */
    private fun saveProgress() {
        val p = player ?: return
        val r = req ?: return
        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) return
        val pos = p.currentPosition
        val dur = if (p.duration != C.TIME_UNSET && p.duration > 0) p.duration else 0L
        Graph.stream.resumeFrom = pos
        Library.saveProgress(r, pos, dur)
    }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun updateStats() {
        val p = player ?: return
        val buffering = p.playbackState == Player.STATE_BUFFERING
        val now = SystemClock.elapsedRealtime()
        if (!buffering) bufferingSince = 0L else if (bufferingSince == 0L) bufferingSince = now
        if (!view.isControllerFullyVisible && !buffering) {
            stats.visibility = View.GONE
            return
        }
        // Кога ще свърши филмът (само докато се вижда лентата с контролите).
        val endsLine = if (p.duration != C.TIME_UNSET && p.duration > 0 && view.isControllerFullyVisible)
            endsAtText(System.currentTimeMillis(), p.duration - p.currentPosition) else ""
        val web = Graph.stream.playback?.webHost.orEmpty()
        if (web.isNotEmpty()) {
            // Поток от уеб сайт: няма пиъри и сийдъри - показваме откъде идва.
            stats.text = "Сайт: $web" + (if (buffering) " · буферира…" else "") + (if (endsLine.isNotEmpty()) "\n$endsLine" else "")
            stats.visibility = View.VISIBLE
            return
        }
        val s = Graph.stream.stats()
        if (s == null) {
            if (endsLine.isNotEmpty()) {
                stats.text = endsLine
                stats.visibility = View.VISIBLE
            } else stats.visibility = View.GONE
            return
        }
        var t = "Пиъри ${s.peers} · сийдъри ${s.seeds} · ↓ ${formatSpeed(s.speed)}"
        // Дълго буфериране при малко сийдъри: подсказка какво да се направи.
        if (buffering && now - bufferingSince > 10_000 && (s.seeds < 3 || s.speed < 150_000)) {
            t += "\nТози торент има малко сийдъри. Натисни Назад и избери друг торент или сайт."
        }
        if (endsLine.isNotEmpty()) t += "\n$endsLine"
        stats.text = t
        stats.visibility = View.VISIBLE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Бутоните за пауза/възпроизвеждане на дистанционното
        val p = player
        if (p != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (p.isPlaying) p.pause() else p.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { p.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { p.seekTo(p.currentPosition + 30_000); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { p.seekTo((p.currentPosition - 15_000).coerceAtLeast(0)); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        super.onStop()
        visible = false
        saveProgress()
        player?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(applyOffset)
        handler.removeCallbacks(applySeek)
        handler.removeCallbacks(hideSeek)
        player?.release()
        player = null
        super.onDestroy()
        // Потокът се затваря само ако не е сменен със следващия епизод (или вече не е пуснат нов).
        if (isFinishing && !handedOff && !Graph.stream.switching && Graph.stream.token == myToken) Graph.stream.close()
    }
}
