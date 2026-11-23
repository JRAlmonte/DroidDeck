package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.RomScope

@Composable
internal fun RomControlsTab(emulatorId: String, guestPath: String, a: FrontEndActions) {
    val host = rememberMenuHost()
    val scope = remember(emulatorId, guestPath) { RomScope.of(emulatorId, guestPath) }
    Column(Modifier.fillMaxWidth()) {
        SettingsGroup(stringResource(R.string.drawer_page_controller)) {
            InputQuickSettings(host, desktop = false, onEdit = { a.controller?.onMapping?.invoke() }, scope = scope.keys, initial = scope.game)
        }
    }
}
