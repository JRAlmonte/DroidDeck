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
        .put("fs_size_bytes", 1234L).put("platform_slug", "psx").put("platform_id", 4).put("platform_display_name", "PlayStation")
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
        assertEquals("4", item.extra[RommCatalog.PLATFORM_ID])
        assertEquals("Example Quest (USA).chd", item.extra[RommCatalog.FILE])
        assertEquals("1", item.extra[RommCatalog.SINGLE])
    }

    @Test fun aRomCarriesTheHashesTheServerHasOfItsFile() {
        val item = RommCatalog.item(server, rom().put("sha1_hash", "ab".repeat(20)).put("md5_hash", "cd".repeat(16)))!!
        assertEquals("ab".repeat(20), item.extra[RommCatalog.SHA1])
        assertEquals("cd".repeat(16), item.extra[RommCatalog.MD5])
        assertEquals("", RommCatalog.item(server, rom())!!.extra[RommCatalog.MD5])
    }

    @Test fun aRomCarriesWhatTheServerKnowsAboutTheGame() {
        val rom = rom().put("regions", JSONArray(listOf("USA"))).put("languages", JSONArray(listOf("En", "Fr"))).put("revision", "1")
            .put("merged_screenshots", JSONArray(listOf("roms/1/42/screenshots/0.jpg", "https://images.example/1.jpg", "roms/1/42/screenshots/0.jpg")))
            .put("rom_user", JSONObject().put("last_played", "2026-11-01T10:00:00Z"))
        rom.getJSONObject("metadatum").put("developers", JSONArray(listOf("Example Works"))).put("publishers", JSONArray(listOf("Example Soft")))
            .put("franchises", JSONArray(listOf("Example"))).put("age_ratings", JSONArray(listOf("E"))).put("game_modes", JSONArray(listOf("Single player")))
            .put("player_count", "1-2").put("average_rating", 84.5).put("first_release_date", 883612800000L)
        val item = RommCatalog.item(server, rom)!!
        assertEquals(listOf("RPG", "Adventure"), RommCatalog.list(item, RommCatalog.GENRES))
        assertEquals(listOf("USA"), RommCatalog.list(item, RommCatalog.REGIONS))
        assertEquals(listOf("En", "Fr"), RommCatalog.list(item, RommCatalog.LANGUAGES))
        assertEquals(listOf("Example"), RommCatalog.list(item, RommCatalog.FRANCHISES))
        assertEquals(listOf("E"), RommCatalog.list(item, RommCatalog.AGE_RATINGS))
        assertEquals(listOf("Single player"), RommCatalog.list(item, RommCatalog.GAME_MODES))
        assertEquals("1-2", item.extra[RommCatalog.PLAYERS])
        assertEquals("1", item.extra[RommCatalog.REVISION])
        assertEquals("2026-11-01T10:00:00Z", item.extra[RommCatalog.LAST_PLAYED])
        assertEquals(listOf("$server/assets/romm/resources/roms/1/42/screenshots/0.jpg", "https://images.example/1.jpg"), RommCatalog.list(item, RommCatalog.SHOTS))
        assertEquals(item.imageUrl, RommCatalog.list(item, RommCatalog.SHOTS).first())
        assertEquals("Example Works", item.developer)
        assertEquals("Example Soft", item.publisher)
        assertEquals(8.45f, RommCatalog.rating(item)!!, 0.001f)
        assertEquals(1998, RommCatalog.year(item))
        assertEquals("1998-01-01", item.releaseDate)
        assertTrue(com.droiddeck.launcher.stores.GameFacts.releaseLabel(item.releaseDate) != null)
    }

    @Test fun aRomTheServerKnowsNothingAboutCarriesNoFacts() {
        val bare = rom().put("metadatum", JSONObject.NULL)
        val unrated = rom()
        unrated.getJSONObject("metadatum").put("average_rating", JSONObject.NULL).put("first_release_date", JSONObject.NULL).put("player_count", "")
        for (item in listOf(RommCatalog.item(server, bare)!!, RommCatalog.item(server, unrated)!!)) {
            assertEquals(null, RommCatalog.rating(item))
            assertEquals(null, RommCatalog.year(item))
            assertEquals("", item.releaseDate)
            assertTrue(RommCatalog.list(item, RommCatalog.SHOTS).isEmpty())
            for (key in listOf(RommCatalog.RATING, RommCatalog.RELEASED, RommCatalog.PLAYERS, RommCatalog.REGIONS, RommCatalog.LAST_PLAYED)) assertTrue(key, key !in item.extra)
        }
        assertTrue(RommCatalog.list(RommCatalog.item(server, bare)!!, RommCatalog.GENRES).isEmpty())
    }

    @Test fun theValuesToNarrowByAreReadFromAPage() {
        val page = JSONObject().put("filter_values", JSONObject().put("genres", JSONArray(listOf("RPG", " ", "Racing"))).put("regions", JSONArray(listOf("USA"))).put("languages", JSONObject.NULL))
        val values = RommCatalog.filterValues(page)
        assertEquals(listOf("RPG", "Racing"), values.genres)
        assertEquals(listOf("USA"), values.regions)
        assertTrue(values.languages.isEmpty())
        assertTrue(RommCatalog.filterValues(JSONObject()).genres.isEmpty() && RommCatalog.filterValues(null).regions.isEmpty())
    }

    @Test fun gamesOnThisDeviceAreNarrowedAndOrderedHere() {
        fun game(id: Int, name: String, platform: Int, genre: String, rating: Double?, released: Long?) = RommCatalog.item(server, rom().put("id", id).put("name", name).put("platform_id", platform)
            .put("regions", JSONArray(listOf("USA"))).put("metadatum", JSONObject().put("genres", JSONArray(listOf(genre)))
                .put("average_rating", rating ?: JSONObject.NULL).put("first_release_date", released ?: JSONObject.NULL)))!!
        val games = listOf(game(1, "Beta", 4, "RPG", 70.0, 900000000L), game(2, "alpha", 4, "Racing", null, null), game(3, "Gamma", 7, "RPG", 90.0, 800000000L))
        fun ids(list: List<com.droiddeck.launcher.stores.CatalogItem>) = list.map { it.id }
        assertEquals(listOf("1", "3"), ids(games.filter { RommCatalog.matches(it, null, "", RommFilter(genres = setOf("RPG"))) }))
        assertEquals(listOf("1"), ids(games.filter { RommCatalog.matches(it, "4", " bet ", RommFilter(regions = setOf("USA"))) }))
        assertTrue(games.none { RommCatalog.matches(it, null, "", RommFilter(languages = setOf("Ja"))) })
        assertEquals(3, games.count { RommCatalog.matches(it, null, "", RommFilter(favorite = true, played = true)) })
        assertEquals(listOf("3", "1", "2"), ids(RommCatalog.sorted(games, RommSort.RATING)))
        assertEquals(listOf("2", "1", "3"), ids(RommCatalog.sorted(games, RommSort.NAME)))
        assertEquals(listOf("1", "3", "2"), ids(RommCatalog.sorted(games, RommSort.NEWEST)))
        assertEquals(listOf("3", "1", "2"), ids(RommCatalog.sorted(games, RommSort.OLDEST)))
    }

    @Test fun aTileNamesTheRatingTheYearAndThePlatform() {
        val rom = rom()
        rom.getJSONObject("metadatum").put("average_rating", 84.0).put("first_release_date", 883612800L)
        val item = RommCatalog.item(server, rom)!!
        assertEquals("★ 8.4 · 1998 · PSX", RommCatalog.cardLine(item, withPlatform = true))
        assertEquals("1998 · PSX", RommCatalog.cardLine(item, withPlatform = true, rated = false))
        assertEquals("8.4", RommCatalog.ratingText(item))
        assertEquals(2, RommCatalog.cardLine(item, withPlatform = false).split(" · ").size)
        assertEquals("PSX", RommCatalog.cardLine(RommCatalog.item(server, rom())!!, withPlatform = true))
        assertEquals("", RommCatalog.cardLine(RommCatalog.item(server, rom())!!, withPlatform = false))
    }

    @Test fun otherVersionsOfAGameAreKeptWithIt() {
        val grouped = rom().put("sibling_roms", JSONArray(listOf(
            JSONObject().put("id", 43).put("name", "Example Quest").put("fs_name_no_ext", "Example Quest (Europe)"),
            JSONObject().put("id", 44).put("name", "Example Quest").put("fs_name_no_ext", ""),
            JSONObject().put("id", 42).put("name", "Example Quest").put("fs_name_no_ext", "Example Quest (USA)"),
        )))
        val item = RommCatalog.item(server, grouped)!!
        assertEquals(listOf("43" to "Example Quest (Europe)", "44" to "Example Quest"), RommCatalog.versions(item).map { it.id to it.name })
        assertEquals("Example Quest (USA)", RommCatalog.versionName(item))
        assertEquals(emptyList<RommVersion>(), RommCatalog.versions(RommCatalog.item(server, rom())!!))
        assertEquals(item, RommInstalls.parse(RommInstalls.format(mapOf("42" to RommInstalls.Entry("42", "psx/a.chd", "duckstation", item = item)))).getValue("42").item)
    }

    @Test fun discsOfOneGameAreFoundAmongItsVersions() {
        val versions = listOf(
            RommVersion("3", "Quest (USA) (Disc 2)"), RommVersion("1", "Quest (USA) (Disc 1)"), RommVersion("5", "Quest (Europe) (Disc 1)"),
            RommVersion("7", "Quest (USA) (Rev 1)"), RommVersion("8", "Quest (Japan) [CD1]"), RommVersion("9", "Quest (Japan) [CD2]"),
        )
        assertEquals(
            listOf("Quest (USA)" to listOf("1", "3"), "Quest (Japan)" to listOf("8", "9")),
            RommCatalog.discSets(versions).map { it.first to it.second.map { d -> d.id } },
        )
        val one = RommCatalog.item(server, rom())!!
        val folderRom = one.copy(id = "43", extra = one.extra + (RommCatalog.SINGLE to "0"))
        assertNull(RommCatalog.discSet("Example Quest", listOf(one, folderRom)))
        assertNull(RommCatalog.discSet("Example Quest", listOf(one)))
        assertNull(RommCatalog.discSet("Example Quest", listOf(one, one.copy(id = "43", extra = one.extra + (RommCatalog.FILE to "../b.chd")))))
        val set = RommCatalog.discSet("Example Quest", listOf(one, one.copy(id = "43", sizeBytes = 6L, extra = one.extra + (RommCatalog.FILE to "b.chd"))))!!
        assertEquals(listOf("42" to "Example Quest (USA).chd", "43" to "b.chd"), RommCatalog.discs(set).map { it.id to it.path })
        assertEquals(1240L, set.sizeBytes)
        assertNull(RommInstaller.fileName(set))
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

    @Test fun aRomsFilesAreReadByTheirPlaceInItsFolder() {
        fun file(id: Int, folder: String, name: String, vararg more: Pair<String, Any>) = JSONObject()
            .put("id", id).put("file_name", name).put("file_path", folder).put("full_path", "$folder/$name").put("file_size_bytes", 10 * id)
            .apply { more.forEach { put(it.first, it.second) } }
        val rom = JSONObject().put("fs_path", "roms/dc").put("fs_name", "Game (USA)").put("files", JSONArray()
            .put(file(1, "roms/dc/Game (USA)", "Game.gdi"))
            .put(file(2, "roms/dc/Game (USA)", "track01.bin"))
            .put(file(3, "roms/dc/Game (USA)/extra", "track02.raw"))
            .put(file(4, "roms/dc/Game (USA)/dlc", "Pack.bin", "category" to "dlc"))
            .put(file(5, "roms/dc/Game (USA)", "gone.bin", "missing_from_fs" to true))
            .put(file(6, "roms/dc/Game (USA)/..", "escape.bin")))
        val files = RommCatalog.files(rom)
        assertEquals(listOf("Game.gdi", "track01.bin", "extra/track02.raw"), files.map { it.path })
        assertEquals(listOf("1", "2", "3"), files.map { it.id })
        assertEquals(20L, files[1].size)
        assertEquals(emptyList<RommFile>(), RommCatalog.files(JSONObject().put("fs_name", "x")))
        val single = JSONObject().put("fs_path", "roms/snes").put("fs_name", "Game.sfc").put("files", JSONArray().put(file(9, "roms/snes", "Game.sfc")))
        assertEquals(listOf("Game.sfc"), RommCatalog.files(single).map { it.path })
    }

    @Test fun oneFileOfARomIsAskedForByItsId() {
        assertEquals("$server/api/roms/7/content/Disc%201.chd?file_ids=42", RommApi.contentUrl(server, "7", "Disc 1.chd", "42"))
        assertEquals("$server/api/roms/7/content/Game.sfc", RommApi.contentUrl(server, "7", "Game.sfc"))
    }

    @Test fun aRomWithNoFileIsSkipped() {
        assertNull(RommCatalog.item(server, rom().put("fs_name", "")))
    }

    @Test fun aRomWhoseIdCouldLeaveItsFolderIsSkipped() {
        assertNull(RommCatalog.item(server, rom().put("id", "../7")))
        assertNull(RommCatalog.item(server, rom().put("id", "7/8")))
        assertNull(RommCatalog.item(server, rom().put("id", "a".repeat(65))))
        val kept = RommCatalog.item(server, rom().put("sibling_roms", org.json.JSONArray()
            .put(JSONObject().put("id", "../9").put("fs_name_no_ext", "Game (Europe)"))
            .put(JSONObject().put("id", 10).put("fs_name_no_ext", "Game (Japan)"))))!!
        assertEquals(listOf("10"), RommCatalog.versions(kept).map { it.id })
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

    @Test fun collectionsWithGamesAreListedFavouritesFirst() {
        val regular = JSONArray()
            .put(JSONObject().put("id", 3).put("name", "RPGs").put("rom_ids", JSONArray(listOf(1, 2))))
            .put(JSONObject().put("id", 1).put("name", "Favourites").put("is_favorite", true).put("rom_count", 4).put("rom_ids", JSONArray(listOf(1, 2, 3, 4))))
            .put(JSONObject().put("id", 2).put("name", "Empty").put("rom_count", 0))
            .put(JSONObject().put("id", 9).put("rom_count", 2))
        val smart = JSONArray().put(JSONObject().put("id", 3).put("name", "couch co-op").put("rom_count", 7))
        val list = RommCatalog.collections(regular, smart)
        assertEquals(listOf("Favourites", "couch co-op", "RPGs"), list.map { it.name })
        assertEquals(listOf(4, 7, 2), list.map { it.count })
        assertEquals(listOf("REGULAR:1", "SMART:3", "REGULAR:3"), list.map { it.key })
        assertEquals("smart_collection_id", list[1].kind.parameter)
        assertTrue(RommCatalog.collections(null, null).isEmpty())
    }
}
