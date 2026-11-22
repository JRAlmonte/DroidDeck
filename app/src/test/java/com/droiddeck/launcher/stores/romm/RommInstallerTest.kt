package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.CredentialCipher
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import com.droiddeck.launcher.stores.download.DownloadQueue
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class RommInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val token = "rmm_" + "0123456789abcdef".repeat(4)
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val seen = java.util.Collections.synchronizedList(ArrayList<String>())
    private val folder = "Game (USA)"
    private val disc1 = ByteArray(3000) { (it % 251).toByte() }
    private val disc2 = ByteArray(5000) { (it % 241).toByte() }
    private lateinit var roms: File
    private lateinit var server: ServerSocket

    @Before fun setUp() {
        StoreAccounts.keys = CredentialCipher.KeyProvider { key }
        StoreAccounts.keystoreFailed = false
        roms = tmp.newFolder("ROMs")
        SessionPrefs.setRomsDir(app, roms.path)
    }

    @After fun tearDown() {
        if (::server.isInitialized) server.close()
        StoreAccounts.clear(app, Store.ROMM)
        StoreAccounts.keys = CredentialCipher.Keystore
        StoreAccounts.keystoreFailed = false
    }

    private fun details(files: Map<String, Pair<String, ByteArray>>) = JSONObject().put("id", 5).put("fs_path", "roms/psx").put("fs_name", folder)
        .put("files", JSONArray(files.map { (id, file) ->
            JSONObject().put("id", id.toInt()).put("file_name", file.first).put("file_path", "roms/psx/$folder")
                .put("full_path", "roms/psx/$folder/${file.first}").put("file_size_bytes", file.second.size)
        })).toString()

    /** Answers like RomM: the ROM's details, and one file of it by `file_ids`, from the offset a `Range` header asks for. */
    private fun serve(files: Map<String, Pair<String, ByteArray>>, listed: Map<String, Pair<String, ByteArray>> = files) {
        server = ServerSocket(0)
        Thread {
            while (!server.isClosed) runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(input.read().takeIf { it >= 0 }?.toChar() ?: break)
                    val path = head.lineSequence().first().split(' ').getOrElse(1) { "" }
                    val from = Regex("(?i)range: *bytes=(\\d+)-").find(head)?.groupValues?.get(1)?.toInt()
                    val auth = Regex("(?i)authorization: *(.*)").find(head)?.groupValues?.get(1)?.trim() ?: "-"
                    seen.add(path + (from?.let { " from $it" } ?: "") + " " + auth)
                    val file = files[path.substringAfter("file_ids=", "")]?.second
                    val (status, body) = when {
                        path == "/api/roms/5" -> "200 OK" to details(listed).toByteArray()
                        !path.startsWith("/api/roms/5/content/") || file == null -> "404 Not Found" to "{}".toByteArray()
                        from != null -> "206 Partial Content" to file.copyOfRange(from, file.size)
                        else -> "200 OK" to file
                    }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        StoreAccounts.write(app, Store.ROMM, JSONObject().put("server", "http://127.0.0.1:${server.localPort}").put("access_token", token))
    }

    private val game = CatalogItem(
        Store.ROMM, "5", "Game", null, owned = true,
        extra = mapOf(RommCatalog.PLATFORM to "psx", RommCatalog.FILE to folder, RommCatalog.SINGLE to "0"),
    )

    private fun install(): String? = RommInstaller(app, game).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false)))

    private val twoDiscs = mapOf("11" to ("$folder (Disc 1).chd" to disc1), "12" to ("$folder (Disc 2).chd" to disc2))

    @Test fun aGameHeldAsAFolderIsDownloadedFileByFile() {
        serve(twoDiscs)
        val dir = File(roms, "psx/$folder")
        assertEquals(File(dir, "$folder.m3u").path, install())
        assertArrayEquals(disc1, File(dir, "$folder (Disc 1).chd").readBytes())
        assertArrayEquals(disc2, File(dir, "$folder (Disc 2).chd").readBytes())
        assertEquals("$folder (Disc 1).chd\n$folder (Disc 2).chd\n", File(dir, "$folder.m3u").readText())
        assertEquals(listOf("$folder (Disc 1).chd", "$folder (Disc 2).chd", "$folder.m3u"), dir.list()!!.sorted())
        assertEquals(
            listOf(
                "/api/roms/5 Bearer $token",
                "/api/roms/5/content/Game%20%28USA%29%20%28Disc%201%29.chd?file_ids=11 Bearer $token",
                "/api/roms/5/content/Game%20%28USA%29%20%28Disc%202%29.chd?file_ids=12 Bearer $token",
            ),
            seen.toList(),
        )
        val record = RommInstalls.parse(File(app.filesDir, "stores/romm/installed.json").readText()).getValue("5")
        assertEquals("psx/$folder/$folder.m3u", record.path)
        assertEquals("psx/$folder", record.root)
        assertEquals("duckstation", record.emulatorId)
    }

    @Test fun aStoppedInstallCarriesOnWhereItWas() {
        serve(twoDiscs)
        val dir = File(roms, "psx/$folder").apply { mkdirs() }
        File(dir, "$folder (Disc 1).chd").writeBytes(disc1)
        File(dir, "$folder (Disc 2).chd.part").writeBytes(disc2.copyOfRange(0, 1200))
        install()
        assertArrayEquals(disc2, File(dir, "$folder (Disc 2).chd").readBytes())
        assertFalse(File(dir, "$folder (Disc 2).chd.part").exists())
        assertEquals(
            listOf("/api/roms/5 Bearer $token", "/api/roms/5/content/Game%20%28USA%29%20%28Disc%202%29.chd?file_ids=12 from 1200 Bearer $token"),
            seen.toList(),
        )
    }

    @Test fun aFileTheServerDoesNotHaveFailsTheInstallAndKeepsTheRest() {
        serve(twoDiscs, listed = twoDiscs + ("13" to ("$folder (Disc 3).chd" to ByteArray(10))))
        val error = runCatching { install() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertArrayEquals(disc1, File(roms, "psx/$folder/$folder (Disc 1).chd").readBytes())
        assertFalse(File(app.filesDir, "stores/romm/installed.json").let { it.isFile && "5" in RommInstalls.parse(it.readText()) })
    }
}
