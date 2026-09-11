package com.nexo.tv.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Cache de fanarts (backdrop horizontal) para el Hub.
 * Nunca devuelve la caratula vertical: si no hay escena, null.
 */
object BackdropCache {
    private val movieFanart = ConcurrentHashMap<String, String>()
    private val seriesFanart = ConcurrentHashMap<String, String>()
    private val movieMiss = ConcurrentHashMap.newKeySet<String>()
    private val seriesMiss = ConcurrentHashMap.newKeySet<String>()
    private val movieLocks = ConcurrentHashMap<String, Mutex>()
    private val seriesLocks = ConcurrentHashMap<String, Mutex>()

    private fun keyOf(id: String): String = id.substringBefore(".0").trim()

    fun cachedMovieFanart(vodId: String): String? {
        val key = keyOf(vodId)
        if (key.isBlank()) return null
        return movieFanart[key]
    }

    fun cachedSeriesFanart(seriesId: String): String? {
        val key = keyOf(seriesId)
        if (key.isBlank()) return null
        return seriesFanart[key]
    }

    /** Ya se consultó y no hay fanart (se puede usar carátula sin esperar). */
    fun isMovieFanartMiss(vodId: String): Boolean {
        val key = keyOf(vodId)
        return key.isNotBlank() && key in movieMiss
    }

    fun isSeriesFanartMiss(seriesId: String): Boolean {
        val key = keyOf(seriesId)
        return key.isNotBlank() && key in seriesMiss
    }

    suspend fun movieFanart(vodId: String): String? = withContext(Dispatchers.IO) {
        val key = keyOf(vodId)
        if (key.isBlank()) return@withContext null
        movieFanart[key]?.let { return@withContext it }
        if (key in movieMiss) return@withContext null
        val lock = movieLocks.getOrPut(key) { Mutex() }
        lock.withLock {
            movieFanart[key]?.let { return@withContext it }
            if (key in movieMiss) return@withContext null
            val url = runCatching {
                XtreamClient.movieDetail(key).first?.fanartUrl
            }.getOrNull()?.takeIf { it.isNotBlank() }
            if (url != null) movieFanart[key] = url else movieMiss.add(key)
            url
        }
    }

    suspend fun seriesFanart(seriesId: String): String? = withContext(Dispatchers.IO) {
        val key = keyOf(seriesId)
        if (key.isBlank()) return@withContext null
        seriesFanart[key]?.let { return@withContext it }
        if (key in seriesMiss) return@withContext null
        val lock = seriesLocks.getOrPut(key) { Mutex() }
        lock.withLock {
            seriesFanart[key]?.let { return@withContext it }
            if (key in seriesMiss) return@withContext null
            val url = runCatching {
                XtreamClient.seriesDetail(key).info?.fanartUrl
            }.getOrNull()?.takeIf { it.isNotBlank() }
            if (url != null) seriesFanart[key] = url else seriesMiss.add(key)
            url
        }
    }

    fun clear() {
        movieFanart.clear()
        seriesFanart.clear()
        movieMiss.clear()
        seriesMiss.clear()
        movieLocks.clear()
        seriesLocks.clear()
    }
}
