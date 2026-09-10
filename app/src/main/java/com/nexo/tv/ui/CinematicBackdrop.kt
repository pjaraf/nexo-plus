package com.nexo.tv.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest

private val BackdropBg = Color(0xFF0D0E15)

/**
 * Fondo cinematografico compartido (Hub / pelicula / serie):
 * fanart a pantalla completa, sin placeholder NEXO, oscurecido suave.
 */
@Composable
fun CinematicBackdrop(
    url: String?,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    Box(modifier.fillMaxSize().background(BackdropBg)) {
        if (!url.isNullOrBlank()) {
            val model = remember(url) {
                ImageRequest.Builder(ctx)
                    .data(url)
                    .size(1920, 1080)
                    .memoryCacheKey("hub-fanart:$url")
                    .diskCacheKey("hub-fanart:$url")
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().zIndex(0f)
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.28f),
                                Color.Black.copy(alpha = 0.38f),
                                Color.Black.copy(alpha = 0.55f)
                            )
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.42f),
                                Color.Black.copy(alpha = 0.18f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}

fun warmCinematicFanart(context: Context, url: String) {
    val req = ImageRequest.Builder(context)
        .data(url)
        .size(1920, 1080)
        .memoryCacheKey("hub-fanart:$url")
        .diskCacheKey("hub-fanart:$url")
        .build()
    context.imageLoader.enqueue(req)
}