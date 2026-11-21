package com.droiddeck.launcher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
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
            entry != null && Ports.byTarget(entry.emulatorId) != null -> {
                val port = Ports.byTarget(entry.emulatorId)!!
                val accepted by produceState<Boolean?>(null, entry.path) {
                    value = withContext(Dispatchers.IO) { RommInstalls.romsRoot(ctx)?.let { PortsState.accepts(ctx, port, File(it, entry.path)) } == true }
                    PortsState.refresh(ctx)
                }
                when (accepted) {
                    null -> ActionChip(stringResource(R.string.ports_checking_rom), ok = false)
                    false -> ActionChip(stringResource(R.string.ports_wrong_rom, port.name), ok = false)
                    else -> PortActions(port, s, manage = false, leadOn = leadOn)
                }
                uninstall()
            }
            entry != null -> {
                val emulator = s.emulators.firstOrNull { it.id == entry.emulatorId }
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
            !RommPlatforms.playable(item.extra[RommCatalog.PLATFORM].orEmpty(), item.extra[RommCatalog.FILE].orEmpty()) ->
                ActionChip(stringResource(R.string.stores_romm_unsupported), ok = false)
            else -> PrimaryButton(
                if (item.sizeBytes > 0) stringResource(R.string.stores_install_size, formatBytes(item.sizeBytes)) else stringResource(R.string.stores_install),
                main = true, modifier = leadOn,
            ) { install() }
        }
        BusyChip(s)
    }
}
