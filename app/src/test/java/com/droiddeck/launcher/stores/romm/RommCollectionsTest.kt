package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.stores.CredentialCipher
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class RommCollectionsTest {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val token = "rmm_" + "0123456789abcdef".repeat(4)

    @Before fun setUp() {
        StoreAccounts.keys = CredentialCipher.KeyProvider { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        StoreAccounts.keystoreFailed = false
        RommBrowseState.reset()
    }

    @After fun tearDown() {
        RommBrowseCache.clear(app)
        RommBrowseState.reset()
        StoreAccounts.clear(app, Store.ROMM)
        StoreAccounts.keys = CredentialCipher.Keystore
        StoreAccounts.keystoreFailed = false
    }

    private fun signIn(server: String) =
        StoreAccounts.write(app, Store.ROMM, JSONObject().put("server", server).put("access_token", token).put("user_id", 5).put("version", "5.4.1"))

    private fun settle(busy: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        do {
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        } while (busy() && System.currentTimeMillis() < until)
    }

    @Test fun aCollectionIsMadeRenamedAndDeletedAndItsGamesAreNeverSent() {
        RommFakeServer { 200 to "{}" }.use { server ->
            assertEquals("nothing is asked without a sign-in", 400, RommCollections.create(app, "Party games").code)
            signIn(server.address)
            assertTrue(RommCollections.create(app, "Couch co-op & more").ok)
            assertTrue(RommCollections.rename(app, "9", "Party games").ok)
            assertTrue(RommCollections.delete(app, "9").ok)
            assertEquals(listOf("POST /api/collections?is_public=false&is_favorite=false", "PUT /api/collections/9", "DELETE /api/collections/9"), server.paths())
            assertEquals("name=Couch+co-op+%26+more&description=", server.seen[0].text)
            assertEquals(
                "a new name comes with no list of games, which RomM would replace the collection's with",
                mapOf("rom_ids" to "null", "name" to "Party+games"), server.seen[1].text.split('&').associate { it.substringBefore('=') to it.substringAfter('=') },
            )
            assertEquals("", server.seen[2].text)
            assertTrue(server.seen.all { it.authorization == "Bearer $token" })

            server.seen.clear()
            assertEquals(400, RommCollections.rename(app, "9/roms", "Party games").code)
            assertEquals(400, RommCollections.rename(app, "9", "").code)
            assertEquals(400, RommCollections.delete(app, "../7").code)
            assertEquals(400, RommCollections.create(app, "").code)
            assertTrue(server.seen.isEmpty())
            assertEquals("a".repeat(RommCollections.MOST_NAME), RommCollections.name("  " + "a".repeat(500) + " "))
        }
    }

    @Test fun onlyAPlainCollectionOfTheAccountsOwnCanBeRenamedOrDeleted() {
        fun collection(kind: RommCollection.Kind, owner: String, favorite: Boolean = false) = RommCollection(kind, "1", "Some games", 3, owner, favorite)
        assertTrue(RommCollections.own(collection(RommCollection.Kind.REGULAR, "5"), "5"))
        assertFalse("another user's", RommCollections.own(collection(RommCollection.Kind.REGULAR, "6"), "5"))
        assertFalse("the favourites", RommCollections.own(collection(RommCollection.Kind.REGULAR, "5", favorite = true), "5"))
        assertFalse("a smart collection", RommCollections.own(collection(RommCollection.Kind.SMART, "5"), "5"))
        assertFalse("an account whose id is not known", RommCollections.own(collection(RommCollection.Kind.REGULAR, ""), ""))
    }

    @Test fun anEmptyCollectionOfTheAccountsOwnIsListedSoThatGamesCanBePutInIt() {
        val regular = JSONArray(
            """[{"id":1,"name":"Mine, empty","rom_ids":[],"user_id":5},{"id":2,"name":"Theirs, empty","rom_ids":[],"user_id":6},
               {"id":3,"name":"Favourites","rom_ids":[],"user_id":5,"is_favorite":true},{"id":4,"name":"Mine","rom_count":2,"user_id":5}]""",
        )
        val smart = JSONArray("""[{"id":1,"name":"Smart, empty","rom_count":0,"user_id":5}]""")
        assertEquals(listOf("Mine", "Mine, empty"), RommCatalog.collections(regular, smart, "5").map { it.name })
        assertEquals(listOf("Mine"), RommCatalog.collections(regular, smart).map { it.name })
        assertEquals(listOf("Mine"), RommCatalog.collections(regular, smart, "7").map { it.name })
    }

    @Test fun afterAChangeTheMenuIsReadAgainAndACollectionThatIsGoneIsLeft() {
        var held = """[{"id":2,"name":"Old name","rom_count":1,"user_id":5}]"""
        RommFakeServer(4) { r ->
            when {
                r.path.startsWith("/api/collections/smart") -> 200 to "[]"
                r.path.startsWith("/api/collections") -> 200 to held
                r.path.startsWith("/api/platforms") -> 200 to "[]"
                r.path.startsWith("/api/roms") -> 200 to """{"items":[{"id":7,"name":"Game","platform_id":4,"platform_slug":"psx","fs_name":"Game.chd","files":[]}],"total":1}"""
                else -> 200 to "{}"
            }
        }.use { server ->
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            signIn(server.address)
            fun pages() = server.paths().filter { "with_filter_values=false" in it }
            RommBrowseState.more(app)
            settle { RommBrowseState.collections.isEmpty() || RommBrowseState.loading }
            RommBrowseState.chooseCollection(app, RommBrowseState.collections.single())
            settle { RommBrowseState.loading }
            assertTrue("collection_id=2" in pages().last())
            val asked = pages().size

            held = """[{"id":2,"name":"New name","rom_count":1,"user_id":5},{"id":3,"name":"Just made","rom_ids":[],"user_id":5}]"""
            RommBrowseState.collectionsChanged(app)
            settle { RommBrowseState.collections.size < 2 }
            assertEquals(listOf("Just made", "New name"), RommBrowseState.collections.map { it.name })
            assertEquals("the chosen collection goes by its new name", "New name", RommBrowseState.collection?.name)
            assertEquals("and its games are not asked for again", asked, pages().size)

            held = """[{"id":3,"name":"Just made","rom_ids":[],"user_id":5}]"""
            RommBrowseState.collectionsChanged(app)
            settle { RommBrowseState.collection != null || RommBrowseState.loading }
            assertNull(RommBrowseState.collection)
            assertEquals(listOf("Just made"), RommBrowseState.collections.map { it.name })
            assertTrue("the list is every collection's again", pages().size == asked + 1 && "collection_id" !in pages().last())
        }
    }
}
