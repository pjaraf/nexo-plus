package com.nexo.tv.data

import com.google.gson.annotations.SerializedName

data class UserInfo(
    val username: String? = null,
    val status: String? = null,
    val auth: Any? = null
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
    val genre: String? = null
) {
    val id: String get() = streamId?.toString()?.substringBefore(".0").orEmpty()
    val displayName: String get() = name?.trim().orEmpty()

    fun matchesYear(target: Int): Boolean {
        val y = target.toString()
        if (year?.trim() == y) return true
        if (releaseDate?.contains(y) == true) return true
        val title = displayName
        if (title.isEmpty()) return false
        return Regex("""(?:^|[^\d])$y(?:[^\d]|$)""").containsMatchIn(title)
    }
}

data class SeriesItem(
    @SerializedName("series_id") val seriesId: Any? = null,
    val name: String = "",
    val cover: String? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    val genre: String? = null
) {
    val id: String get() = seriesId?.toString()?.substringBefore(".0").orEmpty()
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
        get() = when (val b = backdropPath) {
            is List<*> -> b.firstOrNull()?.toString()?.takeIf { it.isNotBlank() }
            is String -> b.takeIf { it.isNotBlank() }
            else -> null
        } ?: posterUrl
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
