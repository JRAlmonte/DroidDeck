package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.input.validScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun aNameTheInputSettingsWouldRefuseHasNoGameScope() {
        for (odd in listOf("\r", "\n", "\u0085", "\u2028", "\u2029")) {
            val scope = RomScope.of("duckstation", "/root/ROMs/psx/Odd${odd}Name.chd", systems)
            assertNull(scope.game)
            assertEquals(listOf("system:ps1"), scope.keys)
        }
        assertEquals("rom:psx/Pokémon (日本).chd", RomScope.of("duckstation", "/root/ROMs/psx/Pokémon (日本).chd", systems).game)
    }

    @Test fun aSystemKeepsItsNameWhenAnEmulatorIsPickedForOneOfItsFolders() {
        val picked = listOf(Library.RomSystem("retroarch", listOf("psx"), setOf("chd"), picked = true)) + systems
        assertEquals("system:ps1", RomScope.of("retroarch", "/root/ROMs/psx/Doom.chd", picked).system)
        assertEquals("system:ps1", RomScope.of("duckstation", "/root/ROMs/ps1/Doom.chd", picked).system)
        assertEquals("system:ps3", RomScope.of("rpcs3", "/media/EBOOT.BIN", listOf(Library.RomSystem("rpcs3", listOf("ps3"), setOf("iso"), picked = true)) + systems).system)
    }

    @Test fun theSystemsOfTheAppGiveEveryEmulatorOneName() {
        assertEquals("system:ps1", RomScope.of("duckstation", "/root/ROMs/psx/Doom.chd").system)
    }
}
