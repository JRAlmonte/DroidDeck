package com.droiddeck.launcher.stores.romm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.frontend.CoverArt
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.StoresState
import java.io.File
import java.util.zip.ZipFile

/**
 * ROM files already on the device that the server also holds. Each is asked for by its hash, and one the server knows is
 * recorded like a game installed from it, so it gets the server's cover, page and save sync without being downloaded again.
 */
internal object RommAdopt {
    private const val MISSED = "adopt_missed"
    private const val GUEST = "/root/ROMs/"
    private val PACKED = setOf("7z", "gz", "tar", "bz2", "rar")

    var busy by mutableStateOf(false)
        private set
    var checked by mutableStateOf(0)
        private set
    var total by mutableStateOf(0)
        private set

    private fun mark(file: File): String = "${file.path}|${file.length()}|${file.lastModified()}"

    /** The ROM files no RomM game is recorded for and the server was not yet asked about as they now are. */
    fun pending(context: Context): List<Library.Rom> {
        val missed = RommPrefs.get(context).getStringSet(MISSED, emptySet()).orEmpty()
        return Library.emulators(context) { false }.flatMap { it.games }.filter { rom ->
            rom.hostPath.isFile && rom.guestPath.startsWith(GUEST) && RommInstalls.byGuestPath(context, rom.guestPath) == null && mark(rom.hostPath) !in missed
        }.distinctBy { it.guestPath }
    }

    /** The hash RomM knows a file by: the CRC of the one file a zip holds, and of any other file its SHA-1. */
    internal fun hash(file: File): String? {
        val type = RommPlatforms.extension(file.name)
        if (type in PACKED) return null
        if (type == "zip") return runCatching {
            ZipFile(file).use { z -> z.entries().asSequence().filter { !it.isDirectory }.toList().singleOrNull()?.takeIf { it.crc >= 0 }?.let { "%08x".format(it.crc) } }
        }.getOrNull()
        if (file.length() == 0L || !RommInstaller.hashedWhole(file, type)) return null
        return Hashes.sha1(file).lowercase()
    }

    private fun cover(context: Context, rom: Library.Rom, url: String?) {
        val image = RommApi.picture(url ?: return).second?.takeIf { it.isNotEmpty() } ?: return
        runCatching { CoverArt.store(context, rom, image) }
    }

    // A matched game is recorded with its launch file alone, so removing it never deletes a folder other games may share.
    /** Asks the server about every pending file and returns how many it knew; stops at the first answer that is no answer. */
    internal fun match(context: Context, roms: List<Library.Rom>, step: (Int) -> Unit = {}): Int {
        val server = RommAuth.server(context)
        val token = RommAuth.token(context)
        if (server.isEmpty() || token.isEmpty()) return 0
        val prefs = RommPrefs.get(context)
        var found = 0
        for ((index, rom) in roms.withIndex()) {
            step(index)
            val relative = rom.guestPath.removePrefix(GUEST)
            val hash = runCatching { hash(rom.hostPath) }.getOrNull()
            // The list's search takes a hash for a term and looks it up by index; `/api/roms/by-hash` reads the whole library and times out on a large one.
            val answer = hash?.let { RommApi.roms(server, token, null, it, 0, limit = 2, sort = null) }
            if (answer != null && !answer.ok) {
                StoresState.logLine("RomM match: stopped at ${index + 1} of ${roms.size}, the server answered ${answer.code}")
                break
            }
            val item = RommCatalog.items(server, answer?.json?.optJSONArray("items")).singleOrNull()
            if (item == null || RommInstalls.find(context, item.id) != null) {
                prefs.edit().putStringSet(MISSED, prefs.getStringSet(MISSED, emptySet()).orEmpty() + mark(rom.hostPath)).apply()
                continue
            }
            RommInstalls.record(context, RommInstalls.Entry(item.id, relative, rom.emulatorId, relative, item))
            cover(context, rom, item.tallImageUrl)
            found++
        }
        StoresState.logLine("RomM match: $found of ${roms.size} files are games the server holds")
        return found
    }

    fun run(context: Context, done: (Int) -> Unit) {
        if (busy) return
        val app = context.applicationContext
        busy = true
        checked = 0
        total = 0
        Thread({
            val roms = runCatching { pending(app) }.getOrDefault(emptyList())
            StoresState.post { total = roms.size }
            val found = runCatching { match(app, roms) { at -> StoresState.post { checked = at } } }.getOrDefault(0)
            if (found > 0) StoresState.notifyLibraryChanged(app)
            StoresState.post { busy = false; done(found) }
        }, "romm-adopt").start()
    }

    fun clear(context: Context) {
        RommPrefs.get(context).edit().remove(MISSED).apply()
    }
}
