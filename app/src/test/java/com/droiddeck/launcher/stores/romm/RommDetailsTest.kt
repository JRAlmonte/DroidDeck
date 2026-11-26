package com.droiddeck.launcher.stores.romm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun aManualKeepsTheFileTypeItHasOnTheServer() {
        assertEquals("pdf", RommManual.extension("$server/assets/romm/resources/roms/4/42/manual/42.pdf?ts=1"))
        assertEquals("pdf", RommManual.extension("$server/assets/romm/resources/roms/4/42/manual/42"))
        assertEquals("cbz", RommManual.extension("$server/x/Manual.CBZ"))
    }
}
