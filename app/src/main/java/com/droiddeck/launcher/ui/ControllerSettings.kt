package com.droiddeck.launcher.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.droiddeck.launcher.input.ControllerPrefs

/** [onMappingFor] opens the mapping page on one profile's button actions and reports, when it closes, whether anything changed. */
class ControllerActions(val onMapping: () -> Unit, val onMappingFor: (profileId: String, onClosed: (changed: Boolean) -> Unit) -> Unit = { _, _ -> })

@Composable
internal fun rememberControllerSettings(): ControllerPrefs.Settings {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(ControllerPrefs.read(context)) }
    DisposableEffect(context) {
        val prefs = ControllerPrefs.prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> settings = ControllerPrefs.read(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return settings
}
