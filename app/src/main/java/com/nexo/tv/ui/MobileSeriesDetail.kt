package com.nexo.tv.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nexo.tv.data.Favorites
import com.nexo.tv.data.SeriesEpisode
import com.nexo.tv.data.SeriesItem
import com.nexo.tv.player.VlcEngine
import com.nexo.tv.player.VlcVideoLayout

private val AccentRed = Color(0xFFE53935)
private val AccentOrange = Color(0xFFDE5B17)
private val MetaGray = Color(0xFFB0B0B0)
private val EpIdle = Color(0xFF2A2A2A)

/** Detalle de serie para telefono/tablet. No se usa en TV Box. */
@Composable
fun MobileSeriesDetailScreen(
    engine: VlcEngine,
    title: String,
    coverUrl: String?,
    yearGenreLine: String,
    plot: String,
    loading: Boolean,
    error: String?,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    fullScreen: Boolean,
    seriesId: String,
    seasonKeys: List<String>,
    selectedSeason: String?,
    episodes: List<SeriesEpisode>,
    selectedEpisode: SeriesEpisode?,
    episodeTag: String,
    recommended: List<SeriesItem>,
    toast: String?,
    nextEpisodeMsg: Boolean,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onRequestFullscreen: () -> Unit,
    onExitFullscreen: () -> Unit,
    onSelectSeason: (String) -> Unit,
    onSelectEpisode: (SeriesEpisode) -> Unit,
    onOpenRelated: (SeriesItem) -> Unit,
    onClearToast: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onAudio: () -> Unit,
    onSubs: () -> Unit,
    onAspect: () -> Unit,
    resumeOverlay: (@Composable () -> Unit)? = null
) {
    val ctx = LocalContext.current
    var favorite by remember(seriesId) {
        mutableStateOf(Favorites.isSeriesFavorite(ctx, seriesId))
    }
    var showControls by remember { mutableStateOf(false) }
    var hudTick by remember { mutableIntStateOf(0) }

    fun bumpHud() {
        showControls = true
        hudTick++
    }

    LaunchedEffect(hudTick, fullScreen, playing, showControls) {
        if (!fullScreen || !showControls) return@LaunchedEffect
        kotlinx.coroutines.delay(8000)
        showControls = false
    }

    LaunchedEffect(fullScreen) {
        (ctx as? Activity)?.setPhoneTabletPlayerFullscreen(fullScreen)
    }

    LaunchedEffect(toast) {
        if (toast == null) return@LaunchedEffect
        kotlinx.coroutines.delay(if (nextEpisodeMsg) 3500 else 1800)
        onClearToast()
    }

    BackHandler(enabled = fullScreen) {
        onExitFullscreen()
        (ctx as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun shareSeries() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, title)
        }
        ctx.startActivity(Intent.createChooser(send, "Compartir"))
    }

    fun enterFullscreen() {
        bumpHud()
        (ctx as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onRequestFullscreen()
    }

    fun exitFullscreen() {
        onExitFullscreen()
        (ctx as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .navigationBarsPadding()
    ) {
        // Un solo surface VLC: no se recrea al entrar/salir de fullscreen (evita reiniciar).
        MobileSeriesPlayer(
            engine = engine,
            modifier = if (fullScreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            }
        )

        if (fullScreen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable { bumpHud() }
            ) {
                if (showControls) {
                    MobileSeriesFullscreenHud(
                        title = title,
                        episodeTag = episodeTag,
                        coverUrl = coverUrl,
                        playing = playing,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        toast = toast,
                        nextEpisodeMsg = nextEpisodeMsg,
                        onExit = { exitFullscreen() },
                        onSeekBy = {
                            onSeekBy(it)
                            bumpHud()
                        },
                        onTogglePlay = {
                            onTogglePlay()
                            bumpHud()
                        },
                        onSeekTap = { ms ->
                            onSeekTo(ms)
                            bumpHud()
                        },
                        onAudio = {
                            onAudio()
                            bumpHud()
                        },
                        onSubs = {
                            onSubs()
                            bumpHud()
                        },
                        onAspect = {
                            onAspect()
                            bumpHud()
                        }
                    )
                } else {
                    toast?.let { msg ->
                        Text(
                            msg,
                            color = Color.White,
                            fontSize = if (nextEpisodeMsg) 20.sp else 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clickable { showControls = !showControls }
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            color = AccentRed,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(36.dp)
                        )
                    }
                    if (!showControls) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Atras",
                                tint = Color.White
                            )
                        }
                        IconButton(
                            onClick = { enterFullscreen() },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Fullscreen,
                                contentDescription = "Pantalla completa",
                                tint = Color.White
                            )
                        }
                    } else {
                        MobileSeriesEmbeddedChrome(
                            playing = playing,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            onBack = onBack,
                            onSeekTo = onSeekTo,
                            onFullscreen = { enterFullscreen() }
                        )
                    }
                }

                if (error != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(error, color = Color.White)
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Text(
                            title,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 26.sp
                        )
                        if (yearGenreLine.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(yearGenreLine, color = MetaGray, fontSize = 13.sp)
                        }

                        if (seasonKeys.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            if (seasonKeys.size > 1) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(seasonKeys, key = { it }) { season ->
                                        val selected = season == selectedSeason
                                        Text(
                                            "Temporada $season",
                                            color = if (selected) Color.White else MetaGray,
                                            fontSize = 15.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { onSelectSeason(season) }
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    "Temporada ${selectedSeason ?: seasonKeys.first()}",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(end = 8.dp)
                            ) {
                                items(episodes, key = { it.id }) { ep ->
                                    val selected = ep.id == selectedEpisode?.id
                                    Box(
                                        Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (selected) AccentOrange else EpIdle)
                                            .clickable { onSelectEpisode(ep) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (selected) {
                                            Icon(
                                                Icons.Filled.PlayArrow,
                                                contentDescription = "Episodio ${ep.episodeNum}",
                                                tint = Color.White,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        } else {
                                            Text(
                                                if (ep.episodeNum > 0) ep.episodeNum.toString() else "·",
                                                color = Color.White,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(plot, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                        Spacer(Modifier.height(18.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { shareSeries() }
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = "Compartir",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text("Compartir", color = Color.White, fontSize = 12.sp)
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    favorite = Favorites.toggleSeries(ctx, seriesId)
                                }
                            ) {
                                Icon(
                                    if (favorite) Icons.Default.Star else Icons.Outlined.StarOutline,
                                    contentDescription = "Favoritos",
                                    tint = if (favorite) AccentRed else Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text("Favoritos", color = Color.White, fontSize = 12.sp)
                            }
                        }

                        if (recommended.isNotEmpty()) {
                            Spacer(Modifier.height(22.dp))
                            Text(
                                "También podría gustarte",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(12.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(end = 8.dp)
                            ) {
                                items(recommended, key = { it.id }) { item ->
                                    PosterImage(
                                        url = item.cover,
                                        contentDescription = item.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .width(110.dp)
                                            .aspectRatio(2f / 3f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF1A1A1A))
                                            .clickable { onOpenRelated(item) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }

        resumeOverlay?.invoke()
    }
}

/** HUD igual al de TV Box en series: poster + titulo · episodio + barra + controles. */
@Composable
private fun MobileSeriesFullscreenHud(
    title: String,
    episodeTag: String,
    coverUrl: String?,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    toast: String?,
    nextEpisodeMsg: Boolean,
    onExit: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onSeekTap: (Long) -> Unit,
    onAudio: () -> Unit,
    onSubs: () -> Unit,
    onAspect: () -> Unit
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val line = if (episodeTag.isNotBlank()) "$title · $episodeTag" else title

    Box(Modifier.fillMaxSize()) {
        IconButton(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Salir", tint = Color.White)
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xE6000000))
                    )
                )
                .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PosterImage(
                url = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(52.dp)
                    .height(78.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
            Column(Modifier.weight(1f)) {
                Text(
                    line,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .pointerInput(durationMs) {
                            detectTapGestures { offset ->
                                if (durationMs <= 0L) return@detectTapGestures
                                val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                onSeekTap((durationMs * frac).toLong())
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = AccentOrange,
                        trackColor = Color.White.copy(alpha = 0.22f)
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatSeriesMs(positionMs), color = Color.White, fontSize = 11.sp)
                    Text(
                        formatSeriesMs(durationMs),
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MobileSeriesHudBtn(Icons.Filled.Replay10, "-10s") { onSeekBy(-10_000) }
                    MobileSeriesHudBtn(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (playing) "Pausa" else "Play"
                    ) { onTogglePlay() }
                    MobileSeriesHudBtn(Icons.Filled.Forward10, "+10s") { onSeekBy(10_000) }
                    MobileSeriesHudBtn(Icons.Filled.Translate, "Audio", onAudio)
                    MobileSeriesHudBtn(Icons.Filled.Subtitles, "Subs", onSubs)
                    MobileSeriesHudBtn(Icons.Filled.AspectRatio, "Pantalla", onAspect)
                }
            }
        }

        toast?.let { msg ->
            Text(
                msg,
                color = Color.White,
                fontSize = if (nextEpisodeMsg) 20.sp else 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun MobileSeriesHudBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
    }
}

@Composable
private fun MobileSeriesEmbeddedChrome(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    onBack: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onFullscreen: () -> Unit
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atras", tint = Color.White)
            }
            IconButton(onClick = onFullscreen) {
                Icon(Icons.Default.Fullscreen, contentDescription = "Pantalla completa", tint = Color.White)
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .pointerInput(durationMs) {
                        detectTapGestures { offset ->
                            if (durationMs <= 0L) return@detectTapGestures
                            val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onSeekTo((durationMs * frac).toLong())
                        }
                    }
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.28f))
                )
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceAtLeast(0.001f))
                        .height(3.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AccentRed)
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatSeriesMs(positionMs), color = Color.White, fontSize = 12.sp)
                Text(formatSeriesMs(durationMs), color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MobileSeriesPlayer(engine: VlcEngine, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            VlcVideoLayout(ctx).also { layout ->
                layout.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                layout.keepScreenOn = true
                engine.attach(layout)
            }
        },
        update = { engine.attach(it) },
        onRelease = { engine.onHostReleased(it) },
        modifier = modifier
    )
}

private fun formatSeriesMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
