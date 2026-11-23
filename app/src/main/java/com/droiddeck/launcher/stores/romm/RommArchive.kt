package com.droiddeck.launcher.stores.romm

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

internal object RommArchive {
    private val memoryKb: Int get() = (Runtime.getRuntime().maxMemory() / 4 / 1024).coerceIn(32L * 1024, Int.MAX_VALUE.toLong()).toInt()

    class Listing(val names: List<String>, val bytes: Long)

    fun listing(archive: File, sevenZ: Boolean): Listing {
        val names = ArrayList<String>()
        var bytes = 0L
        if (sevenZ) SevenZFile.builder().setFile(archive).setMaxMemoryLimitKb(memoryKb).get().use { z ->
            for (e in z.entries) if (!e.isDirectory) { names.add(bare(e.name)); bytes += e.size.coerceAtLeast(0) }
        } else ZipFile(archive).use { z ->
            for (e in z.entries()) if (!e.isDirectory) { names.add(bare(e.name)); bytes += e.size.coerceAtLeast(0) }
        }
        return Listing(names, bytes)
    }

    fun extract(archive: File, sevenZ: Boolean, each: (name: String, input: InputStream) -> Unit) {
        if (sevenZ) SevenZFile.builder().setFile(archive).setMaxMemoryLimitKb(memoryKb).get().use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) z.getInputStream(e).use { each(bare(e.name), it) }
            }
        } else ZipFile(archive).use { z ->
            for (e in z.entries()) if (!e.isDirectory) z.getInputStream(e).use { each(bare(e.name), it) }
        }
    }

    private fun bare(name: String): String = name.replace('\\', '/').substringAfterLast('/')
}
