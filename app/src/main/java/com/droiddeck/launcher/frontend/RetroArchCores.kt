package com.droiddeck.launcher.frontend

import java.io.File

object RetroArchCores {
    class System(val name: String, val folders: List<String>, val exts: Set<String>, val core: String)

    class Core(val id: String, val name: String, val system: String?)

    const val CORES_DIR = "/usr/lib/aarch64-linux-gnu/libretro"
    private const val INFO_DIR = "usr/share/libretro/info"
    private const val SUFFIX = "_libretro.so"

    /** How a system's pick of a core is written in [EmulatorLinks.choices]. */
    const val CHOICE = "retroarch:"

    /** RetroArch's system folder inside the runtime, which droiddeck-pad-defaults pins. */
    const val SYSTEM_DIR = "root/.config/retroarch/system"

    private const val ROMS = "/root/ROMs/"

    val systems = listOf(
        System("NES", listOf("nes", "famicom"), setOf("nes", "fds", "unf", "unif", "zip"), "nestopia"),
        System("SNES", listOf("snes", "sfc"), setOf("sfc", "smc", "fig", "swc", "zip"), "snes9x"),
        System("Game Boy", listOf("gb"), setOf("gb", "zip"), "gambatte"),
        System("Game Boy Color", listOf("gbc"), setOf("gbc", "gb", "zip"), "gambatte"),
        System("Game Boy Advance", listOf("gba"), setOf("gba", "zip"), "mgba"),
        System("Genesis", listOf("genesis", "megadrive", "md"), setOf("md", "gen", "smd", "bin", "zip"), "genesis_plus_gx"),
        System("Master System", listOf("mastersystem", "sms"), setOf("sms", "zip"), "genesis_plus_gx"),
        System("Game Gear", listOf("gamegear", "gg"), setOf("gg", "zip"), "genesis_plus_gx"),
        System("SG-1000", listOf("sg1000"), setOf("sg", "zip"), "genesis_plus_gx"),
        System("PC Engine", listOf("pcengine", "pce", "tg16"), setOf("pce", "zip"), "mednafen_pce_fast"),
        System("Sega CD", listOf("segacd", "megacd"), setOf("cue", "chd", "iso", "m3u"), "genesis_plus_gx"),
        System("PC Engine CD", listOf("pcenginecd", "pcecd", "tgcd"), setOf("cue", "chd", "ccd", "m3u"), "mednafen_pce_fast"),
        System("Virtual Boy", listOf("virtualboy", "vb"), setOf("vb", "vboy", "zip"), "mednafen_vb"),
        System("WonderSwan", listOf("wonderswan", "wswan"), setOf("ws", "zip"), "mednafen_wswan"),
        System("WonderSwan Color", listOf("wonderswancolor", "wsc"), setOf("wsc", "zip"), "mednafen_wswan"),
    )

    /** Cores the Emulators package also installs, which play a system only when the user picks them. */
    val alternatives = listOf(
        System("SNES", listOf("snes", "sfc"), setOf("sfc", "smc"), "bsnes_mercury_performance"),
        System("Game Boy", listOf("gb"), setOf("gb"), "sameboy"),
        System("Game Boy Color", listOf("gbc"), setOf("gbc", "gb"), "sameboy"),
        System("DS", listOf("ds", "nds"), setOf("nds"), "desmume"),
        System("PS1", listOf("ps1", "psx"), setOf("cue", "chd", "m3u", "pbp", "ccd"), "mednafen_psx"),
    )

    private val defaultFolders: Set<String> = systems.flatMap { it.folders }.toSet()

    /** The folders RetroArch lists games from: its own systems and those the user gave one of its cores. */
    val folders: Set<String> get() = defaultFolders + EmulatorLinks.choices.filter { (folder, value) -> chosen(folder, value) != null }.keys

    private val firmware = mapOf(
        "Sega CD" to listOf("bios_CD_U.bin", "bios_CD_E.bin", "bios_CD_J.bin"),
        "PC Engine CD" to listOf("syscard3.pce"),
    )

    private val coreFirmware = mapOf("mednafen_psx" to listOf("scph5501.bin", "scph5500.bin", "scph5502.bin"))

    /** The BIOS files a system's games need, any one of them, in [SYSTEM_DIR]. */
    fun firmwareFor(folder: String): List<String>? = forFolder(folder)?.let { firmware(it.name, it.core) }

    fun firmware(system: String, core: String): List<String>? = coreFirmware[core] ?: firmware[system]

    internal fun chosen(folder: String, value: String): System? =
        value.takeIf { it.startsWith(CHOICE) }?.removePrefix(CHOICE)?.let { core ->
            (systems + alternatives).firstOrNull { it.core == core && folder in it.folders }
        }

    fun forFolder(name: String): System? = name.lowercase().let { folder ->
        val choice = EmulatorLinks.choices[folder]
        if (choice != null) chosen(folder, choice)?.let { return it }
        systems.firstOrNull { folder in it.folders }
    }

    fun forGuestPath(guestPath: String): System? =
        if (guestPath.startsWith(ROMS)) forFolder(guestPath.removePrefix(ROMS).substringBefore('/')) else null

    fun corePath(system: System): String = "$CORES_DIR/${system.core}$SUFFIX"

    fun launchArgs(guestPath: String): List<String> =
        forGuestPath(guestPath)?.let { listOf("-f", "-L", corePath(it), guestPath) } ?: listOf(guestPath)

    /** The cores on disk in the runtime, by id, named as their info files name them. */
    fun installed(rootfs: File): Map<String, Core> =
        File(rootfs, CORES_DIR.removePrefix("/")).listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty().associate { file ->
            val id = file.name.removeSuffix(SUFFIX)
            val info = runCatching { File(rootfs, "$INFO_DIR/${id}_libretro.info").readLines() }.getOrDefault(emptyList())
            fun field(key: String) = info.firstOrNull { it.substringBefore('=').trim() == key }
                ?.substringAfter('=')?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
            id to Core(id, field("corename") ?: id, field("systemname"))
        }
}
