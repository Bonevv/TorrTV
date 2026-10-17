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
        P("just", "Just Player", listOf("com.brouken.player")),
        P("kodi", "Kodi", listOf("org.xbmc.kodi")),
        P("vimu", "Vimu", listOf("net.gtvbox.videoplayer", "net.gtvbox.vimuhd")),
        P("nova", "Nova Video Player", listOf("org.courville.nova")),
        P("mpv", "mpv", listOf("is.xyz.mpv")),
        P("bsp", "BSPlayer", listOf("com.bsplayer.bspandroid.free")),
        P("archos", "Archos Video", listOf("com.archos.mediacenter.videoplayer"))
    )

    /** Имена на намерените (непознати предварително) плеъри - за етикетите на бутоните. */
    private val names = HashMap<String, String>()

    /** Приложения, които не са плеъри, но могат да се обадят на "отвори видео" - не ги показваме. */
    private val skip = setOf(
        "com.android.chrome", "com.android.browser", "org.mozilla.firefox", "org.mozilla.tv.firefox",
        "com.google.android.googlequicksearchbox", "com.google.android.apps.photos", "com.android.documentsui",
        "com.google.android.documentsui", "com.google.android.tvlauncher", "com.google.android.youtube.tv",
        "com.google.android.youtube", "com.amazon.cloud9", "com.amazon.cloud9.systembar"
    )

    /**
     * Всички други приложения на устройството, които отварят видео от http адрес (Wuffy, KMPlayer, nPlayer,
     * плеърите на производителя и т.н.). Намират се чрез системата, не от фиксиран списък.
     */
    private fun discovered(ctx: Context): List<P> {
        return try {
            val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("http://127.0.0.1:8080/video.mkv"), "video/*")
            val known = all.flatMap { it.packages }.toSet()
            val pm = ctx.packageManager
            val out = ArrayList<P>()
            val seen = HashSet<String>()
            for (ri in pm.queryIntentActivities(probe, 0)) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == ctx.packageName || pkg in known || pkg in skip || !seen.add(pkg)) continue
                val label = try {
                    ri.loadLabel(pm).toString().ifBlank { pkg }
                } catch (_: Exception) {
                    pkg
                }
                val id = "pkg:$pkg"
                names[id] = label
                out.add(P(id, label, listOf(pkg)))
            }
            out.sortedBy { it.label.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun packagesOf(ctx: Context, id: String): List<String> {
        if (id.startsWith("pkg:")) return listOf(id.removePrefix("pkg:"))
        return all.firstOrNull { it.id == id }?.packages ?: emptyList()
    }

    fun installedPackage(ctx: Context, id: String): String? {
        for (pkg in packagesOf(ctx, id)) {
            try {
                ctx.packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (e: Exception) {
            }
        }
        return null
    }

    fun installed(ctx: Context): List<P> = all.filter { installedPackage(ctx, it.id) != null } + discovered(ctx)

    fun label(id: String): String = when (id) {
        "builtin" -> "Вграден плеър"
        "ask" -> "Питай всеки път"
        else -> all.firstOrNull { it.id == id }?.label ?: names[id] ?: id.removePrefix("pkg:")
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
