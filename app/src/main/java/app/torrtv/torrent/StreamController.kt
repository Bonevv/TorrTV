package app.torrtv.torrent

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.torrtv.PlayerActivity
import app.torrtv.StreamService
import app.torrtv.core.AppLog
import app.torrtv.core.Magnets
import app.torrtv.data.subs.SubtitleQuery
import app.torrtv.data.subs.SubtitleResult
import app.torrtv.data.subs.SubtitleStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class LocalSub(val file: File, val lang: String, val label: String, val mime: String, val url: String)

/**
 * Държи целия живот на един стрийм: свързване с торента, избор на файл, буфериране,
 * сваляне на субтитри и пускане в плеъра. Живее извън екраните, за да оцелява при смяна на активити.
 */
class StreamController(
    private val app: Application,
    private val subStore: SubtitleStore,
    private val server: LocalServer
) {
    class Req(
        val magnet: String,
        val title: String,
        val subtitle: String,
        val season: Int?,
        val episode: Int?,
        val subs: List<SubtitleResult>,
        val subQuery: SubtitleQuery?,
        val player: String,
        val backdrop: String?
    )

    enum class Stage { IDLE, CONNECTING, METADATA, PICK_FILE, BUFFERING, READY, ERROR }

    data class Ui(
        val stage: Stage = Stage.IDLE,
        val message: String = "",
        val peers: Int = 0,
        val seeds: Int = 0,
        val speed: Long = 0,
        val bufferPct: Int = 0,
        val fileName: String = "",
        val files: List<TFile> = emptyList(),
        val error: String? = null,
        val subsDone: Int = 0,
        val subsTotal: Int = 0,
        val req: Req? = null
    )

    class Playback(val url: String, val title: String, val subs: List<LocalSub>)

    var ui by mutableStateOf(Ui())
        private set

    @Volatile
    var playback: Playback? = null
        private set

    /** Последната статистика от торент процеса (за екраните "Подготовка" и плеъра). */
    fun stats(): EngineStats? = client.live?.stats

    fun torrentPid(): Int = client.torrentPid

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var pick: CompletableDeferred<Int>? = null
    private var subsJob: Deferred<List<SubtitleStore.Local>>? = null

    /** Торент двигателят върви в отделен процес; тук е връзката с него. */
    private val client = TorrentClient(app).also { c ->
        c.onDied = { why ->
            if (ui.stage != Stage.IDLE && ui.stage != Stage.ERROR) {
                AppLog.d("stream", "торент процесът умря: $why")
                job?.cancel()
                job = null
                ui = ui.copy(stage = Stage.ERROR, error = why)
            }
        }
    }

    fun start(req: Req) {
        close()
        AppLog.d("stream", "Старт: ${req.title} ${req.subtitle} плеър=${req.player} субтитри=${req.subs.size}")
        ui = Ui(stage = Stage.CONNECTING, message = "Свързване…", req = req)
        StreamService.start(app)
        job = scope.launch {
            try {
                run(req)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.e("stream", "грешка", e)
                ui = ui.copy(stage = Stage.ERROR, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun run(req: Req) {
        val sj = scope.async { prepareSubs(req) }
        subsJob = sj

        ui = ui.copy(stage = Stage.METADATA, message = "Стартиране на торент двигателя…")
        client.connect()
        ui = ui.copy(message = "Търсене на пиъри…")
        val files = client.open(Magnets.ensureTrackers(req.magnet)) { peers, seeds ->
            ui = ui.copy(peers = peers, seeds = seeds)
        }
        if (files.isEmpty()) throw IOException("В торента няма видео файл")

        val idx = chooseFile(req, files)
        val started = client.play(idx)
        server.start()
        AppLog.d("stream", "файл: ${started.name} (${started.size / 1048576} MB)")
        ui = ui.copy(stage = Stage.BUFFERING, fileName = started.name, message = "Буфериране…")
        val t0 = System.currentTimeMillis()
        while (true) {
            val lv = client.live
            if (lv != null) {
                ui = ui.copy(peers = lv.stats.peers, seeds = lv.stats.seeds, speed = lv.stats.speed, bufferPct = lv.preloadPct)
                if (lv.preloaded) break
            }
            delay(400)
            if (System.currentTimeMillis() - t0 > 6 * 60_000L)
                throw IOException("Твърде бавно теглене - няма достатъчно сийдъри. Опитай друг източник.")
        }

        val locals = sj.await()
        val subs = ArrayList<LocalSub>()
        locals.forEachIndexed { i, l ->
            val sp = "/sub$i.${l.file.extension}"
            server.set(sp, LocalServer.BytesContent(l.file.readBytes(), l.mime))
            subs.add(LocalSub(l.file, l.lang, l.label, l.mime, "http://127.0.0.1:${server.port}$sp"))
        }
        val title = if (req.subtitle.isBlank()) req.title else "${req.title} · ${req.subtitle}"
        // Видеото идва от сървъра в торент процеса, субтитрите - от този процес.
        val pb = Playback("http://127.0.0.1:${started.port}${started.path}", title, subs)
        playback = pb
        ui = ui.copy(stage = Stage.READY, message = "Готово", bufferPct = 100)
        AppLog.d("stream", "готово, пускам плеъра ${req.player}: ${pb.url}")
        launchPlayer(req.player, pb)
    }

    private suspend fun prepareSubs(req: Req): List<SubtitleStore.Local> {
        val q = req.subQuery ?: return emptyList()
        if (req.subs.isEmpty()) return emptyList()
        ui = ui.copy(subsTotal = req.subs.size)
        val out = ArrayList<SubtitleStore.Local>()
        for (r in req.subs) {
            try {
                subStore.fetch(r, q)?.let { out.add(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // един лош субтитър не бива да спира филма
            }
            ui = ui.copy(subsDone = ui.subsDone + 1)
        }
        return out
    }

    private suspend fun chooseFile(req: Req, files: List<TFile>): Int {
        if (files.size == 1) return files[0].index
        val s = req.season
        val e = req.episode
        if (s != null && e != null) {
            val re = Regex("(?i)s0*$s ?[ex_.-]?e0*$e(?!\\d)|\\b${s}x0*$e(?!\\d)|season ?0*$s.*[ ._-]0*$e[ ._-]")
            val matches = files.filter { re.containsMatchIn(it.path) }
            if (matches.size == 1) return matches[0].index
            if (matches.size > 1) return matches.maxByOrNull { it.size }!!.index
        } else {
            val sorted = files.sortedByDescending { it.size }
            if (sorted[0].size * 2 >= sorted[1].size * 3) return sorted[0].index
        }
        // Не можем да познаем - питаме потребителя.
        ui = ui.copy(stage = Stage.PICK_FILE, files = files.sortedBy { it.path }, message = "Избери файл за гледане")
        val d = CompletableDeferred<Int>()
        pick = d
        val chosen = d.await()
        ui = ui.copy(stage = Stage.METADATA, files = emptyList(), message = "Стартиране…")
        return chosen
    }

    fun pickFile(index: Int) {
        pick?.complete(index)
    }

    private fun launchPlayer(player: String, pb: Playback) {
        val ok = player != "builtin" && try {
            ExternalPlayers.launch(app, player, pb)
        } catch (e: Throwable) {
            AppLog.e("stream", "външен плеър", e)
            false
        }
        AppLog.d("stream", "външен плеър $player пуснат=$ok")
        if (!ok) {
            val i = Intent(app, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(i)
        }
    }

    /** От екрана "Готово" - пуска плеъра отново (след като е затворен). */
    fun relaunch() {
        val pb = playback ?: return
        launchPlayer(ui.req?.player ?: "builtin", pb)
    }

    fun close() {
        AppLog.d("stream", "затваряне")
        job?.cancel()
        job = null
        pick?.cancel()
        pick = null
        subsJob?.cancel()
        subsJob = null
        playback = null
        client.close()
        server.clearRoutes()
        if (ui.stage != Stage.IDLE) {
            ui = Ui()
            StreamService.stop(app)
        }
    }
}
