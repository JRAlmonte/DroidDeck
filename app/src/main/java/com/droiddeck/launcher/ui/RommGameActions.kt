package com.droiddeck.launcher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.RetroArchCores
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.romm.RommApi
import com.droiddeck.launcher.stores.romm.RommAuth
import com.droiddeck.launcher.stores.romm.RommBackend
import com.droiddeck.launcher.stores.romm.RommBrowseState
import com.droiddeck.launcher.stores.romm.RommCatalog
import com.droiddeck.launcher.stores.romm.RommDetails
import com.droiddeck.launcher.stores.romm.RommFile
import com.droiddeck.launcher.stores.romm.RommManual
import com.droiddeck.launcher.stores.romm.RommFirmwareState
import com.droiddeck.launcher.stores.romm.RommInstaller
import com.droiddeck.launcher.stores.romm.RommInstalls
import com.droiddeck.launcher.stores.romm.RommPlatforms
import com.droiddeck.launcher.stores.romm.RommVersion
import java.io.File

@Composable
internal fun RommGameActions(id: String, item: CatalogItem?, s: FrontEndState, a: FrontEndActions, leadOn: Modifier) {
    val ctx = LocalContext.current
    val versions = remember(item) { item?.let(RommCatalog::versions).orEmpty() }
    var picked by remember(id, versions.isEmpty()) {
        val own = RommInstalls.entries[id]
        mutableStateOf(if (own != null) own.item?.takeIf { RommCatalog.DISCS in it.extra } else versions.firstNotNullOfOrNull { RommInstalls.entries[it.id]?.item })
    }
    val sets = remember(item) {
        if (item == null || RommPlatforms.target(item.extra[RommCatalog.PLATFORM].orEmpty())?.exts?.contains("m3u") != true) emptyList()
        else RommCatalog.discSets(listOf(RommVersion(item.id, RommCatalog.versionName(item))) + versions)
    }
    var asking by remember(id) { mutableStateOf<String?>(null) }
    val read = { version: RommVersion, quiet: Boolean ->
        val kept = RommInstalls.entries[version.id]?.item
        if (kept != null) { picked = kept; asking = null }
        else {
            asking = version.id
            val app = ctx.applicationContext
            Thread({
                val (server, token) = RommAuth.both(app)
                val found = RommApi.rom(server, token, version.id).json?.let { rom ->
                    RommDetails.noteFiles(version.id, rom)
                    RommCatalog.item(server, rom)
                }
                StoresState.post { if (asking == version.id) { asking = null; if (found != null) picked = found else if (!quiet) unreachable(app) } }
            }, "romm-version").start()
        }
    }
    // In a list the server puts a game's main version first itself. A game it was asked for on its own is whichever version came.
    val lead = remember(item) { item?.takeIf { it.extra[RommCatalog.ALONE] == "1" }?.let(RommCatalog::mainVersion) }
    LaunchedEffect(id, lead?.id) { if (lead != null && picked == null && RommInstalls.entries[id] == null) read(lead, true) }
    val shown = picked ?: item
    val brief = { name: String -> item?.title?.let { name.removePrefix(it).trim() }.orEmpty().ifEmpty { name } }
    VersionActions(shown?.id ?: id, shown, s, a, leadOn) {
        if (item == null || versions.isEmpty()) return@VersionActions
        val label = when {
            asking != null -> stringResource(R.string.stores_romm_version_loading)
            shown != null && RommCatalog.DISCS in shown.extra -> stringResource(R.string.stores_romm_all_discs, brief(shown.extra[RommCatalog.FILE].orEmpty()), RommCatalog.discs(shown).size)
            else -> brief(RommCatalog.versionName(shown ?: item))
        }
        val whole = picked?.takeIf { RommCatalog.DISCS in it.extra }
        BrowseDropdown(label) { first, close ->
            for ((base, discs) in sets) MenuItem(stringResource(R.string.stores_romm_all_discs, brief(base), discs.size), whole?.extra?.get(RommCatalog.FILE) == base) {
                close()
                asking = base
                val app = ctx.applicationContext
                Thread({
                    val (server, token) = RommAuth.both(app)
                    val read = discs.map { d ->
                        if (d.id == item.id) item else RommInstalls.entries[d.id]?.item?.takeIf { RommCatalog.DISCS !in it.extra }
                            ?: RommApi.rom(server, token, d.id).json?.let { RommCatalog.item(server, it) }
                    }
                    val found = if (read.any { it == null }) null else RommCatalog.discSet(base, read.filterNotNull())
                    StoresState.post { if (asking == base) { asking = null; if (found != null) picked = found else unreachable(app) } }
                }, "romm-discs").start()
            }
            RommVersionRow(brief(RommCatalog.versionName(item)), picked == null, item.extra[RommCatalog.MAIN] == "1", first) { picked = null; asking = null; close() }
            for (v in versions) RommVersionRow(brief(v.name), whole == null && picked?.id == v.id, v.main, null) {
                close()
                read(v, false)
            }
        }
    }
}

/** One version of a game in its page's menu; [main] says the account marked it as the game's main version on RomM. */
@Composable
internal fun RommVersionRow(name: String, picked: Boolean, main: Boolean, first: androidx.compose.ui.focus.FocusRequester?, onClick: () -> Unit) =
    MenuItem(name, picked, detail = stringResource(R.string.stores_romm_version_main).takeIf { main }, focusRequester = first, onClick = onClick)

private fun unreachable(context: android.content.Context) =
    android.widget.Toast.makeText(context, R.string.romm_status_unreachable, android.widget.Toast.LENGTH_SHORT).show()

@Composable
private fun VersionActions(id: String, item: CatalogItem?, s: FrontEndState, a: FrontEndActions, leadOn: Modifier, versions: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val key = "${Store.ROMM.id}:$id"
    val raw = StoresState.download(key)
    val download = raw?.takeIf { it.isActive }
    val failed = raw?.takeIf { it.state == DownloadState.FAILED }
    val entry = RommInstalls.entries[id]
    val offered = RommDetails.offered[id].orEmpty()
    val extras = RommDetails.chosen[id].orEmpty().filterTo(LinkedHashSet()) { picked -> offered.any { it.id == picked } }
    val has = remember(entry, offered) { entry?.let { RommInstalls.extras(ctx, it, RommDetails.ownFiles(id), offered) } }
    val own = RommDetails.gameFiles[id].orEmpty()
    val slug = (entry?.item ?: item)?.extra?.get(RommCatalog.PLATFORM).orEmpty()
    val files = remember(own, slug) { own.takeIf { RommPlatforms.choosable(slug, it) }.orEmpty() }
    val left = RommDetails.leftOut[id].orEmpty().filterTo(LinkedHashSet()) { place -> files.any { it.path == place } }
    LaunchedEffect(id, entry != null) { if (entry != null) RommDetails.listFiles(ctx, id) }
    val install = {
        if (item != null && (s.romsDir != null || a.onRomsDefault())) {
            val picked = RommCatalog.withLeft(RommCatalog.withExtras(item, extras), left)
            val bytes = files.filter { it.path !in left }.sumOf { it.size } + offered.filter { it.id in extras }.sumOf { it.size }
            RommBackend.install(ctx, if (left.isEmpty()) picked else picked.copy(sizeBytes = bytes), File(""))
        }
    }
    val uninstall = @Composable {
        ConfirmIconAction(Icons.Outlined.Delete, stringResource(R.string.stores_uninstall), stringResource(R.string.stores_romm_uninstall_confirm), "romm:uninstall") {
            val app = ctx.applicationContext
            Thread({
                RommInstalls.remove(app, id)
                StoresState.notifyLibraryChanged(app)
            }, "romm-uninstall").start()
        }
    }
    Actions {
        when {
            download != null -> {
                SteadyDownloadChip(download)
                if (download.state != DownloadState.PAUSED) Box(leadOn) { IconAction(Icons.Filled.Pause, stringResource(R.string.stores_dl_pause), "romm:pause") { DownloadQueue.pause(download.key) } }
                else PrimaryButton(stringResource(R.string.stores_dl_resume), main = true, icon = Icons.Filled.PlayArrow, modifier = leadOn, iconOnly = s.rommIcons) { DownloadQueue.resume(ctx, download.key) }
                ConfirmIconAction(Icons.Filled.Close, stringResource(R.string.stores_dl_cancel), stringResource(R.string.stores_dl_cancel_confirm), "romm:cancel") { DownloadQueue.cancel(ctx, download.key) }
            }
            entry != null -> {
                val runner = remember(entry) { entry.runner }
                val emulator = s.emulators.firstOrNull { it.id == runner }
                val pkg = Library.packageId(runner)
                when {
                    runner.isEmpty() -> {
                        ActionChip(stringResource(R.string.stores_romm_unsupported), ok = false)
                        SecondaryButton(stringResource(R.string.emu_romm_setup), onClick = a.onEmulatorSetup)
                    }
                    emulator == null || emulator.installed || pkg == null ->
                        PrimaryButton(stringResource(R.string.stores_play), enabled = !s.busy && emulator != null, main = true, icon = Icons.Filled.PlayArrow, modifier = leadOn, iconOnly = s.rommIcons) {
                            Library.launchRom(ctx, runner, entry.guestPath)
                        }
                    else ->
                        PrimaryButton(stringResource(R.string.content_install_named, emulator.name), enabled = !s.busy && s.packageBusyId == null && !s.sessionRunning, main = true, icon = Icons.Filled.Download, modifier = leadOn) {
                            a.onInstallPackage(pkg)
                        }
                }
                uninstall()
                if (failed != null) {
                    ActionChip(stringResource(R.string.stores_romm_extras_failed, failed.error?.takeIf { it.isNotBlank() } ?: stringResource(R.string.stores_dl_failed)), ok = false)
                    IconAction(Icons.Filled.Close, stringResource(R.string.stores_dl_clear), "romm:clear") { DownloadQueue.clear(ctx, failed.key) }
                }
            }
            item == null -> {}
            !item.extra[RommCatalog.UNAVAILABLE].isNullOrEmpty() -> ActionChip(
                stringResource(if (item.extra[RommCatalog.UNAVAILABLE] == RommCatalog.PHYSICAL) R.string.stores_romm_physical else R.string.stores_romm_missing), ok = false,
            )
            failed != null -> {
                ActionChip(stringResource(R.string.stores_dl_failed), ok = false)
                PrimaryButton(stringResource(R.string.stores_dl_resume), main = true, icon = Icons.Filled.Refresh, modifier = leadOn, iconOnly = s.rommIcons) { install() }
                ConfirmIconAction(Icons.Filled.Close, stringResource(R.string.stores_dl_clear), stringResource(R.string.stores_dl_cancel_confirm), "romm:clear") { DownloadQueue.clear(ctx, failed.key) }
            }
            blocked(item) -> {
                ActionChip(
                    if (RommPlatforms.target(item.extra[RommCatalog.PLATFORM].orEmpty()) == null) stringResource(R.string.stores_romm_unsupported)
                    else stringResource(R.string.stores_romm_file_type, RommPlatforms.extension(item.extra[RommCatalog.FILE].orEmpty()).uppercase()),
                    ok = false,
                )
                PrimaryButton(stringResource(R.string.stores_install), main = true, icon = Icons.Filled.Download, modifier = leadOn, iconOnly = s.rommIcons) { install() }
            }
            !s.ready && RommInstaller.fileName(item)?.let(RommPlatforms::extension) == "rar" -> ActionChip(stringResource(R.string.stores_romm_archive_runtime, "rar"), ok = false)
            else -> PrimaryButton(stringResource(R.string.stores_install), main = true, icon = Icons.Filled.Download, modifier = leadOn, iconOnly = s.rommIcons) { install() }
        }
        val folder = entry?.path?.substringBefore('/') ?: item?.let { RommPlatforms.folder(it.extra[RommCatalog.PLATFORM].orEmpty()) }
        val bios = folder?.let(RetroArchCores::firmwareFor)
        val root = s.romsDir
        if (folder != null && bios != null && root != null) {
            val fetched = RommFirmwareState.done
            val dir = remember { File(LinuxRuntime.rootDir(ctx), RetroArchCores.SYSTEM_DIR) }
            if (!remember(dir, entry, fetched) { bios.any { File(dir, it).isFile } }) {
                val slug = item?.extra?.get(RommCatalog.PLATFORM).orEmpty()
                val platformId = item?.extra?.get(RommCatalog.PLATFORM_ID).orEmpty().ifEmpty { RommBrowseState.platforms.firstOrNull { it.slug == slug }?.id.orEmpty() }
                LaunchedEffect(platformId) { RommFirmwareState.check(ctx, slug, platformId) }
                val held = RommFirmwareState.status[platformId]
                when {
                    platformId.isNotEmpty() && RommFirmwareState.working == platformId -> ActionChip(stringResource(R.string.stores_romm_bios_getting), ok = false)
                    held != null && held.missing > 0 -> PrimaryButton(stringResource(R.string.stores_romm_bios_get, held.missing), icon = Icons.Filled.Download) { RommFirmwareState.fetch(ctx, slug, platformId) }
                    else -> ActionChip(
                        stringResource(R.string.cores_bios_needed, bios.joinToString(" / "), stringResource(R.string.cores_system_folder)),
                        ok = false,
                    )
                }
                if (RommFirmwareState.working == null) RommFirmwareState.problemOf(platformId)?.let { ActionChip(it, ok = false) }
            }
        }
        versions()
        if (item != null && download == null && item.extra[RommCatalog.UNAVAILABLE].isNullOrEmpty()) {
            val app = ctx.applicationContext
            if (entry == null && files.isNotEmpty()) RommGameFiles(files, left) { RommDetails.leftOut[id] = it }
            if (offered.isNotEmpty()) when {
                entry == null -> RommExtraFiles(offered, extras) { RommDetails.chosen[id] = it }
                has == null -> ActionChip(stringResource(R.string.stores_romm_extras_reinstall), ok = false)
                else -> RommInstalledExtraFiles(
                    offered, has,
                    add = { file -> RommBackend.addExtras(app, entry.item ?: item, listOf(file)) },
                    remove = { file ->
                        Thread({
                            RommInstalls.dropExtra(app, id, RommDetails.ownFiles(id), file)
                            StoresState.notifyLibraryChanged(app)
                        }, "romm-extra-remove").start()
                    },
                )
            }
        }
        RommDetails.known[id]?.manual?.let { url -> IconAction(RommIcons.Manual, stringResource(R.string.romm_manual), "romm:manual") { RommManual.open(ctx, id, url) } }
        item?.storeUrl?.takeIf { it.isNotEmpty() }?.let { url -> IconAction(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.romm_open_in_romm), "romm:web") { openUrl(ctx, url) } }
        BusyChip(s)
    }
}

/**
 * The game's own files, where it has several that its emulator opens each by itself. The ones unticked stay on the server,
 * and one always stays ticked.
 */
@Composable
private fun RommGameFiles(files: List<RommFile>, left: Set<String>, choose: (Set<String>) -> Unit) {
    val kept = files.filter { it.path !in left }
    BrowseListDropdown(rommGameFilesLabel(kept.size, files.size, kept.sumOf { it.size }), count = files.size) { index, first, _ ->
        files.getOrNull(index)?.let { file ->
            val ticked = file.path !in left
            RommGameFileRow(file, ticked, first) { if (!ticked) choose(left - file.path) else if (kept.size > 1) choose(left + file.path) }
        }
    }
}

/** What the game-files menu is called: how many of the files are chosen when not all are, and with [bytes] how much those are. */
@Composable
internal fun rommGameFilesLabel(chosen: Int, of: Int, bytes: Long = 0): String = listOfNotNull(
    if (chosen == of) stringResource(R.string.stores_romm_files) else stringResource(R.string.stores_romm_files_chosen, chosen, of),
    bytes.takeIf { it > 0 }?.let(::formatBytes),
).joinToString(" · ")

/** One of a game's own files by its place in the game's folder, with its size and [note]. */
@Composable
internal fun RommGameFileRow(file: RommFile, chosen: Boolean, first: androidx.compose.ui.focus.FocusRequester?, note: String? = null, toggle: () -> Unit) =
    MenuItem(
        file.path, chosen, focusRequester = first, onClick = toggle,
        detail = listOfNotNull(file.size.takeIf { it > 0 }?.let(::formatBytes), note).joinToString(" · "),
    )

/** The files RomM keeps with a game beyond the game itself; the ones chosen come with its install. */
@Composable
private fun RommExtraFiles(offered: List<RommFile>, chosen: Set<String>, choose: (Set<String>) -> Unit) {
    val label = if (chosen.isEmpty()) stringResource(R.string.stores_romm_extras) else stringResource(R.string.stores_romm_extras_chosen, chosen.size, offered.size)
    BrowseListDropdown(label, count = offered.size) { index, first, _ ->
        offered.getOrNull(index)?.let { file -> RommExtraFileRow(file, file.id in chosen, first) { choose(if (file.id in chosen) chosen - file.id else chosen + file.id) } }
    }
}

/**
 * The extra files of an installed game, the ones it has ticked. Choosing one it lacks fetches it; one it has is deleted
 * when it is chosen a second time.
 */
@Composable
internal fun RommInstalledExtraFiles(offered: List<RommFile>, has: Set<String>, add: (RommFile) -> Unit, remove: (RommFile) -> Unit) {
    var armed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(armed) { if (armed != null) { kotlinx.coroutines.delay(4000); armed = null } }
    val label = if (has.isEmpty()) stringResource(R.string.stores_romm_extras) else stringResource(R.string.stores_romm_extras_chosen, has.size, offered.size)
    BrowseListDropdown(label, count = offered.size) { index, first, close ->
        offered.getOrNull(index)?.let { file ->
            val there = file.id in has
            RommExtraFileRow(file, there, first, note = stringResource(R.string.stores_romm_extra_remove_confirm).takeIf { armed == file.id }) {
                when {
                    !there -> { close(); add(file) }
                    armed == file.id -> { armed = null; remove(file) }
                    else -> armed = file.id
                }
            }
        }
    }
}

/** One extra file by its place in the game's folder, with what RomM takes it for and its size, or with [note] in their place. */
@Composable
internal fun RommExtraFileRow(file: RommFile, chosen: Boolean, first: androidx.compose.ui.focus.FocusRequester?, note: String? = null, toggle: () -> Unit) =
    MenuItem(
        file.path, chosen, focusRequester = first, onClick = toggle,
        detail = note ?: listOfNotNull(file.category.takeIf { it.isNotEmpty() }, file.size.takeIf { it > 0 }?.let(::formatBytes)).joinToString(" · "),
    )

private fun blocked(item: CatalogItem): Boolean {
    val slug = item.extra[RommCatalog.PLATFORM].orEmpty()
    return if (RommInstaller.fileName(item) == null) RommPlatforms.target(slug) == null else !RommPlatforms.playable(slug, item.extra[RommCatalog.FILE].orEmpty())
}
