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
    private val port = Ports.all.first()

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
}
