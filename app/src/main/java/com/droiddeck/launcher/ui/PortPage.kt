package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.droiddeck.launcher.runtime.LinuxFex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.droiddeck.launcher.ports.Port
import com.droiddeck.launcher.ports.PortInstaller
import com.droiddeck.launcher.ports.PortLaunch
import com.droiddeck.launcher.ports.Ports
import com.droiddeck.launcher.ports.PortsState
import com.droiddeck.launcher.ports.RunWith

@Composable
internal fun PortPage(shown: Port, s: FrontEndState) {
    val ctx = LocalContext.current
    val port = remember(shown.id, PortsState.sources) { Ports.byId(shown.id) ?: shown }
    val fexMissing by produceState<String?>(null, port.id, PortsState.installed[port.id], s.sessionRunning) {
        value = withContext(Dispatchers.IO) {
            PortInstaller.x86Arch(PortInstaller.dir(ctx, port), port)?.takeIf { PortInstaller.installedTag(ctx, port) != null && !LinuxFex.ready(ctx) }
        }
    }
    Rise(0) { Eyebrow(stringResource(R.string.ports_eyebrow, port.game)) }
    Rise(1) { Title(port.name) }
    if (PortsState.roms[port.id] == null) Rise(2) { Lede(stringResource(R.string.ports_no_rom, port.game, port.name)) }
    Rise(3) { Actions { PortActions(port, s, manage = true); PortSettingsButton(port); BusyChip(s) } }
    if (!s.ready && PortsState.installed[port.id] == null) Rise(4) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Note(stringResource(R.string.ports_runtime_first)) }
    }
    fexMissing?.let { arch -> Rise(4) { Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Note(stringResource(R.string.app_fex_not_ready, arch)) } } }
    Rise(5) { Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Note(stringResource(R.string.ports_unverified, port.source)) } }
    val rom = PortsState.roms[port.id]
    val root = s.romsDir
    val asked = if (port.stagedRom.isEmpty() && !port.usesRom && rom != null && root != null) remember(port.id, rom.path, root) { PortInstaller.romGuest(port, rom, java.io.File(root)) } else null
    if (asked != null) Rise(6) { Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Note(stringResource(R.string.ports_asks_rom, asked)) } }
    val settings = listOf(PortLaunch.show(port.env), PortLaunch.show(port.args)).filter { it.isNotEmpty() }.joinToString(" ")
    if (settings.isNotEmpty()) Rise(7) { Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Note(stringResource(R.string.ports_starts_with, settings)) } }
}

@Composable
internal fun RunWithActions(
    guestPath: String, emulatorName: String?, port: Port?, s: FrontEndState, leadOn: Modifier = Modifier, emulator: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val pick = RunWith.choose(RunWith.stored(ctx, guestPath), emulatorName != null, port != null, port != null && PortsState.installed[port.id] != null)
    if (emulatorName != null && port != null) {
        SheetChips(listOf(emulatorName, port.name), if (pick == RunWith.PORT) 1 else 0) { i -> RunWith.set(ctx, guestPath, if (i == 1) RunWith.PORT else RunWith.EMULATOR) }
    }
    if (pick == RunWith.PORT && port != null) PortActions(port, s, manage = false, leadOn = leadOn) else emulator()
}

@Composable
internal fun PortActions(port: Port, s: FrontEndState, manage: Boolean, leadOn: Modifier = Modifier) {
    val ctx = LocalContext.current
    val tag = PortsState.installed[port.id]
    val working = PortsState.working
    when {
        working == port.id -> {
            val stage = PortsState.stage ?: stringResource(R.string.user_apps_starting)
            val percent = PortsState.percent
            ActionChip(if (percent >= 0) stringResource(R.string.store_busy_percent_short, stage, percent) else stage, ok = false, fraction = if (percent >= 0) percent / 100f else null)
        }
        tag != null -> {
            PrimaryButton(stringResource(R.string.ports_play), enabled = !s.busy, main = true, icon = Icons.Filled.PlayArrow, modifier = leadOn) { PortsState.launch(ctx, port) }
            if (manage) {
                ActionChip(tag, ok = true)
                if (!s.sessionRunning && working == null) ConfirmIconAction(Icons.Outlined.Delete, stringResource(R.string.stores_uninstall), stringResource(R.string.ports_uninstall_confirm), "port:uninstall") { PortsState.remove(ctx, port) }
            }
        }
        PortsState.roms[port.id] != null ->
            PrimaryButton(stringResource(R.string.stores_install), enabled = s.ready && !s.busy && working == null, main = true, icon = Icons.Filled.Download, modifier = leadOn) { PortsState.install(ctx, port) }
    }
    PortsState.errors[port.id]?.let { if (working == null) ActionChip(it, ok = false) }
}
