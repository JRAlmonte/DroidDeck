package com.droiddeck.launcher.frontend

object RetroArchCores {
    class System(val name: String, val folders: List<String>, val exts: Set<String>, val core: String, val thumbnails: String)

    const val CORES_DIR = "/usr/lib/aarch64-linux-gnu/libretro"

    /** RetroArch's system folder inside the runtime, which droiddeck-pad-defaults pins. */
    const val SYSTEM_DIR = "root/.config/retroarch/system"

    private const val ROMS = "/root/ROMs/"

    val systems = listOf(
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
    )

    val folders: Set<String> = systems.flatMap { it.folders }.toSet()

    private val firmware = mapOf(
        "Sega CD" to listOf("bios_CD_U.bin", "bios_CD_E.bin", "bios_CD_J.bin"),
        "PC Engine CD" to listOf("syscard3.pce"),
    )

    /** The BIOS files a system's games need, any one of them, in [SYSTEM_DIR]. */
    fun firmwareFor(folder: String): List<String>? = forFolder(folder)?.let { firmware[it.name] }

    fun forFolder(name: String): System? = name.lowercase().let { folder -> systems.firstOrNull { folder in it.folders } }

    fun forGuestPath(guestPath: String): System? =
        if (guestPath.startsWith(ROMS)) forFolder(guestPath.removePrefix(ROMS).substringBefore('/')) else null

    fun corePath(system: System): String = "$CORES_DIR/${system.core}_libretro.so"

    fun launchArgs(guestPath: String): List<String> =
        forGuestPath(guestPath)?.let { listOf("-f", "-L", corePath(it), guestPath) } ?: listOf(guestPath)
}
