package com.droiddeck.launcher.stores.romm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.stores.StoresState

internal object RommFirmwareState {
    class Status(val held: Int, val missing: Int)

    val status = mutableStateMapOf<String, Status>()
    var working by mutableStateOf<String?>(null)
        private set
    private val problems = mutableStateMapOf<String, String>()
    var done by mutableStateOf(0)
        private set

    fun problemOf(platformId: String): String? = problems[platformId]

    private val checking = HashSet<String>()

    fun reset() {
        status.clear()
        problems.clear()
    }

    private fun read(app: Context, slug: String, platformId: String): Pair<List<Pair<RommFirmwareFile, java.io.File>>, Status>? {
        val server = RommAuth.server(app)
        val token = RommAuth.token(app)
        if (server.isEmpty() || token.isEmpty()) return null
        val answer = RommApi.firmware(server, token, platformId)
        val files = RommFirmware.files(answer.array)
        StoresState.logLine("RomM BIOS: platform $platformId answered ${answer.code} with ${files.size} of ${answer.array?.length() ?: 0} files")
        if (!answer.ok) return null
        val root = RommInstalls.romsRoot(app) ?: java.io.File("/nonexistent")
        val rootfs = LinuxRuntime.rootDir(app)
        val placed = files.filter { RommFirmware.directory(root, rootfs, slug, it.name) != null }
        val missing = RommFirmware.missing(placed, root, rootfs, slug)
        return missing to Status(placed.size, missing.size)
    }

    fun check(context: Context, slug: String, platformId: String) {
        if (platformId.isEmpty() || !checking.add(platformId)) return
        val app = context.applicationContext
        Thread({
            val found = read(app, slug, platformId)
            StoresState.post {
                checking.remove(platformId)
                if (found != null) status[platformId] = found.second
            }
        }, "romm-bios-check").start()
    }

    fun fetch(context: Context, slug: String, platformId: String) {
        if (working != null) return
        val app = context.applicationContext
        working = platformId
        problems.remove(platformId)
        Thread({
            val server = RommAuth.server(app)
            val token = RommAuth.token(app)
            var failure: String? = null
            val wanted = read(app, slug, platformId)?.first
            when {
                wanted == null -> failure = app.getString(R.string.romm_status_unreachable)
                RommInstalls.romsRoot(app) == null -> failure = app.getString(R.string.content_choose_roms)
                else -> for ((file, to) in wanted) {
                    if (to.startsWith(LinuxRuntime.rootDir(app)) && !LinuxRuntime.isInstalled(app)) { failure = app.getString(R.string.stores_romm_bios_runtime); continue }
                    when (RommFirmware.fetch(server, token, file, to)) {
                        null -> StoresState.logLine("RomM BIOS: ${file.name} placed in ${to.parentFile?.name}")
                        RommFirmware.Problem.WRITE -> failure = app.getString(R.string.stores_romm_no_write)
                        RommFirmware.Problem.MISMATCH -> failure = app.getString(R.string.stores_romm_bios_mismatch, file.name)
                        RommFirmware.Problem.DOWNLOAD -> failure = app.getString(R.string.stores_romm_bios_failed, file.name)
                    }
                }
            }
            val after = read(app, slug, platformId)?.second
            StoresState.post {
                working = null
                if (failure != null) problems[platformId] = failure
                if (after != null) status[platformId] = after
                done++
            }
        }, "romm-bios").start()
    }
}
