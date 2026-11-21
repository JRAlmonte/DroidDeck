package com.droiddeck.launcher.frontend

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryRetroArchTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun forgetCores() { Library.coresDir = null }

    @Test fun n64IsARetroArchSystemOnlyOnceItsCoreIsInstalled() {
        assertTrue(Library.romSystems().none { "n64" in it.folders })
        assertEquals(listOf("/root/ROMs/n64/Game.z64"), Library.launchArgs("retroarch", "/root/ROMs/n64/Game.z64"))
        Library.coresDir = tmp.newFolder("libretro").also { File(it, "mupen64plus_next_libretro.so").writeText("") }
        assertEquals("retroarch", Library.romSystems().first { "n64" in it.folders }.emulatorId)
        assertEquals(
            listOf("-f", "-L", "/usr/lib/aarch64-linux-gnu/libretro/mupen64plus_next_libretro.so", "/root/ROMs/n64/Game.z64"),
            Library.launchArgs("retroarch", "/root/ROMs/n64/Game.z64"),
        )
    }

    @Test fun aRomStartsInTheCoreForItsSystemFolder() {
        assertEquals(
            listOf("-f", "-L", "/usr/lib/aarch64-linux-gnu/libretro/snes9x_libretro.so", "/root/ROMs/SNES/Game.sfc"),
            Library.launchArgs("retroarch", "/root/ROMs/SNES/Game.sfc"),
        )
        assertEquals("/usr/lib/aarch64-linux-gnu/libretro/genesis_plus_gx_libretro.so", Library.launchArgs("retroarch", "/root/ROMs/gamegear/a/Game.gg")[2])
        assertEquals(listOf("/root/ROMs/other/Game.rom"), Library.launchArgs("retroarch", "/root/ROMs/other/Game.rom"))
    }

    @Test fun onlyFilesInASystemFolderOfTheirOwnTypeAreListed() {
        val root = tmp.root
        fun file(path: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText("") }
        file("SNES/Game One (USA).sfc"); file("snes/sub/Game Two.smc"); file("snes/notes.txt"); file("snes/bios/firmware.sfc")
        file("gba/Game Three.gba"); file("gba/Wrong.sfc"); file("Loose.sfc"); file("other/Game.gba")
        val roms = Library.coreRoms(root)
        assertEquals(setOf("Game One", "Game Two", "Game Three"), roms.map { it.name }.toSet())
        assertEquals("/root/ROMs/gba/Game Three.gba", roms.first { it.name == "Game Three" }.guestPath)
        assertEquals(setOf("retroarch"), roms.map { it.emulatorId }.toSet())
    }
}
