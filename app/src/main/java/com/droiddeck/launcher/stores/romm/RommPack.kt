package com.droiddeck.launcher.stores.romm

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Saves an emulator keeps as a folder each. RomM holds a save as one file, so every folder under [source] whose name
 * starts with [prefix] is kept as a zip named after it under [staged], and the zips are what is synced.
 */
internal class RommPacked(val source: File, val staged: File, val prefix: String)

internal object RommPack {
    private const val MOST_BYTES = 256L * 1024 * 1024
    private val NAME = Regex("[A-Za-z0-9._ -]{1,96}")

    private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun md5(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // RomM's own hash of a zipped save: the MD5 of "name:md5" lines, one per file, sorted by name.
    private fun combined(parts: Map<String, String>): String = md5(parts.toSortedMap().map { "${it.key}:${it.value}" }.joinToString("\n").toByteArray())

    private fun files(folder: File): Map<String, File> =
        folder.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(folder).path.replace(File.separatorChar, '/') }

    /** What RomM would call the content hash of [folder] zipped under its own name. */
    fun folderHash(folder: File): String = combined(files(folder).map { (name, file) -> "${folder.name}/$name" to file.inputStream().use(::md5) }.toMap())

    fun zipHash(zip: File): String? = runCatching {
        ZipFile(zip).use { z -> combined(z.entries().asSequence().filter { !it.name.endsWith("/") }.associate { e -> e.name to z.getInputStream(e).use(::md5) }) }
    }.getOrNull()

    private fun mine(p: RommPacked, name: String): Boolean = name.startsWith(p.prefix) && NAME.matches(name)

    private fun write(folder: File, zip: File) {
        val tmp = File(zip.path + ".tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { out ->
            for ((name, file) in files(folder).toSortedMap()) {
                out.putNextEntry(ZipEntry("${folder.name}/$name").apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        zip.delete()
        if (!tmp.renameTo(zip)) throw java.io.IOException("could not keep ${zip.name}")
    }

    /**
     * Brings the zips in line with the save folders and returns each zip's MD5 as it then stands. A zip is written again
     * only when its folder's content changed, so an unchanged save keeps the same bytes and is not sent again.
     */
    fun stage(p: RommPacked): Map<String, String> {
        val folders = p.source.listFiles().orEmpty().filter { it.isDirectory && mine(p, it.name) }
        if (folders.isNotEmpty()) p.staged.mkdirs()
        for (folder in folders) {
            val kept = files(folder)
            if (kept.isEmpty() || kept.values.sumOf { it.length() } > MOST_BYTES) continue
            val zip = File(p.staged, folder.name + ".zip")
            if (!zip.isFile || zipHash(zip) != folderHash(folder)) write(folder, zip)
        }
        return p.staged.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".zip") && mine(p, it.name.removeSuffix(".zip")) }
            .associate { it.name to it.inputStream().use(::md5) }
    }

    private fun safe(name: String): Boolean = name.isNotEmpty() && !name.startsWith("/") && '\\' !in name && name.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun unpack(zip: File, folder: File) {
        val work = File(folder.parentFile, ".romm-${folder.name}.tmp")
        work.deleteRecursively()
        try {
            ZipFile(zip).use { z ->
                val entries = z.entries().asSequence().filter { !it.name.endsWith("/") }.toList()
                if (entries.isEmpty() || entries.any { !safe(it.name) } || entries.sumOf { it.size.coerceAtLeast(0) } > MOST_BYTES) throw java.io.IOException("${zip.name} is not a save RomM can hand back")
                val under = folder.name + "/"
                val nested = entries.all { it.name.startsWith(under) }
                for (e in entries) {
                    val out = File(work, if (nested) e.name.removePrefix(under) else e.name)
                    out.parentFile?.mkdirs()
                    z.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                    if (e.time > 0) out.setLastModified(e.time)
                }
            }
            folder.deleteRecursively()
            folder.parentFile?.mkdirs()
            if (!work.renameTo(folder)) throw java.io.IOException("could not put ${folder.name} back")
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Puts back the saves whose zip changed since [before] was read, which is what a download did. A zip left as it was is
     * never unpacked, so a save the emulator wrote meanwhile is not replaced by an older copy of itself.
     */
    fun unstage(p: RommPacked, before: Map<String, String>): List<String> {
        val put = ArrayList<String>()
        for (zip in p.staged.listFiles().orEmpty()) {
            val name = zip.name.removeSuffix(".zip")
            if (!zip.isFile || name == zip.name || !mine(p, name)) continue
            if (zip.inputStream().use(::md5) == before[zip.name]) continue
            unpack(zip, File(p.source, name))
            put += name
        }
        return put
    }
}
