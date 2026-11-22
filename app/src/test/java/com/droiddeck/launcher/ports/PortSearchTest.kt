package com.droiddeck.launcher.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class PortSearchTest {
    private val sha1 = "ab".repeat(20)
    private val md5 = "cd".repeat(16)
    private fun rom(hash: String = sha1) = PortRoms.Facts("n64", listOf("z64"), RomForm.N64, 1024, hash, md5)

    private val digest = "sha256:" + "12".repeat(32)
    private fun releases(vararg assets: String) =
        "[{\"assets\":[" + assets.joinToString(",") { "{\"name\":\"${it.removeSuffix("!")}\"" + (if (it.endsWith("!")) "" else ",\"digest\":\"$digest\"") + "}" } + "]}]"

    private fun items(vararg repos: String) = "{\"items\":[" + repos.joinToString(",") { "{\"full_name\":\"$it\",\"description\":null}" } + "]}"

    private class Web(val pages: Map<String, PortSearch.Reply>) {
        val asked = ArrayList<String>()
        fun get(url: String): PortSearch.Reply {
            asked += url
            return pages.entries.firstOrNull { it.key in url }?.value ?: PortSearch.Reply(404, null)
        }
    }

    private fun ok(body: String) = PortSearch.Reply(200, body, remaining = 30)

    @Test fun aTitleBecomesSearchWords() {
        assertEquals("Banjo-Kazooie", PortSearch.terms("Banjo-Kazooie (USA) (Rev 1) [!]"))
        assertEquals("The Legend of Zelda: Majora's Mask", PortSearch.terms("Legend of Zelda, The - Majora's Mask (USA)"))
        assertEquals("3 Count Bout", PortSearch.terms("3_Count_Bout"))
        val url = PortSearch.searchUrl("Perfect \"Dark\"")
        assertTrue(url, url.startsWith("https://api.github.com/search/repositories?per_page=6&q=%22Perfect++Dark%22+recomp+OR+decomp+OR+port+in%3Aname%2Cdescription"))
    }

    @Test fun hashesAreFoundAsWholeWordsInAnyCase() {
        val text = "ROM sha1: ${sha1.uppercase()}\nmd5 `$md5` and a longer one ${"ab".repeat(32)}"
        assertEquals(setOf(sha1, md5), PortSearch.hashes(text))
        assertTrue(PortSearch.names(text, rom()))
        assertTrue(PortSearch.names("only $md5", rom("ef".repeat(20))))
        assertFalse(PortSearch.names("x${sha1}", PortRoms.Facts("n64", listOf("z64"), RomForm.N64, 1, sha1.drop(1) + "0")))
        assertFalse(PortSearch.names("nothing here", PortRoms.Facts("n64", listOf("z64"), RomForm.N64, 1, sha1)))
    }

    @Test fun onlySmallTextFilesNamedForHashesAreRead() {
        val tree = """{"tree":[
            {"path":"src/hash.c","type":"blob","size":10},{"path":"deep/a/b/checksums.md5","type":"blob","size":10},
            {"path":"sm64.us.sha1","type":"blob","size":60},{"path":"docs/supportedHashes.json","type":"blob","size":900},
            {"path":"hashes","type":"tree"},{"path":"big.sha1","type":"blob","size":9000000},{"path":"README.md","type":"blob","size":10}]}"""
        assertEquals(listOf("sm64.us.sha1", "docs/supportedHashes.json", "deep/a/b/checksums.md5"), PortSearch.hashFiles(tree))
        assertEquals(emptyList<String>(), PortSearch.hashFiles("nonsense"))
    }

    @Test fun aRepositoryIsJudgedByItsBuildAndTheRomsHash() {
        val readme = Web(mapOf("/releases" to ok(releases("p-arm64.zip", "p-x64.zip")), "README.md" to ok("needs $sha1")))
        val (confirmed, _) = PortSearch.check(PortSearch.Hit("o/p", ""), rom(), readme::get)
        assertEquals(PortSearch.Verdict.CONFIRMED, confirmed.verdict)
        assertEquals(listOf("p-arm64.zip"), confirmed.assets)
        assertFalse(readme.asked.any { "git/trees" in it })

        val file = Web(mapOf(
            "/releases" to ok(releases("p-arm64.zip")), "README.md" to ok("no hash"),
            "git/trees" to ok("""{"tree":[{"path":"rom sums/us.md5","type":"blob","size":40}]}"""), "rom%20sums/us.md5" to ok("$md5  baserom.us.z64"),
        ))
        assertEquals(PortSearch.Verdict.CONFIRMED, PortSearch.check(PortSearch.Hit("o/p", ""), rom("ef".repeat(20)), file::get).first.verdict)

        val other = Web(mapOf("/releases" to ok(releases("p-arm64.zip")), "README.md" to ok("needs ${"99".repeat(20)}"), "git/trees" to ok("{\"tree\":[]}")))
        val unconfirmed = PortSearch.check(PortSearch.Hit("o/p", ""), rom(), other::get).first
        assertEquals(PortSearch.Verdict.UNCONFIRMED, unconfirmed.verdict)
        assertTrue(unconfirmed.addable)

        val none = Web(mapOf("/releases" to ok(releases("p-x64.zip", "p-windows-arm64.zip"))))
        assertEquals(PortSearch.Verdict.NO_BUILD, PortSearch.check(PortSearch.Hit("o/p", ""), rom(), none::get).first.verdict)
        assertEquals(1, none.asked.size)
        val unhashed = Web(mapOf("/releases" to ok(releases("p-arm64.zip!"))))
        assertEquals(PortSearch.Verdict.NO_HASH, PortSearch.check(PortSearch.Hit("o/p", ""), rom(), unhashed::get).first.verdict)
        assertEquals(PortSearch.Verdict.UNREACHABLE, PortSearch.check(PortSearch.Hit("o/p", ""), rom(), Web(emptyMap())::get).first.verdict)
    }

    @Test fun aRepositoryThatShipsItsOwnEntryForTheRomGivesItsSettings() {
        fun entry(repo: String, hash: String) = """{"id":"mine","name":"Mine","game":"Game","repo":"$repo","asset":"p-\\d+-arm64\\.zip$",
            "folders":["n64"],"types":["z64"],"romSize":1024,"romSha1":"$hash","env":{"ROM":"{rom}"},"args":["--fast"],"tool":"suite","markers":["portable.txt"]}"""
        val web = Web(mapOf(
            "/releases" to ok(releases("p-2-arm64.zip", "other-arm64.zip")),
            "ports/ports.json" to ok("""{"ports":[${entry("else/where", sha1)},${entry("O/P", "99".repeat(20)).replace("mine", "wrong")},${entry("O/P", sha1).replace("mine", "right")}]}"""),
        ))
        val found = PortSearch.check(PortSearch.Hit("o/p", ""), rom(), web::get).first
        assertEquals(PortSearch.Verdict.CONFIRMED, found.verdict)
        assertEquals("right", found.entry!!.id)
        assertEquals(listOf("p-2-arm64.zip"), found.assets)
        assertFalse(web.asked.any { "README" in it })

        val taken = PortSources.adopted(found.entry!!, "bin/p", "", listOf("--slow"), mapOf("ROM" to "{rom}"), setOf("u-p"))!!
        val port = PortCatalog.single(taken, "o/p")!!
        assertEquals("u-p-2", port.id)
        assertEquals("suite", port.tool)
        assertEquals(listOf("--slow"), port.args)
        assertEquals("bin/p", port.entry)
        assertEquals(listOf("portable.txt"), port.markers)
        assertTrue(port.asset.containsMatchIn("p-3-arm64.zip"))
        val stranger = PortCatalog.single(org.json.JSONObject(entry("else/where", sha1)), "else/where")!!
        assertEquals("u-p", PortCatalog.single(PortSources.adopted(found.entry!!, "", "", emptyList(), emptyMap(), emptySet(), listOf(stranger))!!, "o/p")!!.tool)
        assertNull(PortSources.adopted(found.entry!!, "../sh", "", emptyList(), emptyMap(), emptySet()))

        val unrelated = Web(mapOf("/releases" to ok(releases("p-2-arm64.zip")), "ports/ports.json" to ok("""{"ports":[${entry("else/where", sha1)}]}"""), "README.md" to ok("needs $sha1")))
        val plain = PortSearch.check(PortSearch.Hit("o/p", ""), rom(), unrelated::get).first
        assertEquals(PortSearch.Verdict.CONFIRMED, plain.verdict)
        assertNull(plain.entry)
    }

    @Test fun aLimitIsToldApartFromOtherRefusals() {
        assertEquals(1700L, PortSearch.limited(PortSearch.Reply(403, null, remaining = 0, resetAt = 1700))!!.resetAt)
        assertNotNull(PortSearch.limited(PortSearch.Reply(429, null)))
        assertNotNull(PortSearch.limited(PortSearch.Reply(403, null, remaining = 12, retryAfter = 60))!!.resetAt)
        assertNull(PortSearch.limited(PortSearch.Reply(403, null, remaining = 12)))
        assertNull(PortSearch.limited(PortSearch.Reply(404, null, remaining = 0)))
        assertNull(PortSearch.limited(PortSearch.Reply(200, "{}", remaining = 0)))
    }

    @Test fun aSearchStopsAtTheLimitAndKeepsWhatItHas() {
        val limit = PortSearch.Reply(403, null, remaining = 0, resetAt = 1700)
        val blocked = PortSearch.find("Some Game", rom("01".repeat(20)), get = Web(mapOf("search/repositories" to limit))::get)
        assertEquals(1700L, blocked.limited!!.resetAt)
        assertEquals(emptyList<PortSearch.Candidate>(), blocked.candidates)

        val web = Web(mapOf(
            "search/repositories" to ok(items("a/one", "b/two", "c/three")),
            "repos/a/one/releases" to ok(releases("one-arm64.zip")), "a/one/HEAD/README.md" to ok("sha1 ${"02".repeat(20)}"),
            "repos/b/two/releases" to limit,
        ))
        val seen = ArrayList<String>()
        val partial = PortSearch.find("Some Game", rom("02".repeat(20)), get = web::get) { seen += it.hit.repo }
        assertEquals(listOf("a/one"), partial.candidates.map { it.hit.repo })
        assertEquals(listOf("a/one"), seen)
        assertNotNull(partial.limited)
        assertFalse(web.asked.any { "c/three" in it })

        assertTrue(PortSearch.find("Some Game", rom("03".repeat(20)), get = Web(emptyMap())::get).unreachable)
    }

    @Test fun aTypedRepositoryIsCheckedWithoutSearchingAndRemembered() {
        val web = Web(mapOf("repos/Own/Port/releases" to ok(releases("p-arm64.zip")), "README.md" to ok(sha1.replaceRange(0, 2, "04"))))
        val hash = "04" + sha1.drop(2)
        val first = PortSearch.find("https://github.com/Own/Port", rom(hash), now = 1_000, get = web::get)
        assertEquals(listOf(PortSearch.Verdict.CONFIRMED), first.candidates.map { it.verdict })
        assertFalse(web.asked.any { "search/repositories" in it })
        val calls = web.asked.size
        assertEquals(PortSearch.Verdict.CONFIRMED, PortSearch.find("own/port", rom(hash), now = 2_000, get = web::get).candidates.single().verdict)
        assertEquals(calls, web.asked.size)
        PortSearch.find("own/port", rom(hash), now = 1_000 + 11 * 60 * 1000, get = web::get)
        assertTrue(web.asked.size > calls)
    }
}
