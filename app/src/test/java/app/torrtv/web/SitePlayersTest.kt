package app.torrtv.web

import app.torrtv.data.Media
import app.torrtv.data.MediaType
import app.torrtv.data.WebRef
import app.torrtv.web.catalog.MovieCatalog
import app.torrtv.web.catalog.MovieSources
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Page shapes captured from the live sites (November 2026), reduced to the parts the parser reads. */
class SitePlayersTest {
    @get:Rule val temp = TemporaryFolder()

    private fun detail(provider: String, page: String, html: String, series: Boolean = false): MovieCatalog.Entry {
        val e = MovieCatalog.Entry().also { it.provider = provider; it.page = page; it.series = series }
        return MovieSources.detail(e, Jsoup.parse(html, page))
    }

    @Test fun filmiZtListsEveryPlayerTabInOrderWithoutTheTrailer() {
        val html = """<div id="mv-info"><h1>A Man Apart / Кървав прах (2003)</h1>
            <div id="content-embed" style="display:none"><div id="player3"><div id="tab2"><div class="movieplay"><iframe src="/filmi/0-0-3-10892-20"></iframe></div></div></div>
            <div id="player8"><div id="tab8"><div class="movieplay"><iframe src="//ok.ru/videoembed/149?nochat=1"></iframe></div></div></div></div>
            <div class="modal-body-trailer"><iframe src="https://www.youtube.com/embed/x"></iframe></div></div>"""
        val e = detail("ZT", "https://filmizt.com/filmi/bgaudio/a_man_apart_krvav_prakh_2003/18-1-0-10892", html)
        assertEquals(listOf("https://filmizt.com/filmi/0-0-3-10892-20", "https://ok.ru/videoembed/149?nochat=1"), e.players)
        assertEquals(MovieSources.DETAIL_SCHEMA, e.detailSchema)
    }

    @Test fun filmiFenPlayersComeFromTheInlineTabMap() {
        val html = """<main><h1>PAW Patrol</h1><div id="video-stage"></div><script>document.addEventListener("DOMContentLoaded",(()=>{const e=document.getElementById("video-stage");
            const i=document.querySelectorAll(".tab-btn[data-player]"),t={trailer:"https://www.youtube.com/embed/x",vdn:"https://vidon.to/e/VUIR",icf:"",okr:"https://ok.ru/videoembed/160",fmo:"",voe:"https://voe.sx/e/wq",ytb:""};}))</script></main>"""
        val e = detail("FEN", "https://filmifen.com/filmi/detski/13887-paw-patrol.html", html)
        assertEquals(listOf("https://vidon.to/e/VUIR", "https://ok.ru/videoembed/160", "https://voe.sx/e/wq"), e.players)
    }

    @Test fun filmi2kPlayerIsReadFromTheDelayedEmbedScriptAndTheTitleLosesItsLabel() {
        val html = """<main><article><header><h1>The Matrix Resurrections / Матрицата: Възкресения (2021) BG Audio</h1>
            <div class="video-player"><div class="responsive-player"><script type="text/rocketlazyloadscript">
            var embedCode = '<div class="responsive-player"><iframe src="/?rtp=%3D0zZ" scrolling="no"></iframe></div>'
            </script><div id="videocontainer" class="responsive-player" onclick="document.getElementById('videocontainer').innerHTML = embedCode;"></div></div></div></header></article></main>"""
        val e = detail("TWO_K", "https://www.filmi2k.com/the-matrix-resurrections-matritsata-vazkresenia-2021/", html)
        assertEquals(listOf("https://www.filmi2k.com/?rtp=%3D0zZ"), e.players)
        assertEquals("Матрицата: Възкресения", e.title)
        assertEquals("The Matrix Resurrections", e.original)
        assertEquals(2021, e.year)
    }

    @Test fun seriesPagesLeavePlayersToTheirEpisodes() {
        val html = """<main><h1>Show</h1><div class="movieplay"><iframe src="https://ok.ru/videoembed/1"></iframe></div></main>"""
        assertTrue(detail("ZT", "https://filmizt.com/filmi/seriali/show/27-1-0-1", html, series = true).players.isEmpty())
    }

    @Test fun anEpisodeListWithoutAudioFlagsNoLongerCrashes() {
        // Filmi9 / Всички филми series: neither the season nor its episodes carry isBgAudio.
        val html = """<main><h1>Лихие - Сезон 1</h1><script>{"movie":{"title":"Лихие - Сезон 1","slug":"lihie-sezon-1","episodes":[{"episodeNumber":1,"videoUrl":"https://voe.sx/e/a"},{"episodeNumber":2,"videoUrl":"https://voe.sx/e/b"}]}}</script></main>"""
        val e = detail("NINE", "https://filmi9.com/serial/lihie-sezon-1", html, series = true)
        assertEquals(2, e.episodes.size)
        assertNull(e.episodes[0].bgAudio)
    }

    @Test fun filmiZtSearchHighlightsAreNotPartOfTheTitleAndSeriesStay() {
        val html = """<div class="ml-item"><a href="/filmi/filmizt/greenland_2_migration/29-1-0-10727" class="ml-mask jt" oldtitle="Greenland 2: Migration / <b>Гренландия</b> 2: Миграция (2026)">
            <img data-original="/_sf/1.webp" alt="Greenland 2: Migration / <b>Гренландия</b> 2: Миграция (2026)"><span class="mli-info"><h2>Greenland 2: Migration / <b>Гренландия</b> 2: Миграция (2026)</h2></span></a></div>
            <div class="ml-item"><a href="/filmi/seriali/show/27-1-0-5" class="ml-mask jt" oldtitle="Show / Шоу (2025)"><img alt="Show"></a></div>"""
        val page = MovieSources.parse(MovieSources.Provider.ZT, "https://filmizt.com/search/?q=%D0%93", html)
        assertEquals(2, page.entries.size)
        assertEquals("Гренландия 2: Миграция", page.entries[0].title)
        assertEquals("Greenland 2: Migration", page.entries[0].original)
        assertTrue(page.entries[1].series)
    }

    @Test fun filmiZtSearchIsSentFromTheSite() {
        assertEquals("https://filmizt.com/", BgWebIndex.siteHeaders("https://filmizt.com/search/?q=x")["Referer"])
        assertTrue(BgWebIndex.siteHeaders("https://filmizt.com/filmi/bgaudio/18").isEmpty())
        assertTrue(BgWebIndex.siteHeaders("https://filmifen.com/index.php?do=search").isEmpty())
    }

    private val ztPage = "https://filmizt.com/filmi/bgaudio/a_man_apart_krvav_prakh_2003/18-1-0-10892"
    private val ztHtml = """<div id="mv-info"><h1>A Man Apart / Кървав прах (2003)</h1><div class="movieplay"><iframe src="/filmi/0-0-3-10892-20"></iframe></div>
        <div class="movieplay"><iframe src="https://ok.ru/videoembed/149"></iframe></div><div class="movieplay"><iframe src="https://voe.sx/e/z"></iframe></div></div>"""
    private val movie = Media(10892, MediaType.MOVIE, "Кървав прах", "A Man Apart", "", null, null, "2003", 6.0, 1, emptyList(), "en",
        web = WebRef("ZT", ztPage))

    @Test fun everyWorkingPlayerOfASiteBecomesItsOwnSource() = runBlocking {
        val index = BgWebIndex(temp.root, get = { ztHtml }, enabled = { setOf("ZT") },
            sitePlayers = { _, urls -> urls.map { if (it.contains("/0-0-3-")) "https://player.example.com/#abc" else it } })
        val sources = index.sources(movie, null, 1, 1)
        assertEquals(listOf("https://player.example.com/#abc", "https://ok.ru/videoembed/149", "https://voe.sx/e/z"), sources.map { it.url })
        assertTrue(sources.all { it.playerOnly && it.originalPlayer && it.headers["Referer"] == ztPage })
        assertEquals(listOf("filmizt.com · player.example.com", "filmizt.com · ok.ru", "filmizt.com · voe.sx"), sources.map { it.label })
    }

    @Test fun aSiteWhosePlayersAreAllGoneSaysSoInsteadOfOpeningABlackPage() = runBlocking {
        val index = BgWebIndex(temp.root, get = { ztHtml }, enabled = { setOf("ZT") }, sitePlayers = { _, _ -> emptyList() })
        val failure = runCatching { index.sources(movie, null, 1, 1) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertTrue(failure!!.message!!.contains("filmizt.com"))
    }
}
