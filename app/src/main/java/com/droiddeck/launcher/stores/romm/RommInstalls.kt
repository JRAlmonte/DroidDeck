package com.droiddeck.launcher.stores.romm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONObject
import java.io.File

internal object RommInstalls {
    class Entry(val id: String, val path: String, val emulatorId: String) {
        val guestPath: String get() = "/root/ROMs/$path"
    }

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
            val emulator = e.optString("emulator")
            if (path.isEmpty() || emulator.isEmpty() || path.startsWith("/") || path.split('/').any { it == ".." }) continue
            out[id] = Entry(id, path, emulator)
        }
        return out
    }

    fun format(map: Map<String, Entry>): String {
        val o = JSONObject()
        for (e in map.values) o.put(e.id, JSONObject().put("path", e.path).put("emulator", e.emulatorId))
        return o.toString()
    }

    /** Reads the records and drops the ones whose file is no longer in the ROMs folder. */
    @Synchronized
    fun load(context: Context) {
        val root = romsRoot(context)
        val stored = runCatching { parse(file(context).readText()) }.getOrDefault(emptyMap())
        val present = if (root == null) emptyMap() else stored.filterValues { File(root, it.path).exists() }
        StoresState.post { entries = present }
    }

    @Synchronized
    fun record(context: Context, entry: Entry) = change(context) { it + (entry.id to entry) }

    @Synchronized
    fun remove(context: Context, id: String) {
        val root = romsRoot(context)
        val entry = entries[id] ?: runCatching { parse(file(context).readText()) }.getOrDefault(emptyMap())[id]
        if (root != null && entry != null) File(root, entry.path).delete()
        change(context) { it - id }
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        StoresState.post { entries = emptyMap() }
    }

    private fun change(context: Context, edit: (Map<String, Entry>) -> Map<String, Entry>) {
        val f = file(context)
        val next = edit(runCatching { parse(f.readText()) }.getOrDefault(emptyMap()))
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(format(next))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        load(context)
    }
}
