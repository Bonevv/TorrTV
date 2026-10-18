package app.torrtv.state

import app.torrtv.data.subs.SubPref

/**
 * Помни кой субтитър е избран на ръка за дадено заглавие (език, доставчик, група), за да се предлага първи
 * и за следващите епизоди. Стойността е "език|доставчик|групи|кога"; най-старите се махат при преливане.
 */
class SubChoiceMemory(private val kv: Kv, private val now: () -> Long = { System.currentTimeMillis() }) {

    fun get(mediaKey: String): SubPref? {
        val p = kv.get(mediaKey)?.split('|') ?: return null
        if (p.size < 3 || p[0].isBlank()) return null
        return SubPref(p[0], p[1], p[2].split(',').filter { it.isNotBlank() }.toSet())
    }

    fun set(mediaKey: String, pref: SubPref) {
        kv.put(mediaKey, listOf(pref.lang, pref.provider, pref.groups.joinToString(","), now().toString()).joinToString("|"))
        val all = kv.keys()
        if (all.size > MAX) {
            val oldest = all.sortedBy { kv.get(it)?.substringAfterLast('|')?.toLongOrNull() ?: 0L }
            for (k in oldest.take(all.size - MAX + 10)) kv.remove(k)
        }
    }

    companion object {
        const val MAX = 80
    }
}

/** Общото хранилище на приложението (SharedPreferences "subchoice"). */
object SubChoice {
    private val kv = object : Kv {
        private val sp get() = Graph.app.getSharedPreferences("subchoice", android.content.Context.MODE_PRIVATE)
        override fun get(key: String): String? = sp.getString(key, null)
        override fun put(key: String, value: String) {
            sp.edit().putString(key, value).apply()
        }

        override fun remove(key: String) {
            sp.edit().remove(key).apply()
        }

        override fun keys(): Collection<String> = sp.all.keys
    }
    val memory = SubChoiceMemory(kv)
}
