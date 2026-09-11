package com.nexo.tv.data

import android.content.Context

/** Favoritos locales (SharedPreferences). */
object Favorites {
    private const val PREFS = "nexo_favorites"
    private const val KEY_SERIES = "series_ids"
    private const val KEY_MOVIES = "movie_ids"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSeriesFavorite(ctx: Context, seriesId: String): Boolean {
        val id = seriesId.substringBefore(".0")
        return load(ctx, KEY_SERIES).contains(id)
    }

    fun toggleSeries(ctx: Context, seriesId: String): Boolean =
        toggle(ctx, KEY_SERIES, seriesId)

    fun isMovieFavorite(ctx: Context, movieId: String): Boolean {
        val id = movieId.substringBefore(".0")
        return load(ctx, KEY_MOVIES).contains(id)
    }

    fun toggleMovie(ctx: Context, movieId: String): Boolean =
        toggle(ctx, KEY_MOVIES, movieId)

    private fun toggle(ctx: Context, key: String, rawId: String): Boolean {
        val id = rawId.substringBefore(".0")
        val set = load(ctx, key).toMutableSet()
        val added = if (set.contains(id)) {
            set.remove(id)
            false
        } else {
            set.add(id)
            true
        }
        prefs(ctx).edit().putStringSet(key, set).apply()
        return added
    }

    private fun load(ctx: Context, key: String): Set<String> =
        prefs(ctx).getStringSet(key, emptySet())?.toSet().orEmpty()
}
