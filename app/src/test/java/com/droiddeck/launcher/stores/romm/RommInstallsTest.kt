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
    }

    @Test fun aRecordPointingOutsideTheRomsFolderIsDropped() {
        val text = """{"1":{"path":"../secret","emulator":"duckstation"},"2":{"path":"/etc/passwd","emulator":"duckstation"},"3":{"path":"psx/a.chd","emulator":""},"4":{"path":"psx/b.chd","emulator":"duckstation"}}"""
        assertEquals(listOf("4"), RommInstalls.parse(text).keys.toList())
        assertEquals(emptyMap<String, RommInstalls.Entry>(), RommInstalls.parse("not json"))
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
