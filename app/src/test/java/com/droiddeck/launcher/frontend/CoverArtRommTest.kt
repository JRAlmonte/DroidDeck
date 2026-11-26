package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.stores.romm.RommInstalls
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class CoverArtRommTest {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()

    @Test fun noBoxArtIsLookedUpForAGameRommInstalled() {
        RommInstalls.record(app, RommInstalls.Entry("7", "psx/Game (USA).chd", "duckstation"))
        try {
            val rom = Library.Rom("Game", File(app.cacheDir, "psx/Game (USA).chd"), "/root/ROMs/psx/Game (USA).chd", "duckstation")
            assertFalse(CoverArt.fetchMissing(app, listOf(rom)))
            assertFalse(File(app.filesDir, "covers/duckstation/Game (USA).chd.miss").exists())
        } finally {
            RommInstalls.clear(app)
        }
    }

    @Test fun aCoverKeptForOneEmulatorIsFetchedAgainFromTheServerWhenAnotherPlaysTheGame() {
        com.droiddeck.launcher.stores.romm.RommFakeServer { r -> if (r.path == "/cover.png") 200 to "picture" else 404 to "" }.use { server ->
            val item = com.droiddeck.launcher.stores.CatalogItem(com.droiddeck.launcher.stores.Store.ROMM, "7", "Game", null, tallImageUrl = server.address + "/cover.png", owned = true)
            RommInstalls.record(app, RommInstalls.Entry("7", "psx/Game (USA).chd", "duckstation", "psx/Game (USA).chd", item))
            RommInstalls.record(app, RommInstalls.Entry("8", "psx/Other (USA).chd", "duckstation", "psx/Other (USA).chd", item.copy(id = "8", tallImageUrl = server.address + "/gone.png")))
            try {
                fun rom(name: String, emulator: String) = Library.Rom(name, File(app.cacheDir, "psx/$name (USA).chd"), "/root/ROMs/psx/$name (USA).chd", emulator)
                CoverArt.store(app, rom("Game", "duckstation"), "picture".toByteArray())
                assertNull(CoverArt.cached(app, rom("Game", "retroarch")))
                assertTrue(CoverArt.fetchMissing(app, listOf(rom("Game", "retroarch"), rom("Other", "retroarch"))))
                assertEquals("picture", CoverArt.cached(app, rom("Game", "retroarch"))!!.readText())
                assertNull(CoverArt.cached(app, rom("Other", "retroarch")))
                assertEquals(listOf("GET /cover.png", "GET /gone.png"), server.paths())
                assertFalse(CoverArt.fetchMissing(app, listOf(rom("Game", "retroarch"), rom("Other", "retroarch"))))
                assertEquals(2, server.seen.size)
            } finally {
                RommInstalls.clear(app)
                File(app.filesDir, "covers").deleteRecursively()
            }
        }
    }
}
