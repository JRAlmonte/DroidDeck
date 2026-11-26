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
        val config: String? = null, val needs: List<EmulatorProfiles.Need> = emptyList(),
    ) {
        fun launchArgs(guestPath: String): List<String> = baseArgs + romArgs + guestPath

        /** Where the emulator keeps its settings, as a path inside the runtime: a Flatpak has a folder of its own. */
        val configPath: String? get() = config?.let {
            if (appKey.startsWith(FLATPAK)) "root/.var/app/${appKey.removePrefix(FLATPAK)}/config/$it" else "root/.config/$it"
        }
    }

    private const val PREFS = "emulators"
    private const val OFF = "off"
    private const val CHOICE = "choice:"
    private const val ROMS = "/root/ROMs"
    private const val FLATPAK = "flatpak:"
    private const val ASSIGNED = "manual:"
    private const val GAME = "game:"

    /** How the id of a link the user made by hand starts. */
    const val MANUAL = "app-"

    @Volatile var links: List<Link> = emptyList()
        private set
    @Volatile var choices: Map<String, String> = emptyMap()
        private set

    /** The emulator picked for one game, by the game's path below the ROMs folder. */
    @Volatile var gameChoices: Map<String, String> = emptyMap()
        private set

    fun link(id: String): Link? = links.firstOrNull { it.id == id }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun off(context: Context): Set<String> = prefs(context).getStringSet(OFF, emptySet()).orEmpty().toSet()

    /** The name, folders and file types of the system a ROM folder belongs to, where something here knows them. */
    internal fun systemFor(folder: String): Triple<String, List<String>, Set<String>>? {
        val name = folder.lowercase()
        Library.standalone().firstOrNull { name in it.folders }?.let { return Triple(it.system, it.folders, it.exts) }
        RetroArchCores.systems.firstOrNull { name in it.folders }?.let { return Triple(it.name, it.folders, it.exts) }
        return EmulatorProfiles.all.firstOrNull { name in it.folders }?.let { Triple(it.system, it.folders, it.exts) }
    }

    /** [assigned] maps an app the user set to play a system to that system's folder; such an app gets the game's path alone. */
    internal fun match(apps: List<UserApps.App>, off: Set<String> = emptySet(), assigned: Map<String, String> = emptyMap()): List<Link> {
        val candidates = apps.filter { it.kind != UserApps.Kind.SCRIPT }.sortedBy { it.key }
        val known = EmulatorProfiles.all.filter { it.id !in off }.mapNotNull { profile ->
            candidates.firstOrNull { it.key !in assigned && profile.matches(it.key.substringAfter(':'), it.name) }?.let { app ->
                Link(profile.id, app.name, profile.system, profile.folders, profile.exts, app.key, app.program, app.args, profile.romArgs, app.icon,
                    profile.config, profile.needs)
            }
        }
        val own = assigned.toSortedMap().mapNotNull { (appKey, folder) ->
            val app = candidates.firstOrNull { it.key == appKey } ?: return@mapNotNull null
            val (system, folders, exts) = systemFor(folder) ?: return@mapNotNull null
            Link(MANUAL + appKey.replace(Regex("[^A-Za-z0-9._-]"), "-"), app.name, system, folders, exts, app.key, app.program, app.args, emptyList(), app.icon)
        }
        return known + own
    }

    private fun assigned(context: Context): Map<String, String> =
        prefs(context).all.filter { (key, value) -> key.startsWith(ASSIGNED) && value is String }
            .map { (key, value) -> key.removePrefix(ASSIGNED) to value as String }.toMap()

    /** Sets the app to play the games of [folder]'s system, or with null to play none. Reads the installed apps: not for the main thread. */
    fun assign(context: Context, appKey: String, folder: String?) {
        val key = ASSIGNED + appKey
        prefs(context).edit().apply { if (folder == null) remove(key) else putString(key, folder.lowercase()) }.commit()
        refresh(context)
    }

    /** Reads the installed apps again; true when what plays which system changed. */
    fun refresh(context: Context): Boolean {
        val prefs = prefs(context)
        val found = match(runCatching { UserApps.list(context) }.getOrDefault(emptyList()), off(context), assigned(context))
        val rootfs = LinuxRuntime.rootDir(context)
        found.filter { it.appKey.startsWith(FLATPAK) }.forEach { allowRoms(rootfs, it.appKey.removePrefix(FLATPAK)) }
        fun stored(prefix: String) = prefs.all.filter { (key, value) -> key.startsWith(prefix) && value is String }
            .map { (key, value) -> key.removePrefix(prefix) to value as String }.toMap()
        val picked = stored(CHOICE)
        val games = stored(GAME)
        fun shape(list: List<Link>) = list.map { Triple(it.id, it.appKey, it.folders) }
        val changed = shape(found) != shape(links) || picked != choices || games != gameChoices
        links = found
        choices = picked
        gameChoices = games
        return changed
    }

    fun choose(context: Context, folder: String, emulatorId: String?) {
        val key = CHOICE + folder.lowercase()
        prefs(context).edit().apply { if (emulatorId == null) remove(key) else putString(key, emulatorId) }.commit()
        choices = if (emulatorId == null) choices - folder.lowercase() else choices + (folder.lowercase() to emulatorId)
    }

    fun chooseForGame(context: Context, path: String, key: String?) {
        prefs(context).edit().apply { if (key == null) remove(GAME + path) else putString(GAME + path, key) }.commit()
        gameChoices = if (key == null) gameChoices - path else gameChoices + (path to key)
    }

    /** Reads the installed apps: not for the main thread. */
    fun setOff(context: Context, id: String, off: Boolean) {
        val now = off(context).let { if (off) it + id else it - id }
        prefs(context).edit().putStringSet(OFF, now).commit()
        refresh(context)
    }

    fun resetAll(context: Context) {
        prefs(context).edit().clear().commit()
        choices = emptyMap()
        gameChoices = emptyMap()
    }

    internal fun replace(links: List<Link>, choices: Map<String, String> = emptyMap(), games: Map<String, String> = emptyMap()) {
        this.links = links
        this.choices = choices
        this.gameChoices = games
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
