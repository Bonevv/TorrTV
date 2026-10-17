package app.torrtv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.torrtv.BuildConfig
import app.torrtv.core.Http
import app.torrtv.core.Http.await
import app.torrtv.core.long
import app.torrtv.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Обновяване директно от GitHub Releases: проверка, сваляне на APK и стартиране на инсталацията. */
class Updater(private val ctx: Context) {

    class Release(val tag: String, val name: String, val notes: String, val apkUrl: String, val size: Long)

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun latest(): Release? {
        val body = Http.get(
            "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest",
            mapOf("Accept" to "application/vnd.github+json")
        )
        val j = JSONObject(body)
        val tag = j.str("tag_name")
        val assets = j.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.str("name").endsWith(".apk", ignoreCase = true)) {
                return Release(tag, j.str("name").ifBlank { tag }, j.str("body"), a.str("browser_download_url"), a.long("size"))
            }
        }
        return null
    }

    fun isNewer(tag: String): Boolean = compare(tag, BuildConfig.VERSION_NAME) > 0

    private fun nums(s: String): List<Int> = Regex("\\d+").findAll(s).map { it.value.toInt() }.toList()

    private fun compare(a: String, b: String): Int {
        val x = nums(a)
        val y = nums(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p.compareTo(q)
        }
        return 0
    }

    suspend fun download(r: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").also { it.mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "TorrTV-update.apk")
        val req = Request.Builder().url(r.apkUrl).header("User-Agent", "TorrTV-updater").build()
        Http.client.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Празен отговор")
            val total = if (body.contentLength() > 0) body.contentLength() else r.size
            body.byteStream().use { input ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastReport > 250) {
                            lastReport = now
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
        onProgress(1f)
        out
    }

    /** Връща съобщение за потребителя, ако инсталацията не може да започне веднага; null = всичко е наред. */
    fun install(apk: File): String? {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                ctx.startActivity(i)
                "Разреши на TorrTV да инсталира приложения, върни се и натисни „Инсталирай“ пак."
            } catch (e: Exception) {
                "Разреши ръчно „Инсталиране от неизвестни източници“ за TorrTV в настройките на телевизора, после опитай пак."
            }
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            null
        } catch (e: Exception) {
            "На това устройство няма инсталатор на пакети. Свали APK-то ръчно от GitHub."
        }
    }
}
