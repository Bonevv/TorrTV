package app.torrtv.core

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

/** Места, където може да се тегли филмът при гледане: вътрешната памет и външен диск (USB / SD карта), ако има. */
object Storage {
    class Place(val id: String, val label: String, val root: File) {
        /** Тук отива всичко от торент двигателя; чисти се при излизане и при чистене на кеша. */
        val torrents: File get() = File(root, "torrents")
    }

    fun places(ctx: Context): List<Place> {
        val out = ArrayList<Place>()
        out.add(Place("internal", "Вътрешна памет", ctx.cacheDir))
        try {
            val dirs = ctx.getExternalFilesDirs(null)
            // Първият е основното споделено хранилище; следващите са външни дискове.
            for (i in 1 until dirs.size) {
                val d = dirs[i] ?: continue
                if (!usable(d)) continue
                out.add(Place(if (i == 1) "usb" else "usb$i", if (i == 1) "USB / SD карта" else "USB / SD карта $i", d))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun usable(d: File): Boolean = try {
        Environment.getExternalStorageState(d) == Environment.MEDIA_MOUNTED && d.canWrite()
    } catch (_: Exception) {
        false
    }

    /** Избраното място; ако дискът е извадил, се връща вътрешната памет. */
    fun chosen(ctx: Context, id: String): Place {
        val all = places(ctx)
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun free(p: Place): Long = try {
        StatFs(p.root.path).availableBytes
    } catch (_: Exception) {
        0L
    }
}
