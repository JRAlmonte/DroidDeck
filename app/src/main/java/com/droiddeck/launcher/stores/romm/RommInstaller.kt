package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.CoverArt
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.StoreArchiveUnpacker
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean

internal class RommInstaller(private val app: Context, private val item: CatalogItem) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var partial: File? = null

    private val runtimeUnpacker = StoreArchiveUnpacker(app, cancelled, "RomM", R.string.stores_romm_archive_runtime, R.string.stores_itch_extract_failed, "romm-unrar")

    private fun fail(message: Int, vararg args: Any): Nothing = throw IllegalStateException(app.getString(message, *args))

    override fun run(handle: DownloadQueue.JobHandle): String? {
        val server = RommAuth.server(app)
        val token = RommAuth.token(app)
        if (server.isEmpty() || token.isEmpty()) fail(R.string.stores_sync_sign_in)
        val root = RommInstalls.romsRoot(app) ?: fail(R.string.content_choose_roms)
        val slug = item.extra[RommCatalog.PLATFORM].orEmpty()
        val plain = fileName(item)
        val target = RommPlatforms.target(slug)?.takeIf { plain == null || RommPlatforms.playable(slug, plain) } ?: fail(R.string.stores_romm_unsupported)
        handle.stage(DownloadStage.MANIFEST)
        val dir = RommPlatforms.directory(root, target)
        if (!dir.isDirectory && !dir.mkdirs()) fail(R.string.stores_romm_no_write)
        val (main, kept) = when {
            plain != null -> one(server, token, dir, plain, null, item.sizeBytes, target, handle)
            RommCatalog.DISCS in item.extra -> discs(server, token, dir, target, handle)
            else -> many(server, token, dir, target, handle)
        } ?: return null
        fun relative(f: File) = f.relativeTo(root).path.replace(File.separatorChar, '/')
        cover(Library.Rom(item.title, main, "/root/ROMs/${relative(main)}", target.emulatorId))
        RommInstalls.record(app, RommInstalls.Entry(item.id, relative(main), target.emulatorId, relative(kept), item))
        StoresState.notifyLibraryChanged(app)
        return main.path
    }

    /** One file: kept as it is when the emulator opens it, unpacked when it is an archive the emulator does not open. */
    private fun one(
        server: String, token: String, dir: File, name: String, fileId: String?, size: Long, target: RommPlatforms.Target, handle: DownloadQueue.JobHandle,
        romId: String = item.id, before: Long = 0, total: Long = size, track: Boolean = true,
    ): Pair<File, File>? {
        val type = RommPlatforms.extension(name)
        val packed = type !in target.exts
        if (packed && type !in RommPlatforms.ARCHIVES) fail(R.string.stores_romm_file_type, type.uppercase())
        if (packed && type == "rar" && !LinuxRuntime.isInstalled(app)) fail(R.string.stores_romm_archive_runtime, type)
        val part = File(dir, ".romm-$romId.part").also { if (track) partial = it }
        handle.stage(DownloadStage.DOWNLOAD)
        download(RommApi.contentUrl(server, romId, name, fileId), token, part, handle, before, total, size)
        if (cancelled.get()) return null
        handle.stage(DownloadStage.INSTALL)
        if (!packed) {
            val file = File(dir, name)
            file.delete()
            if (!part.renameTo(file)) fail(R.string.stores_romm_no_write)
            return file to file
        }
        val folder = File(dir, name.substringBeforeLast('.'))
        return if (type == "rar") unpackInRuntime(part, name, folder, target.exts, handle) else unpack(part, type == "7z", folder, target.exts, handle)
    }

    /** Discs RomM holds as separate ROMs, installed into one folder with a playlist; a disc already there in full is kept. */
    private fun discs(server: String, token: String, dir: File, target: RommPlatforms.Target, handle: DownloadQueue.JobHandle): Pair<File, File>? {
        val folderName = item.extra[RommCatalog.FILE].orEmpty().takeUnless { it.isBlank() || it == "." || it == ".." || '/' in it || '\\' in it }
            ?: fail(R.string.stores_romm_no_files)
        val discs = RommCatalog.discs(item)
        if (discs.size < 2 || "m3u" !in target.exts) fail(R.string.stores_romm_unsupported)
        val folder = File(dir, folderName)
        val existed = folder.exists()
        if (!folder.isDirectory && !folder.mkdirs()) fail(R.string.stores_romm_no_write)
        if (!existed) partial = folder
        val total = discs.sumOf { it.size }
        var before = 0L
        val mains = ArrayList<File>()
        for (disc in discs) {
            val whole = File(folder, disc.path)
            mains += if (whole.isFile && disc.size > 0 && whole.length() == disc.size && RommPlatforms.extension(disc.path) in target.exts) whole
                else (one(server, token, folder, disc.path, null, disc.size, target, handle, disc.id, before, total, track = existed) ?: return null).first
            before += disc.size
        }
        handle.stage(DownloadStage.INSTALL)
        val sheet = File(folder, "$folderName.m3u")
        sheet.writeText(mains.joinToString("\n", postfix = "\n") { it.relativeTo(folder).path.replace(File.separatorChar, '/') })
        return sheet to folder
    }

    /** A ROM that RomM holds as a folder: each file is downloaded into a folder of the same name, and files already there are kept. */
    private fun many(server: String, token: String, dir: File, target: RommPlatforms.Target, handle: DownloadQueue.JobHandle): Pair<File, File>? {
        val folderName = item.extra[RommCatalog.FILE].orEmpty().takeUnless { it.isBlank() || it == "." || it == ".." || '/' in it || '\\' in it }
            ?: fail(R.string.stores_romm_no_files)
        val files = RommApi.rom(server, token, item.id).json?.let(RommCatalog::files).orEmpty()
        if (files.isEmpty()) fail(R.string.stores_romm_no_files)
        if (files.size == 1) return files.first().let { one(server, token, dir, it.path.substringAfterLast('/'), it.id, it.size, target, handle) }
        val names = files.map { it.path }
        val discs = RommPlatforms.discs(names, target.exts)
        val launch = if (discs.isNotEmpty()) "$folderName.m3u"
            else RommPlatforms.mainFile(names.filter { '/' !in it }, target.exts) ?: RommPlatforms.mainFile(names, target.exts) ?: fail(R.string.stores_romm_unsupported)
        val folder = File(dir, folderName)
        val existed = folder.exists()
        if (!folder.isDirectory && !folder.mkdirs()) fail(R.string.stores_romm_no_write)
        if (!existed) partial = folder
        handle.stage(DownloadStage.DOWNLOAD)
        val total = files.sumOf { it.size }
        var before = 0L
        for (f in files) {
            val out = File(folder, f.path)
            if (!(out.isFile && f.size > 0 && out.length() == f.size)) {
                out.parentFile?.mkdirs()
                val part = File(out.path + ".part")
                if (existed) partial = part
                download(RommApi.contentUrl(server, item.id, out.name, f.id), token, part, handle, before, total, f.size)
                if (cancelled.get()) return null
                out.delete()
                if (!part.renameTo(out)) fail(R.string.stores_romm_no_write)
            }
            before += f.size
        }
        handle.stage(DownloadStage.INSTALL)
        if (discs.isNotEmpty()) File(folder, launch).writeText(discs.joinToString("\n", postfix = "\n"))
        return File(folder, launch) to folder
    }

    /** Unpacks a rar with the runtime's bsdtar, then places its files like [unpack] does. */
    private fun unpackInRuntime(archive: File, name: String, folder: File, exts: Set<String>, handle: DownloadQueue.JobHandle): Pair<File, File>? {
        val work = File(folder.parentFile, ".romm-${item.id}.x")
        work.deleteRecursively()
        if (!work.mkdirs()) fail(R.string.stores_romm_no_write)
        try {
            runtimeUnpacker.work = work
            runtimeUnpacker.unpack(archive, name, handle)
            if (cancelled.get()) return null
            val files = work.walkTopDown().filter { it.isFile }.toList()
            val names = files.map { it.name }
            val launch = RommPlatforms.mainFile(names, exts) ?: fail(R.string.stores_romm_unsupported)
            if (names.toSet().size != names.size) fail(R.string.stores_romm_unsupported)
            val single = names.size == 1
            val into = if (single) folder.parentFile!! else folder
            if (!into.isDirectory && !into.mkdirs()) fail(R.string.stores_romm_no_write)
            for (f in files) {
                val to = File(into, f.name)
                to.delete()
                if (!f.renameTo(to)) fail(R.string.stores_romm_no_write)
            }
            archive.delete()
            val main = File(into, launch)
            return main to (if (single) main else folder)
        } finally {
            work.deleteRecursively()
        }
    }

    /** Unpacks a zip or 7z into [folder] and returns the file to launch and what to delete on uninstall; one lone file goes beside the folder instead. */
    private fun unpack(archive: File, sevenZ: Boolean, folder: File, exts: Set<String>, handle: DownloadQueue.JobHandle): Pair<File, File>? {
        val listing = RommArchive.listing(archive, sevenZ)
        val names = listing.names
        val total = listing.bytes
        val launch = RommPlatforms.mainFile(names, exts) ?: throw IllegalStateException(app.getString(R.string.stores_romm_unsupported))
        if (names.any { it.isEmpty() || it == "." || it == ".." || '\\' in it } || names.toSet().size != names.size) throw IllegalStateException(app.getString(R.string.stores_romm_unsupported))
        val single = names.size == 1
        val into = if (single) folder.parentFile!! else folder
        if (!into.isDirectory && !into.mkdirs()) throw IllegalStateException(app.getString(R.string.stores_romm_no_write))
        var done = 0L
        val buffer = ByteArray(128 * 1024)
        try {
            RommArchive.extract(archive, sevenZ) { name, input ->
                FileOutputStream(File(into, name)).use { output ->
                    while (true) {
                        if (cancelled.get()) throw java.io.InterruptedIOException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        handle.stageProgress(done, total, bytes = true)
                    }
                }
            }
        } catch (t: Throwable) {
            if (single) File(into, names.first()).delete() else folder.deleteRecursively()
            if (t is java.io.InterruptedIOException) return null
            throw t
        }
        archive.delete()
        val main = File(into, launch)
        return main to (if (single) main else folder)
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        if (deleteFiles) partial?.deleteRecursively()
    }

    /** Downloads one file, resuming its part; [before] and [total] place it in a download of several files. */
    private fun download(url: String, token: String, part: File, handle: DownloadQueue.JobHandle, before: Long, total: Long, expected: Long) {
        for (attempt in 0..1) {
            val have = part.length()
            var conn: HttpURLConnection? = null
            try {
                conn = RommApi.open(url, token).apply {
                    setRequestProperty("Accept-Encoding", "identity")
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = conn.responseCode
                if (code == 416 && attempt == 0) {
                    part.delete()
                    continue
                }
                if (code !in 200..299) throw IllegalStateException(app.getString(R.string.stores_romm_download_failed, code))
                val append = code == 206 && have > 0
                var done = if (append) have else 0L
                val size = conn.contentLengthLong.takeIf { it > 0 }?.let { if (append) it + have else it } ?: expected
                val all = if (total > size) total else before + size
                handle.progress(before + done, all)
                val buffer = ByteArray(128 * 1024)
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        while (true) {
                            if (cancelled.get()) return
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            handle.progress(before + done, all)
                        }
                    }
                }
                if (size > 0 && done < size) throw IllegalStateException(app.getString(R.string.stores_romm_download_failed, code))
                return
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    private fun cover(rom: Library.Rom) {
        val url = item.tallImageUrl ?: return
        var conn: HttpURLConnection? = null
        runCatching {
            conn = RommApi.open(url, "")
            if (conn!!.responseCode in 200..299) CoverArt.store(app, rom, conn!!.inputStream.use { it.readBytes() })
        }
        runCatching { conn?.disconnect() }
    }

    companion object {
        /** The name to save under, or null when the ROM is not one plain file. */
        fun fileName(item: CatalogItem): String? {
            if (item.extra[RommCatalog.SINGLE] != "1") return null
            val name = item.extra[RommCatalog.FILE].orEmpty()
            return name.takeUnless { it.isBlank() || it == "." || it == ".." || '/' in it || '\\' in it }
        }
    }
}
