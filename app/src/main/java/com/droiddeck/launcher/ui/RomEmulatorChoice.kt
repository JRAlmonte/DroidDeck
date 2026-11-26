package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.EmulatorLinks
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.RetroArchCores
import com.droiddeck.launcher.runtime.LinuxRuntime

private const val ROMS = "/root/ROMs/"

/** A game's path below the ROMs folder, which is what a pick for one game is kept under. */
internal fun romPath(guestPath: String): String? = guestPath.takeIf { it.startsWith(ROMS) }?.removePrefix(ROMS)?.takeIf { '/' in it }

/** The installed emulators and cores that take this game's file, the system's own first. */
internal fun romRunners(path: String, s: FrontEndState, cores: Set<String>): List<Library.Runner> {
    val ext = path.substringAfterLast('.', "").lowercase()
    val installed = s.emulators.filter { it.installed }.map { it.id }.toSet()
    return Library.runners(path.substringBefore('/')).filter { ext in it.exts && it.emulatorId in installed && (it.core == null || it.core in cores) }
}

/**
 * A row that sets the emulator for one game, shown only when there is more than one to pick from. [onPicked] gets the id of
 * the emulator that now runs the game.
 */
@Composable
internal fun RomEmulatorRow(host: MenuHost, guestPath: String, s: FrontEndState, a: FrontEndActions, onPicked: (String) -> Unit = {}) {
    val path = romPath(guestPath) ?: return
    val ctx = LocalContext.current
    val cores = remember(s.emulators) { RetroArchCores.installed(LinuxRuntime.rootDir(ctx)) }
    val runners = romRunners(path, s, cores.keys)
    if (runners.size < 2) return
    val usual = Library.folderRunner(path) ?: return
    val picked = Library.gamePick(path)
    fun label(runner: Library.Runner) = runner.core?.let { "RetroArch · " + (cores[it]?.name ?: it) } ?: runner.name
    val options = listOf("" to stringResource(R.string.rom_emulator_default, label(usual))) + runners.map { it.key to label(it) }
    ChoiceRow(host, "rom-emulator", stringResource(R.string.rom_emulator), stringResource(R.string.rom_emulator_hint), options, picked?.key.orEmpty()) { key ->
        EmulatorLinks.chooseForGame(ctx, path, key.ifEmpty { null })
        a.onEmulatorsChanged()
        onPicked(runners.firstOrNull { it.key == key }?.emulatorId ?: usual.emulatorId)
    }
}
