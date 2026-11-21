package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.ports.Ports
import java.io.File

internal object RommPlatforms {
    class Target(val emulatorId: String, val folders: List<String>, val exts: Set<String>, val folder: String)

    private val folderForSlug = mapOf(
        "ngc" to "gamecube",
        "sfam" to "sfc",
        "wonderswan-color" to "wonderswancolor",
        "genesis-slash-megadrive" to "genesis",
        "turbografx16--1" to "tg16",
        "turbografx-16-slash-pc-engine" to "pcengine",
    )

    fun target(slug: String, systems: List<Library.RomSystem> = Library.romSystems()): Target? {
        val folder = folderForSlug[slug] ?: slug.lowercase()
        systems.firstOrNull { folder in it.folders }?.let { return Target(it.emulatorId, it.folders, it.exts, folder) }
        val port = Ports.forFolder(folder) ?: return null
        return Target(Ports.PREFIX + port.id, port.systemFolders, port.exts, folder)
    }

    fun extension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    /** Whether the file can be installed: a type the emulator lists, or a zip that may hold one. */
    fun playable(slug: String, fileName: String, systems: List<Library.RomSystem> = Library.romSystems()): Boolean =
        target(slug, systems)?.let { extension(fileName) in it.exts || extension(fileName) == ARCHIVE } == true

    const val ARCHIVE = "zip"

    private val sheets = listOf("m3u", "cue", "gdi")

    /** The file to launch among unpacked names: a disc sheet when there is one, else the first type the emulator lists. */
    fun mainFile(names: List<String>, exts: Set<String>): String? {
        val known = names.filter { extension(it) in exts }.sortedBy { it.lowercase() }
        return sheets.firstNotNullOfOrNull { sheet -> known.firstOrNull { extension(it) == sheet } } ?: known.firstOrNull()
    }

    /** The system folder to download into: one the user already has under any accepted name, else the default name. */
    fun directory(romsRoot: File, target: Target): File =
        romsRoot.listFiles { f -> f.isDirectory && f.name.lowercase() in target.folders }?.minByOrNull { it.name.lowercase() != target.folder }
            ?: File(romsRoot, target.folder)
}
