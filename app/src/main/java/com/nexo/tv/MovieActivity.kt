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
import com.nexo.tv.ui.PagedSixPosterRow
import com.nexo.tv.ui.PosterImage
import com.nexo.tv.data.BackdropCache
import com.nexo.tv.ui.warmCinematicFanart
import com.nexo.tv.data.Catalog
import com.nexo.tv.data.ContinueWatching
import com.nexo.tv.data.SeriesDetailInfo
import com.nexo.tv.data.VodItem
import com.nexo.tv.data.XtreamClient
import com.nexo.tv.player.IjkEngine
import com.nexo.tv.player.IjkVideoLayout
import com.nexo.tv.player.StreamBridge
import com.nexo.tv.ui.ResumePrompt
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Detalle de película + preview VLC (misma UX que series).
 * "Pantalla completa" expande el mismo motor.
 */
class MovieActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        keepAwakeWhileVisible()
        intent.getStringExtra(EXTRA_USER)?.let { Session.username = it }
        intent.getStringExtra(EXTRA_PASS)?.let { Session.password = it }
        intent.getStringExtra(EXTRA_SERVER)?.let { if (it.isNotBlank()) Session.server = it }

        val movieId = intent.getStringExtra(EXTRA_MOVIE_ID).orEmpty()
        val movieName = intent.getStringExtra(EXTRA_MOVIE_NAME).orEmpty()
        val movieCoverExtra = intent.getStringExtra(EXTRA_MOVIE_COVER).orEmpty()
        val movieFanartExtra = intent.getStringExtra(EXTRA_MOVIE_FANART)?.trim().orEmpty()
        val categoryIdExtra = intent.getStringExtra(EXTRA_CATEGORY_ID).orEmpty()
        val extExtra = intent.getStringExtra(EXTRA_EXT)?.ifBlank { null } ?: "mp4"
        val resumeFromIntent = intent.getLongExtra(EXTRA_RESUME_MS, -1L)

        StreamBridge.start()
        val engine = IjkEngine(this)

        setContent {
            var loading by remember { mutableStateOf(true) }
            var info by remember { mutableStateOf<SeriesDetailInfo?>(null) }
            var containerExt by remember { mutableStateOf(extExtra) }
            var recommended by remember { mutableStateOf<List<VodItem>>(emptyList()) }
            var error by remember { mutableStateOf<String?>(null) }
            var fullScreen by remember { mutableStateOf(false) }
            var playing by remember { mutableStateOf(true) }
            var position by remember { mutableLongStateOf(0L) }
            var duration by remember { mutableLongStateOf(0L) }
            var toast by remember { mutableStateOf<String?>(null) }
            var hudVisible by remember { mutableStateOf(true) }
            var hudTick by remember { mutableIntStateOf(0) }
            var slotX by remember { mutableIntStateOf(0) }
            var slotY by remember { mutableIntStateOf(0) }
            var slotW by remember { mutableIntStateOf(0) }
            var slotH by remember { mutableIntStateOf(0) }
            var pendingResume by remember { mutableLongStateOf(0L) }
            var showResumePrompt by remember { mutableStateOf(false) }
            var resumeChoiceMs by remember { mutableLongStateOf(0L) }
            var expandAfterChoice by remember { mutableStateOf(false) }
            var resumeResolved by remember { mutableStateOf(false) }
            // Fanart de inmediato: nunca carátula mientras llega el fanart.
            var backdropUrl by remember {
                mutableStateOf(
                    BackdropCache.cachedMovieFanart(movieId)
                        ?: movieFanartExtra.takeIf { it.isNotBlank() }
                )
            }
            var backdropFromPoster by remember { mutableStateOf(false) }
            val promptShowing = rememberUpdatedState(showResumePrompt)
            val rootFocus = remember { FocusRequester() }
            val playFocus = remember { FocusRequester() }
            val density = LocalDensity.current

            fun bumpHud() {
                hudVisible = true
                hudTick++
            }

            fun persistProgress() {
                if (movieId.isBlank()) return
                ContinueWatching.save(
                    this@MovieActivity,
                    ContinueWatching.Item(
                        kind = "movie",
                        id = movieId,
                        title = (info?.displayTitle?.takeIf { it.isNotBlank() } ?: movieName)
                            .ifBlank { "Película" },
                        poster = (info?.posterUrl ?: movieCoverExtra).ifBlank { null },
                        positionMs = engine.timeMs(),
                        durationMs = engine.lengthMs().coerceAtLeast(duration),
                        url = XtreamClient.movieUrl(movieId, containerExt),
                        categoryId = categoryIdExtra.ifBlank { null }
                    )
                )
            }

            fun playMovie(resumeMs: Long = 0L, expand: Boolean = false) {
                pendingResume = resumeMs
                val url = StreamBridge.maybeWrap(XtreamClient.movieUrl(movieId, containerExt))
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
                playMovie(resumeMs = seek, expand = expand)
            }

            fun requestFullscreen() {
                if (showResumePrompt) return
                val saved = ContinueWatching.get(this@MovieActivity, "movie", movieId)
                val atStart = engine.timeMs() < 15_000L
                if (
                    !resumeResolved &&
                    saved != null &&
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

            fun openRelated(item: VodItem) {
                persistProgress()
                val fanart = BackdropCache.cachedMovieFanart(item.id).orEmpty()
                startActivity(
                    Intent(this@MovieActivity, MovieActivity::class.java)
                        .putExtra(EXTRA_MOVIE_ID, item.id)
                        .putExtra(EXTRA_MOVIE_NAME, item.displayName)
                        .putExtra(EXTRA_MOVIE_COVER, item.streamIcon.orEmpty())
                        .putExtra(EXTRA_MOVIE_FANART, fanart)
                        .putExtra(EXTRA_CATEGORY_ID, item.categoryId.orEmpty())
                        .putExtra(EXTRA_EXT, item.ext ?: "mp4")
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
                    if (promptShowing.value) {
                        engine.pause()
                        playing = false
                    } else if (pendingResume > 0L) {
                        val seek = pendingResume
                        pendingResume = 0L
                        engine.seekTo(seek)
                    }
                }
                onDispose {
                    persistProgress()
                    engine.release()
                }
            }

            LaunchedEffect(movieId) {
                backdropUrl?.let { warmCinematicFanart(this@MovieActivity, it, fromPoster = false) }
                if (backdropUrl == null && !BackdropCache.isMovieFanartMiss(movieId)) {
                    val url = BackdropCache.movieFanart(movieId)
                    if (url != null) {
                        backdropUrl = url
                        backdropFromPoster = false
                        warmCinematicFanart(this@MovieActivity, url, fromPoster = false)
                    }
                }
            }

            LaunchedEffect(movieId) {
                loading = true
                error = null
                if (movieId.isBlank()) {
                    error = "Película no válida"
                    loading = false
                    return@LaunchedEffect
                }
                val (detail, ext) = XtreamClient.movieDetail(movieId)
                info = detail
                val fanart = detail?.fanartUrl?.takeIf { it.isNotBlank() }
                if (fanart != null) {
                    BackdropCache.putMovieFanart(movieId, fanart)
                    backdropUrl = fanart
                    backdropFromPoster = false
                    warmCinematicFanart(this@MovieActivity, fanart, fromPoster = false)
                } else {
                    BackdropCache.markMovieFanartMiss(movieId)
                    if (backdropUrl == null) {
                        val coverBg = (detail?.posterUrl ?: movieCoverExtra).takeIf { it.isNotBlank() }
                        backdropUrl = coverBg
                        backdropFromPoster = coverBg != null
                        coverBg?.let { warmCinematicFanart(this@MovieActivity, it, fromPoster = true) }
                    }
                }
                containerExt = ext.ifBlank { extExtra }
                val saved = ContinueWatching.get(this@MovieActivity, "movie", movieId)
                val wantResume = when {
                    resumeFromIntent > 0L -> resumeFromIntent
                    saved != null && saved.positionMs > 0L -> saved.positionMs
                    else -> 0L
                }
                if (wantResume > 20_000L) {
                    resumeChoiceMs = wantResume
                    expandAfterChoice = false
                    showResumePrompt = true
                    // No reproducir hasta que el usuario elija (evita mini player negro).
                } else {
                    resumeResolved = true
                    playMovie(resumeMs = wantResume)
                }

                val all = Catalog.movies
                    .ifEmpty { runCatching { XtreamClient.movies() }.getOrDefault(emptyList()) }
                    .filter { it.id.isNotBlank() && it.id != movieId }
                val genreTokens = detail?.genre.orEmpty()
                    .lowercase()
                    .split(',', '|', '/', ';')
                    .map { it.trim() }
                    .filter { it.length > 2 }
                val cat = categoryIdExtra.ifBlank { null }
                val byCategory = if (!cat.isNullOrBlank()) {
                    all.filter { it.categoryId == cat }
                } else emptyList()
                val byGenre = if (genreTokens.isNotEmpty()) {
                    all.filter { m ->
                        val g = (m.genre ?: "").lowercase()
                        val n = m.displayName.lowercase()
                        genreTokens.any { t -> g.contains(t) || n.contains(t) }
                    }
                } else emptyList()
                recommended = (byCategory + byGenre + all)
                    .distinctBy { it.id }
                    .sortedWith(
                        compareByDescending<VodItem> { it.ratingValue }
                            .thenByDescending { it.addedEpoch }
                    )
                    .take(24)

                loading = false
            }

            LaunchedEffect(Unit) {
                while (true) {
                    position = engine.timeMs()
                    val len = engine.lengthMs()
                    if (len > 0) duration = len
                    playing = engine.isPlaying
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
                delay(1800)
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

            val title = info?.displayTitle?.takeIf { it.isNotBlank() } ?: movieName
            val cover = info?.posterUrl?.takeIf { it.isNotBlank() } ?: movieCoverExtra
            val backdrop = backdropUrl
            val castText = info?.cast?.takeIf { it.isNotBlank() } ?: "—"
            val plotText = info?.displayPlot
                ?: "Disfruta de esta película en alta definición."
            val dateLine = buildString {
                val d = info?.displayDate.orEmpty()
                if (d.isNotBlank()) append(d).append(" | ")
                append(title)
            }
            val rating = info?.ratingBadge.orEmpty()
            val genreLine = info?.genre?.takeIf { it.isNotBlank() }.orEmpty()

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
                            // Con HUD visible, el DPAD navega botones (no consumir).
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
                AndroidView(
                    factory = { ctx ->
                        IjkVideoLayout(ctx).also { layout ->
                            layout.layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            layout.keepScreenOn = true
                            layout.isFocusable = false
                            layout.isFocusableInTouchMode = false
                            engine.attach(layout)
                        }
                    },
                    modifier = if (fullScreen) {
                        Modifier.fillMaxSize().zIndex(0f)
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
                        fromPoster = backdropFromPoster,
                        modifier = Modifier.fillMaxSize().zIndex(0f)
                    )

                    when {
                        loading -> Box(
                            Modifier.fillMaxSize().zIndex(2f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MovieBlue)
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
                                                    .background(MovieRating)
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
                                    if (genreLine.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            genreLine,
                                            color = MovieAmber,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Black,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
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
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        MovieActionButton("Pantalla completa", Icons.Filled.Tv, true) {
                                            requestFullscreen()
                                        }
                                        MovieActionButton("Idioma y subtítulos", Icons.Filled.Subtitles, false) {
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

                            if (recommended.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "Recomendadas",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(6.dp))
                                val recItems = remember(recommended) {
                                    recommended.map { it.id to it.streamIcon }
                                }
                                val recById = remember(recommended) {
                                    recommended.associateBy { it.id }
                                }
                                PagedSixPosterRow(
                                    items = recItems,
                                    onClickItem = { id ->
                                        recById[id]?.let { openRelated(it) }
                                    }
                                )
                            }
                        }
                    }
                } else if (hudVisible) {
                    // Ventana aparte: SurfaceView no puede tapar el HUD.
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
                                        title,
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
                                        Text(formatMovieTime(position), color = Color.White, fontSize = 11.sp)
                                        Text(
                                            formatMovieTime(duration),
                                            color = Color.White.copy(alpha = 0.65f),
                                            fontSize = 11.sp
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        MovieHudBtn(Icons.Filled.Replay10, "−10s", onFocused = { bumpHud() }) {
                                            engine.seekBy(-10_000); bumpHud()
                                        }
                                        MovieHudBtn(
                                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                            if (playing) "Pausa" else "Play",
                                            playFocus,
                                            onFocused = { bumpHud() }
                                        ) {
                                            engine.togglePause()
                                            playing = engine.isPlaying
                                            bumpHud()
                                        }
                                        MovieHudBtn(Icons.Filled.Forward10, "+10s", onFocused = { bumpHud() }) {
                                            engine.seekBy(10_000); bumpHud()
                                        }
                                        MovieHudBtn(Icons.Filled.Translate, "Audio", onFocused = { bumpHud() }) {
                                            toast = engine.cycleAudioTrack() ?: "Sin audio"
                                            bumpHud()
                                        }
                                        MovieHudBtn(Icons.Filled.Subtitles, "Subs", onFocused = { bumpHud() }) {
                                            toast = engine.cycleSubtitleTrack()
                                            bumpHud()
                                        }
                                        MovieHudBtn(Icons.Filled.AspectRatio, "Pantalla", onFocused = { bumpHud() }) {
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
                                    fontSize = 15.sp,
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
                            fontSize = 15.sp,
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
                        subtitle = title.takeIf { it.isNotBlank() },
                        onContinue = { applyResumeChoice(continueWatching = true) },
                        onFromStart = { applyResumeChoice(continueWatching = false) }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_MOVIE_ID = "movie_id"
        const val EXTRA_MOVIE_NAME = "movie_name"
        const val EXTRA_MOVIE_COVER = "movie_cover"
        const val EXTRA_MOVIE_FANART = "movie_fanart"
        const val EXTRA_CATEGORY_ID = "category_id"
        const val EXTRA_EXT = "ext"
        const val EXTRA_RESUME_MS = "resume_ms"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_SERVER = "server"
    }
}

private val MovieBlue = Color(0xFF007AFF)
private val MovieAmber = Color(0xFFFFC107)
private val MovieRating = Color(0xFF00B0FF)

@Composable
private fun MovieActionButton(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MovieBlue
        primary -> MovieAmber
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
private fun MovieHudBtn(
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

private fun formatMovieTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
