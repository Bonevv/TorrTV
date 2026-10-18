package app.torrtv

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.media3.ui.PlayerView
import app.torrtv.core.formatSpeed
import app.torrtv.data.subs.Fit
import app.torrtv.data.subs.Langs
import app.torrtv.data.subs.SubCheck
import app.torrtv.data.subs.SubRank
import app.torrtv.data.subs.SubShift
import app.torrtv.data.subs.SubtitleResult
import app.torrtv.state.Graph
import app.torrtv.state.Library
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
    /** Кога свършва последният ред на всеки зареден субтитър (мс, без синхрона) - за проверката по дължина. */
    private val ends = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val warned = HashSet<String>()
    private var sleepIdx = 0
    private var sleepAt = 0L // кога изтича таймера (elapsedRealtime), 0 = изключен
    private var sleepWarned = false
    private val applyOffset = Runnable { applyOffsetNow() }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateStats()
            if (++tickN % 10 == 0) saveProgress()
            checkSleep()
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Запомнен синхрон за този филм (вече приложен върху субтитрите от контролера).
        offsetMs = Graph.stream.subOffsetMs
        if (offsetMs != 0L) Toast.makeText(this, String.format("Синхрон на субтитрите %+.1f с (запомнен за този филм)", offsetMs / 1000.0), Toast.LENGTH_LONG).show()

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())
        view = PlayerView(this).apply {
            setShowSubtitleButton(true)
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerShowTimeoutMs = 4000
            keepScreenOn = true
        }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stats = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x99000000.toInt())
            textSize = 12f
            setPadding(24, 12, 24, 12)
            visibility = View.GONE
        }
        root.addView(stats, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(32, 32, 0, 0)
        })
        buildSyncBar()
        root.addView(syncBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, 32, 32, 0)
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
        if (pb.subs.isNotEmpty()) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage(pb.subs[0].lang)
                .setSelectUndeterminedTextLanguage(true)
                .build()
        }
        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                refreshWarn()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) checkSubs()
                if (state == Player.STATE_ENDED) {
                    // Изгледано до края - вече не е за "Продължи гледане".
                    Graph.stream.resumeFrom = 0L
                    req?.media?.let { Library.finish(it.key) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Грешка при възпроизвеждане: ${error.errorCodeName}. Ако е звук (DTS/AC3), пусни с VLC / MX / Just Player.",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
            updateStats()
            val shown = vis == View.VISIBLE
            val hasSubs = Graph.stream.playback?.subs?.isNotEmpty() == true
            if (!shown && syncBar.hasFocus()) view.requestFocus()
            syncPart.visibility = if (hasSubs) View.VISIBLE else View.GONE
            subsBtn.visibility = if (req?.allSubs?.isNotEmpty() == true) View.VISIBLE else View.GONE
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

    // ---- Синхрон на субтитрите ----

    private fun pillBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = 60f
        setColor(if (focused) 0xFFFFFFFF.toInt() else 0xB3101521.toInt())
    }

    private fun pill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setPadding(26, 12, 26, 12)
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
        // Избор на друг субтитър от всички намерени.
        subsBtn = pill("💬  Субтитри") { openSubPicker() }
        subsBtn.visibility = View.GONE
        topRow.addView(subsBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = 16
        })
        // Таймер за заспиване: натискане сменя 30 → 60 → 90 → 120 мин → изключен.
        sleepBtn = pill("") { cycleSleep() }
        showSleep()
        topRow.addView(sleepBtn)
        syncBar.addView(topRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 10
        })
        // Предупреждение, когато субтитрите не пасват по дължина на филма.
        subWarn = TextView(this).apply {
            setTextColor(0xFFFFB020.toInt())
            textSize = 12f
            gravity = Gravity.END
            maxWidth = (resources.displayMetrics.widthPixels * 0.55f).toInt()
            setPadding(0, 0, 0, 10)
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
            text = "💬 Синхрон  "
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
        }
        syncValue = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFE9B44C.toInt())
            setPadding(24, 0, 24, 0)
        }
        row.addView(title)
        row.addView(syncButton("−0,5", -500))
        row.addView(syncButton("−0,1", -100))
        row.addView(syncValue)
        row.addView(syncButton("+0,1", 100))
        row.addView(syncButton("+0,5", 500))
        val hint = TextView(this).apply {
            text = "+ = субтитрите излизат по-късно · − = по-рано · запомня се за този филм"
            textSize = 11f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, 6, 0, 0)
        }
        syncPart.addView(row)
        syncPart.addView(hint)
        syncBar.addView(syncPart)
        showOffset()
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
            sleepBtn.text = "⏾  Таймер за заспиване: изкл."
            return
        }
        val left = ((sleepAt - SystemClock.elapsedRealtime()) / 60_000L + 1).coerceAtLeast(0)
        sleepBtn.text = "⏾  Спира след $left мин"
    }

    private fun checkSleep() {
        if (sleepAt == 0L) return
        val left = sleepAt - SystemClock.elapsedRealtime()
        if (left <= 0) {
            // Времето изтече: пауза, запис на мястото и излизане от приложението, за да може телевизорът да заспи.
            saveProgress()
            player?.pause()
            Toast.makeText(this, "Лека нощ 🌙", Toast.LENGTH_LONG).show()
            finishAffinity()
            return
        }
        if (left <= 60_000L && !sleepWarned) {
            sleepWarned = true
            Toast.makeText(this, "Спира след 1 минута - натисни който и да е бутон, за да продължи", Toast.LENGTH_LONG).show()
        }
        if (tickN % 15 == 0 || left <= 60_000L) showSleep()
    }

    /** Всеки бутон в последната минута удължава таймера с 30 минути. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
        subWarn.text = "⚠ $msg\nМоже да се разминават - пробвай друг от „💬 Субтитри“ или ползвай синхрона."
        subWarn.visibility = View.VISIBLE
        if (warned.add(sub.uid)) {
            Toast.makeText(this, "⚠ $msg Пробвай друг субтитър от „💬 Субтитри“.", Toast.LENGTH_LONG).show()
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
        Toast.makeText(this, "Свалям субтитъра…", Toast.LENGTH_SHORT).show()
        thread(isDaemon = true) {
            val added = try {
                Graph.stream.addSub(r)
            } catch (e: Exception) {
                null
            }
            if (added == null) {
                runOnUiThread { Toast.makeText(this, "Не успях да сваля този субтитър. Пробвай друг.", Toast.LENGTH_LONG).show() }
                return@thread
            }
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
        val web = Graph.stream.playback?.webHost.orEmpty()
        if (web.isNotEmpty()) {
            // Поток от уеб сайт: няма пиъри и сийдъри - показваме откъде идва.
            stats.text = "🌐 уеб: $web" + if (buffering) " · буферира…" else ""
            stats.visibility = View.VISIBLE
            return
        }
        val s = Graph.stream.stats()
        if (s == null) {
            stats.visibility = View.GONE
            return
        }
        var t = "Пиъри ${s.peers} · сийдъри ${s.seeds} · ↓ ${formatSpeed(s.speed)}"
        // Дълго буфериране при малко сийдъри: подсказка какво да се направи.
        if (buffering && now - bufferingSince > 10_000 && (s.seeds < 3 || s.speed < 150_000)) {
            t += "\n⚠ Този торент няма достатъчно сийдъри. Натисни Назад → „Откъде?“ за друг източник."
        }
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

    override fun onStop() {
        super.onStop()
        saveProgress()
        player?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(applyOffset)
        player?.release()
        player = null
        super.onDestroy()
        if (isFinishing) Graph.stream.close()
    }
}
