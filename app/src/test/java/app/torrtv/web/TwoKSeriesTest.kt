package app.torrtv.web

import app.torrtv.data.MediaType
import app.torrtv.web.catalog.MovieSources
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Filmi2K series, from pages saved from the live site (November 2026): seasons are categories, episodes are posts. */
class TwoKSeriesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun page(name: String) = javaClass.getResourceAsStream("/web-catalog/$name")!!.bufferedReader().use { it.readText() }
    private val list = "https://www.filmi2k.com/onlayn-seriali/"
    private val vikings = "https://www.filmi2k.com/category/vikings-season-6-vikingi-sezon-6/"

    @Test fun theSeriesListGivesOneEntryPerSeasonWithTheSitesBgAudioTag() {
        val parsed = MovieSources.twoKSeries(Jsoup.parse(page("2k-series.html"), list))
        assertEquals(3, parsed.entries.size)
        val v = parsed.entries.single { it.page == vikings }
        assertEquals("Викинги", v.title)
        assertEquals("Vikings", v.original)
        assertEquals(6, v.season)
        assertTrue(v.series && MovieSources.hasBgAudio(v))
        val obi = parsed.entries.single { it.page.contains("obi-wan-kenobi-seson-1") }
        assertEquals(1, obi.season)
        assertEquals(2022, obi.year)
        assertFalse(MovieSources.hasBgAudio(obi))
        assertEquals("https://www.filmi2k.com/onlayn-seriali/page/2/", parsed.next)
    }

    @Test fun aSeasonPageListsItsEpisodesWithTheirOwnAudio() {
        val e = MovieSources.twoKSeries(Jsoup.parse(page("2k-series.html"), list)).entries.single { it.page == vikings }
        val d = MovieSources.detail(e, Jsoup.parse(page("2k-season.html"), vikings))
        assertEquals("Викинги", d.title)
        assertEquals("Vikings", d.original)
        assertEquals(listOf(18, 19, 20), d.episodes.map { it.number })
        assertTrue(d.episodes.all { it.season == 6 && it.bgAudio == true })
        assertEquals("https://www.filmi2k.com/vikings-s06e20-vikingi-s06e20-bg-audio/", d.episodes.last().page)
    }

    @Test fun aSeasonOpensAndPlaysFromTheEpisodePage() = runBlocking {
        val index = BgWebIndex(temp.root, get = { url -> when {
            url == list -> page("2k-series.html")
            url == vikings -> page("2k-season.html")
            else -> "<main></main>"
        } }, enabled = { setOf("TWO_K") })
        index.refresh(type = MediaType.TV)
        val cards = index.catalogueCandidates(MediaType.TV, true).mapNotNull { index.catalogueCard(it, true) { null } }
        assertEquals(3, cards.size) // one confirmed BG season, two listed as unconfirmed site cards
        val v = cards.single { index.has(it) }
        assertEquals(vikings, v.web!!.page)
        val play = index.series(v, null, 6, 20).sources.single()
        assertTrue(play.bgAudio)
        assertEquals("https://www.filmi2k.com/vikings-s06e20-vikingi-s06e20-bg-audio/", play.discoveryPage)
    }
}
