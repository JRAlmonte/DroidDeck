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
            assertEquals("/opt/droiddeck-ports/${p.id}/.droiddeck-start-${p.id}", p.program)
            assertNull(Ports.byTarget("duckstation"))
        } finally {
            Ports.use(emptyList())
        }
    }

    private fun zip(path: String, vararg files: Pair<String, ByteArray>) = File(tmp.root, path).apply {
        parentFile!!.mkdirs()
        java.util.zip.ZipOutputStream(outputStream()).use { z -> for ((name, data) in files) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(data); z.closeEntry() } }
    }

    @Test fun aZippedRomMatchesThroughTheOneFileItHolds() {
        val cache = PortRoms.HashCache(null)
        val good = zip("n64/Game (USA).zip", "Game (USA).v64" to swapped(2))
        assertTrue(PortRoms.matches(port(), good, cache))
        assertEquals(good, PortRoms.scan(tmp.root, listOf(port()), PortRoms.HashCache(null))["test"])
        assertEquals("Game (USA).v64", PortRoms.inner(good)!!.name)
        val out = ByteArrayOutputStream()
        assertTrue(PortRoms.stage(port(), good, out))
        assertTrue(out.toByteArray().contentEquals(rom))
        val raw = ByteArrayOutputStream()
        assertTrue(PortRoms.copy(good, raw))
        assertTrue(raw.toByteArray().contentEquals(swapped(2)))
        val root = tmp.newFolder("zips")
        val two = File(root, "n64/two.zip").also { zip("zips/n64/two.zip", "a.z64" to rom, "readme.txt" to byteArrayOf(1)) }
        assertNull(PortRoms.inner(two))
        assertFalse(PortRoms.matches(port(), two, cache))
        val wrongType = File(root, "n64/doc.zip").also { zip("zips/n64/doc.zip", "a.txt" to rom) }
        val broken = File(root, "n64/broken.zip").apply { writeBytes(ByteArray(40)) }
        assertEquals(emptyList<File>(), PortRoms.candidates(root, port()))
        assertFalse(PortRoms.matches(port(), broken, cache))
        assertTrue(wrongType.isFile)
    }

    @Test fun aZippedRomSaysWhatAPortEntryNeedsToKnow() {
        val plain = put("n64/a.z64", rom)
        val zipped = zip("n64/sub/a.zip", "inside/a.v64" to swapped(2))
        val facts = PortRoms.facts(tmp.root, zipped)!!
        val same = PortRoms.facts(tmp.root, plain)!!
        assertEquals(same.sha1, facts.sha1)
        assertEquals(same.md5, facts.md5)
        assertEquals(rom.size.toLong(), facts.size)
        assertEquals(RomForm.N64, facts.form)
        assertEquals("n64", facts.folder)
        assertEquals(listOf("sfc"), PortRoms.facts(tmp.root, zip("snes/g.zip", "g.sfc" to ByteArray(8)))!!.exts)
        assertNull(PortRoms.facts(tmp.root, zip("snes/many.zip", "a.sfc" to ByteArray(8), "b.sfc" to ByteArray(8))))
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

    @Test fun hashingARomSaysHowFarItIsAndCanBeStopped() {
        val root = tmp.newFolder("progress")
        val big = File(root, "switch/game.xci").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(3 * 1024 * 1024 + 5) { (it % 251).toByte() }) }
        val seen = ArrayList<Int>()
        val facts = PortRoms.facts(root, big) { seen += it }!!
        assertEquals(seen.sorted().distinct(), seen)
        assertEquals(100, seen.last())
        assertTrue(seen.size > 1)
        assertEquals(Hashes.hex(MessageDigest.getInstance("SHA-1").digest(big.readBytes())), facts.sha1)
        assertEquals(Hashes.hex(MessageDigest.getInstance("MD5").digest(big.readBytes())), facts.md5)
        assertNull(PortRoms.facts(root, big) { throw java.util.concurrent.CancellationException() })
    }
}
