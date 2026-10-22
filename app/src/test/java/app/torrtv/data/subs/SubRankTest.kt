package app.torrtv.data.subs

import org.junit.Assert.*
import org.junit.Test

class SubRankTest {
    private val torrent = "Film.2024.1080p.WEB-DL.x264-FLUX"
    private fun sub(name: String, file: String = "") =
        SubtitleResult("test", "Test", "bg", name, "1", 0, false, false, file)

    @Test fun matchingGroupAndSourceAreStrongEvidence() {
        val s = sub("Film.2024.720p.WEB-DL.x265-FLUX")
        assertEquals(Fit.EXACT, SubRank.fit(s, torrent))
        assertTrue(SubRank.reason(s, torrent).contains("Синхронът не е гарантиран"))
    }

    @Test fun resolutionAndCodecDoNotProveAnExactMatch() {
        assertEquals(Fit.SIMILAR, SubRank.fit(sub("Film.1080p.WEB-DL.x264"), torrent))
        assertEquals(Fit.UNKNOWN, SubRank.fit(sub("Film.1080p.x264"), torrent))
        assertEquals(Fit.UNKNOWN, SubRank.fit(sub("Film"), torrent))
    }

    @Test fun streamingServicesAreNotReleaseGroups() {
        assertEquals(Fit.SIMILAR, SubRank.fit(sub("Film.NF.WEB-DL.1080p"), "Film.NF.WEB-DL.1080p"))
        assertFalse(Release.parse("Film.WEB-DL-NF").groups.contains("nf"))
    }

    @Test fun movieTitleIsNotMistakenForAReleaseGroup() {
        val name = "Joy.2015.1080p.WEB-DL.x264"
        assertTrue(Release.parse(name).groups.isEmpty())
        assertEquals(Fit.SIMILAR, SubRank.fit(sub(name), name))
    }

    @Test fun differentSourceOrCutOutweighsMatchingGroup() {
        assertEquals(Fit.OTHER, SubRank.fit(sub("Film.BluRay-FLUX"), torrent))
        assertEquals(Fit.OTHER, SubRank.fit(sub("Film.Extended.WEB-DL-FLUX"), torrent))
        assertEquals(Fit.OTHER, SubRank.fit(sub("Film.Directors.Cut.WEB-DL-FLUX"), torrent))
    }

    @Test fun fullFilenameIsUsedWithoutDownloading() {
        val s = sub("Film", "$torrent.srt")
        assertEquals(Fit.EXACT, SubRank.fit(s, torrent))
        assertEquals(listOf("Film", "$torrent.srt"), s.names)
        assertEquals("$torrent.srt", s.displayName)
        assertEquals(Fit.EXACT, SubRank.bestFit(listOf(s), torrent))
    }

    @Test fun missingVideoMetadataStaysUnknown() {
        assertEquals(Fit.UNKNOWN, SubRank.fit(sub(torrent), "Film"))
        assertEquals(Fit.UNKNOWN, SubRank.fit(sub(torrent), null))
    }

    @Test fun separateReleasesDoNotCreateAFictionalCombinedMatch() {
        assertEquals(Fit.SIMILAR, SubRank.fit(sub("Film.WEB-DL-OTHER\nFilm.BluRay-FLUX"), torrent))
    }

    @Test fun strongerFilenameEvidenceRanksAheadOfPopularGenericResult() {
        val generic = sub("Film.1080p.WEB-DL.x264").copy(ref = "2", downloads = 99999, trusted = true)
        val specific = sub("Film", "$torrent.srt")
        assertEquals(specific, SubRank.rank(listOf(generic, specific), listOf("bg"), torrent).first())
    }

    @Test fun subtitleWithoutEditionIsNotCalledADifferentCut() {
        val extended = "Film.2024.Extended.1080p.WEB-DL.x264-FLUX"
        assertEquals(Fit.SIMILAR, SubRank.fit(sub("Film.2024.1080p.WEB-DL.x264-FLUX"), extended))
        assertEquals(Fit.OTHER, SubRank.fit(sub("Film.2024.Extended.1080p.WEB-DL.x264-FLUX"), torrent))
        assertTrue(SubRank.reason(sub("Film.2024.1080p.WEB-DL.x264-FLUX"), extended).contains("специална версия"))
    }

    @Test fun displayNameIsOneLine() {
        val s = sub("Film.WEB-DL-A\nFilm.1080p.WEB-DL.x264-FLUX\n" + "Film.".repeat(40))
        assertFalse(s.displayName.contains('\n'))
        assertEquals("Film.1080p.WEB-DL.x264-FLUX", s.displayName)
    }
}
