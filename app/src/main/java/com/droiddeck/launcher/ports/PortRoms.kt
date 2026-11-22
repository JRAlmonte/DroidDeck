package com.droiddeck.launcher.ports

import com.droiddeck.launcher.core.Hashes
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

object PortRoms {
    class HashCache(private val file: File?) {
        private class Row(val size: Long, val modified: Long, val sha1: String)
        private val rows = HashMap<String, Row>()
        private var dirty = false
        private val inners = HashMap<String, Inner?>()

        fun inner(zip: File): Inner? = inners.getOrPut(zip.path) { PortRoms.inner(zip) }

        init {
            runCatching { file?.readLines() }.getOrNull()?.forEach { line ->
                val p = line.split('\t', limit = 4)
                val size = p.getOrNull(0)?.toLongOrNull()
                val modified = p.getOrNull(1)?.toLongOrNull()
                if (p.size == 4 && size != null && modified != null) rows[p[3]] = Row(size, modified, p[2])
            }
        }

        fun sha1(rom: File, compute: (File) -> String?): String? {
            val known = rows[rom.path]
            if (known != null && known.size == rom.length() && known.modified == rom.lastModified()) return known.sha1
            val hash = compute(rom) ?: return null
            rows[rom.path] = Row(rom.length(), rom.lastModified(), hash)
            dirty = true
            return hash
        }

        fun save() {
            if (!dirty || file == null) return
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(rows.filterKeys { File(it).isFile }.entries.joinToString("\n") { (path, r) -> "${r.size}\t${r.modified}\t${r.sha1}\t$path" })
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            dirty = false
        }
    }

    /** The one file a zip holds. */
    class Inner(val name: String, val size: Long) {
        val ext: String get() = name.substringAfterLast('.', "").lowercase()
    }

    fun zipped(rom: File): Boolean = rom.extension.equals("zip", ignoreCase = true)

    /** The ROM in a zip: its only file. A zip holding several files is not taken for a ROM. */
    fun inner(zip: File): Inner? = runCatching {
        ZipFile(zip).use { z -> z.entries().asSequence().filter { !it.isDirectory }.take(2).toList().singleOrNull()?.let { Inner(it.name.substringAfterLast('/'), it.size) } }
    }.getOrNull()

    /** The ROM's bytes, read from inside a zip when it is zipped. */
    fun <T> read(rom: File, use: (InputStream) -> T): T =
        if (zipped(rom)) ZipFile(rom).use { z -> z.getInputStream(z.entries().asSequence().first { !it.isDirectory }).use(use) }
        else rom.inputStream().use(use)

    /** SHA-1 of the ROM in the form the port checks, or null when the file is not in that form at all. */
    fun sha1(port: Port, rom: File): String? = sha1(port.form, rom)

    fun sha1(form: RomForm, rom: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-1")
        val ok = read(rom) { input -> feed(form, input) { b, n -> digest.update(b, 0, n) } }
        if (ok) Hashes.hex(digest.digest()) else null
    }.getOrNull()

    class Facts(val folder: String, val exts: List<String>, val form: RomForm, val size: Long, val sha1: String, val md5: String = "")

    /** SHA-1 and MD5 of the ROM in the form a port checks, read in one pass. */
    fun digests(form: RomForm, rom: File): Pair<String, String>? = runCatching {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val md5 = MessageDigest.getInstance("MD5")
        val ok = read(rom) { input -> feed(form, input) { b, n -> sha1.update(b, 0, n); md5.update(b, 0, n) } }
        if (ok) Hashes.hex(sha1.digest()) to Hashes.hex(md5.digest()) else null
    }.getOrNull()

    private val N64_TYPES = listOf("z64", "n64", "v64")

    /** What a port entry says about a ROM, read from the file itself; null for a file outside a system folder. */
    fun facts(romsRoot: File, rom: File): Facts? {
        val inside = rom.path.removePrefix(romsRoot.path).trimStart(File.separatorChar)
        if (!rom.isFile || !rom.path.startsWith(romsRoot.path + File.separator) || File.separatorChar !in inside) return null
        val folder = inside.substringBefore(File.separatorChar).lowercase()
        val held = if (zipped(rom)) inner(rom) ?: return null else null
        val ext = held?.ext ?: rom.extension.lowercase()
        val n64 = ext in N64_TYPES && runCatching { read(rom) { i -> ByteArray(4).let { N64Rom.order(it.copyOf(maxOf(i.read(it), 0))) } } }.getOrNull() != null
        val form = if (n64) RomForm.N64 else RomForm.PLAIN
        val (sha1, md5) = digests(form, rom) ?: return null
        return Facts(folder, if (n64) N64_TYPES else listOf(ext), form, held?.size ?: rom.length(), sha1, md5)
    }

    fun stage(port: Port, rom: File, out: OutputStream): Boolean = read(rom) { input -> feed(port.form, input) { b, n -> out.write(b, 0, n) } }

    /** The ROM as it is, taken out of its zip. */
    fun copy(rom: File, out: OutputStream): Boolean = read(rom) { input -> feed(RomForm.PLAIN, input) { b, n -> out.write(b, 0, n) } }

    private fun fits(port: Port, rom: File, cache: HashCache): Boolean =
        if (zipped(rom)) cache.inner(rom)?.let { it.size == port.romSize && it.ext in port.exts } == true
        else rom.extension.lowercase() in port.exts && rom.length() == port.romSize

    private fun feed(form: RomForm, input: java.io.InputStream, sink: (ByteArray, Int) -> Unit): Boolean = when (form) {
        RomForm.N64 -> N64Rom.bigEndian(input, sink)
        RomForm.PLAIN -> {
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sink(buffer, n)
            }
            true
        }
    }

    fun candidates(romsRoot: File, port: Port, cache: HashCache = HashCache(null)): List<File> =
        romsRoot.listFiles { f -> f.isDirectory && f.name.lowercase() in port.systemFolders }.orEmpty().sortedBy { it.name }
            .flatMap { top -> top.walkTopDown().maxDepth(3).filter { it.isFile && fits(port, it, cache) }.sortedBy { it.path }.toList() }

    fun matches(port: Port, rom: File, cache: HashCache): Boolean =
        rom.isFile && (if (zipped(rom)) cache.inner(rom)?.size else rom.length()) == port.romSize && cache.sha1(rom) { sha1(port, it) } == port.romSha1

    /** The first ROM in the ROMs folder each port accepts. */
    fun scan(romsRoot: File?, ports: List<Port>, cache: HashCache): Map<String, File> {
        if (romsRoot == null) return emptyMap()
        val out = LinkedHashMap<String, File>()
        for (port in ports) candidates(romsRoot, port, cache).firstOrNull { matches(port, it, cache) }?.let { out[port.id] = it }
        cache.save()
        return out
    }
}
