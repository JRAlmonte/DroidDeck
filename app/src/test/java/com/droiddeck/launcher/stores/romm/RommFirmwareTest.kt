package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.frontend.Library
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class RommFirmwareTest {
    @get:Rule val tmp = TemporaryFolder()

    private val token = "rmm_" + "0123456789abcdef".repeat(4)
    private val systems = listOf(
        Library.RomSystem("duckstation", listOf("ps1", "psx"), setOf("cue", "chd")),
        Library.RomSystem("retroarch", listOf("segacd", "megacd"), setOf("cue", "chd")),
    )
    private lateinit var roms: File
    private lateinit var rootfs: File

    @Before fun setUp() {
        roms = tmp.newFolder("ROMs")
        rootfs = tmp.newFolder("linuxfs")
    }

    private fun md5(data: ByteArray) = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

    private fun entry(id: Int, name: String, size: Int, md5: String = "") =
        JSONObject().put("id", id).put("file_name", name).put("file_size_bytes", size).put("md5_hash", md5)

    @Test fun theServersListLeavesOutWhatCannotBePlaced() {
        val files = RommFirmware.files(JSONArray()
            .put(entry(1, "scph5501.bin", 524288, "490f666e1afb15b7362b406ed1cea246"))
            .put(entry(2, "../escape.bin", 4))
            .put(entry(3, "gone.bin", 4).put("missing_from_fs", true))
            .put(entry(4, "SCPH5501.BIN", 524288))
            .put(JSONObject().put("id", 5)))
        assertEquals(listOf("scph5501.bin"), files.map { it.name })
        assertEquals("1", files.single().id)
        assertEquals(524288L, files.single().size)
        assertEquals("490f666e1afb15b7362b406ed1cea246", files.single().md5)
        assertTrue(RommFirmware.files(null).isEmpty())
    }

    @Test fun aStandaloneEmulatorReadsTheBiosFolderOfItsSystem() {
        assertEquals(File(roms, "psx/bios"), RommFirmware.directory(roms, rootfs, "psx", systems))
        File(roms, "ps1/BIOS").mkdirs()
        assertEquals(File(roms, "ps1/BIOS"), RommFirmware.directory(roms, rootfs, "psx", systems))
    }

    @Test fun retroArchReadsItsSystemFolder() {
        assertEquals(File(rootfs, "root/.config/retroarch/system"), RommFirmware.directory(roms, rootfs, "segacd", systems))
    }

    @Test fun aPlatformNothingPlaysTakesNone() {
        assertNull(RommFirmware.directory(roms, rootfs, "3do", systems))
        assertNull(RommFirmware.directory(roms, rootfs, "neogeoaes", systems))
    }

    @Test fun onlyFilesThatAreNotHereInFullAreMissing() {
        val files = RommFirmware.files(JSONArray().put(entry(1, "scph5501.bin", 8)).put(entry(2, "scph1001.bin", 8)).put(entry(3, "scph7001.bin", 8)))
        File(roms, "psx/bios").mkdirs()
        File(roms, "psx/bios/scph5501.bin").writeBytes(ByteArray(8))
        File(roms, "psx/bios/scph1001.bin").writeBytes(ByteArray(3))
        assertEquals(listOf("scph1001.bin", "scph7001.bin"), RommFirmware.missing(files, roms, rootfs, "psx", systems).map { it.first.name })
        assertEquals(File(roms, "psx/bios/scph7001.bin"), RommFirmware.missing(files, roms, rootfs, "psx", systems).last().second)
    }

    @Test fun aFileIsKeptOnlyWhenItIsTheOneRommHolds() {
        val data = ByteArray(5000) { (it % 251).toByte() }
        RommFakeServer { request ->
            if (request.path.startsWith("/api/firmware/7/content/")) 200 to String(data, Charsets.ISO_8859_1) else 404 to "{}"
        }.use { server ->
            val to = File(roms, "psx/bios/scph 5501.bin")
            assertNull(RommFirmware.fetch(server.address, token, RommFirmwareFile("7", "scph 5501.bin", 5000, md5(data).uppercase()), to))
            assertArrayEquals(data, to.readBytes())
            assertEquals(listOf("GET /api/firmware/7/content/scph%205501.bin"), server.paths())
            assertEquals("Bearer $token", server.seen.single().authorization)

            val other = File(roms, "psx/bios/other.bin")
            assertEquals(RommFirmware.Problem.MISMATCH, RommFirmware.fetch(server.address, token, RommFirmwareFile("7", "other.bin", 5000, md5(ByteArray(1))), other))
            assertEquals(RommFirmware.Problem.MISMATCH, RommFirmware.fetch(server.address, token, RommFirmwareFile("7", "other.bin", 4000, ""), other))
            assertEquals(RommFirmware.Problem.DOWNLOAD, RommFirmware.fetch(server.address, token, RommFirmwareFile("8", "other.bin", 5000, ""), other))
            assertFalse(other.exists())
            assertEquals(listOf("scph 5501.bin"), File(roms, "psx/bios").list()!!.toList())
        }
    }
}
