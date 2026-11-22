package com.droiddeck.launcher.ports

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.GithubReleases
import com.droiddeck.launcher.runtime.AppImageManager
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
    private const val ENTRY_FILE = ".droiddeck-entry"
    private const val OWNER = ".droiddeck-owner"
    private val KEPT = listOf("saves")

    fun dir(context: Context, port: Port): File = File(LinuxRuntime.rootDir(context), Ports.GUEST_ROOT.removePrefix("/") + "/" + port.tool)

    /** The program of the install in [dir]: the one the port names, else the one the install found. */
    internal fun entry(dir: File, port: Port): String =
        port.entry.takeIf { it.isNotEmpty() && !isAppImage(it) }
            ?: runCatching { File(dir, ENTRY_FILE).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() && inside(dir, it) != null }
            ?: port.entry

    fun installedTag(context: Context, port: Port): String? = installedTag(dir(context, port), port)

    internal fun installedTag(dir: File, port: Port): String? {
        val entry = entry(dir, port)
        if (entry.isEmpty() || isAppImage(entry) || !present(File(dir, entry))) return null
        return runCatching { File(dir, TAG_FILE).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun present(file: File): Boolean = file.isFile || Files.isSymbolicLink(file.toPath())

    fun isAppImage(asset: String): Boolean = asset.endsWith(".appimage", ignoreCase = true)

    fun pickAsset(port: Port, names: List<String>): String? = names.firstOrNull { port.asset.containsMatchIn(it) }

    /** Why this download is not taken, as a string taking the port's name; every download must come with GitHub's hash. */
    fun refusal(port: Port, url: String, sha256: String?): Int? = when {
        !url.startsWith("https://github.com/") -> R.string.ports_no_build
        sha256 == null -> R.string.ports_unchecked
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
    fun install(context: Context, port: Port, rom: File, onProgress: (String, Int) -> Unit): String? {
        onProgress(context.getString(R.string.user_apps_github_checking, port.repo), -1)
        val releases = GithubReleases.list(port.repo) ?: return context.getString(R.string.user_apps_github_unreachable, port.repo)
        val release = GithubReleases.latest(releases) { pickAsset(port, it) } ?: return context.getString(R.string.ports_no_build, port.name)
        refusal(port, release.url, release.sha256)?.let { return context.getString(it, port.name) }
        val download = File(context.cacheDir, "port-${port.tool}-${release.asset}")
        val dir = dir(context, port)
        val fresh = File(dir.parentFile, ".${port.tool}.new")
        try {
            val label = context.getString(R.string.user_apps_downloading, release.asset)
            if (!Downloader.downloadFile(release.url, download, false, release.sha256) { f -> onProgress(label, if (f < 0) -1 else Math.round(f * 100f)) })
                return context.getString(R.string.user_apps_download_failed)
            onProgress(context.getString(R.string.ports_unpacking, port.name), -1)
            fresh.deleteRecursively()
            if (!fresh.mkdirs()) return context.getString(R.string.ports_unpack_failed, port.name)
            val program = if (isAppImage(release.asset)) {
                AppImageManager.unpackInto(context, fresh, "${Ports.GUEST_ROOT}/${fresh.name}", download) { onProgress(it, -1) }?.let { return it }
                Ports.APPIMAGE_ENTRY
            } else {
                unpack(download, fresh, release.asset)
                port.entry.ifEmpty { findProgram(fresh) ?: return context.getString(R.string.ports_no_program, port.name) }
            }
            val entry = File(fresh, program)
            if (!present(entry)) return context.getString(R.string.ports_unpack_failed, port.name)
            if (entry.isFile) entry.setExecutable(true, false)
            File(fresh, ENTRY_FILE).writeText(program)
            onProgress(context.getString(R.string.ports_staging), -1)
            if (!prepare(port, rom, fresh)) return context.getString(R.string.ports_wrong_rom, port.name)
            File(fresh, TAG_FILE).writeText(release.tag)
            if (!swap(dir, fresh)) return context.getString(R.string.ports_unpack_failed, port.name)
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

    private fun owner(dir: File, port: Port) = File(dir, port.stagedRom + OWNER)

    fun stageRom(port: Port, rom: File, dir: File): Boolean {
        if (port.stagedRom.isEmpty()) return true
        val staged = File(dir, port.stagedRom)
        val part = File(dir, port.stagedRom + ".part")
        val ok = runCatching { staged.parentFile?.mkdirs(); FileOutputStream(part).use { PortRoms.stage(port, rom, it) } }.getOrDefault(false)
        if (ok && (staged.delete() || !staged.exists()) && part.renameTo(staged)) {
            runCatching { owner(dir, port).writeText(port.id) }
            return true
        }
        part.delete()
        return false
    }

    /** What one port needs in the install folder it may share: its marker files and its copy of the ROM. False when the ROM cannot be staged. */
    fun prepare(port: Port, rom: File?, dir: File): Boolean {
        runCatching { port.markers.forEach { m -> File(dir, m).takeIf { !it.exists() }?.let { it.parentFile?.mkdirs(); it.writeText("") } } }
        return hasStagedRom(dir, port) || (rom != null && stageRom(port, rom, dir))
    }

    private fun quoted(text: String) = "'" + text.replace("'", "'\\''") + "'"

    /** The script a port starts through; [romGuest] is the ROM's path in the runtime. Null when the port's settings name a ROM and there is none. */
    internal fun starter(port: Port, romGuest: String?, entry: String = port.entry): String? {
        if (romGuest == null && port.usesRom) return null
        val filled = { text: String -> quoted(text.replace(Ports.ROM, romGuest.orEmpty())) }
        val exports = port.env.map { (name, value) -> "export $name=" + filled(value) + "\n" }.joinToString("")
        val run = if (entry == Ports.APPIMAGE_ENTRY) AppImageManager.LAUNCHER + " " + quoted(port.guestDir) else quoted("${port.guestDir}/$entry")
        return "#!/bin/bash\n" + exports + "exec $run" + port.args.joinToString("") { " " + filled(it) } + " \"\$@\"\n"
    }

    fun writeStarter(context: Context, port: Port, romGuest: String?): Boolean {
        val dir = dir(context, port)
        val text = starter(port, romGuest, entry(dir, port)) ?: return false
        val file = File(dir, port.starterName)
        return runCatching { file.writeText(text); file.setExecutable(true, false) }.getOrDefault(false)
    }

    /** Whether the port's ROM copy is in place; a copy another port of the same install made under that name does not count. */
    fun hasStagedRom(dir: File, port: Port): Boolean =
        port.stagedRom.isEmpty() || (File(dir, port.stagedRom).length() == port.romSize &&
            (runCatching { owner(dir, port).readText().trim() }.getOrNull() ?: port.id) == port.id)

    /** Removes the install, for every port that shares it, and keeps its saves for a later install. */
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
