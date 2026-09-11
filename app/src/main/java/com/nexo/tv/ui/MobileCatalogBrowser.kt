package com.nexo.tv.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.tv.AppExit
import com.nexo.tv.MovieActivity
import com.nexo.tv.SeriesActivity
import com.nexo.tv.Session
import com.nexo.tv.data.BackdropCache
import com.nexo.tv.data.Catalog
import com.nexo.tv.data.SeriesItem
import com.nexo.tv.data.VodItem
import com.nexo.tv.ui.PosterPreloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Orange = Color(0xFFDE5B17)

/** Catalogo movil/tablet de peliculas o series (no se usa en TV Box). */
@Composable
fun MobileCatalogBrowser(
    seriesMode: Boolean,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var loadingCatalog by remember { mutableStateOf(!Catalog.ready || (Catalog.movies.isEmpty() && Catalog.series.isEmpty())) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // LiveActivity corre en proceso :player — el Catalog del MainActivity no existe aqui.
    // Si LiveActivity ya disparo preloadAsync, solo esperamos ready (sin segunda descarga).
    LaunchedEffect(Unit) {
        if (Catalog.ready && (Catalog.movies.isNotEmpty() || Catalog.series.isNotEmpty())) {
            loadingCatalog = false
            return@LaunchedEffect
        }
        loadingCatalog = true
        loadError = null
        Catalog.preloadAsync(ctx.applicationContext)
        // Esperar a que el preload en background deje ready=true
        var spins = 0
        while (!Catalog.ready && spins < 120) {
            kotlinx.coroutines.delay(250)
            spins++
            if (Catalog.movies.isNotEmpty() || Catalog.series.isNotEmpty()) break
        }
        if (!Catalog.ready && Catalog.movies.isEmpty() && Catalog.series.isEmpty()) {
            // Fallback sync si el async no termino
            withContext(Dispatchers.IO) {
                runCatching { Catalog.preload(ctx.applicationContext) }
            }
        }
        loadingCatalog = false
        if (Catalog.movies.isEmpty() && Catalog.series.isEmpty()) {
            loadError = "No se pudo cargar el catálogo"
        }
        android.util.Log.i(
            "MobileCatalog",
            "loaded ready=${Catalog.ready} movies=${Catalog.movies.size} series=${Catalog.series.size}"
        )
    }

    val catalogGen by Catalog.generationFlow.collectAsState()
    LaunchedEffect(catalogGen) {
        if (Catalog.ready && (Catalog.movies.isNotEmpty() || Catalog.series.isNotEmpty())) {
            loadingCatalog = false
            loadError = null
        }
    }
    val movies = remember(catalogGen) { Catalog.movies }
    val series = remember(catalogGen) { Catalog.series }
    val movieCats = remember(catalogGen) { Catalog.movieCategories }
    val seriesCats = remember(catalogGen) { Catalog.seriesCategories }

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryId by remember { mutableStateOf("") }
    val cats = if (seriesMode) seriesCats else movieCats
    val listState = rememberLazyListState()

    val movieItems = remember(catalogGen, selectedCategoryId, searchQuery) {
        movies.filter { m ->
            val catOk = selectedCategoryId.isBlank() || m.categoryId == selectedCategoryId
            val qOk = searchQuery.isBlank() || m.displayName.contains(searchQuery, ignoreCase = true)
            catOk && qOk
        }
    }
    val seriesItems = remember(catalogGen, selectedCategoryId, searchQuery) {
        series.filter { s ->
            val catOk = selectedCategoryId.isBlank() || s.categoryId == selectedCategoryId
            val qOk = searchQuery.isBlank() || s.name.contains(searchQuery, ignoreCase = true)
            catOk && qOk
        }
    }
    val itemsCount = if (seriesMode) seriesItems.size else movieItems.size

    fun openMovie(item: VodItem) {
        AppExit.suppressHomeExit = true
        val neighborCovers = movieItems
            .asSequence()
            .filter { it.id != item.id }
            .mapNotNull { it.streamIcon?.trim()?.takeIf { u -> u.isNotEmpty() } }
            .take(24)
            .toList()
        PosterPreloader.warmPriorityAsync(
            ctx,
            listOfNotNull(item.streamIcon?.trim()?.takeIf { it.isNotEmpty() }) + neighborCovers
        )
        val fanart = BackdropCache.cachedMovieFanart(item.id).orEmpty()
        ctx.startActivity(
            Intent(ctx, MovieActivity::class.java)
                .putExtra(MovieActivity.EXTRA_MOVIE_ID, item.id)
                .putExtra(MovieActivity.EXTRA_MOVIE_NAME, item.displayName)
                .putExtra(MovieActivity.EXTRA_MOVIE_COVER, item.streamIcon.orEmpty())
                .putExtra(MovieActivity.EXTRA_MOVIE_FANART, fanart)
                .putExtra(MovieActivity.EXTRA_CATEGORY_ID, item.categoryId.orEmpty())
                .putExtra(MovieActivity.EXTRA_EXT, item.ext ?: "mp4")
                .putExtra(MovieActivity.EXTRA_USER, Session.username)
                .putExtra(MovieActivity.EXTRA_PASS, Session.password)
                .putExtra(MovieActivity.EXTRA_SERVER, Session.server)
        )
    }

    fun openSeries(item: SeriesItem) {
        AppExit.suppressHomeExit = true
        val neighborCovers = seriesItems
            .asSequence()
            .filter { it.id != item.id }
            .mapNotNull { it.cover?.trim()?.takeIf { u -> u.isNotEmpty() } }
            .take(24)
            .toList()
        PosterPreloader.warmPriorityAsync(
            ctx,
            listOfNotNull(item.cover?.trim()?.takeIf { it.isNotEmpty() }) + neighborCovers
        )
        val fanart = BackdropCache.cachedSeriesFanart(item.id).orEmpty()
        ctx.startActivity(
            Intent(ctx, SeriesActivity::class.java)
                .putExtra(SeriesActivity.EXTRA_SERIES_ID, item.id)
                .putExtra(SeriesActivity.EXTRA_SERIES_NAME, item.name)
                .putExtra(SeriesActivity.EXTRA_SERIES_COVER, item.cover.orEmpty())
                .putExtra(SeriesActivity.EXTRA_SERIES_FANART, fanart)
                .putExtra(SeriesActivity.EXTRA_CATEGORY_ID, item.categoryId.orEmpty())
                .putExtra(SeriesActivity.EXTRA_USER, Session.username)
                .putExtra(SeriesActivity.EXTRA_PASS, Session.password)
                .putExtra(SeriesActivity.EXTRA_SERVER, Session.server)
        )
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Color(0xFF131418))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Orange),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.LiveTv, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("NEXO", color = Orange, fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF22242B))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null, tint = Color(0xFF8E909B), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            if (seriesMode) "Buscar serie…" else "Buscar película…",
                            color = Color(0xFF7A7D87),
                            fontSize = 13.sp
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                        cursorBrush = SolidColor(Orange),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Borrar",
                        tint = Color(0xFF8E909B),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { searchQuery = "" }
                    )
                }
            }
        }

        Text(
            if (seriesMode) "Series" else "Películas",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )

        if (cats.isNotEmpty()) {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 10.dp)
            ) {
                item {
                    CategoryChip(
                        label = "Todas",
                        selected = selectedCategoryId.isBlank(),
                        onClick = { selectedCategoryId = "" }
                    )
                }
                items(cats, key = { it.categoryId }) { cat ->
                    CategoryChip(
                        label = cat.categoryName,
                        selected = selectedCategoryId == cat.categoryId,
                        onClick = { selectedCategoryId = cat.categoryId }
                    )
                }
            }
        }

        if (loadingCatalog) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Orange, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Cargando catalogo...", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
            }
        } else if (loadError != null && itemsCount == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(loadError ?: "Error", color = Color.White.copy(alpha = 0.7f))
            }
        } else if (itemsCount == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Sin resultados", color = Color.White.copy(alpha = 0.6f))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (seriesMode) {
                    items(seriesItems, key = { it.id }) { item ->
                        PosterCard(
                            title = item.name,
                            cover = item.cover,
                            onClick = { openSeries(item) }
                        )
                    }
                } else {
                    items(movieItems, key = { it.id }) { item ->
                        PosterCard(
                            title = item.displayName,
                            cover = item.streamIcon,
                            onClick = { openMovie(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.White else Color(0xFF8E909B),
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Orange else Color(0xFF22242B))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun PosterCard(title: String, cover: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1C1E26))
        ) {
            PosterImage(
                url = cover,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp
        )
    }
}
