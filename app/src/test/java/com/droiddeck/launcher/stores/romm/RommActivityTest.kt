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

    private fun signIn(server: String, version: String = "4.9.0", scopes: List<String>? = listOf("assets.write", "devices.write", "roms.user.write")) {
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
        RommSync.denied(app)
        assertEquals(RommSync.Access.NO_ACCESS, RommSync.access(app))
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
            assertEquals("PUT /api/roms/7/props?update_last_played=true", server.paths().last())
        }
    }

    @Test fun aLaunchThatEndsAtOnceIsNotASession() {
        RommActivity.started(app, "7", nowMs = 1_000_000L)
        RommActivity.ended(app, nowMs = 1_002_000L)
        assertEquals(0, RommActivity.pending(app))
    }

    @Test fun aRommGameStartedThroughAPortIsASessionToo() {
        RommInstalls.record(app, RommInstalls.Entry("7", "n64/Game.z64", "port:zelda"))
        try {
            RommSync.portStarted(app, "/root/ROMs/n64/Other.z64")
            RommActivity.ended(app, nowMs = System.currentTimeMillis() + 60_000L)
            assertEquals(0, RommActivity.pending(app))
            RommSync.portStarted(app, "/root/ROMs/n64/Game.z64")
            RommActivity.ended(app, nowMs = System.currentTimeMillis() + 60_000L)
            assertEquals(1, RommActivity.pending(app))
        } finally {
            RommInstalls.clear(app)
        }
    }

    @Test fun aFavouriteAndAStatusAreSentAndARefusalStopsTheQueue() {
        var refuse = false
        RommFakeServer { r ->
            when {
                refuse -> 403 to "{}"
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
                listOf("GET /api/collections", "POST /api/collections/4/roms", "GET /api/collections", "DELETE /api/collections/4/roms", "PUT /api/roms/7/props"),
                server.paths(),
            )
            assertEquals("""{"rom_ids":[7]}""", server.seen[1].text)
            assertEquals("""{"rating":8}""", server.seen[4].text)

            refuse = true
            RommActivity.queue(app, JSONObject().put("type", "props").put("rom", "7").put("data", JSONObject().put("backlogged", true)))
            assertEquals(1, RommActivity.flush(app))
            assertEquals(RommSync.Access.NO_ACCESS, RommSync.access(app))
        }
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
            assertTrue("POST /api/collections/4/roms" in server.paths())
            assertEquals("""{"rom_ids":[1]}""", server.seen.first { it.method == "POST" }.text)

            var removed = emptySet<String>()
            server.seen.clear()
            RommActivity.syncFavorites(app, setOf("1", "2", "3")) { _, remove -> removed = remove }
            assertEquals(setOf("1"), removed)
            assertTrue(server.paths().none { it.startsWith("POST") })
        }
    }
}
