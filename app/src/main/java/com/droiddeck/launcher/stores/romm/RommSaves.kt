package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

/** Where RetroArch keeps a game's saves and states, and which of the files there are that game's. */
internal object RommSaves {
    const val EMULATOR = "retroarch"
    const val SAVES = "saves"
    const val STATES = "states"
    val KINDS = listOf(SAVES, STATES)

    private val STATE = Regex("state(\\d+|\\.auto)?")

    fun folder(context: Context): File = File(LinuxRuntime.rootDir(context), "root/.config/retroarch")

    /** The name RetroArch gives a game's saves: the file it opens, without its extension. */
    fun base(entry: RommInstalls.Entry): String? =
        if (entry.emulatorId != EMULATOR) null
        else entry.path.substringAfterLast('/').substringBeforeLast('.').ifEmpty { null }

    fun belongs(name: String, base: String): Boolean {
        val kind = name.substringBefore('/')
        val file = name.substringAfter('/', "")
        if (file.isEmpty() || '/' in file || !file.startsWith("$base.")) return false
        val ext = file.substring(base.length + 1)
        return when (kind) {
            SAVES -> ext.isNotEmpty() && '.' !in ext
            STATES -> STATE.matches(ext)
            else -> false
        }
    }
}
