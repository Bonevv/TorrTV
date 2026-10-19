package app.torrtv.core

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("torrtv_prefs", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = ""): String = sp.getString(key, def) ?: def
    private fun put(key: String, v: String) {
        sp.edit().putString(key, v.trim()).apply()
    }

    /** Вградените ключове се ползват само докато няма услуга за настройки (виж Remote.URL). */
    private fun dflt(v: String): String = if (app.torrtv.data.Remote.enabled) "" else v

    // ---- API ключове (въвеждат се от телефона през Настройки) ----
    var tmdbKey: String
        get() = str("tmdb_key").ifBlank { dflt("d2c1e02ec9baf771e1ce6b4511a5aea8") }
        set(v) = put("tmdb_key", v)
    var osKey: String
        get() = str("os_key").ifBlank { dflt("4tGuxbPYtl1GjgrC2BGflxnn4Khg7kwD") }
        set(v) = put("os_key", v)
    var osUser: String
        get() = str("os_user")
        set(v) = put("os_user", v)
    var osPass: String
        get() = str("os_pass")
        set(v) = put("os_pass", v)
    var subdlKey: String
        get() = str("subdl_key").ifBlank { dflt("subdl_rXlwOT-Ef-4xAljUNAsJnUX1V7yjpSEUTP0P6hBmsFw") }
        set(v) = put("subdl_key", v)
    var subsourceKey: String
        get() = str("subsource_key").ifBlank { dflt("sk_7cd6bf070b192defbfe29842f7a7a4396f7f722eca793c33c889fc9b02386648") }
        set(v) = put("subsource_key", v)

    // ---- Профил от услугата за настройки (виж Remote) ----
    var syncOk: Boolean
        get() = sp.getBoolean("sync_ok", false)
        set(v) {
            sp.edit().putBoolean("sync_ok", v).apply()
        }
    var syncNum: String
        get() = str("sync_num")
        set(v) = put("sync_num", v)
    var syncPin: String
        get() = str("sync_pin")
        set(v) = put("sync_pin", v)
    var syncRev: String
        get() = str("sync_rev")
        set(v) = put("sync_rev", v)
    var syncName: String
        get() = str("sync_name")
        set(v) = put("sync_name", v)
    var syncAt: Long
        get() = sp.getLong("sync_at", 0L)
        set(v) {
            sp.edit().putLong("sync_at", v).apply()
        }

    /** Месецът (година*100+месец) на последната проверка за нови настройки. */
    var syncMonth: Int
        get() = sp.getInt("sync_month", 0)
        set(v) {
            sp.edit().putInt("sync_month", v).apply()
        }

    /** Свои тракери (по един на ред); празно - стандартният списък. */
    var trackersSrc: String
        get() = str("trackers_src")
        set(v) = put("trackers_src", v)
    val trackersList: List<String>
        get() = trackersSrc.lines().map { it.trim() }.filter { it.isNotEmpty() }

    // ---- Домейни (по желание, ако сайтът смени адреса си) ----
    var domApibay: String
        get() = str("dom_apibay")
        set(v) = put("dom_apibay", v)
    var domYts: String
        get() = str("dom_yts")
        set(v) = put("dom_yts", v)
    var domEztv: String
        get() = str("dom_eztv")
        set(v) = put("dom_eztv", v)
    var domNyaa: String
        get() = str("dom_nyaa")
        set(v) = put("dom_nyaa", v)
    var domKnaben: String
        get() = str("dom_knaben")
        set(v) = put("dom_knaben", v)
    var domCsv: String
        get() = str("dom_csv")
        set(v) = put("dom_csv", v)
    var domTorrentsdb: String
        get() = str("dom_torrentsdb")
        set(v) = put("dom_torrentsdb", v)
    var domTorrentio: String
        get() = str("dom_torrentio")
        set(v) = put("dom_torrentio", v)

    /** Свои торент сайтове от услугата (един на ред, вж. GenericSite). */
    var sitesSrc: String
        get() = str("sites_src")
        set(v) = put("sites_src", v)

    /** Източници, които услугата е изключила за всички: не се показват и не се търси в тях. */
    var srcOffSrc: String
        get() = str("src_off_src")
        set(v) = put("src_off_src", v)
    val srcOff: Set<String>
        get() = srcOffSrc.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Дистанционно от телефона по време на филм: 0 = само на силни устройства, 1 = включено, 2 = изключено. */
    var remoteCtl: Int
        get() = sp.getInt("remote_ctl", 0)
        set(v) { sp.edit().putInt("remote_ctl", v).apply() }

    /** Проверката на бокса след първия вход е показана. */
    var boxChecked: Boolean
        get() = sp.getBoolean("box_checked", false)
        set(v) { sp.edit().putBoolean("box_checked", v).apply() }

    // ---- Настройки ----
    /** builtin | vlc | mx | just | ask */
    var player: String
        get() = str("player", "builtin")
        set(v) = put("player", v)

    /** bg | en - език на описанията от TMDB */
    var metaLang: String
        get() = str("meta_lang", "bg")
        set(v) = put("meta_lang", v)

    /** На колко часа се чисти кешът автоматично (0 = изключено). */
    var cacheHours: Int
        get() = sp.getInt("cache_hours", 12)
        set(v) {
            sp.edit().putInt("cache_hours", v).apply()
        }

    /**
     * Режим на торент двигателя (0, 1, 2). При срив на торент процеса приложението само превключва
     * към следващия режим, така че следващият опит да мине по друг път в native кода.
     */
    var engineProfile: Int
        get() = sp.getInt("engine_mode_v2", 0).coerceIn(0, 2)
        set(v) {
            sp.edit().putInt("engine_mode_v2", v.coerceIn(0, 2)).apply()
        }

    // ---- Уеб източници (адреси с {tmdb} {imdb} {season} {episode}, по един на ред) ----
    var webMovieSrc: String
        get() = str("web_movie")
        set(v) = put("web_movie", v)
    var webTvSrc: String
        get() = str("web_tv")
        set(v) = put("web_tv", v)

    /** Свои Stremio addon-и за торенти (по един адрес на ред). */
    var addonsSrc: String
        get() = str("addons")
        set(v) = put("addons", v)

    /** Показва ли се "уеб сайтове" като място за гледане. */
    var webEnabled: Boolean
        get() = sp.getBoolean("web_enabled", true)
        set(v) {
            sp.edit().putBoolean("web_enabled", v).apply()
        }

    /** Какво става, когато уеб сайтът пусне видео: 0 = питай, 1 = автоматично във вградения плеър, 2 = остани на сайта. */
    var webHandoff: Int
        get() = sp.getInt("web_handoff", 0).coerceIn(0, 2)
        set(v) {
            sp.edit().putInt("web_handoff", v.coerceIn(0, 2)).apply()
        }

    /** Колко MB напред се тегли при гледане (0 = класически: целият файл). */
    var streamWindowMb: Int
        get() = sp.getInt("stream_window_mb", 0)
        set(v) {
            sp.edit().putInt("stream_window_mb", v).apply()
        }

    /** Къде се тегли филмът при гледане: "internal" или "usb". */
    var storageId: String
        get() = str("storage_id", "internal")
        set(v) = put("storage_id", v)

    /** Последно измерени скорости (0 = не са мерени). */
    var netMbps: Float
        get() = sp.getFloat("net_mbps", 0f)
        set(v) {
            sp.edit().putFloat("net_mbps", v).apply()
        }
    var diskMBps: Float
        get() = sp.getFloat("disk_mbps", 0f)
        set(v) {
            sp.edit().putFloat("disk_mbps", v).apply()
        }

    /** Лимит на кеша в MB (0 = без лимит): при надвишаване се чисти автоматично. */
    var cacheLimitMb: Int
        get() = sp.getInt("cache_limit_mb", 500)
        set(v) {
            sp.edit().putInt("cache_limit_mb", v).apply()
        }

    /**
     * Лек режим (без анимации на фокуса и плавните преходи, по-малки картинки): 0 = сам, според устройството;
     * 1 = включен; 2 = изключен. Докато не е избирано нищо, ключът липсва и важи "сам".
     */
    var liteChoice: Int
        get() = if (!sp.contains("lite_mode")) 0 else if (sp.getBoolean("lite_mode", false)) 1 else 2
        set(v) {
            val e = sp.edit()
            if (v == 0) e.remove("lite_mode") else e.putBoolean("lite_mode", v == 1)
            e.apply()
        }

    /** Дали лекият режим е в сила: избраното от потребителя, а при "сам" - дали устройството е слабо. */
    fun liteOn(weakDevice: Boolean): Boolean = when (liteChoice) {
        1 -> true
        2 -> false
        else -> weakDevice
    }

    var lastClean: Long
        get() = sp.getLong("last_clean", 0L)
        set(v) {
            sp.edit().putLong("last_clean", v).apply()
        }

    var subLangs: Set<String>
        get() = sp.getStringSet("sub_langs", null)?.toSet() ?: setOf("bg", "en")
        set(v) {
            sp.edit().putStringSet("sub_langs", v).apply()
        }

    /** Дали субтитрите се свалят сами при пускане (иначе се избират в плеъра или в диалога и се теглят само тогава). */
    var subAuto: Boolean
        get() = sp.getBoolean("sub_auto", false)
        set(v) {
            sp.edit().putBoolean("sub_auto", v).apply()
        }

    /** Размер на субтитрите във вградения плеър: 0 малки, 1 средни, 2 големи, 3 много големи. */
    var subSize: Int
        get() = sp.getInt("sub_size", 1).coerceIn(0, 3)
        set(v) {
            sp.edit().putInt("sub_size", v.coerceIn(0, 3)).apply()
        }

    /** Оформление на субтитрите: 0 като в системата, 1 контур, 2 фон, 3 сянка. */
    var subEdge: Int
        get() = sp.getInt("sub_edge", 1).coerceIn(0, 3)
        set(v) {
            sp.edit().putInt("sub_edge", v.coerceIn(0, 3)).apply()
        }

    /** Позиция на субтитрите: 0 ниско, 1 стандартно, 2 високо. */
    var subLift: Int
        get() = sp.getInt("sub_lift", 1).coerceIn(0, 2)
        set(v) {
            sp.edit().putInt("sub_lift", v.coerceIn(0, 2)).apply()
        }

    /** Предпочитан език на звука: auto (какъвто е във файла) | orig (оригиналният на филма) | bg | en. */
    var audioLang: String
        get() = str("audio_lang", "auto")
        set(v) = put("audio_lang", v)

    /** Пуска ли се следващият епизод сам, когато свърши текущият (във вградения плеър). */
    var autoNext: Boolean
        get() = sp.getBoolean("auto_next", true)
        set(v) {
            sp.edit().putBoolean("auto_next", v).apply()
        }

    /** Тракери, които не са отговорили при последната проверка (валидно 7 дни, после се пробват пак). */
    val deadTrackers: Set<String>
        get() {
            val at = sp.getLong("dead_trk_at", 0L)
            if (at == 0L || System.currentTimeMillis() - at > 7L * 24 * 3_600_000) return emptySet()
            return sp.getStringSet("dead_trk", null)?.toSet() ?: emptySet()
        }

    /** Кога е правена последната проверка на тракерите (0 = никога). */
    val trackersCheckedAt: Long get() = sp.getLong("dead_trk_at", 0L)

    fun saveDeadTrackers(dead: Set<String>) {
        sp.edit().putStringSet("dead_trk", dead).putLong("dead_trk_at", System.currentTimeMillis()).apply()
    }

    /** Последните търсения, по един на ред (виж RecentSearches). */
    var recentSearches: String
        get() = sp.getString("recent_search", "") ?: ""
        set(v) {
            sp.edit().putString("recent_search", v).apply()
        }

    fun sourceEnabled(id: String): Boolean = sp.getBoolean("src_$id", true)
    fun setSourceEnabled(id: String, on: Boolean) {
        sp.edit().putBoolean("src_$id", on).apply()
    }

    // ---- Масово записване от уеб страницата за телефона ----
    companion object {
        val FIELD_NAMES = listOf(
            "tmdb_key", "os_key", "os_user", "os_pass", "subdl_key", "subsource_key",
            "dom_apibay", "dom_yts", "dom_eztv", "dom_nyaa", "web_movie", "web_tv", "addons"
        )
    }

    fun get(name: String): String = when (name) {
        "tmdb_key" -> tmdbKey
        "os_key" -> osKey
        "os_user" -> osUser
        "os_pass" -> osPass
        "subdl_key" -> subdlKey
        "subsource_key" -> subsourceKey
        "dom_apibay" -> domApibay
        "dom_yts" -> domYts
        "dom_eztv" -> domEztv
        "dom_nyaa" -> domNyaa
        "dom_knaben" -> domKnaben
        "dom_csv" -> domCsv
        "dom_torrentsdb" -> domTorrentsdb
        "dom_torrentio" -> domTorrentio
        "sites" -> sitesSrc
        "web_movie" -> webMovieSrc
        "web_tv" -> webTvSrc
        "addons" -> addonsSrc
        "trackers" -> trackersSrc
        else -> ""
    }

    fun set(name: String, value: String) {
        when (name) {
            "tmdb_key" -> tmdbKey = value
            "os_key" -> osKey = value
            "os_user" -> osUser = value
            "os_pass" -> osPass = value
            "subdl_key" -> subdlKey = value
            "subsource_key" -> subsourceKey = value
            "dom_apibay" -> domApibay = value
            "dom_yts" -> domYts = value
            "dom_eztv" -> domEztv = value
            "dom_nyaa" -> domNyaa = value
            "dom_knaben" -> domKnaben = value
            "dom_csv" -> domCsv = value
            "dom_torrentsdb" -> domTorrentsdb = value
            "dom_torrentio" -> domTorrentio = value
            "sites" -> sitesSrc = if (value.trim() == "-") "" else value
            "web_movie" -> webMovieSrc = if (value.trim() == "-") "" else value
            "web_tv" -> webTvSrc = if (value.trim() == "-") "" else value
            "addons" -> addonsSrc = if (value.trim() == "-") "" else value
            "trackers" -> trackersSrc = if (value.trim() == "-") "" else value
        }
    }

    fun isSet(name: String): Boolean = get(name).isNotBlank()
}
