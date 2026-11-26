package com.droiddeck.launcher.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.frontend.EmulatorInventory
import com.droiddeck.launcher.frontend.EmulatorLinks
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.romm.RommApi
import com.droiddeck.launcher.stores.romm.RommAuth
import com.droiddeck.launcher.stores.romm.RommBrowseState
import com.droiddeck.launcher.stores.romm.RommCatalog
import com.droiddeck.launcher.stores.romm.RommPlatform
import com.droiddeck.launcher.stores.romm.RommPlatforms
import java.io.File

internal object EmulatorSetupState {
    private val main = Handler(Looper.getMainLooper())
    private var seq = 0
    @Volatile private var fetched: List<RommPlatform>? = null

    var result by mutableStateOf<EmulatorInventory.Result?>(null)
        private set

    private const val ROMS = "/root/ROMs/"

    internal fun games(emulators: List<Library.Emulator>): Map<String, Int> =
        emulators.flatMap { it.games }.mapNotNull { rom ->
            rom.guestPath.takeIf { it.startsWith(ROMS) }?.removePrefix(ROMS)?.takeIf { '/' in it }?.substringBefore('/')?.lowercase()
        }.groupingBy { it }.eachCount()

    internal fun input(context: Context, emulators: List<Library.Emulator>, platforms: List<RommPlatform>) = EmulatorInventory.Input(
        packages = emulators.filter { it.installed }.mapNotNull { Library.packageId(it.id) }.toSet(),
        rootfs = LinuxRuntime.rootDir(context).takeIf { it.isDirectory },
        romsRoot = SessionPrefs.romsDir(context).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isDirectory },
        games = games(emulators),
        platforms = platforms.map { EmulatorInventory.Platform(RommPlatforms.folder(it.slug), it.name, it.count, it.id, it.slug) },
    )

    fun refresh(context: Context, emulators: List<Library.Emulator>) {
        val app = context.applicationContext
        val mine = ++seq
        val known = RommBrowseState.platforms.ifEmpty { fetched.orEmpty() }
        Thread({
            val first = EmulatorInventory.build(input(app, emulators, known))
            main.post { if (mine == seq) result = first }
            if (known.isNotEmpty()) return@Thread
            val server = RommAuth.server(app)
            val token = RommAuth.token(app)
            if (server.isEmpty() || token.isEmpty()) return@Thread
            val listed = runCatching { RommCatalog.platforms(RommApi.platforms(server, token).array) }.getOrNull().orEmpty()
            if (listed.isEmpty()) return@Thread
            fetched = listed
            val second = EmulatorInventory.build(input(app, emulators, listed))
            main.post { if (mine == seq) result = second }
        }, "emulator-inventory").start()
    }

    fun copy(need: EmulatorInventory.Need, done: () -> Unit) {
        Thread({
            for ((from, to) in need.copies) runCatching {
                to.parentFile?.mkdirs()
                from.copyTo(to, overwrite = true)
            }
            main.post(done)
        }, "emulator-files").start()
    }

    fun choose(context: Context, row: EmulatorInventory.SystemRow, key: String) {
        val value = key.takeIf { it != row.candidates.firstOrNull()?.key }
        row.folders.forEach { EmulatorLinks.choose(context, it, value) }
    }
}
