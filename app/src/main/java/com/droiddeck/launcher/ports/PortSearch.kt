package com.droiddeck.launcher.ports

import com.droiddeck.launcher.core.DownloadSource
import com.droiddeck.launcher.runtime.UserApps
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object PortSearch {
    class Reply(val status: Int, val body: String?, val remaining: Int? = null, val resetAt: Long? = null, val retryAfter: Long? = null)

    class Hit(val repo: String, val about: String)

    enum class Verdict { CONFIRMED, UNCONFIRMED, NO_BUILD, NO_HASH, UNREACHABLE }

    class Candidate(val hit: Hit, val verdict: Verdict, val assets: List<String> = emptyList(), val entry: Port? = null) {
        val addable: Boolean get() = verdict == Verdict.CONFIRMED || verdict == Verdict.UNCONFIRMED
    }

    /** GitHub refused for now; [resetAt] is the time it takes requests again, in epoch seconds, when it said. */
    class Limited(val resetAt: Long?)

    class Outcome(val candidates: List<Candidate>, val limited: Limited?, val unreachable: Boolean = false)

    const val MAX_HITS = 6
    private const val MAX_TEXT = 512 * 1024
    private const val MAX_BODY = 8 * 1024 * 1024
    private const val MAX_FILES = 6
    private const val KEEP_MS = 10 * 60 * 1000L
    private val BRACKETS = Regex("\\([^)]*\\)|\\[[^\\]]*]")
    private val ARTICLE = Regex("^(.*), (The|A|An)( - .*)?$", RegexOption.IGNORE_CASE)
    private val HASH = Regex("(?<![0-9a-f])(?:[0-9a-f]{40}|[0-9a-f]{32})(?![0-9a-f])")
    private val HASH_FILE = Regex("sha1|md5|checksum|hash|baserom", RegexOption.IGNORE_CASE)
    private val TEXT_FILE = Regex("^[^.]+$|\\.(txt|md|sha1|md5|sfv|json|ya?ml|toml|ini|cfg|csv)$", RegexOption.IGNORE_CASE)
    private val READMES = listOf("README.md", "README", "readme.md", "README.txt", "README.rst")

    private val kept = HashMap<String, Pair<Long, Candidate>>()

    fun terms(title: String): String {
        val bare = title.replace(BRACKETS, " ").replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        val turned = ARTICLE.find(bare)?.let { "${it.groupValues[2]} ${it.groupValues[1]}${it.groupValues[3]}" } ?: bare
        return turned.replace(" - ", ": ").take(80)
    }

    fun searchUrl(words: String): String {
        val q = "\"" + words.replace("\"", " ").trim() + "\" recomp OR decomp OR port in:name,description"
        return "https://api.github.com/search/repositories?per_page=$MAX_HITS&q=" + URLEncoder.encode(q, "UTF-8")
    }

    fun hits(body: String): List<Hit>? = runCatching {
        val items = JSONObject(body).getJSONArray("items")
        (0 until items.length()).mapNotNull { items.optJSONObject(it) }.mapNotNull { o ->
            UserApps.githubRepo(o.optString("full_name"))?.let { Hit(it, o.optString("description").takeIf { d -> d != "null" }.orEmpty().take(140)) }
        }.take(MAX_HITS)
    }.getOrNull()

    fun limited(reply: Reply): Limited? = when {
        reply.status != 403 && reply.status != 429 -> null
        reply.retryAfter != null -> Limited(System.currentTimeMillis() / 1000 + reply.retryAfter)
        reply.remaining == 0 || reply.status == 429 -> Limited(reply.resetAt)
        else -> null
    }

    fun hashes(text: String): Set<String> = HASH.findAll(text.lowercase()).map { it.value }.toSet()

    fun names(text: String, rom: PortRoms.Facts): Boolean = hashes(text).let { rom.sha1 in it || (rom.md5.isNotEmpty() && rom.md5 in it) }

    fun hashFiles(tree: String): List<String> = runCatching {
        val items = JSONObject(tree).getJSONArray("tree")
        (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .filter { it.optString("type") == "blob" && it.optLong("size") in 1..MAX_TEXT }
            .map { it.optString("path") }
            .filter { p -> p.substringAfterLast('/').let { HASH_FILE.containsMatchIn(it) && TEXT_FILE.containsMatchIn(it) } }
            .sortedWith(compareBy<String> { p -> p.count { it == '/' } }.thenBy { it })
            .take(MAX_FILES)
    }.getOrDefault(emptyList())

    private fun raw(repo: String, path: String) =
        "https://raw.githubusercontent.com/$repo/HEAD/" + path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    fun check(hit: Hit, rom: PortRoms.Facts, get: (String) -> Reply): Pair<Candidate, Limited?> {
        val releases = get("https://api.github.com/repos/${hit.repo}/releases?per_page=15")
        limited(releases)?.let { return Candidate(hit, Verdict.UNREACHABLE) to it }
        val list = releases.body?.takeIf { releases.status == 200 }?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return Candidate(hit, Verdict.UNREACHABLE) to null
        val offering = PortSources.offering(list)
        if (offering.isEmpty()) return Candidate(hit, Verdict.NO_BUILD) to null
        val assets = offering.filter { it.second }.map { it.first }
        if (assets.isEmpty()) return Candidate(hit, Verdict.NO_HASH) to null
        val own = get(raw(hit.repo, "ports/ports.json")).takeIf { it.status == 200 }?.body?.let { PortCatalog.parse(it, hit.repo) }.orEmpty()
            .firstOrNull { p -> p.repo.equals(hit.repo, ignoreCase = true) && p.romSha1 == rom.sha1 && assets.any { p.asset.containsMatchIn(it) } }
        if (own != null) return Candidate(hit, Verdict.CONFIRMED, assets.filter { own.asset.containsMatchIn(it) }, own) to null
        val readme = READMES.firstNotNullOfOrNull { name -> get(raw(hit.repo, name)).takeIf { it.status == 200 }?.body }
        if (readme != null && names(readme, rom)) return Candidate(hit, Verdict.CONFIRMED, assets) to null
        val tree = get("https://api.github.com/repos/${hit.repo}/git/trees/HEAD?recursive=1")
        limited(tree)?.let { return Candidate(hit, Verdict.UNCONFIRMED, assets) to it }
        val found = tree.body?.takeIf { tree.status == 200 }?.let(::hashFiles).orEmpty()
            .any { path -> get(raw(hit.repo, path)).let { it.status == 200 && it.body != null && names(it.body, rom) } }
        return Candidate(hit, if (found) Verdict.CONFIRMED else Verdict.UNCONFIRMED, assets) to null
    }

    fun find(
        words: String, rom: PortRoms.Facts, now: Long = System.currentTimeMillis(), get: (String) -> Reply = ::get,
        onCandidate: (Candidate) -> Unit = {},
    ): Outcome {
        val typed = UserApps.githubRepo(words)
        val found = if (typed != null) listOf(Hit(typed, "")) else {
            val reply = get(searchUrl(words))
            limited(reply)?.let { return Outcome(emptyList(), it) }
            reply.body?.takeIf { reply.status == 200 }?.let(::hits) ?: return Outcome(emptyList(), null, unreachable = true)
        }
        val out = ArrayList<Candidate>()
        for (hit in found) {
            val key = hit.repo.lowercase() + " " + rom.sha1
            val known = synchronized(kept) { kept[key]?.takeIf { now - it.first in 0 until KEEP_MS }?.second }
            val (candidate, limit) = if (known != null) Candidate(hit, known.verdict, known.assets, known.entry) to null else check(hit, rom, get)
            if (limit != null && !candidate.addable) return Outcome(out, limit)
            if (known == null && limit == null && candidate.verdict != Verdict.UNREACHABLE) synchronized(kept) { kept[key] = now to candidate }
            out += candidate
            onCandidate(candidate)
            if (limit != null) return Outcome(out, limit)
        }
        return Outcome(out, null)
    }

    fun get(url: String): Reply {
        var connection: HttpURLConnection? = null
        return try {
            val proxy = DownloadSource.httpProxy()
            connection = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()) as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "DroidDeck-Android")
            if (url.startsWith("https://api.github.com/")) connection.setRequestProperty("Accept", "application/vnd.github+json")
            val status = connection.responseCode
            val body = if (status == 200) connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (out.size() <= MAX_BODY) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            } else null
            Reply(
                status, body, connection.getHeaderField("x-ratelimit-remaining")?.toIntOrNull(),
                connection.getHeaderField("x-ratelimit-reset")?.toLongOrNull(), connection.getHeaderField("retry-after")?.toLongOrNull(),
            )
        } catch (e: Exception) {
            Reply(0, null)
        } finally {
            connection?.disconnect()
        }
    }
}
