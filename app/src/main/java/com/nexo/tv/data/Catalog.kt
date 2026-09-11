package com.nexo.tv.data

import android.content.Context
import com.nexo.tv.ui.PosterPreloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private val preloadLock = Mutex()
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    /** Precarga en segundo plano (sobrevive al finish de MainActivity). */
    fun preloadAsync(context: Context) {
        val app = context.applicationContext
        bgScope.launch {
            runCatching { preload(app) }
                .onFailure { android.util.Log.w("Catalog", "preloadAsync fail: ${it.message}") }
        }
    }

    /**
     * Carga catálogo + arranca warm de carátulas.
     * Seguro ante llamadas concurrentes (mutex).
     */
    suspend fun preload(context: Context) = preloadLock.withLock {
        val app = context.applicationContext
        if (ready && movies.isNotEmpty() && series.isNotEmpty()) {
            // Ya en memoria de este proceso: solo refrescar carátulas en Coil.
            warmCovers(app)
            return@withLock
        }
        coroutineScope {
            val moviesJob = async { runCatching { XtreamClient.movies() }.getOrDefault(emptyList()) }
            val seriesJob = async { runCatching { XtreamClient.series() }.getOrDefault(emptyList()) }
            val movieCatsJob = async { runCatching { XtreamClient.vodCategories() }.getOrDefault(emptyList()) }
            val seriesCatsJob = async { runCatching { XtreamClient.seriesCategories() }.getOrDefault(emptyList()) }
            // TV en vivo en paralelo; no bloquea
            async { runCatching { LiveBoot.preload(app) } }

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
            warmCovers(app)
        }
    }

    private fun warmCovers(context: Context) {
        val firstScreen = firstScreenCoverUrls()
        val mobileGrid = mobileGridCoverUrls()
        PosterPreloader.warmPriorityAsync(context, firstScreen + mobileGrid.take(120))
        val firstSet = (firstScreen + mobileGrid.take(120)).toHashSet()
        val rest = (browseCoverUrls() + mobileGrid).filterNot { it in firstSet }
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

    /** Primeras carátulas del grid móvil Películas/Series. */
    private fun mobileGridCoverUrls(): List<String> {
        val out = LinkedHashSet<String>()
        movies.take(120).mapNotNull { cleanUrl(it.streamIcon) }.forEach { out += it }
        series.take(120).mapNotNull { cleanUrl(it.cover) }.forEach { out += it }
        return out.toList()
    }

    private fun homeMovies(): List<VodItem> {
        val popularShelf = movieShelves.firstOrNull { shelf ->
            val n = shelf.name.lowercase()
            listOf(
                "popular", "top", "trending", "solicit", "visto", "recomend",
                "destac", "mejor", "más vist", "mas vist", "hot", "favorit"
            ).any { n.contains(it) }
        }
        val pool = when {
            popularShelf != null -> {
                val byCat = movies.filter { it.categoryId == popularShelf.id }
                if (byCat.isNotEmpty()) byCat
                else {
                    val ids = popularShelf.posters.map { it.id }.toHashSet()
                    movies.filter { it.id in ids }
                }
            }
            else -> {
                val y2026 = movies.filter { it.matchesYear(2026) }
                if (y2026.isNotEmpty()) y2026 else movies
            }
        }
        return pool
            .sortedWith(
                compareByDescending<VodItem> { it.ratingValue }
                    .thenByDescending { it.addedEpoch }
            )
            .take(60)
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
