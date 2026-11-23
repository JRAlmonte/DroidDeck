package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
internal fun ScreenshotStrip(urls: List<String>, height: Dp, onOpen: ((String) -> Unit)? = null) {
    val shots = rememberScrollState()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().edgeFade(shots).horizontalScroll(shots)) {
        urls.forEach { url ->
            val open = if (onOpen == null) Modifier else Modifier.readable().controllerConfirm { onOpen(url) }.pointerInput(url) { detectTapGestures { onOpen(url) } }
            AsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = open.height(height).aspectRatio(16f / 9f).clip(Shape12).background(MaterialTheme.colorScheme.surface),
            )
        }
    }
}
