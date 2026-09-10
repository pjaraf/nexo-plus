package com.nexo.tv.data

import android.content.Context
import android.util.Log
import com.nexo.tv.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Precarga canales en vivo y calienta el último canal visto durante el splash,
 * para que al abrir TV en vivo no haya espera ni pantalla negra.
 */
object LiveBoot { // encoding: utf-8
    private const val TAG = "LiveBoot"
    const val PREFS = "nexo_live"
    const val KEY_CHANNEL = "channel_id"
    const val KEY_CATEGORY = "category_id"
    const val KEY_CHANNEL_NAME = "channel_name"
    const val KEY_CHANNEL_ICON = "channel_icon"

    @Volatile var channels: List<LiveChannel> = emptyList()
        private set
    @Volatile var categories: List<LiveCategory> = emptyList()
        private set
    @Volatile var ready: Boolean = false
        private set

    suspend fun preload(context: Context) = coroutineScope {
        val catsJob = async {
            runCatching { XtreamClient.liveCategories() }.getOrDefault(emptyList())
        }
        val streamsJob = async {
            runCatching { XtreamClient.liveChannels() }.getOrDefault(emptyList())
                .filter { it.id.isNotBlank() }
        }
        val warmJob = async { warmLastChannel(context) }

        val cats = catsJob.await()
        val streams = streamsJob.await()
        categories = buildList {
            add(LiveCategory(categoryId = "", categoryName = "Todas"))
            addAll(cats.filter { it.categoryId.isNotBlank() })
        }
        channels = streams
        ready = true

        // Precargar icono del ultimo canal (placeholder anti-negro)
        val saved = savedChannel(context)
        val icon = saved?.streamIcon?.trim()?.takeIf { it.isNotEmpty() }
        if (icon != null) {
            runCatching {
                com.nexo.tv.ui.PosterPreloader.warmPriority(context, listOf(icon))
            }
        }

        warmJob.await()
        Log.i(TAG, "ready channels=${streams.size} cats=${categories.size}")
    }

    fun savedChannel(context: Context): LiveChannel? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_CHANNEL, null).orEmpty()
        if (id.isBlank()) return null
        channels.firstOrNull { it.id == id }?.let { return it }
        val name = prefs.getString(KEY_CHANNEL_NAME, null).orEmpty().ifBlank { "Canal" }
        val icon = prefs.getString(KEY_CHANNEL_ICON, null)
        val cat = prefs.getString(KEY_CATEGORY, null)
        return LiveChannel(
            streamId = id,
            name = name,
            streamIcon = icon,
            categoryId = cat
        )
    }

    fun clear() {
        channels = emptyList()
        categories = emptyList()
        ready = false
    }

    private suspend fun warmLastChannel(context: Context) = withContext(Dispatchers.IO) {
        if (!Session.isLoggedIn) return@withContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_CHANNEL, null).orEmpty()
        if (id.isBlank()) return@withContext
        val url = XtreamClient.liveUrl(id)
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "IPTVSmartersPro")
                .header("Accept", "*/*")
                .build()
            Http.mediaClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful && res.code != 206) {
                    Log.w(TAG, "warm $id -> ${res.code}")
                    return@withContext
                }
                val body = res.body ?: return@withContext
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0
                    while (read < 384 * 1024) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        read += n
                    }
                    Log.i(TAG, "warmed channel=$id bytes=$read")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "warm failed $id: ${e.message}")
        }
    }
}
