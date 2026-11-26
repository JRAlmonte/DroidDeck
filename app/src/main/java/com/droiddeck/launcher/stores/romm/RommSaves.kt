package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

internal object RommSaves {
    const val EMULATOR = "retroarch"
    const val SAVES = "saves"
    const val STATES = "states"
    val KINDS = listOf(SAVES, STATES)

    /**
     * One game's files in one emulator. They sit under [root], in the sub-folder [dirs] names for each kind RomM keeps
     * (`saves`, `states`), and are named [base] followed by what [suffix] allows for that kind. [folders] limits what is
     * looked through under [root]; null looks through all of it. [emulator] is the name the files carry on the server.
     * [packed] is set when the emulator keeps a save as a folder: the saves synced are then zips of those folders.
     * [bases] names a kind's files when they do not start with [base].
     */
    class Layout(
        val emulator: String, val root: File, val folders: List<String>?, private val base: String,
        private val dirs: Map<String, String>, private val suffix: Map<String, Regex>,
        val packed: RommPacked? = null, private val bases: Map<String, String> = emptyMap(),
    ) {
        val kinds: List<String> get() = KINDS.filter { it in dirs }

        fun kind(name: String): String? {
            val dir = name.substringBeforeLast('/', "")
            val file = name.substringAfterLast('/')
            return kinds.firstOrNull { kind ->
                val start = bases[kind] ?: base
                dirs[kind] == dir && file.startsWith(start) && file.length > start.length && suffix.getValue(kind).matches(file.substring(start.length))
            }
        }

        fun local(kind: String, file: String): String = dirs[kind].orEmpty().let { if (it.isEmpty()) file else "$it/$file" }
    }

    /** The name emulators give a game's saves: the file they open, without its extension. */
    fun base(entry: RommInstalls.Entry): String? = entry.path.substringAfterLast('/').substringBeforeLast('.').ifEmpty { null }

    private val retroArch = mapOf(SAVES to Regex("\\.[^.]+"), STATES to Regex("\\.state(\\d+|\\.auto)?"))
    private val duckStation = mapOf(SAVES to Regex("_[12]\\.mcd"))
    private val melonDs = mapOf(SAVES to Regex("\\.sav"), STATES to Regex("\\.ml[1-8]"))
    private val ppsspp = mapOf(SAVES to Regex("[A-Za-z0-9_-]*\\.zip"), STATES to Regex("_[^/]*\\.ppst"))

    /** Where the zips of PPSSPP's save folders are kept, beside the folders; PPSSPP leaves a folder it does not know alone. */
    const val PACKED_DIR = ".droiddeck-saves"
    private val FOLDER_LAYOUTS = setOf("folder-prefix", "folder-exact")

    private fun code(text: String?): String = text.orEmpty().filter { it.isLetterOrDigit() }.uppercase()

    /** The id PPSSPP names a game's states by, as RomM read it from the game; empty when it read none. */
    fun titleId(entry: RommInstalls.Entry): String = code(entry.item?.extra?.get(RommCatalog.TITLE_ID))

    /** What a game's save folders start with: the name RomM says its saves go by when it knows one, else its title id. */
    private fun saveName(entry: RommInstalls.Entry): String =
        code(entry.item?.extra?.get(RommCatalog.SAVE_TARGET)?.takeIf { entry.item.extra[RommCatalog.SAVE_LAYOUT] in FOLDER_LAYOUTS }).ifEmpty { titleId(entry) }

    /** DuckStation names a memory card after the game's file only with this card type, which droiddeck-pad-defaults sets. */
    private fun cardPerFile(settings: File): Boolean {
        var inCards = false
        for (line in runCatching { settings.readLines() }.getOrNull() ?: return false) {
            val text = line.trim()
            if (text.startsWith("[")) inCards = text == "[MemoryCards]"
            else if (inCards && text.substringBefore('=').trim() == "Card1Type") return text.substringAfter('=').trim() == "PerGameFileTitle"
        }
        return false
    }

    private val retroArchFolders = mapOf("savefile_directory" to SAVES, "savestate_directory" to STATES)
    private val retroArchElsewhere = listOf(
        "savefiles_in_content_dir", "savestates_in_content_dir", "sort_savefiles_enable", "sort_savestates_enable",
        "sort_savefiles_by_content_enable", "sort_savestates_by_content_enable",
    )

    /** Whether RetroArch keeps saves and states where droiddeck-pad-defaults first puts them; a user who moved them is not synced. */
    private fun retroArchInPlace(cfg: File): Boolean {
        for (line in runCatching { cfg.readLines() }.getOrNull() ?: return true) {
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=', "").trim().trim('"')
            val folder = retroArchFolders[key]
            if (folder != null && value != "default" && value.trimEnd('/') !in listOf("~/.config/retroarch/$folder", "/root/.config/retroarch/$folder")) return false
            if (key in retroArchElsewhere && value == "true") return false
        }
        return true
    }

    fun layout(rootfs: File, romsRoot: File?, entry: RommInstalls.Entry): Layout? {
        val base = base(entry) ?: return null
        return when (entry.runner) {
            EMULATOR -> File(rootfs, "root/.config/retroarch").takeIf { retroArchInPlace(File(it, "retroarch.cfg")) }
                ?.let { Layout(EMULATOR, it, KINDS, base, mapOf(SAVES to SAVES, STATES to STATES), retroArch) }
            "duckstation" -> {
                val home = listOf("root/.config/duckstation", "root/.local/share/duckstation").map { File(rootfs, it) }
                    .firstOrNull { File(it, "settings.ini").isFile } ?: return null
                if (!cardPerFile(File(home, "settings.ini"))) return null
                Layout("duckstation", home, listOf("memcards"), base, mapOf(SAVES to "memcards"), duckStation)
            }
            "ppsspp" -> {
                val id = titleId(entry)
                if (id.length < 8) return null
                val home = File(rootfs, "root/.config/ppsspp/PSP")
                val name = saveName(entry)
                Layout(
                    "ppsspp", home, listOf(PACKED_DIR, "PPSSPP_STATE"), name, mapOf(SAVES to PACKED_DIR, STATES to "PPSSPP_STATE"), ppsspp,
                    RommPacked(File(home, "SAVEDATA"), File(home, PACKED_DIR), name), mapOf(STATES to id),
                )
            }
            "melonds" -> {
                val dir = romsRoot?.let { File(it, entry.path).parentFile } ?: return null
                Layout("melonds", dir, null, base, mapOf(SAVES to "", STATES to ""), melonDs)
            }
            else -> null
        }
    }

    fun layout(context: Context, entry: RommInstalls.Entry): Layout? = layout(LinuxRuntime.rootDir(context), RommInstalls.romsRoot(context), entry)
}
