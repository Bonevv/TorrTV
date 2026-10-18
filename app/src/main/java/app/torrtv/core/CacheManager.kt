package app.torrtv.core

import android.content.Context
import android.os.Debug
import app.torrtv.state.Graph
import java.io.File

/** Кеш и памет: размери, ръчно и автоматично чистене. */
object CacheManager {

    class Sizes(val torrents: Long, val images: Long, val subs: Long, val other: Long) {
        val total: Long get() = torrents + images + subs + other
    }

    /** pssMb = интерфейсът; torrentMb = торент процесът (0, когато не върви стрийм); totalMb = всичко. */
    class Mem(val pssMb: Int, val javaMb: Int, val nativeMb: Int, val torrentMb: Int) {
        val totalMb: Int get() = pssMb + torrentMb
    }

    /** Възможни интервали за автоматично чистене (часове; 0 = изключено). */
    val intervals: List<Pair<Int, String>> = listOf(
        0 to "Изключено", 3 to "На 3 часа", 12 to "На 12 часа", 24 to "Всеки ден", 168 to "Веднъж седмично"
    )

    /** Възможни лимити на размера (MB; 0 = без лимит). */
    val limits: List<Pair<Int, String>> = listOf(
        0 to "Без лимит", 200 to "200 MB", 500 to "500 MB", 1000 to "1 GB", 2000 to "2 GB"
    )

    private fun size(f: File): Long {
        if (!f.exists()) return 0
        if (f.isFile) return f.length()
        var s = 0L
        f.listFiles()?.forEach { s += size(it) }
        return s
    }

    fun sizes(ctx: Context): Sizes {
        val c = ctx.cacheDir
        val inner = size(File(c, "torrents"))
        val outer = externalTorrents(ctx)
        val i = size(File(c, "img"))
        val s = size(File(c, "subs"))
        val total = size(c)
        return Sizes(inner + outer, i, s, (total - inner - i - s).coerceAtLeast(0))
    }

    /** Свалени парчета на външните дискове (не са в папката на кеша). */
    private fun externalTorrents(ctx: Context): Long =
        Storage.places(ctx).filter { it.id != "internal" }.sumOf { size(it.torrents) }

    /** Трие свалените парчета на всички места. */
    fun deleteTorrents(ctx: Context) {
        for (p in Storage.places(ctx)) {
            try {
                p.torrents.deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }

    fun memory(ctx: Context): Mem {
        val rt = Runtime.getRuntime()
        var tor = 0
        val pid = Graph.stream.torrentPid()
        if (pid > 0) {
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                tor = (am.getProcessMemoryInfo(intArrayOf(pid)).firstOrNull()?.totalPss ?: 0) / 1024
            } catch (_: Exception) {
            }
        }
        return Mem(
            pssMb = (Debug.getPss() / 1024).toInt(),
            javaMb = ((rt.totalMemory() - rt.freeMemory()) / 1048576).toInt(),
            nativeMb = (Debug.getNativeHeapAllocatedSize() / 1048576).toInt(),
            torrentMb = tor
        )
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private fun clearImages(ctx: Context) {
        try {
            val il = coil.Coil.imageLoader(ctx)
            il.memoryCache?.clear()
            il.diskCache?.clear()
        } catch (_: Exception) {
        }
    }

    /** Чисти кеша. Файловете на активния стрийм не се пипат. */
    fun clear(ctx: Context, streamActive: Boolean) {
        try {
            val c = ctx.cacheDir
            if (!streamActive) deleteTorrents(ctx)
            File(c, "subs").deleteRecursively()
            File(c, "updates").deleteRecursively()
            if (!streamActive) AppLog.trim(200)
            clearImages(ctx)
            Graph.prefs.lastClean = System.currentTimeMillis()
            AppLog.d("cache", "кешът е изчистен")
        } catch (e: Exception) {
            AppLog.d("cache", "грешка при чистене: ${e.message}")
        }
    }

    /** Връща true, ако е почистило. Вика се при старт и периодично. */
    fun maybeAutoClean(ctx: Context, streamActive: Boolean): Boolean {
        if (streamActive) return false
        // По размер: щом кешът надхвърли лимита, чисти се веднага (не чака часовете).
        val limit = Graph.prefs.cacheLimitMb
        if (limit > 0) {
            val total = size(ctx.cacheDir) + externalTorrents(ctx)
            if (total > limit * 1048576L) {
                AppLog.d("cache", "кешът е ${fmt(total)} (лимит $limit MB) - чистя")
                clear(ctx, false)
                return true
            }
        }
        val hours = Graph.prefs.cacheHours
        if (hours <= 0) return false
        val last = Graph.prefs.lastClean
        val now = System.currentTimeMillis()
        if (last == 0L) {
            Graph.prefs.lastClean = now
            return false
        }
        if (now - last < hours * 3_600_000L) return false
        clear(ctx, streamActive)
        return true
    }

    fun fmt(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "${bytes shr 20} MB"
        bytes >= 1L shl 10 -> "${bytes shr 10} KB"
        else -> "$bytes B"
    }
}
