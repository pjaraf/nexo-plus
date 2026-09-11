package com.nexo.tv.data

import com.google.gson.annotations.SerializedName

data class UserInfo(
    val username: String? = null,
    val password: String? = null,
    val message: String? = null,
    val auth: Any? = null,
    val status: String? = null,
    @SerializedName("exp_date") val expDate: Any? = null,
    @SerializedName("is_trial") val isTrial: Any? = null,
    @SerializedName("active_cons") val activeCons: Any? = null,
    @SerializedName("created_at") val createdAt: Any? = null,
    @SerializedName("max_connections") val maxConnections: Any? = null,
)

data class LoginResponse(
    @SerializedName("user_info") val userInfo: UserInfo? = null
)

data class LiveCategory(
    @SerializedName("category_id") val categoryId: String = "",
    @SerializedName("category_name") val categoryName: String = ""
)

data class LiveChannel(
    @SerializedName("stream_id") val streamId: Any? = null,
    @SerializedName("num") val num: Any? = null,
    val name: String = "",
    @SerializedName("stream_icon") val streamIcon: String? = null,
    @SerializedName("category_id") val categoryId: String? = null
) {
    val id: String get() = streamId?.toString()?.substringBefore(".0").orEmpty()
    val channelNumber: String get() = num?.toString()?.substringBefore(".0").orEmpty()
}

data class VodItem(
    @SerializedName("stream_id") val streamId: Any? = null,
    val name: String? = null,
    @SerializedName("stream_icon") val streamIcon: String? = null,
    @SerializedName("container_extension") val ext: String? = "mp4",
    val year: String? = null,
    @SerializedName("releasedate") val releaseDate: String? = null,
    @SerializedName("added") val added: String? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    val genre: String? = null,
    @SerializedName("num") val num: Any? = null,
    val rating: Any? = null,
    @SerializedName("rating_5based") val rating5based: Any? = null
) {
    val id: String get() = streamId?.toString()?.substringBefore(".0").orEmpty()
    val displayName: String get() = name?.trim().orEmpty()

    /** Rating TMDB/web (0–10) cuando el panel lo envía. */
    val ratingValue: Double
        get() {
            rating?.toString()?.replace(",", ".")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { return it }
            val five = rating5based?.toString()?.replace(",", ".")?.toDoubleOrNull()?.takeIf { it > 0 }
            return five?.times(2.0) ?: 0.0
        }

    val addedEpoch: Long
        get() = added?.toString()?.substringBefore(".0")?.toLongOrNull() ?: 0L

    fun matchesYear(target: Int): Boolean {
        val y = target.toString()
        if (year?.trim() == y) return true
        if (releaseDate?.contains(y) == true) return true
        val title = displayName
        if (title.isEmpty()) return false
        return yearRegex(target).containsMatchIn(title)
    }

    companion object {
        private val yearRegexCache = HashMap<Int, Regex>(8)
        private fun yearRegex(target: Int): Regex =
            yearRegexCache.getOrPut(target) {
                Regex("""(?:^|[^\d])$target(?:[^\d]|$)""")
            }
    }
}

data class SeriesItem(
    @SerializedName("series_id") val seriesId: Any? = null,
    val name: String = "",
    val cover: String? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    val genre: String? = null,
    @SerializedName("added") val added: String? = null,
    val rating: Any? = null,
    @SerializedName("rating_5based") val rating5based: Any? = null
) {
    val id: String get() = seriesId?.toString()?.substringBefore(".0").orEmpty()

    val ratingValue: Double
        get() {
            rating?.toString()?.replace(",", ".")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { return it }
            val five = rating5based?.toString()?.replace(",", ".")?.toDoubleOrNull()?.takeIf { it > 0 }
            return five?.times(2.0) ?: 0.0
        }

    val addedEpoch: Long
        get() = added?.toString()?.substringBefore(".0")?.toLongOrNull() ?: 0L
}

data class SeriesDetailInfo(
    val name: String? = null,
    val cover: String? = null,
    @SerializedName("movie_image") val movieImage: String? = null,
    @SerializedName("cover_big") val coverBig: String? = null,
    val plot: String? = null,
    val description: String? = null,
    val cast: String? = null,
    val genre: String? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("releasedate") val releaseDateAlt: String? = null,
    val rating: Any? = null,
    @SerializedName("backdrop_path") val backdropPath: Any? = null
) {
    val displayTitle: String get() = name?.trim().orEmpty()
    val displayPlot: String?
        get() = plot?.takeIf { it.isNotBlank() } ?: description?.takeIf { it.isNotBlank() }
    val posterUrl: String?
        get() = cover?.takeIf { it.isNotBlank() }
            ?: movieImage?.takeIf { it.isNotBlank() }
            ?: coverBig?.takeIf { it.isNotBlank() }
    val displayDate: String
        get() = releaseDate?.trim()?.takeIf { it.isNotBlank() }
            ?: releaseDateAlt?.trim()?.takeIf { it.isNotBlank() }
            ?: ""
    val ratingBadge: String
        get() {
            val raw = rating?.toString()?.trim().orEmpty()
            if (raw.isBlank() || raw == "0" || raw == "0.0") return ""
            val n = raw.replace(",", ".").toDoubleOrNull()
            return if (n != null) n.toInt().toString() else raw.take(3)
        }
    val backdropUrl: String?
        get() = fanartUrl ?: posterUrl

    /** Solo escena / fanart horizontal (sin caer a la carátula vertical). */
    val fanartUrl: String?
        get() = when (val b = backdropPath) {
            is List<*> -> b.asSequence()
                .mapNotNull { it?.toString()?.trim()?.takeIf { u -> u.startsWith("http") } }
                .firstOrNull()
            is String -> b.trim().takeIf { it.startsWith("http") }
            else -> null
        }
}

data class SeriesEpisode(
    val id: String,
    val season: String,
    val episodeNum: Int,
    val title: String,
    val ext: String = "mp4",
    val image: String? = null
) {
    val label: String
        get() {
            val ep = if (episodeNum > 0) "E$episodeNum" else "Ep"
            val name = title.trim()
            return if (name.isNotBlank() && !name.equals("Episode $episodeNum", true)) {
                "$ep · $name"
            } else {
                "Episodio $episodeNum"
            }
        }
}

data class SeriesDetail(
    val info: SeriesDetailInfo?,
    val episodes: Map<String, List<SeriesEpisode>>
)
