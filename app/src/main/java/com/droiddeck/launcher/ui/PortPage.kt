package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.ports.Port
import com.droiddeck.launcher.ports.PortsState

@Composable
internal fun PortPage(port: Port, s: FrontEndState) {
    Rise(0) { Eyebrow(stringResource(R.string.ports_eyebrow, port.game)) }
    Rise(1) { Title(port.name) }
    Rise(2) {
        Lede(stringResource(if (PortsState.roms[port.id] != null) R.string.ports_found else R.string.ports_no_rom, port.game, port.name))
    }
    Rise(3) { Actions { PortActions(port, s, manage = true); BusyChip(s) } }
    if (!s.ready && PortsState.installed[port.id] == null) Rise(4) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Note(stringResource(R.string.ports_runtime_first)) }
    }
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
                ActionChip(stringResource(R.string.ports_version, tag), ok = true)
                if (!s.sessionRunning && working == null) ConfirmButton(stringResource(R.string.stores_uninstall), stringResource(R.string.ports_uninstall_confirm)) { PortsState.remove(ctx, port) }
            }
        }
        PortsState.roms[port.id] != null ->
            PrimaryButton(stringResource(R.string.ports_install, port.name), enabled = s.ready && !s.busy && working == null, main = true, modifier = leadOn) { PortsState.install(ctx, port) }
    }
    PortsState.error?.let { if (working == null) ActionChip(it, ok = false) }
}
