package com.droiddeck.launcher.ports

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.playback.SoundtrackController
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import java.io.File

object PortsState {
    private val main = Handler(Looper.getMainLooper())

    var roms by mutableStateOf<Map<String, File>>(emptyMap())
        private set
    var installed by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var working by mutableStateOf<String?>(null)
        private set
    var stage by mutableStateOf<String?>(null)
        private set
    var percent by mutableStateOf(-1)
        private set
    var errors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    private var scan = 0

    val shown: List<Port> get() = Ports.all.filter { it.id in roms || it.id in installed }

    private fun cache(app: Context) = PortRoms.HashCache(File(app.filesDir, "ports/hashes.tsv"))

    private fun romsRoot(app: Context): File? = SessionPrefs.romsDir(app).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isDirectory }

    fun refresh(context: Context) {
        val app = context.applicationContext
        val seq = ++scan
        Thread({
            if (PortCatalog.refresh(app)) Ports.use(PortCatalog.cached(app))
            val tags = Ports.all.mapNotNull { p -> PortInstaller.installedTag(app, p)?.let { p.id to it } }.toMap()
            main.post { if (seq == scan) installed = tags }
            val found = synchronized(this) { runCatching { PortRoms.scan(romsRoot(app), Ports.all, cache(app)) }.getOrDefault(emptyMap()) }
            main.post { if (seq == scan) roms = found }
        }, "ports-scan").start()
    }

    /** Whether this ROM file is the one the port takes; hashes the file, so call it off the main thread. */
    fun accepts(context: Context, port: Port, rom: File): Boolean {
        val app = context.applicationContext
        return synchronized(this) { cache(app).let { c -> PortRoms.matches(port, rom, c).also { c.save() } } }
    }

    private fun fail(port: Port, reason: String?) {
        errors = if (reason == null) errors - port.id else errors + (port.id to reason)
    }

    fun install(context: Context, port: Port) {
        val rom = roms[port.id] ?: return
        if (working != null) return
        val app = context.applicationContext
        working = port.id; stage = null; percent = -1; fail(port, null)
        Thread({
            val problem = runCatching {
                PortInstaller.install(app, port, rom) { text, pct -> main.post { stage = text; percent = pct } }
            }.getOrElse { it.message ?: it.toString() }
            main.post { working = null; stage = null; percent = -1; fail(port, problem) }
            refresh(app)
        }, "port-install").start()
    }

    fun remove(context: Context, port: Port) {
        if (working != null) return
        val app = context.applicationContext
        working = port.id; stage = null; percent = -1; fail(port, null)
        Thread({
            runCatching { PortInstaller.remove(app, port) }
            main.post { working = null }
            refresh(app)
        }, "port-remove").start()
    }

    fun launch(context: Context, port: Port) {
        val app = context.applicationContext
        val rom = roms[port.id]
        Thread({
            if (!PortInstaller.hasStagedRom(app, port)) {
                val staged = rom != null && PortInstaller.stageRom(port, rom, PortInstaller.dir(app, port))
                if (!staged) {
                    main.post { fail(port, app.getString(R.string.ports_rom_missing, port.game)) }
                    return@Thread
                }
            }
            main.post {
                if (!SessionPrefs.soundtracksIngame(app)) SoundtrackController.pause(app)
                context.startActivity(Intent(context, SessionActivity::class.java)
                    .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
                    .putExtra(SessionService.EXTRA_PROGRAM, port.program)
                    .putExtra(SessionService.EXTRA_PROGRAM_ARGS, emptyArray<String>()))
            }
        }, "port-launch").start()
    }
}
