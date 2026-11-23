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
     */
    class Layout(
        val emulator: String, val root: File, val folders: List<String>?, private val base: String,
        private val dirs: Map<String, String>, private val suffix: Map<String, Regex>,
    ) {
        val kinds: List<String> get() = KINDS.filter { it in dirs }

        fun kind(name: String): String? {
            val dir = name.substringBeforeLast('/', "")
            val file = name.substringAfterLast('/')
            if (!file.startsWith(base) || file.length == base.length) return null
            val rest = file.substring(base.length)
            return kinds.firstOrNull { dirs[it] == dir && suffix.getValue(it).matches(rest) }
        }

        fun local(kind: String, file: String): String = dirs[kind].orEmpty().let { if (it.isEmpty()) file else "$it/$file" }
    }

    /** The name emulators give a game's saves: the file they open, without its extension. */
    fun base(entry: RommInstalls.Entry): String? = entry.path.substringAfterLast('/').substringBeforeLast('.').ifEmpty { null }

    private val retroArch = mapOf(SAVES to Regex("\\.[^.]+"), STATES to Regex("\\.state(\\d+|\\.auto)?"))
    private val duckStation = mapOf(SAVES to Regex("_[12]\\.mcd"))
    private val melonDs = mapOf(SAVES to Regex("\\.sav"), STATES to Regex("\\.ml[1-8]"))

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
        return when (entry.emulatorId) {
            EMULATOR -> File(rootfs, "root/.config/retroarch").takeIf { retroArchInPlace(File(it, "retroarch.cfg")) }
                ?.let { Layout(EMULATOR, it, KINDS, base, mapOf(SAVES to SAVES, STATES to STATES), retroArch) }
            "duckstation" -> {
                val home = listOf("root/.config/duckstation", "root/.local/share/duckstation").map { File(rootfs, it) }
                    .firstOrNull { File(it, "settings.ini").isFile } ?: return null
                if (!cardPerFile(File(home, "settings.ini"))) return null
                Layout("duckstation", home, listOf("memcards"), base, mapOf(SAVES to "memcards"), duckStation)
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
