package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.stores.Store
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun aRomCarriesTheHashTheServerHasOfItsFile() {
        val item = RommCatalog.item(server, rom().put("sha1_hash", "ab".repeat(20)))!!
        assertEquals("ab".repeat(20), item.extra[RommCatalog.SHA1])
        assertEquals("", RommCatalog.item(server, rom())!!.extra[RommCatalog.SHA1])
    }

    @Test fun aRomCarriesWhatTheServerKnowsAboutTheGame() {
        val rom = rom().put("regions", JSONArray(listOf("USA"))).put("languages", JSONArray(listOf("En", "Fr"))).put("revision", "1")
            .put("tags", JSONArray(listOf("Beta", " ", "Unl", "Beta")))
            .put("merged_screenshots", JSONArray(listOf("roms/1/42/screenshots/0.jpg", "https://images.example/1.jpg", "roms/1/42/screenshots/0.jpg")))
            .put("rom_user", JSONObject().put("last_played", "2026-11-01T10:00:00Z"))
            .put("created_at", "2026-10-01T08:00:00Z").put("updated_at", "2026-11-02T09:30:00.123456+01:00")
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
        assertEquals(listOf("Beta", "Unl"), RommCatalog.list(item, RommCatalog.TAGS))
        assertEquals("2026-11-01T10:00:00Z", item.extra[RommCatalog.LAST_PLAYED])
        assertEquals("1790841600", item.extra[RommCatalog.ADDED])
        assertEquals("1793608200", item.extra[RommCatalog.UPDATED])
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
            for (key in listOf(RommCatalog.RATING, RommCatalog.RELEASED, RommCatalog.PLAYERS, RommCatalog.REGIONS, RommCatalog.TAGS, RommCatalog.LAST_PLAYED, RommCatalog.ADDED, RommCatalog.UPDATED)) assertTrue(key, key !in item.extra)
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

    @Test fun englishOnThisDeviceLeavesOutTheGamesTaggedWithAnotherLanguagesRegion() {
        fun game(id: Int, vararg regions: String) = RommCatalog.item(server, rom().put("id", id).put("name", "Game $id").put("regions", JSONArray(regions.toList())))!!
        val games = listOf(game(1, "USA"), game(2, "Japan"), game(3), game(4, "Japan", "USA"), game(5, "Europe"))
        assertEquals(listOf("1", "3", "5"), games.filter { RommCatalog.matches(it, emptySet(), "", RommFilter(installed = true, english = true)) }.map { it.id })
        assertEquals("a chosen region sets it aside", listOf("2", "4"),
            games.filter { RommCatalog.matches(it, emptySet(), "", RommFilter(installed = true, english = true, regions = setOf("Japan"))) }.map { it.id })
    }

    @Test fun gamesOnThisDeviceAreNarrowedAndOrderedHere() {
        fun game(id: Int, name: String, platform: Int, genre: String, rating: Double?, released: Long?) = RommCatalog.item(server, rom().put("id", id).put("name", name).put("platform_id", platform)
            .put("regions", JSONArray(listOf("USA"))).put("metadatum", JSONObject().put("genres", JSONArray(listOf(genre)))
                .put("average_rating", rating ?: JSONObject.NULL).put("first_release_date", released ?: JSONObject.NULL)))!!
        val games = listOf(game(1, "Beta", 4, "RPG", 70.0, 900000000L), game(2, "alpha", 4, "Racing", null, null), game(3, "Gamma", 7, "RPG", 90.0, 800000000L))
        fun ids(list: List<com.droiddeck.launcher.stores.CatalogItem>) = list.map { it.id }
        assertEquals(listOf("1", "3"), ids(games.filter { RommCatalog.matches(it, emptySet(), "", RommFilter(genres = setOf("RPG"))) }))
        assertEquals(listOf("1"), ids(games.filter { RommCatalog.matches(it, setOf("4"), " bet ", RommFilter(regions = setOf("USA"))) }))
        assertEquals(listOf("1", "2"), ids(games.filter { RommCatalog.matches(it, setOf("4"), "", RommFilter()) }))
        assertEquals("a game of any of the chosen platforms", listOf("1", "2", "3"), ids(games.filter { RommCatalog.matches(it, setOf("4", "7"), "", RommFilter()) }))
        assertEquals(listOf("3"), ids(games.filter { RommCatalog.matches(it, setOf("7", "9"), "", RommFilter(genres = setOf("RPG"))) }))
        assertTrue(games.none { RommCatalog.matches(it, emptySet(), "", RommFilter(languages = setOf("Ja"))) })
        assertEquals(3, games.count { RommCatalog.matches(it, emptySet(), "", RommFilter(favorite = true, played = true)) })
        assertEquals(listOf("3", "1", "2"), ids(RommCatalog.sorted(games, RommSort.RATING)))
        assertEquals(listOf("2", "1", "3"), ids(RommCatalog.sorted(games, RommSort.NAME)))
        assertEquals(listOf("1", "3", "2"), ids(RommCatalog.sorted(games, RommSort.NEWEST)))
        assertEquals(listOf("3", "1", "2"), ids(RommCatalog.sorted(games, RommSort.OLDEST)))
    }

    @Test fun gamesOnThisDeviceAreNarrowedByTheTagsTheyCarry() {
        fun game(id: Int, vararg tags: String) = RommCatalog.item(server, rom().put("id", id).put("tags", JSONArray(tags.toList())))!!
        val games = listOf(game(1, "Beta", "Unl"), game(2), game(3, "Proto"), game(4, "Unl"))
        fun ids(filter: RommFilter) = games.filter { RommCatalog.matches(it, emptySet(), "", filter) }.map { it.id }
        assertEquals(listOf("1", "2", "3", "4"), ids(RommFilter(installed = true)))
        assertEquals(listOf("1", "4"), ids(RommFilter(installed = true, tags = setOf("Unl"))))
        assertEquals("any of the chosen tags, as the server answers them", listOf("1", "3"), ids(RommFilter(installed = true, tags = setOf("Beta", "Proto"))))
        assertEquals(listOf("1", "4"), ids(RommFilter(installed = true, tags = setOf("Unl"), genres = setOf("RPG"))))
        assertTrue(ids(RommFilter(installed = true, tags = setOf("Unl"), genres = setOf("Racing"))).isEmpty())
        assertTrue(ids(RommFilter(installed = true, tags = setOf("Demo"))).isEmpty())
    }

    @Test fun gamesOnThisDeviceAreOrderedByWhenTheServerGotOrChangedThem() {
        fun game(id: Int, name: String, added: String?, updated: String?) = RommCatalog.item(server, rom().put("id", id).put("name", name)
            .put("created_at", added ?: JSONObject.NULL).put("updated_at", updated ?: JSONObject.NULL))!!
        val games = listOf(
            game(1, "Delta", "2026-01-05T00:00:00Z", "2026-03-01T00:00:00Z"), game(2, "Zeta", null, null), game(3, "Beta", "2026-02-01T00:00:00Z", "2026-02-02T00:00:00Z"),
            game(4, "Alpha", null, "not a date"), game(5, "Gamma", "2025-12-31T23:00:00-05:00", "2026-04-01T00:00:00Z"),
        )
        assertEquals(listOf("3", "1", "5", "4", "2"), RommCatalog.sorted(games, RommSort.ADDED).map { it.id })
        assertEquals(listOf("5", "1", "3", "4", "2"), RommCatalog.sorted(games, RommSort.UPDATED).map { it.id })
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

    @Test fun theVersionTheAccountMarkedAsMainIsKnownAmongAGamesVersions() {
        fun sibling(id: Int, name: String, main: Boolean) = JSONObject().put("id", id).put("name", "Example Quest").put("fs_name_no_ext", name).put("is_main_sibling", main)
        val siblings = JSONArray(listOf(sibling(43, "Example Quest (Europe)", false), sibling(44, "Example Quest (Japan)", true)))
        val other = RommCatalog.item(server, rom().put("sibling_roms", siblings).put("rom_user", JSONObject().put("is_main_sibling", false)))!!
        assertEquals(listOf(false, true), RommCatalog.versions(other).map { it.main })
        assertEquals("44", RommCatalog.mainVersion(other)?.id)
        val kept = RommInstalls.parse(RommInstalls.format(mapOf("42" to RommInstalls.Entry("42", "psx/a.chd", "duckstation", item = other)))).getValue("42").item!!
        assertEquals("a record keeps which one it is", "44", RommCatalog.mainVersion(kept)?.id)

        val itself = RommCatalog.item(server, rom().put("sibling_roms", siblings).put("rom_user", JSONObject().put("is_main_sibling", true)))!!
        assertEquals("1", itself.extra[RommCatalog.MAIN])
        assertNull("the game shown is the main version already", RommCatalog.mainVersion(itself))

        val none = RommCatalog.item(server, rom().put("sibling_roms", JSONArray(listOf(sibling(43, "Example Quest (Europe)", false)))))!!
        assertNull(RommCatalog.mainVersion(none))
        assertNull(none.extra[RommCatalog.MAIN])
        assertNull("a game from a list is the version the server chose", none.extra[RommCatalog.ALONE])
        assertEquals("1", RommCatalog.alone(none).extra[RommCatalog.ALONE])

        val old = none.copy(extra = none.extra + (RommCatalog.VERSIONS to "[[\"43\",\"Example Quest (Europe)\"]]"))
        assertEquals("versions kept before the mark was read", listOf(false), RommCatalog.versions(old).map { it.main })
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
        val plain = rom().put("path_cover_large", JSONObject.NULL).put("url_cover", "http://images.example/cover.jpg")
        assertEquals("https://images.example/cover.jpg", RommCatalog.item("https://romm.example", plain)!!.tallImageUrl)
        assertEquals("https://images.example/cover.jpg", RommCatalog.item("http://192.168.1.5:8080", plain)!!.tallImageUrl)
        val own = rom().put("path_cover_large", JSONObject.NULL).put("url_cover", "http://192.168.1.5:8080/assets/cover.jpg")
        assertEquals("http://192.168.1.5:8080/assets/cover.jpg", RommCatalog.item("http://192.168.1.5:8080", own)!!.tallImageUrl)
    }

    @Test fun aTileShowsTheSmallCoverAndThePageTheLargeOne() {
        val resources = "$server/assets/romm/resources/roms/1/42"
        val both = RommCatalog.item(server, rom().put("path_cover_small", "roms/1/42/cover/small.png?ts=1"))!!
        assertEquals("$resources/cover/small.png?ts=1", RommCatalog.tile(both))
        assertEquals("$resources/cover/big.png?ts=1", both.tallImageUrl)
        assertEquals("the page opens with the large one", both.tallImageUrl, both.imageUrl)

        val shot = RommCatalog.item(server, rom().put("path_cover_small", "roms/1/42/cover/small.png?ts=1").put("merged_screenshots", JSONArray(listOf("roms/1/42/screenshots/0.jpg"))))!!
        assertEquals("a tile that shows a screenshot keeps it", "$resources/screenshots/0.jpg", RommCatalog.tile(shot))
        assertEquals("$resources/cover/big.png?ts=1", shot.tallImageUrl)

        val large = RommCatalog.item(server, rom())!!
        assertEquals(large.tallImageUrl, RommCatalog.tile(large))
        assertNull(large.extra[RommCatalog.COVER_SMALL])

        val small = RommCatalog.item(server, rom().put("path_cover_large", JSONObject.NULL).put("path_cover_small", "roms/1/42/cover/small.png?ts=1"))!!
        assertEquals("the only cover there is", "$resources/cover/small.png?ts=1", small.tallImageUrl)
        assertEquals(small.tallImageUrl, RommCatalog.tile(small))
        assertNull(small.extra[RommCatalog.COVER_SMALL])
    }

    @Test fun aNameIsPlainOnlyWhenItNamesOneFileAndNothingElse() {
        assertTrue(RommCatalog.plainName("Game (USA) (Disc 1).chd"))
        for (name in listOf("", " ", ".", "..", "a/b", "a\\b", "a\nb.cue", "a\u0000b", "a\rb"))
            assertFalse(name, RommCatalog.plainName(name))
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

    @Test fun theFilesRommMarksAsSomethingElseAreAGamesExtras() {
        fun file(id: Int, folder: String, name: String, category: Any?) = JSONObject()
            .put("id", id).put("file_name", name).put("file_path", folder).put("full_path", "$folder/$name").put("file_size_bytes", 10 * id)
            .put("category", category ?: JSONObject.NULL)
        val base = "roms/switch/Game [0100]"
        val rom = JSONObject().put("fs_path", "roms/switch").put("fs_name", "Game [0100]").put("files", JSONArray()
            .put(file(1, base, "Game.nsp", "game"))
            .put(file(2, base, "Game [UPD].nsp", "update"))
            .put(file(3, "$base/dlc", "Pack 1.nsp", "DLC"))
            .put(file(4, "$base/manual", "Booklet.pdf", "manual"))
            .put(file(5, "$base/extra", "Readme.txt", null))
            .put(file(6, "$base/dlc/..", "escape.nsp", "dlc"))
            .put(file(7, "$base/hack", "gone.nsp", "hack").put("missing_from_fs", true)))
        val files = RommCatalog.fileSet(rom)
        assertEquals(listOf("Game.nsp", "extra/Readme.txt"), files.own.map { it.path })
        assertEquals(listOf("Game [UPD].nsp", "dlc/Pack 1.nsp", "manual/Booklet.pdf"), files.extras.map { it.path })
        assertEquals(listOf("update", "dlc", "manual"), files.extras.map { it.category })
        assertEquals(listOf("2", "3", "4"), files.extras.map { it.id })
        assertEquals(30L, files.extras[1].size)
        assertEquals(files.own.map { it.id }, RommCatalog.files(rom).map { it.id })

        val onlyExtras = JSONObject().put("fs_path", "roms/switch").put("fs_name", "Game [0100]").put("files", JSONArray().put(file(3, "$base/dlc", "Pack 1.nsp", "dlc")))
        assertEquals("with no file of the game's own, every file is", listOf("dlc/Pack 1.nsp"), RommCatalog.fileSet(onlyExtras).own.map { it.path })
        assertTrue(RommCatalog.fileSet(onlyExtras).extras.isEmpty())
        assertTrue(RommCatalog.fileSet(JSONObject().put("fs_name", "x")).let { it.own.isEmpty() && it.extras.isEmpty() })
    }

    @Test fun theExtraFilesChosenAreCarriedWithTheGame() {
        val item = RommCatalog.item(server, rom())!!
        assertTrue(RommCatalog.chosenExtras(item).isEmpty())
        val with = RommCatalog.withExtras(item, setOf("31", "4"))
        assertEquals(setOf("4", "31"), RommCatalog.chosenExtras(with))
        assertEquals(item, RommCatalog.withExtras(with, emptySet()))
        assertEquals(with, RommInstalls.parse(RommInstalls.format(mapOf("42" to RommInstalls.Entry("42", "psx/a.chd", "duckstation", item = with)))).getValue("42").item)
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

    @Test fun aCollectionCarriesItsOwnerAndWhatItIsAbout() {
        val regular = JSONArray()
            .put(JSONObject().put("id", 1).put("name", "Favourites").put("is_favorite", true).put("rom_count", 4).put("user_id", 2).put("description", ""))
            .put(JSONObject().put("id", 3).put("name", "RPGs").put("rom_count", 2).put("user_id", 5).put("description", "Long ones"))
        val smart = JSONArray().put(JSONObject().put("id", 3).put("name", "Short").put("rom_count", 7).put("filter_summary", "Under 5 hours").put("description", "x"))
        val list = RommCatalog.collections(regular, smart)
        assertEquals(listOf("2", "5", ""), list.map { it.owner })
        assertEquals(listOf(true, false, false), list.map { it.favorite })
        assertEquals(listOf("", "Long ones", "Under 5 hours"), list.map { it.about })
    }

    @Test fun aPlatformsYearComesFromTheServerWhenItSendsOne() {
        val list = RommCatalog.platforms(JSONArray()
            .put(JSONObject().put("id", 1).put("slug", "nes").put("name", "NES").put("rom_count", 4))
            .put(JSONObject().put("id", 2).put("slug", "zz-homebrew").put("name", "Homebrew").put("rom_count", 1).put("first_release_year", 2020)))
        assertEquals(listOf("Homebrew", "NES"), list.map { it.name })
        assertEquals(2020, list[0].year)
    }

    @Test fun aPlatformCarriesItsFamilyGenerationAndSizeWhenTheServerSendsThem() {
        val list = RommCatalog.platforms(JSONArray()
            .put(JSONObject().put("id", 2).put("slug", "psx").put("name", "PlayStation").put("rom_count", 3)
                .put("family_name", "Sony").put("category", "Console").put("generation", 5).put("fs_size_bytes", 96_000_000_000L))
            .put(JSONObject().put("id", 7).put("slug", "nes").put("name", "NES").put("rom_count", 4)
                .put("family_name", JSONObject.NULL).put("category", "").put("generation", -1).put("fs_size_bytes", 0))
            .put(JSONObject().put("id", 9).put("slug", "zz-homebrew").put("name", "Homebrew").put("rom_count", 1)))
        assertEquals(listOf("PlayStation", "NES", "Homebrew"), list.map { it.name })
        assertEquals(listOf("Sony", "", ""), list.map { it.family })
        assertEquals("a generation RomM does not know comes as -1 and counts as none", listOf(5, 0, 0), list.map { it.generation })
        assertEquals(listOf(96_000_000_000L, 0L, 0L), list.map { it.bytes })
    }

    @Test fun theServersTotalsAreReadAndATitleIdIsCarried() {
        val totals = RommCatalog.totals(JSONObject().put("PLATFORMS", 48).put("ROMS", 12345).put("TOTAL_FILESIZE_BYTES", 1_500_000_000_000L).put("SAVES", 3).put("STATES", 2))!!
        assertEquals(listOf(12345, 48), listOf(totals.games, totals.platforms))
        assertEquals(1_500_000_000_000L, totals.bytes)
        assertEquals(listOf(3, 2), listOf(totals.saves, totals.states))
        assertEquals("a server that sends neither", listOf(0, 0), RommCatalog.totals(JSONObject().put("ROMS", 1))!!.let { listOf(it.saves, it.states) })
        assertNull(RommCatalog.totals(JSONObject()))
        assertNull(RommCatalog.totals(null))
        val item = RommCatalog.item(server, rom().put("title_id", "ULUS10336").put("save_target", "ULUS10336").put("save_target_layout", "folder-prefix"))!!
        assertEquals(listOf("ULUS10336", "ULUS10336", "folder-prefix"), listOf(RommCatalog.TITLE_ID, RommCatalog.SAVE_TARGET, RommCatalog.SAVE_LAYOUT).map { item.extra[it] })
        assertNull(RommCatalog.item(server, rom().put("title_id", JSONObject.NULL))!!.extra[RommCatalog.TITLE_ID])
    }

    @Test fun everyNarrowingIsSentUnderTheServersName() {
        val filter = RommFilter(
            franchises = setOf("Example"), companies = setOf("Example Soft"), ageRatings = setOf("E"), players = setOf("1-2"), tags = setOf("Beta"),
            status = "finished", saves = true, achievements = true, verified = true,
        )
        assertEquals(
            listOf("franchises" to "Example", "companies" to "Example Soft", "age_ratings" to "E", "player_counts" to "1-2", "tags" to "Beta",
                "statuses" to "finished", "has_saves" to "true", "has_ra" to "true", "verified" to "true"),
            filter.parameters(),
        )
        assertTrue(filter.active)
        val values = RommCatalog.filterValues(JSONObject().put("filter_values", JSONObject()
            .put("franchises", JSONArray(listOf("Example"))).put("companies", JSONArray(listOf("Example Soft"))).put("age_ratings", JSONArray(listOf("E")))
            .put("player_counts", JSONArray(listOf("1", "1-2"))).put("tags", JSONArray(listOf("Beta")))))
        assertEquals(listOf("Example"), values.franchises)
        assertEquals(listOf("1", "1-2"), values.players)
        assertEquals(listOf("Beta"), values.tags)
    }

    @Test fun aGameWithNoFileOnTheServerSaysSoAndAFileCarriesItsOwnHash() {
        assertEquals(RommCatalog.MISSING, RommCatalog.item(server, rom().put("missing_from_fs", true))!!.extra[RommCatalog.UNAVAILABLE])
        assertEquals(RommCatalog.PHYSICAL, RommCatalog.item(server, rom().put("is_physical", true).put("missing_from_fs", true))!!.extra[RommCatalog.UNAVAILABLE])
        assertNull(RommCatalog.item(server, rom())!!.extra[RommCatalog.UNAVAILABLE])
        val folder = JSONObject().put("fs_path", "psx/roms").put("fs_name", "Game").put("files", JSONArray()
            .put(JSONObject().put("id", 1).put("file_name", "a.bin").put("full_path", "psx/roms/Game/a.bin").put("file_size_bytes", 3).put("sha1_hash", "cd".repeat(20)))
            .put(JSONObject().put("id", 2).put("file_name", "a.cue").put("full_path", "psx/roms/Game/a.cue").put("file_size_bytes", 1).put("sha1_hash", JSONObject.NULL)))
        assertEquals(listOf("cd".repeat(20), ""), RommCatalog.files(folder).map { it.sha1 })
    }

    @Test fun theOwnFilesLeftOnTheServerAreKeptByTheirPlace() {
        val item = com.droiddeck.launcher.stores.CatalogItem(Store.ROMM, "1", "Game", null)
        val left = RommCatalog.withLeft(item, setOf("Updates/Game Upd.nsp", "Game.xci"))
        assertEquals(setOf("Game.xci", "Updates/Game Upd.nsp"), RommCatalog.leftOut(left))
        assertEquals(emptySet<String>(), RommCatalog.leftOut(item))
        assertEquals(item, RommCatalog.withLeft(left, emptySet()))
    }

    @Test fun aChoiceAmongAGamesOwnFilesIsOfferedOnlyWhereEachFileStandsByItself() {
        fun system(emulator: String, folder: String, vararg exts: String) = com.droiddeck.launcher.frontend.Library.RomSystem(emulator, listOf(folder), exts.toSet())
        val systems = listOf(
            system("eden", "switch", "xci", "nsp"), system("duckstation", "psx", "cue", "bin", "chd", "m3u"),
            system("flycast", "dc", "gdi", "chd", "cdi"), system("rpcs3", "ps3", "iso"),
        )
        fun own(vararg paths: String) = paths.mapIndexed { n, path -> RommFile("$n", path, 10) }
        assertTrue(RommPlatforms.choosable("switch", own("Game.xci", "Game Upd.nsp", "Game Updates/Game Upd 2.nsp"), systems))
        assertTrue("discs with no sheet", RommPlatforms.choosable("psx", own("Game (Disc 1).chd", "Game (Disc 2).chd"), systems))
        assertFalse("one file is no choice", RommPlatforms.choosable("switch", own("Game.xci"), systems))
        assertFalse("a cue and its tracks", RommPlatforms.choosable("psx", own("Game.cue", "Game (Track 1).bin", "Game (Track 2).bin"), systems))
        assertFalse("a gdi and its tracks", RommPlatforms.choosable("dc", own("Game.gdi", "track01.bin", "track02.raw"), systems))
        assertFalse("discs with a playlist", RommPlatforms.choosable("psx", own("Game.m3u", "Game (Disc 1).chd", "Game (Disc 2).chd"), systems))
        assertFalse("a PS3 folder", RommPlatforms.choosable("ps3", own("PS3_GAME/PARAM.SFO", "PS3_GAME/USRDIR/EBOOT.BIN"), systems))
        assertFalse("a file of another type among them", RommPlatforms.choosable("switch", own("Game.xci", "readme.txt"), systems))
        assertTrue("on a platform nothing plays only a sheet counts", RommPlatforms.choosable("switch", own("Game.xci", "Game Upd.nsp"), emptyList()))
        assertFalse(RommPlatforms.choosable("psx", own("Game.cue", "Game (Track 1).bin"), emptyList()))
    }
}
