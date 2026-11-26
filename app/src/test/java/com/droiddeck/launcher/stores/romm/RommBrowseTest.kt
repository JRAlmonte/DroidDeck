package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.stores.CredentialCipher
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
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
            settle { RommBrowseState.loading || RommBrowseState.items.isEmpty() || RommShelvesState.rows[RommShelvesState.Row.ADDED].isNullOrEmpty() }
            assertEquals(listOf("New"), RommBrowseState.items.map { it.title })
            assertEquals("quest", RommBrowseState.query)
            assertFalse(RommBrowseState.failed)
            assertTrue(server.paths().any { it.startsWith("GET /api/platforms") })
            assertTrue(server.paths().any { "/api/roms?" in it && "search_term=quest" in it })
            assertEquals(listOf("New"), RommShelvesState.rows[RommShelvesState.Row.ADDED]?.map { it.title })
        }
    }
}
