package com.droiddeck.launcher.ui

import android.app.Activity
import android.app.WallpaperManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import java.util.function.Consumer

internal val LocalFrost = staticCompositionLocalOf<Color?> { null }

private const val FROST_TINT_DARK = 0.58f
private const val FROST_TINT_LIGHT = 0.7f
private const val FROST_TINT_MAX = 0.9f
private const val FROST_TINT_STEP = 0.04f
private const val FROST_PANE_DARK = 0.46f
private const val FROST_PANE_LIGHT = 0.62f
private const val FROST_CARD_DARK = 0.6f
private const val FROST_CARD_LIGHT = 0.74f
private const val FROST_BLUR_DP = 28

internal fun frostVeil(palette: Palette, wallpaper: Color?): Color {
    val light = palette.background.luminance() > 0.5f
    val behind = wallpaper ?: if (light) Color.Black else Color.White
    var alpha = if (light) FROST_TINT_LIGHT else FROST_TINT_DARK
    while (alpha < FROST_TINT_MAX &&
        ThemeColors.contrast(palette.onSurfaceVariant, palette.background.copy(alpha = alpha).compositeOver(behind)) < ThemeColors.MIN_CONTRAST
    ) alpha += FROST_TINT_STEP
    return palette.background.copy(alpha = alpha.coerceAtMost(FROST_TINT_MAX))
}

internal fun Palette.frosted(): Palette {
    val alpha = if (background.luminance() > 0.5f) FROST_CARD_LIGHT else FROST_CARD_DARK
    return Palette(
        id, label, detail,
        background = background, surface = surface.copy(alpha = alpha), surfaceVariant = surfaceVariant.copy(alpha = alpha),
        line = line, line2 = line2, onBackground = onBackground, onSurfaceVariant = onSurfaceVariant,
        primary = primary, primary2 = primary2, onPrimary = onPrimary, signal = signal,
        good = good, error = error, warning = warning,
    )
}

@Composable
internal fun frostedPane(color: Color): Color =
    if (LocalFrost.current == null) color else color.copy(alpha = if (color.luminance() > 0.5f) FROST_PANE_LIGHT else FROST_PANE_DARK)

internal class LauncherFrost(private val activity: Activity) {
    var available by mutableStateOf(false)
        private set
    var wallpaper by mutableStateOf<Color?>(null)
        private set
    var prepared = false
        private set

    private var stopListening: (() -> Unit)? = null

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= 31 &&
            (available || activity.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true)

    fun start() {
        if (Build.VERSION.SDK_INT < 31 || stopListening != null) return
        val windows = activity.windowManager
        val walls = WallpaperManager.getInstance(activity)
        val blur = Consumer<Boolean> { available = it }
        val colors = WallpaperManager.OnColorsChangedListener { changed, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) wallpaper = changed?.primaryColor?.let { Color(it.toArgb()) }
        }
        available = windows.isCrossWindowBlurEnabled
        windows.addCrossWindowBlurEnabledListener(blur)
        walls.addOnColorsChangedListener(colors, Handler(Looper.getMainLooper()))
        wallpaper = runCatching { walls.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()?.primaryColor?.let { Color(it.toArgb()) }
        stopListening = {
            windows.removeCrossWindowBlurEnabledListener(blur)
            walls.removeOnColorsChangedListener(colors)
        }
    }

    fun stop() {
        stopListening?.invoke()
        stopListening = null
    }

    // A window made see-through after its first frame keeps drawing over its old frames, so this runs before setContent.
    fun prepare(on: Boolean) {
        prepared = on && supported
        if (prepared) activity.window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    fun show(on: Boolean) {
        if (!prepared || Build.VERSION.SDK_INT < 31) return
        val window = activity.window
        val flags = WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        if (on) window.addFlags(flags) else window.clearFlags(flags)
        window.attributes = window.attributes.apply {
            blurBehindRadius = if (on) (FROST_BLUR_DP * activity.resources.displayMetrics.density).toInt() else 0
        }
    }
}
