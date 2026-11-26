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
    private val rom = """{"id":7,"name":"Example Quest","fs_name":"Example Quest.gba","platform_slug":"gba","platform_id":3,"has_simple_single_file":true}"""

    @Before fun setUp() {
        StoreAccounts.keys = CredentialCipher.KeyProvider { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        StoreAccounts.keystoreFailed = false
        RommSync.signedIn(app)
        RommSent.clear(app)
        SessionPrefs.setRomsDir(app, "")
    }

    @After fun tearDown() {
        StoreAccounts.clear(app, Store.ROMM)
        StoreAccounts.keys = CredentialCipher.Keystore
        StoreAccounts.keystoreFailed = false
    }

    private fun signIn(server: String, device: String? = "dev-9") {
        val account = JSONObject().put("server", server).put("access_token", token).put("version", "5.4.1")
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

    @Test fun aGameSentIsInstalledAndTheServerIsToldWhenItIsDone() {
        SessionPrefs.setRomsDir(app, tmp.newFolder("ROMs").path)
        RommFakeServer(::serve).use { server ->
            signIn(server.address)
            val asked = ArrayList<CatalogItem>()
            RommSent.check(app) { asked += it }
            assertEquals(listOf("Example Quest"), asked.map { it.title })
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
