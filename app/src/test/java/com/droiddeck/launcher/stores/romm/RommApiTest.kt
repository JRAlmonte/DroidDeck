package com.droiddeck.launcher.stores.romm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RommApiTest {
    private val token = "rmm_" + "0123456789abcdef".repeat(4)

    private fun query(path: String): Map<String, String> =
        path.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test fun gamesAreAskedForInTheChosenOrder() {
        RommFakeServer { 200 to "{}" }.use { server ->
            RommApi.roms(server.address, token, "4", "mario kart", 60)
            RommApi.roms(server.address, token, null, null, 0, sort = RommSort.RATING)
            RommApi.roms(server.address, token, null, null, 0, sort = RommSort.ADDED)
            val (byName, byRating, byAdded) = server.paths().map(::query)
            assertTrue(server.paths().all { it.startsWith("GET /api/roms?") })
            assertEquals(listOf("name", "asc", "4", "mario+kart", "60"), listOf("order_by", "order_dir", "platform_ids", "search_term", "offset").map { byName[it] })
            assertEquals("average_rating" to "desc", byRating["order_by"] to byRating["order_dir"])
            assertEquals("created_at" to "desc", byAdded["order_by"] to byAdded["order_dir"])
            assertEquals("true", byRating["group_by_meta_id"])
        }
    }

    @Test fun gamesOfACollectionAreAskedForByItsKind() {
        RommFakeServer { 200 to "{}" }.use { server ->
            RommApi.roms(server.address, token, "4", null, 0, collection = RommCollection(RommCollection.Kind.REGULAR, "12", "Favourites", 3))
            RommApi.roms(server.address, token, null, null, 0, collection = RommCollection(RommCollection.Kind.SMART, "5", "Short games", 9))
            val (regular, smart) = server.paths().map(::query)
            assertEquals("12" to "4", regular["collection_id"] to regular["platform_ids"])
            assertTrue("smart_collection_id" !in regular)
            assertEquals("5", smart["smart_collection_id"])
            assertTrue("collection_id" !in smart)
        }
    }

    @Test fun everyOrderNamesAFieldAndADirection() {
        for (sort in RommSort.values()) {
            assertTrue(sort.name, sort.by.matches(Regex("[a-z_]+")))
            assertTrue(sort.name, sort.dir == "asc" || sort.dir == "desc")
        }
        assertEquals(RommSort.values().size, RommSort.values().map { it.by to it.dir }.toSet().size)
    }
}
