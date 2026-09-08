package com.nexo.tv

import android.os.Bundle
import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nexo.tv.data.ContinueWatching
import com.nexo.tv.player.IjkEngine
import com.nexo.tv.player.IjkVideoLayout
import com.nexo.tv.player.StreamBridge
import com.nexo.tv.ui.PosterImage
import kotlinx.coroutines.delay

class VodActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        keepAwakeWhileVisible()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val poster = intent.getStringExtra(EXTRA_POSTER).orEmpty()
        val movieId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val resumeFromIntent = intent.getLongExtra(EXTRA_RESUME_MS, -1L)
        StreamBridge.start()
        val engine = IjkEngine(this)

        setContent {
            var showHud by remember { mutableStateOf(true) }
            var playing by remember { mutableStateOf(true) }
            var position by remember { mutableLongStateOf(0L) }
            var duration by remember { mutableLongStateOf(0L) }
            var toast by remember { mutableStateOf<String?>(null) }
            var hudTick by remember { mutableStateOf(0) }
            var pendingResume by remember {
                mutableLongStateOf(
                    when {
                        resumeFromIntent > 0L -> resumeFromIntent
                        movieId.isNotBlank() ->
                            ContinueWatching.get(this@VodActivity, "movie", movieId)?.positionMs ?: 0L
                        else -> 0L
                    }
                )
            }
            var showResumePrompt by remember { mutableStateOf(pendingResume > 20_000L) }
            val promptShowing = rememberUpdatedState(showResumePrompt)
            val resumePending = rememberUpdatedState(pendingResume)
            val rootFocus = remember { FocusRequester() }
            val playFocus = remember { FocusRequester() }

            fun bumpHud() {
                showHud = true
                hudTick++
            }

            fun persistProgress() {
                if (movieId.isBlank()) return
                ContinueWatching.save(
                    this@VodActivity,
                    ContinueWatching.Item(
                        kind = "movie",
                        id = movieId,
                        title = title.ifBlank { "Película" },
                        poster = poster.ifBlank { null },
                        positionMs = engine.timeMs(),
                        durationMs = engine.lengthMs().coerceAtLeast(duration),
                        url = url
                    )
                )
            }

            DisposableEffect(engine) {
                engine.onPlaying = {
                    playing = true
                    duration = engine.lengthMs()
                    if (!promptShowing.value) {
                        val seek = resumePending.value
                        if (seek > 0L) {
                            pendingResume = 0L
                            engine.seekTo(seek)
                        }
                    } else {
                        engine.pause()
                        playing = false
                    }
                }
                if (url.isNotBlank()) engine.playVod(StreamBridge.maybeWrap(url))
                onDispose {
                    persistProgress()
                    engine.release()
                }
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

            LaunchedEffect(showResumePrompt) {
                if (showResumePrompt) {
                    engine.pause()
                    playing = false
                }
            }

            LaunchedEffect(hudTick) {
                if (!showHud) return@LaunchedEffect
                delay(8000)
                showHud = false
            }

            LaunchedEffect(toast) {
                if (toast == null) return@LaunchedEffect
                delay(1800)
                toast = null
            }

            LaunchedEffect(showHud) {
                delay(60)
                if (showHud) {
                    runCatching { playFocus.requestFocus() }
                } else {
                    runCatching { rootFocus.requestFocus() }
                }
            }

            BackHandler {
                when {
                    showResumePrompt -> finish()
                    showHud -> showHud = false
                    else -> {
                        persistProgress()
                        finish()
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .focusRequester(rootFocus)
                    .focusProperties { canFocus = !showHud && !showResumePrompt }
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        if (showResumePrompt) return@onPreviewKeyEvent false
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (e.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                        val code = e.nativeKeyEvent.keyCode
                        if (showHud) {
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
                    modifier = Modifier
                        .fillMaxSize()
                        .focusProperties { canFocus = false }
                )

                AnimatedVisibility(
                    visible = showHud,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .focusGroup()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color(0xE6000000))
                                )
                            )
                            .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Carátula abajo a la izquierda
                        Box(
                            Modifier
                                .width(56.dp)
                                .height(84.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            PosterImage(
                                url = poster,
                                contentDescription = title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Column(Modifier.weight(1f)) {
                            if (title.isNotBlank()) {
                                Text(
                                    title,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                            }
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
                                Text(formatTime(position), color = Color.White, fontSize = 11.sp)
                                Text(
                                    formatTime(duration),
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                HudButton(Icons.Filled.Replay10, "−10s") {
                                    engine.seekBy(-10_000); bumpHud()
                                }
                                HudButton(
                                    icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    label = if (playing) "Pausa" else "Play",
                                    focusRequester = playFocus
                                ) {
                                    engine.togglePause()
                                    playing = engine.isPlaying
                                    bumpHud()
                                }
                                HudButton(Icons.Filled.Forward10, "+10s") {
                                    engine.seekBy(10_000); bumpHud()
                                }
                                HudButton(Icons.Filled.Translate, "Audio") {
                                    toast = engine.cycleAudioTrack() ?: "Sin pistas de audio"
                                    bumpHud()
                                }
                                HudButton(Icons.Filled.Subtitles, "Subs") {
                                    toast = engine.cycleSubtitleTrack()
                                    bumpHud()
                                }
                                HudButton(Icons.Filled.AspectRatio, "Pantalla") {
                                    toast = engine.cycleAspectMode()
                                    bumpHud()
                                }
                            }
                        }
                    }
                }

                toast?.let { msg ->
                    Text(
                        text = msg,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (showResumePrompt) {
                    ResumePrompt(
                        title = "¿Seguir viendo?",
                        subtitle = title.ifBlank { null },
                        onContinue = {
                            showResumePrompt = false
                            val seek = pendingResume
                            pendingResume = 0L
                            if (seek > 0L) engine.seekTo(seek)
                            engine.resume()
                            playing = true
                            bumpHud()
                        },
                        onFromStart = {
                            showResumePrompt = false
                            pendingResume = 0L
                            engine.seekTo(0L)
                            engine.resume()
                            playing = true
                            bumpHud()
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSTER = "poster"
        const val EXTRA_ID = "id"
        const val EXTRA_RESUME_MS = "resume_ms"
    }
}

@Composable
private fun HudButton(
    icon: ImageVector,
    label: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { isFocused = it.isFocused }
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

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
