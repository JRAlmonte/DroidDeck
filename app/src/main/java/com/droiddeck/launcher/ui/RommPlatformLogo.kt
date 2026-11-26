package com.droiddeck.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.droiddeck.launcher.stores.romm.RommPlatform
import com.droiddeck.launcher.stores.romm.RommPlatformIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The picture the RomM server keeps for [platform], or null while it is read and when the server has none. */
@Composable
internal fun rommPlatformLogo(platform: RommPlatform?): ImageBitmap? {
    val app = LocalContext.current.applicationContext
    return key(platform?.slug) {
        produceState(platform?.let(RommPlatformIcons::ready)?.asImageBitmap()) {
            if (value == null && platform != null) value = withContext(Dispatchers.IO) { RommPlatformIcons.load(app, platform) }?.asImageBitmap()
        }.value
    }
}

@Composable
internal fun RommPlatformLogo(platform: RommPlatform, size: Dp) {
    val logo = rommPlatformLogo(platform)
    Box(Modifier.size(size)) { if (logo != null) Image(logo, contentDescription = null, modifier = Modifier.size(size)) }
}
