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
    private const val MAX_CHARS = 512 * 1024
    private const val MAX_PORTS = 200
    private val ID = Regex("[a-z0-9][a-z0-9_-]{0,39}")
    private val REPO = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    private val SHA1 = Regex("[0-9a-f]{40}")
    private val WORD = Regex("[a-z0-9][a-z0-9 _-]{0,31}")

    private val refreshedAt = HashMap<String, Long>()

    /** The user's lists whose last fetch gave nothing usable, by [PortSources.Source.key]. */
    @Volatile var failed: Set<String> = emptySet()
        private set

    fun url(repo: String, branch: String?) = "https://raw.githubusercontent.com/$repo/${branch ?: "HEAD"}/ports/ports.json"

    /** The usable entries, or null when the text is not a catalog at all; an entry with anything missing or unsafe is left out. */
    fun parse(text: String, source: String? = null): List<Port>? {
        if (text.length > MAX_CHARS) return null
        val array = runCatching { JSONObject(text).getJSONArray("ports") }.getOrNull() ?: return null
        return (0 until minOf(array.length(), MAX_PORTS)).mapNotNull { array.optJSONObject(it) }.mapNotNull { port(it, source, loose = false) }.distinctBy { it.id }
    }

    /** One port the user added by hand: the program and the ROM's staged name may be left open. */
    fun single(o: JSONObject, source: String): Port? = port(o, source, loose = true)

    private fun relative(path: String): Boolean =
        path.isNotBlank() && path.length <= 200 && '\\' !in path && !path.startsWith("/") && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun words(array: JSONArray?): List<String>? {
        array ?: return null
        val list = (0 until array.length()).map { array.optString(it).lowercase() }
        return list.takeIf { it.isNotEmpty() && it.all(WORD::matches) }
    }

    private fun port(o: JSONObject, source: String?, loose: Boolean): Port? {
        val id = o.optString("id").takeIf(ID::matches) ?: return null
        val name = o.optString("name").trim().takeIf { it.isNotEmpty() && it.length <= 80 } ?: return null
        val game = o.optString("game").trim().takeIf { it.isNotEmpty() && it.length <= 120 } ?: return null
        val repo = o.optString("repo").takeIf(REPO::matches) ?: return null
        val asset = o.optString("asset").takeIf { it.isNotEmpty() && it.length <= 200 }?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() } ?: return null
        val entry = o.optString("entry").takeIf { (loose && it.isEmpty()) || relative(it) } ?: return null
        val folders = words(o.optJSONArray("folders")) ?: return null
        val types = words(o.optJSONArray("types")) ?: return null
        val form = when (o.optString("form", "plain").lowercase()) { "plain" -> RomForm.PLAIN; "n64" -> RomForm.N64; else -> return null }
        val size = o.optLong("romSize").takeIf { it > 0 } ?: return null
        val sha1 = o.optString("romSha1").lowercase().takeIf(SHA1::matches) ?: return null
        val staged = o.optString("stagedRom").takeIf { (loose && it.isEmpty()) || relative(it) } ?: return null
        val markers = o.optJSONArray("markers")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        if (!markers.all(::relative)) return null
        return Port(id, name, game, repo, asset, entry, folders, types.toSet(), form, size, sha1, staged, markers, source)
    }

    /** The fetched list first, then built-in ports it does not name. */
    fun merge(builtIn: List<Port>, fetched: List<Port>): List<Port> = fetched + builtIn.filter { b -> fetched.none { it.id == b.id } }

    /** The user's ports after ours, in the order given; one whose name is taken is left out, so it can never replace a listed port. */
    fun combine(builtIn: List<Port>, fetched: List<Port>, user: List<Port>): List<Port> =
        (merge(builtIn, fetched) + user).distinctBy { it.id }

    private fun file(context: Context) = File(context.filesDir, "ports/catalog.json")

    private fun file(context: Context, source: PortSources.Source) = File(context.filesDir, "ports/sources/${source.fileName}.json")

    fun cached(context: Context): List<Port> = runCatching { parse(file(context).readText()) }.getOrNull().orEmpty()

    fun cached(context: Context, source: PortSources.Source): List<Port> =
        runCatching { parse(file(context, source).readText(), source.repo) }.getOrNull().orEmpty()

    fun user(context: Context): List<Port> = PortSources.list(context).flatMap { s ->
        if (s.port != null) listOfNotNull(single(s.port, s.repo)) else cached(context, s)
    }

    fun forget(context: Context, source: PortSources.Source) {
        file(context, source).delete()
        synchronized(this) { refreshedAt.remove(url(source.repo, source.branch)) }
        failed = failed - source.key
    }

    /** Fetches each list whose last try is old enough, [force] regardless; true when a usable list was stored. */
    @Synchronized
    fun refresh(
        context: Context, now: Long = System.currentTimeMillis(), force: PortSources.Source? = null,
        fetch: (String) -> String? = Downloader::downloadDirect,
    ): Boolean {
        var stored = fetchInto(URL, file(context), null, now, false, fetch)
        for (s in PortSources.list(context)) {
            if (s.port == null) stored = fetchInto(url(s.repo, s.branch), file(context, s), s.key, now, force?.key == s.key, fetch) || stored
        }
        return stored
    }

    private fun fetchInto(url: String, f: File, key: String?, now: Long, force: Boolean, fetch: (String) -> String?): Boolean {
        val last = refreshedAt[url]
        if (!force && last != null && now - last in 0 until REFRESH_MS) return false
        refreshedAt[url] = now
        val text = fetch(url)?.takeIf { !parse(it).isNullOrEmpty() }
        val ok = text != null && runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) } else true
        }.getOrDefault(false)
        if (key != null) failed = if (ok) failed - key else failed + key
        return ok
    }
}
