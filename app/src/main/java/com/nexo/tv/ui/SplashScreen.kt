package com.nexo.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nexo.tv.R

/** Solo el logo NEXO al iniciar (sin textos de carga). */
@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize()) {
        LoginBackdrop()
        Image(
            painter = painterResource(R.drawable.ic_nexo_logo),
            contentDescription = "NEXO",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.Center)
                .size(148.dp)
                .clip(RoundedCornerShape(32.dp))
        )
    }
}
