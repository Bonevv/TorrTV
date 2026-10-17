package app.torrtv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import app.torrtv.core.AppLog
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import app.torrtv.state.Graph
import java.io.File
import kotlin.concurrent.thread

class TorrTvApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Торент услугата върви в отделни процеси (:t0/:t1/:t2) - там не стартираме нищо от интерфейса.
        val proc = processName()
        val torrentProc = proc.startsWith("$packageName:t")
        AppLog.init(this, torrentProc)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            AppLog.e("CRASH", "нишка ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
        if (torrentProc) return

        Graph.app = this
        AppLog.d("app", "Старт ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        // Остатъци от предишна сесия (недоизтрити торенти и субтитри) - на заден план, да не бавим старта.
        thread(isDaemon = true) {
            logLastExit()
            try {
                File(cacheDir, "torrents").deleteRecursively()
                File(cacheDir, "subs").deleteRecursively()
                app.torrtv.core.CacheManager.maybeAutoClean(this, false)
            } catch (_: Exception) {
            }
        }
    }

    private fun processName(): String =
        try {
            File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
        } catch (_: Exception) {
            packageName
        }

    private fun logLastExit() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            for (x in am.getHistoricalProcessExitReasons(packageName, 0, 4)) {
                val why = when (x.reason) {
                    1 -> "EXIT_SELF"
                    2 -> "SIGNALED"
                    3 -> "LOW_MEMORY"
                    4 -> "CRASH"
                    5 -> "CRASH_NATIVE"
                    6 -> "ANR"
                    7 -> "INITIALIZATION_FAILURE"
                    8 -> "PERMISSION_CHANGE"
                    9 -> "EXCESSIVE_RESOURCE_USAGE"
                    10 -> "USER_REQUESTED"
                    11 -> "USER_STOPPED"
                    12 -> "DEPENDENCY_DIED"
                    13 -> "OTHER"
                    else -> "UNKNOWN(${x.reason})"
                }
                val name = x.processName.substringAfter(':', "основен")
                AppLog.d("app", "Предишен процес [$name] ${fmt.format(java.util.Date(x.timestamp))}: $why (status ${x.status}) ${x.description ?: ""}")
                if (x.reason == 5) logTombstone(x)
            }
        } catch (_: Exception) {
        }
    }

    /** При native срив системата пази следа (tombstone) - вадим от нея имената на библиотеките/функциите. */
    private fun logTombstone(x: android.app.ApplicationExitInfo) {
        try {
            val bytes = x.traceInputStream?.use { it.readBytes() } ?: return
            val text = String(bytes, 0, minOf(bytes.size, 300_000), Charsets.ISO_8859_1)
            Regex("[\\x20-\\x7e]{8,}").findAll(text).map { it.value }
                .filter { it.contains(".so") || it.contains("SIG") || it.contains("bort") || it.contains("lib") }
                .distinct().take(30).forEach { AppLog.d("срив", it.take(160)) }
        } catch (_: Exception) {
        }
    }

    /** Картинките са най-големият консуматор на памет - държим кеша малък. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.08).build() }
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "img")).maxSizeBytes(80L * 1024 * 1024).build() }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()
}
