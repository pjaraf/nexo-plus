package com.nexo.tv.ui

import android.content.Intent
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nexo.tv.AppExit
import com.nexo.tv.LiveActivity
import com.nexo.tv.MovieActivity
import com.nexo.tv.SeriesActivity
import com.nexo.tv.Session
import com.nexo.tv.data.BackdropCache
import com.nexo.tv.data.Catalog
import com.nexo.tv.data.CategoryShelf
import com.nexo.tv.data.SeriesItem
import com.nexo.tv.data.VodItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Orange = Color(0xFFDE5B17)
private val PosterW = 132.dp
private val PosterH = 188.dp

private enum class Tab { HOME, TV, SERIES, MOVIES }
private data class FanartRequest(
    val id: String,
    val series: Boolean,
    val coverFallback: String? = null
)

@Composable
fun HubScreen(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tab by remember { mutableStateOf(Tab.HOME) }
    var hubBackdropUrl by remember { mutableStateOf<String?>(null) }
    var hubBackdropFromPoster by remember { mutableStateOf(false) }
    var fanartRequest by remember { mutableStateOf<FanartRequest?>(null) }
    val catalogGen by Catalog.generationFlow.collectAsState()
    val movies = remember(catalogGen) { Catalog.movies }
    val series = remember(catalogGen) { Catalog.series }
    val movieShelvesCached = remember(catalogGen) { Catalog.movieShelves }
    val movies2026 = remember(movies) { movies.filter { it.matchesYear(2026) } }
    val firstShelf = remember(movieShelvesCached) { movieShelvesCached.firstOrNull() }
    val firstCategoryMovies = remember(movies, firstShelf) {
        if (firstShelf == null) emptyList()
        else {
            val catId = firstShelf.id
            val inCat = movies.filter { it.categoryId == catId }
            if (inCat.isNotEmpty()) inCat
            else {
                val ids = firstShelf.posters.map { it.id }.toSet()
                movies.filter { it.id in ids }
            }
        }
    }
    val popularShelf = remember(movieShelvesCached) {
        movieShelvesCached.firstOrNull { shelf ->
            val n = shelf.name.lowercase()
            listOf(
                "popular", "top", "trending", "solicit", "visto", "recomend",
                "destac", "mejor", "más vist", "mas vist", "más pedid", "mas pedid",
                "hot", "favorit"
            ).any { n.contains(it) }
        }
    }
    val (homeCategoryTitle, homeMovies) = remember(
        movies,
        movieShelvesCached,
        popularShelf,
        movies2026,
        firstShelf,
        firstCategoryMovies
    ) {
        val pool = when {
            popularShelf != null -> {
                val ids = popularShelf.posters.map { it.id }.toHashSet()
                val byCat = movies.filter { it.categoryId == popularShelf.id }
                if (byCat.isNotEmpty()) byCat else movies.filter { it.id in ids }
            }
            movies2026.isNotEmpty() -> movies2026
            firstCategoryMovies.isNotEmpty() -> firstCategoryMovies
            else -> movies
        }
        val sorted = pool
            .sortedWith(
                compareByDescending<VodItem> { it.ratingValue }
                    .thenByDescending { it.addedEpoch }
            )
            .take(60)
        val title = when {
            popularShelf != null -> popularShelf.name.ifBlank { "Más solicitadas" }
            else -> "Más solicitadas"
        }
        title to sorted
    }
    val moviesById = remember(movies) { movies.associateBy { it.id } }
    val seriesById = remember(series) { series.associateBy { it.id } }
    val liveFocus = remember { FocusRequester() }

    // Foco por defecto en TV en vivo (OK abre canales).
    var hubResumeTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hubResumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(hubResumeTick) {
        delay(200)
        runCatching { liveFocus.requestFocus() }
    }

    // Fanart primero: nunca mostrar carátula mientras llega el fanart.
    // Sin fanart: fondo automatico generado desde la carátula (blur cinematografico).
    LaunchedEffect(fanartRequest) {
        val req = fanartRequest ?: return@LaunchedEffect
        val cover = req.coverFallback?.trim()?.takeIf { it.isNotBlank() }
        val cached = if (req.series) {
            BackdropCache.cachedSeriesFanart(req.id)
        } else {
            BackdropCache.cachedMovieFanart(req.id)
        }
        if (cached != null) {
            hubBackdropUrl = cached
            hubBackdropFromPoster = false
            warmCinematicFanart(ctx, cached, fromPoster = false)
            return@LaunchedEffect
        }
        val knownMiss = if (req.series) {
            BackdropCache.isSeriesFanartMiss(req.id)
        } else {
            BackdropCache.isMovieFanartMiss(req.id)
        }
        if (knownMiss) {
            if (cover != null) {
                hubBackdropUrl = cover
                hubBackdropFromPoster = true
                warmCinematicFanart(ctx, cover, fromPoster = true)
            }
            return@LaunchedEffect
        }
        // Mantener fondo anterior hasta que llegue el fanart (sin flash de carátula).
        val url = if (req.series) {
            BackdropCache.seriesFanart(req.id)
        } else {
            BackdropCache.movieFanart(req.id)
        }
        if (fanartRequest != req) return@LaunchedEffect
        if (url != null) {
            hubBackdropUrl = url
            hubBackdropFromPoster = false
            warmCinematicFanart(ctx, url, fromPoster = false)
        } else if (cover != null) {
            hubBackdropUrl = cover
            hubBackdropFromPoster = true
            warmCinematicFanart(ctx, cover, fromPoster = true)
        }
    }

    // Precargar fanarts del home primero (página visible) para cambio instantáneo.
    LaunchedEffect(homeMovies) {
        homeMovies.take(28).forEachIndexed { index, m ->
            launch(Dispatchers.IO) {
                val url = BackdropCache.movieFanart(m.id) ?: return@launch
                withContext(Dispatchers.Main.immediate) {
                    warmCinematicFanart(ctx, url, fromPoster = false)
                    // Si es la destacada actual y aún no hay fondo, aplicar ya.
                    if (index == 0 && hubBackdropUrl == null) {
                        hubBackdropUrl = url
                        hubBackdropFromPoster = false
                    }
                }
            }
        }
    }

    fun openMovie(item: VodItem, resumeMs: Long = -1L) {
        val fanart = BackdropCache.cachedMovieFanart(item.id)
        fanart?.let { warmCinematicFanart(ctx, it, fromPoster = false) }
        AppExit.openChildActivity {
            ctx.startActivity(
                Intent(ctx, MovieActivity::class.java)
                    .putExtra(MovieActivity.EXTRA_MOVIE_ID, item.id)
                    .putExtra(MovieActivity.EXTRA_MOVIE_NAME, item.displayName)
                    .putExtra(MovieActivity.EXTRA_MOVIE_COVER, item.streamIcon.orEmpty())
                    .putExtra(MovieActivity.EXTRA_MOVIE_FANART, fanart.orEmpty())
                    .putExtra(MovieActivity.EXTRA_CATEGORY_ID, item.categoryId.orEmpty())
                    .putExtra(MovieActivity.EXTRA_EXT, item.ext ?: "mp4")
                    .putExtra(MovieActivity.EXTRA_RESUME_MS, resumeMs)
                    .putExtra(MovieActivity.EXTRA_USER, Session.username)
                    .putExtra(MovieActivity.EXTRA_PASS, Session.password)
                    .putExtra(MovieActivity.EXTRA_SERVER, Session.server)
            )
        }
    }

    fun openSeries(
        item: SeriesItem,
        resumeEpisodeId: String = "",
        resumeMs: Long = -1L
    ) {
        val fanart = BackdropCache.cachedSeriesFanart(item.id)
        fanart?.let { warmCinematicFanart(ctx, it, fromPoster = false) }
        AppExit.openChildActivity {
            ctx.startActivity(
                Intent(ctx, SeriesActivity::class.java)
                    .putExtra(SeriesActivity.EXTRA_SERIES_ID, item.id)
                    .putExtra(SeriesActivity.EXTRA_SERIES_NAME, item.name)
                    .putExtra(SeriesActivity.EXTRA_SERIES_COVER, item.cover.orEmpty())
                    .putExtra(SeriesActivity.EXTRA_SERIES_FANART, fanart.orEmpty())
                    .putExtra(SeriesActivity.EXTRA_CATEGORY_ID, item.categoryId.orEmpty())
                    .putExtra(SeriesActivity.EXTRA_RESUME_EPISODE_ID, resumeEpisodeId)
                    .putExtra(SeriesActivity.EXTRA_RESUME_MS, resumeMs)
                    .putExtra(SeriesActivity.EXTRA_USER, Session.username)
                    .putExtra(SeriesActivity.EXTRA_PASS, Session.password)
                    .putExtra(SeriesActivity.EXTRA_SERVER, Session.server)
            )
        }
    }

    fun openLive() {
        AppExit.openChildActivity {
            ctx.startActivity(
                Intent(ctx, LiveActivity::class.java)
                    .putExtra(LiveActivity.EXTRA_USER, Session.username)
                    .putExtra(LiveActivity.EXTRA_PASS, Session.password)
                    .putExtra(LiveActivity.EXTRA_SERVER, Session.server)
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        CinematicBackdrop(
            url = if (tab == Tab.TV) null else hubBackdropUrl,
            fromPoster = hubBackdropFromPoster && tab != Tab.TV
        )

        Box(Modifier.fillMaxSize()) {
            when (tab) {
                Tab.HOME -> HomePane(
                    title = homeCategoryTitle,
                    movies = homeMovies,
                    onMovie = { openMovie(it) },
                    onFeaturedChange = { movie ->
                        val id = movie?.id?.takeIf { it.isNotBlank() }
                        if (id == null) {
                            fanartRequest = null
                        } else {
                            val cover = movie.streamIcon?.trim()?.takeIf { it.isNotBlank() }
                            val cached = BackdropCache.cachedMovieFanart(id)
                            when {
                                cached != null -> {
                                    hubBackdropUrl = cached
                                    hubBackdropFromPoster = false
                                }
                                BackdropCache.isMovieFanartMiss(id) && cover != null -> {
                                    hubBackdropUrl = cover
                                    hubBackdropFromPoster = true
                                }
                                // Si hay fanart pendiente, no poner carátula: mantiene el fondo anterior.
                            }
                            fanartRequest = FanartRequest(id, series = false, coverFallback = cover)
                        }
                    }
                )
                Tab.SERIES -> Box(
                    Modifier
                        .padding(start = 88.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)
                        .fillMaxSize()
                ) {
                    val shelves = remember(catalogGen) { Catalog.seriesShelves }
                    if (shelves.isEmpty()) {
                        if (Catalog.ready) {
                            Text(
                                "No hay series en el catálogo",
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 18.sp,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    } else {
                        CategoryBrowser(
                            shelves = shelves,
                            onPoster = { id -> seriesById[id]?.let { openSeries(it) } },
                            onFocusId = { id ->
                                seriesById[id]?.let { s ->
                                    val cover = s.cover?.trim()?.takeIf { it.isNotBlank() }
                                    val cached = BackdropCache.cachedSeriesFanart(id)
                                    when {
                                        cached != null -> {
                                            hubBackdropUrl = cached
                                            hubBackdropFromPoster = false
                                        }
                                        BackdropCache.isSeriesFanartMiss(id) && cover != null -> {
                                            hubBackdropUrl = cover
                                            hubBackdropFromPoster = true
                                        }
                                    }
                                    fanartRequest = FanartRequest(id, series = true, coverFallback = cover)
                                }
                            }
                        )
                    }
                }
                Tab.MOVIES -> Box(
                    Modifier
                        .padding(start = 88.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)
                        .fillMaxSize()
                ) {
                    val shelves = remember(catalogGen) { Catalog.movieShelves }
                    if (shelves.isEmpty()) {
                        if (Catalog.ready) {
                            Text(
                                "No hay películas en el catálogo",
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 18.sp,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    } else {
                        CategoryBrowser(
                            shelves = shelves,
                            onPoster = { id -> moviesById[id]?.let { openMovie(it) } },
                            onFocusId = { id ->
                                moviesById[id]?.let { m ->
                                    val cover = m.streamIcon?.trim()?.takeIf { it.isNotBlank() }
                                    val cached = BackdropCache.cachedMovieFanart(id)
                                    when {
                                        cached != null -> {
                                            hubBackdropUrl = cached
                                            hubBackdropFromPoster = false
                                        }
                                        BackdropCache.isMovieFanartMiss(id) && cover != null -> {
                                            hubBackdropUrl = cover
                                            hubBackdropFromPoster = true
                                        }
                                    }
                                    fanartRequest = FanartRequest(id, series = false, coverFallback = cover)
                                }
                            }
                        )
                    }
                }
                Tab.TV -> {}
            }
        }

        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 14.dp, top = 20.dp, bottom = 20.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "N",
                color = Orange,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            NavIcon(Icons.Filled.Home, tab == Tab.HOME) { tab = Tab.HOME }
            NavIcon(
                icon = Icons.Filled.LiveTv,
                selected = tab == Tab.TV,
                focusRequester = liveFocus
            ) {
                openLive()
            }
            NavIcon(Icons.Filled.Tv, tab == Tab.SERIES) { tab = Tab.SERIES }
            NavIcon(Icons.Filled.Movie, tab == Tab.MOVIES) { tab = Tab.MOVIES }
            Spacer(Modifier.weight(1f))
            NavIcon(Icons.Filled.Logout, false) {
                Session.logout()
                onLogout()
            }
        }
    }
}

@Composable
private fun NavIcon(
    icon: ImageVector,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(52.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(shape = RoundedCornerShape(50), focusedScale = 1.08f)
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused || selected -> Orange.copy(alpha = 0.92f)
                    else -> Color.Black.copy(alpha = 0.28f)
                }
            )
            .clickable(onClick = onClick)
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun HomePane(
    title: String,
    movies: List<VodItem>,
    onMovie: (VodItem) -> Unit,
    onFeaturedChange: (VodItem?) -> Unit
) {
    var featured by remember(movies) { mutableStateOf(movies.firstOrNull()) }
    LaunchedEffect(featured) { onFeaturedChange(featured) }
    LaunchedEffect(movies) {
        if (featured == null || movies.none { it.id == featured?.id }) {
            featured = movies.firstOrNull()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 88.dp, end = 28.dp, top = 26.dp, bottom = 14.dp)
    ) {
        featured?.let { movie ->
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = movie.displayName,
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .tvFocus(shape = RoundedCornerShape(12.dp), focusedScale = 1.04f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Orange)
                        .clickable { onMovie(movie) }
                        .focusable()
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reproducir", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (movies.isEmpty()) {
            Text(
                "No hay películas disponibles en el catálogo",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp)
            )
        } else {
            // Páginas fijas de 6 carátulas enteras (sin recortes al navegar).
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 10.dp
                val visible = 6
                // Deja aire a la derecha para que la 6.ª nunca se corte.
                val usable = maxWidth * 0.92f
                val posterW = (usable - gap * (visible - 1)) / visible
                val posterH = posterW * 1.5f
                var page by remember(movies) { mutableIntStateOf(0) }
                var pageDir by remember { mutableIntStateOf(1) }
                val pageCount = ((movies.size + visible - 1) / visible).coerceAtLeast(1)
                val firstFocus = remember { FocusRequester() }
                val lastFocus = remember { FocusRequester() }
                LaunchedEffect(movies) { page = 0 }
                LaunchedEffect(page) {
                    delay(40)
                    runCatching {
                        if (pageDir < 0) lastFocus.requestFocus() else firstFocus.requestFocus()
                    }
                }
                val pageItems = remember(movies, page) {
                    movies.drop(page * visible).take(visible)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom
                ) {
                    // Sentinela izq.: al enfocar, página anterior.
                    if (page > 0) {
                        Box(
                            Modifier
                                .size(1.dp)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        pageDir = -1
                                        page -= 1
                                    }
                                }
                                .focusable()
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        pageItems.forEachIndexed { i, m ->
                            val edgeRequester = when (i) {
                                0 -> firstFocus
                                pageItems.lastIndex -> lastFocus
                                else -> null
                            }
                            Poster(
                                url = m.streamIcon,
                                title = m.displayName,
                                modifier = Modifier
                                    .width(posterW)
                                    .height(posterH)
                                    .then(if (edgeRequester != null) Modifier.focusRequester(edgeRequester) else Modifier)
                                    .tvFocus(shape = RoundedCornerShape(10.dp), focusedScale = 1f)
                                    .onFocusChanged { if (it.isFocused) featured = m }
                                    .clickable { onMovie(m) }
                                    .focusable()
                            )
                        }
                    }
                    // Sentinela der.: al enfocar, página siguiente.
                    if (page < pageCount - 1) {
                        Box(
                            Modifier
                                .size(1.dp)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        pageDir = 1
                                        page += 1
                                    }
                                }
                                .focusable()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryBrowser(
    shelves: List<CategoryShelf>,
    onPoster: (String) -> Unit,
    onFocusId: (String) -> Unit = {}
) {
    var expanded by remember { mutableStateOf<CategoryShelf?>(null) }

    val open = expanded
    if (open != null) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .padding(start = 8.dp, bottom = 8.dp)
                    .tvFocus(shape = RoundedCornerShape(10.dp), focusedScale = 1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Orange.copy(alpha = 0.92f))
                    .clickable { expanded = null }
                    .focusable()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Volver · ${open.name}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            val gridItems = remember(open.id, open.posters) {
                open.posters.map { it.id to (it.cover to it.title) }
            }
            PosterGrid(
                items = gridItems,
                onClick = onPoster,
                onFocusId = onFocusId,
                modifier = Modifier
                    .weight(1f, fill = true)
                    .fillMaxWidth()
            )
        }
    } else {
        // Exactamente 3 filas visibles por página. Sin LazyColumn: el scroll parcial
        // era lo que cortaba carátulas arriba y dejaba asomar una 4ª fila abajo.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val rowsPerPage = 3
            val rowGap = 6.dp
            val shelfH = (maxHeight - rowGap * (rowsPerPage - 1)) / rowsPerPage
            val pages = remember(shelves) { shelves.chunked(rowsPerPage) }
            var page by remember(shelves) { mutableIntStateOf(0) }
            var pageDir by remember { mutableIntStateOf(1) }
            val pageCount = pages.size.coerceAtLeast(1)
            val pageShelves = pages.getOrElse(page) { emptyList() }
            val topFocus = remember { FocusRequester() }
            val bottomFocus = remember { FocusRequester() }

            LaunchedEffect(shelves) { page = 0 }
            LaunchedEffect(page, pageShelves.size) {
                delay(40)
                runCatching {
                    when {
                        pageDir < 0 && pageShelves.size > 1 -> bottomFocus.requestFocus()
                        else -> topFocus.requestFocus()
                    }
                }
            }

            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(rowGap)
            ) {
                pageShelves.forEachIndexed { rowIndex, shelf ->
                    val edgeRequester = when {
                        rowIndex == 0 -> topFocus
                        rowIndex == pageShelves.lastIndex -> bottomFocus
                        else -> null
                    }
                    CategoryShelfRow(
                        shelf = shelf,
                        shelfHeight = shelfH,
                        firstPosterRequester = edgeRequester,
                        onPoster = onPoster,
                        onFocusId = onFocusId,
                        onSeeAll = { expanded = shelf }
                    )
                }
            }
            if (page > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .size(1.dp)
                        .onFocusChanged {
                            if (it.isFocused) {
                                pageDir = -1
                                page -= 1
                            }
                        }
                        .focusable()
                )
            }
            if (page < pageCount - 1) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .size(1.dp)
                        .onFocusChanged {
                            if (it.isFocused) {
                                pageDir = 1
                                page += 1
                            }
                        }
                        .focusable()
                )
            }
        }
    }
}

@Composable
private fun CategoryShelfRow(
    shelf: CategoryShelf,
    shelfHeight: Dp,
    firstPosterRequester: FocusRequester? = null,
    onPoster: (String) -> Unit,
    onFocusId: (String) -> Unit,
    onSeeAll: () -> Unit
) {
    // 8 slots: 7 carátulas + "Ver categoría completa"
    val posterSlots = 7
    val preview = remember(shelf) { shelf.posters.take(posterSlots) }

    Column(
        Modifier
            .fillMaxWidth()
            .height(shelfHeight)
            .padding(horizontal = 2.dp)
    ) {
        Text(
            text = shelf.name,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, bottom = 3.dp, end = 2.dp)
        )
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)
        ) {
            val gap = 6.dp
            val slots = 8
            // Alto primero: la carátula siempre cabe entera en la fila (ratio 2:3).
            val maxPosterH = maxHeight
            val maxPosterW = (maxWidth - gap * (slots - 1)) / slots
            val posterH = minOf(maxPosterH, maxPosterW * 1.5f)
            val posterW = posterH * 2f / 3f
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                preview.forEachIndexed { index, poster ->
                    Poster(
                        url = poster.cover,
                        title = poster.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(posterW)
                            .height(posterH)
                            .then(
                                if (index == 0 && firstPosterRequester != null) {
                                    Modifier.focusRequester(firstPosterRequester)
                                } else {
                                    Modifier
                                }
                            )
                            .tvFocus(shape = RoundedCornerShape(8.dp), focusedScale = 1f)
                            .onFocusChanged { if (it.isFocused) onFocusId(poster.id) }
                            .clickable { onPoster(poster.id) }
                            .focusable()
                    )
                }
                SeeAllCategoryCard(
                    backdrop = shelf.posters.getOrNull(posterSlots)?.cover
                        ?: shelf.posters.firstOrNull()?.cover,
                    categoryName = shelf.name,
                    modifier = Modifier
                        .width(posterW)
                        .height(posterH),
                    onFocused = { },
                    onClick = onSeeAll
                )
            }
        }
    }
}

@Composable
private fun SeeAllCategoryCard(
    backdrop: String?,
    categoryName: String,
    modifier: Modifier = Modifier,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .tvFocus(shape = RoundedCornerShape(10.dp), focusedScale = 1f)
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged { onFocused(it.isFocused) }
            .clickable(onClick = onClick)
            .focusable()
    ) {
        PosterImage(
            url = backdrop,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFF7A18).copy(alpha = 0.92f),
                            Color(0xFFDE5B17).copy(alpha = 0.95f),
                            Color(0xFF3A1208).copy(alpha = 0.98f)
                        )
                    )
                )
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Ver categoría",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
            Text(
                "completa",
                color = Color.White.copy(alpha = 0.95f),
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(2.dp))
            Text(
                categoryName,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PosterGrid(
    items: List<Pair<String, Pair<String?, String>>>,
    onClick: (String) -> Unit = {},
    onFocusId: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cols = 7
    val rowsPerPage = 3
    val pageSize = cols * rowsPerPage
    val pages = remember(items) { items.chunked(pageSize) }
    var page by remember(items) { mutableIntStateOf(0) }
    var pageDir by remember { mutableIntStateOf(1) }
    val pageCount = pages.size.coerceAtLeast(1)
    val pageItems = pages.getOrElse(page) { emptyList() }
    val topFocus = remember { FocusRequester() }
    val bottomFocus = remember { FocusRequester() }

    LaunchedEffect(items) { page = 0 }
    LaunchedEffect(page, pageItems.size) {
        delay(40)
        runCatching {
            when {
                pageDir < 0 && pageItems.size > cols -> bottomFocus.requestFocus()
                else -> topFocus.requestFocus()
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        PosterPage(
            items = pageItems,
            cols = cols,
            rows = rowsPerPage,
            topFocus = topFocus,
            bottomFocus = bottomFocus,
            onClick = onClick,
            onFocusId = onFocusId,
            modifier = Modifier.fillMaxSize()
        )
        if (page > 0) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .size(1.dp)
                    .onFocusChanged {
                        if (it.isFocused) {
                            pageDir = -1
                            page -= 1
                        }
                    }
                    .focusable()
            )
        }
        if (page < pageCount - 1) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .size(1.dp)
                    .onFocusChanged {
                        if (it.isFocused) {
                            pageDir = 1
                            page += 1
                        }
                    }
                    .focusable()
            )
        }
    }
}

@Composable
private fun PosterPage(
    items: List<Pair<String, Pair<String?, String>>>,
    cols: Int,
    rows: Int,
    topFocus: FocusRequester,
    bottomFocus: FocusRequester,
    onClick: (String) -> Unit,
    onFocusId: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val hGap = 8.dp
    val vGap = 8.dp
    val rowItems = remember(items, cols) { items.chunked(cols) }

    BoxWithConstraints(modifier) {
        val rowH = (maxHeight - vGap * (rows - 1)) / rows
        val maxPosterW = (maxWidth - hGap * (cols - 1)) / cols
        // Que quepa entero en la celda (ratio 2:3), sin recorte por alto.
        val posterH = minOf(rowH, maxPosterW * 1.5f)
        val posterW = posterH * 2f / 3f

        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(vGap)
        ) {
            repeat(rows) { rowIndex ->
                val row = rowItems.getOrNull(rowIndex).orEmpty()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowH),
                    horizontalArrangement = Arrangement.spacedBy(hGap, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(cols) { colIndex ->
                        val item = row.getOrNull(colIndex)
                        val lastRowIndex = (rowItems.size - 1).coerceAtLeast(0)
                        val edgeRequester = when {
                            rowIndex == 0 && colIndex == 0 -> topFocus
                            rowIndex == lastRowIndex && colIndex == 0 && lastRowIndex > 0 -> bottomFocus
                            else -> null
                        }
                        Box(
                            modifier = Modifier
                                .width(posterW)
                                .height(posterH),
                            contentAlignment = Alignment.Center
                        ) {
                            if (item != null) {
                                Poster(
                                    url = item.second.first,
                                    title = item.second.second,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(
                                            if (edgeRequester != null) {
                                                Modifier.focusRequester(edgeRequester)
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .tvFocus(shape = RoundedCornerShape(8.dp), focusedScale = 1f)
                                        .onFocusChanged {
                                            if (it.isFocused) onFocusId(item.first)
                                        }
                                        .clickable { onClick(item.first) }
                                        .focusable()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Poster(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    PosterImage(
        url = url,
        contentDescription = title,
        contentScale = contentScale,
        modifier = modifier.clip(RoundedCornerShape(8.dp))
    )
}
