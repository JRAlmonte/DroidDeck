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

    @Test fun aPlatformNothingPlaysStillHasAFolderToKeepItsGamesIn() {
        val held = RommPlatforms.held("switch")
        assertTrue(held.held)
        assertEquals("switch", held.folder)
        assertEquals(File(tmp.root, "switch"), RommPlatforms.directory(tmp.root, held))
        assertEquals("gamecube", RommPlatforms.held("ngc").folder)
        assertFalse(RommPlatforms.target("psx", systems)!!.held)
    }

    @Test fun aFolderNameThatLeadsOutOfTheRomsFolderIsNotSafe() {
        for (slug in listOf("psx", "ngc", "genesis-slash-megadrive", "turbografx16--1", "3DO", "my.system_2")) assertTrue(slug, RommPlatforms.safe(slug))
        for (slug in listOf("", ".", "..", "../Download", "psx/../..", "a\\b", "/sdcard", ".hidden", "a".repeat(65))) assertFalse(slug, RommPlatforms.safe(slug))
    }

    @Test fun aGameKeptWhileNothingPlayedItRunsWithWhatPlaysItsFileNow() {
        fun kept(path: String) = RommInstalls.Entry("1", path, "")
        assertEquals("", RommPlatforms.runner(kept("switch/Game.nsp"), systems))
        assertEquals("duckstation", RommPlatforms.runner(kept("psx/Game.chd"), systems))
        assertEquals("", RommPlatforms.runner(kept("psx/Game.zip"), systems))
        assertEquals("", RommPlatforms.runner(kept("psx/Game"), systems))
        assertEquals("", RommPlatforms.runner(kept("n64/Game.z64"), systems))
        assertEquals("dolphin", RommPlatforms.runner(RommInstalls.Entry("2", "wii/Game.iso", "dolphin"), emptyList()))
        val gameCube = com.droiddeck.launcher.stores.CatalogItem(com.droiddeck.launcher.stores.Store.ROMM, "3", "Game", null, extra = mapOf(RommCatalog.PLATFORM to "ngc"))
        assertEquals("dolphin", RommPlatforms.runner(RommInstalls.Entry("3", "GameCube/Game.rvz", "", item = gameCube), systems))
    }

    @Test fun aPickForTheFolderWinsOverTheEmulatorTheGameWasInstalledFor() {
        val picked = listOf(Library.RomSystem("retroarch", listOf("psx"), setOf("chd", "cue"))) + systems
        val game = RommInstalls.Entry("1", "psx/Game.chd", "duckstation")
        assertEquals("duckstation", RommPlatforms.runner(game, systems, emptyMap()))
        assertEquals("retroarch", RommPlatforms.runner(game, picked, mapOf("psx" to "retroarch:mednafen_psx")))
        val pbp = RommInstalls.Entry("2", "psx/Game.pbp", "duckstation")
        assertEquals("duckstation", RommPlatforms.runner(pbp, picked, mapOf("psx" to "retroarch:mednafen_psx")))
    }

    @Test fun aPickForTheGameItselfWinsOverEverythingElse() {
        val game = RommInstalls.Entry("1", "psx/Game.chd", "duckstation")
        com.droiddeck.launcher.frontend.EmulatorLinks.replace(emptyList(), games = mapOf("psx/Game.chd" to "retroarch:mednafen_psx"))
        try {
            assertEquals("retroarch", RommPlatforms.runner(game, systems, emptyMap()))
            assertEquals("duckstation", RommPlatforms.runner(RommInstalls.Entry("2", "psx/Other.chd", "duckstation"), systems, emptyMap()))
        } finally {
            com.droiddeck.launcher.frontend.EmulatorLinks.replace(emptyList())
        }
    }

    @Test fun aGameWhoseEmulatorIsGoneTakesWhatPlaysItNow() {
        val game = RommInstalls.Entry("1", "switch/Game.nsp", "ryujinx")
        assertEquals("", RommPlatforms.runner(game, systems, emptyMap()))
        val eden = systems + Library.RomSystem("eden", listOf("switch"), setOf("nsp", "xci"))
        assertEquals("eden", RommPlatforms.runner(game, eden, emptyMap()))
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

    @Test fun aFolderGameLaunchesFromItsSheetOrElseItsLargestFileAtTheTop() {
        val exts = setOf("xci", "nsp", "cue", "bin", "m3u")
        fun files(vararg sized: Pair<String, Long>) = sized.mapIndexed { n, (path, size) -> RommFile("$n", path, size) }
        assertEquals("Game.xci", RommPlatforms.launchFile(files("Game Upd v1.4.nsp" to 300L, "Game.xci" to 8000L, "Game DLC.nsp" to 10L), exts))
        assertEquals("a file below the top is passed over while one at the top will do", "Game DLC.nsp", RommPlatforms.launchFile(files("Updates/Game Upd.nsp" to 9000L, "Game DLC.nsp" to 10L), exts))
        assertEquals("Updates/Game.xci", RommPlatforms.launchFile(files("Updates/Game Upd.nsp" to 300L, "Updates/Game.xci" to 8000L, "readme.txt" to 99999L), exts))
        assertEquals("of two files of one size the first by name", "a.nsp", RommPlatforms.launchFile(files("b.xci" to 0L, "a.nsp" to 0L), exts))
        assertEquals("a sheet still comes before any file it names", "Game.cue", RommPlatforms.launchFile(files("Game (Track 1).bin" to 9000L, "Game.cue" to 1L), exts))
        assertEquals("Game.m3u", RommPlatforms.launchFile(files("Game (Disc 1).cue" to 1L, "Game.m3u" to 1L, "Game (Disc 1).bin" to 9000L), exts))
        assertNull(RommPlatforms.launchFile(files("readme.txt" to 5L), exts))
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

    @Test fun aSystemTheEmulatorsPackageHasNoCoreForHasNoTarget() {
        for (slug in listOf("n64", "dc", "arcade", "atari2600", "atari5200", "atari7800", "neogeoaes", "neogeomvs", "jaguar")) {
            assertNull(slug, RommPlatforms.target(slug))
            assertFalse(slug, RommPlatforms.playable(slug, "Game.zip"))
        }
        assertEquals("retroarch", RommPlatforms.target("snes")?.emulatorId)
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
