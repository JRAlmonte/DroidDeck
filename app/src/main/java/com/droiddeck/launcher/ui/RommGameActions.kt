package com.droiddeck.launcher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.CoreFetch
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.RetroArchCores
import com.droiddeck.launcher.ports.Port
import com.droiddeck.launcher.ports.Ports
import com.droiddeck.launcher.ports.PortsState
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.romm.RommApi
import com.droiddeck.launcher.stores.romm.RommAuth
import com.droiddeck.launcher.stores.romm.RommBackend
import com.droiddeck.launcher.stores.romm.RommCatalog
import com.droiddeck.launcher.stores.romm.RommInstaller
import com.droiddeck.launcher.stores.romm.RommInstalls
import com.droiddeck.launcher.stores.romm.RommPlatforms
import com.droiddeck.launcher.stores.romm.RommVersion
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The game's actions, with a menu of its other versions when RomM groups several; the buttons act on the version chosen there. */
@Composable
internal fun RommGameActions(id: String, item: CatalogItem?, s: FrontEndState, a: FrontEndActions, leadOn: Modifier) {
    val ctx = LocalContext.current
    val versions = remember(item) { item?.let(RommCatalog::versions).orEmpty() }
    var picked by remember(id) {
        val own = RommInstalls.entries[id]
        mutableStateOf(if (own != null) own.item?.takeIf { RommCatalog.DISCS in it.extra } else versions.firstNotNullOfOrNull { RommInstalls.entries[it.id]?.item })
    }
    val sets = remember(item) {
        if (item == null || RommPlatforms.target(item.extra[RommCatalog.PLATFORM].orEmpty())?.exts?.contains("m3u") != true) emptyList()
        else RommCatalog.discSets(listOf(RommVersion(item.id, RommCatalog.versionName(item))) + versions)
    }
    var asking by remember(id) { mutableStateOf<String?>(null) }
    val shown = picked ?: item
    VersionActions(shown?.id ?: id, shown, s, a, leadOn) {
        if (item == null || versions.isEmpty()) return@VersionActions
        val label = when {
            asking != null -> stringResource(R.string.stores_romm_version_loading)
            shown != null && RommCatalog.DISCS in shown.extra -> stringResource(R.string.stores_romm_all_discs, shown.extra[RommCatalog.FILE].orEmpty(), RommCatalog.discs(shown).size)
            else -> RommCatalog.versionName(shown ?: item)
        }
        val whole = picked?.takeIf { RommCatalog.DISCS in it.extra }
        BrowseDropdown(label) { first, close ->
            for ((base, discs) in sets) MenuItem(stringResource(R.string.stores_romm_all_discs, base, discs.size), whole?.extra?.get(RommCatalog.FILE) == base) {
                close()
                asking = base
                val app = ctx.applicationContext
                Thread({
                    val server = RommAuth.server(app)
                    val token = RommAuth.token(app)
                    val read = discs.map { d ->
                        if (d.id == item.id) item else RommInstalls.entries[d.id]?.item?.takeIf { RommCatalog.DISCS !in it.extra }
                            ?: RommApi.rom(server, token, d.id).json?.let { RommCatalog.item(server, it) }
                    }
                    val found = if (read.any { it == null }) null else RommCatalog.discSet(base, read.filterNotNull())
                    StoresState.post { if (asking == base) { asking = null; if (found != null) picked = found } }
                }, "romm-discs").start()
            }
            MenuItem(RommCatalog.versionName(item), picked == null, focusRequester = first) { picked = null; asking = null; close() }
            for (v in versions) MenuItem(v.name, whole == null && picked?.id == v.id) {
                close()
                val kept = RommInstalls.entries[v.id]?.item
                if (kept != null) { picked = kept; asking = null; return@MenuItem }
                asking = v.id
                val app = ctx.applicationContext
                Thread({
                    val server = RommAuth.server(app)
                    val found = RommApi.rom(server, RommAuth.token(app), v.id).json?.let { RommCatalog.item(server, it) }
                    StoresState.post { if (asking == v.id) { asking = null; if (found != null) picked = found } }
                }, "romm-version").start()
            }
        }
    }
}

@Composable
private fun VersionActions(id: String, item: CatalogItem?, s: FrontEndState, a: FrontEndActions, leadOn: Modifier, versions: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val key = "${Store.ROMM.id}:$id"
    val raw = StoresState.download(key)
    val download = raw?.takeIf { it.isActive }
    val failed = raw?.takeIf { it.state == DownloadState.FAILED }
    val entry = RommInstalls.entries[id]
    val install = { if (item != null) RommBackend.install(ctx, item, File("")) }
    val refused = item?.let { RommPlatforms.refusedBy(it.extra[RommCatalog.PLATFORM].orEmpty(), it.extra[RommCatalog.FILE].orEmpty(), it.extra[RommCatalog.SHA1].orEmpty()) }
    val uninstall = @Composable {
        ConfirmButton(stringResource(R.string.stores_uninstall), stringResource(R.string.stores_romm_uninstall_confirm)) {
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
                if (download.state != DownloadState.PAUSED) SecondaryButton(stringResource(R.string.stores_dl_pause), modifier = leadOn) { DownloadQueue.pause(download.key) }
                else PrimaryButton(stringResource(R.string.stores_dl_resume), main = true, modifier = leadOn) { DownloadQueue.resume(ctx, download.key) }
                ConfirmButton(stringResource(R.string.stores_dl_cancel), stringResource(R.string.stores_dl_cancel_confirm)) { DownloadQueue.cancel(ctx, download.key) }
            }
            entry != null -> {
                val portOnly = Ports.byTarget(entry.emulatorId)
                val folder = entry.path.substringBefore('/').lowercase()
                val candidates = remember(entry.path, PortsState.sources) { Ports.all.filter { folder in it.systemFolders } }
                val checked by produceState<Pair<Boolean, Port?>>(candidates.isEmpty() to null, entry.path, candidates) {
                    if (candidates.isEmpty()) { value = true to null; return@produceState }
                    value = true to withContext(Dispatchers.IO) {
                        RommInstalls.romsRoot(ctx)?.let { root -> candidates.firstOrNull { PortsState.accepts(ctx, it, File(root, entry.path)) } }
                    }
                    PortsState.refresh(ctx)
                }
                val port = checked.second
                val accepted = if (checked.first) port != null else null
                val emulator = s.emulators.firstOrNull { it.id == entry.emulatorId }
                when {
                    accepted == null -> ActionChip(stringResource(R.string.ports_checking_rom), ok = false)
                    portOnly != null && accepted == false -> ActionChip(stringResource(R.string.ports_wrong_rom, portOnly.name), ok = false)
                    else -> RunWithActions(entry.guestPath, if (portOnly == null) emulator?.name ?: entry.emulatorId else null, port, s, leadOn) {
                        val pkg = Library.packageId(entry.emulatorId)
                        if (emulator == null || emulator.installed || pkg == null) {
                            PrimaryButton(stringResource(R.string.stores_play), enabled = !s.busy && emulator != null, main = true, icon = Icons.Filled.PlayArrow, modifier = leadOn) {
                                Library.launchRom(ctx, entry.emulatorId, entry.guestPath)
                            }
                        } else {
                            PrimaryButton(stringResource(R.string.content_install_named, emulator.name), enabled = !s.busy && s.packageBusyId == null && !s.sessionRunning, main = true, modifier = leadOn) {
                                a.onInstallPackage(pkg)
                            }
                        }
                    }
                }
                if (accepted == false) AddPortButton(item?.title ?: entry.path.substringAfterLast('/')) { RommInstalls.romsRoot(ctx)?.let { File(it, entry.path) } }
                uninstall()
            }
            item == null -> {}
            failed != null -> {
                ActionChip(stringResource(R.string.stores_dl_failed), ok = false)
                PrimaryButton(stringResource(R.string.stores_dl_resume), main = true, modifier = leadOn) { install() }
                ConfirmButton(stringResource(R.string.stores_dl_clear), stringResource(R.string.stores_dl_cancel_confirm), compact = true) { DownloadQueue.clear(ctx, failed.key) }
            }
            s.romsDir == null -> {
                PrimaryButton(stringResource(R.string.roms_choose), main = true, modifier = leadOn, onClick = a.onRoms)
                ActionChip(stringResource(R.string.content_choose_roms), ok = false)
            }
            blocked(item) -> {
                val fetched = CoreFetch.done
                val wanting = remember(item.id, fetched) { RetroArchCores.missing(RommPlatforms.folder(item.extra[RommCatalog.PLATFORM].orEmpty())) }
                val retroArch = s.emulators.firstOrNull { it.id == "retroarch" }
                when {
                    wanting == null -> ActionChip(
                        if (RommPlatforms.target(item.extra[RommCatalog.PLATFORM].orEmpty()) == null) stringResource(R.string.stores_romm_unsupported)
                        else stringResource(R.string.stores_romm_file_type, RommPlatforms.extension(item.extra[RommCatalog.FILE].orEmpty()).uppercase()),
                        ok = false,
                    )
                    retroArch?.installed != true -> {
                        PrimaryButton(stringResource(R.string.content_install_named, "RetroArch"), enabled = !s.busy && s.packageBusyId == null && !s.sessionRunning, main = true, modifier = leadOn) {
                            Library.packageId("retroarch")?.let(a.onInstallPackage)
                        }
                        ActionChip(stringResource(R.string.cores_needed, wanting.name), ok = false)
                    }
                    CoreFetch.working -> ActionChip(CoreFetch.stage ?: stringResource(R.string.cores_getting), ok = false)
                    else -> {
                        PrimaryButton(stringResource(R.string.cores_get, wanting.name), enabled = s.ready && !s.busy && !s.sessionRunning, main = true, modifier = leadOn) { CoreFetch.start(ctx) }
                        if (CoreFetch.failed) ActionChip(stringResource(R.string.cores_failed), ok = false)
                    }
                }
            }
            refused != null -> ActionChip(stringResource(R.string.ports_wrong_rom, refused.name), ok = false)
            !s.ready && RommInstaller.fileName(item)?.let(RommPlatforms::extension) == "rar" -> ActionChip(stringResource(R.string.stores_romm_archive_runtime, "rar"), ok = false)
            else -> PrimaryButton(
                if (item.sizeBytes > 0) stringResource(R.string.stores_install_size, formatBytes(item.sizeBytes)) else stringResource(R.string.stores_install),
                main = true, modifier = leadOn,
            ) { install() }
        }
        val folder = entry?.path?.substringBefore('/') ?: item?.let { RommPlatforms.folder(it.extra[RommCatalog.PLATFORM].orEmpty()) }
        val bios = folder?.let(RetroArchCores::firmwareFor)
        val root = s.romsDir
        if (folder != null && bios != null && root != null) {
            val dir = remember(root, folder, entry) { File(root).listFiles { f -> f.isDirectory && f.name.equals(folder, ignoreCase = true) }?.firstOrNull() ?: File(root, folder) }
            if (!remember(dir, entry) { File(dir, bios).isFile }) ActionChip(stringResource(R.string.cores_bios_needed, bios, "ROMs/${dir.name}"), ok = false)
        }
        versions()
        BusyChip(s)
    }
}

/** Whether nothing here can take the game: no emulator for its platform, or a single file of a type that emulator does not open. */
private fun blocked(item: CatalogItem): Boolean {
    val slug = item.extra[RommCatalog.PLATFORM].orEmpty()
    return if (RommInstaller.fileName(item) == null) RommPlatforms.target(slug) == null else !RommPlatforms.playable(slug, item.extra[RommCatalog.FILE].orEmpty())
}
