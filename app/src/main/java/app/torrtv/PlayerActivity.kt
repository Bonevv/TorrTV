package app.torrtv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
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

@androidx.annotation.OptIn(UnstableApi::class)
/** Вграденият плеър: ExoPlayer, който чете от локалния HTTP сървър на торент стрийма. */
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var stats: TextView
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

        val subs = pb.subs.mapIndexed { i, s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                .setMimeType(if (s.mime.contains("vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(s.lang)
                .setLabel(s.label)
                .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val item = MediaItem.Builder()
            .setUri(Uri.parse(pb.url))
            .setSubtitleConfigurations(subs)
            .build()
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
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { updateStats() })
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
        handler.post(tick)
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
        val s = try { Graph.stream.session?.stats() } catch (e: Exception) { null }
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
        player?.release()
        player = null
        super.onDestroy()
        if (isFinishing) Graph.stream.close()
    }
}
