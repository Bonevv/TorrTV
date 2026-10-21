package app.torrtv.data

import app.torrtv.core.Http
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Връзка с услугата за настройки (Cloudflare Worker). Приложението пита с номер и ПИН и получава ключовете
 * и списъците на своя профил. Празен [URL] - услугата не се ползва: няма вход и ключовете се въвеждат ръчно.
 */
object Remote {
    /** Адресът на услугата, например "https://torrtv.нещо.workers.dev". Празно - без вход. */
    const val URL = "https://torrtv.xaxaoo.workers.dev"

    val enabled: Boolean get() = URL.isNotBlank()

    /** Низовите полета, които услугата раздава (имената са същите като на страницата за телефона). */
    val STR_FIELDS = listOf(
        "tmdb_key", "os_key", "os_user", "os_pass", "subdl_key", "subsource_key",
        "dom_apibay", "dom_yts", "dom_eztv", "dom_nyaa",
        "dom_knaben", "dom_csv", "dom_torrentsdb", "dom_torrentio"
    )

    /** Списъците (един елемент на ред): уеб сайтове, addon-и и тракери. */
    val LIST_FIELDS = listOf("web_movie", "web_tv", "addons", "trackers", "sites")

    enum class Err { NONE, BAD, LOCKED, DISABLED, TOO_MANY, NETWORK, SERVER }

    class Result(
        val err: Err,
        val changed: Boolean = false,
        val name: String = "",
        val rev: String = "",
        val set: JSONObject? = null,
        val waitSec: Int = 0
    ) {
        val ok: Boolean get() = err == Err.NONE
    }

    /** Разчита отговора на услугата (HTTP код и тяло). */
    fun parse(code: Int, body: String): Result {
        val j = try {
            JSONObject(body)
        } catch (e: Exception) {
            return Result(Err.SERVER)
        }
        if (code == 200 && j.optBoolean("ok")) {
            return Result(Err.NONE, j.optBoolean("changed"), j.optString("name"), j.optString("rev"), j.optJSONObject("set"))
        }
        return when (j.optString("err")) {
            "bad" -> Result(Err.BAD)
            "locked" -> Result(Err.LOCKED, waitSec = j.optInt("wait"))
            "disabled" -> Result(Err.DISABLED)
            "ip" -> Result(Err.TOO_MANY)
            else -> Result(Err.SERVER)
        }
    }

    /** Пита услугата. [have] е ревизията, която вече имаме ("" при първо влизане). */
    suspend fun sync(num: String, pin: String, have: String, version: String): Result {
        if (!enabled) return Result(Err.SERVER)
        return try {
            val body = JSONObject().put("n", num).put("p", pin).put("have", have).put("v", version).toString()
            val r = Http.postJsonRaw(URL.trimEnd('/') + "/api/sync", body)
            parse(r.code, r.body)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result(Err.NETWORK)
        }
    }

    /** Елементите на списъка от отговора (без празните). */
    fun list(set: JSONObject, field: String): List<String> {
        val a = set.optJSONArray(field) ?: return emptyList()
        // JSON null не бива да стане думата "null" (optString го превръща в текст)
        return (0 until a.length()).filter { !a.isNull(it) }.map { a.optString(it).trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Записва получените полета. [put] е Prefs.set: низовете се пишат както са, списъците - по един елемент на ред
     * (празен списък връща вградените стойности на приложението).
     */
    fun apply(set: JSONObject, put: (String, String) -> Unit) {
        for (f in STR_FIELDS) if (set.has(f)) put(f, if (set.isNull(f)) "" else set.optString(f).trim())
        for (f in LIST_FIELDS) if (set.has(f)) put(f, list(set, f).joinToString("\n"))
    }

    /** Идентификаторите на торент източниците, които услугата е изключила. */
    fun disabledSources(set: JSONObject): Set<String> = list(set, "src_off").toSet()

    /** Месецът като число (година*100+месец), по часовата зона на телевизора. */
    fun monthKey(millis: Long, tz: TimeZone = TimeZone.getDefault()): Int {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = millis
        return c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH) + 1
    }

    /** Време ли е за проверка: още не е правена в този месец (първото пускане след 1-во число). */
    fun due(lastMonth: Int, nowMillis: Long, tz: TimeZone = TimeZone.getDefault()): Boolean =
        lastMonth != monthKey(nowMillis, tz)

    /** Кратко съобщение за неуспех на български (null при успех). */
    fun message(r: Result): String? = when (r.err) {
        Err.NONE -> null
        Err.BAD -> "Грешен номер или ПИН."
        Err.LOCKED -> "Заключено за " + maxOf(1, (r.waitSec + 59) / 60) + " мин. заради грешни опити."
        Err.DISABLED -> "Профилът е изключен."
        Err.TOO_MANY -> "Твърде много опити. Опитай по-късно."
        Err.NETWORK -> "Няма връзка. Провери интернета и опитай пак."
        Err.SERVER -> "Услугата не отговаря. Опитай по-късно."
    }

    /** Добавя цифра към въвеждан номер или ПИН (най-много [max] цифри). */
    fun appendDigit(cur: String, digit: Char, max: Int): String =
        if (digit in '0'..'9' && cur.length < max) cur + digit else cur
}
