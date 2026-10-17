package app.torrtv

import android.app.Application
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
        // Остатъци от предишна сесия (недоизтрити торенти и субтитри)
        thread(isDaemon = true) {
            try {
                File(cacheDir, "torrents").deleteRecursively()
                File(cacheDir, "subs").deleteRecursively()
            } catch (_: Exception) {
            }
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
