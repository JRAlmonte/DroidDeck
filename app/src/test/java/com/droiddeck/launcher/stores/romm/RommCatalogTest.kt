package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.stores.Store
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class RommCatalogTest {
    private val server = "http://romm.example:8080"

    private fun rom() = JSONObject()
        .put("id", 42).put("name", "Example Quest").put("fs_name", "Example Quest (USA).chd").put("fs_name_no_ext", "Example Quest (USA)")
        .put("fs_size_bytes", 1234L).put("platform_slug", "psx").put("platform_display_name", "PlayStation")
        .put("summary", "A game.").put("path_cover_large", "roms/1/42/cover/big.png?ts=1")
        .put("has_simple_single_file", true)
        .put("metadatum", JSONObject().put("genres", JSONArray(listOf("RPG", "Adventure"))).put("companies", JSONArray(listOf("Example Soft"))))

    @Test fun aRomBecomesAnOwnedItemWithNoPrice() {
        val item = RommCatalog.item(server, rom())!!
        assertEquals(Store.ROMM, item.store)
        assertEquals("42", item.id)
        assertEquals("Example Quest", item.title)
        assertTrue(item.owned)
        assertTrue(!item.hasPrice && !item.isFree)
        assertEquals(1234L, item.sizeBytes)
        assertEquals("PlayStation, RPG, Adventure", item.tags)
        assertEquals("Example Soft", item.developer)
        assertEquals("psx", item.extra[RommCatalog.PLATFORM])
        assertEquals("Example Quest (USA).chd", item.extra[RommCatalog.FILE])
        assertEquals("1", item.extra[RommCatalog.SINGLE])
    }

    @Test fun coverPathsAreServedFromTheServer() {
        assertEquals("$server/assets/romm/resources/roms/1/42/cover/big.png?ts=1", RommCatalog.item(server, rom())!!.tallImageUrl)
        assertEquals("$server/assets/x.png?ts=2026-01-01%2010:00:00", RommCatalog.item(server, rom().put("path_cover_large", "/assets/x.png?ts=2026-01-01 10:00:00"))!!.tallImageUrl)
        val remote = rom().put("path_cover_large", JSONObject.NULL).put("url_cover", "https://images.example/cover.jpg")
        assertEquals("https://images.example/cover.jpg", RommCatalog.item(server, remote)!!.tallImageUrl)
    }

    @Test fun aRomWithoutANameUsesItsFileName() {
        assertEquals("Example Quest (USA)", RommCatalog.item(server, rom().put("name", JSONObject.NULL))!!.title)
    }

    @Test fun aRomOfSeveralFilesIsMarked() {
        assertEquals("0", RommCatalog.item(server, rom().put("has_multiple_files", true))!!.extra[RommCatalog.SINGLE])
    }

    @Test fun aRomWithNoFileIsSkipped() {
        assertNull(RommCatalog.item(server, rom().put("fs_name", "")))
    }

    @Test fun platformsAreListedNewestFirst() {
        val list = RommCatalog.platforms(JSONArray()
            .put(JSONObject().put("id", 1).put("slug", "nds").put("name", "Nintendo DS").put("display_name", "DS").put("rom_count", 5))
            .put(JSONObject().put("id", 2).put("slug", "psx").put("name", "PlayStation").put("rom_count", 3))
            .put(JSONObject().put("id", 5).put("slug", "zz-homebrew").put("name", "Homebrew").put("rom_count", 1))
            .put(JSONObject().put("id", 6).put("slug", "psp").put("name", "PlayStation Portable").put("rom_count", 2))
            .put(JSONObject().put("id", 7).put("slug", "nes").put("name", "NES").put("rom_count", 4))
            .put(JSONObject().put("id", 4).put("slug", "snes").put("name", "Empty").put("rom_count", 0))
            .put(JSONObject().put("id", 3)))
        assertEquals(listOf("DS", "PlayStation Portable", "PlayStation", "NES", "Homebrew"), list.map { it.name })
        assertEquals(listOf(5, 2, 3, 4, 1), list.map { it.count })
    }
}
