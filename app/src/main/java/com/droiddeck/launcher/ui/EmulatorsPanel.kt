package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.EmulatorInventory
import com.droiddeck.launcher.frontend.EmulatorInventory.State
import com.droiddeck.launcher.stores.romm.RommFirmwareState

@Composable
internal fun EmulatorsPanel(s: FrontEndState, a: FrontEndActions) {
    val ctx = LocalContext.current
    LaunchedEffect(s.emulators, s.romsDir, s.ready, RommFirmwareState.done) { EmulatorSetupState.refresh(ctx, s.emulators) }
    EmulatorsPanel(EmulatorSetupState.result, s, a)
}

@Composable
internal fun EmulatorsPanel(result: EmulatorInventory.Result?, s: FrontEndState, a: FrontEndActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (result == null) Note(stringResource(R.string.emu_checking))
        else EmulatorSummary(result, s, a)
    }
}

/** The systems worth a row: those with games on the device first, then on the server, then what an emulator could play. */
internal fun emulatorSystems(result: EmulatorInventory.Result): List<EmulatorInventory.SystemRow> =
    result.systems.filter { it.wanted || it.candidates.isNotEmpty() || it.suggestions.isNotEmpty() }
        .sortedWith(compareBy({ !it.wanted }, { it.games == 0 }, { it.state == State.READY }, { it.name.lowercase() }))

/** What the summary counts: the systems with games on the device, or failing that on the server, or all that can be played. */
internal fun emulatorSummary(result: EmulatorInventory.Result): List<EmulatorInventory.SystemRow> =
    result.systems.filter { it.games > 0 }.ifEmpty { result.wanted }.ifEmpty { result.systems.filter { it.candidates.isNotEmpty() } }

@Composable
private fun EmulatorSummary(result: EmulatorInventory.Result, s: FrontEndState, a: FrontEndActions) {
    val counted = emulatorSummary(result)
    val attention = counted.filter { it.state != State.READY }
    var open by rememberSaveable { mutableStateOf(false) }
    ReadinessCard(
        counted.size - attention.size, counted.size,
        if (attention.isEmpty()) stringResource(R.string.emu_summary_all)
        else stringResource(R.string.emu_summary_attention, attention.joinToString(", ") { it.name }),
        open && attention.isNotEmpty(), { open = !open },
        title = stringResource(if (attention.isEmpty()) R.string.emu_summary_ready else R.string.emu_summary_not_ready),
        ready = attention.isEmpty(),
    ) {
        attention.forEachIndexed { index, row ->
            CheckRow(CheckState.WARN, row.name, emulatorHint(row), divider = index < attention.lastIndex) { EmulatorRowAction(row, s, a) }
        }
    }
    PrimaryButton(stringResource(R.string.emu_setup_open), main = attention.isNotEmpty(), modifier = Modifier.testTag("emulator-setup-open"), onClick = a.onEmulatorSetup)
}

@Composable
internal fun emulatorStateLine(row: EmulatorInventory.SystemRow): String = when (row.state) {
    State.READY -> stringResource(R.string.emu_state_ready, row.chosen?.name.orEmpty())
    State.NOT_INSTALLED -> stringResource(R.string.emu_state_not_installed, row.chosen?.name.orEmpty())
    State.CORE_MISSING -> stringResource(R.string.emu_state_core_missing, row.chosen?.core.orEmpty())
    State.NEEDS_FILES -> row.needs.firstOrNull()?.let { need ->
        if (need.inApp) stringResource(R.string.emu_need_in_app, need.name, row.chosen?.name.orEmpty())
        else stringResource(R.string.cores_bios_needed, need.name, need.where)
    } ?: stringResource(R.string.cores_bios_needed, row.missing.joinToString(" / "), stringResource(R.string.cores_system_folder))
    State.NOTHING -> stringResource(R.string.emu_state_nothing)
}

@Composable
internal fun emulatorHint(row: EmulatorInventory.SystemRow): String = listOfNotNull(
    emulatorStateLine(row),
    row.games.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.mode_added_count, it, it) },
    row.onServer.takeIf { it > 0 }?.let { stringResource(R.string.emu_on_server, it) },
).joinToString(" · ")

@Composable
internal fun EmulatorRowAction(row: EmulatorInventory.SystemRow, s: FrontEndState, a: FrontEndActions) {
    val pkg = row.chosen?.packageId
    if ((row.state == State.NOT_INSTALLED || row.state == State.CORE_MISSING) && pkg != null) {
        if (s.packageBusyId == pkg) ActionChip(s.packageStage ?: stringResource(R.string.store_installing), ok = false)
        else SecondaryButton(
            stringResource(if (row.state == State.CORE_MISSING) R.string.emu_action_repair else R.string.stores_install),
            enabled = s.ready && !s.busy && s.packageBusyId == null && !s.packageCatalogLoading && !s.sessionRunning && s.packages?.any { it.id == pkg } == true,
            compact = true, modifier = Modifier.testTag("emulator-install-${row.key}"),
        ) { a.onInstallPackage(pkg) }
    } else if (row.state == State.NEEDS_FILES) {
        val ctx = LocalContext.current
        val need = row.needs.firstOrNull()
        val emulator = s.emulators.firstOrNull { it.id == row.chosen?.emulatorId }
        when {
            need != null && need.copies.isNotEmpty() ->
                SecondaryButton(stringResource(R.string.emu_action_copy, need.name), compact = true, modifier = Modifier.testTag("emulator-copy-${row.key}")) {
                    EmulatorSetupState.copy(need, a.onEmulatorsChanged)
                }
            need != null && need.inApp && emulator != null ->
                SecondaryButton(stringResource(R.string.content_open_named, emulator.name), enabled = !s.busy, compact = true) { a.onEmulator(emulator) }
            row.platformId.isNotEmpty() -> {
                LaunchedEffect(row.platformId) { RommFirmwareState.check(ctx, row.slug, row.platformId) }
                val held = RommFirmwareState.status[row.platformId]
                if (RommFirmwareState.working == row.platformId) ActionChip(stringResource(R.string.stores_romm_bios_getting), ok = false)
                else if (held != null && held.missing > 0) SecondaryButton(stringResource(R.string.stores_romm_bios_get, held.missing), compact = true) {
                    RommFirmwareState.fetch(ctx, row.slug, row.platformId)
                }
            }
        }
    } else if (row.state != State.READY) row.suggestions.firstOrNull()?.let { profile ->
        SecondaryButton(stringResource(R.string.emu_action_get, profile.name), compact = true, modifier = Modifier.testTag("emulator-get-${row.key}")) {
            com.droiddeck.launcher.store.StoreState.openRequest = profile.flatpak
            RailRequest.key = "store"
        }
    }
}

/** The emulator in use for [row], to pick another when more than one can play it. */
@Composable
internal fun EmulatorChooser(row: EmulatorInventory.SystemRow, a: FrontEndActions, host: MenuHost) {
    val ctx = LocalContext.current
    val chosen = row.chosen ?: return
    if (row.candidates.size < 2) return
    ChoiceChip(host, "emulator-${row.key}", row.name, row.candidates.map { it.key to it.name }, chosen.key) { picked ->
        EmulatorSetupState.choose(ctx, row, picked)
        a.onEmulatorsChanged()
    }
}
