package com.nexo.tv

import android.app.Application
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

    /** Coil con caché grande para carátulas precargadas al entrar. */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .crossfade(false)
            .respectCacheHeaders(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.35)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("nexo_poster_cache"))
                    .maxSizeBytes(512L * 1024L * 1024L)
                    .build()
            }
            .okHttpClient { Http.client }
            .build()
    }
}
