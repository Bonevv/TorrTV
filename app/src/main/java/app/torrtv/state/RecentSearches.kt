package app.torrtv.state

/** Последните търсения (най-новото първо, без повторения). Чиста логика; пази се като по един ред на търсене. */
object RecentSearches {
    const val MAX = 8
    const val MAX_LEN = 80

    /** Един ред, без излишни интервали и нови редове; празното и твърде късото (под 2 знака) се отхвърля. */
    fun clean(q: String): String? {
        val t = q.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim().replace(Regex("\\s+"), " ")
        if (t.length < 2) return null
        return t.take(MAX_LEN)
    }

    /** Добавя търсене най-отпред. Същото търсене (без значение от главни/малки букви) не се повтаря. */
    fun add(list: List<String>, q: String, max: Int = MAX): List<String> {
        val t = clean(q) ?: return list
        val out = ArrayList<String>()
        out.add(t)
        for (s in list) if (!s.equals(t, ignoreCase = true)) out.add(s)
        return out.take(max)
    }

    fun encode(list: List<String>): String = list.joinToString("\n")

    fun decode(text: String, max: Int = MAX): List<String> {
        val out = ArrayList<String>()
        for (line in text.lines()) {
            val t = clean(line) ?: continue
            if (out.none { it.equals(t, ignoreCase = true) }) out.add(t)
        }
        return out.take(max)
    }
}
