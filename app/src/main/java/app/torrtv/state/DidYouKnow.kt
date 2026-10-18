package app.torrtv.state

import app.torrtv.data.Detail
import app.torrtv.data.MediaType

/** Кратки факти за заглавието и съвети за приложението - показват се, докато филмът се зарежда. Чиста логика. */
object DidYouKnow {

    val tips: List<String> = listOf(
        "Докато гледаш, OK показва контролите, а стрелките наляво и надясно превъртат.",
        "Ако субтитрите изостават или избързват, намести ги с бутоните горе вдясно в плеъра.",
        "Мястото, до което си стигнал, се помни. „Продължи гледане“ започва оттам.",
        "Не можеш да решиш какво да гледаш? Пробвай зарчето от менюто.",
        "Търсенето може да се пише и от телефона. Адресът се вижда на екрана „Търсене“.",
        "Торентът не тръгва? „Откъде?“ показва други торенти и сайтове.",
        "От бутона „Субтитри“ в плеъра можеш да смениш субтитрите или да ги изключиш."
    )

    /** 142 -> "2 ч. 22 мин.", 45 -> "45 мин.", 120 -> "2 ч." */
    fun runtimeText(min: Int): String {
        if (min <= 0) return ""
        val h = min / 60
        val m = min % 60
        return when {
            h == 0 -> "$m мин."
            m == 0 -> "$h ч."
            else -> "$h ч. $m мин."
        }
    }

    /** "А", "А и Б", "А, Б и В". */
    fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " и " + names.last()
    }

    /** Факти от данните, които вече имаме за заглавието (нищо не се измисля и не се тегли отнякъде). */
    fun facts(d: Detail): List<String> {
        val tv = d.media.type == MediaType.TV
        val what = if (tv) "Сериалът" else "Филмът"
        val out = ArrayList<String>()
        if (d.tagline.isNotBlank()) out.add("Слоганът е „${d.tagline.trim()}“.")
        val cast = d.cast.filter { it.isNotBlank() }.take(3)
        if (cast.size >= 2) out.add("В главните роли са ${joinNames(cast)}.")
        if (tv) {
            val seasons = d.seasons.count { it.number > 0 }
            if (seasons > 0) out.add("Сериалът има $seasons ${if (seasons == 1) "сезон" else "сезона"}.")
        } else if (d.runtime > 0) {
            out.add("Филмът трае ${runtimeText(d.runtime)}") // runtimeText завършва с точка
        }
        val orig = d.media.originalTitle.trim()
        if (orig.isNotBlank() && !orig.equals(d.media.title.trim(), ignoreCase = true)) out.add("Оригиналното заглавие е „$orig“.")
        if (d.media.rating > 0 && d.media.votes >= 100) {
            out.add("В TMDB има оценка " + String.format(java.util.Locale.US, "%.1f", d.media.rating) + " от ${d.media.votes} гласа.")
        }
        if (d.media.year.isNotBlank()) out.add("$what е от ${d.media.year} г.")
        return out.take(6)
    }

    /** Редува факти и съвети (първо факт), докато свършат и двете. */
    fun mix(facts: List<String>, tips: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < facts.size || i < tips.size) {
            if (i < facts.size) out.add(facts[i])
            if (i < tips.size) out.add(tips[i])
            i++
        }
        return out
    }
}
