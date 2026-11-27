package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.stores.CredentialCipher
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import com.droiddeck.launcher.stores.StoresState
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class RommBrowseTest {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        StoreAccounts.keys = CredentialCipher.KeyProvider { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        StoreAccounts.keystoreFailed = false
        RommBrowseState.reset()
        RommShelvesState.reset()
    }

    @After fun tearDown() {
        RommBrowseCache.clear(app)
        RommBrowseState.reset()
        RommShelvesState.reset()
        StoreAccounts.clear(app, Store.ROMM)
        StoreAccounts.keys = CredentialCipher.Keystore
        StoreAccounts.keystoreFailed = false
    }

    private fun settle(busy: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        do {
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        } while (busy() && System.currentTimeMillis() < until)
    }

    private val token = "rmm_" + "0123456789abcdef".repeat(4)

    private fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun signIn(server: String, user: Int = 5) =
        StoreAccounts.write(app, Store.ROMM, JSONObject().put("server", server).put("access_token", token).put("user_id", user).put("version", "5.4.1"))

    /** A server with one game, whose first page can be held back or refused. */
    private class Library {
        @Volatile var name = "Old"
        @Volatile var code = 200
        @Volatile var gate: java.util.concurrent.CountDownLatch? = null

        fun answer(r: RommFakeServer.Request): Pair<Int, String> = when {
            r.path.startsWith("/api/platforms") -> 200 to """[{"id":4,"slug":"psx","name":"PlayStation","rom_count":1}]"""
            r.path.startsWith("/api/collections/smart") -> 200 to "[]"
            r.path.startsWith("/api/collections") -> 200 to """[{"id":2,"name":"Favourites","rom_count":1,"is_favorite":true}]"""
            r.path.startsWith("/api/stats") -> 200 to """{"ROMS":1,"PLATFORMS":1,"TOTAL_FILESIZE_BYTES":10}"""
            r.path.startsWith("/api/roms") -> {
                if ("with_filter_values=false" in r.path) gate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
                code to """{"items":[{"id":7,"name":"$name","platform_id":4,"platform_slug":"psx","fs_name":"$name.chd","files":[]}],"total":1}"""
            }
            else -> 200 to "{}"
        }
    }

    private fun onDisk(server: String, user: Int = 5) = RommBrowseCache.read(app, server, user.toString(), RommSort.DEFAULT)

    /** The kept games once they are [wanted], as one read saw them: the menus that land after a page write the copy again. */
    private fun written(server: String, wanted: List<String>): List<String>? {
        var seen: List<String>? = null
        settle { onDisk(server)?.items?.map { it.title }.also { seen = it } != wanted }
        return seen
    }

    private fun copies() = java.io.File(app.filesDir, "stores/romm/browse").listFiles().orEmpty().toList()

    private fun shown() = RommBrowseState.items.map { it.title }

    /** Signs in and opens the store, until the first page and the menus are kept on the device. */
    private fun fill(server: String, user: Int = 5) {
        idle()
        signIn(server, user)
        RommBrowseState.more(app)
        settle { onDisk(server, user).let { it == null || it.platforms.isEmpty() || it.collections.isEmpty() || it.totals == null } }
        assertEquals(listOf("Old"), onDisk(server, user)?.items?.map { it.title })
    }

    @Test fun aStartShowsTheKeptGamesBeforeTheServerAnswersAndTheAnswerReplacesThem() {
        val library = Library()
        RommFakeServer(library::answer).use { server ->
            fill(server.address)
            RommBrowseState.reset()
            assertTrue(RommBrowseState.items.isEmpty() && RommBrowseState.platforms.isEmpty())

            library.name = "New"
            val held = java.util.concurrent.CountDownLatch(1)
            library.gate = held
            RommBrowseState.more(app)
            settle { RommBrowseState.items.isEmpty() }
            assertEquals(listOf("Old"), shown())
            assertTrue(RommBrowseState.kept)
            assertTrue(RommBrowseState.loading)
            assertFalse(RommBrowseState.failed)
            assertEquals(listOf("PlayStation"), RommBrowseState.platforms.map { it.name })
            assertEquals(listOf("Favourites"), RommBrowseState.collections.map { it.name })
            assertEquals(1, RommBrowseState.totals?.games)

            held.countDown()
            settle { RommBrowseState.loading }
            assertEquals(listOf("New"), shown())
            assertFalse(RommBrowseState.kept)
            assertFalse(RommBrowseState.failed)
            assertEquals(listOf("New"), written(server.address, listOf("New")))

            // Back from a platform, the first page is there at once and is still asked for again.
            settle { RommBrowseState.platforms.isEmpty() }
            RommBrowseState.choose(app, RommBrowseState.platforms.first())
            settle { RommBrowseState.loading }
            library.name = "Newer"
            val again = java.util.concurrent.CountDownLatch(1)
            library.gate = again
            RommBrowseState.choose(app, null)
            assertEquals(listOf("New"), shown())
            assertTrue(RommBrowseState.kept)
            again.countDown()
            settle { RommBrowseState.loading }
            assertEquals(listOf("Newer"), shown())
            assertFalse(RommBrowseState.kept)
        }
    }

    @Test fun aRefreshThatFailsLeavesTheKeptGamesAndSaysSo() {
        val library = Library()
        RommFakeServer(library::answer).use { server ->
            fill(server.address)
            RommBrowseState.reset()

            library.code = 500
            RommBrowseState.more(app)
            settle { RommBrowseState.loading }
            assertEquals(listOf("Old"), shown())
            assertTrue(RommBrowseState.kept)
            assertTrue(RommBrowseState.failed)
            assertEquals(listOf("Old"), onDisk(server.address)?.items?.map { it.title })

            val pages = { server.seen.toList().count { "with_filter_values=false" in it.path } }
            val asked = pages()
            RommBrowseState.more(app)
            assertFalse(RommBrowseState.loading)
            assertEquals(asked, pages())

            library.code = 200
            library.name = "New"
            RommBrowseState.more(app, retry = true)
            assertEquals(listOf("Old"), shown())
            settle { RommBrowseState.loading }
            assertEquals(listOf("New"), shown())
            assertFalse(RommBrowseState.kept)
            assertFalse(RommBrowseState.failed)
            settle { onDisk(server.address)?.items?.map { it.title } != listOf("New") }

            RommBrowseState.reset()
            server.close()
            RommBrowseState.more(app)
            settle { RommBrowseState.loading }
            assertEquals(listOf("New"), shown())
            assertTrue(RommBrowseState.kept)
            assertTrue(RommBrowseState.failed)
        }
    }

    @Test fun aViewThatIsLeftGivesItsRequestUpAndTheNextOneIsStillAnswered() {
        val library = Library()
        val waiting = java.util.concurrent.atomic.AtomicInteger()
        val left = java.util.concurrent.atomic.AtomicInteger()
        val serve = { r: RommFakeServer.Request ->
            if ("platform_ids=4" in r.path && "with_filter_values=false" in r.path) {
                waiting.incrementAndGet()
                val until = System.currentTimeMillis() + 10_000
                while (!r.left() && System.currentTimeMillis() < until) Thread.sleep(10)
                if (r.left()) left.incrementAndGet()
            }
            library.answer(r)
        }
        RommFakeServer(4, serve).use { server ->
            fill(server.address)
            settle { RommBrowseState.platforms.isEmpty() }
            val pages = { server.seen.toList().count { "with_filter_values=false" in it.path } }
            assertEquals(1, pages())

            RommBrowseState.choose(app, RommBrowseState.platforms.first())
            settle { waiting.get() == 0 }
            assertTrue(RommBrowseState.loading)
            assertEquals(0, left.get())

            library.name = "New"
            RommBrowseState.choose(app, null)
            assertEquals(listOf("Old"), shown())
            assertTrue(RommBrowseState.kept)
            settle { left.get() == 0 || RommBrowseState.loading }
            assertEquals(1, left.get())
            assertEquals(listOf("New"), shown())
            assertFalse(RommBrowseState.kept)
            assertFalse(RommBrowseState.failed)
            assertFalse(RommBrowseState.loading)
            assertEquals(3, pages())
            assertEquals(listOf("New"), written(server.address, listOf("New")))
            assertEquals(null, StoresState.problems[Store.ROMM])

            // Several views left one after another: each request is given up, and only the last view is drawn.
            waiting.set(0)
            left.set(0)
            val psx = RommBrowseState.platforms.first()
            RommBrowseState.choose(app, psx)
            settle { waiting.get() < 1 }
            RommBrowseState.narrow(app, RommFilter(favorite = true))
            settle { waiting.get() < 2 }
            RommBrowseState.narrow(app, RommFilter())
            settle { waiting.get() < 3 }
            RommBrowseState.choose(app, null)
            settle { left.get() < 3 || RommBrowseState.loading }
            assertEquals(3, left.get())
            assertEquals(listOf("New"), shown())
            assertFalse(RommBrowseState.failed)
            assertFalse(RommBrowseState.kept)
        }
    }

    @Test fun aRequestGivenUpIsNotAnAnswerFromTheServer() {
        val held = java.util.concurrent.CountDownLatch(1)
        RommFakeServer(2) { r -> if ("/api/roms" in r.path) held.await(10, java.util.concurrent.TimeUnit.SECONDS); 200 to "{}" }.use { server ->
            val call = RommCall()
            val answer = java.util.concurrent.atomic.AtomicReference<RommAnswer>()
            val asking = Thread { answer.set(RommApi.roms(server.address, token, null, null, 0, sort = null, call = call)) }.apply { start() }
            settle { server.seen.isEmpty() }
            val began = System.currentTimeMillis()
            call.cancel()
            asking.join(5_000)
            held.countDown()
            assertTrue(System.currentTimeMillis() - began < 5_000)
            assertEquals(0, answer.get().code)
            assertTrue(answer.get().cancelled)
            assertFalse(answer.get().untrusted)

            val before = RommCall().apply { cancel() }
            server.seen.clear()
            assertTrue(RommApi.roms(server.address, token, null, null, 0, sort = null, call = before).cancelled)
            assertTrue(server.seen.isEmpty())
            assertFalse(RommApi.roms(server.address, token, null, null, 0, sort = null, call = RommCall()).cancelled)
        }
    }

    @Test fun signingOutAndAnotherServerOrUserDeleteTheKeptGames() {
        val library = Library()
        RommFakeServer(library::answer).use { server ->
            fill(server.address)
            assertEquals(1, copies().size)
            RommBackend.signOut(app)
            assertTrue(copies().isEmpty())

            fill(server.address)
            RommFakeServer { 201 to "{}" }.use { second ->
                RommAuth.save(app, second.address, "rmm_" + "f".repeat(64), JSONObject().put("id", 5).put("username", "player"))
                assertTrue(copies().isEmpty())
            }

            fill(server.address)
            RommAuth.save(app, server.address, token, JSONObject().put("id", 6).put("username", "other"))
            assertTrue(copies().isEmpty())
            idle()
            assertTrue(RommBrowseState.items.isEmpty())
        }
    }

    @Test fun theKeptGamesHoldNoSignInAndLieWithTheAppsOwnFiles() {
        RommFakeServer(Library()::answer).use { server ->
            fill(server.address)
            val file = RommBrowseCache.file(app, server.address, "5")
            assertEquals(listOf(file), copies())
            assertTrue(file.path.startsWith(app.filesDir.path + "/"))
            assertFalse(file.path.startsWith(app.cacheDir.path + "/"))
            val text = file.readText()
            assertTrue("\"Old\"" in text)
            assertFalse(token in text)
            assertFalse("rmm_" in text)
            assertFalse("access_token" in text)
        }
    }

    @Test fun anotherUsersKeptGamesAreNotShown() {
        val library = Library()
        RommFakeServer(library::answer).use { server ->
            fill(server.address)
            RommBrowseState.reset()
            signIn(server.address, user = 6)

            library.name = "Theirs"
            val held = java.util.concurrent.CountDownLatch(1)
            library.gate = held
            server.seen.clear()
            RommBrowseState.more(app)
            settle { server.paths().none { "/api/roms?" in it } }
            idle()
            assertTrue(RommBrowseState.items.isEmpty())
            assertFalse(RommBrowseState.kept)
            assertTrue(RommBrowseState.platforms.isEmpty())

            held.countDown()
            settle { RommBrowseState.loading }
            assertEquals(listOf("Theirs"), shown())
            settle { onDisk(server.address, 6) == null }
            // The menus are written after the games, each through a file of its own that is then renamed.
            val theirs = listOf(RommBrowseCache.file(app, server.address, "6"))
            settle { copies() != theirs }
            assertEquals(theirs, copies())
        }
    }

    @Test fun aKeptCopyThatCannotBeReadIsNoCopy() {
        val library = Library()
        RommFakeServer(library::answer).use { server ->
            fill(server.address)
            RommBrowseState.reset()
            val file = RommBrowseCache.file(app, server.address, "5")
            val good = file.readText()
            assertEquals(null, RommBrowseCache.read(app, server.address, "5", RommSort.NAME))

            val other = RommBrowseCache.file(app, server.address, "6")
            file.copyTo(other)
            assertEquals(null, onDisk(server.address, 6))
            other.delete()

            file.writeText(good.replace("\"id\":\"7\"", "\"id\":\"../7\""))
            assertEquals(null, onDisk(server.address))
            file.writeText(good + " ".repeat(2 * 1024 * 1024))
            assertEquals(null, onDisk(server.address))
            file.writeText(good.dropLast(good.length / 2))
            assertEquals(null, onDisk(server.address))

            val held = java.util.concurrent.CountDownLatch(1)
            library.gate = held
            server.seen.clear()
            RommBrowseState.more(app)
            settle { server.paths().none { "/api/roms?" in it } }
            idle()
            assertTrue(RommBrowseState.items.isEmpty())
            assertFalse(RommBrowseState.kept)
            held.countDown()
            settle { RommBrowseState.loading }
            assertEquals(listOf("Old"), shown())
            assertFalse(RommBrowseState.failed)
        }
    }

    @Test fun aPlatformsPictureIsAskedForWhereTheServersWebPageLooksAndKeptOnceFound() {
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
        val xbox = RommPlatform("3", "xbox", "Xbox", 973, 2001, fsSlug = "Xbox-OG")
        assertEquals(
            listOf("xbox-og.svg", "xbox-og.ico", "xbox.svg", "xbox.ico").map { "https://romm.example/assets/platforms/$it" },
            RommPlatformIcons.addresses("https://romm.example", xbox),
        )
        assertEquals(listOf("psx.svg", "psx.ico"), RommPlatformIcons.addresses("", RommPlatform("1", "psx", "PlayStation", 1, fsSlug = "../psx")).map { it.substringAfterLast('/') })
        RommFakeServer { r -> if (r.path == "/assets/platforms/xbox.ico") 200 to String(png, Charsets.ISO_8859_1) else 404 to "" }.use { server ->
            StoreAccounts.write(app, Store.ROMM, JSONObject().put("server", server.address).put("access_token", "rmm_" + "0123456789abcdef".repeat(4)).put("version", "5.4.1"))
            RommPlatformIcons.clear(app)
            try {
                assertTrue(RommPlatformIcons.load(app, xbox) != null)
                assertEquals(listOf("xbox-og.svg", "xbox-og.ico", "xbox.svg", "xbox.ico").map { "GET /assets/platforms/$it" }, server.paths())
                assertTrue(RommPlatformIcons.ready(xbox) != null)
                val saturn = RommPlatform("4", "saturn", "Saturn", 5)
                assertEquals(null, RommPlatformIcons.load(app, saturn))
                server.seen.clear()
                assertTrue(RommPlatformIcons.load(app, xbox) != null)
                assertEquals(null, RommPlatformIcons.load(app, saturn))
                assertTrue(server.seen.isEmpty())
            } finally {
                RommPlatformIcons.clear(app)
            }
            assertEquals(null, RommPlatformIcons.ready(xbox))
        }
    }

    @Test fun refreshReadsTheStoreAgainAndKeepsTheSearch() {
        var name = "Old"
        RommFakeServer { r ->
            when {
                r.path.startsWith("/api/platforms") -> 200 to """[{"id":4,"slug":"psx","name":"PlayStation","rom_count":1}]"""
                r.path.startsWith("/api/roms") -> 200 to """{"items":[{"id":7,"name":"$name","platform_slug":"psx","fs_name":"$name.chd","files":[]}],"total":1}"""
                else -> 200 to "{}"
            }
        }.use { server ->
            StoreAccounts.write(app, Store.ROMM, JSONObject().put("server", server.address).put("access_token", "rmm_" + "0123456789abcdef".repeat(4)).put("version", "5.4.1"))
            RommBrowseState.search(app, "quest")
            settle { RommBrowseState.loading }
            assertEquals(listOf("Old"), RommBrowseState.items.map { it.title })

            name = "New"
            server.seen.clear()
            RommBackend.loadShelves(app, force = true)
            settle {
                RommBrowseState.loading || RommBrowseState.items.isEmpty() || RommShelvesState.rows[RommShelvesState.Row.ADDED].isNullOrEmpty() ||
                    server.paths().none { it.startsWith("GET /api/platforms") }
            }
            assertEquals(listOf("New"), RommBrowseState.items.map { it.title })
            assertEquals("quest", RommBrowseState.query)
            assertFalse(RommBrowseState.failed)
            assertTrue(server.paths().any { it.startsWith("GET /api/platforms") })
            assertTrue(server.paths().any { "/api/roms?" in it && "search_term=quest" in it })
            assertEquals(listOf("New"), RommShelvesState.rows[RommShelvesState.Row.ADDED]?.map { it.title })
        }
    }
}
