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
import app.torrtv.core.Prefs
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
    var lite by remember { mutableStateOf(prefs.lite) }
    var webOn by remember { mutableStateOf(prefs.webEnabled) }
    var webHandoff by remember { mutableStateOf(prefs.webHandoff) }
    val mem by produceState(remember { CacheManager.memory(ctx) }) {
        while (true) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { CacheManager.memory(ctx) }
            delay(3000)
        }
    }
    var cacheTick by remember { mutableStateOf(0) }
    // Размерите на кеша се обновяват на ~4.5 с; derivedStateOf не позволява целият екран да се прерисува на всеки тик.
    val sizeTick by remember { androidx.compose.runtime.derivedStateOf { tick / 3 } }
    val sizes by produceState<CacheManager.Sizes?>(null, cacheTick, sizeTick) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CacheManager.sizes(ctx) }
    }
    var logText by remember { mutableStateOf<String?>(null) }
    val srcState = remember { mutableStateListOf<Pair<String, Boolean>>().also { l -> Graph.torrents.sources.forEach { l.add(it.id to prefs.sourceEnabled(it.id)) } } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 50.d)) {
        item {
            Group("📱  Въвеждане на ключовете от телефона") {
                val addrs = remember(tick / 4) { Graph.config.addresses() }
                val port = Graph.config.port
                Text2("Отвори на телефона (в същата Wi-Fi мрежа) и въведи ПИН-а:", 13, C.dim, FontWeight.Normal, 2)
                Spacer(Modifier.height(6.d))
                if (addrs.isEmpty()) Text2("Няма Wi-Fi/LAN адрес. Свържи телевизора към мрежата.", 13, C.red, FontWeight.Medium, 2)
                addrs.forEach { Text2("http://$it:$port", 22, C.accent2, FontWeight.ExtraBold, 1) }
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
            Group("▶  Плеър по подразбиране") {
                val installed = remember { ExternalPlayers.installed(ctx) }
                val opts = listOf("builtin") + installed.map { it.id } + "ask"
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    items(opts, key = { it }) { id ->
                        Chip(ExternalPlayers.label(id), player == id, { player = id; prefs.player = id })
                    }
                }
                Spacer(Modifier.height(4.d))
                Text2(
                    if (installed.isEmpty()) "Външни плеъри (VLC, MX, Just Player) не са открити - инсталирай ги от магазина, ако ти трябват DTS/AC3."
                    else "Открити външни плеъри: " + installed.joinToString { it.label },
                    11, C.dim, FontWeight.Normal, 2
                )
            }
        }

        item {
            Group("🌐  Език на описанията") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    Chip("Български", metaLang == "bg", { metaLang = "bg"; prefs.metaLang = "bg" })
                    Chip("English", metaLang == "en", { metaLang = "en"; prefs.metaLang = "en" })
                }
            }
        }

        item {
            Group("💬  Езици на субтитрите") {
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
            Group("🧲  Торент източници") {
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
            }
        }

        item {
            Group("⬆  Обновяване") {
                Text2("Инсталирана версия: ${Graph.updater.currentVersion}", 13, C.dim, FontWeight.Medium, 1)
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
                                if (l != null && Graph.updater.isNewer(l.tag)) { release = l } else { release = null; updMsg = "Имаш последната версия ✓" }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                updMsg = "Грешка: ${e.message}"
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
                                    updMsg = Graph.updater.install(f) ?: "Стартиране на инсталатора…"
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    progress = -1f
                                    updMsg = "Грешка: ${e.message}"
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
            Group("📊  Място и скорост") {
                val places = remember(cacheTick) { app.torrtv.core.Storage.places(ctx) }
                var storageId by remember { mutableStateOf(prefs.storageId) }
                val cur = places.firstOrNull { it.id == storageId } ?: places.first()
                val free = remember(cur, cacheTick) { app.torrtv.core.Storage.free(cur) }
                var netMbps by remember { mutableStateOf(prefs.netMbps) }
                var diskMBps by remember { mutableStateOf(prefs.diskMBps) }
                var netBusy by remember { mutableStateOf(false) }
                var diskBusy by remember { mutableStateOf(false) }
                var testMsg by remember { mutableStateOf("") }
                Text2("Къде се тегли филмът, докато се гледа:", 12, C.dim, FontWeight.Bold, 1)
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
                    "На избраното място могат да се гледат файлове до около " + CacheManager.fmt(app.torrtv.core.StreamFit.usableBytes(free)) +
                        ". " + (if (places.size == 1) "Външен диск (USB или SD карта) се появява тук, когато е включен. " else "") +
                        "Работата с външен диск зависи от модела на устройството и от скоростта на самия диск - проверява се с теста на записа.",
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
                            } else testMsg = "Тестът на записа не успя (няма достъп или място)."
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
                            } else testMsg = "Тестът на мрежата не успя - няма връзка със сървърите за измерване."
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
                            "Препоръчан буфер: напред " + (if (rec >= 1000) "1 GB" else "$rec MB") +
                                (if (netMbps < 20) " (връзката е бавна - по-голям буфер)" else ""),
                            12, C.text, FontWeight.Medium, 2, Modifier.weight(1f)
                        )
                        PrimaryButton(if (streamWindow == rec) "Зададен ✓" else "Задай", { streamWindow = rec; prefs.streamWindowMb = rec }, primary = false)
                    }
                }
            }
        }

        item {
            Group("💾  Памет и кеш") {
                Text2(
                    "RAM на приложението: ${mem.totalMb} MB   (интерфейс ${mem.pssMb} MB" +
                        (if (mem.torrentMb > 0) " + торент двигател ${mem.torrentMb} MB" else "") +
                        " · Java ${mem.javaMb} MB · native ${mem.nativeMb} MB)",
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
                Text2("Автоматично чистене:", 12, C.dim, FontWeight.Bold, 1)
                Spacer(Modifier.height(4.d))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    items(CacheManager.intervals, key = { it.first }) { (h, label) ->
                        Chip(label, cacheHours == h, { cacheHours = h; prefs.cacheHours = h; prefs.lastClean = System.currentTimeMillis() })
                    }
                }
                Spacer(Modifier.height(8.d))
                Text2("Чисти, когато кешът надхвърли:", 12, C.dim, FontWeight.Bold, 1)
                Spacer(Modifier.height(4.d))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    items(CacheManager.limits, key = { it.first }) { (mb, label) ->
                        Chip(label, cacheLimit == mb, { cacheLimit = mb; prefs.cacheLimitMb = mb })
                    }
                }
                Spacer(Modifier.height(8.d))
                Text2("Теглене при гледане:", 12, C.dim, FontWeight.Bold, 1)
                Spacer(Modifier.height(4.d))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    items(listOf(0 to "Класическо", 200 to "Напред 200 MB", 500 to "Напред 500 MB", 1000 to "Напред 1 GB"), key = { it.first }) { (mb, label) ->
                        Chip(label, streamWindow == mb, { streamWindow = mb; prefs.streamWindowMb = mb })
                    }
                }
                Spacer(Modifier.height(4.d))
                Text2(
                    "Класическо: торентът се тегли докрай, докато гледаш. Напред: тегли се само избраният размер пред мястото на гледане - " +
                        "за дълги и големи файлове при малко свободно място. Вече изгледаното остава на диска до края на гледането. Важи от следващото пускане.",
                    11, C.dim, FontWeight.Normal, 4
                )
                Spacer(Modifier.height(8.d))
                PrimaryButton(if (cleared) "Изчистено ✓" else "Изчисти кеша сега", {
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
            Group("⚡  Лек режим") {
                Text2(
                    "За по-слаби телевизори: без анимации на фокуса и плавни преходи, по-малки картинки. Включи го, ако менютата се движат на пресекулки.",
                    12, C.dim, FontWeight.Normal, 3
                )
                Spacer(Modifier.height(6.d))
                Row(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    Chip("Изключен", !lite, { lite = false; prefs.lite = false; Look.lite = false })
                    Chip("Включен", lite, { lite = true; prefs.lite = true; Look.lite = true })
                }
            }
        }

        item {
            Group("🌐  Уеб сайтове") {
                Text2(
                    "Когато торентът няма сийдъри, филмът може да се гледа онлайн от сайт (в страницата на филма, бутон „Уеб сайтове“). Рекламите се блокират. " +
                        "Тази част е отделна от торентите - ако не я искаш, я изключи и ще се виждат само торентите.",
                    12, C.dim, FontWeight.Normal, 4
                )
                Spacer(Modifier.height(6.d))
                Row(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    Chip("Изключени", !webOn, { webOn = false; prefs.webEnabled = false })
                    Chip("Включени", webOn, { webOn = true; prefs.webEnabled = true })
                }
                Spacer(Modifier.height(8.d))
                Text2("Когато сайтът пусне видеото:", 12, C.dim, FontWeight.Bold, 1)
                Spacer(Modifier.height(4.d))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.d)) {
                    items(
                        listOf(0 to "Питай ме", 1 to "Автоматично във вградения плеър", 2 to "Остани на сайта"),
                        key = { it.first }
                    ) { (m, label) -> Chip(label, webHandoff == m, { webHandoff = m; prefs.webHandoff = m }) }
                }
                Spacer(Modifier.height(6.d))
                Text2(
                    "Адресите на сайтовете се сменят от телефона - същата страница като за ключовете, в раздел „Уеб сайтове“. " +
                        "Ако един сайт спре да работи, махни реда му там.",
                    11, C.dim, FontWeight.Normal, 3
                )
            }
        }

        item {
            Group("⚙  Торент режим") {
                Text2(
                    "Ако филмът спре веднага след старта, смени режима. Приложението само сменя режима след срив, но тук можеш да го избереш и ръчно.",
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
            Group("🩺  Диагностика") {
                Text2("Тества всеки торент сайт, доставчик на субтитри и TMDB и показва кой не работи.", 12, C.dim, FontWeight.Normal, 2)
                Spacer(Modifier.height(6.d))
                Row(horizontalArrangement = Arrangement.spacedBy(10.d)) {
                    PrimaryButton(if (diagRunning) "Тества се…" else "Тествай източниците", {
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
            Group("📜  Дневник (при проблем покажи го на разработчика)") {
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
}

@Composable
private fun StatusPill(label: String, ok: Boolean) {
    Pill((if (ok) "✓ " else "✗ ") + label, if (ok) androidx.compose.ui.graphics.Color(0x333DDC84) else androidx.compose.ui.graphics.Color(0x33FF5C6C), if (ok) C.green else C.red)
}
