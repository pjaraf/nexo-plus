package com.nexo.tv.data

import android.content.Context
import com.nexo.tv.ui.PosterPreloader
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /** Estanterías cacheadas (no se reconstruyen en cada acceso del Hub). */
    @Volatile var movieShelves: List<CategoryShelf> = emptyList()
        private set
    @Volatile var seriesShelves: List<CategoryShelf> = emptyList()
        private set

    /** Incrementa cuando cambian películas/series para refrescar el Hub. */
    @Volatile var generation: Int = 0
        private set

    private val _generation = MutableStateFlow(0)
    val generationFlow: StateFlow<Int> = _generation.asStateFlow()

    private fun bump() {
        generation++
        _generation.value = generation
    }

    private fun rebuildShelves() {
        movieShelves = buildShelves(
            categories = movieCategories,
            items = movies.map {
                Triple(it.categoryId.orEmpty(), PosterRef(it.id, it.displayName, it.streamIcon), it)
            }
        )
        seriesShelves = buildShelves(
            categories = seriesCategories,
            items = series.map {
                Triple(it.categoryId.orEmpty(), PosterRef(it.id, it.name, it.cover), it)
            }
        )
    }

    /**
     * Carga catálogo lo antes posible para abrir el Hub; carátulas y live warm
     * siguen en segundo plano (misma UX, splash más corto).
     */
    suspend fun preload(context: Context) = coroutineScope {
        val moviesJob = async { runCatching { XtreamClient.movies() }.getOrDefault(emptyList()) }
        val seriesJob = async { runCatching { XtreamClient.series() }.getOrDefault(emptyList()) }
        val movieCatsJob = async { runCatching { XtreamClient.vodCategories() }.getOrDefault(emptyList()) }
        val seriesCatsJob = async { runCatching { XtreamClient.seriesCategories() }.getOrDefault(emptyList()) }
        // TV en vivo en paralelo; no bloquea la apertura del Hub
        async { runCatching { LiveBoot.preload(context) } }

        movies = moviesJob.await()
        series = seriesJob.await()
        movieCategories = movieCatsJob.await()
        seriesCategories = seriesCatsJob.await()
        rebuildShelves()
        ready = true
        bump()
        android.util.Log.i(
            "Catalog",
            "ready movies=${movies.size} series=${series.size} " +
                "movieCats=${movieCategories.size} seriesCats=${seriesCategories.size}"
        )

        val firstScreen = firstScreenCoverUrls()
        PosterPreloader.warmPriorityAsync(context, firstScreen)
        val firstSet = firstScreen.toHashSet()
        val rest = browseCoverUrls().filterNot { it in firstSet }
        PosterPreloader.warmBackground(context, rest)
    }

    fun clear() {
        movies = emptyList()
        series = emptyList()
        movieCategories = emptyList()
        seriesCategories = emptyList()
        movieShelves = emptyList()
        seriesShelves = emptyList()
        ready = false
        LiveBoot.clear()
        BackdropCache.clear()
        bump()
    }

    /** Portadas que se ven apenas entra al Hub (recortado a lo realmente visible). */
    private fun firstScreenCoverUrls(): List<String> {
        val out = LinkedHashSet<String>()
        homeMovies().take(14).mapNotNull { cleanUrl(it.streamIcon) }.forEach { out += it }
        movieShelves.take(3).forEach { shelf ->
            shelf.posters.take(8).mapNotNull { cleanUrl(it.cover) }.forEach { out += it }
        }
        seriesShelves.take(2).forEach { shelf ->
            shelf.posters.take(8).mapNotNull { cleanUrl(it.cover) }.forEach { out += it }
        }
        return out.toList()
    }

    private fun homeMovies(): List<VodItem> {
        val movies2026 = movies.filter { it.matchesYear(2026) }
        if (movies2026.isNotEmpty()) return movies2026
        val firstId = movieShelves.firstOrNull()?.id
        return if (firstId != null) movies.filter { it.categoryId == firstId } else movies
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
