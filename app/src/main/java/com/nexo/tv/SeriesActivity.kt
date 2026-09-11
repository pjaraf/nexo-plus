package com.nexo.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.nexo.tv.ui.CinematicBackdrop
import com.nexo.tv.ui.PosterImage
import com.nexo.tv.data.BackdropCache
import com.nexo.tv.data.ContinueWatching
import com.nexo.tv.data.SeriesDetailInfo
import com.nexo.tv.data.SeriesEpisode
import com.nexo.tv.data.SeriesItem
import com.nexo.tv.data.XtreamClient
import com.nexo.tv.player.IjkEngine
import com.nexo.tv.player.IjkVideoLayout
import com.nexo.tv.player.StreamBridge
import com.nexo.tv.ui.ResumePrompt
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Detalle de serie + preview VLC. "Pantalla completa" expande el mismo motor
 * (sin segunda reproducción).
 */
class SeriesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        keepAwakeWhileVisible()
        intent.getStringExtra(EXTRA_USER)?.let { Session.username = it }
        intent.getStringExtra(EXTRA_PASS)?.let { Session.password = it }
        intent.getStringExtra(EXTRA_SERVER)?.let { if (it.isNotBlank()) Session.server = it }

        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        val seriesName = intent.getStringExtra(EXTRA_SERIES_NAME).orEmpty()
        val seriesCoverExtra = intent.getStringExtra(EXTRA_SERIES_COVER).orEmpty()
        val categoryIdExtra = intent.getStringExtra(EXTRA_CATEGORY_ID).orEmpty()
        val resumeEpisodeId = intent.getStringExtra(EXTRA_RESUME_EPISODE_ID).orEmpty()
        val resumeFromIntent = intent.getLongExtra(EXTRA_RESUME_MS, -1L)

        StreamBridge.start()
        val engine = IjkEngine(this)

        setContent {
            var loading by remember { mutableStateOf(true) }
            var info by remember { mutableStateOf<SeriesDetailInfo?>(null) }
            var seasons by remember { mutableStateOf<Map<String, List<SeriesEpisode>>>(emptyMap()) }
            var selectedSeason by remember { mutableStateOf<String?>(null) }
            var selectedEpisode by remember { mutableStateOf<SeriesEpisode?>(null) }
            var recommended by remember { mutableStateOf<List<SeriesItem>>(emptyList()) }
            var error by remember { mutableStateOf<String?>(null) }
            var fullScreen by remember { mutableStateOf(false) }
            var playing by remember { mutableStateOf(true) }
            var position by remember { mutableLongStateOf(0L) }
            var duration by remember { mutableLongStateOf(0L) }
            var toast by remember { mutableStateOf<String?>(null) }
            var nextEpisodeMsg by remember { mutableStateOf(false) }
            var hudVisible by remember { mutableStateOf(true) }
            var hudTick by remember { mutableIntStateOf(0) }
            var slotX by remember { mutableIntStateOf(0) }
            var slotY by remember { mutableIntStateOf(0) }
            var slotW by remember { mutableIntStateOf(0) }
            var slotH by remember { mutableIntStateOf(0) }
            var pendingResume by remember { mutableLongStateOf(0L) }
            var autoNextArmed by remember { mutableStateOf(true) }
            var showResumePrompt by remember { mutableStateOf(false) }
            var resumeChoiceMs by remember { mutableLongStateOf(0L) }
            var expandAfterChoice by remember { mutableStateOf(false) }
            var resumeResolved by remember { mutableStateOf(false) }
            val promptShowing = rememberUpdatedState(showResumePrompt)
            val rootFocus = remember { FocusRequester() }
            val playFocus = remember { FocusRequester() }
            val density = LocalDensity.current

            fun bumpHud() {
                hudVisible = true
                hudTick++
            }

            fun persistProgress() {
                val ep = selectedEpisode ?: return
                if (seriesId.isBlank()) return
                ContinueWatching.save(
                    this@SeriesActivity,
                    ContinueWatching.Item(
                        kind = "series",
                        id = seriesId,
                        title = (info?.displayTitle?.takeIf { it.isNotBlank() } ?: seriesName)
                            .ifBlank { "Serie" },
                        poster = (info?.posterUrl ?: seriesCoverExtra).ifBlank { null },
                        positionMs = engine.timeMs(),
                        durationMs = engine.lengthMs().coerceAtLeast(duration),
                        episodeId = ep.id,
                        episodeExt = ep.ext,
                        season = ep.season,
                        episodeNum = ep.episodeNum,
                        categoryId = categoryIdExtra.ifBlank { null }
                    )
                )
            }

            fun playEpisode(ep: SeriesEpisode, expand: Boolean = false, resumeMs: Long = 0L) {
                selectedEpisode = ep
                selectedSeason = ep.season
                autoNextArmed = true
                pendingResume = resumeMs
                val url = StreamBridge.maybeWrap(XtreamClient.seriesUrl(ep.id, ep.ext))
                engine.playVod(url)
                playing = true
                if (expand) fullScreen = true
            }

            fun applyResumeChoice(continueWatching: Boolean) {
                showResumePrompt = false
                resumeResolved = true
                val seek = if (continueWatching) resumeChoiceMs else 0L
                resumeChoiceMs = 0L
                val expand = expandAfterChoice
                expandAfterChoice = false
                val ep = selectedEpisode ?: return
                playEpisode(ep, expand = expand, resumeMs = seek)
            }

            fun requestFullscreen() {
                if (showResumePrompt) return
                val saved = ContinueWatching.get(this@SeriesActivity, "series", seriesId)
                val ep = selectedEpisode
                val atStart = engine.timeMs() < 15_000L
                if (
                    !resumeResolved &&
                    ep != null &&
                    saved != null &&
                    saved.episodeId == ep.id &&
                    saved.positionMs > 20_000L &&
                    atStart
                ) {
                    resumeChoiceMs = saved.positionMs
                    expandAfterChoice = true
                    showResumePrompt = true
                    engine.pause()
                    playing = false
                } else {
                    fullScreen = true
                    bumpHud()
                }
            }

            fun nextEpisode(): SeriesEpisode? {
                val keys = seasons.keys.toList()
                val season = selectedSeason ?: return null
                val eps = seasons[season].orEmpty()
                val idx = eps.indexOfFirst { it.id == selectedEpisode?.id }
                if (idx >= 0 && idx + 1 < eps.size) return eps[idx + 1]
                val sIdx = keys.indexOf(season)
                if (sIdx >= 0 && sIdx + 1 < keys.size) {
                    return seasons[keys[sIdx + 1]]?.firstOrNull()
                }
                return null
            }

            fun goNextEpisode() {
                if (!autoNextArmed) return
                val next = nextEpisode() ?: return
                autoNextArmed = false
                nextEpisodeMsg = true
                toast = "Cargando siguiente episodio…"
                playEpisode(next, expand = true)
            }

            val goNextRef = rememberUpdatedState(newValue = { goNextEpisode() })

            fun openRelated(item: SeriesItem) {
                persistProgress()
                startActivity(
                    Intent(this@SeriesActivity, SeriesActivity::class.java)
                        .putExtra(EXTRA_SERIES_ID, item.id)
                        .putExtra(EXTRA_SERIES_NAME, item.name)
                        .putExtra(EXTRA_SERIES_COVER, item.cover.orEmpty())
                        .putExtra(EXTRA_CATEGORY_ID, item.categoryId.orEmpty())
                        .putExtra(EXTRA_USER, Session.username)
                        .putExtra(EXTRA_PASS, Session.password)
                        .putExtra(EXTRA_SERVER, Session.server)
                )
                finish()
            }

            DisposableEffect(engine) {
                engine.onPlaying = {
                    playing = true
                    duration = engine.lengthMs()
                    nextEpisodeMsg = false
                    if (promptShowing.value) {
                        engine.pause()
                        playing = false
                    } else if (pendingResume > 0L) {
                        val seek = pendingResume
                        pendingResume = 0L
                        engine.seekTo(seek)
                    }
                }
                engine.onEnded = { goNextRef.value.invoke() }
                onDispose {
                    persistProgress()
                    engine.release()
                }
            }

            LaunchedEffect(seriesId) {
                loading = true
                error = null
                nextEpisodeMsg = false
                val detail = XtreamClient.seriesDetail(seriesId)
                info = detail.info
                seasons = detail.episodes
                val saved = ContinueWatching.get(this@SeriesActivity, "series", seriesId)
                val wantEpId = resumeEpisodeId.ifBlank { saved?.episodeId.orEmpty() }
                val wantResume = when {
                    resumeFromIntent > 0L -> resumeFromIntent
                    !saved?.episodeId.isNullOrBlank() && saved?.episodeId == wantEpId ->
                        saved?.positionMs ?: 0L
                    else -> 0L
                }

                var startEp: SeriesEpisode? = null
                if (wantEpId.isNotBlank()) {
                    for ((season, eps) in detail.episodes) {
                        val found = eps.firstOrNull { it.id == wantEpId }
                        if (found != null) {
                            selectedSeason = season
                            startEp = found
                            break
                        }
                    }
                }
                if (startEp == null) {
                    val first = detail.episodes.keys.firstOrNull()
                    selectedSeason = first
                    startEp = first?.let { detail.episodes[it]?.firstOrNull() }
                }
                selectedEpisode = startEp
                if (detail.episodes.isEmpty()) {
                    error = "No se encontraron episodios"
                } else if (startEp != null) {
                    if (wantResume > 20_000L) {
                        resumeChoiceMs = wantResume
                        expandAfterChoice = resumeFromIntent > 0L
                        showResumePrompt = true
                        // Esperar elección antes de reproducir.
                    } else {
                        resumeResolved = true
                        playEpisode(startEp, expand = false, resumeMs = wantResume)
                    }
                }

                val all = runCatching { XtreamClient.series() }.getOrDefault(emptyList())
                    .filter { it.id.isNotBlank() && it.id != seriesId }
                val genreTokens = detail.info?.genre.orEmpty()
                    .lowercase()
                    .split(',', '|', '/', ';')
                    .map { it.trim() }
                    .filter { it.length > 2 }
                val byCategory = if (categoryIdExtra.isNotBlank()) {
                    all.filter { it.categoryId == categoryIdExtra }
                } else emptyList()
                val byGenre = if (genreTokens.isNotEmpty()) {
                    all.filter { s ->
                        val g = (s.genre ?: "").lowercase()
                        val n = s.name.lowercase()
                        genreTokens.any { t -> g.contains(t) || n.contains(t) }
                    }
                } else emptyList()
                recommended = (byCategory + byGenre + all)
                    .distinctBy { it.id }
                    .sortedWith(
                        compareByDescending<SeriesItem> { it.ratingValue }
                            .thenByDescending { it.addedEpoch }
                    )
                    .take(24)

                loading = false
            }

            val episodes = selectedSeason?.let { seasons[it] }.orEmpty()
            LaunchedEffect(selectedSeason, episodes) {
                if (selectedEpisode == null || episodes.none { it.id == selectedEpisode?.id }) {
                    episodes.firstOrNull()?.let { playEpisode(it) }
                }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    position = engine.timeMs()
                    val len = engine.lengthMs()
                    if (len > 0) duration = len
                    playing = engine.isPlaying
                    // Backup si EndReached no dispara
                    if (autoNextArmed && len > 30_000L && position >= len - 1_200L && position > 0L) {
                        goNextEpisode()
                    }
                    delay(500)
                }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    delay(5_000)
                    persistProgress()
                }
            }

            LaunchedEffect(hudTick, fullScreen) {
                if (!fullScreen || !hudVisible) return@LaunchedEffect
                delay(8000)
                hudVisible = false
            }

            LaunchedEffect(toast) {
                if (toast == null) return@LaunchedEffect
                delay(if (nextEpisodeMsg) 3500 else 1800)
                toast = null
            }

            LaunchedEffect(fullScreen, hudVisible, showResumePrompt) {
                if (!fullScreen || showResumePrompt) return@LaunchedEffect
                delay(80)
                if (hudVisible) {
                    runCatching { playFocus.requestFocus() }
                } else {
                    runCatching { rootFocus.requestFocus() }
                }
            }

            BackHandler {
                when {
                    showResumePrompt -> {
                        showResumePrompt = false
                        resumeChoiceMs = 0L
                        expandAfterChoice = false
                        pendingResume = 0L
                    }
                    fullScreen -> fullScreen = false
                    else -> {
                        persistProgress()
                        finish()
                    }
                }
            }

            val title = info?.displayTitle?.takeIf { it.isNotBlank() } ?: seriesName
            val cover = info?.posterUrl?.takeIf { it.isNotBlank() } ?: seriesCoverExtra
            val backdrop = info?.fanartUrl ?: BackdropCache.cachedSeriesFanart(seriesId)
            val castText = info?.cast?.takeIf { it.isNotBlank() } ?: "—"
            val plotText = info?.displayPlot
                ?: "Disfruta de todos los episodios en alta definición."
            val dateLine = buildString {
                val d = info?.displayDate.orEmpty()
                if (d.isNotBlank()) append(d).append(" | ")
                append(title)
            }
            val rating = info?.ratingBadge.orEmpty()
            val epTag = selectedEpisode?.let { "T${selectedSeason ?: it.season} - E${it.episodeNum}" } ?: ""
            val epRange = if (episodes.isNotEmpty()) {
                val nums = episodes.map { it.episodeNum }.filter { it > 0 }
                if (nums.isNotEmpty()) "${nums.min()}-${nums.max()}" else "1-${episodes.size}"
            } else ""

            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D0E15))
                    .focusRequester(rootFocus)
                    .focusProperties { canFocus = fullScreen && !hudVisible && !showResumePrompt }
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        if (!fullScreen || showResumePrompt) return@onPreviewKeyEvent false
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (e.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                        val code = e.nativeKeyEvent.keyCode
                        if (hudVisible) {
                            when (code) {
                                AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                    engine.togglePause(); playing = engine.isPlaying; bumpHud(); true
                                }
                                AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> {
                                    engine.resume(); playing = true; bumpHud(); true
                                }
                                AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                    engine.pause(); playing = false; bumpHud(); true
                                }
                                AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                    engine.seekBy(10_000); bumpHud(); true
                                }
                                AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                                    engine.seekBy(-10_000); bumpHud(); true
                                }
                                else -> false
                            }
                        } else {
                            when (code) {
                                AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                                AndroidKeyEvent.KEYCODE_ENTER,
                                AndroidKeyEvent.KEYCODE_DPAD_UP,
                                AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                                AndroidKeyEvent.KEYCODE_INFO,
                                AndroidKeyEvent.KEYCODE_MENU,
                                AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                    bumpHud(); true
                                }
                                AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                                AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                                    engine.seekBy(-10_000); bumpHud(); true
                                }
                                AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
                                AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                    engine.seekBy(10_000); bumpHud(); true
                                }
                                else -> false
                            }
                        }
                    }
            ) {
                // Un solo surface IjkPlayer: miniatura o pantalla completa (misma instancia)
                AndroidView(
                    factory = { ctx ->
                        IjkVideoLayout(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            keepScreenOn = true
                            isFocusable = false
                            isFocusableInTouchMode = false
                            engine.attach(this)
                        }
                    },
                    modifier = if (fullScreen) {
                        Modifier
                            .fillMaxSize()
                            .zIndex(0f)
                    } else if (slotW > 0 && slotH > 0) {
                        Modifier
                            .zIndex(3f)
                            .offset { IntOffset(slotX, slotY) }
                            .width(with(density) { slotW.toDp() })
                            .height(with(density) { slotH.toDp() })
                            .clip(RoundedCornerShape(10.dp))
                    } else {
                        Modifier
                            .size(1.dp)
                            .zIndex(0f)
                    }
                )

                if (!fullScreen) {
                    CinematicBackdrop(
                        url = backdrop,
                        modifier = Modifier.fillMaxSize().zIndex(0f)
                    )

                    when {
                        loading -> Box(
                            Modifier.fillMaxSize().zIndex(2f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = SeriesBlue)
                        }
                        error != null -> Box(
                            Modifier.fillMaxSize().zIndex(2f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(error!!, color = Color.White, fontSize = 16.sp)
                        }
                        else -> Column(
                            Modifier
                                .fillMaxSize()
                                .zIndex(2f)
                                .padding(horizontal = 22.dp, vertical = 10.dp)
                        ) {
                            // Carátula izq. | info | mini player 16:9
                            Row(
                                Modifier
                                    .weight(1.05f, fill = true)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                PosterImage(
                                    url = cover,
                                    contentDescription = title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(108.dp)
                                        .height(156.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF222222))
                                )

                                Column(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    verticalArrangement = Arrangement.Top
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            title,
                                            color = Color.White,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Black,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (rating.isNotBlank()) {
                                            Box(
                                                Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(SeriesRating)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    rating,
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Black
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        dateLine,
                                        color = Color.White.copy(alpha = 0.65f),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (epTag.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            epTag,
                                            color = SeriesAmber,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row {
                                        Text(
                                            "Actores: ",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            castText,
                                            color = Color.White.copy(alpha = 0.8f),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Row {
                                        Text(
                                            "Sinopsis: ",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            plotText,
                                            color = Color.White.copy(alpha = 0.8f),
                                            fontSize = 11.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        SeriesActionButton("Pantalla completa", Icons.Filled.Tv, true) {
                                            requestFullscreen()
                                        }
                                        SeriesActionButton("Idioma y subtítulos", Icons.Filled.Subtitles, false) {
                                            toast = engine.cycleAudioTrack()
                                                ?: engine.cycleSubtitleTrack()
                                                ?: "Sin pistas"
                                        }
                                    }
                                }

                                Box(
                                    Modifier
                                        .width(360.dp)
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.Black)
                                        .onGloballyPositioned { coords ->
                                            val pos = coords.positionInRoot()
                                            slotX = pos.x.roundToInt()
                                            slotY = pos.y.roundToInt()
                                            slotW = coords.size.width
                                            slotH = coords.size.height
                                        }
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(seasons.keys.toList(), key = { it }) { season ->
                                        val selected = season == selectedSeason
                                        var focused by remember { mutableStateOf(false) }
                                        Box(
                                            Modifier
                                                .onFocusChanged { focused = it.isFocused }
                                                .clip(RoundedCornerShape(18.dp))
                                                .background(
                                                    when {
                                                        focused || selected -> SeriesBlue
                                                        else -> Color.White.copy(alpha = 0.12f)
                                                    }
                                                )
                                                .border(
                                                    BorderStroke(
                                                        if (focused) 2.dp else 1.dp,
                                                        Color.White.copy(alpha = if (focused) 0.95f else 0.2f)
                                                    ),
                                                    RoundedCornerShape(18.dp)
                                                )
                                                .clickable { selectedSeason = season }
                                                .focusable()
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                "Temporada $season",
                                                color = Color.White,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                                if (epRange.isNotBlank()) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(Color.White.copy(alpha = 0.12f))
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Text(epRange, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
                                    }
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(bottom = 2.dp)
                            ) {
                                items(episodes, key = { it.id }) { ep ->
                                    val selected = ep.id == selectedEpisode?.id
                                    var focused by remember { mutableStateOf(false) }
                                    Box(
                                        Modifier
                                            .size(40.dp)
                                            .onFocusChanged { focused = it.isFocused }
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                when {
                                                    focused || selected -> SeriesBlue
                                                    else -> Color.White.copy(alpha = 0.10f)
                                                }
                                            )
                                            .border(
                                                BorderStroke(
                                                    if (focused || selected) 2.dp else 1.dp,
                                                    Color.White.copy(alpha = if (focused || selected) 0.9f else 0.22f)
                                                ),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable { playEpisode(ep) }
                                            .focusable(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (selected) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.Filled.PlayArrow,
                                                    null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Text(
                                                    "${ep.episodeNum}",
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        } else {
                                            Text(
                                                "${ep.episodeNum}",
                                                color = Color.White,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                            }

                            if (recommended.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Recomendadas",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(6.dp))
                                BoxWithConstraints(
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(1f, fill = true)
                                ) {
                                    val gap = 10.dp
                                    val visible = 6
                                    val widthForPosters = (maxWidth - gap * (visible - 1)) / visible
                                    val posterW = minOf(widthForPosters, maxHeight * 2f / 3f)
                                    val posterH = posterW * 1.5f
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(gap),
                                        contentPadding = PaddingValues(bottom = 2.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        items(recommended, key = { it.id }) { item ->
                                            var focused by remember(item.id) { mutableStateOf(false) }
                                            PosterImage(
                                                url = item.cover,
                                                contentDescription = item.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .width(posterW)
                                                    .height(posterH)
                                                    .onFocusChanged { focused = it.isFocused }
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .border(
                                                        BorderStroke(
                                                            if (focused) 2.dp else 0.dp,
                                                            if (focused) SeriesBlue else Color.Transparent
                                                        ),
                                                        RoundedCornerShape(8.dp)
                                                    )
                                                    .background(Color(0xFF222222))
                                                    .clickable { openRelated(item) }
                                                    .focusable()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (hudVisible) {
                    Dialog(
                        onDismissRequest = { hudVisible = false },
                        properties = DialogProperties(
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                            usePlatformDefaultWidth = false,
                            decorFitsSystemWindows = false
                        )
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Transparent)
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    if (e.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                                    when (e.nativeKeyEvent.keyCode) {
                                        AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                            engine.togglePause(); playing = engine.isPlaying; bumpHud(); true
                                        }
                                        AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                            engine.seekBy(10_000); bumpHud(); true
                                        }
                                        AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                                            engine.seekBy(-10_000); bumpHud(); true
                                        }
                                        else -> false
                                    }
                                }
                        ) {
                            Row(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color.Transparent, Color(0xE6000000))
                                        )
                                    )
                                    .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                PosterImage(
                                    url = cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(52.dp)
                                        .height(78.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "$title · $epTag",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    val progress = if (duration > 0) {
                                        (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                                    } else 0f
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = Color(0xFFDE5B17),
                                        trackColor = Color.White.copy(alpha = 0.22f)
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(formatSeriesTime(position), color = Color.White, fontSize = 11.sp)
                                        Text(
                                            formatSeriesTime(duration),
                                            color = Color.White.copy(alpha = 0.65f),
                                            fontSize = 11.sp
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        MiniHudBtn(Icons.Filled.Replay10, "−10s", onFocused = { bumpHud() }) {
                                            engine.seekBy(-10_000); bumpHud()
                                        }
                                        MiniHudBtn(
                                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                            if (playing) "Pausa" else "Play",
                                            playFocus,
                                            onFocused = { bumpHud() }
                                        ) {
                                            engine.togglePause()
                                            playing = engine.isPlaying
                                            bumpHud()
                                        }
                                        MiniHudBtn(Icons.Filled.Forward10, "+10s", onFocused = { bumpHud() }) {
                                            engine.seekBy(10_000); bumpHud()
                                        }
                                        MiniHudBtn(Icons.Filled.Translate, "Audio", onFocused = { bumpHud() }) {
                                            toast = engine.cycleAudioTrack() ?: "Sin audio"
                                            bumpHud()
                                        }
                                        MiniHudBtn(Icons.Filled.Subtitles, "Subs", onFocused = { bumpHud() }) {
                                            toast = engine.cycleSubtitleTrack()
                                            bumpHud()
                                        }
                                        MiniHudBtn(Icons.Filled.AspectRatio, "Pantalla", onFocused = { bumpHud() }) {
                                            toast = engine.cycleAspectMode()
                                            bumpHud()
                                        }
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
                }

                if (!fullScreen) {
                    toast?.let { msg ->
                        Text(
                            msg,
                            color = Color.White,
                            fontSize = if (nextEpisodeMsg) 20.sp else 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .zIndex(5f)
                                .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                        )
                    }
                }

                if (showResumePrompt) {
                    ResumePrompt(
                        title = "¿Seguir viendo?",
                        subtitle = selectedEpisode?.let { "T${it.season} · E${it.episodeNum}" },
                        onContinue = { applyResumeChoice(continueWatching = true) },
                        onFromStart = { applyResumeChoice(continueWatching = false) }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_SERIES_NAME = "series_name"
        const val EXTRA_SERIES_COVER = "series_cover"
        const val EXTRA_CATEGORY_ID = "category_id"
        const val EXTRA_RESUME_EPISODE_ID = "resume_episode_id"
        const val EXTRA_RESUME_MS = "resume_ms"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_SERVER = "server"
    }
}

private val SeriesBlue = Color(0xFF007AFF)
private val SeriesAmber = Color(0xFFFFC107)
private val SeriesRating = Color(0xFF00B0FF)

@Composable
private fun SeriesActionButton(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> SeriesBlue
        primary -> SeriesAmber
        else -> Color.White.copy(alpha = 0.12f)
    }
    val fg = if (!focused && primary) Color.Black else Color.White
    Row(
        Modifier
            .height(42.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(
                BorderStroke(
                    if (focused) 2.dp else 1.dp,
                    if (focused) Color.White else Color.White.copy(alpha = 0.28f)
                ),
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MiniHudBtn(
    icon: ImageVector,
    label: String,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) onFocused?.invoke()
                }
                .clip(CircleShape)
                .background(if (isFocused) Color(0xFFDE5B17) else Color.White.copy(alpha = 0.16f))
                .clickable(onClick = onClick)
                .focusable(),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = if (isFocused) Color(0xFFFF6A1A) else Color.White.copy(alpha = 0.8f),
            fontSize = 10.sp,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal
        )
    }
}

private fun formatSeriesTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
