package com.droiddeck.launcher.frontend

/** Emulators that are no package of DroidDeck's own, known when one is installed as a Flatpak or an AppImage. */
object EmulatorProfiles {
    class Profile(
        val id: String, val name: String, val system: String, val folders: List<String>, val exts: Set<String>,
        val flatpak: String, private val match: Regex, val romArgs: List<String> = emptyList(),
    ) {
        fun matches(appId: String, appName: String): Boolean = match.containsMatchIn(appId) || match.containsMatchIn(appName)
    }

    private val SWITCH = setOf("nsp", "xci", "nca", "nro", "nso")

    val all = listOf(
        Profile("ryujinx", "Ryujinx", "Switch", listOf("switch"), SWITCH, "io.github.ryubing.Ryujinx",
            Regex("(?i)ryujinx|ryubing"), listOf("--fullscreen")),
        Profile("eden", "Eden", "Switch", listOf("switch"), SWITCH, "dev.eden_emu.eden",
            Regex("(?i)\\beden\\b|eden_emu|citron|sudachi|suyu|yuzu"), listOf("-f", "-g")),
        Profile("azahar", "Azahar", "3DS", listOf("3ds", "n3ds", "new-nintendo-3ds"),
            setOf("3ds", "3dsx", "app", "axf", "cci", "cxi", "elf", "z3dsx", "zcci", "zcxi"), "org.azahar_emu.Azahar", Regex("(?i)azahar")),
        Profile("flycast", "Flycast", "Dreamcast", listOf("dreamcast", "dc"), setOf("cdi", "chd", "cue", "gdi", "m3u"),
            "org.flycast.Flycast", Regex("(?i)flycast")),
        Profile("rmg", "Rosalie's Mupen GUI", "N64", listOf("n64"), setOf("z64", "n64", "v64", "ndd"),
            "com.github.Rosalie241.RMG", Regex("(?i)\\bRMG\\b|rosalie"), listOf("--nogui", "-q")),
        Profile("simple64", "simple64", "N64", listOf("n64"), setOf("z64", "n64", "v64"),
            "io.github.simple64.simple64", Regex("(?i)simple64"), listOf("--nogui")),
        Profile("xemu", "xemu", "Xbox", listOf("xbox"), setOf("iso", "xiso"), "app.xemu.xemu", Regex("(?i)xemu"), listOf("-dvd_path")),
    )

    fun of(id: String): Profile? = all.firstOrNull { it.id == id }

    fun forFolder(folder: String): List<Profile> = folder.lowercase().let { name -> all.filter { name in it.folders } }
}
