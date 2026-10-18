package app.torrtv.core

import kotlin.math.max
import kotlin.math.min

/** Чиста логика: събира ли се файлът на диска и ще върви ли гладко при измерените скорости. */
object StreamFit {
    enum class Level { NONE, GOOD, EDGE, BAD }

    /** Място, което се оставя на системата и на кеша на картинките. */
    const val RESERVE = 1L shl 30

    /** Най-големият файл, който се събира удобно. */
    fun usableBytes(freeBytes: Long): Long = max(0L, freeBytes - RESERVE)

    fun storage(sizeBytes: Long, freeBytes: Long): Level {
        if (sizeBytes <= 0) return Level.NONE
        val usable = usableBytes(freeBytes)
        return when {
            sizeBytes <= usable * 8 / 10 -> Level.GOOD
            sizeBytes <= usable -> Level.EDGE
            else -> Level.BAD
        }
    }

    /** Мбит/с, които са нужни на видеото (размер върху дължина). 0, ако не се знае. */
    fun bitrateMbps(sizeBytes: Long, runtimeMin: Int): Double =
        if (sizeBytes <= 0 || runtimeMin <= 0) 0.0 else sizeBytes * 8.0 / 1_000_000.0 / (runtimeMin * 60.0)

    /** netMbps: измерена мрежа; diskMBps: измерен запис. 0 = не е измерено. */
    fun smooth(sizeBytes: Long, runtimeMin: Int, netMbps: Double, diskMBps: Double, seeders: Int): Level {
        val need = bitrateMbps(sizeBytes, runtimeMin)
        if (need <= 0) return Level.NONE
        var have = Double.MAX_VALUE
        if (netMbps > 0) have = min(have, netMbps)
        if (diskMBps > 0) have = min(have, diskMBps * 8)
        if (have == Double.MAX_VALUE) return Level.NONE
        val ratio = have / need
        var level = when {
            ratio >= 2.0 -> Level.GOOD
            ratio >= 1.15 -> Level.EDGE
            else -> Level.BAD
        }
        // Малко сийдъри: скоростта на интернета не е гаранция.
        if (seeders in 0..4 && level == Level.GOOD) level = Level.EDGE
        return level
    }

    /** Препоръчан размер на буфера напред (MB) според мрежата; 0, ако не е измерена. */
    fun recommendedWindowMb(netMbps: Double): Int = when {
        netMbps <= 0 -> 0
        netMbps >= 60 -> 200
        netMbps >= 20 -> 500
        else -> 1000
    }
}
