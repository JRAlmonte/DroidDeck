package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.frontend.Library
import java.io.File

internal object RommPlatforms {
    class Target(val emulatorId: String, val folders: List<String>, val exts: Set<String>, val folder: String)

    private val folderForSlug = mapOf(
        "ngc" to "gamecube",
        "sfam" to "sfc",
        "wonderswan-color" to "wonderswancolor",
    )

    fun target(slug: String, systems: List<Library.RomSystem> = Library.romSystems()): Target? {
        val folder = folderForSlug[slug] ?: slug.lowercase()
        val system = systems.firstOrNull { folder in it.folders } ?: return null
        return Target(system.emulatorId, system.folders, system.exts, folder)
    }

    fun playable(slug: String, fileName: String, systems: List<Library.RomSystem> = Library.romSystems()): Boolean =
        target(slug, systems)?.let { fileName.substringAfterLast('.', "").lowercase() in it.exts } == true

    /** The system folder to download into: one the user already has under any accepted name, else the default name. */
    fun directory(romsRoot: File, target: Target): File =
        romsRoot.listFiles { f -> f.isDirectory && f.name.lowercase() in target.folders }?.minByOrNull { it.name.lowercase() != target.folder }
            ?: File(romsRoot, target.folder)
}
