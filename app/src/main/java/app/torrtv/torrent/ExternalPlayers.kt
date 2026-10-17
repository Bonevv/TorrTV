package app.torrtv.torrent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable

object ExternalPlayers {
    class P(val id: String, val label: String, val packages: List<String>)

    val all = listOf(
        P("vlc", "VLC", listOf("org.videolan.vlc")),
        P("mx", "MX Player", listOf("com.mxtech.videoplayer.pro", "com.mxtech.videoplayer.ad")),
        P("just", "Just Player", listOf("com.brouken.player"))
    )

    fun installedPackage(ctx: Context, id: String): String? {
        val p = all.firstOrNull { it.id == id } ?: return null
        for (pkg in p.packages) {
            try {
                ctx.packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (e: Exception) {
            }
        }
        return null
    }

    fun installed(ctx: Context): List<P> = all.filter { installedPackage(ctx, it.id) != null }

    fun label(id: String): String = when (id) {
        "builtin" -> "Вграден плеър"
        "ask" -> "Питай всеки път"
        else -> all.firstOrNull { it.id == id }?.label ?: id
    }

    /** Връща true, ако плеърът е пуснат. */
    fun launch(ctx: Context, id: String, pb: StreamController.Playback): Boolean {
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(Uri.parse(pb.url), "video/*")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (id == "ask") {
            return try {
                i.putExtra("title", pb.title)
                val chooser = Intent.createChooser(i, "Избери плеър").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(chooser)
                true
            } catch (e: Exception) {
                false
            }
        }
        val pkg = installedPackage(ctx, id) ?: return false
        i.setPackage(pkg)
        i.putExtra("title", pb.title)
        if (pb.subs.isNotEmpty()) {
            if (id == "vlc") {
                i.putExtra("subtitles_location", pb.subs[0].url)
            } else {
                // MX Player / Just Player: списък със субтитри, първият е включен
                val uris: Array<Parcelable> = pb.subs.map { Uri.parse(it.url) as Parcelable }.toTypedArray()
                i.putExtra("subs", uris)
                i.putExtra("subs.name", pb.subs.map { it.label }.toTypedArray())
                i.putExtra("subs.enable", arrayOf<Parcelable>(Uri.parse(pb.subs[0].url)))
            }
        }
        return try {
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }
}
