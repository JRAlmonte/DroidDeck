package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.EmulatorInventory
import com.droiddeck.launcher.frontend.EmulatorInventory.State
import com.droiddeck.launcher.frontend.EmulatorLinks
import com.droiddeck.launcher.frontend.RetroArchCores
import com.droiddeck.launcher.frontend.RomScope
import com.droiddeck.launcher.runtime.UserApps
import com.droiddeck.launcher.store.UserAppsState

internal const val EMULATOR_SETUP_PAGE = "emulator-setup"

@Composable
internal fun EmulatorSetupPage(s: FrontEndState, a: FrontEndActions, onDone: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { UserAppsState.refresh(ctx) }
    LaunchedEffect(s.emulators, s.romsDir, s.ready, com.droiddeck.launcher.stores.romm.RommFirmwareState.done) { EmulatorSetupState.refresh(ctx, s.emulators) }
    EmulatorSetupPage(EmulatorSetupState.result, s, a, onDone = onDone)
}

@Composable
internal fun EmulatorSetupPage(result: EmulatorInventory.Result?, s: FrontEndState, a: FrontEndActions, opened: String? = null, onDone: () -> Unit) {
    val host = rememberMenuHost()
    var open by rememberSaveable { mutableStateOf(opened) }
    val system = result?.systems?.firstOrNull { it.key == open }
    if (system != null) {
        SystemSettingsPage(system, s, a) { open = null }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 620.dp && maxHeight < 500.dp
        SettingsPage(
            host = host, title = stringResource(R.string.emu_setup_title), eyebrow = stringResource(R.string.setup_tab_emulators),
            lede = if (compact) null else stringResource(R.string.emu_setup_lede), onBack = onDone, compactLayout = compact,
        ) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsGroup(stringResource(R.string.emu_step_basics)) { Basics(s, a, result, onDone) }
                SettingsGroup(stringResource(R.string.emu_step_found)) { Found(s, a, result, host) }
                if (result != null) {
                    SettingsGroup(stringResource(R.string.emu_step_systems)) { Systems(s, a, result, host) { open = it } }
                    val others = emulatorSystems(result).filter { !it.wanted }
                    if (others.isNotEmpty()) SettingsGroup(stringResource(R.string.emu_group_others)) {
                        others.forEachIndexed { index, row -> SystemRow(row, s, a, host, index < others.lastIndex) { open = it } }
                    }
                    if (result.cores.isNotEmpty()) Cores(result)
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    PrimaryButton(stringResource(R.string.emu_setup_done), main = true, modifier = Modifier.testTag("emulator-setup-done"), onClick = onDone)
                }
            }
        }
    }
}

@Composable
private fun Basics(s: FrontEndState, a: FrontEndActions, result: EmulatorInventory.Result?, onDone: () -> Unit) {
    CheckRow(
        if (s.romsDir != null) CheckState.OK else CheckState.WARN, stringResource(R.string.setup_tool_roms),
        s.romsDir ?: stringResource(R.string.emu_step_roms_hint),
    ) {
        if (s.romsDir == null) PrimaryButton(stringResource(R.string.emu_step_roms_default), compact = true, modifier = Modifier.testTag("emulator-setup-roms")) { a.onRomsDefault() }
        SecondaryButton(stringResource(if (s.romsDir == null) R.string.roms_choose else R.string.roms_change), compact = true, onClick = a.onRoms)
    }
    CheckRow(
        if (s.ready) CheckState.OK else CheckState.WARN, stringResource(R.string.emu_step_runtime),
        stringResource(if (s.ready) R.string.emu_step_runtime_ready else R.string.emu_step_runtime_hint),
    ) {
        if (!s.ready) SecondaryButton(stringResource(R.string.setup_tab_overview), compact = true) {
            SetupTabs.openSection = SettingsSection.OVERVIEW
            onDone()
        }
    }
    val retroArch = s.emulators.firstOrNull { it.id == "retroarch" }
    val pkg = com.droiddeck.launcher.runtime.DesktopCatalog.EMULATORS_ID
    val wanted = remember { RetroArchCores.systems.map { it.core }.distinct() }
    val missing = result?.cores.orEmpty().filter { it.id in wanted && !it.present }
    val installed = retroArch?.installed == true
    val working = s.packageBusyId == pkg
    CheckRow(
        if (working) CheckState.BUSY else if (installed && missing.isEmpty()) CheckState.OK else CheckState.WARN,
        stringResource(R.string.emu_step_package),
        when {
            working -> s.packageStage ?: stringResource(R.string.store_installing)
            !installed -> stringResource(R.string.emu_step_package_hint)
            missing.isEmpty() -> stringResource(R.string.emu_step_cores_all, wanted.size)
            else -> stringResource(R.string.emu_step_cores_missing, missing.joinToString(", ") { it.name })
        },
        divider = false,
    ) {
        if (!working && (!installed || missing.isNotEmpty())) PrimaryButton(
            stringResource(if (installed) R.string.emu_action_repair else R.string.stores_install),
            enabled = s.ready && !s.busy && s.packageBusyId == null && !s.packageCatalogLoading && !s.sessionRunning && s.packages?.any { it.id == pkg } == true,
            compact = true, modifier = Modifier.testTag("emulator-setup-package"),
        ) { a.onInstallPackage(pkg) }
    }
}

@Composable
private fun appSource(appKey: String?): String =
    stringResource(if (appKey?.startsWith("flatpak:") == true) R.string.emu_source_store else R.string.emu_source_appimage)

@Composable
private fun Found(s: FrontEndState, a: FrontEndActions, result: EmulatorInventory.Result?, host: MenuHost) {
    val ctx = LocalContext.current
    val apps = UserAppsState.items.filter { it.kind != UserApps.Kind.SCRIPT }
    val linked = s.emulators.filter { it.linked }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val off = remember(apps, linked) { EmulatorLinks.match(apps).filter { found -> linked.none { it.id == found.id } } }
    fun change(work: () -> Unit) {
        Thread({ work(); main.post(a.onEmulatorsChanged) }, "emulator-link").start()
    }
    linked.forEach { e ->
        CheckRow(CheckState.OK, e.name, stringResource(R.string.emu_found_plays, e.system, appSource(e.appKey))) {
            SecondaryButton(stringResource(R.string.emu_found_stop), compact = true, modifier = Modifier.testTag("emulator-unlink-${e.id}")) {
                change { if (e.id.startsWith(EmulatorLinks.MANUAL)) EmulatorLinks.assign(ctx, e.appKey.orEmpty(), null) else EmulatorLinks.setOff(ctx, e.id, true) }
            }
        }
    }
    off.forEach { link ->
        CheckRow(CheckState.WARN, link.name, stringResource(R.string.emu_found_off, link.system)) {
            SecondaryButton(stringResource(R.string.emu_found_use), compact = true) { change { EmulatorLinks.setOff(ctx, link.id, false) } }
        }
    }
    if (linked.isEmpty() && off.isEmpty()) CheckRow(CheckState.OK, stringResource(R.string.emu_found_none), stringResource(R.string.emu_found_none_hint))
    val others = apps.filter { app -> linked.none { it.appKey == app.key } && off.none { it.appKey == app.key } }
    val systems = result?.let(::emulatorSystems).orEmpty().filter { EmulatorLinks.systemFor(it.key) != null }
    if (others.isNotEmpty() && systems.isNotEmpty()) {
        var app by rememberSaveable { mutableStateOf(others.first().key) }
        var system by rememberSaveable { mutableStateOf(systems.first().key) }
        val picked = others.firstOrNull { it.key == app } ?: others.first()
        val target = systems.firstOrNull { it.key == system } ?: systems.first()
        SettingsRow(stringResource(R.string.emu_found_other), stringResource(R.string.emu_found_other_hint), stacked = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(host, "emulator-manual-app", stringResource(R.string.emu_found_other), others.map { it.key to it.name }, picked.key) { app = it }
                ChoiceChip(host, "emulator-manual-system", stringResource(R.string.emu_group_others), systems.map { it.key to it.name }, target.key) { system = it }
                SecondaryButton(stringResource(R.string.emu_found_use), compact = true, modifier = Modifier.testTag("emulator-manual-use")) {
                    change { EmulatorLinks.assign(ctx, picked.key, target.key) }
                }
            }
        }
    }
}

@Composable
private fun Systems(s: FrontEndState, a: FrontEndActions, result: EmulatorInventory.Result, host: MenuHost, open: (String) -> Unit) {
    val (wanted, beyond) = emulatorSystems(result).filter { it.wanted }
        .partition { it.games > 0 || it.candidates.isNotEmpty() || it.suggestions.isNotEmpty() }
    if (wanted.isEmpty() && beyond.isEmpty()) {
        CheckRow(CheckState.OK, stringResource(R.string.emu_systems_none), stringResource(R.string.emu_systems_none_hint), divider = false)
        return
    }
    wanted.forEachIndexed { index, row -> SystemRow(row, s, a, host, index < wanted.lastIndex || beyond.isNotEmpty(), open) }
    if (beyond.isNotEmpty()) SettingsRow(
        pluralStringResource(R.plurals.emu_systems_beyond, beyond.size, beyond.size),
        beyond.joinToString(", ") { it.name }, hintLines = 2,
    ) {}
}

@Composable
private fun SystemRow(row: EmulatorInventory.SystemRow, s: FrontEndState, a: FrontEndActions, host: MenuHost, divider: Boolean, open: (String) -> Unit) {
    CheckRow(if (row.state == State.READY) CheckState.OK else CheckState.WARN, row.name, emulatorHint(row), divider = divider) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EmulatorRowAction(row, s, a)
            EmulatorChooser(row, a, host)
            SecondaryButton(stringResource(R.string.emu_system_settings), compact = true, modifier = Modifier.testTag("emulator-system-${row.key}")) { open(row.key) }
        }
    }
}

@Composable
private fun Cores(result: EmulatorInventory.Result) {
    var shown by rememberSaveable { mutableStateOf(false) }
    SettingsGroup(stringResource(R.string.emu_group_cores), detail = stringResource(R.string.emu_cores_count, result.cores.count { it.present })) {
        if (!shown) SettingsRow(stringResource(R.string.emu_cores_show), result.cores.filter { it.used }.joinToString(", ") { it.name }, hintLines = 1) {
            SecondaryButton(stringResource(R.string.emu_cores_show), compact = true, modifier = Modifier.testTag("emulator-cores-show")) { shown = true }
        }
        else result.cores.forEach { core ->
            val plays = core.systems.joinToString(", ")
            SettingsRow(
                core.name,
                when {
                    core.used -> stringResource(R.string.emu_core_plays, plays)
                    plays.isEmpty() -> stringResource(R.string.emu_core_unused)
                    else -> plays + " · " + stringResource(R.string.emu_core_unused)
                },
            ) { ActionChip(stringResource(if (core.present) R.string.emu_core_present else R.string.emu_core_missing), ok = core.present) }
        }
    }
}

/**
 * One system's own page: which emulator plays it, and the controller and screen settings its games start with unless a game
 * has its own. The settings are kept under the system's key, which outlives a change of emulator.
 */
@Composable
internal fun SystemSettingsPage(row: EmulatorInventory.SystemRow, s: FrontEndState, a: FrontEndActions, onBack: () -> Unit) {
    val host = rememberMenuHost()
    val emulatorId = row.chosen?.emulatorId.orEmpty()
    val key = remember(row.key, emulatorId) { RomScope.system(emulatorId, row.key) }
    val keys = listOfNotNull(key)
    SettingsPage(
        host = host, title = row.name, eyebrow = stringResource(R.string.emu_setup_title),
        lede = stringResource(R.string.emu_system_lede), onBack = onBack,
    ) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsGroup(stringResource(R.string.emu_system_emulator)) {
                CheckRow(if (row.state == State.READY) CheckState.OK else CheckState.WARN, row.chosen?.name ?: row.name, emulatorHint(row), divider = false) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EmulatorRowAction(row, s, a)
                        EmulatorChooser(row, a, host)
                    }
                }
            }
            if (key != null) {
                SettingsGroup(stringResource(R.string.drawer_page_controller)) {
                    InputQuickSettings(host, desktop = false, onEdit = { a.controller?.onMapping?.invoke() }, scope = keys, initial = key)
                }
                RomSettingsGroup(host, emulatorId, keys, com.droiddeck.launcher.frontend.RomControls.Group.CONTROLS)
                RomSettingsGroup(host, emulatorId, keys, com.droiddeck.launcher.frontend.RomControls.Group.SCREEN)
                if (com.droiddeck.launcher.frontend.RomControls.forEmulator(emulatorId, com.droiddeck.launcher.frontend.RomControls.Group.SCREEN).isEmpty()) {
                    Note(stringResource(R.string.emu_system_no_screen, row.chosen?.name ?: row.name))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                PrimaryButton(stringResource(R.string.emu_setup_done), main = true, modifier = Modifier.testTag("emulator-system-done"), onClick = onBack)
            }
        }
    }
}
