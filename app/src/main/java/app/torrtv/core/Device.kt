package app.torrtv.core

import android.app.ActivityManager
import android.content.Context

/** Колко е силно устройството - за да се включи лекият режим сам на слабите телевизори. */
object Device {
    /** Под толкова обща памет (MB) устройството се смята за слабо. Телевизор с 2 GB съобщава около 1,8 GB. */
    const val WEAK_BELOW_MB = 1600L

    /** Чиста логика: слабо е, ако системата го води за "с малко памет" или общата памет е малка. */
    fun weakBy(totalMb: Long, lowRamFlag: Boolean): Boolean = lowRamFlag || (totalMb in 1 until WEAK_BELOW_MB)

    fun totalMb(ctx: Context): Long = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem / 1048576
    } catch (_: Exception) {
        0L
    }

    fun isWeak(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        weakBy(totalMb(ctx), am.isLowRamDevice)
    } catch (_: Exception) {
        false
    }
}
