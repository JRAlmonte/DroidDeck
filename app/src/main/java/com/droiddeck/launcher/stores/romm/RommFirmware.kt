package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.RetroArchCores
import java.io.File
import org.json.JSONArray

internal class RommFirmwareFile(val id: String, val name: String, val size: Long, val md5: String)

internal object RommFirmware {
    enum class Problem { DOWNLOAD, WRITE, MISMATCH }

    private val biosFolders = setOf("bios", "firmware")
    private const val MOST_BYTES = 2L * 1024 * 1024 * 1024
    private const val RETRIES = 3
    internal var retryMs = 2_000L

    fun files(array: JSONArray?): List<RommFirmwareFile> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id")
            val name = if (o.isNull("file_name")) "" else o.optString("file_name")
            if (id.isEmpty() || !RommCatalog.plainName(name) || o.optBoolean("missing_from_fs")) null
            else RommFirmwareFile(id, name, o.optLong("file_size_bytes").coerceAtLeast(0), if (o.isNull("md5_hash")) "" else o.optString("md5_hash").trim())
        }.distinctBy { it.name.lowercase() }
    }

    /**
     * The folder a platform's BIOS file belongs in, or null when nothing here plays the platform. A standalone emulator reads
     * `ROMs/<system>/bios`; RetroArch reads its system folder under [rootfs].
     */
    fun directory(romsRoot: File, rootfs: File, slug: String, systems: List<Library.RomSystem> = Library.romSystems()): File? {
        val target = RommPlatforms.target(slug, systems) ?: return null
        if (target.emulatorId == RommSaves.EMULATOR) return File(rootfs, RetroArchCores.SYSTEM_DIR)
        val games = RommPlatforms.directory(romsRoot, target)
        return games.listFiles { f -> f.isDirectory && f.name.lowercase() in biosFolders }?.minByOrNull { it.name.lowercase() != "bios" } ?: File(games, "bios")
    }

    fun missing(files: List<RommFirmwareFile>, romsRoot: File, rootfs: File, slug: String, systems: List<Library.RomSystem> = Library.romSystems()): List<Pair<RommFirmwareFile, File>> =
        files.mapNotNull { f -> directory(romsRoot, rootfs, slug, systems)?.let { f to File(it, f.name) } }
            .filter { (f, to) -> !(to.isFile && (f.size == 0L || to.length() == f.size)) }

    /**
     * Downloads [file] to [to] through a hidden part file. A connection that drops is carried on from what came, and what a
     * failed download leaves stays for the next one only when RomM gave the file a size and a hash to check the whole against.
     */
    fun fetch(server: String, token: String, file: RommFirmwareFile, to: File): Problem? {
        val dir = to.parentFile ?: return Problem.WRITE
        if (!dir.isDirectory && !dir.mkdirs()) return Problem.WRITE
        val part = File(dir, ".${file.name}.part")
        val checkable = file.size > 0 && file.md5.isNotEmpty()
        if (!checkable) part.delete()
        val carried = part.length() > 0
        val url = RommApi.firmwareUrl(server, file.id, file.name)
        val problem = attempt(url, token, file, part, to, checkable)
        // What an earlier download left may be of a file the server has replaced since.
        return if (problem == Problem.MISMATCH && carried) attempt(url, token, file, part, to, checkable) else problem
    }

    private fun attempt(url: String, token: String, file: RommFirmwareFile, part: File, to: File, checkable: Boolean): Problem? {
        var keep = false
        try {
            var dropped = 0
            var came: String?
            while (true) {
                try {
                    came = RommDownload.once(url, token, part, file.size, "MD5", file.size.takeIf { it > 0 } ?: MOST_BYTES)
                    break
                } catch (e: java.io.FileNotFoundException) {
                    return Problem.WRITE
                } catch (e: java.io.IOException) {
                    if (part.length() == 0L || ++dropped > RETRIES) {
                        keep = checkable && part.length() > 0
                        return Problem.DOWNLOAD
                    }
                    Thread.sleep(retryMs * dropped)
                }
            }
            val md5 = came ?: Hashes.md5(part)
            if ((file.size > 0 && part.length() != file.size) || (file.md5.isNotEmpty() && !file.md5.equals(md5, ignoreCase = true))) return Problem.MISMATCH
            to.delete()
            return if (part.renameTo(to)) null else Problem.WRITE
        } catch (e: RommDownload.Refused) {
            return if (e.tooLong) Problem.MISMATCH else Problem.DOWNLOAD
        } catch (e: Exception) {
            return Problem.DOWNLOAD
        } finally {
            if (!keep) part.delete()
        }
    }
}
