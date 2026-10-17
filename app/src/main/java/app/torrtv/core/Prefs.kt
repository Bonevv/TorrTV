package app.torrtv.core

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("torrtv_prefs", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = ""): String = sp.getString(key, def) ?: def
    private fun put(key: String, v: String) {
        sp.edit().putString(key, v.trim()).apply()
    }

    // ---- API ключове (въвеждат се от телефона през Настройки) ----
    var tmdbKey: String
        get() = str("tmdb_key").ifBlank { "d2c1e02ec9baf771e1ce6b4511a5aea8" }
        set(v) = put("tmdb_key", v)
    var osKey: String
        get() = str("os_key").ifBlank { "4tGuxbPYtl1GjgrC2BGflxnn4Khg7kwD" }
        set(v) = put("os_key", v)
    var osUser: String
        get() = str("os_user").ifBlank { "xaxaoo" }
        set(v) = put("os_user", v)
    var osPass: String
        get() = str("os_pass").ifBlank { "Lan4gogo" }
        set(v) = put("os_pass", v)
    var subdlKey: String
        get() = str("subdl_key").ifBlank { "subdl_rXlwOT-Ef-4xAljUNAsJnUX1V7yjpSEUTP0P6hBmsFw" }
        set(v) = put("subdl_key", v)
    var subsourceKey: String
        get() = str("subsource_key").ifBlank { "sk_7cd6bf070b192defbfe29842f7a7a4396f7f722eca793c33c889fc9b02386648" }
        set(v) = put("subsource_key", v)

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

    /** Лек режим: без анимации на фокуса и плавните преходи, по-малки картинки (за по-слаби телевизори). */
    var lite: Boolean
        get() = sp.getBoolean("lite_mode", false)
        set(v) {
            sp.edit().putBoolean("lite_mode", v).apply()
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

    fun sourceEnabled(id: String): Boolean = sp.getBoolean("src_$id", true)
    fun setSourceEnabled(id: String, on: Boolean) {
        sp.edit().putBoolean("src_$id", on).apply()
    }

    // ---- Масово записване от уеб страницата за телефона ----
    companion object {
        val FIELD_NAMES = listOf(
            "tmdb_key", "os_key", "os_user", "os_pass", "subdl_key", "subsource_key",
            "dom_apibay", "dom_yts", "dom_eztv", "dom_nyaa"
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
        }
    }

    fun isSet(name: String): Boolean = get(name).isNotBlank()
}
