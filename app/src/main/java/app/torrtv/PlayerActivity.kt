package app.torrtv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.torrtv.core.formatSpeed
import app.torrtv.state.Graph
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
    private val applyOffset = Runnable { applyOffsetNow() }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateStats()
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
        val p = ExoPlayer.Builder(this, renderers).setLoadControl(load).build()
        player = p
        view.player = p

        pbUrl = pb.url
        val item = buildItem(pb.url, pb.subs, 0)
        if (pb.subs.isNotEmpty()) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage(pb.subs[0].lang)
                .setSelectUndeterminedTextLanguage(true)
                .build()
        }
        p.addListener(object : Player.Listener {
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
            val show = vis == View.VISIBLE && (Graph.stream.playback?.subs?.isNotEmpty() == true)
            if (!show && syncBar.hasFocus()) view.requestFocus()
            syncBar.visibility = if (show) View.VISIBLE else View.GONE
        })
        p.setMediaItem(item)
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
        return MediaItem.Builder().setUri(Uri.parse(url)).setSubtitleConfigurations(subs).build()
    }

    // ---- Синхрон на субтитрите ----

    private fun pillBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = 60f
        setColor(if (focused) 0xFFFFFFFF.toInt() else 0xB3101521.toInt())
    }

    private fun syncButton(label: String, delta: Long): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setPadding(26, 12, 26, 12)
        background = pillBg(false)
        isFocusable = true
        setOnClickListener {
            changeOffset(delta)
            view.showController()
        }
        setOnFocusChangeListener { v, f ->
            v.background = pillBg(f)
            (v as TextView).setTextColor(if (f) 0xFF080B12.toInt() else 0xFFFFFFFF.toInt())
            if (f) view.showController()
        }
    }

    private fun buildSyncBar() {
        syncBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            visibility = View.GONE
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
            setTextColor(0xFF22D3EE.toInt())
            setPadding(24, 0, 24, 0)
        }
        row.addView(title)
        row.addView(syncButton("−0,5", -500))
        row.addView(syncButton("−0,1", -100))
        row.addView(syncValue)
        row.addView(syncButton("+0,1", 100))
        row.addView(syncButton("+0,5", 500))
        val hint = TextView(this).apply {
            text = "+ = субтитрите излизат по-късно · − = по-рано"
            textSize = 11f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, 6, 0, 0)
        }
        syncBar.addView(row)
        syncBar.addView(hint)
        showOffset()
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
        thread(isDaemon = true) {
            val subs = try {
                Graph.stream.shiftSubs(ms)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread { reloadSubs(subs) }
        }
    }

    /** Презарежда видеото със същите субтитри, но с новото изместване - от същото място. */
    private fun reloadSubs(subs: List<LocalSub>) {
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
        val idx = subs.indexOfFirst { it.label == label }.let { if (it < 0) 0 else it }
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(subs[idx].lang)
            .build()
        p.setMediaItem(buildItem(pbUrl, subs, idx), pos)
        p.prepare()
        p.playWhenReady = play
    }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun updateStats() {
        val p = player ?: return
        val buffering = p.playbackState == Player.STATE_BUFFERING
        if (!view.isControllerFullyVisible && !buffering) {
            stats.visibility = View.GONE
            return
        }
        val s = Graph.stream.stats()
        if (s == null) {
            stats.visibility = View.GONE
            return
        }
        stats.text = "Пиъри ${s.peers} · сийдъри ${s.seeds} · ↓ ${formatSpeed(s.speed)}"
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
