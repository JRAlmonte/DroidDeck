package com.droiddeck.launcher.frontend

import java.io.File

/** What plays each system, what is installed and what is still missing, worked out from files and settings alone. */
object EmulatorInventory {
    enum class State { READY, NOT_INSTALLED, CORE_MISSING, NEEDS_FILES, NOTHING }

    class Candidate(val key: String, val name: String, val emulatorId: String, val core: String? = null, val packageId: String? = null)

    /** A thing the chosen emulator still lacks: [copies] are the files found in the system's BIOS folder that would supply it. */
    class Need(val name: String, val where: String, val copies: List<Pair<File, File>>, val inApp: Boolean)

    class SystemRow(
        val name: String, val folders: List<String>, val games: Int, val onServer: Int,
        val candidates: List<Candidate>, val chosen: Candidate?, val state: State,
        val missing: List<String>, val suggestions: List<EmulatorProfiles.Profile>,
        val needs: List<Need> = emptyList(), val platformId: String = "", val slug: String = "",
    ) {
        val key: String get() = folders.first()
        val wanted: Boolean get() = games > 0 || onServer > 0
    }

    class CoreRow(val id: String, val name: String, val systems: List<String>, val present: Boolean, val used: Boolean)

    class Platform(val folder: String, val name: String, val count: Int, val id: String = "", val slug: String = folder)

    class Input(
        val packages: Set<String>, val rootfs: File?, val romsRoot: File?, val games: Map<String, Int>,
        val platforms: List<Platform> = emptyList(),
        val links: List<EmulatorLinks.Link> = EmulatorLinks.links,
        val choices: Map<String, String> = EmulatorLinks.choices,
    )

    class Result(val systems: List<SystemRow>, val cores: List<CoreRow>) {
        val wanted: List<SystemRow> get() = systems.filter { it.wanted }
        fun count(state: State): Int = systems.count { it.state == state }
    }

    private class Def(val name: String, val folders: MutableList<String>) {
        val candidates = ArrayList<Candidate>()
        val profiles = ArrayList<EmulatorProfiles.Profile>()
    }

    private val skipped = setOf("bios", "firmware")

    fun build(input: Input): Result {
        val present = input.rootfs?.let(RetroArchCores::installed).orEmpty()
        val defs = ArrayList<Def>()
        fun at(folders: List<String>) = defs.firstOrNull { d -> folders.any { it in d.folders } }
        fun def(name: String, folders: List<String>) = at(folders) ?: Def(name, folders.toMutableList()).also(defs::add)
        fun core(id: String) =
            Candidate(RetroArchCores.CHOICE + id, "RetroArch · " + (present[id]?.name ?: id), "retroarch", id, Library.packageId("retroarch"))

        Library.standalone().forEach { def(it.system, it.folders).candidates += Candidate(it.id, it.name, it.id, packageId = Library.packageId(it.id)) }
        RetroArchCores.systems.forEach { def(it.name, it.folders).candidates += core(it.core) }
        RetroArchCores.alternatives.forEach { alt ->
            if (alt.core in present || RetroArchCores.CHOICE + alt.core in input.choices.values) def(alt.name, alt.folders).candidates += core(alt.core)
        }
        input.links.forEach { def(it.system, it.folders).candidates += Candidate(it.id, it.name, it.id) }
        EmulatorProfiles.all.filter { p -> input.links.none { it.id == p.id } }.forEach { def(it.system, it.folders).profiles += it }
        input.platforms.forEach { p -> if (at(listOf(p.folder)) == null) defs += Def(p.name, mutableListOf(p.folder)) }
        val loose = input.romsRoot?.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && f.name.lowercase() !in skipped }.orEmpty()
            .sortedBy { it.name.lowercase() }
        loose.forEach { dir -> if (at(listOf(dir.name.lowercase())) == null) defs += Def(dir.name, mutableListOf(dir.name.lowercase())) }

        val systems = defs.map { d ->
            val chosenKey = d.folders.firstNotNullOfOrNull { input.choices[it] }
            val chosen = d.candidates.firstOrNull { it.key == chosenKey } ?: d.candidates.firstOrNull()
            val needed = chosen?.core?.let { RetroArchCores.firmware(d.name, it) }.orEmpty()
            val system = input.rootfs?.let { File(it, RetroArchCores.SYSTEM_DIR) }
            val missing = needed.takeIf { names -> system == null || names.none { File(system, it).isFile } }.orEmpty()
            val needs = input.links.firstOrNull { it.id == chosen?.emulatorId }?.let { needs(it, d.folders, input, loose) }.orEmpty()
            val state = when {
                chosen == null -> State.NOTHING
                chosen.packageId != null && chosen.packageId !in input.packages -> State.NOT_INSTALLED
                chosen.core != null && chosen.core !in present -> State.CORE_MISSING
                missing.isNotEmpty() || needs.isNotEmpty() -> State.NEEDS_FILES
                else -> State.READY
            }
            val platform = input.platforms.firstOrNull { it.folder in d.folders }
            val listed = d.folders.sumOf { input.games[it] ?: 0 }
            val games = if (listed > 0 || chosen != null) listed else loose.filter { it.name.lowercase() in d.folders }.sumOf(::files)
            SystemRow(
                d.name, d.folders, games, input.platforms.filter { it.folder in d.folders }.sumOf { it.count },
                d.candidates, chosen, state, missing,
                if (d.candidates.isEmpty() || state != State.READY) d.profiles else emptyList(),
                needs, platform?.id.orEmpty(), platform?.slug.orEmpty(),
            )
        }
        val used = (RetroArchCores.systems.map { it.core to it.name } + RetroArchCores.alternatives.map { it.core to it.name })
            .groupBy({ it.first }, { it.second })
        val cores = used.map { (id, names) -> CoreRow(id, present[id]?.name ?: id, names.distinct(), id in present, true) } +
            present.values.filter { it.id !in used }.sortedBy { it.name.lowercase() }
                .map { CoreRow(it.id, it.name, listOfNotNull(it.system), present = true, used = false) }
        return Result(systems, cores)
    }

    private fun needs(link: EmulatorLinks.Link, folders: List<String>, input: Input, loose: List<File>): List<Need> {
        val rootfs = input.rootfs ?: return emptyList()
        val config = link.configPath ?: return emptyList()
        val held = loose.filter { it.name.lowercase() in folders }
            .flatMap { dir -> dir.listFiles { f -> f.isDirectory && f.name.lowercase() in skipped }.orEmpty().sortedBy { it.name.lowercase() } }
        return link.needs.mapNotNull { need ->
            val target = File(rootfs, "$config/${need.folder}")
            val there = if (need.files.isEmpty()) !target.list().isNullOrEmpty() else File(target, need.files.first()).isFile
            if (there) return@mapNotNull null
            val copies = need.files.mapNotNull { name -> held.map { File(it, name) }.firstOrNull { it.isFile }?.let { it to File(target, name) } }
                .takeIf { found -> found.any { it.second.name == need.files.firstOrNull() } }.orEmpty()
            Need(need.name, "~/" + "$config/${need.folder}".removePrefix("root/"), copies, need.inApp)
        }
    }

    private fun files(dir: File): Int =
        dir.walkTopDown().maxDepth(3).onEnter { it.name.lowercase() !in skipped }.count { it.isFile && !it.name.startsWith(".") }
}
