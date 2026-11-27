package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.CatalogItem
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class RommSentTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val token = "rmm_" + "0123456789abcdef".repeat(4)
    private val sent = """[{"id":"req-1","user_id":1,"device_id":"dev-9","rom_id":7,"file_ids":[70],"status":"taken","reason":null}]"""
    private val rom = """{"id":7,"name":"Example Quest","fs_name":"Example Quest.gba","platform_slug":"gba","platform_id":3,"has_simple_single_file":true,"files":[{"id":70,"file_name":"Example Quest.gba","file_size_bytes":10}]}"""

    @Before fun setUp() {
        StoreAccounts.keys = CredentialCipher.KeyProvider { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        StoreAccounts.keystoreFailed = false
        RommSync.signedIn(app)
        RommSent.clear(app)
        SessionPrefs.setRomsDir(app, "")
    }

    @After fun tearDown() {
        val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        looper.idle()
        RommInstalls.clear(app)
        looper.idle()
        RommDetails.clear(app)
        StoreAccounts.clear(app, Store.ROMM)
        StoreAccounts.keys = CredentialCipher.Keystore
        StoreAccounts.keystoreFailed = false
    }

    private fun signIn(server: String, device: String? = "dev-9", version: String = "5.4.1") {
        val account = JSONObject().put("server", server).put("access_token", token).put("version", version)
            .put("scopes", JSONArray(listOf("roms.read", "devices.read", "devices.write", "roms.user.write")))
        if (device != null) account.put("device_id", device)
        StoreAccounts.write(app, Store.ROMM, account)
    }

    private fun serve(r: RommFakeServer.Request): Pair<Int, String> = when {
        r.path == "/api/devices/dev-9/installs/claim" -> 200 to sent
        r.path == "/api/roms/7" -> 200 to rom
        else -> 200 to "{}"
    }

    @Test fun aSignInWithNoDeviceOfItsOwnTakesNothing() {
        RommFakeServer(::serve).use { server ->
            signIn(server.address, device = null)
            RommSent.check(app) { throw AssertionError("nothing was sent to this device") }
            assertTrue(server.paths().none { "installs" in it })
        }
    }

    @Test fun aServerOlderThanInstallRequestsIsNotAskedForThem() {
        RommFakeServer(::serve).use { server ->
            signIn(server.address, version = "5.3.1")
            RommSent.check(app) { throw AssertionError("RomM 5.3 has no install requests") }
            assertTrue(server.seen.isEmpty())
        }
    }

    @Test fun nothingIsTakenWhileSentGamesAreTurnedOff() {
        SessionPrefs.setRommSent(app, false)
        try {
            RommFakeServer(::serve).use { server ->
                signIn(server.address)
                RommSent.check(app) { throw AssertionError("sent games are turned off") }
                assertTrue(server.seen.isEmpty())
            }
        } finally {
            SessionPrefs.setRommSent(app, true)
        }
    }

    @Test fun aGameSentWithNowhereToPutItIsReportedAsFailed() {
        RommFakeServer(::serve).use { server ->
            signIn(server.address)
            RommSent.check(app) { throw AssertionError("there is no ROMs folder") }
            val told = server.seen.first { it.method == "PUT" && it.path == "/api/devices/dev-9" }
            assertTrue(JSONObject(told.text).getJSONObject("capabilities").getBoolean("remote_install"))
            val report = server.seen.last { it.path == "/api/devices/dev-9/installs/req-1" }
            assertEquals("PUT", report.method)
            assertEquals("failed", JSONObject(report.text).getString("status"))
            assertTrue(JSONObject(report.text).getString("reason").isNotEmpty())
        }
    }

    private fun file(id: Int, path: String, category: String?) = JSONObject().put("id", id).put("file_name", path.substringAfterLast('/'))
        .put("full_path", "roms/switch/Example Quest/$path").put("file_size_bytes", 10).put("category", category ?: JSONObject.NULL)

    /** A game RomM keeps in a folder with an update, DLC, a manual and a hack beside it. */
    private val folder = JSONObject().put("id", 7).put("name", "Example Quest").put("fs_path", "roms/switch").put("fs_name", "Example Quest").put("platform_slug", "switch")
        .put("has_multiple_files", true).put("files", JSONArray(listOf(
            file(70, "Example Quest.nsp", null), file(71, "update/Example Quest [UPD].nsp", "update"), file(72, "dlc/Pack.nsp", "dlc"),
            file(73, "manual/Booklet.pdf", "manual"), file(74, "hack/Hard Mode.nsp", "hack"),
        ))).toString()

    private fun claim(ids: String, request: String = "req-1") =
        """[{"id":"$request","user_id":1,"device_id":"dev-9","rom_id":7,"file_ids":$ids,"status":"taken","reason":null}]"""

    private fun handle() = com.droiddeck.launcher.stores.download.DownloadQueue.JobHandle("romm:7", java.util.concurrent.atomic.AtomicBoolean(false))

    private fun reports(server: RommFakeServer, request: String): List<JSONObject> =
        server.seen.toList().filter { it.method == "PUT" && it.path == "/api/devices/dev-9/installs/$request" }.map { JSONObject(it.text) }

    private fun awaitReport(server: RommFakeServer, request: String): JSONObject {
        repeat(100) { if (reports(server, request).isEmpty()) Thread.sleep(50) }
        return reports(server, request).last()
    }

    @Test fun aGameSentAgainWhileInstalledBringsTheUpdatesAndDlcItLacks() {
        val roms = tmp.newFolder("ROMs")
        SessionPrefs.setRomsDir(app, roms.path)
        var request = "req-1"
        RommFakeServer(8) { r ->
            when {
                r.path == "/api/devices/dev-9/installs/claim" -> 200 to claim("[70,71,72]", request)
                r.path == "/api/roms/7" -> 200 to folder
                "/content/" in r.path -> 200 to "0123456789"
                else -> 200 to "{}"
            }
        }.use { server ->
            signIn(server.address)
            val game = RommCatalog.item(server.address, JSONObject(folder))!!
            RommInstaller(app, RommCatalog.withExtras(game, setOf("71"))).run(handle())
            val dir = java.io.File(roms, "switch/Example Quest")
            assertEquals(listOf("Example Quest.nsp", "update"), dir.list()!!.sorted())
            server.seen.clear()

            val added = ArrayList<Pair<CatalogItem, List<RommFile>>>()
            RommSent.check(app, add = { item, files -> added += item to files }) { throw AssertionError("the game is installed") }
            assertEquals("only what the request names and the game lacks: not the update it has, the manual or the hack", listOf(listOf("72")), added.map { (_, files) -> files.map { it.id } })
            assertTrue("nothing is reported before the files have come", reports(server, "req-1").isEmpty())

            val (item, files) = added.single()
            RommInstaller(app, item, files).run(handle())
            assertEquals("done", awaitReport(server, "req-1").getString("status"))
            assertEquals("0123456789", java.io.File(dir, "dlc/Pack.nsp").readText())
            assertEquals(listOf("Example Quest.nsp", "dlc", "update"), dir.list()!!.sorted())
            assertEquals(setOf("71", "72"), RommCatalog.chosenExtras(RommInstalls.onRecord(app, "7")!!.item!!))
            assertEquals("the game's own file is not asked for again", listOf("72"), server.seen.toList().filter { "/content/" in it.path }.map { it.path.substringAfter("file_ids=") })

            request = "req-2"
            RommSent.check(app, add = { _, _ -> throw AssertionError("the game has every file the request names") }) { throw AssertionError("the game is installed") }
            assertEquals("already_installed", reports(server, "req-2").single().getString("status"))
        }
    }

    @Test fun anAddedFileThatDoesNotComeIsReportedAsFailed() {
        val roms = tmp.newFolder("ROMs")
        SessionPrefs.setRomsDir(app, roms.path)
        var refuse = false
        RommFakeServer(8) { r ->
            when {
                r.path == "/api/devices/dev-9/installs/claim" -> 200 to claim("[70,71,72]")
                r.path == "/api/roms/7" -> 200 to folder
                "/content/" in r.path -> if (refuse) 404 to "{}" else 200 to "0123456789"
                else -> 200 to "{}"
            }
        }.use { server ->
            signIn(server.address)
            RommInstaller(app, RommCatalog.withExtras(RommCatalog.item(server.address, JSONObject(folder))!!, setOf("71"))).run(handle())
            refuse = true
            val added = ArrayList<Pair<CatalogItem, List<RommFile>>>()
            RommSent.check(app, add = { item, files -> added += item to files }) { throw AssertionError("the game is installed") }
            val (item, files) = added.single()
            assertTrue(runCatching { RommInstaller(app, item, files).run(handle()) }.exceptionOrNull() is IllegalStateException)
            val report = awaitReport(server, "req-1")
            assertEquals("failed", report.getString("status"))
            assertTrue(report.getString("reason").isNotEmpty())
            assertTrue("the game stays as it was", java.io.File(roms, "switch/Example Quest/Example Quest.nsp").isFile)
            assertEquals(setOf("71"), RommCatalog.chosenExtras(RommInstalls.onRecord(app, "7")!!.item!!))
        }
    }

    @Test fun aGameSentAgainThatIsInstalledAsOnePlainFileIsReportedAsInstalled() {
        val roms = tmp.newFolder("ROMs")
        SessionPrefs.setRomsDir(app, roms.path)
        RommFakeServer { r ->
            when (r.path) {
                "/api/devices/dev-9/installs/claim" -> 200 to claim("[70,71,72]")
                "/api/roms/7" -> 200 to folder
                else -> 200 to "{}"
            }
        }.use { server ->
            signIn(server.address)
            java.io.File(roms, "switch").mkdirs()
            java.io.File(roms, "switch/Example Quest.nsp").writeText("0123456789")
            RommInstalls.record(app, RommInstalls.Entry("7", "switch/Example Quest.nsp", "", item = RommCatalog.item(server.address, JSONObject(folder))))
            RommSent.check(app, add = { _, _ -> throw AssertionError("a game kept as one file takes no files beside it") }) { throw AssertionError("the game is installed") }
            assertEquals("already_installed", reports(server, "req-1").single().getString("status"))
            assertTrue(server.paths().none { "/content/" in it })
            assertEquals(listOf("Example Quest.nsp"), java.io.File(roms, "switch").list()!!.toList())
        }
    }

    @Test fun aGameSentBringsTheUpdatesAndDlcTheRequestNames() {
        SessionPrefs.setRomsDir(app, tmp.newFolder("ROMs").path)
        for ((ids, extras) in listOf("[70,71,72]" to setOf("71", "72"), "[70]" to emptySet(), "[70,99]" to emptySet())) {
            RommSent.clear(app)
            RommFakeServer { r ->
                when (r.path) {
                    "/api/devices/dev-9/installs/claim" -> 200 to claim(ids)
                    "/api/roms/7" -> 200 to folder
                    else -> 200 to "{}"
                }
            }.use { server ->
                signIn(server.address)
                val asked = ArrayList<CatalogItem>()
                RommSent.check(app) { asked += it }
                assertEquals(ids, listOf(extras), asked.map(RommCatalog::chosenExtras))
                assertEquals("the install is given the files the request was read with", listOf("71", "72", "73", "74"), RommDetails.takeFiles("7")?.extras?.map { it.id })
            }
        }
    }

    @Test fun aGameSentIsInstalledAndTheServerIsToldWhenItIsDone() {
        SessionPrefs.setRomsDir(app, tmp.newFolder("ROMs").path)
        RommFakeServer(::serve).use { server ->
            signIn(server.address)
            val asked = ArrayList<CatalogItem>()
            RommSent.check(app) { asked += it }
            assertEquals(listOf("Example Quest"), asked.map { it.title })
            assertEquals(listOf("70"), RommDetails.takeFiles("7")?.own?.map { it.id })
            assertTrue(server.paths().none { it.startsWith("PUT /api/devices/dev-9/installs") })
            RommSent.finished(app, "7", null)
            repeat(100) { if (server.paths().none { it == "PUT /api/devices/dev-9/installs/req-1" }) Thread.sleep(50) }
            val report = server.seen.last { it.path == "/api/devices/dev-9/installs/req-1" }
            assertEquals("done", JSONObject(report.text).getString("status"))
            server.seen.clear()
            RommSent.finished(app, "7", null)
            Thread.sleep(200)
            assertTrue(server.seen.isEmpty())
        }
    }
}
