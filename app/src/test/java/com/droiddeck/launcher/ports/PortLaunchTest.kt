package com.droiddeck.launcher.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortLaunchTest {
    @Test fun argumentsAreSplitOnSpacesAndQuotesKeepAWordTogether() {
        assertEquals(listOf("--game", "gold", "--rom", "{rom}"), PortLaunch.args("  --game gold   --rom {rom} "))
        assertEquals(listOf("--name", "two words", "it's", ""), PortLaunch.args("--name 'two words' \"it's\" ''"))
        assertEquals(emptyList<String>(), PortLaunch.args("   "))
        assertNull(PortLaunch.args("--name 'open"))
        assertNull(PortLaunch.args("x".repeat(201)))
    }

    @Test fun variablesAreNamesWithValues() {
        assertEquals(mapOf("GAME" to "gold", "ROM" to "{rom}", "EMPTY" to "", "TITLE" to "two words"), PortLaunch.env("GAME=gold ROM={rom} EMPTY= \"TITLE=two words\""))
        assertEquals(emptyMap<String, String>(), PortLaunch.env(""))
        for (bad in listOf("gold", "game=gold", "1A=x", "=x", "A B=x")) assertNull(bad, PortLaunch.env(bad))
    }

    @Test fun whatIsShownReadsBackTheSame() {
        val args = listOf("--rom", "{rom}", "two words", "it's", "")
        assertEquals(args, PortLaunch.args(PortLaunch.show(args)))
        val env = mapOf("GAME" to "gold", "TITLE" to "two words", "EMPTY" to "")
        assertEquals(env, PortLaunch.env(PortLaunch.show(env)))
    }
}
