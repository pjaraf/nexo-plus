package com.nexo.tv.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest

private val BackdropBg = Color(0xFF0D0E15)

/**
 * Fondo cinematografico compartido (Hub / pelicula / serie).
 *
 * Si [fromPoster] es true (sin fanart del servidor), la app genera un fondo
 * automatico a partir de la caratula: ampliada, suave y con vineta, para que
 * no se vea recortada ni en mala calidad como poster vertical crudo.
 */
@Composable
fun CinematicBackdrop(
    url: String?,
    modifier: Modifier = Modifier,
    fromPoster: Boolean = false
) {
    val ctx = LocalContext.current
    Box(modifier.fillMaxSize().background(BackdropBg)) {
        if (!url.isNullOrBlank()) {
            val model = remember(url, fromPoster) {
                if (fromPoster) {
                    // Baja resolucion + upscale = fondo suave (blur) en cualquier API.
                    ImageRequest.Builder(ctx)
                        .data(url)
                        .size(120, 180)
                        .allowHardware(false)
                        .memoryCacheKey("hub-poster-bg:$url")
                        .diskCacheKey("hub-poster-bg:$url")
                        .crossfade(false)
                        .build()
                } else {
                    ImageRequest.Builder(ctx)
                        .data(url)
                        .size(1920, 1080)
                        .memoryCacheKey("hub-fanart:$url")
                        .diskCacheKey("hub-fanart:$url")
                        .crossfade(false)
                        .build()
                }
            }
            val imageMod = Modifier
                .fillMaxSize()
                .zIndex(0f)
                .then(
                    if (fromPoster) {
                        Modifier
                            .graphicsLayer {
                                scaleX = 1.55f
                                scaleY = 1.55f
                            }
                            .then(
                                if (Build.VERSION.SDK_INT >= 31) {
                                    Modifier.blur(28.dp)
                                } else {
                                    Modifier
                                }
                            )
                    } else {
                        Modifier
                    }
                )
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = if (fromPoster) FilterQuality.Low else FilterQuality.Medium,
                modifier = imageMod
            )
            val vertical = if (fromPoster) {
                listOf(
                    Color.Black.copy(alpha = 0.48f),
                    Color.Black.copy(alpha = 0.55f),
                    Color.Black.copy(alpha = 0.75f)
                )
            } else {
                listOf(
                    Color.Black.copy(alpha = 0.28f),
                    Color.Black.copy(alpha = 0.38f),
                    Color.Black.copy(alpha = 0.55f)
                )
            }
            val horizontal = if (fromPoster) {
                listOf(
                    Color.Black.copy(alpha = 0.58f),
                    Color.Black.copy(alpha = 0.30f),
                    Color.Black.copy(alpha = 0.40f)
                )
            } else {
                listOf(
                    Color.Black.copy(alpha = 0.42f),
                    Color.Black.copy(alpha = 0.18f),
                    Color.Transparent
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .background(Brush.verticalGradient(vertical))
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .background(Brush.horizontalGradient(horizontal))
            )
        }
    }
}

fun warmCinematicFanart(context: Context, url: String, fromPoster: Boolean = false) {
    val req = if (fromPoster) {
        ImageRequest.Builder(context)
            .data(url)
            .size(120, 180)
            .allowHardware(false)
            .memoryCacheKey("hub-poster-bg:$url")
            .diskCacheKey("hub-poster-bg:$url")
            .build()
    } else {
        ImageRequest.Builder(context)
            .data(url)
            .size(1920, 1080)
            .memoryCacheKey("hub-fanart:$url")
            .diskCacheKey("hub-fanart:$url")
            .build()
    }
    context.imageLoader.enqueue(req)
}
