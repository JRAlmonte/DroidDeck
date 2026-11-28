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
    val host = rememberMenuHost()
    val scope = remember(emulatorId, guestPath) { RomScope.of(emulatorId, guestPath) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RomEmulatorRow(host, guestPath, s, a)
        SettingsGroup(stringResource(R.string.drawer_page_controller)) {
            InputQuickSettings(host, desktop = false, onEdit = { a.controller?.onMapping?.invoke() }, scope = scope.keys, initial = scope.game)
        }
        RomSettingsGroup(host, emulatorId, scope.keys, RomControls.Group.CONTROLS)
        RomSettingsGroup(host, emulatorId, scope.keys, RomControls.Group.SCREEN)
    }
}

/** The emulator's own settings of [group] DroidDeck can set, one row per setting and scope of [keys]; nothing when it has none. */
@Composable
internal fun RomSettingsGroup(host: MenuHost, emulatorId: String, keys: List<String>, group: RomControls.Group) {
    val context = LocalContext.current
    var saved by remember { mutableIntStateOf(0) }
    val settings = RomControls.forEmulator(emulatorId, group)
    if (settings.isEmpty() || keys.isEmpty()) return
    SettingsGroup(stringResource(if (group == RomControls.Group.SCREEN) R.string.rom_controls_screen else R.string.rom_controls_emulator)) {
        val unset = "" to stringResource(R.string.rom_controls_unset)
        for (setting in settings) for (key in keys) {
            val options = listOf(unset) + setting.values.map { it to stringResource(valueLabel(setting, it)) }
            val chosen = remember(saved, key, setting) { RomControls.get(context, key, setting).orEmpty() }
            ChoiceRow(host, "rom-${setting.key}-$key", stringResource(settingLabel(setting)), if (keys.size > 1) scopeLabel(key) else null, options, chosen) { value ->
                RomControls.set(context, key, setting, value.ifEmpty { null })
                saved++
            }
        }
        Text(stringResource(R.string.rom_controls_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

private fun settingLabel(setting: RomControls.Setting): Int = when (setting) {
    RomControls.Setting.PS1_PAD -> R.string.rom_controls_ps1_pad
    RomControls.Setting.ANALOG_DPAD -> R.string.rom_controls_analog_dpad
    RomControls.Setting.DS_LAYOUT, RomControls.Setting.N3DS_LAYOUT -> R.string.rom_screen_layout
    RomControls.Setting.DS_SIZING -> R.string.rom_screen_sizing
    RomControls.Setting.DS_SWAP, RomControls.Setting.N3DS_SWAP -> R.string.rom_screen_swap
    RomControls.Setting.PS1_ASPECT -> R.string.rom_screen_aspect
    RomControls.Setting.RA_INTEGER -> R.string.rom_screen_integer
}

private fun valueLabel(setting: RomControls.Setting, value: String): Int = when (setting) {
    RomControls.Setting.PS1_PAD -> if (value == "DigitalController") R.string.rom_controls_ps1_digital else R.string.rom_controls_ps1_analog
    RomControls.Setting.ANALOG_DPAD -> when (value) {
        "1" -> R.string.rom_controls_dpad_left
        "2" -> R.string.rom_controls_dpad_right
        else -> R.string.rom_controls_dpad_off
    }
    RomControls.Setting.DS_LAYOUT -> when (value) {
        "1" -> R.string.rom_screen_stacked
        "2" -> R.string.rom_screen_side_by_side
        "3" -> R.string.rom_screen_hybrid
        else -> R.string.rom_screen_natural
    }
    RomControls.Setting.DS_SIZING -> when (value) {
        "1" -> R.string.rom_screen_big_top
        "2" -> R.string.rom_screen_big_bottom
        "4" -> R.string.rom_screen_top_only
        "5" -> R.string.rom_screen_bottom_only
        else -> R.string.rom_screen_even
    }
    RomControls.Setting.N3DS_LAYOUT -> when (value) {
        "1" -> R.string.rom_screen_single
        "2" -> R.string.rom_screen_large
        "3" -> R.string.rom_screen_side_by_side
        "5" -> R.string.rom_screen_hybrid
        else -> R.string.rom_screen_stacked
    }
    RomControls.Setting.PS1_ASPECT -> if (value == "16:9") R.string.rom_screen_wide else R.string.rom_screen_four_three
    RomControls.Setting.DS_SWAP, RomControls.Setting.N3DS_SWAP, RomControls.Setting.RA_INTEGER -> if (value == "true") R.string.rom_screen_on else R.string.rom_controls_dpad_off
}
