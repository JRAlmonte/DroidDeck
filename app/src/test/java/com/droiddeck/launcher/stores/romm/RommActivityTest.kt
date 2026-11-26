package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.stores.CredentialCipher
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class RommActivityTest {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val token = "rmm_" + "0123456789abcdef".repeat(4)

    @Before fun setUp() {
        StoreAccounts.keys = CredentialCipher.KeyProvider { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        StoreAccounts.keystoreFailed = false
        RommActivity.clear(app)
        RommSync.signedIn(app)
    }

    @After fun tearDown() {
        StoreAccounts.clear(app, Store.ROMM)
        StoreAccounts.keys = CredentialCipher.Keystore
        StoreAccounts.keystoreFailed = false
    }

    private fun signIn(server: String, version: String = "4.9.0", scopes: List<String>? = listOf("assets.write", "devices.write", "roms.user.write", "collections.write")) {
        val account = JSONObject().put("server", server).put("access_token", token).put("version", version)
        if (scopes != null) account.put("scopes", JSONArray(scopes))
        StoreAccounts.write(app, Store.ROMM, account)
    }

    @Test fun aSignInThatCannotWriteOrAnOldServerDoesNotSync() {
        assertEquals(RommSync.Access.SIGNED_OUT, RommSync.access(app))
        signIn("http://127.0.0.1:1")
        assertEquals(RommSync.Access.OK, RommSync.access(app))
        signIn("http://127.0.0.1:1", scopes = listOf("roms.read"))
        assertEquals(RommSync.Access.NO_ACCESS, RommSync.access(app))
        signIn("http://127.0.0.1:1", version = "4.8.2")
        assertEquals(RommSync.Access.OLD_SERVER, RommSync.access(app))
        signIn("http://127.0.0.1:1", version = "5.4.1", scopes = null)
        assertEquals(RommSync.Access.OK, RommSync.access(app))
        RommSync.denied(app, "assets.write")
        assertEquals(RommSync.Access.NO_ACCESS, RommSync.access(app))
        assertEquals(RommSync.Access.OK, RommSync.access(app, RommActivity.PLAY_SCOPE))
        assertEquals(RommSync.Access.OK, RommSync.access(app, nowMs = System.currentTimeMillis() + 25L * 3600 * 1000))
        RommSync.signedIn(app)
        assertEquals(RommSync.Access.OK, RommSync.access(app))
    }

    @Test fun signingInToAnotherServerForgetsWhatTheFirstWasOwedAndDeletesItsToken() {
        val user = JSONObject().put("id", 7).put("username", "player")
        RommFakeServer { r -> if (r.method == "DELETE") 200 to "null" else 201 to "{}" }.use { first ->
            RommAuth.save(app, first.address, token, user, listOf("me.write", "roms.user.write"), "5.4.1", tokenId = 5)
            RommInstalls.record(app, RommInstalls.Entry("7", "psx/Game.chd", "duckstation"))
            RommActivity.started(app, "7", nowMs = 1_000_000L)
            RommActivity.ended(app, nowMs = 1_600_000L)
            RommPrefs.get(app).edit().putString("cloud_baseline_7", "{}").putBoolean("cloud_off_7", true).apply()
            com.droiddeck.launcher.stores.CloudSaves.markDirty(app, Store.ROMM, "7")
            assertEquals(1, RommActivity.pending(app))

            RommFakeServer { 201 to "{}" }.use { second ->
                RommAuth.save(app, second.address, "rmm_" + "f".repeat(64), user, listOf("roms.user.write"), "5.4.1")
                assertEquals(0, RommActivity.pending(app))
                assertEquals(null, RommInstalls.find(app, "7"))
                assertTrue(RommPrefs.get(app).all.keys.none { it.startsWith("cloud_") })
                assertTrue(com.droiddeck.launcher.stores.CloudSaves.dirty(app).none { it.first == Store.ROMM })
                assertEquals(0, RommActivity.flush(app))
                assertTrue(second.seen.isEmpty())
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (first.seen.none { it.method == "DELETE" } && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(listOf("DELETE /api/client-tokens/5"), first.paths())
        }
    }

    @Test fun anotherAccountOnTheSameServerKeepsTheGamesAndForgetsThePlay() {
        RommFakeServer { 201 to "{}" }.use { server ->
            RommAuth.save(app, server.address, token, JSONObject().put("id", 7).put("username", "player"), listOf("roms.user.write"), "5.4.1")
            RommInstalls.record(app, RommInstalls.Entry("7", "psx/Game.chd", "duckstation"))
            RommActivity.started(app, "7", nowMs = 1_000_000L)
            RommActivity.ended(app, nowMs = 1_600_000L)
            RommAuth.save(app, server.address, token, JSONObject().put("id", 8).put("username", "other"), listOf("roms.user.write"), "5.4.1")
            assertEquals(0, RommActivity.pending(app))
            assertEquals("psx/Game.chd", RommInstalls.find(app, "7")?.path)
        }
    }

    @Test fun aPlaySessionWaitsUntilTheServerTakesIt() {
        var down = true
        RommFakeServer { r ->
            when {
                r.path == "/api/devices" -> 201 to """{"device_id":"dev-1"}"""
                down -> 503 to "{}"
                else -> 201 to "{}"
            }
        }.use { server ->
            signIn(server.address)
            RommActivity.started(app, "7", nowMs = 1_000_000L)
            RommActivity.ended(app, nowMs = 1_600_000L)
            assertEquals(1, RommActivity.flush(app))
            down = false
            assertEquals(0, RommActivity.flush(app))
            val sent = server.seen.last { it.path == "/api/play-sessions" }
            val session = JSONObject(sent.text).getJSONArray("sessions").getJSONObject(0)
            assertEquals(7, session.getInt("rom_id"))
            assertEquals(600_000L, session.getLong("duration_ms"))
            assertEquals("1970-01-01T00:16:40Z", session.getString("start_time"))
            assertEquals("dev-1", JSONObject(sent.text).getString("device_id"))
            assertEquals("POST /api/play-sessions", server.paths().last())
        }
    }

    @Test fun aSessionCutShortByTheAppBeingKilledEndsWhenItWasLastSeenRunning() {
        RommActivity.started(app, "7", nowMs = 1_000_000L)
        RommActivity.alive(app, nowMs = 1_300_000L)
        RommActivity.closeLost(app, nowMs = 1_310_000L)
        assertEquals(0, RommActivity.pending(app))
        RommActivity.closeLost(app, nowMs = 9_000_000L)
        assertEquals(1, RommActivity.pending(app))
        RommFakeServer { 201 to "{}" }.use { server ->
            signIn(server.address, scopes = listOf("roms.user.write"))
            assertEquals(0, RommActivity.flush(app))
            val session = JSONObject(server.seen.last { it.path == "/api/play-sessions" }.text).getJSONArray("sessions").getJSONObject(0)
            assertEquals(300_000L, session.getLong("duration_ms"))
        }
    }

    @Test fun aNewLaunchClosesTheSessionLeftOpenBeforeIt() {
        RommActivity.started(app, "7", nowMs = 1_000_000L)
        RommActivity.alive(app, nowMs = 1_120_000L)
        RommActivity.started(app, "8", nowMs = 1_130_000L)
        assertEquals(1, RommActivity.pending(app))
        assertEquals("8", RommActivity.ended(app, nowMs = 1_200_000L))
        assertEquals(2, RommActivity.pending(app))
    }

    @Test fun playTimeIsSentByASignInThatMayNotChangeCollectionsOrSaves() {
        RommFakeServer { 201 to "{}" }.use { server ->
            signIn(server.address, scopes = listOf("roms.read", "roms.user.write"))
            RommActivity.queue(app, JSONObject().put("type", "favorite").put("rom", "7").put("on", true))
            RommActivity.started(app, "7", nowMs = 1_000_000L)
            RommActivity.ended(app, nowMs = 1_600_000L)
            RommActivity.queue(app, JSONObject().put("type", "props").put("rom", "7").put("data", JSONObject().put("rating", 8)))
            assertEquals(1, RommActivity.flush(app))
            assertEquals(listOf("POST /api/play-sessions", "PUT /api/roms/7/props"), server.paths())
            assertEquals(RommSync.Access.OK, RommSync.access(app, RommActivity.PLAY_SCOPE))
        }
    }

    @Test fun aLaunchThatEndsAtOnceIsNotASession() {
        RommActivity.started(app, "7", nowMs = 1_000_000L)
        RommActivity.ended(app, nowMs = 1_002_000L)
        assertEquals(0, RommActivity.pending(app))
    }

    @Test fun aFavouriteAndAStatusAreSentAndARefusalHoldsBackOnlyItsOwnKind() {
        var refuse = false
        RommFakeServer { r ->
            when {
                refuse && r.path.endsWith("/props") -> 403 to "{}"
                r.method == "GET" && r.path == "/api/collections" -> 200 to """[{"id":3,"is_favorite":false},{"id":4,"is_favorite":true,"rom_ids":[1]}]"""
                else -> 200 to "{}"
            }
        }.use { server ->
            signIn(server.address)
            RommActivity.queue(app, JSONObject().put("type", "favorite").put("rom", "7").put("on", true))
            RommActivity.queue(app, JSONObject().put("type", "favorite").put("rom", "1").put("on", false))
            RommActivity.queue(app, JSONObject().put("type", "props").put("rom", "7").put("data", JSONObject().put("rating", 8)))
            assertEquals(0, RommActivity.flush(app))
            assertEquals(
                listOf("GET /api/collections", "POST /api/collections/4/roms", "DELETE /api/collections/4/roms", "PUT /api/roms/7/props"),
                server.paths(),
            )
            assertEquals("""{"rom_ids":[7]}""", server.seen[1].text)
            assertEquals("""{"rom_ids":[1]}""", server.seen[2].text)
            assertEquals("""{"rating":8}""", server.seen[3].text)

            refuse = true
            server.seen.clear()
            RommActivity.queue(app, JSONObject().put("type", "props").put("rom", "7").put("data", JSONObject().put("backlogged", true)))
            RommActivity.queue(app, JSONObject().put("type", "props").put("rom", "8").put("data", JSONObject().put("backlogged", true)))
            RommActivity.queue(app, JSONObject().put("type", "favorite").put("rom", "9").put("on", true))
            assertEquals(2, RommActivity.flush(app))
            assertEquals(listOf("PUT /api/roms/7/props", "GET /api/collections", "POST /api/collections/4/roms"), server.paths())
            assertEquals(RommSync.Access.NO_ACCESS, RommSync.access(app, RommActivity.PLAY_SCOPE))
            assertEquals(RommSync.Access.OK, RommSync.access(app))
            assertEquals(RommSync.Access.OK, RommSync.access(app, "collections.write"))
        }
    }

    @Test fun whatIsNotedWhileTheServerIsAskedIsNeitherKeptWaitingNorLost() {
        RommFakeServer { r ->
            if (r.path.endsWith("/props")) RommActivity.queue(app, JSONObject().put("type", "note").put("rom", "7").put("note", "").put("title", "Later").put("content", "x"))
            200 to "{}"
        }.use { server ->
            signIn(server.address)
            RommActivity.queue(app, JSONObject().put("type", "props").put("rom", "7").put("data", JSONObject().put("rating", 8)))
            assertEquals(0, RommActivity.flush(app))
            assertEquals(1, RommActivity.pending(app))
            assertEquals(0, RommActivity.flush(app))
            assertEquals(listOf("PUT /api/roms/7/props", "POST /api/roms/7/notes"), server.paths())
            assertEquals(0, RommActivity.pending(app))
        }
    }

    @Test fun aLaunchThatOnlyBringsBackTheSessionAlreadyRunningOpensNoPlaySession() {
        val session = com.droiddeck.launcher.session.SessionState
        RommInstalls.record(app, RommInstalls.Entry("7", "psx/Game.chd", "duckstation"))
        try {
            var starts = 0
            session.running = true
            RommSync.beforeLaunch(app, "duckstation", "/root/ROMs/psx/Game.chd") { starts++ }
            assertEquals(1, starts)
            assertEquals(null, RommActivity.ended(app))
            session.running = false
            RommSync.beforeLaunch(app, "duckstation", "/root/ROMs/psx/Game.chd") { starts++ }
            assertEquals(2, starts)
            assertEquals("7", RommActivity.ended(app))
        } finally {
            session.running = false
            RommActivity.rest(app)
            RommInstalls.clear(app)
        }
    }

    @Test fun aNoteIsMadeOnceAndThenChangedAndACollectionTakesTheGame() {
        RommFakeServer { 200 to "{}" }.use { server ->
            signIn(server.address)
            RommActivity.queue(app, JSONObject().put("type", "note").put("rom", "7").put("note", "").put("title", "Stuck").put("content", "The key is in the well."))
            RommActivity.queue(app, JSONObject().put("type", "note").put("rom", "7").put("note", "70").put("title", "Stuck").put("content", "Found it."))
            RommActivity.queue(app, JSONObject().put("type", "collection").put("rom", "7").put("id", "9").put("on", true))
            RommActivity.queue(app, JSONObject().put("type", "collection").put("rom", "7").put("id", "9").put("on", false))
            assertEquals(0, RommActivity.flush(app))
            assertEquals(listOf("POST /api/roms/7/notes", "PUT /api/roms/7/notes/70", "POST /api/collections/9/roms", "DELETE /api/collections/9/roms"), server.paths())
            val made = JSONObject(server.seen[0].text)
            assertEquals("Stuck", made.getString("title"))
            assertEquals(false, made.getBoolean("is_public"))
            assertEquals("Found it.", JSONObject(server.seen[1].text).getString("content"))
            assertEquals("""{"rom_ids":[7]}""", server.seen[2].text)
        }
    }

    @Test fun aCollectionThatIsNotThisAccountsIsLeftAloneWithoutStoppingTheRest() {
        RommFakeServer { r -> if ("/collections/" in r.path) 403 to "{}" else 200 to "{}" }.use { server ->
            signIn(server.address)
            RommActivity.queue(app, JSONObject().put("type", "collection").put("rom", "7").put("id", "9").put("on", true))
            RommActivity.queue(app, JSONObject().put("type", "props").put("rom", "7").put("data", JSONObject().put("difficulty", 4)))
            assertEquals(0, RommActivity.flush(app))
            assertEquals(RommSync.Access.OK, RommSync.access(app, RommActivity.PLAY_SCOPE))
            assertEquals("PUT /api/roms/7/props", server.paths().last())
        }
    }

    private fun favorite(rom: String, on: Boolean) = RommActivity.queue(app, JSONObject().put("type", "favorite").put("rom", rom).put("on", on))

    private val favourites = """[{"id":3,"is_favorite":false},{"id":4,"is_favorite":true,"rom_ids":[1]}]"""

    @Test fun theFavouritesThatWaitGoTogetherAndTheCollectionIsLookedUpOnce() {
        RommFakeServer { r -> if (r.method == "GET" && r.path == "/api/collections") 200 to favourites else 200 to "{}" }.use { server ->
            signIn(server.address)
            favorite("7", true)
            favorite("9", true)
            RommActivity.queue(app, JSONObject().put("type", "props").put("rom", "7").put("data", JSONObject().put("rating", 8)))
            favorite("1", false)
            favorite("8", true)
            favorite("9", false)
            favorite("5", false)
            favorite("5", true)
            assertEquals(0, RommActivity.flush(app))
            assertEquals(listOf("GET /api/collections", "POST /api/collections/4/roms", "DELETE /api/collections/4/roms", "PUT /api/roms/7/props"), server.paths())
            assertEquals("""{"rom_ids":[7,8,5]}""", server.seen[1].text)
            assertEquals("""{"rom_ids":[9,1]}""", server.seen[2].text)
            assertEquals(0, RommActivity.pending(app))
        }
    }

    @Test fun aFirstSyncSendsEveryFavouriteOfOursInOneRequest() {
        RommFakeServer { r -> if (r.method == "GET" && r.path == "/api/collections") 200 to favourites else 200 to "{}" }.use { server ->
            signIn(server.address)
            val ours = (1..40).map { it.toString() }.toSet()
            RommActivity.syncFavorites(app, ours) { _, _ -> }
            assertEquals(listOf("GET /api/collections", "POST /api/collections/4/roms"), server.paths())
            val sent = JSONObject(server.seen[1].text).getJSONArray("rom_ids")
            assertEquals((2..40).toSet(), (0 until sent.length()).map { sent.getInt(it) }.toSet())
            assertEquals(0, RommActivity.pending(app))
        }
    }

    @Test fun favouritesWaitTogetherWhileTheServerIsDownAndNothingElseIsAsked() {
        var down = true
        RommFakeServer { r ->
            when {
                r.method == "GET" && r.path == "/api/collections" -> 200 to favourites
                down -> 503 to "{}"
                else -> 200 to "{}"
            }
        }.use { server ->
            signIn(server.address)
            favorite("7", true)
            favorite("1", false)
            favorite("8", true)
            RommActivity.queue(app, JSONObject().put("type", "props").put("rom", "7").put("data", JSONObject().put("rating", 8)))
            assertEquals(4, RommActivity.flush(app))
            assertEquals(listOf("GET /api/collections", "POST /api/collections/4/roms"), server.paths())
            assertEquals(4, RommActivity.pending(app))

            down = false
            server.seen.clear()
            assertEquals(0, RommActivity.flush(app))
            assertEquals(listOf("GET /api/collections", "POST /api/collections/4/roms", "DELETE /api/collections/4/roms", "PUT /api/roms/7/props"), server.paths())
            assertEquals("""{"rom_ids":[7,8]}""", server.seen[1].text)
            assertEquals(0, RommActivity.pending(app))
        }
    }

    @Test fun oneGameTheServerCannotReadDoesNotTakeTheOtherFavouritesWithIt() {
        RommFakeServer { r ->
            when {
                r.method == "GET" && r.path == "/api/collections" -> 200 to favourites
                r.path.endsWith("/roms") && "not-a-number" in r.text -> 422 to "{}"
                else -> 200 to "{}"
            }
        }.use { server ->
            signIn(server.address)
            favorite("7", true)
            favorite("not-a-number", true)
            favorite("8", true)
            assertEquals(0, RommActivity.flush(app))
            assertEquals(listOf("GET /api/collections") + List(4) { "POST /api/collections/4/roms" }, server.paths())
            assertEquals(listOf("""{"rom_ids":[7,"not-a-number",8]}""", """{"rom_ids":[7]}""", """{"rom_ids":["not-a-number"]}""", """{"rom_ids":[8]}"""), server.seen.drop(1).map { it.text })
            assertEquals(0, RommActivity.pending(app))
        }
    }

    @Test fun theFavouritesCollectionIsMadeOnceForTheGamesItGains() {
        RommFakeServer { r ->
            when {
                r.method == "GET" && r.path == "/api/collections" -> 200 to """[{"id":3,"is_favorite":false}]"""
                r.method == "POST" && r.path.startsWith("/api/collections?") -> 201 to """{"id":12,"is_favorite":true}"""
                else -> 200 to "{}"
            }
        }.use { server ->
            signIn(server.address)
            favorite("1", false)
            assertEquals(0, RommActivity.flush(app))
            assertEquals(listOf("GET /api/collections"), server.paths())

            server.seen.clear()
            favorite("7", true)
            favorite("1", false)
            favorite("8", true)
            assertEquals(0, RommActivity.flush(app))
            assertEquals(listOf("GET /api/collections", "POST /api/collections?is_favorite=true&is_public=false", "POST /api/collections/12/roms"), server.paths())
            assertEquals("""{"rom_ids":[7,8]}""", server.seen[2].text)
            assertEquals(0, RommActivity.pending(app))
        }
    }

    @Test fun signingOutForgetsRommFavouritesAndKeepsTheOthers() {
        com.droiddeck.launcher.session.SessionPrefs.setLibraryFavorites(app, setOf("romm:7491", "romm:12", "steam:440"))
        assertEquals(setOf("7491", "12"), RommActivity.forgetFavorites(app))
        assertEquals(setOf("steam:440"), com.droiddeck.launcher.session.SessionPrefs.libraryFavorites(app))
        assertEquals(emptySet<String>(), RommActivity.forgetFavorites(app))
    }

    @Test fun favouritesMeetInTheMiddle() {
        val first = RommActivity.mergeFavorites(local = setOf("1", "2"), remote = setOf("2", "3"), synced = null)
        assertEquals(setOf("3"), first.add); assertTrue(first.remove.isEmpty()); assertEquals(setOf("1"), first.send)

        val later = RommActivity.mergeFavorites(local = setOf("1", "2", "3"), remote = setOf("1", "4"), synced = setOf("1", "2", "3"))
        assertEquals(setOf("4"), later.add); assertEquals(setOf("2", "3"), later.remove); assertTrue(later.send.isEmpty())
    }

    @Test fun favouritesMadeOnTheServerComeHereAndOursGoThereOnce() {
        RommFakeServer { r ->
            if (r.method == "GET" && r.path == "/api/collections") 200 to """[{"id":4,"is_favorite":true,"rom_ids":[2,3]}]""" else 200 to "{}"
        }.use { server ->
            signIn(server.address)
            var added = emptySet<String>()
            RommActivity.syncFavorites(app, setOf("1", "2")) { add, _ -> added = add }
            assertEquals(setOf("3"), added)
            assertEquals(listOf("GET /api/collections", "POST /api/collections/4/roms"), server.paths())
            assertEquals("""{"rom_ids":[1]}""", server.seen.first { it.method == "POST" }.text)

            var removed = emptySet<String>()
            server.seen.clear()
            RommActivity.syncFavorites(app, setOf("1", "2", "3")) { _, remove -> removed = remove }
            assertEquals(setOf("1"), removed)
            assertTrue(server.paths().none { it.startsWith("POST") })
        }
    }
}
