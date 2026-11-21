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
        assertFalse(RommPlatforms.playable("psx", "Game.zip", systems))
        assertFalse(RommPlatforms.playable("snes", "Game.sfc", systems))
    }

    @Test fun theBuiltInEmulatorsCoverTheirRommPlatforms() {
        for (slug in listOf("psx", "ps2", "ps3", "psp", "ngc", "wii", "wiiu", "nds")) assertTrue(slug, RommPlatforms.target(slug) != null)
    }

    @Test fun anExistingFolderUnderAnotherAcceptedNameIsReused() {
        val root = tmp.root
        val target = RommPlatforms.target("psx", systems)!!
        assertEquals(File(root, "psx"), RommPlatforms.directory(root, target))
        File(root, "PS1").mkdirs()
        assertEquals(File(root, "PS1"), RommPlatforms.directory(root, target))
        File(root, "psx").mkdirs()
        assertEquals(File(root, "psx"), RommPlatforms.directory(root, target))
    }
}
