package com.droiddeck.launcher.ports

import org.junit.Assert.assertEquals
import org.junit.Test

class RunWithTest {
    @Test fun withOnlyOneWayToRunThereIsNoChoice() {
        assertEquals(RunWith.EMULATOR, RunWith.choose(RunWith.PORT, hasEmulator = true, hasPort = false, portInstalled = false))
        assertEquals(RunWith.PORT, RunWith.choose(RunWith.EMULATOR, hasEmulator = false, hasPort = true, portInstalled = false))
    }

    @Test fun theStoredChoiceWinsWhenBothCanRunTheGame() {
        assertEquals(RunWith.EMULATOR, RunWith.choose(RunWith.EMULATOR, hasEmulator = true, hasPort = true, portInstalled = true))
        assertEquals(RunWith.PORT, RunWith.choose(RunWith.PORT, hasEmulator = true, hasPort = true, portInstalled = false))
    }

    @Test fun withNothingStoredAnInstalledPortIsPreferred() {
        assertEquals(RunWith.PORT, RunWith.choose(null, hasEmulator = true, hasPort = true, portInstalled = true))
        assertEquals(RunWith.EMULATOR, RunWith.choose(null, hasEmulator = true, hasPort = true, portInstalled = false))
        assertEquals(RunWith.EMULATOR, RunWith.choose("junk", hasEmulator = true, hasPort = true, portInstalled = false))
    }
}
