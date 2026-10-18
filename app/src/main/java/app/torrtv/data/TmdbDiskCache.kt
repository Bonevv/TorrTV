package app.torrtv.data

import java.io.File
import java.security.MessageDigest

/**
 * Дисков кеш за отговорите на TMDB. След рестарт началните редове и вече отваряните детайли излизат веднага,
 * без нова заявка, и при липса на връзка се показва последното запазено вместо грешка.
 *
 * Пази се само тялото на отговора. Ключът (адресът без API ключа) се хешира за име на файл, така че нито
 * ключът, нито заявките се виждат на диска. Свежестта се мери по времето на промяна на файла.
 * Файловете са ограничени по брой и общ размер - най-старите се трият първи.
 */
class TmdbDiskCache(
    private val dir: File,
    private val maxFiles: Int = 160,
    private val maxBytes: Long = 6L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis
) {
    private var puts = 0

    /** Отговор не по-стар от [maxAgeMs], иначе null. */
    @Synchronized
    fun get(key: String, maxAgeMs: Long): String? {
        val f = file(key)
        val age = ageOf(f) ?: return null
        return if (age <= maxAgeMs) read(f) else null
    }

    /** Последният запазен отговор, ако не е по-стар от [MAX_STALE_MS] - за случаите без мрежа. */
    @Synchronized
    fun getStale(key: String): String? {
        val f = file(key)
        val age = ageOf(f) ?: return null
        return if (age <= MAX_STALE_MS) read(f) else null
    }

    @Synchronized
    fun put(key: String, body: String) {
        if (body.isBlank()) return
        try {
            dir.mkdirs()
            val f = file(key)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(body)
            tmp.setLastModified(now())
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) tmp.delete()
            }
            // Чистенето не е на всяко записване - листването на папката не е безплатно.
            if (puts++ % PRUNE_EVERY == 0) prune()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun remove(key: String) {
        try {
            file(key).delete()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clear() {
        try {
            dir.deleteRecursively()
        } catch (_: Exception) {
        }
    }

    /** Трие твърде старите, после най-старите, докато остане в границите по брой и размер. */
    @Synchronized
    fun prune() {
        try {
            val all = dir.listFiles()?.filter { it.isFile } ?: return
            val t = now()
            var count = 0
            var bytes = 0L
            for (f in all.sortedByDescending { it.lastModified() }) {
                val age = t - f.lastModified()
                val keep = !f.name.endsWith(".tmp") && age <= MAX_STALE_MS &&
                    count < maxFiles && bytes + f.length() <= maxBytes
                if (keep) {
                    count++
                    bytes += f.length()
                } else {
                    f.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun file(key: String) = File(dir, hash(key))

    /** Възраст на файла; null, ако го няма или часовникът е бил върнат назад (времето е непригодно). */
    private fun ageOf(f: File): Long? {
        if (!f.isFile) return null
        val age = now() - f.lastModified()
        return if (age < 0) null else age
    }

    private fun read(f: File): String? = try {
        f.readText().ifBlank { null }
    } catch (_: Exception) {
        null
    }

    private fun hash(key: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(HEX[(b.toInt() shr 4) and 15]).append(HEX[b.toInt() and 15])
        return sb.toString()
    }

    companion object {
        const val HOUR = 3_600_000L
        const val MAX_STALE_MS = 14 * 24 * HOUR
        private const val PRUNE_EVERY = 12
        private const val HEX = "0123456789abcdef"

        /**
         * Колко дълго е свеж отговорът за дадена заявка (null = не се пази на диска).
         * Търсенето не се пази - заявките са произволни и не се повтарят.
         * Списъците (тенденции, откриване, популярни...) се менят през деня, детайлите рядко.
         */
        fun ttlFor(path: String): Long? = when {
            path.startsWith("/search") -> null
            path.startsWith("/trending") || path.startsWith("/discover") -> 6 * HOUR
            LISTS.any { path.endsWith("/$it") } -> 6 * HOUR
            path.contains("/season/") -> 12 * HOUR
            else -> 24 * HOUR
        }

        private val LISTS = listOf("popular", "top_rated", "now_playing", "on_the_air")
    }
}
