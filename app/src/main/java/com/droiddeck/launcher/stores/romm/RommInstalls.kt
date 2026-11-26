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

    fun byGuestPath(context: Context, guestPath: String): Entry? =
        entries.values.firstOrNull { it.guestPath == guestPath } ?: stored(context).values.firstOrNull { it.guestPath == guestPath }

    /** The installed games as library items; a record written before games were kept with it has none. */
    fun items(): List<CatalogItem> = entries.values.mapNotNull { it.item }.sortedBy { it.title.lowercase() }

    @Synchronized
    fun load(context: Context) {
        val root = romsRoot(context)
        val stored = runCatching { parse(file(context).readText()) }.getOrDefault(emptyMap())
        val present = if (root == null) emptyMap() else stored.filterValues { File(root, it.path).exists() }
        StoresState.post {
            entries = present
            if (StoresState.library.containsKey(Store.ROMM)) StoresState.library[Store.ROMM] = items()
        }
    }

    @Synchronized
    fun record(context: Context, entry: Entry) = change(context) { it + (entry.id to entry) }

    /** Adds to an installed game's record what the server has since learned of it, such as the id its saves are named by. */
    @Synchronized
    fun learn(context: Context, id: String, known: Map<String, String>) {
        val entry = find(context, id) ?: return
        val item = entry.item ?: return
        val added = known.filter { it.value.isNotEmpty() && item.extra[it.key].isNullOrEmpty() }
        if (added.isEmpty()) return
        change(context) { it + (id to Entry(entry.id, entry.path, entry.emulatorId, entry.root, item.copy(extra = item.extra + added))) }
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val root = romsRoot(context)
        val entry = entries[id] ?: runCatching { parse(file(context).readText()) }.getOrDefault(emptyMap())[id]
        if (root != null && entry != null) {
            val target = File(root, entry.root)
            if ('/' in entry.root && target.isDirectory) target.deleteRecursively() else if (target.isFile) target.delete()
        }
        RommDetails.forget(context, id)
        change(context) { it - id }
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        read = null
        StoresState.post { entries = emptyMap() }
    }

    private fun change(context: Context, edit: (Map<String, Entry>) -> Map<String, Entry>) {
        val f = file(context)
        val next = edit(runCatching { parse(f.readText()) }.getOrDefault(emptyMap()))
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(format(next))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        read = null
        load(context)
    }
}
