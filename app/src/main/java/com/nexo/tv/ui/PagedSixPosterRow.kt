package com.nexo.tv.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Misma fila del Home: exactamente 6 caratulas enteras, paginadas al navegar.
 */
@Composable
fun PagedSixPosterRow(
    items: List<Pair<String, String?>>,
    onFocusItem: (String) -> Unit = {},
    onClickItem: (String) -> Unit
) {
    if (items.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 10.dp
        val visible = 6
        val usable = maxWidth * 0.92f
        val posterW = (usable - gap * (visible - 1)) / visible
        val posterH = posterW * 1.5f
        var page by remember(items) { mutableIntStateOf(0) }
        var pageDir by remember { mutableIntStateOf(1) }
        val pageCount = ((items.size + visible - 1) / visible).coerceAtLeast(1)
        val firstFocus = remember { FocusRequester() }
        val lastFocus = remember { FocusRequester() }
        LaunchedEffect(items) { page = 0 }
        LaunchedEffect(page) {
            delay(40)
            runCatching {
                if (pageDir < 0) lastFocus.requestFocus() else firstFocus.requestFocus()
            }
        }
        val pageItems = remember(items, page) {
            items.drop(page * visible).take(visible)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom
        ) {
            if (page > 0) {
                Box(
                    Modifier
                        .size(1.dp)
                        .onFocusChanged {
                            if (it.isFocused) {
                                pageDir = -1
                                page -= 1
                            }
                        }
                        .focusable()
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                pageItems.forEachIndexed { i, (id, url) ->
                    val edgeRequester = when (i) {
                        0 -> firstFocus
                        pageItems.lastIndex -> lastFocus
                        else -> null
                    }
                    PosterImage(
                        url = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(posterW)
                            .height(posterH)
                            .then(if (edgeRequester != null) Modifier.focusRequester(edgeRequester) else Modifier)
                            .tvFocus(shape = RoundedCornerShape(10.dp), focusedScale = 1f)
                            .onFocusChanged { if (it.isFocused) onFocusItem(id) }
                            .clickable { onClickItem(id) }
                            .focusable()
                    )
                }
            }
            if (page < pageCount - 1) {
                Box(
                    Modifier
                        .size(1.dp)
                        .onFocusChanged {
                            if (it.isFocused) {
                                pageDir = 1
                                page += 1
                            }
                        }
                        .focusable()
                )
            }
        }
    }
}