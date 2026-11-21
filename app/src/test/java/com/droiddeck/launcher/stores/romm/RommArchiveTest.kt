package com.droiddeck.launcher.stores.romm

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RommArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val files = listOf("Game (Japan).gbc" to "rom-bytes", "extras/readme.txt" to "notes")

    private fun sevenZ(): File = tmp.newFile("game.7z").also { file ->
        SevenZOutputFile(file).use { out ->
            for ((name, text) in files) {
                out.putArchiveEntry(out.createArchiveEntry(File(name), name))
                out.write(text.toByteArray())
                out.closeArchiveEntry()
            }
        }
    }

    private fun zip(): File = tmp.newFile("game.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, text) in files) { out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry() }
        }
    }

    private fun read(archive: File, sevenZ: Boolean): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        RommArchive.extract(archive, sevenZ) { name, input -> found[name] = input.readBytes().decodeToString() }
        return found
    }

    @Test fun aSevenZipListsAndUnpacksItsFilesByBareName() {
        val archive = sevenZ()
        val listing = RommArchive.listing(archive, sevenZ = true)
        assertEquals(listOf("Game (Japan).gbc", "readme.txt"), listing.names)
        assertEquals(14L, listing.bytes)
        assertEquals(mapOf("Game (Japan).gbc" to "rom-bytes", "readme.txt" to "notes"), read(archive, sevenZ = true))
    }

    @Test fun aZipReadsTheSameWay() {
        val archive = zip()
        assertEquals(listOf("Game (Japan).gbc", "readme.txt"), RommArchive.listing(archive, sevenZ = false).names)
        assertEquals(mapOf("Game (Japan).gbc" to "rom-bytes", "readme.txt" to "notes"), read(archive, sevenZ = false))
    }
}
