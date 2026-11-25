package com.droiddeck.launcher.ports

import android.content.Context
import com.droiddeck.launcher.core.Hashes
import org.json.JSONArray
import org.json.JSONObject

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
    private val OTHER_SYSTEM = Regex("windows|win32|win64|[-_.]win[-_.]|macos|darwin|osx|android|(^|[-_.])ios([-_.]|$)|switch|\\.(exe|msi|dmg|apk|ipa|nro|txt|sig|asc|sha\\d*|json|deb|rpm|flatpak|snap|pkg|zsync|md|rar|7z|bz2|zst)$", RegexOption.IGNORE_CASE)
    private val LINUX = Regex("linux|\\.appimage$", RegexOption.IGNORE_CASE)
    private val OTHER_CPU = Regex("(^|[-_. ])(arm(v\\d+\\w*|hf|el|32)?|riscv\\d*|ppc\\w*|i[36]86|x86(?![-_]?64)|x32|32[-_]?bit)([-_. ]|$)", RegexOption.IGNORE_CASE)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

    fun export(context: Context): String = PortCatalog.write(PortCatalog.user(context).distinctBy { it.id })

    fun own(context: Context, id: String): JSONObject? = list(context).firstNotNullOfOrNull { s -> s.port?.takeIf { it.optString("id") == id } }

    @Synchronized
    fun setLaunch(context: Context, id: String, program: String, stagedRom: String, args: List<String>, env: Map<String, String>): Boolean {
        val current = list(context)
        val at = current.indexOfFirst { it.port?.optString("id") == id }.takeIf { it >= 0 } ?: return false
        val changed = JSONObject(current[at].port.toString()).put("entry", program.trim()).put("stagedRom", stagedRom.trim())
            .put("args", JSONArray(args)).put("env", JSONObject(env))
        if (PortCatalog.single(changed, current[at].repo) == null) return false
        save(context, current.toMutableList().also { it[at] = Source(current[at].repo, current[at].branch, changed) })
        return true
    }

    /** The downloads of a release this device takes: the ARM64 ones, or without any, the Linux ones for x86-64 PCs, which run through FEX. */
    fun deviceAssets(names: List<String>): List<String> =
        names.filter { THIS_DEVICE.containsMatchIn(it) && !OTHER_SYSTEM.containsMatchIn(it) }
            .ifEmpty { names.filter { LINUX.containsMatchIn(it) && !OTHER_SYSTEM.containsMatchIn(it) && !OTHER_CPU.containsMatchIn(it) } }

    fun throughFex(asset: String): Boolean = !THIS_DEVICE.containsMatchIn(asset)

    fun offered(releases: JSONArray): List<String> = offering(releases).map { it.first }

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

    fun assetPattern(name: String): String =
        "^" + Regex("\\d+").split(name).joinToString("\\d+") { part -> part.replace(Regex("[\\\\.\\[\\]{}()*+?^$|]")) { "\\" + it.value } } + "$"

    fun otherGame(repo: String, rom: PortRoms.Facts, listed: List<Port>): Port? =
        listed.firstOrNull { it.repo.equals(repo, ignoreCase = true) }?.takeIf { it.romSha1 != rom.sha1 }

    private fun freshId(repo: String, taken: Set<String>): String {
        val base = "u-" + repo.substringAfter('/').lowercase().replace(Regex("[^a-z0-9_-]"), "-").take(34)
        return (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "$base-$it" }).first { it !in taken }
    }

    fun adopted(
        theirs: Port, program: String, stagedRom: String, args: List<String>, env: Map<String, String>, taken: Set<String>, have: List<Port> = emptyList(),
    ): JSONObject? {
        val id = freshId(theirs.repo, taken)
        val shared = have.firstOrNull { it.repo.equals(theirs.repo, ignoreCase = true) && it.asset.pattern == theirs.asset.pattern }?.tool
            ?: theirs.tool.takeIf { it != theirs.id && it !in taken && have.none { p -> p.tool == it && !p.repo.equals(theirs.repo, ignoreCase = true) } }
        val o = PortCatalog.json(theirs).put("id", id).put("entry", program.trim()).put("stagedRom", stagedRom.trim())
            .put("args", JSONArray(args)).put("env", JSONObject(env))
        o.remove("tool")
        if (shared != null) o.put("tool", shared)
        return o.takeIf { PortCatalog.single(it, theirs.repo) != null }
    }

    fun single(
        repo: String, asset: String, game: String, rom: PortRoms.Facts, program: String, stagedRom: String, taken: Set<String>,
        args: List<String> = emptyList(), env: Map<String, String> = emptyMap(), have: List<Port> = emptyList(),
    ): JSONObject? {
        val name = repo.substringAfter('/')
        val id = freshId(repo, taken)
        val o = JSONObject()
            .put("id", id).put("name", name.take(80)).put("game", game.trim().take(120)).put("repo", repo)
            .put("asset", assetPattern(asset)).put("entry", program.trim()).put("folders", JSONArray(listOf(rom.folder)))
            .put("types", JSONArray(rom.exts)).put("form", if (rom.form == RomForm.N64) "n64" else "plain")
            .put("romSize", rom.size).put("romSha1", rom.sha1).put("stagedRom", stagedRom.trim())
        have.firstOrNull { it.repo.equals(repo, ignoreCase = true) && it.asset.pattern == assetPattern(asset) }?.let { o.put("tool", it.tool) }
        if (args.isNotEmpty()) o.put("args", JSONArray(args))
        if (env.isNotEmpty()) o.put("env", JSONObject(env))
        return o.takeIf { PortCatalog.single(it, repo) != null }
    }
}
