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

    /** Whether the file can be installed: a type the emulator lists, or an archive that may hold one. */
    fun playable(slug: String, fileName: String, systems: List<Library.RomSystem> = Library.romSystems()): Boolean =
        target(slug, systems)?.let { extension(fileName) in it.exts || extension(fileName) in ARCHIVES } == true

    val ARCHIVES = setOf("zip", "7z")

    private val released = mapOf(
        "arcade" to 1971, "atari2600" to 1977, "intellivision" to 1979, "dos" to 1981, "atari5200" to 1982, "colecovision" to 1982,
        "c64" to 1982, "zxs" to 1982, "nes" to 1983, "famicom" to 1983, "sg1000" to 1983, "msx" to 1983, "sms" to 1985, "amiga" to 1985,
        "atari7800" to 1986, "fds" to 1986, "tg16" to 1987, "turbografx16--1" to 1987, "turbografx-16-slash-pc-engine" to 1987,
        "genesis" to 1988, "genesis-slash-megadrive" to 1988, "gb" to 1989, "lynx" to 1989, "snes" to 1990, "sfam" to 1990,
        "gamegear" to 1990, "neogeomvs" to 1990, "neogeoaes" to 1990, "segacd" to 1991, "jaguar" to 1993, "3do" to 1993,
        "psx" to 1994, "saturn" to 1994, "sega32" to 1994, "virtualboy" to 1995, "n64" to 1996, "gbc" to 1998, "dc" to 1998,
        "neo-geo-pocket" to 1998, "wonderswan" to 1999, "neo-geo-pocket-color" to 1999, "ps2" to 2000, "wonderswan-color" to 2000,
        "gba" to 2001, "ngc" to 2001, "xbox" to 2001, "nds" to 2004, "psp" to 2004, "xbox360" to 2005, "ps3" to 2006, "wii" to 2006,
        "3ds" to 2011, "psvita" to 2011, "wiiu" to 2012, "ps4" to 2013, "xboxone" to 2013, "switch" to 2017,
    )

    /** The year a platform came out, for ordering; platforms this does not know sort after the ones it does. */
    fun released(slug: String): Int = released[slug.lowercase()] ?: Int.MAX_VALUE

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
