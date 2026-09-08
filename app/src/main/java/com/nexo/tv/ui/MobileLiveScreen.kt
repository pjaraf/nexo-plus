package com.nexo.tv.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.nexo.tv.Session
import com.nexo.tv.data.LiveCategory
import com.nexo.tv.data.LiveChannel
import com.nexo.tv.player.IjkEngine
import com.nexo.tv.player.IjkVideoLayout
import kotlinx.coroutines.delay

/**
 * Interfaz de TV en vivo para teléfonos y tablets.
 * Diseño idéntico a Tele Latino:
 * - Cabecera con logo Nexo + barra de búsqueda
 * - Reproductor embebido 16:9 arriba
 * - Pestañas "Categoría" y "Favoritos"
 * - Columna izquierda: Categorías
 * - Columna derecha: Canales con logo, número, nombre, subtítulo y favorito
 * - Barra de navegación inferior: Inicio, TV, Perfil
 */
@Composable
fun MobileLiveScreen(
    engine: IjkEngine,
    allChannels: List<LiveChannel>,
    categories: List<LiveCategory>,
    selectedCategoryId: String,
    onSelectCategory: (String) -> Unit,
    currentChannel: LiveChannel?,
    onSelectChannel: (LiveChannel) -> Unit,
    onZap: (Int) -> Unit,
    status: String,
    loading: Boolean,
    favorites: Set<String>,
    onToggleFavorite: (String) -> Unit,
    onHomeClick: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableIntStateOf(0) } // 0 = Categoría, 1 = Favoritos
    var isFullScreen by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    var controlsTick by remember { mutableIntStateOf(0) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var aspectLabel by remember { mutableStateOf<String?>(null) }
    val channelsListState = rememberLazyListState()

    // Ocultar controles táctiles automáticamente tras 3.5 segundos
    LaunchedEffect(controlsTick) {
        if (controlsTick > 0 && showControls) {
            delay(3500)
            showControls = false
        }
    }

    // Scroll automático al canal actualmente reproduciéndose al cambiar de categoría
    LaunchedEffect(currentChannel?.id, selectedCategoryId, activeTab) {
        if (currentChannel != null) {
            val visibleChannels = when {
                searchQuery.isNotBlank() -> allChannels.filter { it.name.contains(searchQuery, ignoreCase = true) }
                activeTab == 1 -> allChannels.filter { it.id in favorites }
                selectedCategoryId.isBlank() -> allChannels
                else -> allChannels.filter { it.categoryId == selectedCategoryId }
            }
            val idx = visibleChannels.indexOfFirst { it.id == currentChannel.id }
            if (idx >= 0) {
                runCatching { channelsListState.animateScrollToItem(idx) }
            }
        }
    }

    // Manejar botón atrás en pantalla completa
    BackHandler(enabled = isFullScreen) {
        isFullScreen = false
        (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    if (isFullScreen) {
        // === MODO PANTALLA COMPLETA ===
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable {
                    showControls = !showControls
                    if (showControls) controlsTick++
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    IjkVideoLayout(ctx).apply {
                        engine.attach(this)
                    }
                },
                update = { engine.attach(it) },
                modifier = Modifier.fillMaxSize()
            )

            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    // Barra superior
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .align(Alignment.TopCenter),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            isFullScreen = false
                            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Salir de pantalla completa",
                                tint = Color.White
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = currentChannel?.name ?: status,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            Modifier
                                .background(Color(0xFFDE5B17), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text("EN VIVO", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Controles centrales (Canal anterior, Play/Pausa, Canal siguiente)
                    Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        IconButton(
                            onClick = { onZap(-1); controlsTick++ },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Anterior", tint = Color.White)
                        }
                        IconButton(
                            onClick = { engine.togglePause(); controlsTick++ },
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color(0xFFDE5B17), CircleShape)
                        ) {
                            Icon(
                                if (engine.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Reproducir/Pausar",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        IconButton(
                            onClick = { onZap(1); controlsTick++ },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Siguiente", tint = Color.White)
                        }
                    }

                    // Barra inferior
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .align(Alignment.BottomCenter),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Cambiar modo de aspecto
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.15f))
                                .clickable {
                                    aspectLabel = engine.cycleAspectMode()
                                    controlsTick++
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AspectRatio, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(aspectLabel ?: "Aspecto", color = Color.White, fontSize = 12.sp)
                        }

                        // Salir de pantalla completa
                        IconButton(onClick = {
                            isFullScreen = false
                            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }) {
                            Icon(Icons.Default.FullscreenExit, contentDescription = "Minimizar", tint = Color.White)
                        }
                    }
                }
            }
        }
        return
    }

    // === MODO NORMAL (TELE LATINO STYLE: PORTRAIT) ===
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF131418))
    ) {
        // 1. Cabecera superior (Logo + Barra de búsqueda)
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(Color(0xFF131418))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logo NEXO
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onHomeClick() }
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFDE5B17)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.LiveTv,
                        contentDescription = "NEXO",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "NEXO",
                    color = Color(0xFFDE5B17),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.width(12.dp))

            // Barra de búsqueda redondeada
            Row(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF22242B))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Buscar",
                    tint = Color(0xFF8E909B),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            "Búsqueda por canal.",
                            color = Color(0xFF7A7D87),
                            fontSize = 13.sp
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                        cursorBrush = SolidColor(Color(0xFFDE5B17)),
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

        // 2. Reproductor de video embebido (16:9)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
                .clickable {
                    showControls = !showControls
                    if (showControls) controlsTick++
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    IjkVideoLayout(ctx).apply {
                        engine.attach(this)
                    }
                },
                update = { engine.attach(it) },
                modifier = Modifier.fillMaxSize()
            )

            // Controles táctiles embebidos
            if (showControls) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    // Play/Pausa central
                    IconButton(
                        onClick = { engine.togglePause(); controlsTick++ },
                        modifier = Modifier
                            .size(52.dp)
                            .align(Alignment.Center)
                            .background(Color(0xFFDE5B17), CircleShape)
                    ) {
                        Icon(
                            if (engine.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pausa",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    // Título del canal actual (arriba a la izquierda)
                    currentChannel?.let { ch ->
                        Text(
                            text = ch.name,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 12.dp, top = 8.dp)
                        )
                    }

                    // Botón para expandir a pantalla completa (abajo a la derecha)
                    IconButton(
                        onClick = {
                            isFullScreen = true
                            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Fullscreen,
                            contentDescription = "Pantalla completa",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            // Spinner de carga si está conectando
            if (loading && allChannels.isEmpty()) {
                CircularProgressIndicator(
                    color = Color(0xFFDE5B17),
                    modifier = Modifier
                        .size(36.dp)
                        .align(Alignment.Center)
                )
            }
        }

        // 3. Pestañas: Categoría | Favoritos
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Color(0xFF16171B))
        ) {
            // Pestaña Categoría
            val catSelected = activeTab == 0
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { activeTab = 0 },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Categoría",
                    color = if (catSelected) Color(0xFFDE5B17) else Color(0xFF8A8D98),
                    fontSize = 15.sp,
                    fontWeight = if (catSelected) FontWeight.Bold else FontWeight.Normal
                )
                if (catSelected) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .width(60.dp)
                            .height(2.5.dp)
                            .background(Color(0xFFDE5B17), RoundedCornerShape(1.dp))
                    )
                }
            }

            // Línea divisoria sutil
            Box(
                Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .align(Alignment.CenterVertically)
                    .background(Color(0xFF282A33))
            )

            // Pestaña Favoritos
            val favSelected = activeTab == 1
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { activeTab = 1 },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Favoritos",
                    color = if (favSelected) Color(0xFFDE5B17) else Color(0xFF8A8D98),
                    fontSize = 15.sp,
                    fontWeight = if (favSelected) FontWeight.Bold else FontWeight.Normal
                )
                if (favSelected) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .width(60.dp)
                            .height(2.5.dp)
                            .background(Color(0xFFDE5B17), RoundedCornerShape(1.dp))
                    )
                }
            }
        }

        // 4. Contenido dividido en 2 columnas (Categorías a la izquierda, Canales a la derecha)
        val channelsToDisplay = remember(allChannels, selectedCategoryId, activeTab, searchQuery, favorites) {
            when {
                searchQuery.isNotBlank() ->
                    allChannels.filter { it.name.contains(searchQuery, ignoreCase = true) }
                activeTab == 1 ->
                    allChannels.filter { it.id in favorites }
                selectedCategoryId.isBlank() ->
                    allChannels
                else ->
                    allChannels.filter { it.categoryId == selectedCategoryId }.ifEmpty { allChannels }
            }
        }

        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Columna Izquierda: Lista de Categorías
            LazyColumn(
                Modifier
                    .width(122.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF16171C))
            ) {
                itemsIndexed(categories, key = { _, c -> c.categoryId.ifBlank { "all" } }) { _, cat ->
                    val isCatSelected = activeTab == 0 && searchQuery.isBlank() && cat.categoryId == selectedCategoryId
                    val displayName = when {
                        cat.categoryId.isBlank() -> "ChannelList"
                        else -> cat.categoryName.ifBlank { "General" }
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .background(if (isCatSelected) Color(0xFF22242D) else Color.Transparent)
                            .clickable {
                                activeTab = 0
                                searchQuery = ""
                                onSelectCategory(cat.categoryId)
                            }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isCatSelected) {
                                Box(
                                    Modifier
                                        .width(3.dp)
                                        .height(22.dp)
                                        .background(Color(0xFFDE5B17), RoundedCornerShape(1.5.dp))
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = displayName,
                                color = if (isCatSelected) Color.White else Color(0xFF8E909B),
                                fontSize = 13.5.sp,
                                fontWeight = if (isCatSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Columna Derecha: Lista de Canales
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xFF1B1D24))
            ) {
                if (channelsToDisplay.isEmpty() && !loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (activeTab == 1) "No tienes canales favoritos aún.\nToca la estrella para agregar."
                                   else "No hay canales disponibles",
                            color = Color(0xFF8E909B),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        state = channelsListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(channelsToDisplay, key = { _, ch -> ch.id }) { index, ch ->
                            val isPlaying = ch.id == currentChannel?.id
                            val isFav = ch.id in favorites
                            val channelNum = ch.channelNumber.ifBlank { "%03d".format(index + 1) }

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .then(
                                        if (isPlaying) {
                                            Modifier.background(
                                                Brush.horizontalGradient(
                                                    listOf(
                                                        Color(0xFFDE5B17),
                                                        Color(0xFF8A2E05),
                                                        Color(0xFF1E1C22)
                                                    )
                                                )
                                            )
                                        } else Modifier
                                    )
                                    .clickable { onSelectChannel(ch) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Logo del canal
                                Box(
                                    Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF282B35)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    PosterImage(
                                        url = ch.streamIcon,
                                        contentDescription = ch.name,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(4.dp)
                                    )
                                }

                                Spacer(Modifier.width(10.dp))

                                // Número, Nombre y Subtítulo
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = channelNum,
                                            color = if (isPlaying) Color.White else Color(0xFFDE5B17),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = ch.name,
                                            color = Color.White,
                                            fontSize = 13.5.sp,
                                            fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "En vivo",
                                        color = if (isPlaying) Color.White.copy(alpha = 0.8f) else Color(0xFF8E909B),
                                        fontSize = 11.sp
                                    )
                                }

                                // Botón de favorito (Estrella)
                                IconButton(
                                    onClick = { onToggleFavorite(ch.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        if (isFav) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                        contentDescription = "Favorito",
                                        tint = if (isFav) Color(0xFFFFB300) else Color(0xFF6B6E7A),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Flecha derecha (>)
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = if (isPlaying) Color.White else Color(0xFF6B6E7A),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Separador inferior
                            if (!isPlaying) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(0.6.dp)
                                        .background(Color(0xFF252731))
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Barra de navegación inferior (Inicio, TV, Perfil)
        Row(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(Color(0xFF121316))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Inicio
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onHomeClick() },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Home,
                    contentDescription = "Inicio",
                    tint = Color(0xFF8E909B),
                    modifier = Modifier.size(20.dp)
                )
                Text("Inicio", color = Color(0xFF8E909B), fontSize = 10.sp)
            }

            // TV (Activo)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.LiveTv,
                    contentDescription = "TV",
                    tint = Color(0xFFDE5B17),
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    "TV",
                    color = Color(0xFFDE5B17),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Perfil
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { showProfileDialog = true },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = "Perfil",
                    tint = Color(0xFF8E909B),
                    modifier = Modifier.size(20.dp)
                )
                Text("Perfil", color = Color(0xFF8E909B), fontSize = 10.sp)
            }
        }
    }

    // Cuadro de diálogo de Perfil
    if (showProfileDialog) {
        Dialog(onDismissRequest = { showProfileDialog = false }) {
            Box(
                Modifier
                    .fillMaxWidth(0.9f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1E2028))
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(60.dp)
                            .background(Color(0xFFDE5B17), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Mi Perfil",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(16.dp))

                    Text(
                        "Usuario: ${Session.username.ifBlank { "Desconocido" }}",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Servidor: ${Session.server}",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Versión: Nexo Plus 2.2.47",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )

                    Spacer(Modifier.height(24.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.12f))
                                .clickable { showProfileDialog = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Cerrar", color = Color.White, fontSize = 13.sp)
                        }

                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFD32F2F))
                                .clickable {
                                    showProfileDialog = false
                                    Session.logout()
                                    onLogout()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Cerrar Sesión", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
