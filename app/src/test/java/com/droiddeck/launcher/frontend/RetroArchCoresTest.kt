package com.droiddeck.launcher.frontend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroArchCoresTest {
    @Test fun aSystemWhoseCoreTheEmulatorsPackageDoesNotInstallIsNotListed() {
        for (folder in listOf("n64", "dc", "arcade", "neogeo", "atari2600", "jaguar")) {
            assertNull(folder, RetroArchCores.forFolder(folder))
            assertTrue(folder, Library.romSystems().none { folder in it.folders })
        }
        assertEquals(listOf("/root/ROMs/n64/Game.z64"), Library.launchArgs("retroarch", "/root/ROMs/n64/Game.z64"))
    }

    @Test fun aSystemFolderPicksItsCore() {
        assertEquals("snes9x", RetroArchCores.forFolder("snes")?.core)
        assertEquals("mgba", RetroArchCores.forFolder("GBA")?.core)
        assertEquals("genesis_plus_gx", RetroArchCores.forFolder("megadrive")?.core)
        assertNull(RetroArchCores.forFolder("psx"))
    }

    @Test fun aGameStartsFullScreenInItsCore() {
        assertEquals(
            listOf("-f", "-L", "/usr/lib/aarch64-linux-gnu/libretro/snes9x_libretro.so", "/root/ROMs/snes/USA/Game.sfc"),
            RetroArchCores.launchArgs("/root/ROMs/snes/USA/Game.sfc"),
        )
        assertEquals(RetroArchCores.launchArgs("/root/ROMs/gba/Game.zip"), Library.launchArgs("retroarch", "/root/ROMs/gba/Game.zip"))
    }

    @Test fun aGameOutsideTheSystemFoldersIsHandedOverAsIs() {
        assertEquals(listOf("/root/ROMs/misc/Game.bin"), RetroArchCores.launchArgs("/root/ROMs/misc/Game.bin"))
        assertEquals(listOf("/root/Storage/Game.sfc"), RetroArchCores.launchArgs("/root/Storage/Game.sfc"))
    }

    @Test fun aSystemNamesTheBiosItsGamesNeed() {
        assertEquals(listOf("bios_CD_U.bin", "bios_CD_E.bin", "bios_CD_J.bin"), RetroArchCores.firmwareFor("SegaCD"))
        assertEquals(listOf("syscard3.pce"), RetroArchCores.firmwareFor("pcenginecd"))
        assertNull(RetroArchCores.firmwareFor("neogeo"))
        assertNull(RetroArchCores.firmwareFor("snes"))
    }

    @Test fun cdSystemsPlayInTheCoresOfTheirCartridgeSystems() {
        assertEquals("genesis_plus_gx", RetroArchCores.forFolder("segacd")?.core)
        assertEquals("mednafen_pce_fast", RetroArchCores.forFolder("pcenginecd")?.core)
        assertTrue("cue" in RetroArchCores.forFolder("megacd")!!.exts)
        assertTrue("bin" !in RetroArchCores.forFolder("segacd")!!.exts)
    }

    @Test fun noFolderNameIsClaimedTwice() {
        val names = RetroArchCores.systems.flatMap { it.folders } + Library.romSystems().filter { it.emulatorId != "retroarch" }.flatMap { it.folders }
        assertEquals(names.size, names.toSet().size)
        assertEquals(RetroArchCores.folders, RetroArchCores.systems.flatMap { it.folders }.toSet())
    }
}
