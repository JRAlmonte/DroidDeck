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
        RommDetails.clear(app)
        StoreAccounts.clear(app, Store.ROMM)
        StoreAccounts.keys = CredentialCipher.Keystore
        StoreAccounts.keystoreFailed = false
    }

    private fun details(files: Map<String, Pair<String, ByteArray>>, hashes: Boolean = false) = JSONObject().put("id", 5).put("fs_path", "roms/psx").put("fs_name", folder)
        .put("files", JSONArray(files.map { (id, file) ->
            JSONObject().put("id", id.toInt()).put("file_name", file.first).put("file_path", "roms/psx/$folder")
                .put("full_path", "roms/psx/$folder/${file.first}").put("file_size_bytes", file.second.size)
                .apply { if (hashes) put("sha1_hash", sha1(file.second)) }
        })).toString()

    /** What RomM answers [r] with: the ROM's details, or one file of it by `file_ids`, from the offset a `Range` header asks for. */
    private fun romm(r: RommFakeServer.Request, files: Map<String, Pair<String, ByteArray>>, sent: Map<String, Pair<String, ByteArray>> = files, hashes: Boolean = false): Pair<Int, String> {
        val file = sent[r.path.substringAfter("file_ids=", "")]?.second
        val from = Regex("bytes=(\\d+)-").find(r.range)?.groupValues?.get(1)?.toInt()
        return when {
            r.path == "/api/roms/5" -> 200 to details(files, hashes)
            "/content/" !in r.path || file == null -> 404 to "{}"
            from != null -> 206 to String(file, from, file.size - from, Charsets.ISO_8859_1)
            else -> 200 to String(file, Charsets.ISO_8859_1)
        }
    }

    private fun signIn(server: RommFakeServer) =
        StoreAccounts.write(app, Store.ROMM, JSONObject().put("server", server.address).put("access_token", token).put("version", "5.4.1"))

    private val eightDiscs = (1..8).associate { n -> "${10 + n}" to ("$folder (Disc $n).chd" to ByteArray(3000 + 100 * n) { (it * n % 251).toByte() }) }

    private fun asked(server: RommFakeServer) = server.seen.toList().filter { "/content/" in it.path }.map { it.path.substringAfter("file_ids=") }

    private fun recorded() = File(app.filesDir, "stores/romm/installed.json").let { it.isFile && "5" in RommInstalls.parse(it.readText()) }

    private fun assertInstalled(files: Map<String, Pair<String, ByteArray>>) {
        val dir = File(roms, "psx/$folder")
        for ((name, bytes) in files.values) assertArrayEquals(name, bytes, File(dir, name).readBytes())
        assertEquals((files.values.map { it.first } + "$folder.m3u").sorted(), dir.list()!!.sorted())
        assertEquals(files.values.joinToString("\n", postfix = "\n") { it.first }, File(dir, "$folder.m3u").readText())
        assertEquals("psx/$folder/$folder.m3u", record().path)
    }

    @Test fun theFilesOfAFolderGameComeThreeAtATime() {
        val together = java.util.concurrent.atomic.AtomicInteger()
        val most = java.util.concurrent.atomic.AtomicInteger()
        val three = java.util.concurrent.CountDownLatch(RommInstaller.AT_ONCE)
        RommFakeServer(8) { r ->
            if ("/content/" !in r.path) return@RommFakeServer romm(r, eightDiscs, hashes = true)
            most.accumulateAndGet(together.incrementAndGet(), ::maxOf)
            three.countDown()
            three.await(5, java.util.concurrent.TimeUnit.SECONDS)
            Thread.sleep(20)
            romm(r, eightDiscs).also { together.decrementAndGet() }
        }.use { server ->
            signIn(server)
            assertEquals(File(roms, "psx/$folder/$folder.m3u").path, install())
            assertEquals(0L, three.count)
            assertEquals(RommInstaller.AT_ONCE, most.get())
            assertEquals("GET /api/roms/5", server.paths().first())
            assertEquals(eightDiscs.keys.sorted(), asked(server).sorted())
            assertTrue(server.seen.all { it.authorization == "Bearer $token" })
            assertInstalled(eightDiscs)
        }
    }

    @Test fun oneFileThatFailsAmongSeveralLeavesNoRecordAndTheNextInstallCarriesOn() {
        var missing = true
        RommFakeServer(8) { r ->
            if ("/content/" in r.path) Thread.sleep(15)
            romm(r, eightDiscs, sent = if (missing) eightDiscs - "15" else eightDiscs, hashes = true)
        }.use { server ->
            signIn(server)
            val error = runCatching { install() }.exceptionOrNull()
            assertTrue(error.toString(), error is IllegalStateException)
            assertFalse(recorded())
            val dir = File(roms, "psx/$folder")
            assertFalse(File(dir, "$folder.m3u").exists())
            assertFalse(File(dir, "$folder (Disc 5).chd").exists())
            val whole = eightDiscs.filterValues { (name, bytes) -> File(dir, name).let { it.isFile && it.readBytes().contentEquals(bytes) } }.keys
            for ((name, bytes) in eightDiscs.values) File(dir, "$name.part").takeIf { it.isFile }?.let { part ->
                assertArrayEquals(name, bytes.copyOfRange(0, part.length().toInt()), part.readBytes())
            }

            missing = false
            server.seen.clear()
            assertEquals(File(dir, "$folder.m3u").path, install())
            assertTrue(asked(server).none { it in whole })
            assertTrue("15" in asked(server))
            assertInstalled(eightDiscs)
        }
    }

    @Test fun oneFileAmongSeveralThatIsNotTheOneOnRecordIsNotInstalled() {
        var wrong = true
        val other = eightDiscs + ("13" to (eightDiscs.getValue("13").first to ByteArray(eightDiscs.getValue("13").second.size) { 9 }))
        RommFakeServer(8) { r -> if ("/content/" in r.path) romm(r, eightDiscs, sent = if (wrong) other else eightDiscs) else romm(r, eightDiscs, hashes = true) }.use { server ->
            signIn(server)
            assertTrue(runCatching { install() }.exceptionOrNull() is IllegalStateException)
            assertFalse(recorded())
            val dir = File(roms, "psx/$folder")
            assertFalse(File(dir, "$folder (Disc 3).chd").exists())
            assertFalse(File(dir, "$folder (Disc 3).chd.part").exists())

            wrong = false
            assertEquals(File(dir, "$folder.m3u").path, install())
            assertInstalled(eightDiscs)
        }
    }

    @Test fun severalStoppedFilesCarryOnFromTheirPartsAndAreCheckedAsAWhole() {
        RommFakeServer(8) { r -> romm(r, eightDiscs, hashes = true) }.use { server ->
            signIn(server)
            val dir = File(roms, "psx/$folder").apply { mkdirs() }
            RommInstalls.claim(app, "5", "psx/$folder")
            File(dir, "$folder (Disc 1).chd").writeBytes(eightDiscs.getValue("11").second)
            for (id in listOf("12", "13", "14")) eightDiscs.getValue(id).let { (name, bytes) -> File(dir, "$name.part").writeBytes(bytes.copyOfRange(0, 1200)) }
            assertEquals(File(dir, "$folder.m3u").path, install())
            val ranges = server.seen.toList().filter { "/content/" in it.path }.associate { it.path.substringAfter("file_ids=") to it.range }
            assertEquals((12..18).map { it.toString() }.toSet(), ranges.keys)
            assertEquals(setOf("12", "13", "14"), ranges.filterValues { it == "bytes=1200-" }.keys)
            assertTrue(ranges.filterKeys { it !in setOf("12", "13", "14") }.values.all { it.isEmpty() })
            assertInstalled(eightDiscs)

            File(dir, "$folder (Disc 2).chd").delete()
            File(dir, "$folder (Disc 2).chd.part").writeBytes(ByteArray(1200) { 7 })
            assertTrue(runCatching { install() }.exceptionOrNull() is IllegalStateException)
            assertFalse(File(dir, "$folder (Disc 2).chd").exists())
        }
    }

    @Test fun cancellingWhileSeveralFilesComeStopsThemAndTakesTheFolderThatWasMadeForThem() {
        val asked = java.util.concurrent.CountDownLatch(RommInstaller.AT_ONCE)
        val go = java.util.concurrent.CountDownLatch(1)
        RommFakeServer(8) { r ->
            if ("/content/" in r.path) { asked.countDown(); go.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            romm(r, eightDiscs)
        }.use { server ->
            signIn(server)
            val installer = RommInstaller(app, game)
            val result = java.util.concurrent.atomic.AtomicReference<Result<String?>>()
            val running = Thread { result.set(runCatching { installer.run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))) }) }.apply { start() }
            assertTrue(asked.await(5, java.util.concurrent.TimeUnit.SECONDS))
            installer.cancel(deleteFiles = true)
            go.countDown()
            running.join(10_000)
            assertEquals(Result.success<String?>(null), result.get())
            assertEquals(RommInstaller.AT_ONCE, asked(server).size)
            assertFalse(File(roms, "psx/$folder").exists())
            assertFalse(recorded())
        }
    }

    @Test fun clearingAFolderInstallThatFailedTakesItsFolderSoTheNextInstallIsNotTurnedAway() {
        var missing = true
        RommFakeServer(8) { r -> romm(r, eightDiscs, sent = if (missing) eightDiscs - "15" else eightDiscs, hashes = true) }.use { server ->
            signIn(server)
            val dir = File(roms, "psx/$folder")
            assertTrue(runCatching { install() }.exceptionOrNull() is IllegalStateException)
            assertTrue(dir.isDirectory)

            RommInstaller(app, game).cancel(deleteFiles = true)

            assertFalse("what the failed install brought is gone", dir.exists())
            assertTrue(File(roms, "psx").isDirectory)
            missing = false
            assertEquals(File(dir, "$folder.m3u").path, install())
            assertInstalled(eightDiscs)

            RommInstaller(app, game).cancel(deleteFiles = true)
            assertInstalled(eightDiscs)
        }
    }

    @Test fun clearingAnInstallThatWasTurnedAwayLeavesTheFolderThatWasInItsWay() {
        RommFakeServer(8) { r -> romm(r, eightDiscs) }.use { server ->
            signIn(server)
            val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            looper.idle()
            RommInstalls.clear(app)
            looper.idle()
            val mine = File(roms, "psx/$folder/mine.bin").apply { parentFile!!.mkdirs(); writeBytes(disc1) }
            assertTrue(runCatching { install() }.exceptionOrNull() is IllegalStateException)
            RommInstaller(app, game).cancel(deleteFiles = true)
            assertArrayEquals(disc1, mine.readBytes())
        }
    }

    @Test fun theFilesReadWithAGamesPageAreNotAskedForAgainByTheInstallStartedFromIt() {
        RommFakeServer(8) { r -> romm(r, twoDiscs) }.use { server ->
            signIn(server)
            assertEquals("5", RommDetails.fetch(app, "5")?.id)
            assertEquals(1, server.paths().count { it == "GET /api/roms/5" })
            server.seen.clear()
            assertEquals(File(roms, "psx/$folder/$folder.m3u").path, install())
            assertEquals(listOf("11", "12"), asked(server).sorted())
            assertEquals(0, server.paths().count { it == "GET /api/roms/5" })
            assertInstalled(twoDiscs)

            File(roms, "psx/$folder/$folder (Disc 2).chd").delete()
            server.seen.clear()
            install()
            assertEquals(listOf("GET /api/roms/5"), server.paths().take(1))
            assertEquals(listOf("12"), asked(server))

            RommDetails.fetch(app, "5")
            assertEquals(null, RommDetails.takeFiles("5", nowMs = android.os.SystemClock.elapsedRealtime() + 11 * 60_000L))
            assertEquals(null, RommDetails.takeFiles("5"))
            RommDetails.fetch(app, "5")
            assertEquals(2, RommDetails.takeFiles("5")?.own?.size)
        }
    }


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

    private val hack = ByteArray(1500) { (it % 233).toByte() }
    private val pack = ByteArray(2100) { (it % 229).toByte() }
    private val patch = ByteArray(900) { (it % 227).toByte() }

    /** A game of one file that RomM keeps a hack, a DLC pack and an update with; the hack is of the game's own type and sorts before it. */
    private val withExtras = mapOf(
        "11" to Triple("$folder.chd", "game", disc1), "21" to Triple("hack/A Hack.chd", "hack", hack),
        "22" to Triple("dlc/Pack.bin", "dlc", pack), "23" to Triple("$folder [UPD].bin", "update", patch),
    )

    private fun rommWithExtras(r: RommFakeServer.Request, wrong: String? = null): Pair<Int, String> {
        val file = withExtras[r.path.substringAfter("file_ids=", "")]
        val from = Regex("bytes=(\\d+)-").find(r.range)?.groupValues?.get(1)?.toInt()
        return when {
            r.path == "/api/roms/5" -> 200 to JSONObject().put("id", 5).put("fs_path", "roms/psx").put("fs_name", folder).put("files", JSONArray(withExtras.map { (id, f) ->
                val (path, category, bytes) = f
                val dir = listOf("roms/psx/$folder", path.substringBeforeLast('/', "")).filter { it.isNotEmpty() }.joinToString("/")
                JSONObject().put("id", id.toInt()).put("file_name", path.substringAfterLast('/')).put("file_path", dir).put("full_path", "roms/psx/$folder/$path")
                    .put("file_size_bytes", bytes.size).put("category", category).put("sha1_hash", sha1(if (id == wrong) disc2 else bytes))
            })).toString()
            "/content/" !in r.path || file == null -> 404 to "{}"
            from != null -> 206 to String(file.third, from, file.third.size - from, Charsets.ISO_8859_1)
            else -> 200 to String(file.third, Charsets.ISO_8859_1)
        }
    }

    private fun install(extras: Set<String>): String? =
        RommInstaller(app, RommCatalog.withExtras(game, extras)).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false)))

    @Test fun theExtraFilesChosenComeWithTheGameAndAreRemovedWithIt() {
        RommFakeServer(8) { r -> rommWithExtras(r) }.use { server ->
            signIn(server)
            val dir = File(roms, "psx/$folder")
            assertEquals(File(dir, "$folder.chd").path, install(setOf("21", "22")))
            assertEquals(listOf("11", "21", "22"), asked(server).sorted())
            assertTrue(server.seen.filter { "/content/" in it.path }.all { it.authorization == "Bearer $token" })
            assertArrayEquals(disc1, File(dir, "$folder.chd").readBytes())
            assertArrayEquals(hack, File(dir, "hack/A Hack.chd").readBytes())
            assertArrayEquals(pack, File(dir, "dlc/Pack.bin").readBytes())
            assertEquals("a file that was not chosen stays on the server", listOf("$folder.chd", "dlc", "hack"), dir.list()!!.sorted())
            assertEquals("the game's own file is what is opened, not the hack of the same type", "psx/$folder/$folder.chd", record().path)
            assertEquals("psx/$folder", record().root)
            assertEquals(setOf("21", "22"), RommCatalog.chosenExtras(record().item!!))

            File(dir, "dlc/mine.sav").writeBytes(disc2)
            RommInstalls.remove(app, "5")
            assertEquals(listOf("dlc"), dir.list()!!.toList())
            assertEquals(listOf("mine.sav"), File(dir, "dlc").list()!!.toList())
        }
    }

    @Test fun aGamesPageOffersTheExtraFilesAndItsInstallDoesNotAskForThemAgain() {
        RommFakeServer(8) { r -> rommWithExtras(r) }.use { server ->
            signIn(server)
            assertEquals("5", RommDetails.fetch(app, "5")?.id)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val offered = RommDetails.offered.getValue("5")
            assertEquals(listOf("hack/A Hack.chd", "dlc/Pack.bin", "$folder [UPD].bin"), offered.map { it.path })
            assertEquals(listOf("hack", "dlc", "update"), offered.map { it.category })
            assertEquals(listOf(hack.size.toLong(), pack.size.toLong(), patch.size.toLong()), offered.map { it.size })
            server.seen.clear()
            assertEquals(File(roms, "psx/$folder/$folder.chd").path, install(setOf("23")))
            assertEquals(0, server.paths().count { it == "GET /api/roms/5" })
            assertEquals(listOf("11", "23"), asked(server).sorted())
            assertArrayEquals(patch, File(roms, "psx/$folder/$folder [UPD].bin").readBytes())

            RommDetails.clear(app)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(RommDetails.offered.isEmpty())
        }
    }

    @Test fun withNoExtraFileChosenAGameOfOneFileIsInstalledAsThatFile() {
        RommFakeServer(8) { r -> rommWithExtras(r) }.use { server ->
            signIn(server)
            assertEquals(File(roms, "psx/$folder.chd").path, install(emptySet()))
            assertEquals(listOf("11"), asked(server))
            assertFalse(File(roms, "psx/$folder").exists())
            assertEquals("psx/$folder.chd", record().root)
        }
    }

    @Test fun anExtraFileThatIsNotTheOneOnRecordIsNotInstalled() {
        RommFakeServer(8) { r -> rommWithExtras(r, wrong = "22") }.use { server ->
            signIn(server)
            val failure = runCatching { install(setOf("22")) }.exceptionOrNull()
            assertTrue(failure.toString(), failure is IllegalStateException)
            assertFalse(recorded())
            assertFalse(File(roms, "psx/$folder/dlc/Pack.bin").exists())
        }
    }

    @Test fun anExtraFileTheServerNoLongerListsStopsTheInstallBeforeAnythingIsAskedFor() {
        RommFakeServer(8) { r -> rommWithExtras(r) }.use { server ->
            signIn(server)
            assertTrue(runCatching { install(setOf("22", "99")) }.exceptionOrNull() is IllegalStateException)
            assertTrue(asked(server).isEmpty())
            assertFalse(recorded())
            assertFalse(File(roms, "psx/$folder").exists())
        }
    }

    @Test fun anInstallTriedAgainWithoutItsChoiceKeepsTheExtraFilesThatCameAndDropsTheOnesCutShort() {
        RommFakeServer(8) { r -> rommWithExtras(r) }.use { server ->
            signIn(server)
            val dir = File(roms, "psx/$folder")
            File(dir, "dlc").mkdirs()
            RommInstalls.claim(app, "5", "psx/$folder")
            File(dir, "dlc/Pack.bin").writeBytes(pack)
            File(dir, "$folder [UPD].bin.part").writeBytes(patch.copyOfRange(0, 300))
            File(dir, "$folder.chd.part").writeBytes(disc1.copyOfRange(0, 1000))

            assertEquals(File(dir, "$folder.chd").path, install(emptySet()))

            assertEquals("the game carries on from its part and nothing else is asked for", listOf("11"), asked(server))
            assertEquals("bytes=1000-", server.seen.last { "/content/" in it.path }.range)
            assertArrayEquals(disc1, File(dir, "$folder.chd").readBytes())
            assertEquals(listOf("$folder.chd", "dlc"), dir.list()!!.sorted())
            RommInstalls.remove(app, "5")
            assertFalse("the extra file that came earlier goes with the game", dir.exists())
        }
    }

    private val listedWithExtras get() = RommCatalog.fileSet(JSONObject(rommWithExtras(RommFakeServer.Request("GET", "/api/roms/5", "", ByteArray(0))).second))

    private fun extra(id: String) = listedWithExtras.extras.first { it.id == id }

    private fun add(vararg ids: String): String? =
        RommInstaller(app, record().item!!, ids.map(::extra)).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false)))

    private fun has() = RommInstalls.extras(app, record(), listedWithExtras.own, listedWithExtras.extras)

    @Test fun anExtraFileIsAddedToAnInstalledGameAndGoesWithIt() {
        RommFakeServer(8) { r -> rommWithExtras(r) }.use { server ->
            signIn(server)
            val dir = File(roms, "psx/$folder")
            install(setOf("21"))
            assertEquals(setOf("21"), has())
            val before = record()
            server.seen.clear()

            assertEquals(dir.path, add("22", "23"))

            assertEquals("the game's own file is not asked for again", listOf("22", "23"), asked(server).sorted())
            assertTrue(server.seen.all { it.authorization == "Bearer $token" })
            assertArrayEquals(pack, File(dir, "dlc/Pack.bin").readBytes())
            assertArrayEquals(patch, File(dir, "$folder [UPD].bin").readBytes())
            assertArrayEquals(disc1, File(dir, "$folder.chd").readBytes())
            assertEquals(setOf("21", "22", "23"), has())
            assertEquals(setOf("21", "22", "23"), RommCatalog.chosenExtras(record().item!!))
            assertEquals(listOf(before.path, before.root, before.emulatorId), listOf(record().path, record().root, record().emulatorId))

            server.seen.clear()
            assertEquals(dir.path, add("22"))
            assertTrue("a file that is there whole is not asked for again", asked(server).isEmpty())

            RommInstalls.remove(app, "5")
            assertFalse("the files added later go with the game", dir.exists())
        }
    }

    @Test fun anExtraFileIsRemovedFromAnInstalledGameAndNothingElseIs() {
        RommFakeServer(8) { r -> rommWithExtras(r) }.use { server ->
            signIn(server)
            val dir = File(roms, "psx/$folder")
            install(setOf("21", "22"))
            File(dir, "dlc/mine.sav").writeBytes(disc2)
            File(dir, "$folder [UPD].bin").writeBytes(disc2)
            val own = listedWithExtras.own

            assertTrue(RommInstalls.dropExtra(app, "5", own, extra("22")))
            assertEquals("a file put beside it stays, and so does its folder", listOf("mine.sav"), File(dir, "dlc").list()!!.toList())
            assertTrue(RommInstalls.dropExtra(app, "5", own, extra("21")))
            assertFalse("a folder left empty goes", File(dir, "hack").exists())
            assertEquals(emptySet<String>(), has())
            assertEquals(emptySet<String>(), RommCatalog.chosenExtras(record().item!!))

            assertFalse("a file the record does not list is the user's", RommInstalls.dropExtra(app, "5", own, extra("23")))
            assertArrayEquals(disc2, File(dir, "$folder [UPD].bin").readBytes())
            assertFalse("the game's own file is not an extra", RommInstalls.dropExtra(app, "5", own, own.single()))
            assertFalse(RommInstalls.dropExtra(app, "5", own, RommFile("11", "../$folder.chd", 1)))
            assertArrayEquals(disc1, File(dir, "$folder.chd").readBytes())
            assertEquals("psx/$folder/$folder.chd", record().path)

            RommInstalls.remove(app, "5")
            assertEquals(listOf("$folder [UPD].bin", "dlc"), dir.list()!!.sorted())
        }
    }

    @Test fun aGameInstalledAsOnePlainFileTakesNoExtraFiles() {
        RommFakeServer(8) { r -> rommWithExtras(r) }.use { server ->
            signIn(server)
            install(emptySet())
            assertEquals(null, has())
            server.seen.clear()
            val failure = runCatching { add("22") }.exceptionOrNull()
            assertTrue(failure.toString(), failure is IllegalStateException)
            assertTrue(asked(server).isEmpty())
            assertFalse(File(roms, "psx/$folder").exists())
            assertArrayEquals("the game stays where it was installed", disc1, File(roms, "psx/$folder.chd").readBytes())
            assertEquals("psx/$folder.chd", record().path)
        }
    }

    @Test fun anExtraFileIsNeverPutOverAFileOfTheUsersOwn() {
        RommFakeServer(8) { r -> rommWithExtras(r) }.use { server ->
            signIn(server)
            val dir = File(roms, "psx/$folder")
            install(setOf("21"))
            val mine = File(dir, "dlc/Pack.bin").apply { parentFile!!.mkdirs(); writeBytes(disc2) }
            server.seen.clear()
            assertTrue(runCatching { add("22") }.exceptionOrNull() is IllegalStateException)
            assertTrue(asked(server).isEmpty())
            assertArrayEquals(disc2, mine.readBytes())
            assertEquals(setOf("21"), has())
            RommInstalls.remove(app, "5")
            assertArrayEquals("and it is not taken with the game", disc2, mine.readBytes())
        }
    }

    @Test fun anExtraFileThatDoesNotComeLeavesTheGameAsItWas() {
        RommFakeServer(8) { r -> rommWithExtras(r, wrong = "22") }.use { server ->
            signIn(server)
            val dir = File(roms, "psx/$folder")
            install(setOf("21"))
            val listed = RommInstalls.files(app, record())

            assertEquals(dir.path, add("23"))
            assertTrue("not the file on record", runCatching { add("22") }.exceptionOrNull() is IllegalStateException)
            assertFalse(File(dir, "dlc/Pack.bin").exists())
            assertFalse(File(dir, "dlc/Pack.bin.part").exists())
            assertEquals("what did not come is not listed", listed!! + "psx/$folder/$folder [UPD].bin", RommInstalls.files(app, record()))
            assertEquals(setOf("21", "23"), has())

            server.seen.clear()
            val gone = RommFile("99", "dlc/Gone.bin", 10, category = "dlc")
            assertTrue(runCatching { RommInstaller(app, record().item!!, listOf(gone)).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))) }.exceptionOrNull() is IllegalStateException)
            assertTrue("a file the server no longer lists is not asked for", asked(server).isEmpty())
            assertEquals(setOf("21", "23"), has())
            assertTrue(recorded())
        }
    }

    @Test fun givingUpAnExtraFileTakesWhatCameOfItAndLeavesTheGame() {
        RommFakeServer(8) { r -> rommWithExtras(r) }.use { server ->
            signIn(server)
            val dir = File(roms, "psx/$folder")
            install(setOf("21"))
            val part = File(dir, "dlc/Pack.bin.part").apply { parentFile!!.mkdirs(); writeBytes(pack.copyOfRange(0, 500)) }

            RommInstaller(app, record().item!!, listOf(extra("22"))).cancel(deleteFiles = true)

            assertFalse(part.exists())
            assertTrue(recorded())
            assertArrayEquals(disc1, File(dir, "$folder.chd").readBytes())
            assertArrayEquals(hack, File(dir, "hack/A Hack.chd").readBytes())
            assertEquals(setOf("21"), has())
        }
    }

    /** A game held as its base file and an update the emulator opens too, both at the top of its folder; the update's name sorts first. */
    private val withUpdate = mapOf("11" to ("$folder.chd" to disc2), "12" to ("$folder Upd v1.4.pbp" to patch))

    @Test fun playStartsTheLargestOfAGamesOwnFilesAndNotTheFirstByName() {
        RommFakeServer(8) { r -> romm(r, withUpdate, hashes = true) }.use { server ->
            signIn(server)
            val dir = File(roms, "psx/$folder")
            assertEquals(File(dir, "$folder.chd").path, install())
            assertEquals("psx/$folder/$folder.chd", record().path)
            assertEquals(listOf("$folder Upd v1.4.pbp", "$folder.chd"), dir.list()!!.sorted())
        }
    }

    private val twoDiscs = mapOf("11" to ("$folder (Disc 1).chd" to disc1), "12" to ("$folder (Disc 2).chd" to disc2))

    @Test fun aGameHeldAsAFolderIsDownloadedFileByFile() {
        serve(twoDiscs)
        val dir = File(roms, "psx/$folder")
        assertEquals(File(dir, "$folder.m3u").path, install())
        assertArrayEquals(disc1, File(dir, "$folder (Disc 1).chd").readBytes())
        assertArrayEquals(disc2, File(dir, "$folder (Disc 2).chd").readBytes())
        assertEquals("$folder (Disc 1).chd\n$folder (Disc 2).chd\n", File(dir, "$folder.m3u").readText())
        assertEquals(listOf("$folder (Disc 1).chd", "$folder (Disc 2).chd", "$folder.m3u"), dir.list()!!.sorted())
        assertEquals("/api/roms/5 Bearer $token", seen.first())
        assertEquals(
            listOf(
                "/api/roms/5/content/Game%20%28USA%29%20%28Disc%201%29.chd?file_ids=11 Bearer $token",
                "/api/roms/5/content/Game%20%28USA%29%20%28Disc%202%29.chd?file_ids=12 Bearer $token",
            ),
            seen.drop(1).sorted(),
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

    private fun crc(bytes: ByteArray) = "%08x".format(java.util.zip.CRC32().apply { update(bytes) }.value)

    private fun keptZip(name: String, crc: String) = oneFile(name, "").let { it.copy(extra = it.extra + (RommCatalog.PLATFORM to "switch") + (RommCatalog.CRC to crc)) }

    @Test fun aZipKeptAsSentThatHoldsOneFileIsCheckedAgainstThatFilesCrc() {
        val zip = zipped("$folder.nsp", disc1)
        serve(mapOf("5" to ("$folder.zip" to zip)))
        val kept = File(roms, "switch/$folder.zip")
        assertEquals(kept.path, RommInstaller(app, keptZip("$folder.zip", crc(disc1).uppercase())).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))))
        assertArrayEquals("the zip is kept as it came", zip, kept.readBytes())

        kept.delete()
        RommInstalls.remove(app, "5")
        val error = runCatching { RommInstaller(app, keptZip("$folder.zip", crc(disc2))).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))) }.exceptionOrNull()
        assertTrue("a zip whose one file is not the one on record fails like a wrong SHA-1", error is IllegalStateException)
        assertEquals(app.getString(com.droiddeck.launcher.R.string.stores_romm_not_as_recorded), error?.message)
        assertEquals(emptyList<String>(), File(roms, "switch").list().orEmpty().toList())
        assertFalse(recorded())
    }

    @Test fun aZipTheServerHashedAsItIsOrThatHoldsSeveralFilesIsNotTurnedAway() {
        val zip = zipped("$folder.nsp", disc1)
        serve(mapOf("5" to ("$folder.zip" to zip)))
        val kept = File(roms, "switch/$folder.zip")
        assertEquals("RomM hashes a zip it cannot read inside as it is", kept.path, RommInstaller(app, keptZip("$folder.zip", crc(zip))).run(DownloadQueue.JobHandle("romm:5", AtomicBoolean(false))))

        val two = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z -> for ((name, data) in listOf("a.bin" to disc1, "b.bin" to disc2)) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(data); z.closeEntry() } }
        }.toByteArray()
        val several = File(roms, "several.zip").apply { writeBytes(two) }
        val broken = File(roms, "broken.zip").apply { writeBytes(disc1) }
        val one = File(roms, "one.zip").apply { writeBytes(zip) }
        assertTrue("a zip of several files has no one CRC to go by", RommInstaller.zipAsRecorded(several, crc(disc2)))
        assertTrue("nor has one that cannot be opened", RommInstaller.zipAsRecorded(broken, crc(disc2)))
        assertFalse(RommInstaller.zipAsRecorded(one, crc(disc2)))
        assertTrue(RommInstaller.zipAsRecorded(one, crc(disc1)))
        assertEquals(crc(zip), com.droiddeck.launcher.core.Hashes.crc32(one))
    }

    @Test fun aFolderGamesZipIsCheckedAgainstItsOwnCrcAndADiscCarriesIts() {
        val files = RommCatalog.fileSet(JSONObject("""{"fs_path":"roms/psx","fs_name":"Game","files":[{"id":1,"file_name":"a.zip","full_path":"roms/psx/Game/a.zip","file_size_bytes":10,"crc_hash":"0a1b2c3d","sha1_hash":""}]}"""))
        assertEquals("0a1b2c3d", files.own.single().crc)
        fun disc(n: Int, crc: String) = oneFile("$folder (Disc $n).zip", "").let { it.copy(id = "$n", extra = it.extra + (RommCatalog.CRC to crc)) }
        val set = RommCatalog.discSet(folder, listOf(disc(1, "11111111"), disc(2, "")))!!
        assertEquals(listOf("11111111", ""), RommCatalog.discs(set).map { it.crc })
        assertEquals("a set recorded before the CRC was kept has none", listOf(""), RommCatalog.discs(set.copy(extra = set.extra + (RommCatalog.DISCS to """[["1","a.chd",5,""]]"""))).map { it.crc })
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

    @Test fun aFileTheServerDoesNotHaveFailsTheInstallAndKeepsWhatCame() {
        serve(twoDiscs, listed = twoDiscs + ("13" to ("$folder (Disc 3).chd" to ByteArray(10))))
        val error = runCatching { install() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        // The other files stop where they are when one fails: each is there whole, or as the part the next install carries on from.
        for ((name, bytes) in twoDiscs.values) {
            val came = File(roms, "psx/$folder/$name").takeIf { it.isFile } ?: File(roms, "psx/$folder/$name.part")
            assertArrayEquals(name, bytes.copyOfRange(0, came.length().toInt()), came.takeIf { it.isFile }?.readBytes() ?: ByteArray(0))
        }
        assertFalse(recorded())
    }
}
