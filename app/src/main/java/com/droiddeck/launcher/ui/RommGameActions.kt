package com.droiddeck.launcher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.droiddeck.launcher.stores.romm.RommBackend
import com.droiddeck.launcher.stores.romm.RommCatalog
import com.droiddeck.launcher.stores.romm.RommInstaller
import com.droiddeck.launcher.stores.romm.RommInstalls
import com.droiddeck.launcher.stores.romm.RommPlatforms
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun RommGameActions(id: String, item: CatalogItem?, s: FrontEndState, a: FrontEndActions, leadOn: Modifier) {
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
            RommInstaller.fileName(item) == null -> ActionChip(stringResource(R.string.stores_romm_multi_file), ok = false)
            !RommPlatforms.playable(item.extra[RommCatalog.PLATFORM].orEmpty(), item.extra[RommCatalog.FILE].orEmpty()) -> {
                val fetched = CoreFetch.done
                val wanting = remember(item.id, fetched) { RetroArchCores.missing(RommPlatforms.folder(item.extra[RommCatalog.PLATFORM].orEmpty())) }
                val retroArch = s.emulators.firstOrNull { it.id == "retroarch" }
                when {
                    wanting == null -> ActionChip(stringResource(R.string.stores_romm_unsupported), ok = false)
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
            else -> PrimaryButton(
                if (item.sizeBytes > 0) stringResource(R.string.stores_install_size, formatBytes(item.sizeBytes)) else stringResource(R.string.stores_install),
                main = true, modifier = leadOn,
            ) { install() }
        }
        BusyChip(s)
    }
}
