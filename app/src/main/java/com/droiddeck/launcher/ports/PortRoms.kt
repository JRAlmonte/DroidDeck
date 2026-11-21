package com.droiddeck.launcher.ports

import com.droiddeck.launcher.core.Hashes
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

object PortRoms {
    class HashCache(private val file: File?) {
        private class Row(val size: Long, val modified: Long, val sha1: String)
        private val rows = HashMap<String, Row>()
        private var dirty = false

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

    /** SHA-1 of the ROM in the form the port checks, or null when the file is not in that form at all. */
    fun sha1(port: Port, rom: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-1")
        val ok = rom.inputStream().use { input -> feed(port, input) { b, n -> digest.update(b, 0, n) } }
        if (ok) Hashes.hex(digest.digest()) else null
    }.getOrNull()

    fun stage(port: Port, rom: File, out: OutputStream): Boolean = rom.inputStream().use { input -> feed(port, input) { b, n -> out.write(b, 0, n) } }

    private fun feed(port: Port, input: java.io.InputStream, sink: (ByteArray, Int) -> Unit): Boolean = when (port.form) {
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

    fun candidates(romsRoot: File, port: Port): List<File> =
        romsRoot.listFiles { f -> f.isDirectory && f.name.lowercase() in port.systemFolders }.orEmpty().sortedBy { it.name }
            .flatMap { top -> top.walkTopDown().maxDepth(3).filter { it.isFile && it.extension.lowercase() in port.exts && it.length() == port.romSize }.sortedBy { it.path }.toList() }

    fun matches(port: Port, rom: File, cache: HashCache): Boolean =
        rom.isFile && rom.length() == port.romSize && cache.sha1(rom) { sha1(port, it) } == port.romSha1

    /** The first ROM in the ROMs folder each port accepts. */
    fun scan(romsRoot: File?, ports: List<Port>, cache: HashCache): Map<String, File> {
        if (romsRoot == null) return emptyMap()
        val out = LinkedHashMap<String, File>()
        for (port in ports) candidates(romsRoot, port).firstOrNull { matches(port, it, cache) }?.let { out[port.id] = it }
        cache.save()
        return out
    }
}
