package com.droiddeck.launcher.ports

enum class RomForm { PLAIN, N64 }

class Port(
    val id: String,
    val name: String,
    val game: String,
    val repo: String,
    val asset: Regex,
    val entry: String,
    val systemFolders: List<String>,
    val exts: Set<String>,
    val form: RomForm,
    val romSize: Long,
    val romSha1: String,
    val stagedRom: String,
    val markers: List<String> = emptyList(),
    val source: String? = null,
) {
    val guestDir: String get() = "${Ports.GUEST_ROOT}/$id"
    val program: String get() = "$guestDir/$entry"

    fun withEntry(entry: String) = Port(id, name, game, repo, asset, entry, systemFolders, exts, form, romSize, romSha1, stagedRom, markers, source)
}

object Ports {
    const val GUEST_ROOT = "/opt/droiddeck-ports"
    const val PREFIX = "port:"

    val builtIn = listOf(
        Port(
            id = "zelda64recomp",
            name = "Zelda 64: Recompiled",
            game = "The Legend of Zelda: Majora's Mask",
            repo = "Zelda64Recomp/Zelda64Recomp",
            asset = Regex("Linux-ARM64\\.zip$", RegexOption.IGNORE_CASE),
            entry = "Zelda64Recompiled",
            systemFolders = listOf("n64"),
            exts = setOf("z64", "n64", "v64"),
            form = RomForm.N64,
            romSize = 32L * 1024 * 1024,
            romSha1 = "d6133ace5afaa0882cf214cf88daba39e266c078",
            stagedRom = "mm.n64.us.1.0.z64",
            markers = listOf("portable.txt"),
        ),
    )

    /** The ports in use: the fetched list, the built-in ports it does not name, then the user's own that take no listed name. */
    @Volatile var all: List<Port> = builtIn
        private set

    fun use(fetched: List<Port>, user: List<Port> = emptyList()) {
        all = PortCatalog.combine(builtIn, fetched, user)
    }

    fun byId(id: String): Port? = all.firstOrNull { it.id == id }

    fun byTarget(target: String): Port? = if (target.startsWith(PREFIX)) byId(target.removePrefix(PREFIX)) else null

    fun forFolder(folder: String): Port? = all.firstOrNull { folder.lowercase() in it.systemFolders }
}
