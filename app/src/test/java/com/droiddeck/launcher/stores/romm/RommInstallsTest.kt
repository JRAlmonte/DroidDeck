package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class RommInstallsTest {
    @Test fun recordsSurviveARoundTrip() {
        val map = mapOf("42" to RommInstalls.Entry("42", "psx/Example Quest (USA).chd", "duckstation"))
        val read = RommInstalls.parse(RommInstalls.format(map))
        assertEquals(listOf("42"), read.keys.toList())
        assertEquals("psx/Example Quest (USA).chd", read.getValue("42").path)
        assertEquals("duckstation", read.getValue("42").emulatorId)
        assertEquals("/root/ROMs/psx/Example Quest (USA).chd", read.getValue("42").guestPath)
        assertEquals("psx/Example Quest (USA).chd", read.getValue("42").root)
    }

    @Test fun aRecordKeepsTheGameForTheLibrary() {
        val game = CatalogItem(
            Store.ROMM, "9", "Pocket Quest", "http://romm.lan/shot.png", "http://romm.lan/cover.png", tags = "Game Boy", developer = "Studio",
            description = "About it", owned = true, sizeBytes = 131072L, extra = mapOf(RommCatalog.PLATFORM to "gb", RommCatalog.FILE to "Pocket Quest.gb"),
        )
        val read = RommInstalls.parse(RommInstalls.format(mapOf("9" to RommInstalls.Entry("9", "gb/Pocket Quest.gb", "retroarch", item = game)))).getValue("9")
        assertEquals(game, read.item)
        assertNull(RommInstalls.parse(RommInstalls.format(mapOf("4" to RommInstalls.Entry("4", "psx/b.chd", "duckstation")))).getValue("4").item)
    }

    @Test fun anUnpackedGameRemembersItsFolder() {
        val map = mapOf("7" to RommInstalls.Entry("7", "psx/Game (USA)/Game (USA).cue", "duckstation", "psx/Game (USA)"))
        val read = RommInstalls.parse(RommInstalls.format(map)).getValue("7")
        assertEquals("psx/Game (USA)", read.root)
        assertEquals("/root/ROMs/psx/Game (USA)/Game (USA).cue", read.guestPath)
        val elsewhere = """{"8":{"path":"psx/a/a.cue","emulator":"duckstation","root":"ps2"}}"""
        assertEquals(emptyMap<String, RommInstalls.Entry>(), RommInstalls.parse(elsewhere))
    }

    @Test fun aRecordPointingOutsideTheRomsFolderIsDropped() {
        val text = """{"1":{"path":"../secret","emulator":"duckstation"},"2":{"path":"/etc/passwd","emulator":"duckstation"},"3":{"path":"psx/a.chd"},"4":{"path":"psx/b.chd","emulator":"duckstation"}}"""
        assertEquals(listOf("4"), RommInstalls.parse(text).keys.toList())
        assertEquals(emptyMap<String, RommInstalls.Entry>(), RommInstalls.parse("not json"))
    }

    @Test fun aGameKeptWhileNothingPlayedItSurvivesARoundTrip() {
        val read = RommInstalls.parse(RommInstalls.format(mapOf("6" to RommInstalls.Entry("6", "switch/Game.nsp", "")))).getValue("6")
        assertEquals("", read.emulatorId)
        assertEquals("switch/Game.nsp", read.path)
    }

    @Test fun aRecordMadeForANativePortIsKeptWithNothingToPlayIt() {
        val read = RommInstalls.parse("""{"5":{"path":"n64/Game.z64","emulator":"port:zelda64recomp"}}""").getValue("5")
        assertEquals("", read.emulatorId)
        assertEquals("n64/Game.z64", read.path)
    }

    private fun item(file: String, single: String = "1") =
        CatalogItem(Store.ROMM, "1", "Game", null, extra = mapOf(RommCatalog.FILE to file, RommCatalog.SINGLE to single))

    @Test fun onlyAPlainFileNameIsDownloaded() {
        assertEquals("Game (USA).chd", RommInstaller.fileName(item("Game (USA).chd")))
        assertNull(RommInstaller.fileName(item("../Game.chd")))
        assertNull(RommInstaller.fileName(item("dir\\Game.chd")))
        assertNull(RommInstaller.fileName(item("..")))
        assertNull(RommInstaller.fileName(item("Game.chd", single = "0")))
    }
}
