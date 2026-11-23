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

    @Test fun anAddressOnTheUsersNetworkTriesHttpsThenHttp() {
        for (address in listOf("192.168.1.20:8080", "10.0.0.5", "172.20.1.1", "100.100.4.2", "nas:8080", "romm.local", "romm.lan/", "[fd12::1]:8080", "localhost:3000"))
            assertEquals(address, listOf("https://${address.trimEnd('/')}", "http://${address.trimEnd('/')}"), RommAuth.candidates(address))
    }

    @Test fun anAddressOnTheInternetIsNeverTriedOverPlainHttp() {
        for (address in listOf("romm.example:8080", "8.8.8.8", "172.32.0.1", "192.169.1.1", "[2001:db8::1]", "local.example.com"))
            assertEquals(address, listOf("https://$address"), RommAuth.candidates(address))
        assertFalse(RommAuth.mayBePlain("romm.example"))
        assertTrue(RommAuth.mayBePlain("http://romm.example"))
        assertTrue(RommAuth.mayBePlain("192.168.1.20"))
        assertFalse(RommAuth.mayBePlain("https://192.168.1.20"))
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
            val result = RommAuth.verify("$address/", "", token)
            assertNull(result.problem)
            assertEquals(address, result.server)
            assertEquals("player", result.user?.optString("username"))
            assertEquals(token, result.token)
            assertEquals(listOf("/api/heartbeat -", "/api/users/me Bearer $token"), seen.toList())
        }
    }

    @Test fun aPasswordIsTradedForATokenWithTheScopesTheAccountHas() {
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        val login = "Basic " + java.util.Base64.getEncoder().encodeToString("player:secret".toByteArray())
        server(mapOf(
            "/api/heartbeat" to ("200 OK" to """{"SYSTEM":{"VERSION":"5.4.0"}}"""),
            "/api/users/me" to ("200 OK" to """{"id":7,"username":"player","oauth_scopes":["me.read","me.write","roms.read","platforms.read"]}"""),
            "/api/client-tokens" to ("201 Created" to """{"id":1,"raw_token":"$token"}"""),
        ), seen).use { s ->
            val result = RommAuth.verify("http://127.0.0.1:${s.localPort}", " player ", "secret")
            assertNull(result.problem)
            assertEquals(token, result.token)
            assertEquals(1L, result.tokenId)
            assertEquals(listOf("me.read", "me.write", "roms.read", "platforms.read"), result.scopes)
            assertEquals(listOf("/api/heartbeat -", "/api/users/me $login", "/api/client-tokens $login"), seen.toList())
        }
    }

    @Test fun aWrongPasswordIsNotATokenProblem() {
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        server(mapOf(
            "/api/heartbeat" to ("200 OK" to """{"SYSTEM":{"VERSION":"5.4.0"}}"""),
            "/api/users/me" to ("401 Unauthorized" to "{}"),
        ), seen).use { s ->
            assertEquals(RommAuth.Problem.BAD_LOGIN, RommAuth.verify("http://127.0.0.1:${s.localPort}", "player", "nope").problem)
        }
        server(mapOf(
            "/api/heartbeat" to ("200 OK" to """{"SYSTEM":{"VERSION":"5.4.0"}}"""),
            "/api/users/me" to ("200 OK" to """{"id":7,"username":"player","oauth_scopes":["me.read","roms.read"]}"""),
            "/api/client-tokens" to ("403 Forbidden" to "{}"),
        ), seen).use { s ->
            assertEquals(RommAuth.Problem.NO_TOKEN, RommAuth.verify("http://127.0.0.1:${s.localPort}", "player", "secret").problem)
        }
    }

    @Test fun eachFailureIsToldApart() {
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        assertEquals(RommAuth.Problem.UNREACHABLE, RommAuth.verify("http://127.0.0.1:1", "", token).problem)
        server(emptyMap(), seen).use { s ->
            assertEquals(RommAuth.Problem.NOT_ROMM, RommAuth.verify("http://127.0.0.1:${s.localPort}", "", token).problem)
        }
        server(mapOf("/api/heartbeat" to ("200 OK" to """{"SYSTEM":{"VERSION":"4.6.1"}}""")), seen).use { s ->
            assertEquals(RommAuth.Problem.TOO_OLD, RommAuth.verify("http://127.0.0.1:${s.localPort}", "", token).problem)
        }
        server(mapOf(
            "/api/heartbeat" to ("200 OK" to """{"SYSTEM":{"VERSION":"5.4.0"}}"""),
            "/api/users/me" to ("401 Unauthorized" to """{"detail":"Not authenticated"}"""),
        ), seen).use { s ->
            assertEquals(RommAuth.Problem.BAD_TOKEN, RommAuth.verify("http://127.0.0.1:${s.localPort}", "", token).problem)
        }
    }

    private val beat = """{"SYSTEM":{"VERSION":"5.4.0"}}"""
    private val started = """{"device_code":"dc1","user_code":"ABCD2345","verification_path":"/pair/device","verification_path_complete":"/pair/device?user_code=ABCD2345","expires_in":600,"interval":5}"""

    @Test fun aCodeIsAskedForAndTheTokenArrivesOnceItIsApproved() {
        var polls = 0
        RommFakeServer { request ->
            when (request.path) {
                "/api/heartbeat" -> 200 to beat
                "/api/auth/device/init" -> 201 to started
                "/api/auth/device/token" -> if (++polls < 3) 400 to """{"detail":"authorization_pending"}""" else 200 to """{"access_token":"$token","device_id":"d1","scopes":["me.read","roms.read"]}"""
                "/api/users/me" -> 200 to """{"id":7,"username":"player"}"""
                "/api/client-tokens" -> 200 to """[{"id":3,"device_id":"d1"},{"id":5,"device_id":"d1"},{"id":9,"device_id":"d2"},{"id":11,"device_id":null}]"""
                else -> 404 to "{}"
            }
        }.use { server ->
            val (pairing, problem) = RommAuth.pair(server.address, "install-1", "DroidDeck (Test)")
            assertNull(problem)
            assertEquals("ABCD2345", pairing!!.userCode)
            assertEquals("${server.address}/pair/device?user_code=ABCD2345", pairing.url)
            val asked = org.json.JSONObject(server.seen[1].text)
            assertEquals("install-1", asked.optString("client_device_identifier"))
            assertEquals("DroidDeck (Test)", asked.optString("name"))
            assertTrue(asked.optJSONArray("requested_scopes")!!.length() > 5)
            assertTrue(asked.optJSONArray("requested_scopes").toString().contains("\"me.write\""))
            assertEquals("", server.seen[1].authorization)

            var slept = 0L
            val result = RommAuth.awaitPairing(pairing, { false }) { slept += it }!!
            assertNull(result.problem)
            assertEquals(token, result.token)
            assertEquals("player", result.user?.optString("username"))
            assertEquals(listOf("me.read", "roms.read"), result.scopes)
            assertEquals("5.4.0", result.version)
            assertEquals(5L, result.tokenId)
            assertEquals(15_000L, slept)
            assertEquals("""{"device_code":"dc1"}""", server.seen[2].text)
            assertEquals("Bearer $token", server.seen.last().authorization)
            assertNull(RommAuth.awaitPairing(pairing, { server.seen.count { it.path == "/api/users/me" } > 1 }) {})
        }
    }

    @Test fun signingOutDeletesOnlyATokenMadeHereThatMayDeleteItself() {
        fun account(id: Long?, vararg scopes: String, address: String) = org.json.JSONObject()
            .put("server", address).put("access_token", token).put("scopes", org.json.JSONArray(scopes.toList()))
            .apply { if (id != null) put("token_id", id) }
        RommFakeServer { request -> if (request.method == "DELETE" && request.path == "/api/client-tokens/5") 200 to "null" else 404 to "{}" }.use { server ->
            assertFalse(RommAuth.revoke(null))
            assertFalse(RommAuth.revoke(account(null, "me.read", "me.write", address = server.address)))
            assertFalse(RommAuth.revoke(account(5, "me.read", address = server.address)))
            assertFalse(RommAuth.revoke(org.json.JSONObject().put("server", server.address).put("access_token", token).put("token_id", 5)))
            assertTrue(server.seen.isEmpty())
            assertTrue(RommAuth.revoke(account(5, "me.read", "me.write", address = server.address)))
            assertEquals(listOf("DELETE /api/client-tokens/5"), server.paths())
            assertEquals("Bearer $token", server.seen.single().authorization)
        }
    }

    @Test fun aRefusedOrRunOutCodeAndAServerWithoutCodesAreToldApart() {
        var detail = "access_denied"
        RommFakeServer { request ->
            when (request.path) {
                "/api/heartbeat" -> 200 to beat
                "/api/auth/device/init" -> 201 to started
                "/api/auth/device/token" -> 400 to """{"detail":"$detail"}"""
                else -> 404 to "{}"
            }
        }.use { server ->
            val pairing = RommAuth.pair(server.address, "install-1").first!!
            assertEquals(RommAuth.Problem.PAIR_DENIED, RommAuth.awaitPairing(pairing, { false }) {}!!.problem)
            detail = "expired_token"
            assertEquals(RommAuth.Problem.PAIR_EXPIRED, RommAuth.awaitPairing(pairing, { false }) {}!!.problem)
            detail = "authorization_pending"
            val before = server.seen.size
            assertEquals(RommAuth.Problem.PAIR_EXPIRED, RommAuth.awaitPairing(pairing, { false }) {}!!.problem)
            assertEquals(120, server.seen.size - before)
            assertNull(RommAuth.awaitPairing(pairing, { true }) {})
        }
        RommFakeServer { request -> if (request.path == "/api/heartbeat") 200 to """{"SYSTEM":{"VERSION":"4.9.0"}}""" else 404 to """{"detail":"Not Found"}""" }.use { server ->
            assertEquals(null to RommAuth.Problem.PAIR_UNSUPPORTED, RommAuth.pair(server.address, "install-1"))
        }
        assertEquals(RommAuth.Problem.UNREACHABLE, RommAuth.pair("http://127.0.0.1:1", "install-1").second)
    }
}
