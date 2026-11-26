package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.frontend.Library
import java.io.File

internal object RommPlatforms {
    class Target(val emulatorId: String, val folders: List<String>, val exts: Set<String>, val folder: String) {
        val held: Boolean get() = emulatorId.isEmpty()
    }

    private val folderForSlug = mapOf(
        "ngc" to "gamecube",
        "sfam" to "sfc",
        "wonderswan-color" to "wonderswancolor",
        "genesis-slash-megadrive" to "genesis",
        "turbografx16--1" to "tg16",
        "turbografx-16-slash-pc-engine" to "pcengine",
        "turbografx-16-slash-pc-engine-cd" to "pcenginecd",
        "turbografx-cd" to "pcenginecd",
    )

    fun folder(slug: String): String = folderForSlug[slug] ?: slug.lowercase()

    fun target(slug: String, systems: List<Library.RomSystem> = Library.romSystems()): Target? {
        val folder = folder(slug)
        return systems.firstOrNull { folder in it.folders }?.let { Target(it.emulatorId, it.folders, it.exts, folder) }
    }

    /** Where a game nothing here plays is kept, as the server sent it. */
    fun held(slug: String): Target = folder(slug).let { Target("", listOf(it), emptySet(), it) }

    /**
     * What runs an installed game: the emulator it was installed for, unless the user picked another for its folder or that
     * emulator is gone. A game kept while nothing played it takes what plays its file now, and "" while nothing does.
     */
    fun runner(
        entry: RommInstalls.Entry, systems: List<Library.RomSystem> = Library.romSystems(),
        choices: Map<String, String> = com.droiddeck.launcher.frontend.EmulatorLinks.choices,
    ): String {
        val slug = entry.item?.extra?.get(RommCatalog.PLATFORM)?.takeIf { it.isNotEmpty() } ?: entry.path.substringBefore('/')
        val now = target(slug, systems)?.takeIf { extension(entry.path.substringAfterLast('/')) in it.exts }?.emulatorId.orEmpty()
        val kept = entry.emulatorId
        return if (kept.isEmpty() || !Library.runs(kept) || (folder(slug) in choices && now.isNotEmpty())) now else kept
    }

    fun extension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    fun playable(slug: String, fileName: String, systems: List<Library.RomSystem> = Library.romSystems()): Boolean =
        target(slug, systems)?.let { extension(fileName) in it.exts || extension(fileName) in ARCHIVES } == true

    val ARCHIVES = setOf("zip", "7z", "rar")

    private val released = mapOf(
        "arcade" to 1971, "atari2600" to 1977, "intellivision" to 1979, "dos" to 1981, "atari5200" to 1982, "colecovision" to 1982,
        "c64" to 1982, "zxs" to 1982, "nes" to 1983, "famicom" to 1983, "sg1000" to 1983, "msx" to 1983, "sms" to 1985, "amiga" to 1985,
        "atari7800" to 1986, "fds" to 1986, "tg16" to 1987, "turbografx16--1" to 1987, "turbografx-16-slash-pc-engine" to 1987,
        "turbografx-16-slash-pc-engine-cd" to 1988, "turbografx-cd" to 1988,
        "genesis" to 1988, "genesis-slash-megadrive" to 1988, "gb" to 1989, "lynx" to 1989, "snes" to 1990, "sfam" to 1990,
        "gamegear" to 1990, "neogeomvs" to 1990, "neogeoaes" to 1990, "segacd" to 1991, "jaguar" to 1993, "3do" to 1993,
        "psx" to 1994, "saturn" to 1994, "sega32" to 1994, "virtualboy" to 1995, "n64" to 1996, "gbc" to 1998, "dc" to 1998,
        "neo-geo-pocket" to 1998, "wonderswan" to 1999, "neo-geo-pocket-color" to 1999, "ps2" to 2000, "wonderswan-color" to 2000,
        "gba" to 2001, "ngc" to 2001, "xbox" to 2001, "nds" to 2004, "psp" to 2004, "xbox360" to 2005, "ps3" to 2006, "wii" to 2006,
        "3ds" to 2011, "psvita" to 2011, "wiiu" to 2012, "ps4" to 2013, "xboxone" to 2013, "switch" to 2017,
    )

    fun released(slug: String): Int = released[slug.lowercase()] ?: 0

    private val sheets = listOf("m3u", "cue", "gdi")

    fun mainFile(names: List<String>, exts: Set<String>): String? {
        val known = names.filter { extension(it) in exts }.sortedBy { it.lowercase() }
        return sheets.firstNotNullOfOrNull { sheet -> known.firstOrNull { extension(it) == sheet } } ?: known.firstOrNull()
    }

    private val disc = Regex("(?i)[(\\[ _-](disc|disk|cd)[ _-]?\\d+")

    fun discs(names: List<String>, exts: Set<String>): List<String> {
        if ("m3u" !in exts || names.any { extension(it) == "m3u" }) return emptyList()
        val named = names.filter { extension(it) in exts && disc.containsMatchIn(it.substringAfterLast('/')) }.sortedBy { it.lowercase() }
        val sheeted = named.filter { extension(it) in sheets }
        return (if (sheeted.isNotEmpty()) sheeted else named).takeIf { it.size > 1 }.orEmpty()
    }

    fun directory(romsRoot: File, target: Target): File =
        romsRoot.listFiles { f -> f.isDirectory && f.name.lowercase() in target.folders }?.minByOrNull { it.name.lowercase() != target.folder }
            ?: File(romsRoot, target.folder)
}
