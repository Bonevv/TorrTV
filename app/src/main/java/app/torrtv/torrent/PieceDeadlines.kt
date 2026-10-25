package app.torrtv.torrent

/**
 * Чиста логика: в какъв срок (мс) да дойде всяко парче пред мястото, което плеърът чете.
 * Сроковете следват колко бързо върви видеото: парче, което покрива 2 секунди картина, се иска
 * за около 1 секунда (двоен запас). Нереално кратки срокове карат двигателя да смята всичко за
 * закъсняло и да тегли едни и същи части от няколко места.
 */
object PieceDeadlines {
    /** Колко байта в секунда приемаме, ако дължината на видеото не се знае (около 8 Мбит/с). */
    const val DEFAULT_BPS = 1_000_000L

    /** Най-краткото и най-дългото време за едно парче. */
    const val MIN_STEP_MS = 250
    const val MAX_STEP_MS = 4_000

    /** Срок за парчето, което плеърът чете в момента. */
    const val FIRST_MS = 300

    /** Колко байта в секунда иска видеото (размер върху дължина); DEFAULT_BPS, ако не се знае. */
    fun bytesPerSec(sizeBytes: Long, runtimeMin: Int): Long =
        if (sizeBytes <= 0 || runtimeMin <= 0) DEFAULT_BPS
        else (sizeBytes / (runtimeMin * 60L)).coerceAtLeast(1L)

    /** Половината от времето, което едно парче покрива при гледане (между MIN_STEP_MS и MAX_STEP_MS). */
    fun stepMs(pieceLen: Int, bytesPerSec: Long): Int {
        if (pieceLen <= 0 || bytesPerSec <= 0) return MIN_STEP_MS
        val full = pieceLen.toLong() * 1000L / bytesPerSec
        return (full / 2).coerceIn(MIN_STEP_MS.toLong(), MAX_STEP_MS.toLong()).toInt()
    }

    /** Срок за парчето на rank места пред четеното (0 = четеното). */
    fun ahead(rank: Int, stepMs: Int): Int = FIRST_MS + rank * stepMs

    /**
     * Срок за парчетата в началото и края на файла. Плеърът тръгва чак когато всички те са изтеглени,
     * затова остават кратки, както досега.
     */
    fun start(rank: Int): Int = 100 + rank * 150
}
