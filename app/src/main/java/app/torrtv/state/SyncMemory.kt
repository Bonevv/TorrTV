package app.torrtv.state

/** Най-малкото нужно от хранилище на ключ-стойност (в тестовете - обикновена карта). */
interface Kv {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(): Collection<String>
}

/**
 * Помни синхрона на субтитрите за всеки филм / епизод и субтитър, за да не се настройва втори път.
 * Стойността е "изместване_мс:кога" - най-старите се махат, когато записите станат твърде много.
 */
class SyncMemory(private val kv: Kv, private val now: () -> Long = { System.currentTimeMillis() }) {

    fun get(key: String): Long = kv.get(key)?.substringBefore(':')?.toLongOrNull() ?: 0L

    fun set(key: String, offsetMs: Long) {
        if (offsetMs == 0L) kv.remove(key) else kv.put(key, "$offsetMs:${now()}")
        prune()
    }

    private fun prune() {
        val all = kv.keys()
        if (all.size <= MAX) return
        val oldest = all.sortedBy { kv.get(it)?.substringAfter(':', "")?.toLongOrNull() ?: 0L }
        for (k in oldest.take(all.size - MAX + 50)) kv.remove(k)
    }

    companion object {
        const val MAX = 400

        fun key(mediaKey: String, season: Int?, episode: Int?, subUid: String): String =
            "$mediaKey|${season ?: 0}x${episode ?: 0}|$subUid"
    }
}

/** Общото хранилище на приложението (SharedPreferences "sync"). */
object Sync {
    private val kv = object : Kv {
        private val sp get() = Graph.app.getSharedPreferences("sync", android.content.Context.MODE_PRIVATE)
        override fun get(key: String): String? = sp.getString(key, null)
        override fun put(key: String, value: String) {
            sp.edit().putString(key, value).apply()
        }

        override fun remove(key: String) {
            sp.edit().remove(key).apply()
        }

        override fun keys(): Collection<String> = sp.all.keys
    }
    val memory = SyncMemory(kv)
}
