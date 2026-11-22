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

    @org.junit.Before fun aPortIsInUse() = com.droiddeck.launcher.ports.Ports.use(listOf(com.droiddeck.launcher.ports.TestPorts.zelda))

    @org.junit.After fun noPortIsInUse() = com.droiddeck.launcher.ports.Ports.use(emptyList())

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

    @Test fun aPlatformOnlyANativePortPlaysTargetsThatPort() {
        val target = RommPlatforms.target("n64", systems)!!
        assertEquals("port:zelda64recomp", target.emulatorId)
        assertEquals("n64", target.folder)
        assertTrue(RommPlatforms.playable("n64", "Game (USA).z64", systems))
        assertFalse(RommPlatforms.playable("n64", "Game.iso", systems))
    }

    @Test fun aDumpThePortCannotTakeIsRefusedBeforeDownloading() {
        val wanted = "d6133ace5afaa0882cf214cf88daba39e266c078"
        assertNull(RommPlatforms.refusedBy("n64", "Majora's Mask (U).z64", wanted.uppercase(), systems))
        assertEquals("zelda64recomp", RommPlatforms.refusedBy("n64", "Ocarina of Time (U).z64", "ad69c91157f6705e8ab06c79fe08aad47bb57ba7", systems)?.id)
        assertNull(RommPlatforms.refusedBy("n64", "Majora's Mask (U).v64", "0000", systems))
        assertNull(RommPlatforms.refusedBy("n64", "Majora's Mask (U).zip", "0000", systems))
        assertNull(RommPlatforms.refusedBy("n64", "Unknown.z64", "", systems))
        assertNull(RommPlatforms.refusedBy("psx", "Game.chd", "0000", systems))
    }

    @Test fun onlyFileTypesTheEmulatorListsArePlayable() {
        assertTrue(RommPlatforms.playable("psx", "Game (USA).CHD", systems))
        assertTrue(RommPlatforms.playable("psx", "Game.rar", systems))
        assertFalse(RommPlatforms.playable("psx", "Game.tar", systems))
        assertFalse(RommPlatforms.playable("snes", "Game.sfc", systems))
    }

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

    @Test fun theBuiltInEmulatorsCoverTheirRommPlatforms() {
        for (slug in listOf("psx", "ps2", "ps3", "psp", "ngc", "wii", "wiiu", "nds")) assertTrue(slug, RommPlatforms.target(slug) != null)
    }

    @Test fun cartridgeSystemsGoToRetroArch() {
        val slugs = listOf("snes", "sfam", "nes", "famicom", "gb", "gbc", "gba", "genesis", "genesis-slash-megadrive", "sms", "gamegear", "tg16", "virtualboy", "wonderswan", "wonderswan-color")
        for (slug in slugs) assertEquals(slug, "retroarch", RommPlatforms.target(slug)?.emulatorId)
        assertTrue(RommPlatforms.playable("snes", "Game (USA).sfc"))
        assertTrue(RommPlatforms.playable("gba", "Game.zip"))
        assertTrue(RommPlatforms.playable("gbc", "Game (Japan).7z"))
        assertFalse(RommPlatforms.playable("snes", "Game.iso"))
    }

    @Test fun severalDiscsWithoutAPlaylistGetOne() {
        val exts = setOf("cue", "chd", "bin", "m3u")
        assertEquals(listOf("Game (Disc 1).chd", "Game (Disc 2).chd"), RommPlatforms.discs(listOf("Game (Disc 2).chd", "Game (Disc 1).chd", "readme.txt"), exts))
        assertEquals(
            listOf("Game (Disc 1).cue", "Game (Disc 2).cue"),
            RommPlatforms.discs(listOf("Game (Disc 1).cue", "Game (Disc 1).bin", "Game (Disc 2).cue", "Game (Disc 2).bin"), exts),
        )
        assertEquals(emptyList<String>(), RommPlatforms.discs(listOf("Game (Disc 1).chd", "Game (Disc 2).chd", "Game.m3u"), exts))
        assertEquals(emptyList<String>(), RommPlatforms.discs(listOf("Game (Track 1).bin", "Game (Track 2).bin", "Game.cue"), exts))
        assertEquals(emptyList<String>(), RommPlatforms.discs(listOf("Game (Disc 1).chd"), exts))
        assertEquals(emptyList<String>(), RommPlatforms.discs(listOf("Game (Disc 1).iso", "Game (Disc 2).iso"), setOf("iso")))
    }

    @Test fun systemsWithAFetchedCoreBecomeInstallable() {
        val slugs = listOf("dc", "arcade", "atari2600", "atari5200", "atari7800", "neogeoaes", "neogeomvs", "jaguar")
        for (slug in slugs) assertNull(slug, RommPlatforms.target(slug))
        com.droiddeck.launcher.frontend.RetroArchCores.coresDir = tmp.newFolder("libretro").also { dir ->
            listOf("flycast", "fbneo", "stella", "a5200", "prosystem", "virtualjaguar").forEach { File(dir, "${it}_libretro.so").writeText("") }
        }
        try {
            for (slug in slugs) assertEquals(slug, "retroarch", RommPlatforms.target(slug)?.emulatorId)
            assertTrue(RommPlatforms.playable("dc", "Game (USA).chd"))
            assertTrue(RommPlatforms.playable("dc", "Game (USA).zip"))
            assertTrue(RommPlatforms.playable("arcade", "sf2.zip"))
            assertTrue(RommPlatforms.playable("atari2600", "Game.a26"))
            assertFalse(RommPlatforms.playable("jaguar", "Game.iso"))
        } finally {
            com.droiddeck.launcher.frontend.RetroArchCores.coresDir = null
        }
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
