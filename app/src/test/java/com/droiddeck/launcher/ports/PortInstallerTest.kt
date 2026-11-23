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

    @Test fun aReinstallKeepsTheSavesAPortNames() {
        val dir = tmp.newFolder("zelda").apply {
            File(this, "data/slots").mkdirs(); File(this, "data/slots/a.sav").writeText("a"); File(this, "data/other.txt").writeText("")
            File(this, "card.bin").writeText("card"); File(this, "saves").mkdirs(); File(this, "saves/old.bin").writeText("")
        }
        val fresh = tmp.newFolder(".zelda.new").apply { File(this, "card.bin").writeText("shipped") }
        assertTrue(PortInstaller.swap(dir, fresh, listOf("data/slots", "card.bin", "missing/file")))
        assertEquals("a", File(dir, "data/slots/a.sav").readText())
        assertEquals("card", File(dir, "card.bin").readText())
        assertFalse(File(dir, "data/other.txt").exists())
        assertFalse(File(dir, "saves").exists())
        assertFalse(File(dir, "missing").exists())
        val blocked = tmp.newFolder("blocked").apply { File(this, "data/slots").mkdirs(); File(this, "data/slots/a.sav").writeText("a") }
        assertFalse(PortInstaller.swap(blocked, File(tmp.root, ".blocked.new"), listOf("data/slots")))
        assertEquals("a", File(blocked, "data/slots/a.sav").readText())
    }

    @Test fun aRemovedInstallLeavesOnlyTheSavesOfThePortsThatShareIt() {
        fun game(id: String, vararg saves: String, tool: String = "suite") = Port(
            id = id, name = "Demo", game = id, repo = "o/r", asset = Regex("arm64"), entry = "demo", systemFolders = listOf("snes"),
            exts = setOf("sfc"), form = RomForm.PLAIN, romSize = 4, romSha1 = "ab".repeat(20), stagedRom = "", tool = tool, saves = saves.toList(),
        )
        val ports = listOf(game("one", "data/one.sav", "~/.local/share/demo"), game("two", "data", "data/two"), game("three"), game("far", "x", tool = "far"))
        assertEquals(listOf("data", "saves"), PortInstaller.kept("suite", ports))
        assertEquals(listOf("saves"), PortInstaller.kept("suite", listOf(game("one", "~/.local/share/demo"))))
        val dir = tmp.newFolder("suite").apply {
            File(this, "a/b").mkdirs(); File(this, "a/b/keep.sav").writeText("k"); File(this, "a/b/drop.txt").writeText(""); File(this, "a/drop").mkdirs()
            File(this, "program").writeText(""); File(this, "saves").mkdirs(); File(this, "saves/s.bin").writeText("s")
        }
        PortInstaller.clear(dir, listOf("a/b/keep.sav", "saves", "gone/away"))
        assertEquals(listOf("a/b/keep.sav", "saves/s.bin"), dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList())
        assertFalse(File(dir, "a/drop").exists())
        val empty = tmp.newFolder("empty-port").apply { File(this, "a").mkdirs(); File(this, "a/x").writeText("") }
        PortInstaller.clear(empty, listOf("a/saves"))
        assertFalse(empty.exists())
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

    @Test fun aPortStartsThroughItsOwnScriptWithItsSettings() {
        val plain = Port(
            id = "demo", name = "Demo", game = "Game", repo = "o/r", asset = Regex("arm64"), entry = "bin/demo", systemFolders = listOf("gbc"),
            exts = setOf("gbc"), form = RomForm.PLAIN, romSize = 4, romSha1 = "ab".repeat(20), stagedRom = "",
            env = mapOf("GAME" to "it's gold", "ROM_PATH" to "{rom}"), args = listOf("--rom", "{rom}", "two words"),
        )
        assertEquals(
            "#!/bin/bash\nexport GAME='it'\\''s gold'\nexport ROM_PATH='/root/ROMs/gbc/Gold.gbc'\n" +
                "exec '/opt/droiddeck-ports/demo/bin/demo' '--rom' '/root/ROMs/gbc/Gold.gbc' 'two words' \"\$@\"\n",
            PortInstaller.starter(plain, "/root/ROMs/gbc/Gold.gbc"),
        )
        assertNull(PortInstaller.starter(plain, null))
        assertEquals("/opt/droiddeck-ports/demo/.droiddeck-start-demo", plain.program)
        val image = added(entry = Ports.APPIMAGE_ENTRY, staged = "", source = "o/r")
        assertEquals("#!/bin/bash\nexec /usr/local/bin/droiddeck-appimage-run '/opt/droiddeck-ports/demo' \"\$@\"\n", PortInstaller.starter(image, null))
        assertEquals("#!/bin/bash\nexec '/opt/droiddeck-ports/demo/bin/demo' \"\$@\"\n", PortInstaller.starter(added(entry = "bin/demo", staged = "", source = "o/r"), null))
        assertTrue(PortInstaller.isAppImage("Demo-aarch64.AppImage"))
        assertFalse(PortInstaller.isAppImage("Demo-arm64.zip"))
    }

    @Test fun anInstallRemembersItsProgramForEveryPortThatSharesIt() {
        val dir = tmp.newFolder("shared")
        val open = added(entry = "", staged = "", source = "o/r")
        assertNull(PortInstaller.installedTag(dir, open))
        File(dir, ".droiddeck-port").writeText("v1")
        File(dir, "app").mkdirs()
        File(dir, "app/AppRun").writeText("")
        File(dir, ".droiddeck-entry").writeText("app/AppRun")
        assertEquals("v1", PortInstaller.installedTag(dir, open))
        assertEquals("#!/bin/bash\nexec /usr/local/bin/droiddeck-appimage-run '/opt/droiddeck-ports/demo' \"\$@\"\n", PortInstaller.starter(open, null, PortInstaller.entry(dir, open)))
        val stale = added(entry = "Demo-aarch64.AppImage", staged = "", source = "o/r")
        File(dir, "Demo-aarch64.AppImage").writeText("")
        assertEquals("app/AppRun", PortInstaller.entry(dir, stale))
        File(dir, ".droiddeck-entry").delete()
        assertNull(PortInstaller.installedTag(dir, stale))
        assertNull(PortInstaller.installedTag(dir, added(entry = "bin/missing", staged = "", source = "o/r")))
        File(dir, ".droiddeck-entry").writeText("../outside")
        assertEquals("", PortInstaller.entry(dir, open))
    }

    @Test fun portsThatShareAnInstallEachGetTheirOwnRomUnderOneName() {
        val dir = tmp.newFolder("suite")
        fun game(id: String) = Port(
            id = id, name = "Demo", game = id, repo = "o/r", asset = Regex("arm64"), entry = "demo", systemFolders = listOf("snes"),
            exts = setOf("sfc"), form = RomForm.PLAIN, romSize = 4, romSha1 = "ab".repeat(20), stagedRom = "roms/base.sfc", markers = listOf("portable.txt"), tool = "suite",
        )
        val one = File(tmp.root, "one.sfc").apply { writeBytes(byteArrayOf(1, 1, 1, 1)) }
        val two = File(tmp.root, "two.sfc").apply { writeBytes(byteArrayOf(2, 2, 2, 2)) }
        assertFalse(PortInstaller.prepare(game("one"), null, dir))
        assertTrue(File(dir, "portable.txt").exists())
        assertTrue(PortInstaller.prepare(game("one"), one, dir))
        assertTrue(PortInstaller.hasStagedRom(dir, game("one")))
        assertFalse(PortInstaller.hasStagedRom(dir, game("two")))
        assertTrue(PortInstaller.prepare(game("two"), two, dir))
        assertTrue(File(dir, "roms/base.sfc").readBytes().contentEquals(byteArrayOf(2, 2, 2, 2)))
        assertTrue(PortInstaller.prepare(game("two"), null, dir))
        File(dir, "roms/base.sfc.droiddeck-owner").delete()
        assertTrue(PortInstaller.hasStagedRom(dir, game("one")))
    }

    @Test fun aZippedRomIsUnpackedForAPortThatReadsItItself() {
        val root = tmp.newFolder("ROMs")
        val bytes = byteArrayOf(9, 8, 7, 6)
        val zipped = File(root, "snes/My Game (USA).zip").apply {
            parentFile!!.mkdirs()
            ZipOutputStream(outputStream()).use { it.putNextEntry(ZipEntry("My Game (USA).sfc")); it.write(bytes); it.closeEntry() }
        }
        val open = added(entry = "", staged = "", source = "o/r")
        val dir = tmp.newFolder("unzipped")
        assertEquals("/opt/droiddeck-ports/demo/roms/My Game (USA).sfc", PortInstaller.romGuest(open, zipped, root))
        assertTrue(PortInstaller.prepare(open, zipped, dir))
        assertTrue(File(dir, "roms/My Game (USA).sfc").readBytes().contentEquals(bytes))
        assertFalse(File(dir, "roms/My Game (USA).sfc.part").exists())
        val plain = File(root, "snes/plain.sfc").apply { writeBytes(bytes) }
        assertEquals("/root/ROMs/snes/plain.sfc", PortInstaller.romGuest(open, plain, root))
        assertNull(PortInstaller.romGuest(open, null, root))
        assertNull(PortInstaller.romGuest(open, tmp.newFile("outside.sfc"), root))
        val staged = added(entry = "", staged = "g.sfc", source = "o/r")
        val other = tmp.newFolder("staged")
        assertTrue(PortInstaller.prepare(staged, zipped, other))
        assertTrue(File(other, "g.sfc").readBytes().contentEquals(bytes))
        assertFalse(File(other, "roms").exists())
        val junk = File(root, "snes/junk.zip").apply { writeBytes(ByteArray(30)) }
        assertFalse(PortInstaller.prepare(open, junk, tmp.newFolder("junk")))
    }

    @Test fun aPortWithNoStagedNameLeavesTheRomWhereItIs() {
        val open = added(entry = "", staged = "", source = "o/r")
        val dir = tmp.newFolder("open")
        assertTrue(PortInstaller.stageRom(open, File(dir, "missing.sfc"), dir))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }
}
