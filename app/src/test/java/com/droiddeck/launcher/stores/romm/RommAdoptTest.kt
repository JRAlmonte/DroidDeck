package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.CredentialCipher
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class RommAdoptTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val token = "rmm_" + "0123456789abcdef".repeat(4)

    @Before fun setUp() {
        StoreAccounts.keys = CredentialCipher.KeyProvider { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        StoreAccounts.keystoreFailed = false
        RommInstalls.clear(app)
        RommAdopt.clear(app)
    }

    @After fun tearDown() {
        StoreAccounts.clear(app, Store.ROMM)
        StoreAccounts.keys = CredentialCipher.Keystore
        StoreAccounts.keystoreFailed = false
    }

    private fun zip(file: File, vararg entries: Pair<String, String>) =
        ZipOutputStream(file.outputStream()).use { out -> for ((name, text) in entries) { out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry() } }

    @Test fun aFileIsKnownByTheHashRommKeepsOfIt() {
        val plain = tmp.newFile("Game.gba").apply { writeText("rom bytes") }
        val sha1 = MessageDigest.getInstance("SHA-1").digest("rom bytes".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(sha1, RommAdopt.hash(plain))
        val one = tmp.newFile("Game.zip").also { zip(it, "Game.gba" to "rom bytes") }
        val crc = "%08x".format(CRC32().apply { update("rom bytes".toByteArray()) }.value)
        assertEquals(crc, RommAdopt.hash(one))
        assertNull(RommAdopt.hash(tmp.newFile("Two.zip").also { zip(it, "a.bin" to "a", "b.bin" to "b") }))
        assertNull(RommAdopt.hash(tmp.newFile("Game.7z").apply { writeText("x") }))
        assertNull(RommAdopt.hash(tmp.newFile("Empty.gba")))
    }

    @Test fun aRomTheServerHoldsIsRecordedAndOneItDoesNotIsNotAskedAboutAgain() {
        val known = tmp.newFile("Known.gba").apply { writeText("known") }
        val other = tmp.newFile("Other.gba").apply { writeText("other") }
        val knownSha = RommAdopt.hash(known)!!
        RommFakeServer { r ->
            if (r.path.endsWith("&search_term=$knownSha")) 200 to """{"items":[{"id":7,"name":"Known Quest","fs_name":"Known Quest (USA).gba","platform_slug":"gba","platform_id":3}],"total":1}"""
            else 200 to """{"items":[],"total":0}"""
        }.use { server ->
            StoreAccounts.write(app, Store.ROMM, JSONObject().put("server", server.address).put("access_token", token).put("version", "5.4.1"))
            val roms = listOf(Library.Rom("Known", known, "/root/ROMs/gba/Known.gba", "retroarch"), Library.Rom("Other", other, "/root/ROMs/gba/sub/Other.gba", "retroarch"))
            assertEquals(1, RommAdopt.match(app, roms))
            val entry = RommInstalls.find(app, "7")!!
            assertEquals(listOf("gba/Known.gba", "gba/Known.gba", "retroarch", "Known Quest"), listOf(entry.path, entry.root, entry.emulatorId, entry.item!!.title))
            assertEquals(2, server.seen.count { it.path.startsWith("/api/roms?limit=2&") && "order_by" !in it.path })
            assertTrue(RommPrefs.get(app).getStringSet("adopt_missed", emptySet())!!.single().startsWith(other.path + "|"))
        }
    }

    @Test fun aServerThatDoesNotAnswerLeavesTheFilesToBeAskedAboutLater() {
        val file = tmp.newFile("Game.gba").apply { writeText("rom") }
        RommFakeServer { 503 to "{}" }.use { server ->
            StoreAccounts.write(app, Store.ROMM, JSONObject().put("server", server.address).put("access_token", token).put("version", "5.4.1"))
            assertEquals(0, RommAdopt.match(app, listOf(Library.Rom("Game", file, "/root/ROMs/gba/Game.gba", "retroarch"), Library.Rom("Game", file, "/root/ROMs/gba/Game2.gba", "retroarch"))))
            assertEquals(1, server.seen.size)
            assertTrue(RommPrefs.get(app).getStringSet("adopt_missed", emptySet())!!.isEmpty())
        }
    }
}
