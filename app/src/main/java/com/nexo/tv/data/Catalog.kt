package com.nexo.tv.data

import android.content.Context
import com.nexo.tv.ui.PosterPreloader
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class CategoryShelf(
    val id: String,
    val name: String,
    val posters: List<PosterRef>
)

data class PosterRef(
    val id: String,
    val title: String,
    val cover: String?
)

/** Catálogo precargado al arrancar / tras login (datos + carátulas). */
object Catalog {
    @Volatile var movies: List<VodItem> = emptyList()
        private set
    @Volatile var series: List<SeriesItem> = emptyList()
        private set
    @Volatile var movieCategories: List<LiveCategory> = emptyList()
        private set
    @Volatile var seriesCategories: List<LiveCategory> = emptyList()
        private set
    @Volatile var ready: Boolean = false
        private set

    /** Incrementa cuando cambian películas/series para refrescar el Hub. */
    @Volatile var generation: Int = 0
        private set

    private fun bump() {
        generation++
    }

    val movieShelves: List<CategoryShelf>
        get() = buildShelves(
            categories = movieCategories,
            items = movies.map {
                Triple(it.categoryId.orEmpty(), PosterRef(it.id, it.displayName, it.streamIcon), it)
            }
        )

    val seriesShelves: List<CategoryShelf>
        get() = buildShelves(
            categories = seriesCategories,
            items = series.map {
                Triple(it.categoryId.orEmpty(), PosterRef(it.id, it.name, it.cover), it)
            }
        )

    /**
     * Carga el catálogo y precarga carátulas en segundo plano.
     * No bloquea con mensajes: al terminar los datos hace [bump] para refrescar el Hub.
     */
    suspend fun preload(context: Context) = coroutineScope {
        val moviesJob = async { runCatching { XtreamClient.movies() }.getOrDefault(emptyList()) }
        val seriesJob = async { runCatching { XtreamClient.series() }.getOrDefault(emptyList()) }
        val movieCatsJob = async { runCatching { XtreamClient.vodCategories() }.getOrDefault(emptyList()) }
        val seriesCatsJob = async { runCatching { XtreamClient.seriesCategories() }.getOrDefault(emptyList()) }
        movies = moviesJob.await()
        series = seriesJob.await()
        movieCategories = movieCatsJob.await()
        seriesCategories = seriesCatsJob.await()
        ready = true
        bump()
        android.util.Log.i(
            "Catalog",
            "ready movies=${movies.size} series=${series.size} " +
                "movieCats=${movieCategories.size} seriesCats=${seriesCategories.size}"
        )

        // Carátulas en segundo plano: no retienen la apertura del Hub
        val priority = priorityCoverUrls()
        val rest = browseCoverUrls().filterNot { it in priority.toSet() }
        PosterPreloader.warmBackground(context, priority + rest)
    }

    fun clear() {
        movies = emptyList()
        series = emptyList()
        movieCategories = emptyList()
        seriesCategories = emptyList()
        ready = false
        bump()
    }

    /** Home + primeras filas visibles de películas/series. */
    private fun priorityCoverUrls(): List<String> {
        val out = LinkedHashSet<String>()
        val movies2026 = movies.filter { it.matchesYear(2026) }
        val homeMovies = when {
            movies2026.isNotEmpty() -> movies2026
            else -> {
                val firstId = movieShelves.firstOrNull()?.id
                if (firstId != null) movies.filter { it.categoryId == firstId } else movies
            }
        }
        homeMovies.take(48).mapNotNull { cleanUrl(it.streamIcon) }.forEach { out += it }

        movieShelves.take(15).forEach { shelf ->
            shelf.posters.take(10).mapNotNull { cleanUrl(it.cover) }.forEach { out += it }
        }
        seriesShelves.take(15).forEach { shelf ->
            shelf.posters.take(10).mapNotNull { cleanUrl(it.cover) }.forEach { out += it }
        }
        return out.toList()
    }

    /** Carátulas de filas de categorías (lo que se ve al navegar). */
    private fun browseCoverUrls(): List<String> {
        val out = LinkedHashSet<String>()
        movieShelves.forEach { shelf ->
            shelf.posters.take(16).mapNotNull { cleanUrl(it.cover) }.forEach { out += it }
        }
        seriesShelves.forEach { shelf ->
            shelf.posters.take(16).mapNotNull { cleanUrl(it.cover) }.forEach { out += it }
        }
        return out.toList()
    }

    private fun cleanUrl(url: String?): String? =
        url?.trim()?.takeIf { it.isNotEmpty() }

    private fun <T> buildShelves(
        categories: List<LiveCategory>,
        items: List<Triple<String, PosterRef, T>>
    ): List<CategoryShelf> {
        val byCat = items.groupBy { it.first }
        val shelves = mutableListOf<CategoryShelf>()
        val seen = mutableSetOf<String>()

        for (cat in categories) {
            val id = cat.categoryId
            if (id.isBlank()) continue
            val posters = byCat[id].orEmpty().map { it.second }
            if (posters.isEmpty()) continue
            shelves += CategoryShelf(id = id, name = cat.categoryName.ifBlank { "Sin nombre" }, posters = posters)
            seen += id
        }

        for ((catId, group) in byCat) {
            if (catId.isBlank() || catId in seen) continue
            shelves += CategoryShelf(
                id = catId,
                name = "Otras",
                posters = group.map { it.second }
            )
        }

        val noCat = byCat[""].orEmpty()
        if (noCat.isNotEmpty()) {
            shelves += CategoryShelf(
                id = "_none",
                name = "Sin categoría",
                posters = noCat.map { it.second }
            )
        }

        return shelves
    }
}
