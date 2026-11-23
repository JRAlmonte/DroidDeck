package com.droiddeck.launcher.frontend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CoverArtKeyTest {
    @Test fun gamesOfTwoRetroArchSystemsWithOneFileNameKeepTheirOwnCovers() {
        val genesis = CoverArt.keyOf("retroarch", "/root/ROMs/genesis/Sonic (USA).zip", "Sonic (USA).zip")
        val gameGear = CoverArt.keyOf("retroarch", "/root/ROMs/gamegear/Sonic (USA).zip", "Sonic (USA).zip")
        assertNotEquals(genesis, gameGear)
        assertEquals("genesis~Sonic (USA).zip", genesis)
    }

    @Test fun anEmulatorOfOneSystemKeepsTheKeyItHad() {
        assertEquals("Game_ Part 2.chd", CoverArt.keyOf("duckstation", "/root/ROMs/psx/Game: Part 2.chd", "Game: Part 2.chd"))
    }
}
