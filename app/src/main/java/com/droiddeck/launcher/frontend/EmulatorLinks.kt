package com.droiddeck.launcher.frontend

import android.content.Context
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.UserApps
import java.io.File

/** The installed apps that play a system's games, and the emulator the user picked where more than one can. */
object EmulatorLinks {
    class Link(
        val id: String, val name: String, val system: String, val folders: List<String>, val exts: Set<String>,
        val appKey: String, val program: String, val baseArgs: List<String>, val romArgs: List<String>, val icon: Any?,
    ) {
        fun launchArgs(guestPath: String): List<String> = baseArgs + romArgs + guestPath
    }

    private const val PREFS = "emulators"
    private const val OFF = "off"
    private const val CHOICE = "choice:"
    private const val ROMS = "/root/ROMs"
    private const val FLATPAK = "flatpak:"

    @Volatile var links: List<Link> = emptyList()
        private set
    @Volatile var choices: Map<String, String> = emptyMap()
        private set

    fun link(id: String): Link? = links.firstOrNull { it.id == id }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun off(context: Context): Set<String> = prefs(context).getStringSet(OFF, emptySet()).orEmpty().toSet()

    internal fun match(apps: List<UserApps.App>, off: Set<String> = emptySet()): List<Link> {
        val candidates = apps.filter { it.kind != UserApps.Kind.SCRIPT }.sortedBy { it.key }
        return EmulatorProfiles.all.filter { it.id !in off }.mapNotNull { profile ->
            candidates.firstOrNull { profile.matches(it.key.substringAfter(':'), it.name) }?.let { app ->
                Link(profile.id, app.name, profile.system, profile.folders, profile.exts, app.key, app.program, app.args, profile.romArgs, app.icon)
            }
        }
    }

    /** Reads the installed apps again; true when what plays which system changed. */
    fun refresh(context: Context): Boolean {
        val prefs = prefs(context)
        val found = match(runCatching { UserApps.list(context) }.getOrDefault(emptyList()), off(context))
        val rootfs = LinuxRuntime.rootDir(context)
        found.filter { it.appKey.startsWith(FLATPAK) }.forEach { allowRoms(rootfs, it.appKey.removePrefix(FLATPAK)) }
        val picked = prefs.all.filter { (key, value) -> key.startsWith(CHOICE) && value is String }
            .map { (key, value) -> key.removePrefix(CHOICE) to value as String }.toMap()
        val changed = found.map { it.id to it.appKey } != links.map { it.id to it.appKey } || picked != choices
        links = found
        choices = picked
        return changed
    }

    fun choose(context: Context, folder: String, emulatorId: String?) {
        val key = CHOICE + folder.lowercase()
        prefs(context).edit().apply { if (emulatorId == null) remove(key) else putString(key, emulatorId) }.commit()
        choices = if (emulatorId == null) choices - folder.lowercase() else choices + (folder.lowercase() to emulatorId)
    }

    fun setOff(context: Context, id: String, off: Boolean) {
        val now = off(context).let { if (off) it + id else it - id }
        prefs(context).edit().putStringSet(OFF, now).commit()
        refresh(context)
    }

    fun resetAll(context: Context) {
        prefs(context).edit().clear().commit()
        choices = emptyMap()
    }

    internal fun replace(links: List<Link>, choices: Map<String, String> = emptyMap()) {
        this.links = links
        this.choices = choices
    }

    internal fun allowRoms(rootfs: File, flatpakId: String) {
        val file = File(rootfs, "root/.local/share/flatpak/overrides/$flatpakId")
        val merged = withFilesystem(FileUtils.readString(file).orEmpty(), ROMS) ?: return
        file.parentFile?.mkdirs()
        FileUtils.writeString(file, merged)
    }

    /** A Flatpak override file that also lets the app read [path], or null when it already does. */
    internal fun withFilesystem(overrides: String, path: String): String? {
        val lines = overrides.lines().dropLastWhile { it.isBlank() }.toMutableList()
        val start = lines.indexOfFirst { it.trim() == "[Context]" }
        if (start < 0) {
            if (lines.isNotEmpty()) lines += ""
            lines += listOf("[Context]", "filesystems=$path;")
            return lines.joinToString("\n") + "\n"
        }
        val end = (start + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
        val at = (start + 1 until end).firstOrNull { lines[it].startsWith("filesystems=") }
        if (at == null) {
            lines.add(start + 1, "filesystems=$path;")
        } else {
            val entries = lines[at].substringAfter('=').split(';').map { it.trim() }.filter { it.isNotEmpty() }
            if (entries.any { it.substringBefore(':') == path }) return null
            lines[at] = "filesystems=" + (entries + path).joinToString(";") + ";"
        }
        return lines.joinToString("\n") + "\n"
    }
}
