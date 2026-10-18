package app.torrtv.state

import app.torrtv.data.subs.Release
import app.torrtv.data.torrents.TorrentAggregator
import app.torrtv.data.torrents.TorrentResult
import java.util.Calendar

/** Правилата за „Следващ епизод“: кой е следващият и кой торент да се пусне за него. Чиста логика, без мрежа. */
object NextEpisode {

    /** Най-малкият номер, по-голям от [current] (епизодите може да не са подред). */
    fun after(numbers: List<Int>, current: Int): Int? = numbers.filter { it > current }.minOrNull()

    /** Следващият сезон след [current]; сезон 0 (специални епизоди) не се брои. */
    fun nextSeason(seasons: List<Int>, current: Int): Int? = seasons.filter { it > current && it > 0 }.minOrNull()

    /** Излязъл ли е епизодът: празна дата значи „не се знае“ - допуска се. Датите са във вида 2026-10-18. */
    fun aired(airDate: String, today: String): Boolean = airDate.isBlank() || airDate <= today

    fun today(): String {
        val c = Calendar.getInstance()
        return String.format("%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun label(season: Int, episode: Int, name: String): String =
        "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}" + if (name.isNotBlank()) " · $name" else ""

    /**
     * Кой торент да се пусне за следващия епизод: най-добрият по качество и сийдъри, като се предпочита
     * същото като при предишния - същият торент (пакет за сезона), същото качество, същата група, същият сайт.
     * Така епизодите са с еднакъв вид и звук и не се сменят субтитрите.
     */
    fun choose(prev: TorrentResult?, list: List<TorrentResult>): TorrentResult? {
        // seeders < 0 значи „не се знае“ (някои addon-и не го дават)
        val ok = list.filter { (it.seeders >= 2 || it.seeders < 0) && !it.isCam }
        if (ok.isEmpty()) return null
        val prevGroups = prev?.let { Release.parse(it.title).groups }.orEmpty()
        return ok.maxByOrNull { r ->
            var s = TorrentAggregator.score(r)
            if (prev != null) {
                if (r.hash == prev.hash) s += 60
                if (r.quality == prev.quality) s += 35
                if (prevGroups.isNotEmpty() && Release.parse(r.title).groups.any { it in prevGroups }) s += 25
                if (r.pack == prev.pack) s += 10
                if (r.source == prev.source) s += 5
            }
            s
        }
    }
}
