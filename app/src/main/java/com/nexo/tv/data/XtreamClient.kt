package com.nexo.tv.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nexo.tv.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

object XtreamClient {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36 VLC/3.0.18"

    private val hosts = listOf(
        Session.SERVER
    )

    private val gson = Gson()

    private fun apiUrl(base: String, action: String?, extra: Map<String, String>, u: String, p: String): String {
        return buildString {
            append(base.trimEnd('/'))
            append("/player_api.php?username=")
            append(u)
            append("&password=")
            append(p)
            if (!action.isNullOrBlank()) {
                append("&action=")
                append(action)
            }
            extra.forEach { (k, v) ->
                append("&")
                append(k)
                append("=")
                append(v)
            }
        }
    }

    private fun tryGet(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "*/*").build()
        return try {
            Http.client.newCall(req).execute().use { res ->
                if (res.isSuccessful) res.body?.string() else null
            }
        } catch (e: Throwable) {
            android.util.Log.w("Xtream", "GET $url -> ${e.message}")
            null
        }
    }

    private suspend fun fetch(action: String? = null, extra: Map<String, String> = emptyMap()): String? =
        withContext(Dispatchers.IO) {
            val u = Session.username
            val p = Session.password
            if (u.isBlank() || p.isBlank()) return@withContext null
            val preferred = Session.server
            val order = (listOf(preferred) + hosts).distinct()
            for (base in order) {
                val body = tryGet(apiUrl(base, action, extra, u, p))
                if (!body.isNullOrBlank()) {
                    if (base != preferred) Session.server = base
                    return@withContext body
                }
            }
            null
        }

    suspend fun login(user: String, pass: String, preferredServer: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val prevU = Session.username
            val prevP = Session.password
            val prevS = Session.server
            Session.login(user, pass)
            if (!preferredServer.isNullOrBlank()) {
                Session.server = preferredServer
            }
            val json = fetch()
            if (json.isNullOrBlank()) {
                Session.login(prevU, prevP)
                Session.server = prevS
                return@withContext false
            }
            val res = runCatching { gson.fromJson(json, LoginResponse::class.java) }.getOrNull()
            val info = res?.userInfo
            val auth = info?.auth?.toString()?.trim()
            val status = info?.status?.trim()
            val badAuth = auth == "0" || auth.equals("false", true)
            val badStatus = status.equals("Disabled", true) ||
                status.equals("Banned", true) ||
                status.equals("Expired", true)
            if (info == null || badAuth || badStatus) {
                Session.login(prevU, prevP)
                Session.server = prevS
                false
            } else {
                android.util.Log.i("Xtream", "login OK server=${Session.server} user=$user")
                true
            }
        }

    suspend fun liveCategories(): List<LiveCategory> {
        val json = fetch("get_live_categories") ?: return emptyList()
        val type = object : TypeToken<List<LiveCategory>>() {}.type
        return runCatching { gson.fromJson<List<LiveCategory>>(json, type) }.getOrNull().orEmpty()
    }

    suspend fun liveChannels(): List<LiveChannel> {
        val json = fetch("get_live_streams") ?: return emptyList()
        val type = object : TypeToken<List<LiveChannel>>() {}.type
        return runCatching { gson.fromJson<List<LiveChannel>>(json, type) }.getOrNull().orEmpty()
    }

    suspend fun movies(): List<VodItem> {
        val json = fetch("get_vod_streams") ?: return emptyList()
        val type = object : TypeToken<List<VodItem>>() {}.type
        return runCatching { gson.fromJson<List<VodItem>>(json, type) }.getOrNull().orEmpty()
    }

    suspend fun vodCategories(): List<LiveCategory> {
        val json = fetch("get_vod_categories") ?: return emptyList()
        val type = object : TypeToken<List<LiveCategory>>() {}.type
        return runCatching { gson.fromJson<List<LiveCategory>>(json, type) }.getOrNull().orEmpty()
    }

    suspend fun series(): List<SeriesItem> {
        val json = fetch("get_series") ?: return emptyList()
        val type = object : TypeToken<List<SeriesItem>>() {}.type
        return runCatching { gson.fromJson<List<SeriesItem>>(json, type) }.getOrNull().orEmpty()
    }

    suspend fun seriesCategories(): List<LiveCategory> {
        val json = fetch("get_series_categories") ?: return emptyList()
        val type = object : TypeToken<List<LiveCategory>>() {}.type
        return runCatching { gson.fromJson<List<LiveCategory>>(json, type) }.getOrNull().orEmpty()
    }

    /**
     * Detalle de serie: info (sinopsis, actores, cover) + episodios por temporada.
     */
    suspend fun seriesDetail(seriesId: String): SeriesDetail =
        withContext(Dispatchers.IO) {
            val empty = SeriesDetail(null, emptyMap())
            val sid = seriesId.substringBefore(".0")
            val json = fetch("get_series_info", mapOf("series_id" to sid)) ?: return@withContext empty
            try {
                val root = com.google.gson.JsonParser.parseString(json).asJsonObject
                val infoEl = root.get("info")
                val info = if (infoEl != null && infoEl.isJsonObject) {
                    gson.fromJson(infoEl, SeriesDetailInfo::class.java)
                } else null

                val episodesRoot = root.get("episodes")
                if (episodesRoot == null || !episodesRoot.isJsonObject) {
                    return@withContext SeriesDetail(info, emptyMap())
                }
                val unsorted = linkedMapOf<String, List<SeriesEpisode>>()
                for ((seasonKey, seasonVal) in episodesRoot.asJsonObject.entrySet()) {
                    val list = mutableListOf<SeriesEpisode>()
                    val elements: List<com.google.gson.JsonElement> = when {
                        seasonVal.isJsonArray -> seasonVal.asJsonArray.toList()
                        seasonVal.isJsonObject -> seasonVal.asJsonObject.entrySet().map { it.value }
                        else -> emptyList()
                    }
                    for (el in elements) {
                        if (!el.isJsonObject) continue
                        val o = el.asJsonObject
                        val id = (jsonAsString(o.get("id")) ?: jsonAsString(o.get("episode_id")))
                            ?.substringBefore(".0")
                            .orEmpty()
                        if (id.isBlank()) continue
                        val epNum = jsonAsString(o.get("episode_num"))
                            ?.substringBefore(".")
                            ?.toIntOrNull()
                            ?: 0
                        val title = jsonAsString(o.get("title"))
                            ?: jsonAsString(o.getAsJsonObject("info")?.get("name"))
                            ?: ""
                        val ext = jsonAsString(o.get("container_extension")) ?: "mp4"
                        val image = jsonAsString(o.getAsJsonObject("info")?.get("movie_image"))
                        list.add(
                            SeriesEpisode(
                                id = id,
                                season = seasonKey,
                                episodeNum = epNum,
                                title = title,
                                ext = ext.ifBlank { "mp4" },
                                image = image
                            )
                        )
                    }
                    if (list.isNotEmpty()) {
                        unsorted[seasonKey] = list.sortedBy { it.episodeNum }
                    }
                }
                val sortedKeys = unsorted.keys.sortedWith(compareBy { it.toIntOrNull() ?: Int.MAX_VALUE })
                val map = linkedMapOf<String, List<SeriesEpisode>>()
                sortedKeys.forEach { map[it] = unsorted[it].orEmpty() }
                SeriesDetail(info, map)
            } catch (t: Throwable) {
                android.util.Log.w("Xtream", "seriesDetail failed: ${t.message}")
                empty
            }
        }

    /** @deprecated use seriesDetail */
    suspend fun seriesEpisodes(seriesId: String): Pair<String?, Map<String, List<SeriesEpisode>>> {
        val d = seriesDetail(seriesId)
        return (d.info?.posterUrl) to d.episodes
    }

    /**
     * Detalle de película: info (sinopsis, actores, cover) + extensión del contenedor.
     */
    suspend fun movieDetail(vodId: String): Pair<SeriesDetailInfo?, String> =
        withContext(Dispatchers.IO) {
            val sid = vodId.substringBefore(".0")
            val json = fetch("get_vod_info", mapOf("vod_id" to sid))
                ?: return@withContext null to "mp4"
            try {
                val root = com.google.gson.JsonParser.parseString(json).asJsonObject
                val infoEl = root.get("info")
                val info = if (infoEl != null && infoEl.isJsonObject) {
                    gson.fromJson(infoEl, SeriesDetailInfo::class.java)
                } else null
                val movieData = root.get("movie_data")
                val ext = if (movieData != null && movieData.isJsonObject) {
                    movieData.asJsonObject.get("container_extension")?.asString?.trim()?.ifBlank { null }
                } else null
                info to (ext ?: "mp4")
            } catch (_: Exception) {
                null to "mp4"
            }
        }

    private fun jsonAsString(el: com.google.gson.JsonElement?): String? {
        if (el == null || el.isJsonNull || !el.isJsonPrimitive) return null
        val p = el.asJsonPrimitive
        return when {
            p.isNumber -> p.asNumber.toString()
            p.isString -> p.asString
            else -> runCatching { p.asString }.getOrNull()
        }
    }

    fun liveUrl(channelId: String): String {
        val u = Session.username
        val p = Session.password
        val id = channelId.substringBefore(".0")
        val base = Session.server.trimEnd('/')
        // ElitePlus / Xtream: .ts suele ir mejor por HTTP
        return "$base/live/$u/$p/$id.ts"
    }

    fun liveCandidates(channelId: String): List<String> {
        val u = Session.username
        val p = Session.password
        val id = channelId.substringBefore(".0")
        val base = Session.server.trimEnd('/')
        return listOf(
            "$base/live/$u/$p/$id.ts",
            "$base/live/$u/$p/$id.m3u8"
        )
    }

    fun movieUrl(id: String, ext: String): String {
        val u = Session.username
        val p = Session.password
        val e = ext.ifBlank { "mp4" }
        return "${Session.server.trimEnd('/')}/movie/$u/$p/${id.substringBefore(".0")}.$e"
    }

    fun seriesUrl(episodeId: String, ext: String): String {
        val u = Session.username
        val p = Session.password
        val e = ext.ifBlank { "mp4" }
        return "${Session.server.trimEnd('/')}/series/$u/$p/${episodeId.substringBefore(".0")}.$e"
    }
}
