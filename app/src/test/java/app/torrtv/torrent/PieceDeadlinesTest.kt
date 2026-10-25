package app.torrtv.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PieceDeadlinesTest {
    private val mb = 1024 * 1024

    @Test fun bitrateComesFromSizeAndRuntime() {
        // 6 000 000 000 байта за 100 минути = 1 000 000 байта в секунда (8 Мбит/с).
        assertEquals(1_000_000L, PieceDeadlines.bytesPerSec(6_000_000_000L, 100))
    }

    @Test fun unknownRuntimeOrSizeUsesDefault() {
        assertEquals(PieceDeadlines.DEFAULT_BPS, PieceDeadlines.bytesPerSec(6_000_000_000L, 0))
        assertEquals(PieceDeadlines.DEFAULT_BPS, PieceDeadlines.bytesPerSec(0L, 100))
    }

    @Test fun pieceIsAskedAtTwicePlaybackSpeed() {
        // Парче от 2 MB при 1 MB/s покрива ~2 сек. картина -> иска се за ~1 сек.
        assertEquals(1048, PieceDeadlines.stepMs(2 * mb, 1_000_000L))
    }

    @Test fun stepStaysWithinLimits() {
        // 4K: 2 MB при 7,5 MB/s покриват ~0,28 сек. -> под долната граница.
        assertEquals(PieceDeadlines.MIN_STEP_MS, PieceDeadlines.stepMs(2 * mb, 7_500_000L))
        // Много ниско качество: 16 MB при 100 KB/s -> над горната граница.
        assertEquals(PieceDeadlines.MAX_STEP_MS, PieceDeadlines.stepMs(16 * mb, 100_000L))
        assertEquals(PieceDeadlines.MIN_STEP_MS, PieceDeadlines.stepMs(0, 1_000_000L))
    }

    @Test fun deadlinesGrowWithDistanceFromTheReadPosition() {
        val step = PieceDeadlines.stepMs(2 * mb, 1_000_000L)
        assertEquals(PieceDeadlines.FIRST_MS, PieceDeadlines.ahead(0, step))
        assertTrue(PieceDeadlines.ahead(1, step) < PieceDeadlines.ahead(2, step))
        // 12 парчета по 2 MB (24 MB напред) не са нереално кратки: над 10 секунди за последното.
        assertTrue(PieceDeadlines.ahead(11, step) > 10_000)
    }

    @Test fun startOfFileKeepsShortDeadlines() {
        assertEquals(100, PieceDeadlines.start(0))
        assertEquals(850, PieceDeadlines.start(5))
    }
}
