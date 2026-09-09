package com.nexo.tv

import android.content.Context
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nexo.tv.data.LiveBoot
import com.nexo.tv.data.LiveCategory
import com.nexo.tv.data.LiveChannel
import com.nexo.tv.data.XtreamClient
import com.nexo.tv.player.IjkEngine
import com.nexo.tv.player.IjkVideoLayout
import com.nexo.tv.player.StreamBridge
import com.nexo.tv.ui.ChannelMaintenanceOverlay
import com.nexo.tv.ui.Device
import com.nexo.tv.ui.MobileLiveScreen
import com.nexo.tv.ui.PosterImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class LiveActivity : ComponentActivity() {
    /** Último canal reproducido (para guardar al ir a Home / cerrar). */
    @Volatile private var lastPlayed: LiveChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        keepAwakeWhileVisible()
        intent.getStringExtra(EXTRA_USER)?.let { Session.username = it }
        intent.getStringExtra(EXTRA_PASS)?.let { Session.password = it }
        intent.getStringExtra(EXTRA_SERVER)?.let { if (it.isNotBlank()) Session.server = it }
        StreamBridge.start()
        val engine = IjkEngine(this)
        val prefs = getSharedPreferences(LiveBoot.PREFS, Context.MODE_PRIVATE)

        fun persistWatching(ch: LiveChannel) {
            lastPlayed = ch
            // Guardar canal + categoria del canal visto (no "Todas").
            val cat = ch.categoryId.orEmpty()
            prefs.edit()
                .putString(LiveBoot.KEY_CHANNEL, ch.id)
                .putString(LiveBoot.KEY_CATEGORY, cat)
                .putString(LiveBoot.KEY_CHANNEL_NAME, ch.name)
                .putString(LiveBoot.KEY_CHANNEL_ICON, ch.streamIcon.orEmpty())
                .apply()
        }

        setContent {
            var allChannels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
            var categories by remember { mutableStateOf<List<LiveCategory>>(emptyList()) }
            var selectedCategoryId by remember {
                mutableStateOf(prefs.getString(LiveBoot.KEY_CATEGORY, null).orEmpty())
            }
            var index by remember { mutableIntStateOf(0) }
            var loading by remember { mutableStateOf(!LiveBoot.ready) }
            var status by remember { mutableStateOf("Cargando…") }
            var showBanner by remember { mutableStateOf(false) }
            var bannerTick by remember { mutableIntStateOf(0) }
            var showCategories by remember { mutableStateOf(false) }
            var hasVideoFrame by remember { mutableStateOf(false) }
            var maintenance by remember { mutableStateOf(false) }
            var playGen by remember { mutableIntStateOf(0) }
            var bootPlayed by remember { mutableStateOf(false) }
            val rootFocus = remember { FocusRequester() }
            val categoryFocus = remember { FocusRequester() }
            val listState = rememberLazyListState()
            val recentChannelIds = remember { mutableListOf<String>() }

            val activeChannels = remember(allChannels, selectedCategoryId) {
                if (selectedCategoryId.isBlank()) allChannels
                else allChannels.filter { it.categoryId == selectedCategoryId }.ifEmpty { allChannels }
            }
            val selectedCategoryName = remember(categories, selectedCategoryId) {
                categories.firstOrNull { it.categoryId == selectedCategoryId }?.categoryName
                    ?: if (selectedCategoryId.isBlank()) "Todas" else "Categoría"
            }

            DisposableEffect(engine) {
                engine.onPlaying = {
                    status = "Reproduciendo"
                    hasVideoFrame = true
                    maintenance = false
                }
                engine.onError = {
                    hasVideoFrame = false
                    maintenance = true
                }
                engine.onBuffering = { buffering ->
                    if (!buffering && engine.isPlaying) {
                        hasVideoFrame = true
                        maintenance = false
                    }
                }
                onDispose { engine.release() }
            }

            fun revealBanner() {
                showBanner = true
                bannerTick++
            }

            fun playChannel(ch: LiveChannel, instant: Boolean = true) {
                persistWatching(ch)
                hasVideoFrame = false
                maintenance = false
                playGen++
                val remote = XtreamClient.liveUrl(ch.id)
                val revisiting = recentChannelIds.contains(ch.id)
                // Historial de canales visitados en esta sesion (para volver atras al instante)
                recentChannelIds.remove(ch.id)
                recentChannelIds.add(0, ch.id)
                while (recentChannelIds.size > 12) recentChannelIds.removeAt(recentChannelIds.lastIndex)

                android.util.Log.i(
                    "LiveActivity",
                    "play ${ch.name} id=${ch.id} revisit=$revisiting instant=$instant"
                )
                status = ch.name
                revealBanner()
                when {
                    // Canal ya pasado: matar anteriores y abrir sin debounce
                    revisiting -> engine.playRecent(remote)
                    instant -> engine.playZap(remote)
                    else -> engine.playNow(remote)
                }
            }

            fun selectCategory(catId: String) {
                selectedCategoryId = catId
                showCategories = false
                val list = if (catId.isBlank()) allChannels
                else allChannels.filter { it.categoryId == catId }.ifEmpty { allChannels }
                if (list.isEmpty()) {
                    status = "Sin canales en categoría"
                    prefs.edit().putString(LiveBoot.KEY_CATEGORY, catId).apply()
                    return
                }
                // Retomar ultimo canal de esta categoria si existe; si no, el primero.
                val savedId = prefs.getString(LiveBoot.KEY_CHANNEL, null).orEmpty()
                val resumeIdx = list.indexOfFirst { it.id == savedId }.takeIf { it >= 0 } ?: 0
                index = resumeIdx
                // playChannel guarda canal + categoria del canal visto
                playChannel(list[resumeIdx], instant = true)
                // Si eligio "Todas", persistir filtro vacio aparte del canal.
                if (catId.isBlank()) {
                    prefs.edit().putString(LiveBoot.KEY_CATEGORY, "").apply()
                }
                runCatching { rootFocus.requestFocus() }
            }

            fun zap(delta: Int) {
                val list = activeChannels
                if (list.isEmpty()) return
                // Loop: al pasar el último vuelve al primero (y viceversa).
                index = (index + delta + list.size) % list.size
                playChannel(list[index])
            }

            fun openCategories() {
                showCategories = true
                revealBanner()
            }

            LaunchedEffect(bannerTick) {
                if (bannerTick == 0) return@LaunchedEffect
                delay(3500)
                showBanner = false
            }

            // Si no hay video en ~8s, mostrar mantenimiento automaticamente
            LaunchedEffect(playGen) {
                if (playGen == 0) return@LaunchedEffect
                delay(8000)
                if (!hasVideoFrame) {
                    maintenance = true
                }
            }

            LaunchedEffect(showCategories) {
                if (showCategories) {
                    delay(80)
                    runCatching { categoryFocus.requestFocus() }
                    val idx = categories.indexOfFirst { it.categoryId == selectedCategoryId }
                        .coerceAtLeast(0)
                    if (categories.isNotEmpty()) {
                        runCatching { listState.scrollToItem(idx) }
                    }
                } else {
                    delay(40)
                    runCatching { rootFocus.requestFocus() }
                }
            }

            LaunchedEffect(Unit) {
                // 1) Reproducir al instante el ultimo canal (ya calentado en el splash).
                val boot = LiveBoot.savedChannel(this@LiveActivity)
                if (boot != null && !bootPlayed) {
                    bootPlayed = true
                    selectedCategoryId = boot.categoryId.orEmpty()
                    status = boot.name
                    // Pequeña espera para que el SurfaceView este listo
                    delay(40)
                    playChannel(boot, instant = false)
                }

                // 2) Lista de canales: usar cache del splash o pedirla ahora.
                val (cats, streams) = if (LiveBoot.ready && LiveBoot.channels.isNotEmpty()) {
                    LiveBoot.categories to LiveBoot.channels
                } else {
                    withContext(Dispatchers.IO) {
                        val c = runCatching { XtreamClient.liveCategories() }.getOrDefault(emptyList())
                        val s = runCatching { XtreamClient.liveChannels() }.getOrDefault(emptyList())
                            .filter { it.id.isNotBlank() }
                        val built = buildList {
                            add(LiveCategory(categoryId = "", categoryName = "Todas"))
                            addAll(c.filter { it.categoryId.isNotBlank() })
                        }
                        built to s
                    }
                }
                categories = cats
                allChannels = streams
                loading = false

                val savedChannelId = prefs.getString(LiveBoot.KEY_CHANNEL, null).orEmpty()
                val savedChannel = streams.firstOrNull { it.id == savedChannelId }

                val catId = when {
                    savedChannel != null -> savedChannel.categoryId.orEmpty()
                    else -> {
                        val raw = prefs.getString(LiveBoot.KEY_CATEGORY, null).orEmpty()
                        if (raw.isNotBlank() && cats.any { it.categoryId == raw }) raw else ""
                    }
                }
                selectedCategoryId = catId
                prefs.edit().putString(LiveBoot.KEY_CATEGORY, catId).apply()

                android.util.Log.i(
                    "LiveActivity",
                    "channels=${streams.size} cats=${cats.size} cat=$catId ch=$savedChannelId boot=$bootPlayed"
                )

                val list = if (catId.isBlank()) streams
                else streams.filter { it.categoryId == catId }.ifEmpty { streams }

                val playIdx = when {
                    savedChannelId.isNotBlank() ->
                        list.indexOfFirst { it.id == savedChannelId }.takeIf { it >= 0 } ?: 0
                    else -> 0
                }
                val start = list.getOrNull(playIdx)
                if (start != null) {
                    index = playIdx
                    // Si ya arranco el boot con el mismo id, no reabrir (evita flash negro).
                    if (!bootPlayed || start.id != boot?.id) {
                        playChannel(start, instant = true)
                    } else {
                        // Actualizar metadata/index sin matar el stream
                        persistWatching(start)
                        status = start.name
                    }
                } else if (!bootPlayed) {
                    status = "Sin canales"
                }
                delay(80)
                runCatching { rootFocus.requestFocus() }
            }

            var favorites by remember {
                mutableStateOf(prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet())
            }

            fun toggleFavorite(chId: String) {
                val updated = if (chId in favorites) favorites - chId else favorites + chId
                favorites = updated
                prefs.edit().putStringSet(KEY_FAVORITES, updated).apply()
            }

            val current = activeChannels.getOrNull(index)
            val isTv = remember { Device.isTv(this@LiveActivity) }

            if (!isTv) {
                MobileLiveScreen(
                    engine = engine,
                    allChannels = allChannels,
                    categories = categories,
                    selectedCategoryId = selectedCategoryId,
                    onSelectCategory = { selectCategory(it) },
                    currentChannel = current,
                    onSelectChannel = { ch ->
                        val idx = activeChannels.indexOfFirst { it.id == ch.id }
                        if (idx >= 0) index = idx
                        playChannel(ch, instant = true)
                    },
                    onZap = { delta -> zap(delta) },
                    status = status,
                    loading = loading,
                    favorites = favorites,
                    onToggleFavorite = { toggleFavorite(it) },
                    onHomeClick = {
                        current?.let { persistWatching(it) }
                        finish()
                    },
                    onLogout = {
                        exitNexoCompletely()
                    }
                )
            } else {
                BackHandler {
                    when {
                        showCategories -> showCategories = false
                        else -> {
                            current?.let { persistWatching(it) }
                            finish()
                        }
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .focusRequester(rootFocus)
                        .focusable()
                        .onKeyEvent { e ->
                            if (showCategories) return@onKeyEvent false
                            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                            if (e.nativeKeyEvent.repeatCount > 0) return@onKeyEvent true
                            when (e.nativeKeyEvent.keyCode) {
                                AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                                AndroidKeyEvent.KEYCODE_CHANNEL_DOWN,
                                AndroidKeyEvent.KEYCODE_PAGE_DOWN -> {
                                    zap(1); true
                                }
                                AndroidKeyEvent.KEYCODE_DPAD_UP,
                                AndroidKeyEvent.KEYCODE_CHANNEL_UP,
                                AndroidKeyEvent.KEYCODE_PAGE_UP -> {
                                    zap(-1); true
                                }
                                AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
                                AndroidKeyEvent.KEYCODE_MENU,
                                AndroidKeyEvent.KEYCODE_INFO -> {
                                    openCategories(); true
                                }
                                AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                                AndroidKeyEvent.KEYCODE_ENTER -> {
                                    if (current != null) revealBanner()
                                    true
                                }
                                else -> false
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
                        update = { engine.attach(it) },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Canal caido / sin senal: pantalla cinematografica (sin mensajes de carga al centro)
                    AnimatedVisibility(
                        visible = maintenance,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        ChannelMaintenanceOverlay()
                    }

                    // Banner canal (Popup sin foco: no bloquea el zapping)
                    if (showBanner && current != null && !showCategories && !maintenance) {
                        Popup(
                            alignment = Alignment.CenterStart,
                            properties = PopupProperties(
                                focusable = false,
                                dismissOnBackPress = false,
                                dismissOnClickOutside = false
                            )
                        ) {
                            ChannelSideBanner(
                                number = index + 1,
                                channel = current,
                                categoryName = selectedCategoryName
                            )
                        }
                    }

                    if (showCategories) {
                        Dialog(
                            onDismissRequest = { showCategories = false },
                            properties = DialogProperties(
                                dismissOnBackPress = true,
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
                                        when (e.nativeKeyEvent.keyCode) {
                                            AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                                            AndroidKeyEvent.KEYCODE_BACK -> {
                                                showCategories = false
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                            ) {
                                CategorySidePanel(
                                    categories = categories,
                                    selectedCategoryId = selectedCategoryId,
                                    listState = listState,
                                    firstFocus = categoryFocus,
                                    onSelect = { selectCategory(it) },
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                )
                            }
                        }
                    }

                    // Sin texto al centro en fullscreen (el mantenimiento tiene su propia UI)
                }
            }
        }
    }

    override fun onPause() {
        lastPlayed?.let { ch ->
            getSharedPreferences(LiveBoot.PREFS, Context.MODE_PRIVATE).edit()
                .putString(LiveBoot.KEY_CHANNEL, ch.id)
                .putString(LiveBoot.KEY_CATEGORY, ch.categoryId.orEmpty())
                .putString(LiveBoot.KEY_CHANNEL_NAME, ch.name)
                .putString(LiveBoot.KEY_CHANNEL_ICON, ch.streamIcon.orEmpty())
                .apply()
        }
        super.onPause()
    }

    override fun onStop() {
        lastPlayed?.let { ch ->
            getSharedPreferences(LiveBoot.PREFS, Context.MODE_PRIVATE).edit()
                .putString(LiveBoot.KEY_CHANNEL, ch.id)
                .putString(LiveBoot.KEY_CATEGORY, ch.categoryId.orEmpty())
                .putString(LiveBoot.KEY_CHANNEL_NAME, ch.name)
                .putString(LiveBoot.KEY_CHANNEL_ICON, ch.streamIcon.orEmpty())
                .apply()
        }
        super.onStop()
    }

    override fun onUserLeaveHint() {
        // Home: guardar canal
        lastPlayed?.let { ch ->
            getSharedPreferences(LiveBoot.PREFS, Context.MODE_PRIVATE).edit()
                .putString(LiveBoot.KEY_CHANNEL, ch.id)
                .putString(LiveBoot.KEY_CATEGORY, ch.categoryId.orEmpty())
                .putString(LiveBoot.KEY_CHANNEL_NAME, ch.name)
                .putString(LiveBoot.KEY_CHANNEL_ICON, ch.streamIcon.orEmpty())
                .apply()
        }
        super.onUserLeaveHint()
        if (Device.isTv(this)) {
            exitNexoCompletely()
        }
    }

    companion object {
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_SERVER = "server"
        private const val KEY_FAVORITES = "favorites"
    }
}

@Composable
private fun CategorySidePanel(
    categories: List<LiveCategory>,
    selectedCategoryId: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    firstFocus: FocusRequester,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0x00000000), Color(0xEE0A0A0A), Color(0xF5080808))
                )
            )
            .padding(start = 18.dp, end = 14.dp, top = 18.dp, bottom = 18.dp)
    ) {
        Text(
            "Categorías",
            color = Color(0xFFDE5B17),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Selecciona y queda guardada",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(categories, key = { _, c -> c.categoryId.ifBlank { "all" } }) { i, cat ->
                val selected = cat.categoryId == selectedCategoryId
                var focused by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (cat.categoryId == selectedCategoryId) Modifier.focusRequester(firstFocus)
                            else Modifier
                        )
                        .onFocusChanged { focused = it.isFocused }
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                focused -> Color(0xFFDE5B17)
                                selected -> Color.White.copy(alpha = 0.16f)
                                else -> Color.White.copy(alpha = 0.06f)
                            }
                        )
                        .border(
                            BorderStroke(
                                if (focused || selected) 2.dp else 1.dp,
                                when {
                                    focused -> Color.White
                                    selected -> Color(0xFFDE5B17)
                                    else -> Color.White.copy(alpha = 0.12f)
                                }
                            ),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelect(cat.categoryId) }
                        .focusable()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Text(
                        cat.categoryName.ifBlank { "Sin nombre" },
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelSideBanner(
    number: Int,
    channel: LiveChannel,
    categoryName: String
) {
    Row(
        Modifier
            .widthIn(min = 220.dp, max = 360.dp)
            .background(
                brush = Brush.horizontalGradient(
                    listOf(
                        Color(0xEE0A0A0A),
                        Color(0xCC141414),
                        Color(0x00000000)
                    )
                ),
                shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            )
            .padding(start = 18.dp, end = 28.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF222222)),
            contentAlignment = Alignment.Center
        ) {
            val icon = channel.streamIcon
            PosterImage(
                url = icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.widthIn(max = 220.dp)) {
            Text(
                text = categoryName,
                color = Color(0xFFDE5B17),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "%03d".format(number),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = channel.name,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
