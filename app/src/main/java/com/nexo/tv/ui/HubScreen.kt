package com.nexo.tv.ui

import android.content.Intent
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.nexo.tv.ui.CinematicBackdrop
import com.nexo.tv.ui.warmCinematicFanart
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
private data class FanartRequest(val id: String, val series: Boolean)

@Composable
fun HubScreen(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tab by remember { mutableStateOf(Tab.HOME) }
    var hubBackdropUrl by remember { mutableStateOf<String?>(null) }
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
    val (homeCategoryTitle, homeMovies) = remember(movies2026, firstShelf, firstCategoryMovies, movies) {
        if (movies2026.isNotEmpty()) {
            "Películas 2026" to movies2026
        } else if (firstCategoryMovies.isNotEmpty()) {
            (firstShelf?.name?.ifBlank { "Películas" } ?: "Películas") to firstCategoryMovies
        } else {
            "Películas" to movies
        }
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

    // Fanart: mantener la escena anterior hasta que llegue la nueva (sin flash NEXO).
    LaunchedEffect(fanartRequest) {
        val req = fanartRequest ?: return@LaunchedEffect
        val cached = if (req.series) {
            BackdropCache.cachedSeriesFanart(req.id)
        } else {
            BackdropCache.cachedMovieFanart(req.id)
        }
        if (cached != null) {
            hubBackdropUrl = cached
            warmCinematicFanart(ctx, cached)
        }
        val url = if (req.series) {
            BackdropCache.seriesFanart(req.id)
        } else {
            BackdropCache.movieFanart(req.id)
        }
        if (fanartRequest == req && url != null) {
            hubBackdropUrl = url
            warmCinematicFanart(ctx, url)
        }
    }

    // Precargar fanarts de la fila del home para cambio instantáneo
    LaunchedEffect(homeMovies) {
        homeMovies.take(28).forEach { m ->
            launch(Dispatchers.IO) {
                val url = BackdropCache.movieFanart(m.id) ?: return@launch
                withContext(Dispatchers.Main.immediate) {
                    warmCinematicFanart(ctx, url)
                }
            }
        }
    }

    fun openMovie(item: VodItem, resumeMs: Long = -1L) {
        AppExit.openChildActivity {
            ctx.startActivity(
                Intent(ctx, MovieActivity::class.java)
                    .putExtra(MovieActivity.EXTRA_MOVIE_ID, item.id)
                    .putExtra(MovieActivity.EXTRA_MOVIE_NAME, item.displayName)
                    .putExtra(MovieActivity.EXTRA_MOVIE_COVER, item.streamIcon.orEmpty())
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
        AppExit.openChildActivity {
            ctx.startActivity(
                Intent(ctx, SeriesActivity::class.java)
                    .putExtra(SeriesActivity.EXTRA_SERIES_ID, item.id)
                    .putExtra(SeriesActivity.EXTRA_SERIES_NAME, item.name)
                    .putExtra(SeriesActivity.EXTRA_SERIES_COVER, item.cover.orEmpty())
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
        CinematicBackdrop(url = if (tab == Tab.TV) null else hubBackdropUrl)

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
                            BackdropCache.cachedMovieFanart(id)?.let { hubBackdropUrl = it }
                            fanartRequest = FanartRequest(id, series = false)
                        }
                    }
                )
                Tab.SERIES -> Box(
                    Modifier
                        .padding(start = 88.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
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
                                BackdropCache.cachedSeriesFanart(id)?.let { hubBackdropUrl = it }
                                fanartRequest = FanartRequest(id, series = true)
                            }
                        )
                    }
                }
                Tab.MOVIES -> Box(
                    Modifier
                        .padding(start = 88.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
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
                                BackdropCache.cachedMovieFanart(id)?.let { hubBackdropUrl = it }
                                fanartRequest = FanartRequest(id, series = false)
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
    val bottomMovies = remember(movies) { movies.take(6) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 88.dp, end = 20.dp, top = 26.dp, bottom = 14.dp)
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
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 12.dp
                val count = bottomMovies.size.coerceAtLeast(1)
                val posterW = (maxWidth - gap * (count - 1)) / count
                val posterH = posterW * 1.5f
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.Bottom
                ) {
                    bottomMovies.forEach { m ->
                        Poster(
                            url = m.streamIcon,
                            title = m.displayName,
                            modifier = Modifier
                                .width(posterW)
                                .height(posterH)
                                .tvFocus(shape = RoundedCornerShape(10.dp), focusedScale = 1.04f)
                                .onFocusChanged { if (it.isFocused) featured = m }
                                .clickable { onMovie(m) }
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
                    .tvFocus(shape = RoundedCornerShape(10.dp), focusedScale = 1.03f)
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
                onFocusId = onFocusId
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(shelves, key = { it.id }) { shelf ->
                CategoryShelfRow(
                    shelf = shelf,
                    onPoster = onPoster,
                    onFocusId = onFocusId,
                    onSeeAll = { expanded = shelf }
                )
            }
        }
    }
}

@Composable
private fun CategoryShelfRow(
    shelf: CategoryShelf,
    onPoster: (String) -> Unit,
    onFocusId: (String) -> Unit,
    onSeeAll: () -> Unit
) {
    val previewCount = 7
    val preview = remember(shelf) { shelf.posters.take(previewCount) }
    val rowState = rememberLazyListState()
    var seeAllFocused by remember { mutableStateOf(false) }

    LaunchedEffect(seeAllFocused) {
        if (seeAllFocused) {
            rowState.animateScrollToItem(preview.size)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = shelf.name,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp, end = 8.dp)
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 7 carátulas visibles + la 8.ª apenas asomada hasta que llega el foco.
            val gap = 8.dp
            val visibleSlots = 7.28f
            val posterW = (maxWidth - gap * 7) / visibleSlots
            val posterH = posterW * 3f / 2f

            LazyRow(
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(gap),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(preview, key = { it.id }) { poster ->
                    Poster(
                        url = poster.cover,
                        title = poster.title,
                        modifier = Modifier
                            .width(posterW)
                            .height(posterH)
                            .tvFocus(shape = RoundedCornerShape(8.dp), focusedScale = 1.04f)
                            .onFocusChanged { if (it.isFocused) onFocusId(poster.id) }
                            .clickable { onPoster(poster.id) }
                            .focusable()
                    )
                }
                item(key = "see-all-${shelf.id}") {
                    SeeAllCategoryCard(
                        backdrop = shelf.posters.getOrNull(previewCount)?.cover
                            ?: shelf.posters.firstOrNull()?.cover,
                        categoryName = shelf.name,
                        modifier = Modifier
                            .width(posterW)
                            .height(posterH),
                        onFocused = { seeAllFocused = it },
                        onClick = onSeeAll
                    )
                }
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
            .tvFocus(shape = RoundedCornerShape(10.dp), focusedScale = 1.05f)
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
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Ver categoría",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Text(
                "completa",
                color = Color.White.copy(alpha = 0.95f),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                categoryName,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
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
    onFocusId: (String) -> Unit = {}
) {
    val cols = 7
    val rowsPerPage = 3
    val pageSize = cols * rowsPerPage
    val pages = remember(items) { items.chunked(pageSize) }
    val listState = rememberLazyListState()
    val snap = rememberSnapFlingBehavior(lazyListState = listState)
    val scope = rememberCoroutineScope()
    var focusedPage by remember { mutableIntStateOf(0) }

    LaunchedEffect(focusedPage) {
        if (pages.isEmpty()) return@LaunchedEffect
        val target = focusedPage.coerceIn(0, pages.lastIndex)
        listState.animateScrollToItem(target)
    }

    LazyColumn(
        state = listState,
        flingBehavior = snap,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = true
    ) {
        items(
            count = pages.size,
            key = { page -> "page-$page-${pages[page].firstOrNull()?.first}" }
        ) { pageIndex ->
            PosterPage(
                items = pages[pageIndex],
                cols = cols,
                rows = rowsPerPage,
                onClick = onClick,
                onFocusId = onFocusId,
                onFocusPage = {
                    if (focusedPage != pageIndex) {
                        focusedPage = pageIndex
                    } else {
                        scope.launch { listState.animateScrollToItem(pageIndex) }
                    }
                },
                modifier = Modifier.fillParentMaxSize()
            )
        }
    }
}

@Composable
private fun PosterPage(
    items: List<Pair<String, Pair<String?, String>>>,
    cols: Int,
    rows: Int,
    onClick: (String) -> Unit,
    onFocusId: (String) -> Unit,
    onFocusPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hGap = 6.dp
    val vGap = 6.dp
    val pad = 4.dp
    val rowItems = remember(items, cols) { items.chunked(cols) }

    Column(
        modifier = modifier.padding(pad),
        verticalArrangement = Arrangement.spacedBy(vGap)
    ) {
        repeat(rows) { rowIndex ->
            val row = rowItems.getOrNull(rowIndex).orEmpty()
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(hGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(cols) { colIndex ->
                    val item = row.getOrNull(colIndex)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (item != null) {
                            Poster(
                                url = item.second.first,
                                title = item.second.second,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(2f / 3f)
                                    .tvFocus(shape = RoundedCornerShape(8.dp), focusedScale = 1.03f)
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            onFocusPage()
                                            onFocusId(item.first)
                                        }
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

@Composable
private fun Poster(url: String?, title: String, modifier: Modifier = Modifier) {
    PosterImage(
        url = url,
        contentDescription = title,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(8.dp))
    )
}
