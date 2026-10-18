package app.torrtv.ui

import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import app.torrtv.core.AppLog
import app.torrtv.core.CacheManager
import app.torrtv.core.Device
import app.torrtv.core.Magnets
import app.torrtv.core.Prefs
import app.torrtv.core.TrackerCheck
import app.torrtv.core.agoText
import app.torrtv.data.subs.Langs
import app.torrtv.state.Diagnostics
import app.torrtv.state.Graph
import app.torrtv.torrent.ExternalPlayers
import app.torrtv.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val fieldLabels = mapOf(
    "tmdb_key" to "TMDB ключ",
    "os_key" to "OpenSubtitles ключ",
    "os_user" to "OpenSubtitles потребител",
    "os_pass" to "OpenSubtitles парола",
    "subdl_key" to "Subdl ключ",
    "subsource_key" to "SubSource ключ"
)

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 36.d, vertical = 6.d)
            .clip(RoundedCornerShape(16.d)).background(C.bg2).border(1.d, C.line, RoundedCornerShape(16.d))
            .padding(horizontal = 16.d, vertical = 12.d)
    ) {
        Text2(title, 16, C.text, FontWeight.ExtraBold, 1)
        Spacer(Modifier.height(8.d))
        content()
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val prefs = Graph.prefs
    val scope = rememberCoroutineScope()

    // Страницата за телефона работи само докато този екран е отворен
    DisposableEffect(Unit) {
        Graph.config.start()
        onDispose { Graph.config.stop() }
    }
    val tick by produceState(0) {
        while (true) { delay(1500); value += 1 }
    }

    var player by remember { mutableStateOf(prefs.player) }
    var metaLang by remember { mutableStateOf(prefs.metaLang) }
    var subLangs by remember { mutableStateOf(prefs.subLangs) }
    var subSize by remember { mutableStateOf(prefs.subSize) }
    var subEdge by remember { mutableStateOf(prefs.subEdge) }
    var subLift by remember { mutableStateOf(prefs.subLift) }
    var audioLang by remember { mutableStateOf(prefs.audioLang) }
    var autoNext by remember { mutableStateOf(prefs.autoNext) }
    var release by remember { mutableStateOf<Updater.Release?>(Graph.home.update) }
    var updMsg by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(-1f) }
    var checking by remember { mutableStateOf(false) }
    var diagRunning by remember { mutableStateOf(false) }
    val diag = remember { mutableStateListOf<String>() }
    var cleared by remember { mutableStateOf(false) }
    var cacheHours by remember { mutableStateOf(prefs.cacheHours) }
    var cacheLimit by remember { mutableStateOf(prefs.cacheLimitMb) }
    var streamWindow by remember { mutableStateOf(prefs.streamWindowMb) }
    var engineMode by remember { mutableStateOf(prefs.engineProfile) }
    var liteChoice by remember { mutableStateOf(prefs.liteChoice) }
    var advanced by remember { mutableStateOf(false) }
    var webOn by remember { mutableStateOf(prefs.webEnabled) }
    var webHandoff by remember { mutableStateOf(prefs.webHandoff) }
    var cacheTick by remember { mutableStateOf(0) }
    var trkTick by remember { mutableStateOf(0) }
    var logText by remember { mutableStateOf<String?>(null) }
    val srcState = remember { mutableStateListOf<Pair<String, Boolean>>().also { l -> Graph.torrents.sources.forEach { l.add(it.id to prefs.sourceEnabled(it.id)) } } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 50.d)) {
        item {
            Group("Настройка от телефона") {
                val addrs = remember(tick / 4) { Graph.config.addresses() }
                val port = Graph.config.port
                Text2("Отвори адреса на телефона си (в същата Wi-Fi мрежа) и въведи ПИН-а. Оттам се въвеждат ключовете и може да се търси филм.", 13, C.dim, FontWeight.Normal, 3)
                Spacer(Modifier.height(6.d))
                if (addrs.isEmpty()) Text2("Телевизорът не е свързан към мрежа.", 13, C.red, FontWeight.Medium, 2)
                if (port != 0) addrs.forEach { Text2("http://$it:$port", 22, C.accent2, FontWeight.ExtraBold, 1) }
                Spacer(Modifier.height(4.d))
                Text2("ПИН: ${Graph.config.pin}", 22, C.gold, FontWeight.ExtraBold, 1)
                Spacer(Modifier.height(8.d))
                // tick се чете, за да се опреснява статусът след запис от телефона
                val ok = remember(tick) { Prefs.FIELD_NAMES.filter { fieldLabels.containsKey(it) } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.d)) {
                    ok.take(3).forEach { StatusPill(fieldLabels[it] ?: it, prefs.isSet(it)) }
                }
                Spacer(Modifier.height(4.d))
                Row(horizontalArrangement = Arrangement.spacedBy(6.d)) {
                    ok.drop(3).forEach { StatusPill(fieldLabels[it] ?: it, prefs.isSet(it)) }
                }
            }
        }

        item {
            Group("Плеър") {
                val installed = remember { ExternalPlayers.installed(ctx) }
                val opts = listOf("builtin") + installed.map { it.id } + "ask"
                Text2("Плеър по подразбиране", 13, C.dim, FontWeight.Bold, 1)
                Spacer(Modifier.height(4.d))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    items(opts, key = { it }) { id ->
                        Chip(ExternalPlayers.label(id), player == id, { player = id; prefs.player = id })
                    }
                }
                Spacer(Modifier.height(4.d))
                Text2(
                    if (installed.isEmpty()) "Няма инсталиран външен плеър (VLC, MX Player, Just Player). Нужен е, когато файлът е със звук DTS или TrueHD."
                    else "Намерени плеъри: " + installed.joinToString { it.label },
                    11, C.dim, FontWeight.Normal, 2
                )
                Spacer(Modifier.height(10.d))
                Text2("Звук във вградения плеър", 13, C.dim, FontWeight.Bold, 1)
                Spacer(Modifier.height(4.d))
                Row(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    listOf("auto" to "От файла", "orig" to "Оригинален", "bg" to "Български", "en" to "English").forEach { (code, name) ->
                        Chip(name, audioLang == code, { audioLang = code; prefs.audioLang = code })
                    }
                }
                Spacer(Modifier.height(4.d))
                Text2("Ако във файла няма такъв звук, пуска се стандартният.", 11, C.dim, FontWeight.Normal, 2)
                Spacer(Modifier.height(10.d))
                Text2("Следващ епизод", 13, C.dim, FontWeight.Bold, 1)
                Spacer(Modifier.height(4.d))
                Row(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    Chip("Автоматично", autoNext, { autoNext = true; prefs.autoNext = true })
                    Chip("Само с бутон", !autoNext, { autoNext = false; prefs.autoNext = false })
                }
                Spacer(Modifier.height(10.d))
                Text2("Размер на субтитрите", 13, C.dim, FontWeight.Bold, 1)
                Spacer(Modifier.height(4.d))
                Row(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    listOf("Малки", "Средни", "Големи", "Много големи").forEachIndexed { i, name ->
                        Chip(name, subSize == i, { subSize = i; prefs.subSize = i })
                    }
                }
                Spacer(Modifier.height(8.d))
                Text2("Стил на субтитрите", 13, C.dim, FontWeight.Bold, 1)
                Spacer(Modifier.height(4.d))
                Row(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    listOf("Като в системата", "Контур", "Фон", "Сянка").forEachIndexed { i, name ->
                        Chip(name, subEdge == i, { subEdge = i; prefs.subEdge = i })
                    }
                }
                Spacer(Modifier.height(8.d))
                Text2("Място на субтитрите", 13, C.dim, FontWeight.Bold, 1)
                Spacer(Modifier.height(4.d))
                Row(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    listOf("По-ниско", "Стандартно", "По-високо").forEachIndexed { i, name ->
                        Chip(name, subLift == i, { subLift = i; prefs.subLift = i })
                    }
                }
            }
        }

        item {
            Group("Езици") {
                Text2("Описания на филмите", 13, C.dim, FontWeight.Bold, 1)
                Spacer(Modifier.height(4.d))
                Row(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    Chip("Български", metaLang == "bg", { metaLang = "bg"; prefs.metaLang = "bg" })
                    Chip("English", metaLang == "en", { metaLang = "en"; prefs.metaLang = "en" })
                }
                Spacer(Modifier.height(10.d))
                Text2("Субтитри (може няколко)", 13, C.dim, FontWeight.Bold, 1)
                Spacer(Modifier.height(4.d))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    items(Langs.choices, key = { it.first }) { (code, name) ->
                        Chip((if (code in subLangs) "✓ " else "") + name, code in subLangs, {
                            val n = if (code in subLangs) subLangs - code else subLangs + code
                            if (n.isNotEmpty()) {
                                subLangs = n
                                prefs.subLangs = n
                            }
                        })
                    }
                }
            }
        }

        item {
            Group("Откъде се гледа") {
                Text2("Торент сайтове", 13, C.dim, FontWeight.Bold, 1)
                Spacer(Modifier.height(4.d))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    items(Graph.torrents.sources, key = { it.id }) { s ->
                        val on = srcState.firstOrNull { it.first == s.id }?.second ?: true
                        Chip((if (on) "✓ " else "✗ ") + s.name, on, {
                            val idx = srcState.indexOfFirst { it.first == s.id }
                            if (idx >= 0) srcState[idx] = s.id to !on
                            prefs.setSourceEnabled(s.id, !on)
                        })
                    }
                }
                Spacer(Modifier.height(10.d))
                Text2("Уеб сайтове", 13, C.dim, FontWeight.Bold, 1)
                Spacer(Modifier.height(4.d))
                Text2(
                    "Когато торентът няма сийдъри, филмът може да се гледа онлайн от сайт (бутонът е в страницата на филма). Рекламите се блокират.",
                    11, C.dim, FontWeight.Normal, 3
                )
                Spacer(Modifier.height(6.d))
                Row(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    Chip("Изключени", !webOn, { webOn = false; prefs.webEnabled = false })
                    Chip("Включени", webOn, { webOn = true; prefs.webEnabled = true })
                }
                Spacer(Modifier.height(8.d))
                Text2("Щом сайтът пусне видеото", 13, C.dim, FontWeight.Bold, 1)
                Spacer(Modifier.height(4.d))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    items(
                        listOf(0 to "Питай ме", 1 to "Във вградения плеър", 2 to "Остани на сайта"),
                        key = { it.first }
                    ) { (m, label) -> Chip(label, webHandoff == m, { webHandoff = m; prefs.webHandoff = m }) }
                }
                Spacer(Modifier.height(6.d))
                Text2(
                    "Списъкът със сайтове се сменя от телефона, на същата страница като ключовете. Ако някой сайт спре да работи, махни го оттам.",
                    11, C.dim, FontWeight.Normal, 3
                )
            }
        }

        item {
            Group("Обновяване") {
                Text2("Версия: ${Graph.updater.currentVersion}", 13, C.dim, FontWeight.Medium, 1)
                Spacer(Modifier.height(6.d))
                val r = release
                if (r != null) {
                    Text2("Нова версия: ${r.name.ifBlank { r.tag }}", 15, C.green, FontWeight.Bold, 1)
                    if (r.notes.isNotBlank()) Text2(r.notes.take(300), 11, C.dim, FontWeight.Normal, 5)
                    Spacer(Modifier.height(6.d))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.d), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(if (checking) "Проверка…" else "Провери за обновление", {
                        if (checking) return@PrimaryButton
                        checking = true
                        updMsg = ""
                        scope.launch {
                            try {
                                val l = Graph.updater.latest()
                                if (l != null && Graph.updater.isNewer(l.tag)) { release = l } else { release = null; updMsg = "Имаш най-новата версия." }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                updMsg = "Не се получи: ${e.message}"
                            } finally { checking = false }
                        }
                    }, primary = release == null)
                    if (r != null) {
                        PrimaryButton(if (progress in 0f..0.999f) "Сваляне ${(progress * 100).toInt()}%" else "Свали и инсталирай", {
                            if (progress in 0f..0.999f) return@PrimaryButton
                            progress = 0f
                            scope.launch {
                                try {
                                    val f = Graph.updater.download(r) { progress = it }
                                    progress = 1f
                                    updMsg = Graph.updater.install(f) ?: "Отваря се инсталаторът…"
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    progress = -1f
                                    updMsg = "Не се получи: ${e.message}"
                                }
                            }
                        })
                    }
                }
                if (updMsg.isNotBlank()) {
                    Spacer(Modifier.height(6.d))
                    Text2(updMsg, 12, C.accent2, FontWeight.Medium, 3)
                }
            }
        }

        item {
            Focusable({ advanced = !advanced }, Modifier.fillMaxWidth().padding(horizontal = 36.d, vertical = 6.d), RoundedCornerShape(16.d), 1.01f) { f ->
                Row(
                    Modifier.fillMaxWidth().background(if (f) C.cardHi else C.bg2).padding(horizontal = 16.d, vertical = 14.d),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text2("Разширени", 16, C.text, FontWeight.ExtraBold, 1)
                        Text2("Диск и кеш, лек режим, торент режим, тракери, проверка на източниците, дневник", 11, C.dim, FontWeight.Normal, 1)
                    }
                    Text2(if (advanced) "▲" else "▼", 14, C.dim, FontWeight.Bold, 1)
                }
            }
        }

        if (advanced) {
            item {
                Group("Диск и скорост") {
                    val places = remember(cacheTick) { app.torrtv.core.Storage.places(ctx) }
                    var storageId by remember { mutableStateOf(prefs.storageId) }
                    val cur = places.firstOrNull { it.id == storageId } ?: places.first()
                    val free = remember(cur, cacheTick) { app.torrtv.core.Storage.free(cur) }
                    var netMbps by remember { mutableStateOf(prefs.netMbps) }
                    var diskMBps by remember { mutableStateOf(prefs.diskMBps) }
                    var netBusy by remember { mutableStateOf(false) }
                    var diskBusy by remember { mutableStateOf(false) }
                    var testMsg by remember { mutableStateOf("") }
                    Text2("Къде да се пази филмът, докато го гледаш:", 12, C.dim, FontWeight.Bold, 1)
                    Spacer(Modifier.height(4.d))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                        items(places, key = { it.id }) { p ->
                            Chip(
                                p.label + " · " + CacheManager.fmt(app.torrtv.core.Storage.free(p)) + " свободни",
                                cur.id == p.id, { storageId = p.id; prefs.storageId = p.id }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.d))
                    Text2(
                        "Тук се събира файл до около " + CacheManager.fmt(app.torrtv.core.StreamFit.usableBytes(free)) + ". " +
                            (if (places.size == 1) "Ако включиш USB диск или SD карта, ще се появи в списъка. " else "") +
                            "На някои телевизори външните дискове са бавни - провери с теста за запис.",
                        11, C.dim, FontWeight.Normal, 4
                    )
                    Spacer(Modifier.height(8.d))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.d)) {
                        PrimaryButton(if (diskBusy) "Тества се…" else "Тествай записа", {
                            if (diskBusy) return@PrimaryButton
                            diskBusy = true
                            scope.launch {
                                val r = app.torrtv.core.SpeedTest.disk(File(cur.root, "test"))
                                if (r != null) {
                                    diskMBps = r.toFloat()
                                    prefs.diskMBps = diskMBps
                                    testMsg = ""
                                } else testMsg = "Тестът на записа не се получи (няма достъп или място)."
                                diskBusy = false
                            }
                        }, primary = false)
                        PrimaryButton(if (netBusy) "Тества се…" else "Тествай мрежата", {
                            if (netBusy) return@PrimaryButton
                            netBusy = true
                            scope.launch {
                                val r = app.torrtv.core.SpeedTest.net()
                                if (r != null) {
                                    netMbps = r.toFloat()
                                    prefs.netMbps = netMbps
                                    testMsg = ""
                                } else testMsg = "Тестът на мрежата не се получи - провери връзката с интернет."
                                netBusy = false
                            }
                        }, primary = false)
                    }
                    Spacer(Modifier.height(6.d))
                    Text2(
                        "Запис: " + (if (diskMBps > 0) "${diskMBps.toInt()} MB/s" else "не е измерен") +
                            "   ·   Мрежа: " + (if (netMbps > 0) "${netMbps.toInt()} Mbit/s" else "не е измерена"),
                        13, C.accent2, FontWeight.Bold, 1
                    )
                    if (testMsg.isNotBlank()) Text2(testMsg, 12, C.red, FontWeight.Medium, 2)
                    val rec = app.torrtv.core.StreamFit.recommendedWindowMb(netMbps.toDouble())
                    if (rec > 0) {
                        Spacer(Modifier.height(8.d))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.d)) {
                            Text2(
                                "Препоръчан буфер: " + (if (rec >= 1000) "1 GB" else "$rec MB") + " напред" +
                                    (if (netMbps < 20) " (връзката ти е бавна)" else ""),
                                12, C.text, FontWeight.Medium, 2, Modifier.weight(1f)
                            )
                            PrimaryButton(if (streamWindow == rec) "Зададен ✓" else "Задай", { streamWindow = rec; prefs.streamWindowMb = rec }, primary = false)
                        }
                    }
                }
            }

            item {
                Group("Памет и кеш") {
                    val mem by produceState(remember { CacheManager.memory(ctx) }) {
                        while (true) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { CacheManager.memory(ctx) }
                            delay(3000)
                        }
                    }
                    // Размерите на кеша се обновяват на ~4.5 с; derivedStateOf не позволява целият екран да се прерисува на всеки тик.
                    val sizeTick by remember { androidx.compose.runtime.derivedStateOf { tick / 3 } }
                    val sizes by produceState<CacheManager.Sizes?>(null, cacheTick, sizeTick) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CacheManager.sizes(ctx) }
                    }
                    Text2(
                        "Заета памет: ${mem.totalMb} MB   (интерфейс ${mem.pssMb} MB" +
                            (if (mem.torrentMb > 0) " + торент ${mem.torrentMb} MB" else "") + ")",
                        13, C.accent2, FontWeight.Bold, 2
                    )
                    Spacer(Modifier.height(4.d))
                    val sz = sizes
                    Text2(
                        if (sz == null) "Кеш: изчислява се…" else
                            "Кеш: ${CacheManager.fmt(sz.total)}  (торенти ${CacheManager.fmt(sz.torrents)} · картинки ${CacheManager.fmt(sz.images)} · субтитри ${CacheManager.fmt(sz.subs)})",
                        12, C.dim, FontWeight.Normal, 2
                    )
                    Spacer(Modifier.height(8.d))
                    Text2("Чисти кеша сам:", 12, C.dim, FontWeight.Bold, 1)
                    Spacer(Modifier.height(4.d))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                        items(CacheManager.intervals, key = { it.first }) { (h, label) ->
                            Chip(label, cacheHours == h, { cacheHours = h; prefs.cacheHours = h; prefs.lastClean = System.currentTimeMillis() })
                        }
                    }
                    Spacer(Modifier.height(8.d))
                    Text2("Чисти и когато стане повече от:", 12, C.dim, FontWeight.Bold, 1)
                    Spacer(Modifier.height(4.d))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                        items(CacheManager.limits, key = { it.first }) { (mb, label) ->
                            Chip(label, cacheLimit == mb, { cacheLimit = mb; prefs.cacheLimitMb = mb })
                        }
                    }
                    Spacer(Modifier.height(8.d))
                    Text2("Как да тегли, докато гледаш:", 12, C.dim, FontWeight.Bold, 1)
                    Spacer(Modifier.height(4.d))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                        items(listOf(0 to "Класическо", 200 to "Напред 200 MB", 500 to "Напред 500 MB", 1000 to "Напред 1 GB"), key = { it.first }) { (mb, label) ->
                            Chip(label, streamWindow == mb, { streamWindow = mb; prefs.streamWindowMb = mb })
                        }
                    }
                    Spacer(Modifier.height(4.d))
                    Text2(
                        "Класическо тегли целия файл, докато гледаш. „Напред“ тегли само определено количество пред мястото, където си - " +
                            "удобно за големи файлове, когато мястото не стига. Изгледаното остава на диска, докато не спреш филма.",
                        11, C.dim, FontWeight.Normal, 4
                    )
                    Spacer(Modifier.height(8.d))
                    PrimaryButton(if (cleared) "Изчистено" else "Изчисти кеша сега", {
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                CacheManager.clear(ctx, Graph.stream.ui.stage != app.torrtv.torrent.StreamController.Stage.IDLE)
                            }
                            cleared = true
                            cacheTick++
                        }
                    }, primary = false)
                }
            }

            item {
                Group("Лек режим") {
                    Text2(
                        "За слаби телевизори: без анимации и с по-малки картинки. „Сам“ го включва там, където паметта е малко.",
                        12, C.dim, FontWeight.Normal, 3
                    )
                    Spacer(Modifier.height(6.d))
                    val weak = remember { Device.isWeak(ctx) }
                    fun pick(v: Int) {
                        liteChoice = v
                        prefs.liteChoice = v
                        Look.lite = prefs.liteOn(weak)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                        Chip("Сам", liteChoice == 0, { pick(0) })
                        Chip("Включен", liteChoice == 1, { pick(1) })
                        Chip("Изключен", liteChoice == 2, { pick(2) })
                    }
                    Spacer(Modifier.height(4.d))
                    Text2(
                        "Сега: " + (if (Look.lite) "включен" else "изключен") +
                            if (liteChoice == 0) (if (weak) " (устройството е със скромна памет)" else " (устройството се справя)") else "",
                        11, C.dim, FontWeight.Normal, 2
                    )
                }
            }

            item {
                Group("Торент режим") {
                    Text2(
                        "Ако филмът спира веднага след като тръгне, пробвай друг режим. След срив приложението го сменя автоматично, но можеш да избереш и ръчно.",
                        12, C.dim, FontWeight.Normal, 3
                    )
                    Spacer(Modifier.height(6.d))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                        items(listOf(0 to "Режим 1 · стандартен", 1 to "Режим 2 · системен диск", 2 to "Режим 3 · щадящ"), key = { it.first }) { (m, label) ->
                            Chip(label, engineMode == m, { engineMode = m; prefs.engineProfile = m })
                        }
                    }
                }
            }

            item {
                Group("Тракери") {
                    val checkedAt = remember(trkTick) { prefs.trackersCheckedAt }
                    val dead = remember(trkTick) { prefs.deadTrackers }
                    val trkLines = remember { mutableStateListOf<String>() }
                    var trkBusy by remember { mutableStateOf(false) }
                    Text2(
                        "Тракерите помагат на торента да намери хора. Тези, които не отговарят, се прескачат при пускане, за да тръгва по-бързо.",
                        12, C.dim, FontWeight.Normal, 3
                    )
                    Spacer(Modifier.height(6.d))
                    val stale = checkedAt > 0 && System.currentTimeMillis() - checkedAt > 7L * 24 * 3_600_000
                    Text2(
                        when {
                            checkedAt == 0L -> "Още не са проверявани."
                            stale -> "Последната проверка е ${agoText(System.currentTimeMillis() - checkedAt)}. Пробват се всички; провери пак."
                            dead.isEmpty() -> "Проверени ${agoText(System.currentTimeMillis() - checkedAt)}: всички отговарят."
                            else -> "Проверени ${agoText(System.currentTimeMillis() - checkedAt)}: не отговарят ${dead.size} от ${Magnets.trackers.size}."
                        },
                        13, if (dead.isEmpty()) C.accent2 else C.amber, FontWeight.Medium, 2
                    )
                    if (dead.isNotEmpty() && !stale) {
                        Spacer(Modifier.height(4.d))
                        Text2("Мъртви: " + dead.map { TrackerCheck.shortName(it) }.sorted().joinToString(", "), 12, C.red, FontWeight.Normal, 3)
                    }
                    Spacer(Modifier.height(8.d))
                    PrimaryButton(if (trkBusy) "Проверява се…" else "Провери тракерите", {
                        if (trkBusy) return@PrimaryButton
                        trkBusy = true
                        trkLines.clear()
                        scope.launch {
                            try {
                                Diagnostics.checkTrackers { trkLines.add(it) }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                trkLines.add("✗ ${e.message}")
                            } finally {
                                trkBusy = false
                                trkTick++
                            }
                        }
                    }, primary = false)
                    if (trkLines.isNotEmpty()) {
                        Spacer(Modifier.height(8.d))
                        trkLines.forEach { line ->
                            Text2(
                                line, 12,
                                when {
                                    line.startsWith("✓") -> C.green
                                    line.startsWith("✗") -> C.red
                                    else -> C.dim
                                },
                                FontWeight.Normal, 2
                            )
                        }
                    }
                }
            }

            item {
                Group("Диагностика") {
                    Text2("Проверява торент сайтовете, субтитрите, тракерите и TMDB и показва кое не работи.", 12, C.dim, FontWeight.Normal, 2)
                    Spacer(Modifier.height(6.d))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.d)) {
                        PrimaryButton(if (diagRunning) "Тества се…" else "Провери източниците", {
                            if (diagRunning) return@PrimaryButton
                            diagRunning = true
                            diag.clear()
                            scope.launch {
                                try {
                                    Diagnostics.run { diag.add(it) }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    diag.add("✗ ${e.message}")
                                } finally { diagRunning = false }
                            }
                        }, primary = false)
                    }
                    Spacer(Modifier.height(8.d))
                    diag.forEach { line ->
                        Text2(
                            line, 12,
                            when {
                                line.startsWith("✓") -> C.green
                                line.startsWith("✗") -> C.red
                                line.startsWith("⏱") -> C.amber
                                line.startsWith("—") -> C.accent2
                                else -> C.dim
                            },
                            if (line.startsWith("—")) FontWeight.Bold else FontWeight.Normal, 3
                        )
                    }
                }
            }

            item {
                Group("Дневник") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.d)) {
                        PrimaryButton(if (logText == null) "Покажи дневника" else "Опресни", { logText = AppLog.text() }, primary = false)
                        PrimaryButton("Изчисти дневника", { AppLog.clear(); logText = "" }, primary = false)
                    }
                    logText?.let { t ->
                        Spacer(Modifier.height(8.d))
                        Text2(if (t.isBlank()) "(празен)" else t, 10, C.dim, FontWeight.Normal, 60)
                    }
                }
            }
        }

        item {
            Box(Modifier.fillMaxWidth().padding(top = 18.d, bottom = 6.d), contentAlignment = Alignment.Center) { Signature(large = true) }
        }
    }
}

@Composable
private fun StatusPill(label: String, ok: Boolean) {
    Pill((if (ok) "✓ " else "✗ ") + label, if (ok) androidx.compose.ui.graphics.Color(0x333DDC84) else androidx.compose.ui.graphics.Color(0x33FF5C6C), if (ok) C.green else C.red)
}
