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
        Graph.app = this
        AppLog.init(this)
        AppLog.d("app", "Старт ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        logLastExit()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            AppLog.e("CRASH", "нишка ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
        // Остатъци от предишна сесия (недоизтрити торенти и субтитри)
        thread(isDaemon = true) {
            try {
                File(cacheDir, "torrents").deleteRecursively()
                File(cacheDir, "subs").deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }

    private fun logLastExit() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val last = am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull() ?: return
            val why = when (last.reason) {
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
                else -> "UNKNOWN(${last.reason})"
            }
            AppLog.d("app", "Предишният процес завърши с: $why ${last.description ?: ""} (status ${last.status})")
        } catch (_: Exception) {
        }
    }

    /** Картинките са най-големият консуматор на памет - държим кеша малък. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.10).build() }
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "img")).maxSizeBytes(120L * 1024 * 1024).build() }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()
}
