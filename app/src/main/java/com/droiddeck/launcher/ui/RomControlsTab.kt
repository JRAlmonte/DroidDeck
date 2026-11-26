package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.RomControls
import com.droiddeck.launcher.frontend.RomScope

@Composable
internal fun RomControlsTab(emulatorId: String, guestPath: String, s: FrontEndState, a: FrontEndActions) {
    val context = LocalContext.current
    val host = rememberMenuHost()
    val scope = remember(emulatorId, guestPath) { RomScope.of(emulatorId, guestPath) }
    var saved by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RomEmulatorRow(host, guestPath, s, a)
        SettingsGroup(stringResource(R.string.drawer_page_controller)) {
            InputQuickSettings(host, desktop = false, onEdit = { a.controller?.onMapping?.invoke() }, scope = scope.keys, initial = scope.game)
        }
        val settings = RomControls.forEmulator(emulatorId)
        if (settings.isNotEmpty() && scope.keys.isNotEmpty()) SettingsGroup(stringResource(R.string.rom_controls_emulator)) {
            val unset = "" to stringResource(R.string.rom_controls_unset)
            for (setting in settings) for (key in scope.keys) {
                val options = listOf(unset) + setting.values.map { it to stringResource(valueLabel(it)) }
                val chosen = remember(saved, key, setting) { RomControls.get(context, key, setting).orEmpty() }
                ChoiceRow(host, "rom-${setting.key}-$key", stringResource(settingLabel(setting)), scopeLabel(key), options, chosen) { value ->
                    RomControls.set(context, key, setting, value.ifEmpty { null })
                    saved++
                }
            }
            Text(stringResource(R.string.rom_controls_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

private fun settingLabel(setting: RomControls.Setting): Int = when (setting) {
    RomControls.Setting.PS1_PAD -> R.string.rom_controls_ps1_pad
    RomControls.Setting.ANALOG_DPAD -> R.string.rom_controls_analog_dpad
}

private fun valueLabel(value: String): Int = when (value) {
    "AnalogController" -> R.string.rom_controls_ps1_analog
    "DigitalController" -> R.string.rom_controls_ps1_digital
    "1" -> R.string.rom_controls_dpad_left
    "2" -> R.string.rom_controls_dpad_right
    else -> R.string.rom_controls_dpad_off
}
