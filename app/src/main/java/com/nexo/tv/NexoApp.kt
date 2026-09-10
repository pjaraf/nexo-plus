package com.nexo.tv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.nexo.tv.data.Http

class NexoApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Session.init(this)
    }

    /** Coil con caché equilibrada para TV Box de poca RAM y móviles. */
    override fun newImageLoader(): ImageLoader {
        val lowRam = runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.isLowRamDevice || am.memoryClass <= 192
        }.getOrDefault(false)
        val memPercent = if (lowRam) 0.18 else 0.22
        val diskBytes = if (lowRam) 256L * 1024L * 1024L else 512L * 1024L * 1024L
        return ImageLoader.Builder(this)
            .crossfade(false)
            .respectCacheHeaders(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(memPercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("nexo_poster_cache"))
                    .maxSizeBytes(diskBytes)
                    .build()
            }
            .okHttpClient { Http.apiClient }
            .build()
    }
}
