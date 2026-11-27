package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.stores.CloudChanged
import com.droiddeck.launcher.stores.CloudSaves
import com.droiddeck.launcher.stores.Store
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class RommCloudTest {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val token = "rmm_" + "0123456789abcdef".repeat(4)

    /** A server holding saves and states in memory, the way RomM lists, serves and takes them. */
    private class Shelf {
        class Held(val id: Int, val kind: String, val name: String, var data: ByteArray, val emulator: String, var updated: String, val hashed: Boolean = true) {
            var slot: String? = null
            var picture: String? = null
        }
        val held = ArrayList<Held>()
        var refuse = 0
        private var last = 0

        fun add(kind: String, name: String, text: String, emulator: String = "retroarch", updated: String = "2026-01-01T00:00:00+00:00", hashed: Boolean = true) =
            Held(++last, kind, name, text.toByteArray(), emulator, updated, hashed).also { held += it }

        private fun json(h: Held) = JSONObject().put("id", h.id).put("file_name", h.name).put("emulator", h.emulator).put("updated_at", h.updated)
            .put("content_hash", if (h.hashed && h.kind == "saves") hash(h.data) else JSONObject.NULL)
            .put("file_size_bytes", h.data.size).put("slot", h.slot ?: JSONObject.NULL)
            .put("screenshot", h.picture?.let { JSONObject().put("id", 90 + h.id).put("download_path", it) } ?: JSONObject.NULL)

        // RomM hashes a zipped save by what is in it, and any other save as it is.
        private fun hash(data: ByteArray): String {
            if (data.size < 4 || data[0] != 'P'.code.toByte() || data[1] != 'K'.code.toByte()) return CloudSaves.md5(data)
            val file = File.createTempFile("held", ".zip")
            return try { file.writeBytes(data); RommPack.zipHash(file) ?: CloudSaves.md5(data) } finally { file.delete() }
        }

        private fun part(r: RommFakeServer.Request): Triple<String, String, ByteArray> {
            val text = r.text
            val field = Regex("name=\"([^\"]+)\"").find(text)!!.groupValues[1]
            val name = Regex("filename=\"([^\"]+)\"").find(text)!!.groupValues[1]
            val start = text.indexOf("\r\n\r\n") + 4
            val end = text.lastIndexOf("\r\n--")
            return Triple(field, name, r.body.copyOfRange(start, end))
        }

        fun answer(r: RommFakeServer.Request): Pair<Int, String> {
            val path = r.path.substringBefore('?')
            val kind = path.removePrefix("/api/").substringBefore('/')
            val id = path.removePrefix("/api/$kind").trim('/').substringBefore('/').toIntOrNull()
            return when {
                refuse != 0 && r.method != "GET" -> refuse to "{}"
                r.method == "POST" && path == "/api/$kind/delete" -> {
                    val ids = JSONObject(r.text).getJSONArray(kind).let { a -> (0 until a.length()).map { a.getInt(it) } }
                    if (ids.any { wanted -> held.none { it.id == wanted && it.kind == kind } }) 404 to "{}"
                    else { held.removeAll { it.kind == kind && it.id in ids }; 200 to JSONArray(ids).toString() }
                }
                r.method == "GET" && id == null -> 200 to JSONArray(held.filter { it.kind == kind }.map(::json)).toString()
                r.method == "GET" -> 200 to String(held.first { it.id == id && it.kind == kind }.data, Charsets.ISO_8859_1)
                r.method == "PUT" -> held.first { it.id == id && it.kind == kind }.let { h ->
                    h.data = part(r).third; h.updated = "2026-02-02T00:00:00+00:00"; 200 to json(h).toString()
                }
                else -> part(r).let { (_, name, data) ->
                    201 to json(Held(++last, kind, name, data, Regex("emulator=([^&]+)").find(r.path)!!.groupValues[1], "2026-02-02T00:00:00+00:00").also { held += it }).toString()
                }
            }
        }
    }

    private fun signedIn(server: RommFakeServer, run: () -> Unit) {
        val accounts = com.droiddeck.launcher.stores.StoreAccounts
        accounts.keys = com.droiddeck.launcher.stores.CredentialCipher.KeyProvider { javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        accounts.keystoreFailed = false
        accounts.write(app, Store.ROMM, JSONObject().put("server", server.address).put("access_token", token).put("version", "5.4.1").put("device_id", "dev-1"))
        RommSync.signedIn(app)
        try { run() } finally {
            accounts.clear(app, Store.ROMM)
            accounts.keys = com.droiddeck.launcher.stores.CredentialCipher.Keystore
        }
    }

    @Test fun theSavesAndStatesTheServerHoldsAreListedWithTheirSlotSizeAndPicture() {
        val shelf = Shelf()
        val shot = "/api/screenshots/91/content?timestamp=2026-02-01 00:00:00&name=Game.state1.png"
        shelf.add("saves", "Game.srm", "12345678", updated = "2026-01-05T00:00:00+00:00").slot = "2"
        shelf.add("saves", "Game.srm", "web", emulator = "snes9x", updated = "2026-03-01T00:00:00+00:00")
        shelf.add("states", "Game.state1", "state", updated = "2026-02-01T00:00:00+00:00").picture = shot
        shelf.add("states", "Game.state2", "s", updated = "2026-01-01T00:00:00+00:00").picture = "https://elsewhere.example/shot.png"
        RommFakeServer { r -> if (r.path.startsWith("/api/screenshots/")) 200 to "PNG" else shelf.answer(r) }.use { server ->
            assertNull("nothing is asked without a sign-in", RommAssets.list(app, "7"))
            assertTrue(server.seen.isEmpty())
            signedIn(server) {
                val listed = RommAssets.list(app, "7")!!
                assertEquals(
                    "the newest first, whatever emulator wrote them",
                    listOf("saves 2 snes9x", "states 3 retroarch", "saves 1 retroarch", "states 4 retroarch"), listed.map { "${it.kind} ${it.id} ${it.emulator}" },
                )
                assertEquals(listOf("", "", "2", ""), listed.map { it.slot })
                assertEquals(listOf(3L, 5L, 8L, 1L), listed.map { it.size })
                assertEquals("only a picture on the server itself is kept", listOf("", shot, "", ""), listed.map { it.picture })
                assertEquals(listOf("GET /api/saves?rom_id=7", "GET /api/states?rom_id=7"), server.paths())

                assertEquals("PNG", RommAssets.picture(app, shot)?.let { String(it) })
                assertEquals("GET /api/screenshots/91/content?timestamp=2026-02-01%2000:00:00&name=Game.state1.png", server.paths().last())
                assertNull(RommAssets.picture(app, "https://elsewhere.example/shot.png"))
                assertEquals(3, server.seen.size)
                assertTrue(server.seen.all { it.authorization == "Bearer $token" })
                assertNull("a ROM id that is no id asks nothing", RommAssets.list(app, "7/../8"))
                assertEquals(3, server.seen.size)
            }
        }
    }

    @Test fun aSaveDeletedOnTheServerStaysOnTheDeviceAndIsNotSentBackUntilItChanges() {
        val shelf = Shelf()
        shelf.add("saves", "Game.srm", "v1")
        shelf.add("states", "Game.state", "s1")
        val dir = File(app.filesDir, "retroarch-${System.nanoTime()}")
        val id = "r${System.nanoTime()}"
        RommFakeServer { r -> if (r.path.startsWith("/api/devices")) 200 to "{}" else shelf.answer(r) }.use { server ->
            signedIn(server) {
                fun up() = CloudSaves.upWith(app, Store.ROMM, id, dir, RommSync.cloud(app, "7", retroArch), force = false)
                fun down() = CloudSaves.downWith(app, Store.ROMM, id, dir, RommSync.cloud(app, "7", retroArch), force = false)
                assertEquals(2, down().files)
                val save = RommAssets.list(app, "7")!!.first { it.kind == "saves" }
                server.seen.clear()

                assertTrue(RommAssets.delete(app, "7", save).ok)
                val asked = server.seen.single()
                assertEquals("POST /api/saves/delete", "${asked.method} ${asked.path}")
                assertEquals("{\"saves\":[1]}", asked.text)
                assertEquals(listOf("Game.state"), shelf.held.map { it.name })
                assertEquals("the file on the device is not touched", "v1", File(dir, "saves/Game.srm").readText())
                assertEquals(setOf("saves" to "Game.srm"), RommAssets.gone(app, "7", "retroarch"))
                assertTrue(RommAssets.gone(app, "7", "melonds").isEmpty())

                assertEquals("left as it was, the file is not sent back", 0, up().files)
                assertEquals(listOf("Game.state"), shelf.held.map { it.name })
                down()
                assertEquals("nor does a download take it away", "v1", File(dir, "saves/Game.srm").readText())

                File(dir, "saves/Game.srm").writeText("v2 played")
                assertEquals(1, up().files)
                assertEquals("v2 played", String(shelf.held.first { it.name == "Game.srm" }.data))
                assertTrue("once it is on the server again it is a save like any other", RommAssets.gone(app, "7", "retroarch").isEmpty())

                assertTrue(RommAssets.delete(app, "7", RommAssets.list(app, "7")!!.first { it.kind == "saves" }).ok)
                assertEquals(0, up().files)
                RommAssets.forget(app, "7")
                assertEquals("an upload asked for by hand sends it again", 1, up().files)
                assertEquals("v2 played", String(shelf.held.first { it.name == "Game.srm" }.data))
            }
        }
    }

    @Test fun deletingAStateNamesItToTheServerAndARefusalChangesNothing() {
        val shelf = Shelf()
        shelf.add("saves", "Game.srm", "v1")
        shelf.add("states", "Game.state", "s1")
        RommFakeServer(shelf::answer).use { server ->
            signedIn(server) {
                val state = RommAssets.list(app, "7")!!.first { it.kind == "states" }
                shelf.refuse = 403
                assertEquals(403, RommAssets.delete(app, "7", state).code)
                assertEquals(2, shelf.held.size)
                assertTrue(RommAssets.gone(app, "7", "retroarch").isEmpty())
                assertEquals("the sign-in is noted as one that may not write saves", RommSync.Access.NO_ACCESS, RommSync.access(app))

                shelf.refuse = 0
                assertTrue(RommAssets.delete(app, "7", state).ok)
                assertEquals("{\"states\":[2]}", server.seen.last().text)
                assertEquals("POST /api/states/delete", server.paths().last())
                assertEquals(listOf("Game.srm"), shelf.held.map { it.name })
                assertEquals("one that is gone already is answered as not found", 404, RommAssets.delete(app, "7", state).code)
                val asked = server.seen.size
                assertEquals(400, RommAssets.delete(app, "7", RommAsset("roms", "1", "x", "", "", 0, 0, "")).code)
                assertEquals("only a save or a state is ever asked to be deleted", asked, server.seen.size)
            }
        }
    }

    private val retroArch = RommSaves.layout(File("/rootfs"), null, RommInstalls.Entry("7", "snes/Game.sfc", "retroarch"))!!

    private fun cloud(server: RommFakeServer, device: String? = "dev-1", layout: RommSaves.Layout = retroArch, onDenied: (String) -> Unit = {}) =
        RommCloud(server.address, token, "7", device, layout, onDenied)

    @Test fun onlyThisGamesRetroArchFilesAreListed() {
        val shelf = Shelf()
        shelf.add("saves", "Game.srm", "old", updated = "2026-01-01T00:00:00+00:00")
        shelf.add("saves", "Game.srm", "new", updated = "2026-01-05T00:00:00+00:00")
        shelf.add("saves", "Game.srm", "web", emulator = "snes9x", updated = "2026-03-01T00:00:00+00:00")
        shelf.add("saves", "Game 2.srm", "other")
        shelf.add("states", "Game.state1", "state")
        RommFakeServer(shelf::answer).use { server ->
            val c = cloud(server)
            val files = c.list().associateBy { it.name }
            assertEquals(setOf("saves/Game.srm", "states/Game.state1"), files.keys)
            assertEquals(CloudSaves.md5("new".toByteArray()), files.getValue("saves/Game.srm").md5)
            assertNull(files.getValue("states/Game.state1").md5)
            assertEquals("new", String(c.get(files.getValue("saves/Game.srm")).first))
            assertEquals(listOf("GET /api/saves?rom_id=7", "GET /api/states?rom_id=7", "GET /api/saves/2/content?device_id=dev-1"), server.paths())
            assertTrue(server.seen.all { it.authorization == "Bearer $token" })
        }
    }

    @Test fun aNewSaveIsPostedAndAKnownOneReplaced() {
        val shelf = Shelf()
        shelf.add("saves", "Game.srm", "v1")
        RommFakeServer(shelf::answer).use { server ->
            val c = cloud(server)
            c.list()
            c.put(mapOf("saves/Game.srm" to "v2".toByteArray(), "states/Game.state" to byteArrayOf(0, 13, 10, -1)))
            assertEquals("v2", String(shelf.held.first { it.name == "Game.srm" }.data))
            assertEquals(listOf<Byte>(0, 13, 10, -1), shelf.held.first { it.name == "Game.state" }.data.toList())
            assertEquals(
                listOf("PUT /api/saves/1?device_id=dev-1", "POST /api/states?rom_id=7&emulator=retroarch"),
                server.paths().drop(2),
            )
            assertTrue("saveFile" in server.seen[2].text && "stateFile" in server.seen[3].text)
            assertEquals(CloudSaves.md5("v2".toByteArray()), c.committed("saves/Game.srm")?.md5)
        }
    }

    @Test fun aSaveTheServerSaysIsNewerIsNotOverwritten() {
        val shelf = Shelf().apply { refuse = 409 }
        RommFakeServer(shelf::answer).use { server ->
            try {
                cloud(server).put(mapOf("saves/Game.srm" to "mine".toByteArray()))
                fail("the save was sent")
            } catch (e: CloudChanged) {
                assertEquals(listOf("saves/Game.srm"), e.unsent)
            }
        }
    }

    @Test fun aTokenThatMayNotWriteIsReported() {
        val shelf = Shelf().apply { refuse = 403 }
        val denied = ArrayList<String>()
        RommFakeServer(shelf::answer).use { server ->
            runCatching { cloud(server) { denied += it }.put(mapOf("saves/Game.srm" to "mine".toByteArray())) }
        }
        assertEquals(listOf("assets.write"), denied)
        RommFakeServer { 403 to "{\"detail\":\"Forbidden\"}" }.use { server -> runCatching { cloud(server) { denied += it }.list() } }
        assertEquals("a list that is refused is a matter of reading", listOf("assets.write", "assets.read"), denied)
    }

    @Test fun aListTheServerRefusesIsNotedAndNotAskedForAgainUntilTheNextSignIn() {
        val shelf = Shelf()
        shelf.add("saves", "Game.srm", "v1")
        var refuse = true
        RommFakeServer { r -> if (refuse) 403 to "{\"detail\":\"Forbidden\"}" else shelf.answer(r) }.use { server ->
            signedIn(server) {
                assertTrue(RommSync.may(app, "assets.read"))
                assertNull(RommAssets.list(app, "7"))
                assertEquals("the states are not asked for once the saves were refused", listOf("GET /api/saves?rom_id=7"), server.paths())
                assertFalse("which the Sync tab says, where it would have listed nothing", RommSync.may(app, "assets.read"))
                assertTrue("what the sign-in may write is another matter", RommSync.may(app, "assets.write"))

                refuse = false
                assertNull(RommAssets.list(app, "7"))
                assertEquals("the server is not asked again while the refusal stands", 1, server.seen.size)
                assertTrue("a day later it is", RommSync.may(app, "assets.read", System.currentTimeMillis() + 25L * 3600 * 1000))
                RommSync.signedIn(app)
                assertEquals("and after the next sign-in", 1, RommAssets.list(app, "7")!!.size)
            }
        }
    }

    @Test fun savesGoDownThenUpAndAClashWaitsForAChoice() {
        val shelf = Shelf()
        val remote = shelf.add("saves", "Game.srm", "v1")
        shelf.add("states", "Game.state", "s1")
        val dir = File(app.filesDir, "retroarch-${System.nanoTime()}")
        File(dir, "saves").mkdirs()
        File(dir, "saves/Other.srm").writeText("someone else's")
        File(dir, "retroarch.cfg").writeText("config")
        val id = "r${System.nanoTime()}"
        RommFakeServer(shelf::answer).use { server ->
            val down = CloudSaves.downWith(app, Store.ROMM, id, dir, cloud(server), force = false)
            assertEquals("ok", down.result); assertEquals(2, down.files)
            assertEquals("v1", File(dir, "saves/Game.srm").readText())
            assertEquals("s1", File(dir, "states/Game.state").readText())

            File(dir, "saves/Game.srm").writeText("v2 played")
            val up = CloudSaves.upWith(app, Store.ROMM, id, dir, cloud(server), force = false)
            assertEquals("ok", up.result); assertEquals(1, up.files)
            assertEquals("v2 played", String(remote.data))
            assertEquals(2, shelf.held.size)

            File(dir, "saves/Game.srm").writeText("v3 here")
            remote.data = "v3 elsewhere".toByteArray(); remote.updated = "2026-03-03T00:00:00+00:00"
            val clash = CloudSaves.upWith(app, Store.ROMM, id, dir, cloud(server), force = false)
            assertEquals(1, clash.conflicts)
            assertEquals("v3 elsewhere", String(remote.data))
            assertEquals(listOf("saves/Game.srm"), CloudSaves.conflicts(app, Store.ROMM, id, dir))

            val kept = CloudSaves.downWith(app, Store.ROMM, id, dir, cloud(server), force = true)
            assertEquals("ok", kept.result)
            assertEquals("v3 elsewhere", File(dir, "saves/Game.srm").readText())
            assertEquals("someone else's", File(dir, "saves/Other.srm").readText())
        }
    }

    @Test fun aDsGamesSaveAndStatesSyncFromBesideTheGameAndTheGameIsNeverRead() {
        val shelf = Shelf()
        shelf.add("saves", "Game.sav", "from retroarch", emulator = "retroarch")
        val remote = shelf.add("saves", "Game.sav", "v1", emulator = "melonds")
        shelf.add("states", "Game.ml1", "s1", emulator = "melonds")
        shelf.add("states", "Game.ml9", "not a slot", emulator = "melonds")
        val roms = File(app.filesDir, "roms-${System.nanoTime()}")
        val dir = File(roms, "nds").apply { mkdirs() }
        File(dir, "Game.nds").writeText("the game")
        File(dir, "Other.sav").writeText("someone else's")
        val layout = RommSaves.layout(File("/rootfs"), roms, RommInstalls.Entry("7", "nds/Game.nds", "melonds"))!!
        val id = "r${System.nanoTime()}"
        RommFakeServer(shelf::answer).use { server ->
            val down = CloudSaves.downWith(app, Store.ROMM, id, layout.root, cloud(server, layout = layout), force = false)
            assertEquals("ok", down.result); assertEquals(2, down.files)
            assertEquals("v1", File(dir, "Game.sav").readText())
            assertEquals("s1", File(dir, "Game.ml1").readText())
            assertFalse(File(dir, "Game.ml9").exists())

            File(dir, "Game.sav").writeText("v2 played")
            File(dir, "Game.ml2").writeText("s2")
            val up = CloudSaves.upWith(app, Store.ROMM, id, layout.root, cloud(server, layout = layout), force = false)
            assertEquals("ok", up.result); assertEquals(2, up.files)
            assertEquals("v2 played", String(remote.data))
            val added = shelf.held.last()
            assertEquals(listOf("states", "Game.ml2", "melonds", "s2"), listOf(added.kind, added.name, added.emulator, String(added.data)))
            assertEquals("from retroarch", String(shelf.held.first().data))
            assertTrue(shelf.held.none { it.name == "Game.nds" || it.name == "Other.sav" })
        }
    }

    @Test fun aPs1GamesCardsSyncFromDuckStationsCardFolder() {
        val shelf = Shelf()
        val rootfs = File(app.filesDir, "rootfs-${System.nanoTime()}")
        val home = File(rootfs, "root/.config/duckstation").apply { mkdirs() }
        File(home, "settings.ini").writeText("[MemoryCards]\nCard1Type = PerGameFileTitle\n")
        File(home, "memcards").mkdirs()
        File(home, "memcards/Game (USA)_1.mcd").writeText("card one")
        File(home, "memcards/shared_card_1.mcd").writeText("shared")
        val layout = RommSaves.layout(rootfs, null, RommInstalls.Entry("7", "psx/Game (USA).chd", "duckstation"))!!
        RommFakeServer(shelf::answer).use { server ->
            val id = "r${System.nanoTime()}"
            CloudSaves.downWith(app, Store.ROMM, id, layout.root, cloud(server, layout = layout), force = false)
            val up = CloudSaves.upWith(app, Store.ROMM, id, layout.root, cloud(server, layout = layout), force = false)
            assertEquals("ok", up.result); assertEquals(1, up.files)
            val sent = shelf.held.single()
            assertEquals(listOf("saves", "Game (USA)_1.mcd", "duckstation", "card one"), listOf(sent.kind, sent.name, sent.emulator, String(sent.data)))
        }
    }

    private fun psp(rootfs: File) = RommSaves.layout(
        rootfs, null,
        RommInstalls.Entry("7", "psp/Game.iso", "ppsspp", item = com.droiddeck.launcher.stores.CatalogItem(Store.ROMM, "7", "Game", null, extra = mapOf(RommCatalog.TITLE_ID to "ULUS10336"))),
    )!!

    @Test fun aPspGamesSaveFoldersGoUpAsZipsAndComeBackAsFoldersOnAnotherDevice() {
        val shelf = Shelf()
        RommFakeServer(shelf::answer).use { server ->
            val first = psp(File(app.filesDir, "rootfs-${System.nanoTime()}"))
            val saves = first.packed!!.source
            File(saves, "ULUS10336DATA00").mkdirs()
            File(saves, "ULUS10336DATA00/PARAM.SFO").writeText("sfo")
            File(saves, "ULUS10336DATA00/DATA.BIN").writeText("chapter one")
            File(saves, "ULES00151DATA00").mkdirs()
            File(saves, "ULES00151DATA00/DATA.BIN").writeText("another game")
            File(first.root, "PPSSPP_STATE").mkdirs()
            File(first.root, "PPSSPP_STATE/ULUS10336_1.00_0.ppst").writeText("state")
            val here = "a${System.nanoTime()}"
            var before = RommPack.stage(first.packed!!)
            CloudSaves.downWith(app, Store.ROMM, here, first.root, cloud(server, layout = first), force = false)
            val up = CloudSaves.upWith(app, Store.ROMM, here, first.root, cloud(server, layout = first), force = false)
            assertTrue(RommPack.unstage(first.packed!!, before).isEmpty())
            assertEquals("ok", up.result); assertEquals(2, up.files)
            assertEquals(setOf("saves ULUS10336DATA00.zip ppsspp", "states ULUS10336_1.00_0.ppst ppsspp"), shelf.held.map { "${it.kind} ${it.name} ${it.emulator}" }.toSet())

            val second = psp(File(app.filesDir, "rootfs-${System.nanoTime()}"))
            val there = "b${System.nanoTime()}"
            before = RommPack.stage(second.packed!!)
            val down = CloudSaves.downWith(app, Store.ROMM, there, second.root, cloud(server, layout = second), force = false)
            assertEquals(listOf("ULUS10336DATA00"), RommPack.unstage(second.packed!!, before))
            assertEquals("ok", down.result); assertEquals(2, down.files)
            assertEquals("chapter one", File(second.packed!!.source, "ULUS10336DATA00/DATA.BIN").readText())
            assertEquals("sfo", File(second.packed!!.source, "ULUS10336DATA00/PARAM.SFO").readText())
            assertEquals("state", File(second.root, "PPSSPP_STATE/ULUS10336_1.00_0.ppst").readText())
            assertTrue(!File(second.packed!!.source, "ULES00151DATA00").exists())

            File(second.packed!!.source, "ULUS10336DATA00/DATA.BIN").writeText("chapter two")
            before = RommPack.stage(second.packed!!)
            val sent = CloudSaves.upWith(app, Store.ROMM, there, second.root, cloud(server, layout = second), force = false)
            RommPack.unstage(second.packed!!, before)
            assertEquals("ok", sent.result); assertEquals(1, sent.files)

            before = RommPack.stage(first.packed!!)
            val back = CloudSaves.downWith(app, Store.ROMM, here, first.root, cloud(server, layout = first), force = false)
            assertEquals(listOf("ULUS10336DATA00"), RommPack.unstage(first.packed!!, before))
            assertEquals("ok", back.result)
            assertEquals("chapter two", File(saves, "ULUS10336DATA00/DATA.BIN").readText())
            assertEquals("another game", File(saves, "ULES00151DATA00/DATA.BIN").readText())
        }
    }
}
