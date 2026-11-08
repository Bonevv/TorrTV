package app.torrtv.web

import org.junit.Assert.*
import org.junit.Test

class BgPlayerPresentationTest {
    @Test fun aCatalogueIsNeverReturnedAsThePlaybackUrl() {
        val source = BgPlayerPresentation.source("filmi9.com", "https://filmi9.com/film/test", "https://filmi9.com/film/test")
        assertEquals("", source.url)
        assertEquals("https://filmi9.com/film/test", source.discoveryPage)
        assertTrue(source.playerOnly)
        assertThrows(IllegalArgumentException::class.java) { BgPlayerPresentation.shell(source.discoveryPage) }
    }
    @Test fun anActualPlayerPreservesRefererAndUsesOnlyTheMinimalFrame() {
        val source = BgPlayerPresentation.source("zenoplay.to", "https://zenoplay.to/movie/test/#player", "https://rollo.uno/embed/abc?x=1&y=2")
        assertEquals("", source.discoveryPage)
        assertEquals("https://zenoplay.to/movie/test/", source.headers["Referer"])
        assertTrue(source.playerOnly)
        val html = BgPlayerPresentation.shell(source.url)
        assertTrue(html.contains("data-torrtv-player-shell"))
        assertTrue(html.contains("x=1&amp;y=2"))
        assertFalse(html.contains("<nav"))
        assertFalse(html.contains("<script"))
    }
    @Test fun aBgPlayerCannotFallBackToAnUnrelatedSite() {
        val bg = BgPlayerPresentation.source("filmifen.com", "https://filmifen.com/filmi/test.html", "")
        val otherBg = BgPlayerPresentation.source("filmi9.com", "https://filmi9.com/film/test", "https://player.example.com/e/abc")
        val regular = WebSources.Source("site.example.com", "https://site.example.com/movie/test")
        assertEquals(listOf(bg, otherBg), BgPlayerPresentation.alternatives(listOf(bg, otherBg, regular), bg))
        assertNotEquals(bg.key, otherBg.key)
    }
    @Test fun directVideoGoesStraightToTheNativePlayer() {
        val source = BgPlayerPresentation.source("filmi9.com", "https://filmi9.com/film/test", "https://cdn.example.com/master.m3u8?token=x")
        assertTrue(source.direct)
        assertTrue(source.playerOnly)
        assertEquals("", source.discoveryPage)
    }
}
