package com.droiddeck.launcher.ports

import android.content.Context
import com.droiddeck.launcher.core.Downloader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The list of native ports, kept in the repository as ports/ports.json so a port can be added without a new build. */
object PortCatalog {
    const val URL = "https://raw.githubusercontent.com/JRAlmonte/DroidDeck/feat/romm-store/ports/ports.json"

    private const val REFRESH_MS = 6 * 60 * 60 * 1000L
    private val ID = Regex("[a-z0-9][a-z0-9_-]{0,39}")
    private val REPO = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    private val SHA1 = Regex("[0-9a-f]{40}")
    private val WORD = Regex("[a-z0-9][a-z0-9 _-]{0,31}")

    @Volatile private var refreshedAt = 0L

    /** The usable entries, or null when the text is not a catalog at all; an entry with anything missing or unsafe is left out. */
    fun parse(text: String): List<Port>? {
        val array = runCatching { JSONObject(text).getJSONArray("ports") }.getOrNull() ?: return null
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull(::port).distinctBy { it.id }
    }

    private fun relative(path: String): Boolean =
        path.isNotBlank() && path.length <= 200 && '\\' !in path && !path.startsWith("/") && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun words(array: JSONArray?): List<String>? {
        array ?: return null
        val list = (0 until array.length()).map { array.optString(it).lowercase() }
        return list.takeIf { it.isNotEmpty() && it.all(WORD::matches) }
    }

    private fun port(o: JSONObject): Port? {
        val id = o.optString("id").takeIf(ID::matches) ?: return null
        val name = o.optString("name").trim().takeIf { it.isNotEmpty() && it.length <= 80 } ?: return null
        val game = o.optString("game").trim().takeIf { it.isNotEmpty() && it.length <= 120 } ?: return null
        val repo = o.optString("repo").takeIf(REPO::matches) ?: return null
        val asset = o.optString("asset").takeIf { it.isNotEmpty() && it.length <= 200 }?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() } ?: return null
        val entry = o.optString("entry").takeIf(::relative) ?: return null
        val folders = words(o.optJSONArray("folders")) ?: return null
        val types = words(o.optJSONArray("types")) ?: return null
        val form = when (o.optString("form", "plain").lowercase()) { "plain" -> RomForm.PLAIN; "n64" -> RomForm.N64; else -> return null }
        val size = o.optLong("romSize").takeIf { it > 0 } ?: return null
        val sha1 = o.optString("romSha1").lowercase().takeIf(SHA1::matches) ?: return null
        val staged = o.optString("stagedRom").takeIf(::relative) ?: return null
        val markers = o.optJSONArray("markers")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        if (!markers.all(::relative)) return null
        return Port(id, name, game, repo, asset, entry, folders, types.toSet(), form, size, sha1, staged, markers)
    }

    /** The fetched list first, then built-in ports it does not name. */
    fun merge(builtIn: List<Port>, fetched: List<Port>): List<Port> = fetched + builtIn.filter { b -> fetched.none { it.id == b.id } }

    private fun file(context: Context) = File(context.filesDir, "ports/catalog.json")

    fun cached(context: Context): List<Port> = runCatching { parse(file(context).readText()) }.getOrNull().orEmpty()

    /** Fetches the list when the last try is old enough; true when a usable list was stored. */
    @Synchronized
    fun refresh(context: Context, now: Long = System.currentTimeMillis(), fetch: (String) -> String? = Downloader::downloadDirect): Boolean {
        if (refreshedAt != 0L && now - refreshedAt < REFRESH_MS) return false
        refreshedAt = now
        val text = fetch(URL) ?: return false
        if (parse(text).isNullOrEmpty()) return false
        return runCatching {
            val f = file(context)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) } else true
        }.getOrDefault(false)
    }
}
