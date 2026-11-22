package com.droiddeck.launcher.ports

import android.content.Context
import com.droiddeck.launcher.core.Hashes
import org.json.JSONArray
import org.json.JSONObject

/** Where ports come from: GitHub repositories holding a ports/ports.json, and single ports added for one ROM or read from a file. */
object PortSources {
    class Source(val repo: String, val branch: String?, val port: JSONObject?) {
        val key: String get() = if (port != null) "port:" + port.optString("id") else "list:$repo" + (branch?.let { "@$it" } ?: "")
        val fileName: String get() = (repo + (branch?.let { "@$it" } ?: "")).replace('/', '~')
    }

    private const val PREFS = "port_sources"
    private const val KEY = "sources"
    private const val MAX_LISTS = 50
    private const val MAX_PORTS = 200
    private val OWNER = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
    private val NAME = Regex("[A-Za-z0-9_.-]{1,100}")
    private val BRANCH = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,99}")
    private val URL = Regex("^(?:https?://)?(?:www\\.)?github\\.com/([^/\\s]+)/([^/#?\\s]+)(?:/tree/([^#?\\s]+?))?/?$", RegexOption.IGNORE_CASE)
    private val SHORT = Regex("^([^/@\\s]+)/([^/@\\s]+)(?:@(\\S+))?$")
    private val THIS_DEVICE = Regex("arm64|aarch64", RegexOption.IGNORE_CASE)
    private val OTHER_SYSTEM = Regex("windows|win32|win64|[-_.]win[-_.]|macos|darwin|osx|android|(^|[-_.])ios([-_.]|$)|switch|\\.(exe|msi|dmg|apk|ipa|nro|txt|sig|asc|sha\\d*|json)$", RegexOption.IGNORE_CASE)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A list source from what the user typed: owner/name, owner/name@branch or a github.com address. */
    fun parse(input: String): Source? {
        val m = URL.find(input.trim()) ?: SHORT.find(input.trim()) ?: return null
        val owner = m.groupValues[1].takeIf(OWNER::matches) ?: return null
        val name = m.groupValues[2].removeSuffix(".git").takeIf { NAME.matches(it) && it != "." && it != ".." } ?: return null
        val branch = m.groupValues[3].takeIf { it.isNotEmpty() }
        if (branch != null && (!BRANCH.matches(branch) || ".." in branch || "//" in branch || branch.endsWith("/"))) return null
        return Source("$owner/$name", branch, null)
    }

    fun list(context: Context): List<Source> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val typed = parse(o.getString("repo") + (o.optString("branch").takeIf { it.isNotEmpty() }?.let { "@$it" } ?: "")) ?: return@mapNotNull null
                Source(typed.repo, typed.branch, o.optJSONObject("port"))
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, sources: List<Source>) {
        val array = JSONArray()
        sources.forEach { s -> array.put(JSONObject().put("repo", s.repo).apply { s.branch?.let { put("branch", it) }; s.port?.let { put("port", it) } }) }
        prefs(context).edit().putString(KEY, array.toString()).commit()
    }

    /** False when it is already there or there is no room for another of its kind. */
    @Synchronized
    fun add(context: Context, source: Source): Boolean {
        val current = list(context)
        val room = if (source.port == null) MAX_LISTS - current.count { it.port == null } else MAX_PORTS - current.count { it.port != null }
        if (room <= 0 || current.any { it.key == source.key }) return false
        save(context, current + source)
        return true
    }

    @Synchronized
    fun remove(context: Context, key: String) = save(context, list(context).filterNot { it.key == key })

    class Imported(val added: Int, val skipped: Int)

    /** Adds each port of a ports.json text as a single port, leaving out ids in [taken]; null when the text is no list. */
    @Synchronized
    fun import(context: Context, text: String, taken: Set<String>): Imported? {
        val ports = PortCatalog.parse(text) ?: return null
        val current = list(context)
        val known = taken + current.mapNotNull { it.port?.optString("id") }
        val room = maxOf(MAX_PORTS - current.count { it.port != null }, 0)
        val fresh = ports.filter { it.id !in known && parse(it.repo) != null }.take(room)
        if (fresh.isNotEmpty()) save(context, current + fresh.map { Source(it.repo, null, PortCatalog.json(it)) })
        return Imported(fresh.size, ports.size - fresh.size)
    }

    /** Every port the user has as one ports.json text: a backup, or a list to put in a repository. */
    fun export(context: Context): String = PortCatalog.write(PortCatalog.user(context).distinctBy { it.id })

    /** Remembers the program found in a single port's download, so it need not be looked for again. */
    @Synchronized
    fun setProgram(context: Context, id: String, program: String) {
        val current = list(context)
        val at = current.indexOfFirst { it.port?.optString("id") == id }.takeIf { it >= 0 } ?: return
        current[at].port?.put("entry", program)
        save(context, current)
    }

    /** The downloads of a release this device can run. */
    fun deviceAssets(names: List<String>): List<String> = names.filter { THIS_DEVICE.containsMatchIn(it) && !OTHER_SYSTEM.containsMatchIn(it) }

    /** The runnable downloads of the newest release that has any, a full release before a prerelease. */
    fun offered(releases: JSONArray): List<String> = offering(releases).map { it.first }

    /** [offered], each with whether GitHub lists the hash an install needs. */
    fun offering(releases: JSONArray): List<Pair<String, Boolean>> {
        val found = (0 until releases.length()).mapNotNull { i ->
            val r = releases.optJSONObject(i)?.takeIf { !it.optBoolean("draft") } ?: return@mapNotNull null
            val assets = r.optJSONArray("assets") ?: return@mapNotNull null
            val hashed = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .associate { it.optString("name") to (Hashes.githubSha256(it.optString("digest")) != null) }
            val names = deviceAssets(hashed.keys.toList())
            if (names.isEmpty()) null else r.optBoolean("prerelease") to names.map { it to hashed.getValue(it) }
        }
        return (found.firstOrNull { !it.first } ?: found.firstOrNull())?.second.orEmpty()
    }

    /** A pattern for a download's name that still fits when the version in it changes. */
    fun assetPattern(name: String): String =
        "^" + Regex("\\d+").split(name).joinToString("\\d+") { part -> part.replace(Regex("[\\\\.\\[\\]{}()*+?^$|]")) { "\\" + it.value } } + "$"

    /** The port a list gives for this repository when it plays some other ROM than this one. */
    fun otherGame(repo: String, rom: PortRoms.Facts, listed: List<Port>): Port? =
        listed.firstOrNull { it.repo.equals(repo, ignoreCase = true) }?.takeIf { it.romSha1 != rom.sha1 }

    /** The entry for one port of one ROM, or null when something in it would not pass the list's checks. */
    fun single(repo: String, asset: String, game: String, rom: PortRoms.Facts, program: String, stagedRom: String, taken: Set<String>): JSONObject? {
        val name = repo.substringAfter('/')
        val base = "u-" + name.lowercase().replace(Regex("[^a-z0-9_-]"), "-").take(34)
        val id = (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "$base-$it" }).first { it !in taken }
        val o = JSONObject()
            .put("id", id).put("name", name.take(80)).put("game", game.trim().take(120)).put("repo", repo)
            .put("asset", assetPattern(asset)).put("entry", program.trim()).put("folders", JSONArray(listOf(rom.folder)))
            .put("types", JSONArray(rom.exts)).put("form", if (rom.form == RomForm.N64) "n64" else "plain")
            .put("romSize", rom.size).put("romSha1", rom.sha1).put("stagedRom", stagedRom.trim())
        return o.takeIf { PortCatalog.single(it, repo) != null }
    }
}
