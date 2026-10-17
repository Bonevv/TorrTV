package app.torrtv.core

import android.content.Context
import android.content.SharedPreferences
import app.torrtv.BuildConfig

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("torrtv_prefs", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = ""): String = sp.getString(key, def) ?: def
    private fun put(key: String, v: String) {
        sp.edit().putString(key, v.trim()).apply()
    }

    // ---- API ключове (въвеждат се от телефона през Настройки) ----
    var tmdbKey: String
        get() = str("tmdb_key").ifBlank { BuildConfig.K_TMDB }
        set(v) = put("tmdb_key", v)
    var osKey: String
        get() = str("os_key").ifBlank { BuildConfig.K_OS_KEY }
        set(v) = put("os_key", v)
    var osUser: String
        get() = str("os_user").ifBlank { BuildConfig.K_OS_USER }
        set(v) = put("os_user", v)
    var osPass: String
        get() = str("os_pass").ifBlank { BuildConfig.K_OS_PASS }
        set(v) = put("os_pass", v)
    var subdlKey: String
        get() = str("subdl_key").ifBlank { BuildConfig.K_SUBDL }
        set(v) = put("subdl_key", v)
    var subsourceKey: String
        get() = str("subsource_key").ifBlank { BuildConfig.K_SUBSOURCE }
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
