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
    /** [cut] makes the first answer that carries a file stop after that many bytes, as a dropped connection does. */
    private fun serve(files: Map<String, Pair<String, ByteArray>>, listed: Map<String, Pair<String, ByteArray>> = files, cut: Int = 0) {
        var dropping = cut
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
                    val file = files[path.substringAfter("file_ids=", "").ifEmpty { path.removePrefix("/api/roms/").substringBefore('/') }]?.second
                    val (status, body) = when {
                        path == "/api/roms/5" -> "200 OK" to details(listed).toByteArray()
                        "/content/" !in path || file == null -> "404 Not Found" to "{}".toByteArray()
                        from != null -> "206 Partial Content" to file.copyOfRange(from, file.size)
                        else -> "200 OK" to file
                    }
                    val sent = if (dropping > 0 && "/content/" in path && file != null) dropping.also { dropping = 0 } else body.size
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body, 0, sent)
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
        RommInstalls.claim(app, "5", "psx/$folder")
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

    @Test fun aFileThatCameWholeBeforeTheInstallStoppedIsNotAskedForAgain() {
        serve(twoDiscs)
        val dir = File(roms, "psx/$folder").apply { mkdirs() }
        RommInstalls.claim(app, "5", "psx/$folder")
        File(dir, "$folder (Disc 1).chd").writeBytes(disc1)
        File(dir, "$folder (Disc 2).chd.part").writeBytes(disc2)
        install()
        assertArrayEquals(disc2, File(dir, "$folder (Disc 2).chd").readBytes())
        assertEquals(listOf("/api/roms/5 Bearer $token"), seen.toList())
    }

    @Test fun discsHeldAsSeparateRomsAreInstalledAsOneGame() {
        serve(mapOf("21" to ("$folder (Disc 1).chd" to disc1), "22" to ("$folder (Disc 2).chd" to disc2)))
        fun disc(id: String, n: Int, size: Int) = CatalogItem(
            Store.ROMM, id, "Game", null, owned = true, sizeBytes = size.toLong(),
            extra = mapOf(RommCatalog.PLATFORM to "psx", RommCatalog.FILE to "$folder (Disc $n).chd", RommCatalog.SINGLE to "1"),
        )
        val set = RommCatalog.discSet(folder, listOf(disc("21", 1, disc1.size), disc("22", 2, disc2.size)))!!
        assertEquals(8000L, set.sizeBytes)
        val dir = File(roms, "psx/$folder").apply { mkdirs() }
        RommInstalls.claim(app, "21", "psx/$folder")
        File(dir, "$folder (Disc 1).chd").writeBytes(disc1)
        assertEquals(File(dir, "$folder.m3u").path, RommInstaller(app, set).run(DownloadQueue.JobHandle("romm:21", AtomicBoolean(false))))
        assertArrayEquals(disc2, File(dir, "$folder (Disc 2).chd").readBytes())
        assertEquals("$folder (Disc 1).chd\n$folder (Disc 2).chd\n", File(dir, "$folder.m3u").readText())
        assertEquals(listOf("/api/roms/22/content/Game%20%28USA%29%20%28Disc%202%29.chd Bearer $token"), seen.toList())
        val record = RommInstalls.parse(File(app.filesDir, "stores/romm/installed.json").readText()).getValue("21")
        assertEquals("psx/$folder/$folder.m3u", record.path)
        assertEquals("psx/$folder", record.root)
        assertEquals(2, RommCatalog.discs(record.item!!).size)
    }

    @Test fun aFolderOfTheUsersOwnIsNeverInstalledInto() {
        serve(twoDiscs)
        val dir = File(roms, "psx/$folder").apply { mkdirs() }
        File(dir, "mine.bin").writeBytes(disc1)
        assertTrue(runCatching { install() }.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf("mine.bin"), dir.list()!!.toList())
        assertFalse(File(app.filesDir, "stores/romm/installed.json").let { it.isFile && "5" in RommInstalls.parse(it.readText()) })
    }

    @Test fun aFileOfTheUsersOwnIsNeverReplaced() {
        serve(mapOf("5" to ("$folder.chd" to disc1)))
        val mine = File(roms, "psx/$folder.chd").apply { parentFile!!.mkdirs(); writeBytes(disc2) }
        val item = oneFile("$folder.chd", sha1(disc1))
        assertTrue(runCatching { RommInstaller(app, item).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))) }.exceptionOrNull() is IllegalStateException)
        assertArrayEquals(disc2, mine.readBytes())
        assertTrue(seen.isEmpty())
    }

    @Test fun anInstalledGameIsInstalledAgainOverItself() {
        serve(twoDiscs)
        val dir = File(roms, "psx/$folder")
        install()
        File(dir, "$folder (Disc 2).chd").delete()
        assertEquals(File(dir, "$folder.m3u").path, install())
        assertArrayEquals(disc2, File(dir, "$folder (Disc 2).chd").readBytes())
    }

    @Test fun removingAFolderGameDeletesItsFilesAndNothingPutBesideThem() {
        serve(twoDiscs)
        val dir = File(roms, "psx/$folder")
        install()
        File(dir, "mine.sav").writeBytes(disc1)
        RommInstalls.remove(app, "5")
        assertEquals(listOf("mine.sav"), dir.list()!!.toList())
        assertFalse(File(app.filesDir, "stores/romm/installed.json").let { it.isFile && "5" in RommInstalls.parse(it.readText()) })
    }

    @Test fun removingAFolderGameTakesItsFolderWhenNothingElseIsInIt() {
        serve(twoDiscs)
        install()
        RommInstalls.remove(app, "5")
        assertFalse(File(roms, "psx/$folder").exists())
        assertTrue(File(roms, "psx").isDirectory)
    }

    private fun oneFile(name: String, sha1: String) = CatalogItem(
        Store.ROMM, "5", "Game", null, owned = true, sizeBytes = disc1.size.toLong(),
        extra = mapOf(RommCatalog.PLATFORM to "psx", RommCatalog.FILE to name, RommCatalog.SINGLE to "1", RommCatalog.SHA1 to sha1),
    )

    private fun sha1(bytes: ByteArray) = com.droiddeck.launcher.core.Hashes.hex(java.security.MessageDigest.getInstance("SHA-1").digest(bytes))

    @Test fun aFileThatMatchesTheServersRecordIsInstalled() {
        serve(mapOf("5" to ("$folder.chd" to disc1)))
        val item = oneFile("$folder.chd", sha1(disc1).uppercase())
        assertEquals(File(roms, "psx/$folder.chd").path, RommInstaller(app, item).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))))
        assertArrayEquals(disc1, File(roms, "psx/$folder.chd").readBytes())
    }

    @Test fun aFileThatCarriedOnFromAPartIsCheckedAsAWhole() {
        serve(mapOf("5" to ("$folder.chd" to disc1)))
        val part = File(roms, "psx/.romm-5.part").apply { parentFile!!.mkdirs() }
        part.writeBytes(disc1.copyOfRange(0, 1200))
        val item = oneFile("$folder.chd", sha1(disc1))
        assertEquals(File(roms, "psx/$folder.chd").path, RommInstaller(app, item).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))))
        assertArrayEquals(disc1, File(roms, "psx/$folder.chd").readBytes())
        assertTrue(seen.last().contains(" from 1200 "))

        File(roms, "psx/$folder.chd").delete()
        RommInstalls.remove(app, "5")
        part.writeBytes(ByteArray(1200) { 7 })
        assertTrue(runCatching { RommInstaller(app, item).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))) }.exceptionOrNull() is IllegalStateException)
        assertFalse(File(roms, "psx/$folder.chd").exists())
    }

    @Test fun aDownloadThatDropsCarriesOnFromWhatCame() {
        RommInstaller.retryMs = 1
        try {
            serve(mapOf("5" to ("$folder.chd" to disc2)), cut = 1700)
            val item = oneFile("$folder.chd", sha1(disc2)).copy(sizeBytes = disc2.size.toLong())
            assertEquals(File(roms, "psx/$folder.chd").path, RommInstaller(app, item).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))))
            assertArrayEquals(disc2, File(roms, "psx/$folder.chd").readBytes())
            assertEquals(2, seen.size)
            assertTrue(seen.last(), seen.last().contains(" from 1700 "))
        } finally {
            RommInstaller.retryMs = 2_000
        }
    }

    @Test fun aFileThatIsNotTheOneOnRecordIsNotInstalled() {
        serve(mapOf("5" to ("$folder.chd" to disc1)))
        val item = oneFile("$folder.chd", sha1(disc2))
        val error = runCatching { RommInstaller(app, item).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(emptyList<String>(), File(roms, "psx").list().orEmpty().toList())
        assertFalse(File(app.filesDir, "stores/romm/installed.json").let { it.isFile && "5" in RommInstalls.parse(it.readText()) })
    }

    private fun zipped(name: String, data: ByteArray): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        java.util.zip.ZipOutputStream(out).use { zip -> zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(data); zip.closeEntry() }
    }.toByteArray()

    @Test fun anArchiveTheEmulatorDoesNotOpenIsUnpacked() {
        serve(mapOf("5" to ("$folder.zip" to zipped("$folder.chd", disc1))))
        assertEquals(File(roms, "psx/$folder.chd").path, RommInstaller(app, oneFile("$folder.zip", "")).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))))
        assertArrayEquals(disc1, File(roms, "psx/$folder.chd").readBytes())
        assertEquals(listOf("$folder.chd"), File(roms, "psx").list()!!.toList())
    }

    @Test fun anArchiveThatUnpacksToMoreThanItListsIsNotInstalled() {
        val zip = zipped("$folder.chd", ByteArray(200_000))
        // The size the archive's own list gives for its one file, made smaller than what is packed.
        val listed = (zip.size - 4 downTo 0).first { zip[it] == 0x50.toByte() && zip[it + 1] == 0x4B.toByte() && zip[it + 2] == 1.toByte() && zip[it + 3] == 2.toByte() } + 24
        java.nio.ByteBuffer.wrap(zip, listed, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(100)
        serve(mapOf("5" to ("$folder.zip" to zip)))
        val error = runCatching { RommInstaller(app, oneFile("$folder.zip", "")).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))) }.exceptionOrNull()
        assertTrue(error.toString(), error != null)
        assertFalse(File(roms, "psx/$folder.chd").exists())
        assertFalse(File(app.filesDir, "stores/romm/installed.json").let { it.isFile && "5" in RommInstalls.parse(it.readText()) })
    }

    @Test fun aLinkInAnUnpackedArchiveIsFoundAndNeverFollowed() {
        val work = tmp.newFolder("work")
        val outside = tmp.newFolder("outside")
        File(outside, "mine.txt").writeText("mine")
        File(work, "game.chd").writeBytes(disc1)
        assertEquals(listOf("game.chd"), RommArchive.plainFiles(work)!!.map { it.name })
        org.junit.Assume.assumeTrue(runCatching { java.nio.file.Files.createSymbolicLink(File(work, "link").toPath(), outside.toPath()) }.isSuccess)
        assertEquals(null, RommArchive.plainFiles(work))
        RommArchive.remove(work)
        assertFalse(work.exists())
        assertEquals("mine", File(outside, "mine.txt").readText())
    }

    private fun record(id: String = "5") = RommInstalls.parse(File(app.filesDir, "stores/romm/installed.json").readText()).getValue(id)

    @Test fun aGameNothingPlaysIsKeptAsTheServerSentIt() {
        serve(mapOf("5" to ("$folder.nsp" to disc1)))
        val item = oneFile("$folder.nsp", sha1(disc1)).let { it.copy(extra = it.extra + (RommCatalog.PLATFORM to "switch")) }
        assertEquals(File(roms, "switch/$folder.nsp").path, RommInstaller(app, item).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))))
        assertArrayEquals(disc1, File(roms, "switch/$folder.nsp").readBytes())
        assertEquals("switch/$folder.nsp", record().path)
        assertEquals("", record().emulatorId)
    }

    @Test fun aSystemFolderThatLeadsOutOfTheRomsFolderIsRefusedBeforeAnythingIsAsked() {
        serve(mapOf("5" to ("$folder.nsp" to disc1)))
        val item = oneFile("$folder.nsp", sha1(disc1)).let { it.copy(extra = it.extra + (RommCatalog.PLATFORM to "../outside")) }
        assertTrue(runCatching { RommInstaller(app, item).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))) }.exceptionOrNull() is IllegalStateException)
        assertFalse(File(roms.parentFile, "outside").exists())
        assertTrue(seen.isEmpty())
    }

    @Test fun anArchiveNothingPlaysIsNotUnpacked() {
        serve(mapOf("5" to ("$folder.zip" to disc1)))
        val item = oneFile("$folder.zip", "").let { it.copy(extra = it.extra + (RommCatalog.PLATFORM to "switch")) }
        assertEquals(File(roms, "switch/$folder.zip").path, RommInstaller(app, item).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))))
        assertEquals(listOf("$folder.zip"), File(roms, "switch").list()!!.toList())
    }

    @Test fun aFileTheEmulatorHereCannotOpenIsKeptToo() {
        serve(mapOf("5" to ("$folder.xyz" to disc1)))
        assertEquals(File(roms, "psx/$folder.xyz").path, RommInstaller(app, oneFile("$folder.xyz", "")).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))))
        assertEquals("", record().emulatorId)
    }

    @Test fun aKeptFileThatIsNotTheOneOnRecordIsNotInstalled() {
        serve(mapOf("5" to ("$folder.nsp" to disc1)))
        val item = oneFile("$folder.nsp", sha1(disc2)).let { it.copy(extra = it.extra + (RommCatalog.PLATFORM to "switch")) }
        assertTrue(runCatching { RommInstaller(app, item).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))) }.exceptionOrNull() is IllegalStateException)
        assertEquals(emptyList<String>(), File(roms, "switch").list().orEmpty().toList())
    }

    @Test fun aFolderGameNothingPlaysIsKeptAsItsFolder() {
        serve(twoDiscs)
        val item = game.copy(extra = game.extra + (RommCatalog.PLATFORM to "switch"))
        val dir = File(roms, "switch/$folder")
        assertEquals(dir.path, RommInstaller(app, item).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))))
        assertEquals(listOf("$folder (Disc 1).chd", "$folder (Disc 2).chd"), dir.list()!!.sorted())
        assertEquals("switch/$folder", record().path)
        assertEquals("switch/$folder", record().root)
        assertEquals("", record().emulatorId)
    }

    @Test fun aFileRommHashesFromTheInsideIsNotCheckedAsAWhole() {
        val zipLike = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(40)
        val plain = File(roms, "a.bin").apply { writeBytes(disc1) }
        val disguised = File(roms, "b.bin").apply { writeBytes(zipLike) }
        assertTrue(RommInstaller.hashedWhole(plain, "bin"))
        assertFalse(RommInstaller.hashedWhole(plain, "ZIP"))
        assertFalse(RommInstaller.hashedWhole(disguised, "bin"))
    }

    @Test fun aFileTheServerDoesNotHaveFailsTheInstallAndKeepsTheRest() {
        serve(twoDiscs, listed = twoDiscs + ("13" to ("$folder (Disc 3).chd" to ByteArray(10))))
        val error = runCatching { install() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertArrayEquals(disc1, File(roms, "psx/$folder/$folder (Disc 1).chd").readBytes())
        assertFalse(File(app.filesDir, "stores/romm/installed.json").let { it.isFile && "5" in RommInstalls.parse(it.readText()) })
    }
}
