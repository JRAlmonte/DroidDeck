package com.droiddeck.launcher.stores.romm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class RommDetailsTest {
    private val server = "http://romm.example:8080"

    private fun resource(name: String) = JSONObject(javaClass.getResource("/romm/$name")!!.readText())

    @Test fun aRomsDetailsCarryWhatTheServerKnowsBeyondTheTile() {
        val d = RommDetails.parse(server, resource("rom-detail.json"))!!
        assertEquals("42", d.id)
        assertEquals(54000, d.hltb!!.main)
        assertEquals(79200, d.hltb!!.extras)
        assertEquals(144000, d.hltb!!.completionist)
        assertEquals("$server/assets/romm/resources/roms/4/42/logo/logo.png", d.logo)
        assertEquals("$server/assets/romm/resources/roms/4/42/fanart/fanart.jpg", d.fanart)
        assertEquals("$server/assets/romm/resources/roms/4/42/manual/42.pdf", d.manual)
        assertEquals(listOf("Example Quest", "Rei no Bouken", "EQ1"), d.alternativeNames)
        assertEquals(listOf("Fantasy", "Action"), d.themes)
        assertEquals(listOf("Third person"), d.perspectives)
        assertEquals(1432, d.ratingCount)
        assertEquals(1790843400L, d.added)
        assertEquals(1795208400L, d.lastPlayed)
        assertEquals(listOf("Redump"), d.verified)
        assertEquals(listOf("Favourites", "Short RPGs"), d.collections.map { it.name })
        assertEquals(RommCollection.Kind.SMART, d.collections[1].kind)
        assertEquals("The key is in the well.", d.notes.single().content)
        assertEquals("11279", d.raId)
        assertEquals(listOf("First Steps", "The End"), d.badges.map { it.title })
        assertEquals("$server/assets/romm/resources/roms/4/42/retroachievements/badges/00501_lock.png", d.badges[0].badgeLocked)
        assertEquals(8, d.user.optInt("rating"))
    }

    @Test fun aRomTheServerKnowsLittleAboutHasEmptyDetails() {
        val d = RommDetails.parse(server, JSONObject().put("id", 7).put("hltb_metadata", JSONObject()).put("ss_metadata", JSONObject.NULL))!!
        assertNull(d.hltb)
        assertNull(d.logo)
        assertNull(d.manual)
        assertTrue(d.badges.isEmpty() && d.notes.isEmpty() && d.collections.isEmpty() && d.verified.isEmpty())
        assertEquals(0L, d.added)
        assertNull(RommDetails.parse(server, JSONObject().put("id", "../x")))
    }

    @Test fun theCopyKeptWithAnInstalledGameReadsTheSame() {
        val whole = resource("rom-detail.json")
        val kept = RommDetails.trimmed(whole)
        assertFalse(kept.has("files"))
        assertFalse(kept.getJSONObject("igdb_metadata").has("similar_games"))
        assertTrue(kept.toString().length < whole.toString().length / 2)
        val a = RommDetails.parse(server, whole)!!
        val b = RommDetails.parse(server, JSONObject(kept.toString()))!!
        assertEquals(a.hltb, b.hltb)
        assertEquals(a.logo, b.logo)
        assertEquals(a.manual, b.manual)
        assertEquals(a.themes, b.themes)
        assertEquals(a.ratingCount, b.ratingCount)
        assertEquals(a.verified, b.verified)
        assertEquals(a.badges.map { it.raId }, b.badges.map { it.raId })
        assertEquals(a.notes.map { it.id }, b.notes.map { it.id })
        assertEquals(a.lastPlayed, b.lastPlayed)
    }

    @Test fun playTimeIsTheSumOfTheSessionsTheServerHolds() {
        val sessions = JSONArray().put(JSONObject().put("duration_ms", 600_000)).put(JSONObject().put("duration_ms", 1_200_000)).put(JSONObject().put("duration_ms", -5))
        assertEquals(1_800_000L, RommDetails.played(sessions))
        assertEquals(0L, RommDetails.played(null))
    }

    @Test fun similarGamesAreTheRomsTheServerNames() {
        val rom = JSONObject().put("id", 9).put("name", "Other Quest").put("fs_name", "Other Quest.chd").put("platform_slug", "psx")
        val answer = JSONArray().put(JSONObject().put("rom", rom).put("score", 0.8).put("reasons", JSONArray())).put(JSONObject().put("score", 0.1))
        assertEquals(listOf("Other Quest"), RommDetails.similar(server, answer).map { it.title })
        assertEquals("the server did not choose this version among the game's", listOf("1"), RommDetails.similar(server, answer).map { it.extra[RommCatalog.ALONE] })
    }

    @Test fun similarGamesComeClosestFirstWithTheServersFirstReason() {
        fun rom(id: Int, name: String) = JSONObject().put("id", id).put("name", name).put("fs_name", "$name.chd").put("platform_slug", "psx")
        fun reason(facet: String, value: String) = JSONObject().put("facet", facet).put("value", value)
        val answer = JSONArray()
            .put(JSONObject().put("rom", rom(1, "Far")).put("score", 0.2).put("reasons", JSONArray().put(reason("decade", "1990"))))
            .put(JSONObject().put("rom", rom(2, "Close")).put("score", 0.9).put("reasons", JSONArray().put(reason("franchise", "Metroid")).put(reason("genre", "Shooter"))))
            .put(JSONObject().put("rom", rom(3, "Linked")).put("score", 0.5).put("reasons", JSONArray().put(reason("igdb", "similar"))))
            .put(JSONObject().put("rom", rom(4, "Also linked")).put("score", 0.5).put("reasons", JSONArray()))
            .put(JSONObject().put("rom", rom(5, "Unscored")))
        assertEquals(listOf("Close", "Linked", "Also linked", "Far", "Unscored"), RommDetails.similar(server, answer).map { it.title })
        val why = RommDetails.reasons(answer)
        assertEquals("a game the server gives no reason for has none", setOf("1", "2", "3"), why.keys)
        assertEquals(listOf("decade 1990", "franchise Metroid", "igdb similar"), listOf("1", "2", "3").map { why.getValue(it).let { r -> "${r.facet} ${r.value}" } })
        assertTrue(RommDetails.reasons(null).isEmpty())
        assertEquals("the order is the server's where it sends no score", listOf("Far", "Unscored"), RommDetails.similar(server, JSONArray().put(JSONObject().put("rom", rom(1, "Far"))).put(JSONObject().put("rom", rom(5, "Unscored")))).map { it.title })
    }

    @Test fun achievementsAreEarnedWhenTheAccountsProgressSaysSo() {
        val d = RommDetails.parse(server, resource("rom-detail.json"))!!
        val earned = RommAchievements.earned(resource("users-me.json"), d.raId)
        assertEquals(setOf("501"), earned.keys)
        assertEquals(1795206600L, earned.getValue("501"))
        val list = RommAchievements.list(d, earned) { "$it pts" }
        assertEquals(listOf(true, false), list.map { it.unlocked })
        assertEquals("Leave the village · 1 pts", list[0].description)
        assertEquals(1795206600L, list[0].unlockedAt)
        assertTrue(list[1].iconLocked!!.endsWith("00502_lock.png"))
        assertTrue(RommAchievements.earned(JSONObject().put("ra_progression", JSONObject.NULL), d.raId).isEmpty())
    }

    @Test fun aServerOlderThanTheGamesLikeAGameIsNotAskedForThem() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        com.droiddeck.launcher.stores.StoreAccounts.keys = com.droiddeck.launcher.stores.CredentialCipher.KeyProvider { javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        com.droiddeck.launcher.stores.StoreAccounts.keystoreFailed = false
        RommSync.signedIn(app)
        try {
            RommFakeServer { r -> if (r.path == "/api/roms/7") 200 to "{\"id\":7,\"name\":\"Quest\",\"fs_name\":\"Quest.chd\",\"platform_slug\":\"psx\"}" else 200 to "[]" }.use { fake ->
                for ((version, asked) in listOf("5.2.0" to false, "5.3.0" to true)) {
                    com.droiddeck.launcher.stores.StoreAccounts.write(
                        app, com.droiddeck.launcher.stores.Store.ROMM,
                        JSONObject().put("server", fake.address).put("access_token", "rmm_" + "0123456789abcdef".repeat(4)).put("version", version),
                    )
                    fake.seen.clear()
                    assertEquals("7", RommDetails.fetch(app, "7")?.id)
                    assertEquals(version, asked, fake.paths().any { "/api/roms/7/similar" in it })
                    assertTrue(fake.paths().any { it.startsWith("GET /api/play-sessions") })
                }
            }
        } finally {
            com.droiddeck.launcher.stores.StoreAccounts.clear(app, com.droiddeck.launcher.stores.Store.ROMM)
            com.droiddeck.launcher.stores.StoreAccounts.keys = com.droiddeck.launcher.stores.CredentialCipher.Keystore
            com.droiddeck.launcher.stores.StoreAccounts.keystoreFailed = false
        }
    }

    @Test fun aGamesPageIsGivenTheGameBeforeItsPlayTimeAndTheGamesLikeIt() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        com.droiddeck.launcher.stores.StoreAccounts.keys = com.droiddeck.launcher.stores.CredentialCipher.KeyProvider { javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        com.droiddeck.launcher.stores.StoreAccounts.keystoreFailed = false
        try {
            RommFakeServer { r ->
                when {
                    r.path == "/api/roms/7" -> 200 to """{"id":7,"name":"Quest","fs_name":"Quest.chd","platform_slug":"psx"}"""
                    r.path.startsWith("/api/play-sessions") -> 200 to """[{"duration_ms":600000},{"duration_ms":30000}]"""
                    r.path.startsWith("/api/roms/7/similar") -> 200 to """[{"rom":{"id":9,"name":"Other Quest","fs_name":"Other Quest.chd","platform_slug":"psx"}}]"""
                    else -> 404 to "{}"
                }
            }.use { fake ->
                com.droiddeck.launcher.stores.StoreAccounts.write(
                    app, com.droiddeck.launcher.stores.Store.ROMM,
                    JSONObject().put("server", fake.address).put("access_token", "rmm_" + "0123456789abcdef".repeat(4)),
                )
                val early = ArrayList<Pair<Long, Int>>()
                val whole = RommDetails.fetch(app, "7") { first -> early += first.playedMs to fake.seen.size }!!
                assertEquals(listOf(0L to 1), early)
                assertEquals(630_000L, whole.playedMs)
                assertEquals(listOf("Other Quest"), whole.similar.map { it.title })
                assertEquals(3, fake.seen.size)
            }
        } finally {
            com.droiddeck.launcher.stores.StoreAccounts.clear(app, com.droiddeck.launcher.stores.Store.ROMM)
            com.droiddeck.launcher.stores.StoreAccounts.keys = com.droiddeck.launcher.stores.CredentialCipher.Keystore
            com.droiddeck.launcher.stores.StoreAccounts.keystoreFailed = false
        }
    }

    @Test fun aManualKeepsTheFileTypeItHasOnTheServer() {
        assertEquals("pdf", RommManual.extension("$server/assets/romm/resources/roms/4/42/manual/42.pdf?ts=1"))
        assertEquals("pdf", RommManual.extension("$server/assets/romm/resources/roms/4/42/manual/42"))
        assertEquals("cbz", RommManual.extension("$server/x/Manual.CBZ"))
    }

    @Test fun aManualKeptFromOneServerIsNotShownForTheSameIdOnAnother() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val here = RommManual.file(app, "12", "https://a.example/assets/romm/resources/roms/1/12/manual/12.pdf")
        val there = RommManual.file(app, "12", "https://b.example/assets/romm/resources/roms/1/12/manual/12.pdf")
        assertNotEquals(here, there)
        assertEquals(here, RommManual.file(app, "12", "https://a.example/assets/romm/resources/roms/1/12/manual/12.pdf"))
        here.parentFile!!.mkdirs()
        here.writeText("manual")
        RommManual.clear(app)
        assertFalse(here.exists())
    }
}
