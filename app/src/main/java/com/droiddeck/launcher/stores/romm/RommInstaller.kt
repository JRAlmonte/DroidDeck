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
import java.util.concurrent.atomic.AtomicBoolean

/** With [adding], the job brings those extra files to the game as it is installed and installs nothing else. */
internal class RommInstaller(private val app: Context, private val item: CatalogItem, private val adding: List<RommFile>? = null) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)
    // Set when one of several files coming at once fails, so the others stop where they are and keep what came.
    private val halted = AtomicBoolean(false)
    @Volatile private var partial: File? = null
    private val parts: MutableSet<File> = java.util.concurrent.ConcurrentHashMap.newKeySet()
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
        parts.clear()
        halted.set(false)
        if (adding != null) return add(server, token, adding, handle)
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
        val hashed = download(RommApi.contentUrl(server, romId, name, fileId), token, part, handle, size, hash = !packed && SHA1.matches(sha1)) { done, all ->
            handle.progress(before + done, maxOf(total, before + all))
        }
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
        val listed = RommDetails.takeFiles(item.id) ?: RommApi.rom(server, token, item.id).json?.let(RommCatalog::fileSet) ?: fail(R.string.stores_romm_no_files)
        val game = listed.own
        if (game.isEmpty()) fail(R.string.stores_romm_no_files)
        val chosen = RommCatalog.chosenExtras(item)
        if (chosen.any { id -> listed.extras.none { it.id == id } }) fail(R.string.stores_romm_extra_gone)
        val folder = File(dir, folderName)
        fun whole(f: RommFile) = File(folder, f.path).let { it.isFile && f.size > 0 && it.length() == f.size }
        // A folder an install of this game already brought files into is carried on in, with the extra files that came whole,
        // so that an install tried again without its choice still records them and removing the game takes them along.
        val begun = folder.isDirectory && RommInstalls.owns(app, item.id, relative(folder)) &&
            (game + listed.extras).any { f -> File(folder, f.path).isFile || File(folder, f.path + ".part").isFile }
        val (extras, left) = listed.extras.partition { it.id in chosen || (begun && whole(it)) }
        if (game.size == 1 && extras.isEmpty() && !begun) return game.first().let { one(server, token, dir, it.path.substringAfterLast('/'), it.id, it.size, target, handle, sha1 = it.sha1) }
        val files = game + extras
        // Only the game's own files can be what the emulator opens: an update or a hack may have the same type.
        val names = game.map { it.path }
        val discs = if (target.held) emptyList() else RommPlatforms.discs(names, target.exts)
        val launch = if (target.held) null else if (discs.isNotEmpty()) "$folderName.m3u"
            else RommPlatforms.mainFile(names.filter { '/' !in it }, target.exts) ?: RommPlatforms.mainFile(names, target.exts) ?: fail(R.string.stores_romm_unsupported)
        val fresh = own(folder)
        if (begun) left.forEach { File(folder, it.path + ".part").delete() }
        if (fresh) partial = folder
        handle.stage(DownloadStage.DOWNLOAD)
        val total = files.sumOf { it.size }
        val (there, wanted) = files.partition(::whole)
        val came = java.util.concurrent.atomic.AtomicLong(there.sumOf { it.size })
        handle.progress(came.get(), total)
        if (wanted.isNotEmpty()) several(server, token, folder, wanted, fresh, handle) { moved -> handle.progress(came.addAndGet(moved), maxOf(total, came.get())) }
        if (cancelled.get()) return null
        written += files.map { File(folder, it.path) }
        handle.stage(DownloadStage.INSTALL)
        if (launch == null) return folder to folder
        if (discs.isNotEmpty()) File(folder, launch).also { written += it }.writeText(discs.joinToString("\n", postfix = "\n"))
        return File(folder, launch) to folder
    }

    /**
     * Brings [files], extras RomM keeps with the game, into the folder the game is installed in and lists them with its
     * record. The game's own files are left as they are.
     */
    private fun add(server: String, token: String, files: List<RommFile>, handle: DownloadQueue.JobHandle): String? {
        handle.stage(DownloadStage.MANIFEST)
        val entry = RommInstalls.onRecord(app, item.id) ?: fail(R.string.stores_romm_extras_reinstall)
        val listed = RommDetails.takeFiles(item.id) ?: RommApi.rom(server, token, item.id).json?.let(RommCatalog::fileSet) ?: fail(R.string.stores_romm_no_files)
        val folder = RommInstalls.folder(app, entry, listed.own) ?: fail(R.string.stores_romm_extras_reinstall)
        val wanted = files.map { f -> listed.extras.firstOrNull { it.id == f.id && it.path == f.path } ?: fail(R.string.stores_romm_extra_gone) }
        val held = RommInstalls.files(app, entry).orEmpty()
        // A file that is there and not on the record was put there by the user.
        wanted.map { File(folder, it.path) }.firstOrNull { it.exists() && relative(it) !in held }?.let { fail(R.string.stores_romm_in_the_way, it.name) }
        val need = wanted.filterNot { f -> File(folder, f.path).let { it.isFile && f.size > 0 && it.length() == f.size } }
        // Listed before they come, so no file is ever in the folder without being on the record; what did not come is taken off again.
        RommInstalls.refile(app, item.id, added = wanted.map { relative(File(folder, it.path)) })
        try {
            handle.stage(DownloadStage.DOWNLOAD)
            val total = wanted.sumOf { it.size }
            val came = java.util.concurrent.atomic.AtomicLong(total - need.sumOf { it.size })
            handle.progress(came.get(), total)
            if (need.isNotEmpty()) several(server, token, folder, need, false, handle) { moved -> handle.progress(came.addAndGet(moved), maxOf(total, came.get())) }
        } finally {
            val (there, absent) = wanted.partition { File(folder, it.path).isFile }
            RommInstalls.refile(app, item.id, removed = absent.map { relative(File(folder, it.path)) }) { had -> had + there.map { it.id } }
        }
        if (cancelled.get()) return null
        StoresState.notifyLibraryChanged(app)
        return folder.path
    }

    /**
     * Downloads [wanted] into [folder], [AT_ONCE] files at a time, and tells [moved] how many more bytes are there. The first
     * file that fails stops the others and is what this throws; what came of each stays as its part, to be carried on from.
     */
    private fun several(server: String, token: String, folder: File, wanted: List<RommFile>, fresh: Boolean, handle: DownloadQueue.JobHandle, moved: (Long) -> Unit) {
        val queue = java.util.concurrent.ConcurrentLinkedQueue(wanted)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(AT_ONCE, wanted.size), DownloadQueue.workerFactory("romm-dl"))
        repeat(minOf(AT_ONCE, wanted.size)) {
            pool.execute {
                while (!stopped()) {
                    val f = queue.poll() ?: break
                    try {
                        val out = File(folder, f.path)
                        out.parentFile?.mkdirs()
                        val part = File(out.path + ".part")
                        if (!fresh) parts += part
                        var mine = 0L
                        val hashed = download(RommApi.contentUrl(server, item.id, out.name, f.id), token, part, handle, f.size, hash = SHA1.matches(f.sha1)) { done, _ ->
                            moved(done - mine)
                            mine = done
                        }
                        if (stopped()) break
                        checked(part, RommPlatforms.extension(out.name), f.sha1, hashed)
                        out.delete()
                        if (!part.renameTo(out)) fail(R.string.stores_romm_no_write)
                        parts -= part
                    } catch (t: Throwable) {
                        if (failure.compareAndSet(null, t)) halted.set(true)
                    }
                }
            }
        }
        pool.shutdown()
        while (!pool.awaitTermination(250, java.util.concurrent.TimeUnit.MILLISECONDS)) if (stopped()) pool.shutdownNow()
        if (!cancelled.get()) failure.get()?.let { throw it }
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
            parts.forEach { it.delete() }
            if (adding != null) {
                // The game stays installed. Only what had come of the extra files goes, also for a job that never ran here.
                val root = RommInstalls.romsRoot(app)
                val entry = RommInstalls.onRecord(app, item.id)
                if (root != null && entry != null) adding.forEach { File(root, "${entry.root}/${it.path}.part").delete() }
                RommSent.finished(app, item.id, "Cancelled on the device")
                return
            }
            if (RommInstalls.find(app, item.id) == null) {
                // A job that never ran here, as when a failed install is cleared, still has the folder an earlier run made for the game.
                if (partial == null) RommInstalls.claimed(app, item.id)?.let { made -> RommInstalls.romsRoot(app)?.let { RommArchive.remove(File(it, made)) } }
                RommInstalls.unclaim(app, item.id)
            }
            RommSent.finished(app, item.id, "Cancelled on the device")
        }
    }

    private fun stopped(): Boolean = cancelled.get() || halted.get()

    /**
     * Downloads to [part], carrying on from what is there, and tells [told] how much of the file is there and how long it is.
     * With [hash] it returns the whole file's SHA-1, worked out as the bytes pass so a large file is not read a second time
     * to check it; null when nothing new came or the install was stopped.
     */
    private fun download(url: String, token: String, part: File, handle: DownloadQueue.JobHandle, expected: Long, hash: Boolean = false, told: (Long, Long) -> Unit): String? {
        var dropped = 0
        while (true) {
            try {
                return RommDownload.once(url, token, part, expected, if (hash) "SHA-1" else null, stopped = ::stopped, told = told)
            } catch (e: RommDownload.Refused) {
                fail(R.string.stores_romm_download_failed, e.code)
            } catch (e: java.io.IOException) {
                // A connection that drops is carried on from what came, a few times, before the install is called failed.
                if (stopped() || ++dropped > RETRIES) throw if (e is RommDownload.CameShort) IllegalStateException(app.getString(R.string.stores_romm_download_failed, e.code)) else e
                handle.log("RomM download: ${e.javaClass.simpleName}, carrying on from ${part.length()} bytes ($dropped of $RETRIES)")
                Thread.sleep(retryMs * dropped)
            }
        }
    }

    private fun cover(rom: Library.Rom) {
        val image = RommApi.picture(item.tallImageUrl ?: return).second?.takeIf { it.isNotEmpty() } ?: return
        runCatching { CoverArt.store(app, rom, image) }
    }

    companion object {
        private val SHA1 = Regex("[0-9a-fA-F]{40}")
        private val HASHED_INSIDE = setOf("zip", "tar", "gz", "7z", "bz2")
        private const val MOST_ENTRIES = 20_000
        private const val RETRIES = 3
        internal const val AT_ONCE = 3
        internal var retryMs = 2_000L

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
