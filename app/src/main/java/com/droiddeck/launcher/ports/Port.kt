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
    /** Set for the program when it starts; `{rom}` in a value becomes the ROM's path in the runtime. */
    val env: Map<String, String> = emptyMap(),
    /** Given to the program when it starts; `{rom}` is replaced as in [env]. */
    val args: List<String> = emptyList(),
    /** Names the install folder; ports of one repository with one tool share a single install. */
    val tool: String = id,
    /** Where the program keeps its saves: files or folders in the install folder, or in the runtime's home when they begin with `~/`. */
    val saves: List<String> = emptyList(),
) {
    val guestDir: String get() = "${Ports.GUEST_ROOT}/$tool"
    val starterName: String get() = "${Ports.STARTER}-$id"
    val program: String get() = "$guestDir/$starterName"
    val usesRom: Boolean get() = (env.values + args).any { Ports.ROM in it }
    val keptPaths: List<String> get() = saves.filter { !it.startsWith(Ports.HOME) }.ifEmpty { listOf(Ports.SAVES) }
}

object Ports {
    const val GUEST_ROOT = "/opt/droiddeck-ports"
    const val PREFIX = "port:"
    const val APPIMAGE_ENTRY = "app/AppRun"
    const val STARTER = ".droiddeck-start"
    const val ROM = "{rom}"
    const val HOME = "~/"
    const val SAVES = "saves"

    /** The ports in use: the user's lists in the order added, then single ports; of two with one id, or one tool from two repositories, the first is kept. */
    @Volatile var all: List<Port> = emptyList()
        private set

    fun use(ports: List<Port>) {
        val owner = HashMap<String, String>()
        all = ports.distinctBy { it.id }.filter { owner.getOrPut(it.tool) { it.repo.lowercase() } == it.repo.lowercase() }
    }

    fun byId(id: String): Port? = all.firstOrNull { it.id == id }

    fun byTarget(target: String): Port? = if (target.startsWith(PREFIX)) byId(target.removePrefix(PREFIX)) else null

    fun forFolder(folder: String): Port? = all.firstOrNull { folder.lowercase() in it.systemFolders }
}
