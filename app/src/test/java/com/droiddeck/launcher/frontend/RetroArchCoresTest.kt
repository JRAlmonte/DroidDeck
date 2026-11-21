package com.droiddeck.launcher.frontend

import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RetroArchCoresTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun forgetCores() { RetroArchCores.coresDir = null }

    @Test fun n64IsASystemOnlyOnceItsCoreIsInstalled() {
        assertNull(RetroArchCores.forFolder("n64"))
        assertTrue("n64" in RetroArchCores.folders)
        assertTrue(Library.romSystems().none { "n64" in it.folders })
        assertEquals(listOf("/root/ROMs/n64/Game.z64"), Library.launchArgs("retroarch", "/root/ROMs/n64/Game.z64"))
        RetroArchCores.coresDir = tmp.newFolder("libretro").also { File(it, "mupen64plus_next_libretro.so").writeText("") }
        assertEquals("retroarch", Library.romSystems().first { "n64" in it.folders }.emulatorId)
        assertEquals(
            listOf("-f", "-L", "/usr/lib/aarch64-linux-gnu/libretro/mupen64plus_next_libretro.so", "/root/ROMs/n64/Game.z64"),
            Library.launchArgs("retroarch", "/root/ROMs/n64/Game.z64"),
        )
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

    @Test fun noFolderNameIsClaimedTwice() {
        val names = RetroArchCores.systems.flatMap { it.folders }
        assertEquals(names.size, names.toSet().size)
    }
}
