package com.droiddeck.launcher.ports

import com.droiddeck.launcher.core.Hashes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class PortRomsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rom = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40) + ByteArray(1020) { (it * 5).toByte() }
    private val sha1 = Hashes.hex(MessageDigest.getInstance("SHA-1").digest(rom))

    private fun port(hash: String = sha1) = Port(
        id = "test", name = "Test Port", game = "Test Game", repo = "a/b", asset = Regex("Linux-ARM64\\.zip$"), entry = "game",
        systemFolders = listOf("n64"), exts = setOf("z64", "n64", "v64"), form = RomForm.N64, romSize = rom.size.toLong(), romSha1 = hash, stagedRom = "game.z64",
    )

    private fun swapped(group: Int) = rom.toList().chunked(group).flatMap { it.reversed() }.toByteArray()

    private fun put(path: String, bytes: ByteArray) = File(tmp.root, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    @Test fun aRomInAnyByteOrderMatchesThePort() {
        val cache = PortRoms.HashCache(null)
        assertTrue(PortRoms.matches(port(), put("n64/a.z64", rom), cache))
        assertTrue(PortRoms.matches(port(), put("n64/b.v64", swapped(2)), cache))
        assertTrue(PortRoms.matches(port(), put("n64/c.n64", swapped(4)), cache))
    }

    @Test fun anotherGameOfTheSameSizeDoesNotMatch() {
        val other = rom.copyOf().also { it[100] = 99 }
        assertFalse(PortRoms.matches(port(), put("n64/other.z64", other), PortRoms.HashCache(null)))
        assertFalse(PortRoms.matches(port(), put("n64/junk.z64", ByteArray(rom.size)), PortRoms.HashCache(null)))
    }

    @Test fun theScanFindsTheRomInItsSystemFolderOnly() {
        put("N64/sub/Game (USA).v64", swapped(2))
        put("n64/short.z64", rom.copyOf(512))
        put("psx/Game.z64", rom)
        val found = PortRoms.scan(tmp.root, listOf(port()), PortRoms.HashCache(null))
        assertEquals(File(tmp.root, "N64/sub/Game (USA).v64"), found["test"])
        assertTrue(PortRoms.scan(tmp.root, listOf(port("0".repeat(40))), PortRoms.HashCache(null)).isEmpty())
        assertTrue(PortRoms.scan(null, listOf(port()), PortRoms.HashCache(null)).isEmpty())
    }

    @Test fun aFileIsHashedOnceUntilItChanges() {
        val file = put("n64/a.z64", rom)
        val store = File(tmp.root, "cache/hashes.tsv")
        var computed = 0
        val compute = { f: File -> computed++; PortRoms.sha1(port(), f) }
        PortRoms.HashCache(store).apply { assertEquals(sha1, sha1(file, compute)); save() }
        assertEquals(sha1, PortRoms.HashCache(store).sha1(file, compute))
        assertEquals(1, computed)
        file.writeBytes(rom.copyOf().also { it[200] = 1 })
        file.setLastModified(file.lastModified() + 5000)
        assertTrue(PortRoms.HashCache(store).sha1(file, compute) != sha1)
        assertEquals(2, computed)
    }

    @Test fun theStagedCopyIsBigEndian() {
        val out = ByteArrayOutputStream()
        assertTrue(PortRoms.stage(port(), put("n64/c.n64", swapped(4)), out))
        assertTrue(out.toByteArray().contentEquals(rom))
        assertNull(PortRoms.sha1(port(), put("n64/junk.z64", ByteArray(rom.size))))
    }

    @Test fun theCatalogPortsAreWellFormed() {
        for (p in Ports.all) {
            assertTrue(p.id, Regex("[0-9a-f]{40}").matches(p.romSha1))
            assertEquals(p, Ports.byTarget(Ports.PREFIX + p.id))
            assertEquals(p, Ports.forFolder(p.systemFolders.first().uppercase()))
            assertEquals("/opt/droiddeck-ports/${p.id}/${p.entry}", p.program)
        }
        assertNull(Ports.byTarget("duckstation"))
    }
}
