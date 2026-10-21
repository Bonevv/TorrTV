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
        app.torrtv.core.Magnets.setCustom(Graph.prefs.trackersList)
        app.torrtv.core.Magnets.setDead(Graph.prefs.deadTrackers)
        AppLog.d("app", "Старт ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        // Остатъци от предишна сесия (недоизтрити торенти и субтитри) - на заден план, да не бавим старта.
        thread(isDaemon = true) {
            app.torrtv.core.CrashInfo.logRecent(this, 6)
            try {
                app.torrtv.core.CacheManager.deleteTorrents(this)
                // Субтитрите се пазят между сесиите: повторното сваляне изразходва дневната квота.
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

    /** Картинките са най-големият консуматор на памет - държим кеша малък. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.08).build() }
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "img")).maxSizeBytes(80L * 1024 * 1024).build() }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()
}
