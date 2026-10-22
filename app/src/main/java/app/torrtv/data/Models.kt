package app.torrtv.data

enum class MediaType(val path: String) { MOVIE("movie"), TV("tv") }

data class Media(
    val id: Int,
    val type: MediaType,
    val title: String,
    val originalTitle: String,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val year: String,
    val rating: Double,
    val votes: Int,
    val genreIds: List<Int>,
    val originalLanguage: String
) {
    val key: String get() = "${type.path}-$id"
}

data class Genre(val id: Int, val name: String)

data class Page<T>(val items: List<T>, val page: Int, val totalPages: Int)

data class SeasonInfo(val number: Int, val name: String, val episodes: Int)

data class Episode(
    val number: Int,
    val name: String,
    val overview: String,
    val stillPath: String?,
    val airDate: String,
    val runtime: Int
)

data class Detail(
    val media: Media,
    val englishTitle: String,
    val tagline: String,
    val runtime: Int,
    val genres: List<Genre>,
    val imdbId: String?,
    val cast: List<String>,
    val seasons: List<SeasonInfo>,
    val status: String,
    val isAnime: Boolean
)

/** Статични имена на жанровете - без излишни заявки към мрежата. */
/** Най-известните платформи. Кои от тях има в България и с какви номера, се взема от TMDB (виж [resolve]). */
object Platforms {
    /** [id] е един или няколко номера на TMDB, разделени с "|" (платформата има и вариант с реклами). */
    class Platform(val id: String, val name: String)

    /** Ако списъкът за България не може да се вземе. */
    val fallback: List<Platform> = listOf(
        Platform("8", "Netflix"), Platform("1899", "HBO Max"), Platform("350", "Apple TV+"), Platform("337", "Disney+")
    )

    private class Want(val name: String, val re: Regex)

    private val wanted = listOf(
        Want("Netflix", Regex("^netflix$")),
        Want("HBO Max", Regex("^(hbo max|max)$")),
        Want("Apple TV+", Regex("^apple tv( plus|\\+)?$")),
        Want("Disney+", Regex("^disney( plus|\\+)$")),
        Want("Prime Video", Regex("^(amazon )?prime video( with ads)?$")),
        Want("SkyShowtime", Regex("^skyshowtime$")),
        Want("Paramount+", Regex("^paramount( plus|\\+)$"))
    )

    /** [providers] - (номер, име) от TMDB за България. Връща само познатите платформи, които ги има там. */
    fun resolve(providers: List<Pair<Int, String>>): List<Platform> {
        val out = ArrayList<Platform>()
        for (w in wanted) {
            val ids = providers.filter { w.re.matches(it.second.trim().lowercase(java.util.Locale.ROOT)) }.map { it.first }.distinct()
            if (ids.isNotEmpty()) out.add(Platform(ids.joinToString("|"), w.name))
        }
        return out
    }
}

object Genres {
    /** Жанровете за раздел "Аниме": без Анимация (тя вече е в основата) и без неподходящите. */
    fun forAnime(type: MediaType): List<Genre> {
        val skip = setOf(16, 99, 10764, 10766, 10767, 10763, 10762, 37, 10402)
        return forType(type).filter { it.id !in skip }
    }

    val movie: List<Genre> = listOf(
        Genre(28, "Екшън"), Genre(12, "Приключенски"), Genre(16, "Анимация"), Genre(35, "Комедия"),
        Genre(80, "Криминален"), Genre(99, "Документален"), Genre(18, "Драма"), Genre(10751, "Семеен"),
        Genre(14, "Фентъзи"), Genre(36, "Исторически"), Genre(27, "Ужаси"), Genre(10402, "Музикален"),
        Genre(9648, "Мистерия"), Genre(10749, "Романтичен"), Genre(878, "Фантастика"),
        Genre(53, "Трилър"), Genre(10752, "Военен"), Genre(37, "Уестърн")
    )

    val tv: List<Genre> = listOf(
        Genre(10759, "Екшън и приключения"), Genre(16, "Анимация"), Genre(35, "Комедия"),
        Genre(80, "Криминален"), Genre(99, "Документален"), Genre(18, "Драма"), Genre(10751, "Семеен"),
        Genre(10762, "Детски"), Genre(9648, "Мистерия"), Genre(10764, "Риалити"),
        Genre(10765, "Фантастика и фентъзи"), Genre(10768, "Война и политика"), Genre(37, "Уестърн")
    )

    private val all: Map<Int, String> = (movie + tv).associate { it.id to it.name }

    fun name(id: Int): String? = all[id]
    fun forType(t: MediaType): List<Genre> = if (t == MediaType.MOVIE) movie else tv
}
