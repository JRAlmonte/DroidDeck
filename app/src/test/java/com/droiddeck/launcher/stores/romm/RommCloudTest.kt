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
        class Held(val id: Int, val kind: String, val name: String, var data: ByteArray, val emulator: String, var updated: String, val hashed: Boolean = true)
        val held = ArrayList<Held>()
        var refuse = 0

        fun add(kind: String, name: String, text: String, emulator: String = "retroarch", updated: String = "2026-01-01T00:00:00+00:00", hashed: Boolean = true) =
            Held(held.size + 1, kind, name, text.toByteArray(), emulator, updated, hashed).also { held += it }

        private fun json(h: Held) = JSONObject().put("id", h.id).put("file_name", h.name).put("emulator", h.emulator).put("updated_at", h.updated)
            .put("content_hash", if (h.hashed && h.kind == "saves") CloudSaves.md5(h.data) else JSONObject.NULL)

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
                r.method == "GET" && id == null -> 200 to JSONArray(held.filter { it.kind == kind }.map(::json)).toString()
                r.method == "GET" -> 200 to String(held.first { it.id == id && it.kind == kind }.data, Charsets.ISO_8859_1)
                r.method == "PUT" -> held.first { it.id == id && it.kind == kind }.let { h ->
                    h.data = part(r).third; h.updated = "2026-02-02T00:00:00+00:00"; 200 to json(h).toString()
                }
                else -> part(r).let { (_, name, data) ->
                    201 to json(Held(held.size + 1, kind, name, data, Regex("emulator=([^&]+)").find(r.path)!!.groupValues[1], "2026-02-02T00:00:00+00:00").also { held += it }).toString()
                }
            }
        }
    }

    private val retroArch = RommSaves.layout(File("/rootfs"), null, RommInstalls.Entry("7", "snes/Game.sfc", "retroarch"))!!

    private fun cloud(server: RommFakeServer, device: String? = "dev-1", layout: RommSaves.Layout = retroArch, onDenied: () -> Unit = {}) =
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
        var denied = 0
        RommFakeServer(shelf::answer).use { server ->
            runCatching { cloud(server) { denied++ }.put(mapOf("saves/Game.srm" to "mine".toByteArray())) }
        }
        assertEquals(1, denied)
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
}
