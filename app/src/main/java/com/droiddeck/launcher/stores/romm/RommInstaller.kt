package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.CoverArt
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.CatalogItem
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

    override fun run(handle: DownloadQueue.JobHandle): String? {
        val server = RommAuth.server(app)
        val token = RommAuth.token(app)
        if (server.isEmpty() || token.isEmpty()) throw IllegalStateException(app.getString(R.string.stores_sync_sign_in))
        val root = RommInstalls.romsRoot(app) ?: throw IllegalStateException(app.getString(R.string.content_choose_roms))
        val name = fileName(item) ?: throw IllegalStateException(app.getString(R.string.stores_romm_multi_file))
        val target = RommPlatforms.target(item.extra[RommCatalog.PLATFORM].orEmpty())?.takeIf { RommPlatforms.playable(item.extra[RommCatalog.PLATFORM].orEmpty(), name) }
            ?: throw IllegalStateException(app.getString(R.string.stores_romm_unsupported))
        handle.stage(DownloadStage.MANIFEST)
        val dir = RommPlatforms.directory(root, target)
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException(app.getString(R.string.stores_romm_no_write))
        val part = File(dir, ".romm-${item.id}.part").also { partial = it }
        handle.stage(DownloadStage.DOWNLOAD)
        download(RommApi.contentUrl(server, item.id, name), token, part, handle)
        if (cancelled.get()) return null
        handle.stage(DownloadStage.INSTALL)
        val packed = RommPlatforms.extension(name) !in target.exts
        val (main, kept) = if (packed) unpack(part, RommPlatforms.extension(name) == "7z", File(dir, name.substringBeforeLast('.')), target.exts, handle) ?: return null else {
            val file = File(dir, name)
            file.delete()
            if (!part.renameTo(file)) throw IllegalStateException(app.getString(R.string.stores_romm_no_write))
            file to file
        }
        fun relative(f: File) = f.relativeTo(root).path.replace(File.separatorChar, '/')
        cover(Library.Rom(item.title, main, "/root/ROMs/${relative(main)}", target.emulatorId))
        RommInstalls.record(app, RommInstalls.Entry(item.id, relative(main), target.emulatorId, relative(kept)))
        StoresState.notifyLibraryChanged(app)
        return main.path
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
        if (deleteFiles) partial?.delete()
    }

    private fun download(url: String, token: String, part: File, handle: DownloadQueue.JobHandle) {
        val expected = item.sizeBytes
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
                val total = conn.contentLengthLong.takeIf { it > 0 }?.let { if (append) it + have else it } ?: expected
                handle.progress(done, total)
                val buffer = ByteArray(128 * 1024)
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        while (true) {
                            if (cancelled.get()) return
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            handle.progress(done, total)
                        }
                    }
                }
                if (total > 0 && done < total) throw IllegalStateException(app.getString(R.string.stores_romm_download_failed, code))
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
