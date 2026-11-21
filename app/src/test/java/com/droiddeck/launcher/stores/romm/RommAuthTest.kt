package com.droiddeck.launcher.stores.romm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class RommAuthTest {
    private val token = "rmm_" + "0123456789abcdef".repeat(4)

    @Test fun anAddressWithASchemeIsUsedAsTyped() {
        assertEquals(listOf("http://romm.example:8080"), RommAuth.candidates(" http://romm.example:8080/ "))
        assertEquals(listOf("https://romm.example"), RommAuth.candidates("https://romm.example/api/"))
    }

    @Test fun anAddressWithoutASchemeTriesHttpsThenHttp() {
        assertEquals(listOf("https://romm.example:8080", "http://romm.example:8080"), RommAuth.candidates("romm.example:8080"))
    }

    @Test fun nothingUsableGivesNoAddress() {
        assertEquals(emptyList<String>(), RommAuth.candidates("  "))
        assertEquals(emptyList<String>(), RommAuth.candidates("ftp://romm.example"))
        assertEquals(emptyList<String>(), RommAuth.candidates("romm example"))
        assertEquals(emptyList<String>(), RommAuth.candidates("http://"))
    }

    @Test fun clientTokensNeedRomm48() {
        assertTrue(RommAuth.supported("4.8.0"))
        assertTrue(RommAuth.supported("5.4.0"))
        assertTrue(RommAuth.supported("5.5.0-alpha.1"))
        assertTrue(RommAuth.supported("development"))
        assertFalse(RommAuth.supported("4.7.2"))
        assertFalse(RommAuth.supported("3.10.0"))
    }

    @Test fun aTokenIsFoundInsideCopiedText() {
        assertEquals(token, RommAuth.tokenIn("Your token: $token\n"))
        assertNull(RommAuth.tokenIn("rmm_short"))
    }

    private fun server(routes: Map<String, Pair<String, String>>, seen: MutableList<String>): java.net.ServerSocket {
        val server = java.net.ServerSocket(0)
        Thread {
            while (!server.isClosed) runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(input.read().takeIf { it >= 0 }?.toChar() ?: break)
                    val path = head.lineSequence().first().split(' ').getOrElse(1) { "" }
                    seen.add(path + " " + (Regex("(?i)authorization: *(.*)").find(head)?.groupValues?.get(1)?.trim() ?: "-"))
                    val (status, text) = routes[path.substringBefore('?')] ?: ("404 Not Found" to "{}")
                    val body = text.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return server
    }

    @Test fun aGoodServerAndTokenSignIn() {
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        server(mapOf(
            "/api/heartbeat" to ("200 OK" to """{"SYSTEM":{"VERSION":"5.4.0"}}"""),
            "/api/users/me" to ("200 OK" to """{"id":7,"username":"player"}"""),
        ), seen).use { s ->
            val address = "http://127.0.0.1:${s.localPort}"
            val result = RommAuth.verify("$address/", token)
            assertNull(result.problem)
            assertEquals(address, result.server)
            assertEquals("player", result.user?.optString("username"))
            assertEquals(listOf("/api/heartbeat -", "/api/users/me Bearer $token"), seen.toList())
        }
    }

    @Test fun eachFailureIsToldApart() {
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        assertEquals(RommAuth.Problem.UNREACHABLE, RommAuth.verify("http://127.0.0.1:1", token).problem)
        server(emptyMap(), seen).use { s ->
            assertEquals(RommAuth.Problem.NOT_ROMM, RommAuth.verify("http://127.0.0.1:${s.localPort}", token).problem)
        }
        server(mapOf("/api/heartbeat" to ("200 OK" to """{"SYSTEM":{"VERSION":"4.6.1"}}""")), seen).use { s ->
            assertEquals(RommAuth.Problem.TOO_OLD, RommAuth.verify("http://127.0.0.1:${s.localPort}", token).problem)
        }
        server(mapOf(
            "/api/heartbeat" to ("200 OK" to """{"SYSTEM":{"VERSION":"5.4.0"}}"""),
            "/api/users/me" to ("401 Unauthorized" to """{"detail":"Not authenticated"}"""),
        ), seen).use { s ->
            assertEquals(RommAuth.Problem.BAD_TOKEN, RommAuth.verify("http://127.0.0.1:${s.localPort}", token).problem)
        }
    }
}
