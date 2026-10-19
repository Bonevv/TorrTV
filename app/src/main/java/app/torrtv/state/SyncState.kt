package app.torrtv.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.torrtv.BuildConfig
import app.torrtv.core.AppLog
import app.torrtv.core.Magnets
import app.torrtv.core.Prefs
import app.torrtv.data.Remote
import app.torrtv.data.subs.OpenSubtitles
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Профилът от услугата за настройки: вход с номер и ПИН, ръчно обновяване и проверка веднъж в месеца.
 * Нищо не върви на заден план - само при вход, при бутона „Обнови настройките“ и при първото пускане през месеца.
 */
class SyncState(private val prefs: Prefs) {

    /** Без услуга всичко е отключено; с услуга - само след вход. */
    var loggedIn by mutableStateOf(!Remote.enabled || prefs.syncOk)
        private set

    /** Защо приложението е върнато на входа (показва се веднъж на входния екран). */
    var notice by mutableStateOf("")
        private set

    fun clearNotice() {
        notice = ""
    }

    val number: String get() = prefs.syncNum
    val name: String get() = prefs.syncName
    val lastAt: Long get() = prefs.syncAt

    private fun applySet(set: JSONObject) {
        Remote.apply(set) { k, v -> prefs.set(k, v) }
        Magnets.setCustom(prefs.trackersList)
        // Изключените за всички се скриват; махнатите от списъка се връщат включени. Личният избор за другите не се пипа.
        val off = Remote.disabledSources(set)
        val prev = prefs.srcOff
        prefs.srcOffSrc = off.joinToString("\n")
        for (s in Graph.torrents.allSources) {
            if (s.id in off) prefs.setSourceEnabled(s.id, false) else if (s.id in prev) prefs.setSourceEnabled(s.id, true)
        }
        // Нов акаунт или ключ: старият вход в OpenSubtitles вече не важи.
        Graph.subs.providers.filterIsInstance<OpenSubtitles>().forEach { it.reset() }
    }

    private fun remember(r: Remote.Result, now: Long) {
        prefs.syncName = r.name
        prefs.syncRev = r.rev
        prefs.syncAt = now
        prefs.syncMonth = Remote.monthKey(now)
    }

    /** Вход с номер и ПИН. При успех настройките се записват и приложението се отключва. */
    suspend fun login(num: String, pin: String): Remote.Result {
        val r = Remote.sync(num, pin, "", BuildConfig.VERSION_NAME)
        if (r.ok) {
            r.set?.let { applySet(it) }
            prefs.syncNum = num
            prefs.syncPin = pin
            remember(r, System.currentTimeMillis())
            prefs.syncOk = true
            loggedIn = true
        }
        return r
    }

    /** Пита за нови настройки (ръчно или веднъж в месеца). Изключен профил или сменен ПИН връщат на входа. */
    suspend fun refresh(): Remote.Result {
        val r = Remote.sync(prefs.syncNum, prefs.syncPin, prefs.syncRev, BuildConfig.VERSION_NAME)
        when {
            r.ok -> {
                if (r.changed) r.set?.let { applySet(it) }
                remember(r, System.currentTimeMillis())
            }
            r.err == Remote.Err.DISABLED -> {
                notice = "Профилът е изключен."
                logout()
            }
            r.err == Remote.Err.BAD -> {
                notice = "Номерът или ПИН-ът вече не важи. Въведи новите."
                logout()
            }
            else -> AppLog.d("sync", "обновяването не стана: " + r.err)
        }
        return r
    }

    /** Излизане: забравя профила и полетата от услугата, за да не остане нищо за следващия. */
    fun logout() {
        prefs.syncOk = false
        prefs.syncPin = ""
        prefs.syncRev = ""
        prefs.syncMonth = 0
        val empty = JSONObject()
        for (f in Remote.STR_FIELDS) empty.put(f, "")
        applySet(empty)
        for (f in Remote.LIST_FIELDS) prefs.set(f, "")
        Magnets.setCustom(emptyList())
        loggedIn = false
    }

    /** Първото пускане в новия месец: тиха проверка. При грешка (няма връзка) не се прави нищо - пак следващия път. */
    fun monthlyCheck() {
        if (!Remote.enabled || !loggedIn) return
        if (!Remote.due(prefs.syncMonth, System.currentTimeMillis())) return
        Graph.scope.launch { refresh() }
    }
}
