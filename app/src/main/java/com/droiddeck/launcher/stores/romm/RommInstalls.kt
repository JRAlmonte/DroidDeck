package com.droiddeck.launcher.stores.romm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONObject
import java.io.File

internal object RommInstalls {
    /** [path] is the file the emulator opens; [root] is what uninstalling deletes, a folder when the game was unpacked into one. [emulatorId] is empty for a game kept while nothing played it. */
    class Entry(val id: String, val path: String, val emulatorId: String, val root: String = path, val item: CatalogItem? = null) {
        val guestPath: String get() = "/root/ROMs/$path"
        val runner: String get() = RommPlatforms.runner(this)
    }

    private fun inside(path: String): Boolean = path.isNotEmpty() && !path.startsWith("/") && path.split('/').none { it == ".." || it.isEmpty() }

    var entries by mutableStateOf<Map<String, Entry>>(emptyMap())
        private set

    private fun file(context: Context) = File(context.filesDir, "stores/romm/installed.json")

    fun romsRoot(context: Context): File? = SessionPrefs.romsDir(context).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isDirectory }

    fun parse(text: String): Map<String, Entry> {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Entry>()
        for (id in o.keys()) {
            val e = o.optJSONObject(id) ?: continue
            val path = e.optString("path")
            val emulator = e.optString("emulator").takeUnless { it.startsWith("port:") }.orEmpty()
            val root = e.optString("root").ifEmpty { path }
            if (!e.has("emulator") || !inside(path) || !inside(root) || !(path == root || path.startsWith("$root/"))) continue
            out[id] = Entry(id, path, emulator, root, e.optJSONObject("game")?.let { game(id, it) })
        }
        return out
    }

    fun format(map: Map<String, Entry>): String {
        val o = JSONObject()
        for (e in map.values) o.put(e.id, JSONObject().put("path", e.path).put("emulator", e.emulatorId).put("root", e.root).apply { e.item?.let { put("game", game(it)) } })
        return o.toString()
    }

    private fun game(item: CatalogItem): JSONObject = JSONObject()
        .put("title", item.title).put("image", item.imageUrl ?: "").put("cover", item.tallImageUrl ?: "").put("tags", item.tags)
        .put("url", item.storeUrl).put("developer", item.developer).put("publisher", item.publisher).put("date", item.releaseDate).put("about", item.description)
        .put("size", item.sizeBytes).put("extra", JSONObject(item.extra))

    private fun game(id: String, o: JSONObject): CatalogItem? {
        val title = o.optString("title").ifEmpty { return null }
        val extra = o.optJSONObject("extra")?.let { x -> x.keys().asSequence().associateWith { x.optString(it) } }.orEmpty()
        return CatalogItem(
            Store.ROMM, id, title, o.optString("image").ifEmpty { null }, o.optString("cover").ifEmpty { null }, tags = o.optString("tags"),
            storeUrl = o.optString("url"), developer = o.optString("developer"), publisher = o.optString("publisher"), releaseDate = o.optString("date"),
            description = o.optString("about"), owned = true, sizeBytes = o.optLong("size"), extra = extra,
        )
    }

    @Volatile private var read: Triple<Long, Long, Map<String, Entry>>? = null

    private fun stored(context: Context): Map<String, Entry> {
        val f = file(context)
        val stamp = f.lastModified()
        val size = f.length()
        read?.takeIf { it.first == stamp && it.second == size }?.let { return it.third }
        return runCatching { parse(f.readText()) }.getOrDefault(emptyMap()).also { read = Triple(stamp, size, it) }
    }

    fun find(context: Context, id: String): Entry? = entries[id] ?: stored(context)[id]

    /** A game's record as the file holds it, which what is shown follows a moment later. */
    internal fun onRecord(context: Context, id: String): Entry? = stored(context)[id]

    private class Index(val of: Map<String, Entry>) {
        val byGuest: Map<String, Entry> = of.values.associateBy { it.guestPath }
    }

    @Volatile private var shown: Index? = null
    @Volatile private var kept: Index? = null

    // Every ROM in the library is looked up here on each scan, so the records are indexed once for as long as they stay the same.
    fun byGuestPath(context: Context, guestPath: String): Entry? {
        val now = entries
        val first = shown?.takeIf { it.of === now } ?: Index(now).also { shown = it }
        first.byGuest[guestPath]?.let { return it }
        val file = stored(context)
        val second = kept?.takeIf { it.of === file } ?: Index(file).also { kept = it }
        return second.byGuest[guestPath]
    }

    /** The installed games as library items; a record written before games were kept with it has none. */
    fun items(): List<CatalogItem> = entries.values.mapNotNull { it.item }.sortedBy { it.title.lowercase() }

    @Synchronized
    fun load(context: Context) = show(context, stored(context))

    private fun show(context: Context, stored: Map<String, Entry>) {
        val root = romsRoot(context)
        val present = if (root == null) emptyMap() else stored.filterValues { File(root, it.path).exists() }
        StoresState.post {
            entries = present
            if (StoresState.library.containsKey(Store.ROMM)) StoresState.library[Store.ROMM] = items()
        }
    }

    private fun kept(context: Context, id: String) = File(context.filesDir, "stores/romm/files/" + id.filter { it.isLetterOrDigit() || it == '_' || it == '-' })

    /** Notes that the folder at [root] was made for the game [id], so an install that was cut short may carry on in it. */
    fun claim(context: Context, id: String, root: String) {
        runCatching { kept(context, id).apply { parentFile?.mkdirs(); writeText(root + "\n") } }
    }

    /** The folder an install that was cut short made for the game [id], by its place under the ROMs folder, or null when no such folder is noted. */
    fun claimed(context: Context, id: String): String? =
        lines(context, id)?.firstOrNull()?.takeIf { '/' in it && inside(it) && !recorded(context, it) }

    fun unclaim(context: Context, id: String) {
        kept(context, id).delete()
    }

    private fun lines(context: Context, id: String): List<String>? = runCatching { kept(context, id).readLines().filter { it.isNotEmpty() } }.getOrNull()

    fun recorded(context: Context, path: String): Boolean =
        entries.values.any { it.root == path || it.path == path } || stored(context).values.any { it.root == path || it.path == path }

    /** Whether the file or folder at [path] under the ROMs folder is an installed game's, or the folder made for the game [id]. */
    fun owns(context: Context, id: String, path: String): Boolean = recorded(context, path) || lines(context, id)?.firstOrNull() == path

    /** [files] names what a game kept in a folder was given, so that removing it deletes those files and nothing put beside them. */
    @Synchronized
    fun record(context: Context, entry: Entry, files: List<String>? = null) {
        if (files == null) unclaim(context, entry.id)
        else runCatching { kept(context, entry.id).apply { parentFile?.mkdirs(); writeText((listOf(entry.root) + files).joinToString("\n", postfix = "\n")) } }
        change(context) { it + (entry.id to entry) }
    }

    /** Records several games in one write; each is a file of its own, as [record] keeps one without a list of files. */
    @Synchronized
    fun recordAll(context: Context, found: List<Entry>) {
        if (found.isEmpty()) return
        found.forEach { unclaim(context, it.id) }
        change(context) { it + found.associateBy { e -> e.id } }
    }

    /** Adds to an installed game's record what the server has since learned of it, such as the id its saves are named by. */
    @Synchronized
    fun learn(context: Context, id: String, known: Map<String, String>) {
        val entry = find(context, id) ?: return
        val item = entry.item ?: return
        val added = known.filter { it.value.isNotEmpty() && item.extra[it.key].isNullOrEmpty() }
        if (added.isEmpty()) return
        change(context) { it + (id to Entry(entry.id, entry.path, entry.emulatorId, entry.root, item.copy(extra = item.extra + added))) }
    }

    /** The ids of the recorded games that carry what the server said of them. */
    fun described(context: Context): Set<String> = stored(context).values.filter { it.item != null }.mapTo(LinkedHashSet()) { it.id }

    // The file on the device is the one that was installed, whatever the server holds under that id now, and its platform
    // named the folder it went to and the emulator that plays it.
    private val AS_INSTALLED = setOf(RommCatalog.PLATFORM, RommCatalog.FILE, RommCatalog.SINGLE, RommCatalog.SHA1, RommCatalog.DISCS, RommCatalog.EXTRAS)
    // Save sync names a game's files by these, so one that is known is never replaced.
    private val FILLED_ONCE = listOf(RommCatalog.TITLE_ID, RommCatalog.SAVE_TARGET, RommCatalog.SAVE_LAYOUT)

    internal fun renewed(was: CatalogItem, now: CatalogItem): CatalogItem = now.copy(
        sizeBytes = was.sizeBytes,
        extra = now.extra - AS_INSTALLED + was.extra.filterKeys { it in AS_INSTALLED } + FILLED_ONCE.mapNotNull { key -> was.extra[key]?.takeIf { it.isNotEmpty() }?.let { key to it } },
    )

    /**
     * Replaces what the records of installed games say of them with what the server says now, in one write, and returns how
     * many records that changed. A game in [fresh] that is not installed is passed over.
     */
    @Synchronized
    fun renew(context: Context, fresh: Collection<CatalogItem>): Int {
        val stored = stored(context)
        val next = fresh.mapNotNull { now ->
            val entry = stored[now.id] ?: return@mapNotNull null
            val was = entry.item ?: return@mapNotNull null
            renewed(was, now).takeIf { it != was }?.let { Entry(entry.id, entry.path, entry.emulatorId, entry.root, it) }
        }
        if (next.isEmpty()) return 0
        change(context) { it + next.associateBy { e -> e.id } }
        return next.size
    }

    /** The files the record of a game kept in a folder lists, by their place under the ROMs folder, or null when it lists none. */
    internal fun files(context: Context, entry: Entry): Set<String>? =
        lines(context, entry.id)?.takeIf { it.firstOrNull() == entry.root }?.drop(1)?.toCollection(LinkedHashSet())

    /**
     * The folder a game was installed into file by file, which is where the files RomM keeps beside the game can go, or null:
     * a game kept as one file, a disc set and an unpacked archive have none. [own] is the game's own files as the server lists
     * them. The record has to list each, so a folder that holds something else under the ROM's name does not count.
     */
    fun folder(context: Context, entry: Entry, own: List<RommFile>): File? {
        val item = entry.item ?: return null
        if (item.extra[RommCatalog.SINGLE] == "1" || RommCatalog.DISCS in item.extra || entry.root.substringAfterLast('/') != item.extra[RommCatalog.FILE]) return null
        val listed = files(context, entry) ?: return null
        if (own.isEmpty() || own.any { "${entry.root}/${it.path}" !in listed }) return null
        return romsRoot(context)?.let { File(it, entry.root) }?.takeIf { it.isDirectory }
    }

    /** Which of [offered] an installed game has, by id: the ones its record lists that are on the device. Null when it has no folder for them. */
    fun extras(context: Context, entry: Entry, own: List<RommFile>, offered: List<RommFile>): Set<String>? {
        val folder = folder(context, entry, own) ?: return null
        val listed = files(context, entry).orEmpty()
        return offered.filter { "${entry.root}/${it.path}" in listed && File(folder, it.path).isFile }.mapTo(LinkedHashSet()) { it.id }
    }

    /**
     * Changes which files the record of a game kept in a folder lists, and which extra files it says the game has. Paths are
     * under the ROMs folder, and one outside the game's folder is not listed. False for a game with no such record, which
     * stays as it is.
     */
    @Synchronized
    fun refile(context: Context, id: String, added: Collection<String> = emptyList(), removed: Collection<String> = emptyList(), extras: (Set<String>) -> Set<String> = { it }): Boolean {
        val entry = onRecord(context, id) ?: return false
        val listed = files(context, entry) ?: return false
        val files = listed + added.filter { it.startsWith(entry.root + "/") && inside(it) } - removed.toSet()
        val list = kept(context, id)
        val tmp = File(list.path + ".tmp")
        tmp.writeText((listOf(entry.root) + files).joinToString("\n", postfix = "\n"))
        if (!tmp.renameTo(list)) { list.delete(); if (!tmp.renameTo(list)) return false }
        val item = entry.item?.let { RommCatalog.withExtras(it, extras(RommCatalog.chosenExtras(it))) }
        change(context) { it + (id to Entry(entry.id, entry.path, entry.emulatorId, entry.root, item)) }
        return true
    }

    /**
     * Deletes one extra file of an installed game and takes it off its record. Nothing is deleted, and this is false, unless
     * the record lists [file] and it is neither one of the game's [own] files nor what the emulator opens.
     */
    @Synchronized
    fun dropExtra(context: Context, id: String, own: List<RommFile>, file: RommFile): Boolean {
        val entry = onRecord(context, id) ?: return false
        val folder = folder(context, entry, own) ?: return false
        val path = "${entry.root}/${file.path}"
        if (path == entry.path || !inside(path) || own.any { it.path.equals(file.path, ignoreCase = true) } || path !in files(context, entry).orEmpty()) return false
        val target = File(folder, file.path)
        if (target.isDirectory || (target.exists() && !target.delete())) return false
        var dir = target.parentFile
        while (dir != null && dir != folder && dir.delete()) dir = dir.parentFile
        return refile(context, id, removed = listOf(path)) { it - file.id }
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val root = romsRoot(context)
        val entry = entries[id] ?: stored(context)[id]
        if (root != null && entry != null) {
            val target = File(root, entry.root)
            // A game recorded before its files were listed has its folder to itself.
            val files = lines(context, id)?.takeIf { it.firstOrNull() == entry.root }?.drop(1)
            if ('/' in entry.root && target.isDirectory) {
                if (files == null) target.deleteRecursively()
                else {
                    for (name in files) if (name.startsWith(entry.root + "/") && inside(name)) File(root, name).takeIf { it.isFile }?.delete()
                    prune(target)
                }
            } else if (target.isFile) target.delete()
        }
        unclaim(context, id)
        RommDetails.forget(context, id)
        change(context) { it - id }
    }

    private fun prune(folder: File) {
        if (java.nio.file.Files.isSymbolicLink(folder.toPath())) return
        folder.listFiles()?.filter { it.isDirectory }?.forEach(::prune)
        folder.delete()
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        File(context.filesDir, "stores/romm/files").deleteRecursively()
        read = null
        StoresState.post { entries = emptyMap() }
    }

    private fun change(context: Context, edit: (Map<String, Entry>) -> Map<String, Entry>) {
        val f = file(context)
        val next = edit(stored(context))
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(format(next))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        // What was just written is what the file holds, so it is not read back and parsed again.
        read = Triple(f.lastModified(), f.length(), next)
        show(context, next)
    }
}
