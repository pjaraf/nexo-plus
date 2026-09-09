package com.nexo.tv.ui

import android.content.Context
import android.util.Log
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Precarga carátulas en memoria/disco (Coil) para que al abrir Películas/Series
 * aparezcan al instante sin esperar red.
 */
object PosterPreloader {
    private const val TAG = "PosterPreloader"
    /** Tamaño de decodificación alineado con las filas del hub (evita doble descarga). */
    const val POSTER_W = 420
    const val POSTER_H = 630

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun warmPriority(context: Context, urls: Collection<String>) {
        val clean = normalize(urls)
        if (clean.isEmpty()) return
        Log.i(TAG, "warm priority ${clean.size}")
        warm(context.applicationContext, clean, parallelism = 12)
    }

    fun warmBackground(context: Context, urls: Collection<String>) {
        val clean = normalize(urls)
        if (clean.isEmpty()) return
        val app = context.applicationContext
        bgScope.launch {
            Log.i(TAG, "warm background ${clean.size}")
            warm(app, clean, parallelism = 6)
            Log.i(TAG, "warm background done")
        }
    }

    private fun normalize(urls: Collection<String>): List<String> {
        return urls.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && (it.startsWith("http://") || it.startsWith("https://")) }
            .distinct()
            .toList()
    }

    private suspend fun warm(context: Context, urls: List<String>, parallelism: Int) = coroutineScope {
        val loader = context.imageLoader
        val sem = Semaphore(parallelism.coerceAtLeast(1))
        urls.map { url ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        .size(POSTER_W, POSTER_H)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .networkCachePolicy(CachePolicy.ENABLED)
                        .allowHardware(true)
                        .build()
                    runCatching { loader.execute(request) }
                }
            }
        }.awaitAll()
    }
}

