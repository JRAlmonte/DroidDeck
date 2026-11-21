package com.droiddeck.launcher.ports

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.GithubReleases
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files

object PortInstaller {
    private const val TAG_FILE = ".droiddeck-port"
    private val KEPT = listOf("saves")

    fun dir(context: Context, port: Port): File = File(LinuxRuntime.rootDir(context), Ports.GUEST_ROOT.removePrefix("/") + "/" + port.id)

    fun installedTag(context: Context, port: Port): String? {
        val dir = dir(context, port)
        if (!File(dir, port.entry).isFile) return null
        return runCatching { File(dir, TAG_FILE).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun pickAsset(port: Port, names: List<String>): String? = names.firstOrNull { port.asset.containsMatchIn(it) }

    /** Why this download is not taken, as a string taking the port's name; a port the user added must come with GitHub's hash. */
    fun refusal(port: Port, url: String, sha256: String?): Int? = when {
        !url.startsWith("https://github.com/") -> R.string.ports_no_build
        port.source != null && sha256 == null -> R.string.ports_unchecked
        else -> null
    }

    private val LIBRARY = Regex("\\.so(\\.\\d+)*$")

    private fun arm64Program(file: File): Boolean = runCatching {
        val head = ByteArray(20)
        file.inputStream().use { it.read(head) } == 20 && head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() &&
            head[3] == 'F'.code.toByte() && head[4] == 2.toByte() && head[18] == 0xb7.toByte() && head[19] == 0.toByte()
    }.getOrDefault(false)

    /** The program of a port that does not name one: the ARM64 executable nearest the top, the largest of several. */
    fun findProgram(dir: File): String? =
        dir.walkTopDown().maxDepth(3)
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && !LIBRARY.containsMatchIn(it.name) && arm64Program(it) }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .sortedWith(compareBy<String> { p -> p.count { it == '/' } }.thenByDescending { File(dir, it).length() }.thenBy { it })
            .firstOrNull()

    /** Null on success, else the reason shown to the user. */
    fun install(context: Context, port: Port, rom: File, onProgram: (String) -> Unit = {}, onProgress: (String, Int) -> Unit): String? {
        onProgress(context.getString(R.string.user_apps_github_checking, port.repo), -1)
        val releases = GithubReleases.list(port.repo) ?: return context.getString(R.string.user_apps_github_unreachable, port.repo)
        val release = GithubReleases.latest(releases) { pickAsset(port, it) } ?: return context.getString(R.string.ports_no_build, port.name)
        refusal(port, release.url, release.sha256)?.let { return context.getString(it, port.name) }
        val download = File(context.cacheDir, "port-${port.id}-${release.asset}")
        val dir = dir(context, port)
        val fresh = File(dir.parentFile, ".${port.id}.new")
        try {
            val label = context.getString(R.string.user_apps_downloading, release.asset)
            if (!Downloader.downloadFile(release.url, download, false, release.sha256) { f -> onProgress(label, if (f < 0) -1 else Math.round(f * 100f)) })
                return context.getString(R.string.user_apps_download_failed)
            onProgress(context.getString(R.string.ports_unpacking, port.name), -1)
            fresh.deleteRecursively()
            if (!fresh.mkdirs()) return context.getString(R.string.ports_unpack_failed, port.name)
            unpack(download, fresh, release.asset)
            val program = port.entry.ifEmpty { findProgram(fresh) ?: return context.getString(R.string.ports_no_program, port.name) }
            val entry = File(fresh, program)
            if (!entry.isFile) return context.getString(R.string.ports_unpack_failed, port.name)
            entry.setExecutable(true, false)
            port.markers.forEach { File(fresh, it).writeText("") }
            onProgress(context.getString(R.string.ports_staging), -1)
            if (!stageRom(port, rom, fresh)) return context.getString(R.string.ports_wrong_rom, port.name)
            File(fresh, TAG_FILE).writeText(release.tag)
            if (!swap(dir, fresh)) return context.getString(R.string.ports_unpack_failed, port.name)
            if (port.entry.isEmpty()) onProgram(program)
            return null
        } catch (e: Exception) {
            return context.getString(R.string.ports_unpack_failed, port.name)
        } finally {
            download.delete()
            fresh.deleteRecursively()
        }
    }

    /** Puts the new install in place with the old one's saves; a failure leaves the old install as it was. */
    internal fun swap(dir: File, fresh: File): Boolean {
        val old = File(dir.parentFile, ".${dir.name}.old")
        if (!dir.exists() && old.exists()) old.renameTo(dir)
        old.deleteRecursively()
        if (dir.exists() && !dir.renameTo(old)) return false
        val moved = ArrayList<String>()
        val ok = KEPT.all { name ->
            val kept = File(old, name)
            if (!kept.exists()) return@all true
            File(fresh, name).deleteRecursively()
            kept.renameTo(File(fresh, name)).also { if (it) moved += name }
        } && fresh.renameTo(dir)
        if (ok) {
            old.deleteRecursively()
            return true
        }
        moved.forEach { File(fresh, it).renameTo(File(old, it)) }
        if (old.exists()) old.renameTo(dir)
        return false
    }

    fun stageRom(port: Port, rom: File, dir: File): Boolean {
        if (port.stagedRom.isEmpty()) return true
        val staged = File(dir, port.stagedRom)
        val part = File(dir, port.stagedRom + ".part")
        val ok = runCatching { FileOutputStream(part).use { PortRoms.stage(port, rom, it) } }.getOrDefault(false)
        if (ok && (staged.delete() || !staged.exists()) && part.renameTo(staged)) return true
        part.delete()
        return false
    }

    fun hasStagedRom(context: Context, port: Port): Boolean =
        port.stagedRom.isEmpty() || File(dir(context, port), port.stagedRom).length() == port.romSize

    /** Removes the port and keeps its saves for a later install. */
    fun remove(context: Context, port: Port) {
        val dir = dir(context, port)
        dir.listFiles()?.forEach { if (it.name !in KEPT) it.deleteRecursively() }
        if (dir.listFiles().isNullOrEmpty()) dir.delete()
    }

    internal fun unpack(archive: File, dest: File, name: String = archive.name) {
        val lower = name.lowercase()
        when {
            lower.endsWith(".zip") -> unzip(archive, dest)
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> archive.inputStream().use { untar(GzipCompressorInputStream(it), dest) }
            lower.endsWith(".tar.xz") -> archive.inputStream().use { untar(XZCompressorInputStream(it), dest) }
            else -> {
                val target = inside(dest, File(name).name) ?: return
                archive.copyTo(target, overwrite = true)
                target.setExecutable(true, false)
            }
        }
    }

    private fun unzip(archive: File, dest: File) {
        ZipFile.builder().setFile(archive).get().use { zip ->
            for (entry in zip.entries) {
                val name = entry.name
                if (entry.isDirectory) continue
                if (name.endsWith(".tar.gz", true) || name.endsWith(".tgz", true)) {
                    zip.getInputStream(entry).use { untar(GzipCompressorInputStream(it), dest) }
                    continue
                }
                val target = inside(dest, name) ?: continue
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> FileOutputStream(target).use { input.copyTo(it, 128 * 1024) } }
                if (entry.unixMode and 0x49 != 0) target.setExecutable(true, false)
            }
        }
    }

    private fun untar(stream: InputStream, dest: File) {
        TarArchiveInputStream(stream).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val target = inside(dest, entry.name) ?: continue
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        val to = entry.linkName
                        if (to.startsWith("/") || inside(dest, File(File(entry.name).parent ?: "", to).path) == null) continue
                        target.parentFile?.mkdirs()
                        target.delete()
                        Files.createSymbolicLink(target.toPath(), File(to).toPath())
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { tar.copyTo(it, 128 * 1024) }
                        if (entry.mode and 0x49 != 0) target.setExecutable(true, false)
                    }
                }
            }
        }
    }

    private fun inside(dest: File, name: String): File? {
        val file = File(dest, name.replace('\\', '/'))
        val root = dest.canonicalPath
        val path = file.canonicalPath
        return if (path == root || path.startsWith(root + File.separator)) file else null
    }
}
