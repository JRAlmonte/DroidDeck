package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.input.validScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RomScopeTest {
    private val systems = listOf(
        Library.RomSystem("duckstation", listOf("ps1", "psx"), setOf("chd")),
        Library.RomSystem("rpcs3", listOf("ps3"), setOf("iso")),
        Library.RomSystem("retroarch", listOf("snes", "sfc"), setOf("sfc")),
        Library.RomSystem("retroarch", listOf("megadrive", "genesis"), setOf("md")),
    )

    @Test fun aGameIsKnownByItsPathAndItsSystemByTheFirstNameOfItsFolder() {
        val scope = RomScope.of("duckstation", "/root/ROMs/psx/Final Fantasy VII (USA).m3u", systems)
        assertEquals("rom:psx/Final Fantasy VII (USA).m3u", scope.game)
        assertEquals("system:ps1", scope.system)
        assertEquals(listOf("rom:psx/Final Fantasy VII (USA).m3u", "system:ps1"), scope.keys)
        assertEquals("system:ps1", RomScope.of("duckstation", "/root/ROMs/PS1/Doom/Doom.chd", systems).system)
        assertEquals("system:megadrive", RomScope.of("retroarch", "/root/ROMs/genesis/Sonic.md", systems).system)
        assertTrue(scope.keys.all(::validScope))
    }

    @Test fun aGameOutsideTheRomFoldersTakesItsEmulatorsSystem() {
        val scope = RomScope.of("rpcs3", "/root/.config/rpcs3/dev_hdd0/game/NPUB30001/USRDIR/EBOOT.BIN", systems)
        assertEquals("rom:/root/.config/rpcs3/dev_hdd0/game/NPUB30001/USRDIR/EBOOT.BIN", scope.game)
        assertEquals("system:ps3", scope.system)
        assertNull(RomScope.of("retroarch", "/media/Sonic.md", systems).system)
        assertEquals("system:homebrew", RomScope.of("retroarch", "/root/ROMs/Homebrew/Game.bin", systems).system)
    }

    @Test fun aPathTooLongToKeepHasNoGameScope() {
        val scope = RomScope.of("duckstation", "/root/ROMs/psx/" + "a".repeat(600) + ".chd", systems)
        assertNull(scope.game)
        assertEquals(listOf("system:ps1"), scope.keys)
    }

    @Test fun aPortIsKnownByItsRomOrByItself() {
        val roms = File("/sdcard/ROMs")
        val withRom = RomScope.ofPort("sm64", listOf("n64"), File(roms, "n64/Mario.z64"), roms, systems)
        assertEquals(listOf("rom:n64/Mario.z64", "system:n64"), withRom.keys)
        assertEquals(listOf("rom:port/doom", "system:dos"), RomScope.ofPort("doom", listOf("dos"), null, roms, systems).keys)
        assertEquals(listOf("rom:port/tool"), RomScope.ofPort("tool", emptyList(), null, null, systems).keys)
    }

    @Test fun theSystemsOfTheAppGiveEveryEmulatorOneName() {
        assertEquals("system:ps1", RomScope.of("duckstation", "/root/ROMs/psx/Doom.chd").system)
    }
}
