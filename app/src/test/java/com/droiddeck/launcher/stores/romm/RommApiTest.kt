package com.droiddeck.launcher.stores.romm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RommApiTest {
    private val token = "rmm_" + "0123456789abcdef".repeat(4)

    private fun query(path: String): Map<String, String> =
        path.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test fun aBodyLongerThanItMayBeIsNotKept() {
        assertEquals(5, RommApi.atMost("12345".byteInputStream(), 5)!!.size)
        assertEquals(null, RommApi.atMost("123456".byteInputStream(), 5))
        RommFakeServer { 200 to "x".repeat(4000) }.use { server ->
            assertEquals(200 to 4000, RommApi.picture(server.address + "/cover.png", 4000).let { it.first to it.second?.size })
            assertEquals(413 to null, RommApi.picture(server.address + "/cover.png", 3999))
            assertEquals(0 to null, RommApi.picture("http://127.0.0.1:1/cover.png"))
        }
    }

    @Test fun gamesAreAskedForInTheChosenOrder() {
        RommFakeServer { 200 to "{}" }.use { server ->
            RommApi.roms(server.address, token, listOf("4"), "mario kart", 60, sort = RommSort.NAME)
            RommApi.roms(server.address, token, emptyList(), null, 0, sort = RommSort.RATING)
            RommApi.roms(server.address, token, emptyList(), null, 0, sort = RommSort.ADDED)
            RommApi.roms(server.address, token, emptyList(), null, 0, sort = RommSort.UPDATED)
            val (byName, byRating, byAdded, byUpdated) = server.paths().map(::query)
            assertTrue(server.paths().all { it.startsWith("GET /api/roms?") })
            assertEquals(listOf("name", "asc", "4", "mario+kart", "60"), listOf("order_by", "order_dir", "platform_ids", "search_term", "offset").map { byName[it] })
            assertEquals("average_rating" to "desc", byRating["order_by"] to byRating["order_dir"])
            assertEquals("created_at" to "desc", byAdded["order_by"] to byAdded["order_dir"])
            assertEquals("updated_at" to "desc", byUpdated["order_by"] to byUpdated["order_dir"])
            assertEquals("true", byRating["group_by_meta_id"])
        }
    }

    @Test fun gamesOfSeveralPlatformsAreAskedForWithEachPlatformNamed() {
        RommFakeServer { 200 to "{}" }.use { server ->
            RommApi.roms(server.address, token, listOf("4", "9"), null, 0, sort = RommSort.NAME)
            RommApi.roms(server.address, token, listOf("9", "4", "12"), null, 0, limit = 1, sort = RommSort.NAME, withFilterValues = true)
            RommApi.random(server.address, token, listOf("4", "9"), null)
            RommApi.random(server.address, token, emptyList(), RommCollection(RommCollection.Kind.REGULAR, "12", "Favourites", 3))
            val (games, values, random, within) = server.paths().map { it.substringAfter('?').split('&') }
            assertEquals(listOf("platform_ids=4", "platform_ids=9"), games.filter { it.startsWith("platform_ids=") })
            assertEquals(listOf("platform_ids=9", "platform_ids=4", "platform_ids=12"), values.filter { it.startsWith("platform_ids=") })
            assertTrue("with_filter_values=true" in values)
            assertEquals(listOf("GET /api/roms/random?platform_ids=4&platform_ids=9", "GET /api/roms/random?collection_id=12"), server.paths().takeLast(2))
            assertEquals(listOf("platform_ids=4", "platform_ids=9"), random.filter { it.startsWith("platform_ids=") })
            assertTrue(within.none { it.startsWith("platform_ids=") })
        }
    }

    @Test fun gamesOfACollectionAreAskedForByItsKind() {
        RommFakeServer { 200 to "{}" }.use { server ->
            RommApi.roms(server.address, token, listOf("4"), null, 0, sort = RommSort.NAME, collection = RommCollection(RommCollection.Kind.REGULAR, "12", "Favourites", 3))
            RommApi.roms(server.address, token, emptyList(), null, 0, sort = RommSort.NAME, collection = RommCollection(RommCollection.Kind.SMART, "5", "Short games", 9))
            val (regular, smart) = server.paths().map(::query)
            assertEquals("12" to "4", regular["collection_id"] to regular["platform_ids"])
            assertTrue("smart_collection_id" !in regular)
            assertEquals("5", smart["smart_collection_id"])
            assertTrue("collection_id" !in smart)
        }
    }

    @Test fun whatNarrowsTheListIsSentByTheServersNames() {
        RommFakeServer { 200 to "{}" }.use { server ->
            RommApi.roms(server.address, token, emptyList(), null, 0, sort = RommSort.NAME)
            RommApi.roms(server.address, token, emptyList(), null, 0, sort = RommSort.NAME, filter = RommFilter(
                genres = setOf("Role-playing (RPG)", "Adventure"), regions = setOf("USA"), languages = setOf("En"),
                favorite = true, backlog = true, nowPlaying = true, played = false, installed = true))
            RommApi.roms(server.address, token, listOf("4"), null, 0, limit = 1, sort = RommSort.NAME, withFilterValues = true)
            val (plain, narrowed, values) = server.paths().map { it.substringAfter('?').split('&') }
            for (name in listOf("genres", "regions", "languages", "favorite", "statuses", "last_played", "installed")) assertTrue(name, plain.none { it.startsWith("$name=") })
            assertTrue("with_filter_values=false" in plain)
            assertEquals(listOf("genres=Adventure", "genres=Role-playing+%28RPG%29"), narrowed.filter { it.startsWith("genres=") })
            assertEquals(listOf("statuses=backlogged", "statuses=now_playing"), narrowed.filter { it.startsWith("statuses=") })
            for (sent in listOf("regions=USA", "languages=En", "favorite=true", "last_played=false")) assertTrue(sent, sent in narrowed)
            assertTrue(narrowed.none { it.startsWith("installed=") })
            for (name in listOf("has_states", "matched", "duplicate", "playable", "missing")) assertTrue(name, (plain + narrowed).none { it.startsWith("$name=") })
            assertTrue("with_filter_values=true" in values && "limit=1" in values && "platform_ids=4" in values)
        }
    }

    @Test fun whatOnlyTheServerKnowsOfAGameNarrowsTheListByItsNames() {
        RommFakeServer { 200 to "{}" }.use { server ->
            RommApi.roms(server.address, token, emptyList(), null, 0, sort = RommSort.NAME, filter = RommFilter(states = true, matched = true, duplicate = true, playable = true, missing = true))
            RommApi.roms(server.address, token, emptyList(), null, 0, sort = RommSort.NAME, filter = RommFilter(matched = false))
            val (all, unmatched) = server.paths().map { it.substringAfter('?').split('&') }
            for (sent in listOf("has_states=true", "matched=true", "duplicate=true", "playable=true", "missing=true")) assertTrue(sent, sent in all)
            assertTrue("matched=false" in unmatched)
            for (name in listOf("has_states", "duplicate", "playable", "missing")) assertTrue(name, unmatched.none { it.startsWith("$name=") })
            assertTrue(RommFilter(missing = true).active && RommFilter(matched = false).active)
            val game = com.droiddeck.launcher.stores.CatalogItem(com.droiddeck.launcher.stores.Store.ROMM, "1", "Game", null, owned = true)
            assertTrue("the installed list does not answer them", RommCatalog.matches(game, emptySet(), "", RommFilter(installed = true, missing = true, matched = false)))
        }
    }

    @Test fun theRowsAboveTheListAskForWhatWasPlayedWhatIsNewAndTheFavourites() {
        RommFakeServer { 200 to "{}" }.use { server ->
            for (row in RommShelvesState.Row.values()) RommApi.roms(server.address, token, emptyList(), null, 0, limit = RommShelvesState.MOST, sort = row.sort, filter = row.filter)
            val (playing, added, favourites) = server.paths().map(::query)
            assertEquals(listOf("last_played", "desc", "true"), listOf("order_by", "order_dir", "last_played").map { playing[it] })
            assertEquals("created_at" to "desc", added["order_by"] to added["order_dir"])
            assertTrue("last_played" !in added && "favorite" !in added)
            assertEquals("true", favourites["favorite"])
            assertTrue(listOf(playing, added, favourites).all { it["limit"] == "24" && it["group_by_meta_id"] == "true" })
        }
    }

    @Test fun whatWasPlayedAndTheFavouritesAreRowsOfTheLibraryAndNotOfTheStore() {
        assertEquals(listOf(RommShelvesState.Row.ADDED), RommShelvesState.store)
        assertEquals(listOf(RommShelvesState.Row.PLAYING, RommShelvesState.Row.ADDED, RommShelvesState.Row.FAVOURITES), RommShelvesState.library)
    }

    @Test fun theDefaultOrderIsHighestRated() {
        assertEquals("average_rating" to "desc", RommSort.DEFAULT.by to RommSort.DEFAULT.dir)
    }

    @Test fun everyOrderNamesAFieldAndADirection() {
        for (sort in RommSort.values()) {
            assertTrue(sort.name, sort.by.matches(Regex("[a-z_]+")))
            assertTrue(sort.name, sort.dir == "asc" || sort.dir == "desc")
        }
        assertEquals(RommSort.values().size, RommSort.values().map { it.by to it.dir }.toSet().size)
    }
}
