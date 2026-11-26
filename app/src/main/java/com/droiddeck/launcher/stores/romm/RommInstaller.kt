package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.frontend.CoverArt
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.StoreArchiveUnpacker
import com.droiddeck.launcher.stores.StoreInstallRoot
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
    private lateinit var roms: File
    private val written = ArrayList<File>()

    private fun relative(f: File) = f.relativeTo(roms).path.replace(File.separatorChar, '/')

    // The server names the files and folders of a game, so a name that is already in the ROMs folder may be the user's own.
    private fun free(target: File) {
        if (target.exists() && !RommInstalls.owns(app, item.id, relative(target))) fail(R.string.stores_romm_in_the_way, target.name)
    }

    /** Takes [folder] for this game and returns whether no installed game is recorded in it yet, so it is this install's to remove. */
    private fun own(folder: File): Boolean {
        val path = relative(folder)
        free(folder)
        val existed = folder.exists()
        if (!folder.isDirectory && !folder.mkdirs()) fail(R.string.stores_romm_no_write)
        if (!existed) RommInstalls.claim(app, item.id, path)
        return !RommInstalls.recorded(app, path)
    }

    private val runtimeUnpacker = StoreArchiveUnpacker(app, cancelled, "RomM", R.string.stores_romm_archive_runtime, R.string.stores_itch_extract_failed, "romm-unrar")

    private fun fail(message: Int, vararg args: Any): Nothing = throw IllegalStateException(app.getString(message, *args))

    override fun run(handle: DownloadQueue.JobHandle): String? {
        val done = try {
            install(handle)
        } catch (t: Throwable) {
            // Only a message of the installer's own goes back: another one can name files and folders on the device.
            RommSent.finished(app, item.id, (t as? IllegalStateException)?.message ?: "The install failed on the device")
            throw t
        }
        if (done != null) RommSent.finished(app, item.id, null)
        return done
    }

    private fun install(handle: DownloadQueue.JobHandle): String? {
        val (server, token) = RommAuth.both(app)
        if (server.isEmpty() || token.isEmpty()) fail(R.string.stores_sync_sign_in)
        val root = RommInstalls.romsRoot(app) ?: fail(R.string.content_choose_roms)
        roms = root
        written.clear()
        val slug = item.extra[RommCatalog.PLATFORM].orEmpty()
        if (!RommPlatforms.safe(slug)) fail(R.string.stores_romm_platform_folder)
        val plain = fileName(item)
        val target = RommPlatforms.target(slug)?.takeIf { plain == null || RommPlatforms.playable(slug, plain) } ?: RommPlatforms.held(slug)
        handle.stage(DownloadStage.MANIFEST)
        val dir = RommPlatforms.directory(root, target)
        if (!dir.isDirectory && !dir.mkdirs()) fail(R.string.stores_romm_no_write)
        val (main, kept) = when {
            plain != null -> one(server, token, dir, plain, null, item.sizeBytes, target, handle, sha1 = item.extra[RommCatalog.SHA1].orEmpty())
            RommCatalog.DISCS in item.extra -> discs(server, token, dir, target, handle)
            else -> many(server, token, dir, target, handle)
        } ?: return null
        cover(Library.Rom(item.title, main, "/root/ROMs/${relative(main)}", target.emulatorId))
        RommInstalls.record(app, RommInstalls.Entry(item.id, relative(main), target.emulatorId, relative(kept), item), written.map(::relative).takeIf { kept.isDirectory })
        StoresState.notifyLibraryChanged(app)
        RommDetails.keepRead(app, item.id)
        return main.path
    }

    private fun one(
        server: String, token: String, dir: File, name: String, fileId: String?, size: Long, target: RommPlatforms.Target, handle: DownloadQueue.JobHandle,
        romId: String = item.id, before: Long = 0, total: Long = size, track: Boolean = true, sha1: String = "", ours: Boolean = false,
    ): Pair<File, File>? {
        val type = RommPlatforms.extension(name)
        val packed = !target.held && type !in target.exts
        if (packed && type !in RommPlatforms.ARCHIVES) fail(R.string.stores_romm_file_type, type.uppercase())
        if (packed && type == "rar" && !LinuxRuntime.isInstalled(app)) fail(R.string.stores_romm_archive_runtime, type)
        if (!packed && !ours) free(File(dir, name))
        val part = File(dir, ".romm-$romId.part").also { if (track) partial = it }
        handle.stage(DownloadStage.DOWNLOAD)
        val hashed = download(RommApi.contentUrl(server, romId, name, fileId), token, part, handle, before, total, size, hash = !packed && SHA1.matches(sha1))
        if (cancelled.get()) return null
        handle.stage(DownloadStage.INSTALL)
        if (!packed) {
            checked(part, type, sha1, hashed)
            val file = File(dir, name)
            if (!ours) free(file)
            file.delete()
            if (!part.renameTo(file)) fail(R.string.stores_romm_no_write)
            written += file
            return file to file
        }
        val folder = File(dir, name.substringBeforeLast('.'))
        return if (type == "rar") unpackInRuntime(part, name, folder, target.exts, handle, ours) else unpack(part, type == "7z", folder, target.exts, handle, ours)
    }

    private fun discs(server: String, token: String, dir: File, target: RommPlatforms.Target, handle: DownloadQueue.JobHandle): Pair<File, File>? {
        val folderName = item.extra[RommCatalog.FILE].orEmpty().takeIf(RommCatalog::plainName)
            ?: fail(R.string.stores_romm_no_files)
        val discs = RommCatalog.discs(item)
        if (discs.size < 2 || (!target.held && "m3u" !in target.exts)) fail(R.string.stores_romm_unsupported)
        val folder = File(dir, folderName)
        val fresh = own(folder)
        if (fresh) partial = folder
        val total = discs.sumOf { it.size }
        var before = 0L
        val mains = ArrayList<File>()
        for (disc in discs) {
            val whole = File(folder, disc.path)
            mains += if (whole.isFile && disc.size > 0 && whole.length() == disc.size && (target.held || RommPlatforms.extension(disc.path) in target.exts)) whole.also { written += it }
                else (one(server, token, folder, disc.path, null, disc.size, target, handle, disc.id, before, total, track = !fresh, sha1 = disc.sha1, ours = true) ?: return null).first
            before += disc.size
        }
        handle.stage(DownloadStage.INSTALL)
        if (target.held) return folder to folder
        val sheet = File(folder, "$folderName.m3u")
        sheet.writeText(mains.joinToString("\n", postfix = "\n") { it.relativeTo(folder).path.replace(File.separatorChar, '/') })
        written += sheet
        return sheet to folder
    }

    private fun many(server: String, token: String, dir: File, target: RommPlatforms.Target, handle: DownloadQueue.JobHandle): Pair<File, File>? {
        val folderName = item.extra[RommCatalog.FILE].orEmpty().takeIf(RommCatalog::plainName)
            ?: fail(R.string.stores_romm_no_files)
        val files = RommApi.rom(server, token, item.id).json?.let(RommCatalog::files).orEmpty()
        if (files.isEmpty()) fail(R.string.stores_romm_no_files)
        if (files.size == 1) return files.first().let { one(server, token, dir, it.path.substringAfterLast('/'), it.id, it.size, target, handle, sha1 = it.sha1) }
        val names = files.map { it.path }
        val discs = if (target.held) emptyList() else RommPlatforms.discs(names, target.exts)
        val launch = if (target.held) null else if (discs.isNotEmpty()) "$folderName.m3u"
            else RommPlatforms.mainFile(names.filter { '/' !in it }, target.exts) ?: RommPlatforms.mainFile(names, target.exts) ?: fail(R.string.stores_romm_unsupported)
        val folder = File(dir, folderName)
        val fresh = own(folder)
        if (fresh) partial = folder
        handle.stage(DownloadStage.DOWNLOAD)
        val total = files.sumOf { it.size }
        var before = 0L
        for (f in files) {
            val out = File(folder, f.path)
            if (!(out.isFile && f.size > 0 && out.length() == f.size)) {
                out.parentFile?.mkdirs()
                val part = File(out.path + ".part")
                if (!fresh) partial = part
                val hashed = download(RommApi.contentUrl(server, item.id, out.name, f.id), token, part, handle, before, total, f.size, hash = SHA1.matches(f.sha1))
                if (cancelled.get()) return null
                checked(part, RommPlatforms.extension(out.name), f.sha1, hashed)
                out.delete()
                if (!part.renameTo(out)) fail(R.string.stores_romm_no_write)
            }
            written += out
            before += f.size
        }
        handle.stage(DownloadStage.INSTALL)
        if (launch == null) return folder to folder
        if (discs.isNotEmpty()) File(folder, launch).also { written += it }.writeText(discs.joinToString("\n", postfix = "\n"))
        return File(folder, launch) to folder
    }

    /** [hashed] is the file's SHA-1 as it was worked out while it came; without one the file is read again. */
    private fun checked(part: File, type: String, sha1: String, hashed: String?) {
        if (!SHA1.matches(sha1) || !hashedWhole(part, type) || (hashed ?: Hashes.sha1(part)).equals(sha1, ignoreCase = true)) return
        part.delete()
        fail(R.string.stores_romm_not_as_recorded)
    }

    /** Unpacks a rar with the runtime's bsdtar, then places its files like [unpack] does. */
    private fun unpackInRuntime(archive: File, name: String, folder: File, exts: Set<String>, handle: DownloadQueue.JobHandle, ours: Boolean): Pair<File, File>? {
        val work = File(folder.parentFile, ".romm-${item.id}.x")
        RommArchive.remove(work)
        if (!work.mkdirs()) fail(R.string.stores_romm_no_write)
        try {
            runtimeUnpacker.work = work
            runtimeUnpacker.limit = StoreInstallRoot.availableBytes(work).takeIf { it > 0 } ?: Long.MAX_VALUE
            try {
                runtimeUnpacker.unpack(archive, name, handle)
            } catch (e: StoreArchiveUnpacker.TooLarge) {
                fail(R.string.stores_romm_no_room)
            }
            if (cancelled.get()) return null
            // A link in an archive can point anywhere on the device, and following one would move or delete what is there.
            val files = RommArchive.plainFiles(work) ?: fail(R.string.stores_romm_unsupported)
            if (files.size > MOST_ENTRIES) fail(R.string.stores_romm_unsupported)
            val names = files.map { it.name }
            val launch = RommPlatforms.mainFile(names, exts) ?: fail(R.string.stores_romm_unsupported)
            if (names.toSet().size != names.size || names.any { !RommCatalog.plainName(it) }) fail(R.string.stores_romm_unsupported)
            val single = names.size == 1
            val into = if (single) folder.parentFile!! else folder
            if (!ours) { if (single) free(File(into, launch)) else own(folder) }
            if (!into.isDirectory && !into.mkdirs()) fail(R.string.stores_romm_no_write)
            for (f in files) {
                val to = File(into, f.name)
                to.delete()
                if (!f.renameTo(to)) fail(R.string.stores_romm_no_write)
                written += to
            }
            archive.delete()
            val main = File(into, launch)
            return main to (if (single) main else folder)
        } finally {
            RommArchive.remove(work)
        }
    }

    private fun room(dir: File, bytes: Long) {
        val free = StoreInstallRoot.availableBytes(dir)
        if (free > 0 && bytes > free) fail(R.string.stores_romm_no_room)
    }

    private fun unpack(archive: File, sevenZ: Boolean, folder: File, exts: Set<String>, handle: DownloadQueue.JobHandle, ours: Boolean): Pair<File, File>? {
        val listing = RommArchive.listing(archive, sevenZ)
        val names = listing.names
        val total = listing.bytes
        if (names.size > MOST_ENTRIES) fail(R.string.stores_romm_unsupported)
        room(folder.parentFile!!, total)
        val launch = RommPlatforms.mainFile(names, exts) ?: throw IllegalStateException(app.getString(R.string.stores_romm_unsupported))
        if (names.any { !RommCatalog.plainName(it) } || names.toSet().size != names.size) throw IllegalStateException(app.getString(R.string.stores_romm_unsupported))
        val single = names.size == 1
        val into = if (single) folder.parentFile!! else folder
        if (!ours) { if (single) free(File(into, launch)) else own(folder) }
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
                        done += read
                        // An archive that unpacks to more than it lists is not what it says it is.
                        if (done > total) throw IllegalStateException(app.getString(R.string.stores_romm_not_as_recorded))
                        output.write(buffer, 0, read)
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
        written += names.map { File(into, it) }
        val main = File(into, launch)
        return main to (if (single) main else folder)
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        if (deleteFiles) {
            partial?.deleteRecursively()
            if (RommInstalls.find(app, item.id) == null) RommInstalls.unclaim(app, item.id)
            RommSent.finished(app, item.id, "Cancelled on the device")
        }
    }

    /**
     * Downloads to [part], carrying on from what is there. With [hash] it returns the whole file's SHA-1, worked out as the
     * bytes pass so a large file is not read a second time to check it; null when nothing new came or the install was cancelled.
     */
    private fun download(url: String, token: String, part: File, handle: DownloadQueue.JobHandle, before: Long, total: Long, expected: Long, hash: Boolean = false): String? {
        var dropped = 0
        while (true) {
            try {
                return downloadOnce(url, token, part, handle, before, total, expected, hash)
            } catch (e: java.io.IOException) {
                // A connection that drops is carried on from what came, a few times, before the install is called failed.
                if (cancelled.get() || ++dropped > RETRIES) throw if (e is CameShort) IllegalStateException(app.getString(R.string.stores_romm_download_failed, e.code)) else e
                handle.log("RomM download: ${e.javaClass.simpleName}, carrying on from ${part.length()} bytes ($dropped of $RETRIES)")
                Thread.sleep(retryMs * dropped)
            }
        }
    }

    private fun downloadOnce(url: String, token: String, part: File, handle: DownloadQueue.JobHandle, before: Long, total: Long, expected: Long, hash: Boolean): String? {
        for (attempt in 0..1) {
            val have = part.length()
            if (expected > 0 && have == expected) {
                handle.progress(before + have, maxOf(total, before + have))
                return null
            }
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
                val digest = if (hash) java.security.MessageDigest.getInstance("SHA-1") else null
                val buffer = ByteArray(128 * 1024)
                if (append && digest != null) part.inputStream().use { kept ->
                    while (true) { val read = kept.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
                }
                var done = if (append) have else 0L
                val size = conn.contentLengthLong.takeIf { it > 0 }?.let { if (append) it + have else it } ?: expected
                val all = if (total > size) total else before + size
                handle.progress(before + done, all)
                var told = done
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        while (true) {
                            if (cancelled.get()) return null
                            val read = input.read(buffer)
                            if (read < 0) break
                            done += read
                            if (size > 0 && done > size) throw IllegalStateException(app.getString(R.string.stores_romm_download_failed, code))
                            out.write(buffer, 0, read)
                            digest?.update(buffer, 0, read)
                            if (done - told >= PROGRESS_EVERY) { told = done; handle.progress(before + done, all) }
                        }
                    }
                }
                handle.progress(before + done, all)
                if (size > 0 && done < size) throw CameShort(code)
                return digest?.let { Hashes.hex(it.digest()) }
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        return null
    }

    private fun cover(rom: Library.Rom) {
        val image = RommApi.picture(item.tallImageUrl ?: return).second?.takeIf { it.isNotEmpty() } ?: return
        runCatching { CoverArt.store(app, rom, image) }
    }

    /** The answer ended before the file did, which is how a connection dropped on the way shows. */
    private class CameShort(val code: Int) : java.io.IOException()

    companion object {
        private val SHA1 = Regex("[0-9a-fA-F]{40}")
        private val HASHED_INSIDE = setOf("zip", "tar", "gz", "7z", "bz2")
        private const val MOST_ENTRIES = 20_000
        private const val RETRIES = 3
        internal var retryMs = 2_000L
        private const val PROGRESS_EVERY = 256 * 1024

        // RomM hashes what is inside a zip, tar, gzip, 7z or bzip2 file, told by its name or its first bytes, and any other file as it is.
        internal fun hashedWhole(file: File, type: String): Boolean {
            if (type.lowercase() in HASHED_INSIDE) return false
            val head = ByteArray(262)
            var read = 0
            file.inputStream().use { input -> while (read < head.size) { val n = input.read(head, read, head.size - read); if (n < 0) break; read += n } }
            fun starts(vararg bytes: Int) = read >= bytes.size && bytes.indices.all { head[it].toInt() and 0xff == bytes[it] }
            val tar = read >= 262 && String(head, 257, 5, Charsets.ISO_8859_1) == "ustar"
            return !(starts(0x50, 0x4B, 0x03, 0x04) || starts(0x1F, 0x8B) || starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) || starts(0x42, 0x5A, 0x68) || tar)
        }

        fun fileName(item: CatalogItem): String? {
            if (item.extra[RommCatalog.SINGLE] != "1") return null
            val name = item.extra[RommCatalog.FILE].orEmpty()
            return name.takeIf(RommCatalog::plainName)
        }
    }
}
