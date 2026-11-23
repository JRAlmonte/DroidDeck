package com.droiddeck.launcher.frontend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomControlsTest {
    private val keys = listOf("rom:psx/Doom.chd", "system:ps1")

    @Test fun aGamesChoiceComesBeforeItsSystems() {
        val saved = mapOf("ps1Pad.system:ps1" to "DigitalController", "ps1Pad.rom:psx/Doom.chd" to "AnalogController")
        assertEquals("AnalogController", RomControls.resolve(saved::get, keys, RomControls.Setting.PS1_PAD))
        assertEquals("DigitalController", RomControls.resolve(saved::get, listOf("rom:psx/Quake.chd", "system:ps1"), RomControls.Setting.PS1_PAD))
        assertEquals(listOf("DROIDDECK_PS1_PAD=AnalogController"), RomControls.env(saved::get, keys))
    }

    @Test fun whatNobodyChoseIsNotToldToTheEmulator() {
        assertTrue(RomControls.env({ null }, keys).isEmpty())
        assertTrue(RomControls.env(mapOf("ps1Pad.system:ps1" to "DigitalController")::get, emptyList()).isEmpty())
        assertNull(RomControls.resolve(mapOf("analogDpad.system:ps1" to "7")::get, keys, RomControls.Setting.ANALOG_DPAD))
    }

    @Test fun eachEmulatorIsOfferedItsOwnSettings() {
        assertEquals(listOf(RomControls.Setting.PS1_PAD), RomControls.forEmulator("duckstation"))
        assertEquals(listOf(RomControls.Setting.ANALOG_DPAD), RomControls.forEmulator("retroarch"))
        assertTrue(RomControls.forEmulator("dolphin").isEmpty())
    }
}
