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
import app.torrtv.core.Storage
import app.torrtv.data.subs.SubtitleQuery
import app.torrtv.data.subs.SubtitleResult
import app.torrtv.data.subs.SubShift
import app.torrtv.data.subs.SubtitleStore
import app.torrtv.state.Graph
import app.torrtv.state.Sync
import app.torrtv.state.SyncMemory
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

class LocalSub(val file: File, val lang: String, val label: String, val mime: String, val url: String, val uid: String = "", val id: Int = 0)

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
        val backdrop: String?,
        val media: app.torrtv.data.Media? = null,
        val torrent: app.torrtv.data.torrents.TorrentResult? = null,
        val resumeMs: Long = 0L,
        /** Всички намерени субтитри (подредени) - за бутона „Субтитри“ в плеъра. */
        val allSubs: List<SubtitleResult> = emptyList(),
        /** Името на торента, с което се сравнява изданието на субтитрите (празно за уеб поток). */
        val fitTitle: String? = null
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
        val req: Req? = null,
        /** Не е празно, когато потокът идва от уеб сайт (а не от торент). */
        val webHost: String = ""
    )

    /** headers - за поток от уеб страница: Referer, Origin, бисквитки и т.н., с които трябва да се тегли. */
    class Playback(val url: String, val title: String, val subs: List<LocalSub>, val headers: Map<String, String> = emptyMap(), val webHost: String = "")

    /** Субтитрите без изместване (оригиналните файлове) и текущото изместване в милисекунди. */
    private var baseSubs: List<LocalSub> = emptyList()

    @Volatile
    var subOffsetMs = 0L
        private set

    /**
     * Нови адреси на субтитрите, изместени с offsetMs (+ = излизат по-късно). Вика се от вградения плеър.
     * Изместеният файл се подава от същия локален сървър; файловете на диска не се пипат.
     */
    fun shiftSubs(offsetMs: Long): List<LocalSub> {
        val pb = playback ?: return emptyList()
        subOffsetMs = offsetMs
        val out = shifted(offsetMs)
        playback = Playback(pb.url, pb.title, out, pb.headers, pb.webHost)
        AppLog.d("stream", "синхрон на субтитрите: ${offsetMs} ms")
        return out
    }

    /** Всички субтитри с адреси, изместени с offsetMs (0 = оригиналите). */
    private fun shifted(offsetMs: Long): List<LocalSub> = baseSubs.map { s ->
        if (offsetMs == 0L) {
            s
        } else {
            val ext = s.file.extension.ifBlank { "srt" }
            val text = SubShift.shift(s.file.readText(), ext, offsetMs)
            val path = "/sub${s.id}.o$offsetMs.$ext"
            server.set(path, LocalServer.BytesContent(text.toByteArray(Charsets.UTF_8), s.mime))
            LocalSub(s.file, s.lang, s.label, s.mime, "http://127.0.0.1:${server.port}$path", s.uid, s.id)
        }
    }

    private var subSeq = 0

    private fun publish(l: SubtitleStore.Local): LocalSub {
        val id = subSeq++
        val sp = "/sub$id.${l.file.extension}"
        server.set(sp, LocalServer.BytesContent(l.file.readBytes(), l.mime))
        return LocalSub(l.file, l.lang, l.label, l.mime, "http://127.0.0.1:${server.port}$sp", l.uid, id)
    }

    /**
     * Сваля още един субтитър по време на гледане и го добавя към наличните (най-много 6). Вика се от
     * фонова нишка. Връща новия списък (със сегашния синхрон) или null, ако не е успяло.
     */
    fun addSub(r: SubtitleResult): List<LocalSub>? {
        val req = ui.req ?: return null
        val q = req.subQuery ?: return null
        val pb = playback ?: return null
        if (baseSubs.none { it.uid == r.uid }) {
            val local = try {
                kotlinx.coroutines.runBlocking { subStore.fetch(r, q) }
            } catch (e: Exception) {
                AppLog.d("stream", "субтитър - грешка: ${e.message}")
                null
            } ?: return null
            baseSubs = (baseSubs + publish(local)).takeLast(6)
        }
        val out = shifted(subOffsetMs)
        playback = Playback(pb.url, pb.title, out, pb.headers, pb.webHost)
        return out
    }

    private fun syncKey(req: Req?, uid: String): String? {
        val m = req?.media ?: return null
        if (uid.isEmpty()) return null
        return SyncMemory.key(m.key, req.season, req.episode, uid)
    }

    /** Запомнен синхрон за този филм / епизод и субтитър (0, ако няма). */
    fun recallOffset(uid: String): Long = syncKey(ui.req, uid)?.let { Sync.memory.get(it) } ?: 0L

    /** Запомня синхрона, за да не се настройва втори път. */
    fun rememberOffset(uid: String, ms: Long) {
        syncKey(ui.req, uid)?.let { Sync.memory.set(it, ms) }
    }

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
                // Следващият опит е с друг режим на двигателя - ако един от тях се срива на това устройство, друг може да мине.
                val cur = Graph.prefs.engineProfile
                val next = (cur + 1) % 3
                Graph.prefs.engineProfile = next
                AppLog.d("stream", "режим ${cur + 1} се срина; следващият опит е в режим ${next + 1}")
                ui = ui.copy(
                    stage = Stage.ERROR,
                    error = "$why (режим ${cur + 1}). Натисни „Опитай пак“ - ще опитам в режим ${next + 1}. Причината е в Настройки → Дневник."
                )
            }
        }
    }

    /** Откъде започва гледането (мс). Плеърът го обновява, за да може „Готово → пусни пак“ да продължи оттам. */
    @Volatile
    var resumeFrom = 0L

    fun start(req: Req) {
        close()
        resumeFrom = req.resumeMs
        startedAt = android.os.SystemClock.elapsedRealtime()
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
        val profile = Graph.prefs.engineProfile
        AppLog.d("stream", "торент режим ${profile + 1}")
        val files = client.open(Magnets.ensureTrackers(req.magnet), profile, Storage.chosen(app, Graph.prefs.storageId).torrents) { peers, seeds ->
            ui = ui.copy(peers = peers, seeds = seeds)
        }
        if (files.isEmpty()) throw IOException("В торента няма видео файл")

        val idx = chooseFile(req, files)
        val started = client.play(idx, Graph.prefs.streamWindowMb)
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

        val subs = registerSubs(sj, req)
        val title = if (req.subtitle.isBlank()) req.title else "${req.title} · ${req.subtitle}"
        // Видеото идва от сървъра в торент процеса, субтитрите - от този процес.
        val pb = Playback("http://127.0.0.1:${started.port}${started.path}", title, subs)
        playback = pb
        ui = ui.copy(stage = Stage.READY, message = "Готово", bufferPct = 100)
        AppLog.d("stream", "готово, пускам плеъра ${req.player}: ${pb.url}")
        launchPlayer(req.player, pb)
    }

    /** Чака субтитрите, подава ги от локалния сървър и прилага запомнения синхрон за първия от тях. */
    private suspend fun registerSubs(sj: Deferred<List<SubtitleStore.Local>>, req: Req): List<LocalSub> {
        val locals = sj.await()
        subSeq = 0
        baseSubs = locals.map { publish(it) }
        val off = baseSubs.firstOrNull()?.let { syncKey(req, it.uid)?.let { k -> Sync.memory.get(k) } } ?: 0L
        subOffsetMs = off
        if (off != 0L) AppLog.d("stream", "запомнен синхрон: $off ms")
        return shifted(off)
    }

    /**
     * Поток, намерен в уеб страница (m3u8/mpd/mp4): сваля субтитрите и го пуска във вградения плеър
     * със същите заглавки, с които страницата го е поискала. Външни плеъри не могат да ги получат.
     */
    fun playWeb(url: String, headers: Map<String, String>, req: Req, host: String) {
        close()
        resumeFrom = 0L
        AppLog.d("stream", "Уеб поток: ${req.title} ${req.subtitle} субтитри=${req.subs.size}")
        ui = Ui(stage = Stage.CONNECTING, message = "Подготвям субтитрите…", req = req, webHost = host)
        StreamService.start(app)
        job = scope.launch {
            try {
                val sj = scope.async { prepareSubs(req) }
                subsJob = sj
                server.start()
                val subs = registerSubs(sj, req)
                val title = if (req.subtitle.isBlank()) req.title else "${req.title} · ${req.subtitle}"
                val pb = Playback(url, title, subs, headers, host)
                playback = pb
                ui = ui.copy(stage = Stage.READY, message = "Готово", bufferPct = 100)
                launchPlayer("builtin", pb)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.e("stream", "уеб поток - грешка", e)
                ui = ui.copy(stage = Stage.ERROR, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Кога е започнало подготвянето (за съобщението „няма достатъчно сийдъри“). */
    @Volatile
    var startedAt = 0L

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
            ExternalPlayers.launch(app, player, pb, resumeFrom)
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
        baseSubs = emptyList()
        subSeq = 0
        subOffsetMs = 0L
        client.close()
        server.clearRoutes()
        if (ui.stage != Stage.IDLE) {
            ui = Ui()
            StreamService.stop(app)
        }
    }
}
