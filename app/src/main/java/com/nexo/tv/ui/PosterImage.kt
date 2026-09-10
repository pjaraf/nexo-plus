package com.nexo.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nexo.tv.R

/** Carátula con logo NEXO cuando no hay imagen remota. Usa el mismo size que la precarga. */
@Composable
fun PosterImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val ctx = LocalContext.current
    val fallback = painterResource(R.drawable.nexo_poster_fallback)
    val trimmed = url?.trim()?.takeIf { it.isNotEmpty() }
    val model: Any = remember(trimmed) {
        if (trimmed != null) {
            ImageRequest.Builder(ctx)
                .data(trimmed)
                .size(PosterPreloader.POSTER_W, PosterPreloader.POSTER_H)
                .memoryCacheKey(trimmed)
                .diskCacheKey(trimmed)
                .crossfade(false)
                .build()
        } else {
            R.drawable.nexo_poster_fallback
        }
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = contentScale,
        placeholder = fallback,
        error = fallback,
        fallback = fallback,
        modifier = modifier
    )
}
