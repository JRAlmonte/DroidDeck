package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.CredentialCipher
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class RommRefreshTest {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val token = "rmm_" + "0123456789abcdef".repeat(4)
    private val noon = 1_800_000_000L

    @Before fun setUp() {
        StoreAccounts.keys = CredentialCipher.KeyProvider { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        StoreAccounts.keystoreFailed = false
        RommInstalls.clear(app)
        RommRefresh.clear(app)
    }

    @After fun tearDown() {
        RommInstalls.clear(app)
        RommRefresh.clear(app)
        StoreAccounts.clear(app, Store.ROMM)
        StoreAccounts.keys = CredentialCipher.Keystore
        StoreAccounts.keystoreFailed = false
    }

    private fun signIn(server: String, user: Int = 5) =
        StoreAccounts.write(app, Store.ROMM, JSONObject().put("server", server).put("access_token", token).put("user_id", user).put("version", "5.4.1"))

    private fun installed(id: String, title: String, extra: Map<String, String> = emptyMap()) = RommInstalls.Entry(
        id, "gb/$title.gb", "retroarch",
        item = CatalogItem(
            Store.ROMM, id, title, null, owned = true, sizeBytes = 1000,
            extra = mapOf(RommCatalog.PLATFORM to "gb", RommCatalog.FILE to "$title.gb", RommCatalog.SINGLE to "1", RommCatalog.SHA1 to "a".repeat(40), RommCatalog.UPDATED to "100") + extra,
        ),
    )

    private fun rom(id: Int, name: String, more: String = "") =
        """{"id":$id,"name":"$name","fs_name":"$name (Rev 1).gb","platform_slug":"gbc","platform_id":9,"platform_display_name":"Game Boy Color","fs_size_bytes":2000,""" +
            """"sha1_hash":"${"b".repeat(40)}","tags":["Beta"],"updated_at":"2026-03-01T10:00:00Z","title_id":"NEWID","save_target":"NEWTARGET","metadatum":{"genres":["Puzzle"]}$more}"""

    private fun page(total: Int, vararg roms: String) = """{"items":[${roms.joinToString(",")}],"total":$total,"limit":100,"offset":0}"""

    private fun record(id: String) = RommInstalls.find(app, id)!!.item!!

    private fun lists(server: RommFakeServer) = server.paths().filter { it.startsWith("GET /api/roms?") }

    /** A check with nothing installed yet, which leaves the time of [noon] kept. */
    private fun firstCheck(server: RommFakeServer) {
        server.clock = noon
        assertEquals(0, RommRefresh.check(app))
        assertEquals(noon - RommRefresh.OVERLAP_S, RommRefresh.since(app))
        server.seen.clear()
    }

    @Test fun withNoCheckKeptEachInstalledGameIsReadOnceAndTheServersTimeIsKept() {
        RommFakeServer { r ->
            when (r.path) {
                "/api/roms/7" -> 200 to rom(7, "Alpha Renamed")
                "/api/roms/8" -> 404 to """{"detail":"Rom not found"}"""
                else -> 500 to "{}"
            }
        }.use { server ->
            signIn(server.address)
            server.clock = noon
            RommInstalls.recordAll(app, listOf(installed("7", "Alpha", mapOf(RommCatalog.TITLE_ID to "OLDID", RommCatalog.GENRES to """["Action"]""")), installed("8", "Beta")))
            val gone = record("8")

            assertEquals(1, RommRefresh.check(app))

            assertEquals(listOf("GET /api/roms/7", "GET /api/roms/8"), server.paths())
            assertEquals(noon - RommRefresh.OVERLAP_S, RommRefresh.since(app))
            val now = record("7")
            assertEquals("Alpha Renamed", now.title)
            assertEquals(listOf("Beta"), RommCatalog.list(now, RommCatalog.TAGS))
            assertEquals(listOf("Puzzle"), RommCatalog.list(now, RommCatalog.GENRES))
            assertEquals(RommDetails.epoch("2026-03-01T10:00:00Z").toString(), now.extra[RommCatalog.UPDATED])
            assertEquals("9", now.extra[RommCatalog.PLATFORM_ID])
            assertEquals("the file on the device is the one that was installed", "Alpha.gb", now.extra[RommCatalog.FILE])
            assertEquals("gb", now.extra[RommCatalog.PLATFORM])
            assertEquals("a".repeat(40), now.extra[RommCatalog.SHA1])
            assertEquals(1000L, now.sizeBytes)
            assertEquals("a title id that is known is kept", "OLDID", now.extra[RommCatalog.TITLE_ID])
            assertEquals("one that was not known is filled in", "NEWTARGET", now.extra[RommCatalog.SAVE_TARGET])
            assertEquals("a game the server no longer holds keeps its record", gone, record("8"))
            assertEquals(now, RommInstalls.parse(java.io.File(app.filesDir, "stores/romm/installed.json").readText()).getValue("7").item)
        }
    }

    @Test fun aLaterCheckAsksOnlyForWhatChangedSinceAndRenewsTheInstalledOnes() {
        RommFakeServer { r ->
            if (r.path.startsWith("/api/roms?")) 200 to page(2, rom(9, "Not Installed"), rom(7, "Alpha Renamed")) else 200 to "{}"
        }.use { server ->
            signIn(server.address)
            firstCheck(server)
            RommInstalls.recordAll(app, listOf(installed("7", "Alpha"), installed("8", "Beta")))
            val untouched = record("8")
            server.clock = noon + 3600

            assertEquals(1, RommRefresh.check(app))

            val asked = server.paths().single()
            val after = java.time.Instant.ofEpochSecond(noon - RommRefresh.OVERLAP_S).toString()
            assertTrue(asked, "updated_after=${RommApi.encode(after)}" in asked)
            assertTrue(asked, "group_by_meta_id=false" in asked && "order_by=updated_at" in asked && "order_dir=desc" in asked && "offset=0" in asked)
            assertEquals("Alpha Renamed", record("7").title)
            assertEquals(untouched, record("8"))
            assertNull(RommInstalls.find(app, "9"))
            assertEquals(noon + 3600 - RommRefresh.OVERLAP_S, RommRefresh.since(app))

            server.seen.clear()
            assertEquals("the same answer changes nothing the second time", 0, RommRefresh.check(app))
        }
    }

    @Test fun aLongerListIsReadPageByPage() {
        RommFakeServer { r ->
            when {
                !r.path.startsWith("/api/roms?") -> 200 to "{}"
                "offset=0" in r.path -> 200 to page(101, *Array(100) { rom(1000 + it, "Other $it") })
                else -> 200 to page(101, rom(7, "Alpha Renamed"))
            }
        }.use { server ->
            signIn(server.address)
            firstCheck(server)
            RommInstalls.recordAll(app, listOf(installed("7", "Alpha"), installed("8", "Beta")))

            assertEquals(1, RommRefresh.check(app))

            assertEquals(2, lists(server).size)
            assertTrue("offset=100" in server.paths()[1])
            assertEquals("Alpha Renamed", record("7").title)
        }
    }

    @Test fun aListLongerThanReadingEachInstalledGameIsNotRead() {
        RommFakeServer { r ->
            when {
                r.path.startsWith("/api/roms?") -> 200 to page(20_000, rom(9, "Not Installed"))
                r.path == "/api/roms/7" -> 200 to rom(7, "Alpha Renamed")
                r.path == "/api/roms/8" -> 200 to rom(8, "Beta Renamed")
                else -> 500 to "{}"
            }
        }.use { server ->
            signIn(server.address)
            firstCheck(server)
            RommInstalls.recordAll(app, listOf(installed("7", "Alpha"), installed("8", "Beta")))
            server.clock = noon + 3600

            assertEquals(2, RommRefresh.check(app))

            assertEquals(1, lists(server).size)
            assertEquals(listOf("GET /api/roms/7", "GET /api/roms/8"), server.paths().drop(1))
            assertEquals(listOf("Alpha Renamed", "Beta Renamed"), listOf(record("7").title, record("8").title))
            assertEquals(noon + 3600 - RommRefresh.OVERLAP_S, RommRefresh.since(app))
        }
    }

    @Test fun aCheckTheServerDoesNotAnswerLeavesTheRecordsAndTheTimeAsTheyWere() {
        val code = java.util.concurrent.atomic.AtomicInteger(500)
        RommFakeServer { r -> if (r.path.startsWith("/api/roms?")) code.get() to page(1, rom(7, "Alpha Renamed")) else 200 to "{}" }.use { server ->
            signIn(server.address)
            firstCheck(server)
            RommInstalls.recordAll(app, listOf(installed("7", "Alpha")))
            server.clock = noon + 3600

            assertNull(RommRefresh.check(app))
            assertEquals("a refused check is not asked again by itself", 1, server.seen.size)
            assertEquals("Alpha", record("7").title)
            assertEquals(noon - RommRefresh.OVERLAP_S, RommRefresh.since(app))

            code.set(200)
            assertEquals(1, RommRefresh.check(app))
            assertEquals("Alpha Renamed", record("7").title)
        }
    }

    @Test fun withNothingInstalledOnlyTheServersTimeIsNoted() {
        RommFakeServer { 200 to "{}" }.use { server ->
            signIn(server.address)
            server.clock = noon
            assertEquals(0, RommRefresh.check(app))
            assertEquals(listOf("GET /api/heartbeat"), server.paths())
            assertEquals(noon - RommRefresh.OVERLAP_S, RommRefresh.since(app))
            assertEquals(0, RommRefresh.check(app))
            assertEquals(1, server.seen.size)
        }
    }

    @Test fun aServerThatSendsNoDateGivesNoTimeToKeep() {
        RommFakeServer { r -> if (r.path == "/api/roms/7") 200 to rom(7, "Alpha Renamed") else 500 to "{}" }.use { server ->
            signIn(server.address)
            RommInstalls.recordAll(app, listOf(installed("7", "Alpha")))
            assertEquals(1, RommRefresh.check(app))
            assertEquals(0L, RommRefresh.since(app))
        }
    }

    @Test fun signingOutAndAnotherServerOrUserForgetTheTime() {
        RommFakeServer { 200 to "{}" }.use { server ->
            signIn(server.address)
            firstCheck(server)
            RommBackend.signOut(app)
            assertEquals(0L, RommRefresh.since(app))

            signIn(server.address)
            firstCheck(server)
            RommFakeServer { 201 to "{}" }.use { second ->
                RommAuth.save(app, second.address, "rmm_" + "f".repeat(64), JSONObject().put("id", 5).put("username", "player"))
                assertEquals(0L, RommRefresh.since(app))
            }

            signIn(server.address)
            firstCheck(server)
            RommAuth.save(app, server.address, token, JSONObject().put("id", 6).put("username", "other"))
            assertEquals(0L, RommRefresh.since(app))
        }
    }

    @Test fun anAnswerThatArrivesAfterTheAccountWasForgottenChangesNothing() {
        val asked = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        RommFakeServer { r ->
            if (r.path == "/api/roms/7") { asked.countDown(); gate.await(); 200 to rom(7, "Another Server's Game") } else 200 to "{}"
        }.use { server ->
            signIn(server.address)
            server.clock = noon
            RommInstalls.recordAll(app, listOf(installed("7", "Alpha")))
            var result: Int? = -1
            val check = Thread { result = RommRefresh.check(app) }.apply { start() }
            assertTrue(asked.await(10, java.util.concurrent.TimeUnit.SECONDS))
            RommRefresh.clear(app)
            RommInstalls.clear(app)
            RommInstalls.recordAll(app, listOf(installed("7", "Alpha")))
            gate.countDown()
            check.join(10_000)

            assertNull(result)
            assertEquals("Alpha", record("7").title)
            assertEquals(0L, RommRefresh.since(app))
        }
    }

    @Test fun aGameInstalledAsADiscSetKeepsItsDiscsAndWhatSaveSyncGoesBy() {
        val discs = """[["7","Quest (Disc 1).chd",10,""],["8","Quest (Disc 2).chd",10,""]]"""
        val was = CatalogItem(
            Store.ROMM, "7", "Quest", null, owned = true, sizeBytes = 20,
            extra = mapOf(
                RommCatalog.PLATFORM to "psx", RommCatalog.FILE to "Quest", RommCatalog.SINGLE to "0", RommCatalog.SHA1 to "", RommCatalog.DISCS to discs,
                RommCatalog.GENRES to """["Action"]""", RommCatalog.SAVE_TARGET to "SLUS-00001", RommCatalog.SAVE_LAYOUT to "folder-prefix",
            ),
        )
        val now = RommCatalog.item("http://romm.lan", JSONObject(rom(7, "Quest (Disc 1)", ""","save_target_layout":"file-exact"""")).apply { remove("metadatum") })!!
        val kept = RommInstalls.renewed(was, now)
        assertEquals("Quest (Disc 1)", kept.title)
        assertEquals(discs, kept.extra[RommCatalog.DISCS])
        assertEquals(listOf("Quest", "0", "psx"), listOf(RommCatalog.FILE, RommCatalog.SINGLE, RommCatalog.PLATFORM).map { kept.extra[it] })
        assertEquals(20L, kept.sizeBytes)
        assertEquals(listOf("SLUS-00001", "folder-prefix", "NEWID"), listOf(RommCatalog.SAVE_TARGET, RommCatalog.SAVE_LAYOUT, RommCatalog.TITLE_ID).map { kept.extra[it] })
        assertNull("a fact the server no longer has is dropped", kept.extra[RommCatalog.GENRES])
        assertEquals(kept, RommInstalls.renewed(kept, now))
    }
}
