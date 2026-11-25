package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.stores.romm.RommInstalls
import java.io.File
import org.junit.Assert.assertFalse
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
}
