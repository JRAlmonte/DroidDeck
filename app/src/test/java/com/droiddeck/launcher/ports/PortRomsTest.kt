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

    @Test fun aPortInUseIsFoundByItsTargetAndFolder() {
        assertEquals(emptyList<Port>(), Ports.all)
        val p = TestPorts.zelda
        Ports.use(listOf(p))
        try {
            assertEquals(p, Ports.byTarget(Ports.PREFIX + p.id))
            assertEquals(p, Ports.forFolder(p.systemFolders.first().uppercase()))
            assertEquals("/opt/droiddeck-ports/${p.id}/${p.entry}", p.program)
            assertNull(Ports.byTarget("duckstation"))
        } finally {
            Ports.use(emptyList())
        }
    }

    @Test fun aRomSaysWhatAPortEntryNeedsToKnow() {
        val root = tmp.newFolder("facts")
        val n64 = File(root, "N64/sub/game.v64").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(0x37, 0x80.toByte(), 0x40, 0x12) + ByteArray(60)) }
        val big = File(root, "N64/game.z64").apply { writeBytes(byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40) + ByteArray(60)) }
        val facts = PortRoms.facts(root, n64)!!
        assertEquals("n64", facts.folder)
        assertEquals(RomForm.N64, facts.form)
        assertEquals(listOf("z64", "n64", "v64"), facts.exts)
        assertEquals(PortRoms.facts(root, big)!!.sha1, facts.sha1)
        assertEquals(Hashes.hex(MessageDigest.getInstance("MD5").digest(big.readBytes())), facts.md5)
        val plain = File(root, "snes/game.sfc").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(32) { 7 }) }
        assertEquals(listOf("sfc"), PortRoms.facts(root, plain)!!.exts)
        assertEquals(RomForm.PLAIN, PortRoms.facts(root, plain)!!.form)
        assertNull(PortRoms.facts(root, File(root, "loose.sfc").apply { writeBytes(ByteArray(4)) }))
        assertNull(PortRoms.facts(root, tmp.newFile("elsewhere.sfc")))
    }
}
