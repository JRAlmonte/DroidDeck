package com.droiddeck.launcher.frontend

/** The systems RetroArch plays from the ROMs folder, each with the core that droiddeck-emulators installs for it. */
object RetroArchCores {
    class System(val name: String, val folders: List<String>, val exts: Set<String>, val core: String, val thumbnails: String, val optional: Boolean = false)

    /** BIOS files the games of a system cannot start without: any one of [files], read from beside the games or from RetroArch's system folder. */
    class Firmware(val files: List<String>, val besideGames: Boolean)

    const val CORES_DIR = "/usr/lib/aarch64-linux-gnu/libretro"

    /** RetroArch's system folder inside the runtime, which droiddeck-pad-defaults pins. */
    const val SYSTEM_DIR = "root/.config/retroarch/system"

    private const val ROMS = "/root/ROMs/"

    private val all = listOf(
        System("NES", listOf("nes", "famicom"), setOf("nes", "fds", "unf", "unif", "zip"), "nestopia", "Nintendo_-_Nintendo_Entertainment_System"),
        System("SNES", listOf("snes", "sfc"), setOf("sfc", "smc", "fig", "swc", "zip"), "snes9x", "Nintendo_-_Super_Nintendo_Entertainment_System"),
        System("Game Boy", listOf("gb"), setOf("gb", "zip"), "gambatte", "Nintendo_-_Game_Boy"),
        System("Game Boy Color", listOf("gbc"), setOf("gbc", "gb", "zip"), "gambatte", "Nintendo_-_Game_Boy_Color"),
        System("Game Boy Advance", listOf("gba"), setOf("gba", "zip"), "mgba", "Nintendo_-_Game_Boy_Advance"),
        System("Genesis", listOf("genesis", "megadrive", "md"), setOf("md", "gen", "smd", "bin", "zip"), "genesis_plus_gx", "Sega_-_Mega_Drive_-_Genesis"),
        System("Master System", listOf("mastersystem", "sms"), setOf("sms", "zip"), "genesis_plus_gx", "Sega_-_Master_System_-_Mark_III"),
        System("Game Gear", listOf("gamegear", "gg"), setOf("gg", "zip"), "genesis_plus_gx", "Sega_-_Game_Gear"),
        System("SG-1000", listOf("sg1000"), setOf("sg", "zip"), "genesis_plus_gx", "Sega_-_SG-1000"),
        System("PC Engine", listOf("pcengine", "pce", "tg16"), setOf("pce", "zip"), "mednafen_pce_fast", "NEC_-_PC_Engine_-_TurboGrafx_16"),
        System("Sega CD", listOf("segacd", "megacd"), setOf("cue", "chd", "iso", "m3u"), "genesis_plus_gx", "Sega_-_Mega-CD_-_Sega_CD"),
        System("PC Engine CD", listOf("pcenginecd", "pcecd", "tgcd"), setOf("cue", "chd", "ccd", "m3u"), "mednafen_pce_fast", "NEC_-_PC_Engine_CD_-_TurboGrafx-CD"),
        System("Virtual Boy", listOf("virtualboy", "vb"), setOf("vb", "vboy", "zip"), "mednafen_vb", "Nintendo_-_Virtual_Boy"),
        System("WonderSwan", listOf("wonderswan", "wswan"), setOf("ws", "zip"), "mednafen_wswan", "Bandai_-_WonderSwan"),
        System("WonderSwan Color", listOf("wonderswancolor", "wsc"), setOf("wsc", "zip"), "mednafen_wswan", "Bandai_-_WonderSwan_Color"),
        System("Nintendo 64", listOf("n64"), setOf("z64", "n64", "v64"), "mupen64plus_next", "Nintendo_-_Nintendo_64", optional = true),
        System("Dreamcast", listOf("dc", "dreamcast"), setOf("chd", "cdi", "gdi", "cue", "m3u"), "flycast", "Sega_-_Dreamcast", optional = true),
        System("Arcade", listOf("arcade", "fbneo"), setOf("zip", "7z"), "fbneo", "FBNeo_-_Arcade_Games", optional = true),
        System("Neo Geo", listOf("neogeo", "neogeoaes", "neogeomvs"), setOf("zip", "7z"), "fbneo", "SNK_-_Neo_Geo", optional = true),
        System("Atari 2600", listOf("atari2600"), setOf("a26", "bin"), "stella", "Atari_-_2600", optional = true),
        System("Atari 5200", listOf("atari5200"), setOf("a52", "bin"), "a5200", "Atari_-_5200", optional = true),
        System("Atari 7800", listOf("atari7800"), setOf("a78", "bin"), "prosystem", "Atari_-_7800", optional = true),
        System("Jaguar", listOf("jaguar", "atarijaguar"), setOf("j64", "jag", "rom", "abs", "cof", "bin"), "virtualjaguar", "Atari_-_Jaguar", optional = true),
    )

    /** The runtime's libretro folder; a core droiddeck-emulators may not have fetched counts only when its file is there. */
    @Volatile var coresDir: java.io.File? = null

    val systems: List<System> get() = all.filter { !it.optional || coresDir?.let { dir -> java.io.File(dir, "${it.core}_libretro.so").isFile } == true }

    val folders: Set<String> = all.flatMap { it.folders }.toSet()

    /** Arcade BIOS sets FinalBurn Neo reads from beside the games; they are not games themselves. */
    val firmwareFiles = setOf("neogeo.zip", "neocdz.zip", "pgm.zip", "decocass.zip", "skns.zip", "ym2608.zip")

    private val firmware = mapOf(
        "Neo Geo" to Firmware(listOf("neogeo.zip"), besideGames = true),
        "Sega CD" to Firmware(listOf("bios_CD_U.bin", "bios_CD_E.bin", "bios_CD_J.bin"), besideGames = false),
        "PC Engine CD" to Firmware(listOf("syscard3.pce"), besideGames = false),
    )

    /** The BIOS the games in this folder cannot start without. */
    fun firmwareFor(folder: String): Firmware? = folder.lowercase().let { name -> all.firstOrNull { name in it.folders }?.let { firmware[it.name] } }

    /** The system of this folder when its core has not been fetched yet. */
    fun missing(name: String): System? = name.lowercase().let { folder -> all.firstOrNull { folder in it.folders && it !in systems } }

    fun forFolder(name: String): System? = name.lowercase().let { folder -> systems.firstOrNull { folder in it.folders } }

    fun forGuestPath(guestPath: String): System? =
        if (guestPath.startsWith(ROMS)) forFolder(guestPath.removePrefix(ROMS).substringBefore('/')) else null

    fun corePath(system: System): String = "$CORES_DIR/${system.core}_libretro.so"

    /** Starts the game in its core, or hands RetroArch the bare path when the folder is not one of its systems. */
    fun launchArgs(guestPath: String): List<String> =
        forGuestPath(guestPath)?.let { listOf("-f", "-L", corePath(it), guestPath) } ?: listOf(guestPath)
}
