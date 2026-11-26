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
        assertFalse(RommAuth.plain("romm.example"))
        assertTrue(RommAuth.plain("http://romm.example"))
        assertFalse(RommAuth.plain("192.168.1.20"))
        assertFalse(RommAuth.plain("https://192.168.1.20"))
    }

    @Test fun anAddressCarryingANameAndPasswordIsNotUsed() {
        assertEquals(emptyList<String>(), RommAuth.candidates("http://player:secret@romm.example"))
        assertEquals(emptyList<String>(), RommAuth.candidates("player@192.168.1.20"))
        assertEquals(listOf("https://romm.example/sub@path"), RommAuth.candidates("romm.example/sub@path"))
    }

    @Test fun thePairingPageIsAlwaysOnTheServerThatWasAsked() {
        assertTrue(RommAuth.pathOnly("/pair/device?user_code=ABCD2345"))
        for (path in listOf("", "pair/device", "//evil.example/pair", "@evil.example/pair", "/pair@evil.example", "/pair\\device", "/pair device", "https://evil.example/pair"))
            assertFalse(path, RommAuth.pathOnly(path))
        RommFakeServer { request ->
            when (request.path) {
                "/api/heartbeat" -> 200 to beat
                "/api/auth/device/init" -> 201 to """{"device_code":"dc1","user_code":"ABCD2345","verification_path":"@evil.example/pair","verification_path_complete":"//evil.example/pair"}"""
                else -> 404 to "{}"
            }
        }.use { server -> assertEquals("${server.address}/pair/device", RommAuth.pair(server.address, "install-1").first!!.url) }
    }

    @Test fun aTokenTheServerDoesNotDeleteIsAskedAboutAgainAndThenLeft() {
        val account = org.json.JSONObject().put("token_id", 5).put("scopes", org.json.JSONArray(listOf("me.write")))
            .put("access_token", token)
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        var waited = 0L
        RommFakeServer { 503 to "{}" }.use { server ->
            RommAuth.revokeOrTell(app, account.put("server", server.address)) { waited += it }
            assertEquals(listOf("DELETE /api/client-tokens/5", "DELETE /api/client-tokens/5", "DELETE /api/client-tokens/5"), server.paths())
            assertEquals(8_000L, waited)
        }
        RommFakeServer { 200 to "null" }.use { server ->
            RommAuth.revokeOrTell(app, account.put("server", server.address)) { throw AssertionError("the first answer was enough") }
            assertEquals(1, server.seen.size)
            RommAuth.revokeOrTell(app, org.json.JSONObject().put("server", server.address).put("access_token", token)) {}
            assertEquals(1, server.seen.size)
        }
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
                    val first = input.read()
                    // A TLS greeting: this server speaks plain HTTP alone and hangs up on it.
                    if (first == 0x16 || first < 0) return@use
                    val head = StringBuilder().append(first.toChar())
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

    @Test fun aServerThatAnswersOnlyWithoutEncryptionIsSentNothingSecretUntilTheAddressSaysHttp() {
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        server(mapOf(
            "/api/heartbeat" to ("200 OK" to """{"SYSTEM":{"VERSION":"5.4.0"}}"""),
            "/api/users/me" to ("200 OK" to """{"id":7,"username":"player"}"""),
            "/api/client-tokens" to ("201 Created" to """{"id":1,"raw_token":"$token"}"""),
            "/api/auth/device/init" to ("201 Created" to started),
        ), seen).use { s ->
            val address = "127.0.0.1:${s.localPort}"
            val result = RommAuth.verify(address, "player", "secret")
            assertEquals(RommAuth.Problem.PLAIN, result.problem)
            assertEquals("http://$address", result.server)
            assertEquals(RommAuth.Problem.PLAIN, RommAuth.verify(address, "", token).problem)
            assertEquals(null to RommAuth.Problem.PLAIN, RommAuth.pair(address, "install-1"))
            assertEquals(listOf("/api/heartbeat -", "/api/heartbeat -", "/api/heartbeat -"), seen.toList())
            assertNull(RommAuth.verify(result.server, "", token).problem)
            assertEquals("/api/users/me Bearer $token", seen.last())
        }
    }

    /** A TLS identity no device trusts, made with the JDK's own keytool; null where that cannot be done. */
    private fun selfSigned(): javax.net.ssl.SSLContext? = runCatching {
        val tool = listOf("keytool", "keytool.exe").map { java.io.File(System.getProperty("java.home"), "bin/$it") }.first { it.isFile }
        val store = java.io.File.createTempFile("romm", ".p12").apply { delete() }
        try {
            val made = ProcessBuilder(
                tool.path, "-genkeypair", "-alias", "romm", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=127.0.0.1",
                "-storetype", "PKCS12", "-keystore", store.path, "-storepass", "changeit", "-keypass", "changeit",
            ).redirectErrorStream(true).start()
            made.inputStream.readBytes()
            check(made.waitFor() == 0)
            val keys = java.security.KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "changeit".toCharArray()) } }
            val managers = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "changeit".toCharArray()) }
            javax.net.ssl.SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        } finally {
            store.delete()
        }
    }.getOrNull()

    @Test fun aCertificateTheDeviceRefusesEndsTheSignInWithoutTryingPlainHttp() {
        val tls = selfSigned()
        org.junit.Assume.assumeNotNull(tls)
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val failures = java.util.Collections.synchronizedList(ArrayList<String>())
        val socket = java.net.ServerSocket(0)
        // Every connection is greeted with TLS and the untrusted certificate, so a second one after a refusal would be the retry over plain HTTP.
        Thread {
            while (!socket.isClosed) runCatching {
                socket.accept().use { client ->
                    asked.incrementAndGet()
                    client.soTimeout = 5_000
                    val secure = tls!!.socketFactory.createSocket(client, "127.0.0.1", socket.localPort, true) as javax.net.ssl.SSLSocket
                    secure.useClientMode = false
                    try {
                        secure.startHandshake()
                        failures.add("the certificate was trusted")
                    } catch (e: java.io.IOException) {
                    }
                }
            }.onFailure { e -> if (!socket.isClosed) failures.add(generateSequence(e) { it.cause }.joinToString(" <- ")) }
        }.apply { isDaemon = true; start() }
        socket.use {
            val address = "127.0.0.1:${socket.localPort}"
            val result = RommAuth.verify(address, "player", "secret")
            assertEquals(failures.toString(), RommAuth.Problem.UNTRUSTED, result.problem)
            assertEquals("https://$address", result.server)
            assertEquals(1, asked.get())
            assertEquals(null to RommAuth.Problem.UNTRUSTED, RommAuth.pair(address, "install-1"))
            assertEquals(RommAuth.Problem.UNTRUSTED, RommAuth.verify("https://$address", "", token).problem)
            assertEquals(3, asked.get())
            assertEquals(emptyList<String>(), failures.toList())
        }
    }

    @Test fun onlyARefusedCertificateCountsAsUntrusted() {
        val refused = javax.net.ssl.SSLHandshakeException("handshake").apply { initCause(java.security.cert.CertificateException("unknown issuer")) }
        assertTrue(RommApi.refusedCertificate(refused))
        assertTrue(RommApi.refusedCertificate(javax.net.ssl.SSLPeerUnverifiedException("another host's certificate")))
        assertFalse(RommApi.refusedCertificate(javax.net.ssl.SSLHandshakeException("not TLS at all")))
        assertFalse(RommApi.refusedCertificate(java.net.ConnectException("refused")))
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
