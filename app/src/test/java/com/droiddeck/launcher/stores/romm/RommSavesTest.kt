package com.droiddeck.launcher.stores.romm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommSavesTest {
    @Test fun aGamesSavesAreTheFilesNamedAfterIt() {
        assertTrue(RommSaves.belongs("saves/Game.srm", "Game"))
        assertTrue(RommSaves.belongs("saves/Game.rtc", "Game"))
        assertTrue(RommSaves.belongs("states/Game.state", "Game"))
        assertTrue(RommSaves.belongs("states/Game.state3", "Game"))
        assertTrue(RommSaves.belongs("states/Game.state.auto", "Game"))
    }

    @Test fun anotherGamesFilesAreLeftAlone() {
        assertFalse(RommSaves.belongs("saves/Game 2.srm", "Game"))
        assertFalse(RommSaves.belongs("saves/Game.v2.srm", "Game"))
        assertFalse(RommSaves.belongs("saves/Gamer.srm", "Game"))
        assertFalse(RommSaves.belongs("saves/sub/Game.srm", "Game"))
        assertFalse(RommSaves.belongs("states/Game.state.png", "Game"))
        assertFalse(RommSaves.belongs("states/Game.srm", "Game"))
        assertFalse(RommSaves.belongs("thumbnails/Game.srm", "Game"))
        assertFalse(RommSaves.belongs("saves/Game.", "Game"))
    }

    @Test fun theNameComesFromTheFileRetroArchOpens() {
        assertEquals("Game (USA)", RommSaves.base(RommInstalls.Entry("1", "snes/Game (USA).zip", "retroarch")))
        assertEquals("Game", RommSaves.base(RommInstalls.Entry("1", "psx/Game/Game.m3u", "retroarch", "psx/Game")))
        assertNull(RommSaves.base(RommInstalls.Entry("1", "psx/Game.chd", "duckstation")))
    }
}
