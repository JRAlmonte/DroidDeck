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
    val source: String = repo,
) {
    val guestDir: String get() = "${Ports.GUEST_ROOT}/$id"
    val program: String get() = "$guestDir/$entry"

    fun withEntry(entry: String) = Port(id, name, game, repo, asset, entry, systemFolders, exts, form, romSize, romSha1, stagedRom, markers, source)
}

object Ports {
    const val GUEST_ROOT = "/opt/droiddeck-ports"
    const val PREFIX = "port:"

    /** The ports in use: the user's lists in the order added, then single ports; of two with one id the first is kept. */
    @Volatile var all: List<Port> = emptyList()
        private set

    fun use(ports: List<Port>) {
        all = ports.distinctBy { it.id }
    }

    fun byId(id: String): Port? = all.firstOrNull { it.id == id }

    fun byTarget(target: String): Port? = if (target.startsWith(PREFIX)) byId(target.removePrefix(PREFIX)) else null

    fun forFolder(folder: String): Port? = all.firstOrNull { folder.lowercase() in it.systemFolders }
}
