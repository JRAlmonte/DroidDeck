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

    /** The files under [root], or null when anything there is a link: an archive's link may lead out of the folder it was unpacked in. */
    fun plainFiles(root: File): List<File>? {
        val found = ArrayList<File>()
        fun walk(dir: File): Boolean {
            for (f in dir.listFiles().orEmpty()) {
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) return false
                if (f.isDirectory) { if (!walk(f)) return false } else if (f.isFile) found += f
            }
            return true
        }
        return if (walk(root)) found else null
    }

    /** Deletes [root] and what is in it without following a link out of it. */
    fun remove(root: File) {
        if (!java.nio.file.Files.isSymbolicLink(root.toPath())) root.listFiles()?.forEach(::remove)
        root.delete()
    }

    private fun bare(name: String): String = name.replace('\\', '/').substringAfterLast('/')
}
