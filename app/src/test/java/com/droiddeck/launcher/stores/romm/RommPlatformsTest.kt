package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.frontend.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommPlatformsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val systems = listOf(
        Library.RomSystem("duckstation", listOf("ps1", "psx"), setOf("chd", "cue")),
        Library.RomSystem("dolphin", listOf("gc", "gamecube", "wii"), setOf("iso", "rvz")),
    )

    @Test fun aPlatformMapsToTheEmulatorThatScansItsFolder() {
        assertEquals("duckstation", RommPlatforms.target("psx", systems)?.emulatorId)
        assertEquals("dolphin", RommPlatforms.target("wii", systems)?.emulatorId)
    }

    @Test fun gameCubeUsesItsFolderName() {
        val target = RommPlatforms.target("ngc", systems)!!
        assertEquals("dolphin", target.emulatorId)
        assertEquals("gamecube", target.folder)
    }

    @Test fun aPlatformNoEmulatorScansHasNoTarget() {
        assertNull(RommPlatforms.target("snes", systems))
    }

    @Test fun onlyFileTypesTheEmulatorListsArePlayable() {
        assertTrue(RommPlatforms.playable("psx", "Game (USA).CHD", systems))
        assertFalse(RommPlatforms.playable("psx", "Game.7z", systems))
        assertFalse(RommPlatforms.playable("snes", "Game.sfc", systems))
    }

    @Test fun theBuiltInEmulatorsCoverTheirRommPlatforms() {
        for (slug in listOf("psx", "ps2", "ps3", "psp", "ngc", "wii", "wiiu", "nds")) assertTrue(slug, RommPlatforms.target(slug) != null)
    }

    @Test fun anExistingFolderUnderAnotherAcceptedNameIsReused() {
        val root = tmp.root
        val target = RommPlatforms.target("psx", systems)!!
        assertEquals(File(root, "psx"), RommPlatforms.directory(root, target))
    @Test fun aZipIsInstallableBecauseItMayHoldAPlayableFile() {
        assertTrue(RommPlatforms.playable("psx", "Game (USA).zip", systems))
        assertFalse(RommPlatforms.playable("snes", "Game.zip", systems))
    }

    @Test fun anUnpackedGameLaunchesFromItsDiscSheet() {
        val exts = setOf("cue", "chd", "bin", "m3u")
        assertEquals("Game.cue", RommPlatforms.mainFile(listOf("Game (Track 2).bin", "Game.cue", "Game (Track 1).bin"), exts))
        assertEquals("Game.m3u", RommPlatforms.mainFile(listOf("Game (Disc 1).cue", "Game.m3u", "Game (Disc 1).bin"), exts))
        assertEquals("Game.chd", RommPlatforms.mainFile(listOf("readme.txt", "Game.chd"), exts))
        assertNull(RommPlatforms.mainFile(listOf("readme.txt"), exts))
    }

        File(root, "PS1").mkdirs()
        assertEquals(File(root, "PS1"), RommPlatforms.directory(root, target))
        File(root, "psx").mkdirs()
        assertEquals(File(root, "psx"), RommPlatforms.directory(root, target))
    }
}
