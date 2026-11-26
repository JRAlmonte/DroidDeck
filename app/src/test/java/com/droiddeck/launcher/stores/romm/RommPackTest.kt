package com.droiddeck.launcher.stores.romm

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RommPackTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun packed(): RommPacked {
        val home = tmp.newFolder()
        return RommPacked(File(home, "SAVEDATA"), File(home, ".droiddeck-saves"), "ULUS10336")
    }

    private fun save(p: RommPacked, folder: String, vararg files: Pair<String, String>) {
        for ((name, text) in files) File(p.source, "$folder/$name").apply { parentFile!!.mkdirs(); writeText(text) }
    }

    private fun zip(file: File, vararg entries: Pair<String, String>) {
        file.parentFile!!.mkdirs()
        ZipOutputStream(file.outputStream()).use { out -> for ((name, text) in entries) { out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry() } }
    }

    @Test fun aSaveFolderIsHashedTheWayRommHashesItsZip() {
        val p = packed()
        save(p, "ULUS10336DATA00", "PARAM.SFO" to "sfo", "DATA.BIN" to "progress")
        val expected = md5("ULUS10336DATA00/DATA.BIN:${md5("progress")}\nULUS10336DATA00/PARAM.SFO:${md5("sfo")}")
        assertEquals(expected, RommPack.folderHash(File(p.source, "ULUS10336DATA00")))
        RommPack.stage(p)
        assertEquals(expected, RommPack.zipHash(File(p.staged, "ULUS10336DATA00.zip")))
    }

    @Test fun onlyThisGamesFoldersAreZippedAndAnUnchangedSaveKeepsItsZip() {
        val p = packed()
        save(p, "ULUS10336DATA00", "PARAM.SFO" to "sfo", "DATA.BIN" to "progress")
        save(p, "ULUS10336DATA01", "PARAM.SFO" to "sfo")
        save(p, "ULES00151DATA00", "PARAM.SFO" to "other game")
        File(p.source, "ULUS10336EMPTY").mkdirs()
        val first = RommPack.stage(p)
        assertEquals(setOf("ULUS10336DATA00.zip", "ULUS10336DATA01.zip"), first.keys)
        ZipFile(File(p.staged, "ULUS10336DATA00.zip")).use { z -> assertEquals(listOf("ULUS10336DATA00/DATA.BIN", "ULUS10336DATA00/PARAM.SFO"), z.entries().asSequence().map { it.name }.toList()) }
        assertEquals(first, RommPack.stage(p))
        save(p, "ULUS10336DATA00", "DATA.BIN" to "more progress")
        val second = RommPack.stage(p)
        assertNotEquals(first["ULUS10336DATA00.zip"], second["ULUS10336DATA00.zip"])
        assertEquals(first["ULUS10336DATA01.zip"], second["ULUS10336DATA01.zip"])
    }

    @Test fun aZipTheDownloadReplacedIsPutBackAsItsFolder() {
        val p = packed()
        save(p, "ULUS10336DATA00", "PARAM.SFO" to "sfo", "DATA.BIN" to "here", "OLD.BIN" to "left over")
        val before = RommPack.stage(p)
        zip(File(p.staged, "ULUS10336DATA00.zip"), "ULUS10336DATA00/PARAM.SFO" to "sfo", "ULUS10336DATA00/DATA.BIN" to "from the server")
        zip(File(p.staged, "ULUS10336DATA07.zip"), "PARAM.SFO" to "flat", "SUB/X.BIN" to "x")
        assertEquals(setOf("ULUS10336DATA00", "ULUS10336DATA07"), RommPack.unstage(p, before).toSet())
        assertEquals("from the server", File(p.source, "ULUS10336DATA00/DATA.BIN").readText())
        assertFalse(File(p.source, "ULUS10336DATA00/OLD.BIN").exists())
        assertEquals("flat", File(p.source, "ULUS10336DATA07/PARAM.SFO").readText())
        assertEquals("x", File(p.source, "ULUS10336DATA07/SUB/X.BIN").readText())
        assertEquals(RommPack.stage(p), RommPack.stage(p))
        assertTrue(RommPack.unstage(p, RommPack.stage(p)).isEmpty())
    }

    @Test fun aSaveWrittenWhileNothingWasDownloadedIsNotReplacedByItsOlderZip() {
        val p = packed()
        save(p, "ULUS10336DATA00", "DATA.BIN" to "old")
        val before = RommPack.stage(p)
        save(p, "ULUS10336DATA00", "DATA.BIN" to "newer, written by the game")
        assertTrue(RommPack.unstage(p, before).isEmpty())
        assertEquals("newer, written by the game", File(p.source, "ULUS10336DATA00/DATA.BIN").readText())
    }

    @Test fun aZipThatWouldWriteOutsideItsFolderIsRefusedAndTheSaveKept() {
        val p = packed()
        save(p, "ULUS10336DATA00", "DATA.BIN" to "mine")
        val before = RommPack.stage(p)
        zip(File(p.staged, "ULUS10336DATA00.zip"), "../../evil.bin" to "x")
        assertTrue(runCatching { RommPack.unstage(p, before) }.isFailure)
        assertEquals("mine", File(p.source, "ULUS10336DATA00/DATA.BIN").readText())
        assertFalse(File(p.source.parentFile, "evil.bin").exists())
        zip(File(p.staged, "ULES00151DATA00.zip"), "DATA.BIN" to "another game")
        File(p.staged, "ULUS10336DATA00.zip").delete()
        assertTrue(RommPack.unstage(p, before).isEmpty())
        assertFalse(File(p.source, "ULES00151DATA00").exists())
    }
}
