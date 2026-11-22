package com.droiddeck.launcher.ports

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PortInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rom = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40) + ByteArray(60) { it.toByte() }
    private val port = TestPorts.zelda

    private fun tarGz(files: List<Triple<String, ByteArray, Int>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GzipCompressorOutputStream(bytes)).use { tar ->
            for ((name, data, mode) in files) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong(); this.mode = mode })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun zip(name: String, data: ByteArray) = tmp.newFile("release.zip").apply {
        ZipOutputStream(outputStream()).use { it.putNextEntry(ZipEntry(name)); it.write(data); it.closeEntry() }
    }

    @Test fun theArm64BuildIsPickedFromARelease() {
        val names = listOf(
            "Zelda64Recompiled-v1.2.2-Linux-ARM64.zip", "Zelda64Recompiled-v1.2.2-Linux-Flatpak-X64.zip", "Zelda64Recompiled-v1.2.2-Linux-X64.zip",
            "Zelda64Recompiled-v1.2.2-macOS.zip", "Zelda64Recompiled-v1.2.2-Windows.zip",
        )
        assertEquals("Zelda64Recompiled-v1.2.2-Linux-ARM64.zip", PortInstaller.pickAsset(port, names))
        assertNull(PortInstaller.pickAsset(port, names.drop(1)))
    }

    @Test fun theTarballInsideTheZipIsUnpackedWithItsProgramRunnable() {
        val archive = zip("Zelda64Recompiled.tar.gz", tarGz(listOf(
            Triple("Zelda64Recompiled", byteArrayOf(1, 2, 3), "755".toInt(8)),
            Triple("assets/recomp.rcss", byteArrayOf(4), "664".toInt(8)),
            Triple("../escaped", byteArrayOf(5), "664".toInt(8)),
        )))
        val dest = tmp.newFolder("port")
        PortInstaller.unpack(archive, dest)
        assertTrue(File(dest, "Zelda64Recompiled").canExecute())
        assertEquals(1L, File(dest, "assets/recomp.rcss").length())
        assertFalse(File(dest.parentFile, "escaped").exists())
    }

    @Test fun theRomIsStagedBigEndianUnderThePortsName() {
        val dir = tmp.newFolder("port")
        val swapped = File(tmp.root, "mm.v64").apply { writeBytes(rom.toList().chunked(2).flatMap { it.reversed() }.toByteArray()) }
        assertTrue(PortInstaller.stageRom(port, swapped, dir))
        assertTrue(File(dir, port.stagedRom).readBytes().contentEquals(rom))
        val junk = File(tmp.root, "junk.z64").apply { writeBytes(ByteArray(64)) }
        assertFalse(PortInstaller.stageRom(port, junk, dir))
        assertTrue(File(dir, port.stagedRom).readBytes().contentEquals(rom))
        assertFalse(File(dir, port.stagedRom + ".part").exists())
    }

    @Test fun aReinstallKeepsTheSaves() {
        val dir = tmp.newFolder("zelda").apply { File(this, "saves").mkdirs(); File(this, "saves/file1.bin").writeText("save"); File(this, "old.txt").writeText("") }
        val fresh = tmp.newFolder(".zelda.new").apply { File(this, "new.txt").writeText("") }
        assertTrue(PortInstaller.swap(dir, fresh))
        assertEquals("save", File(dir, "saves/file1.bin").readText())
        assertTrue(File(dir, "new.txt").exists())
        assertFalse(File(dir, "old.txt").exists())
        assertFalse(File(tmp.root, ".zelda.old").exists())
    }

    @Test fun aSwapThatFailsLeavesTheOldInstallAndItsSaves() {
        val dir = tmp.newFolder("zelda").apply { File(this, "saves").mkdirs(); File(this, "saves/file1.bin").writeText("save"); File(this, "old.txt").writeText("") }
        assertFalse(PortInstaller.swap(dir, File(tmp.root, ".zelda.new")))
        assertEquals("save", File(dir, "saves/file1.bin").readText())
        assertTrue(File(dir, "old.txt").exists())
    }

    private fun elf(machine: Int, size: Int = 64) = ByteArray(size).also {
        it[0] = 0x7f; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte(); it[4] = 2; it[18] = machine.toByte()
    }

    private fun added(entry: String, staged: String, source: String) = Port(
        id = "demo", name = "Demo", game = "Game", repo = "o/r", asset = Regex("arm64"), entry = entry, systemFolders = listOf("snes"),
        exts = setOf("sfc"), form = RomForm.PLAIN, romSize = 4, romSha1 = "ab".repeat(20), stagedRom = staged, source = source,
    )

    @Test fun aDownloadMustComeWithGithubsHash() {
        val theirs = added(entry = "demo", staged = "g.sfc", source = "o/list")
        val url = "https://github.com/o/r/releases/download/v1/demo-arm64.zip"
        assertNull(PortInstaller.refusal(theirs, url, "ab".repeat(32)))
        assertEquals(com.droiddeck.launcher.R.string.ports_unchecked, PortInstaller.refusal(theirs, url, null))
        assertNull(PortInstaller.refusal(port, url, "ab".repeat(32)))
        assertEquals(com.droiddeck.launcher.R.string.ports_unchecked, PortInstaller.refusal(port, url, null))
        assertEquals(com.droiddeck.launcher.R.string.ports_no_build, PortInstaller.refusal(port, "https://evil.example/demo-arm64.zip", "ab".repeat(32)))
    }

    @Test fun theProgramOfADownloadIsItsArm64Executable() {
        val dir = tmp.newFolder("found")
        File(dir, "lib").mkdirs()
        File(dir, "readme.txt").writeText("hello")
        File(dir, "tool-x64").writeBytes(elf(0x3e, 900))
        File(dir, "lib/libgame.so.1").writeBytes(elf(0xb7, 800))
        File(dir, "lib/helper").writeBytes(elf(0xb7, 700))
        assertEquals("lib/helper", PortInstaller.findProgram(dir))
        File(dir, "small").writeBytes(elf(0xb7, 100))
        File(dir, "game").writeBytes(elf(0xb7, 500))
        assertEquals("game", PortInstaller.findProgram(dir))
        assertNull(PortInstaller.findProgram(tmp.newFolder("empty")))
    }

    @Test fun aTarballOrABareProgramIsTakenAsItIs() {
        val tarball = tmp.newFile("port-demo-Demo-arm64.tar.gz").apply { writeBytes(tarGz(listOf(Triple("bin/demo", byteArrayOf(1), "755".toInt(8))))) }
        val a = tmp.newFolder("a")
        PortInstaller.unpack(tarball, a, "Demo-arm64.tar.gz")
        assertTrue(File(a, "bin/demo").canExecute())
        val bare = tmp.newFile("port-demo-download").apply { writeBytes(elf(0xb7)) }
        val b = tmp.newFolder("b")
        PortInstaller.unpack(bare, b, "Demo-aarch64.AppImage")
        assertTrue(File(b, "Demo-aarch64.AppImage").canExecute())
        assertEquals("Demo-aarch64.AppImage", PortInstaller.findProgram(b))
    }

    @Test fun aPortWithNoStagedNameLeavesTheRomWhereItIs() {
        val open = added(entry = "", staged = "", source = "o/r")
        val dir = tmp.newFolder("open")
        assertTrue(PortInstaller.stageRom(open, File(dir, "missing.sfc"), dir))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertEquals("bin/demo", open.withEntry("bin/demo").entry)
    }
}
