package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.RetroArchCores
import com.droiddeck.launcher.ports.Ports
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.security.MessageDigest
import org.json.JSONArray

internal class RommFirmwareFile(val id: String, val name: String, val size: Long, val md5: String)

/** The BIOS files a RomM server holds for a platform, and where each emulator here reads them from. */
internal object RommFirmware {
    enum class Problem { DOWNLOAD, WRITE, MISMATCH }

    private val biosFolders = setOf("bios", "firmware")

    fun files(array: JSONArray?): List<RommFirmwareFile> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id")
            val name = if (o.isNull("file_name")) "" else o.optString("file_name")
            if (id.isEmpty() || name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name || o.optBoolean("missing_from_fs")) null
            else RommFirmwareFile(id, name, o.optLong("file_size_bytes").coerceAtLeast(0), if (o.isNull("md5_hash")) "" else o.optString("md5_hash").trim())
        }.distinctBy { it.name.lowercase() }
    }

    /**
     * The folder a platform's BIOS file belongs in, or null when nothing here plays the platform. A standalone emulator reads
     * `ROMs/<system>/bios`; RetroArch reads its system folder under [rootfs], and FinalBurn Neo reads its sets from beside the games.
     */
    fun directory(romsRoot: File, rootfs: File, slug: String, fileName: String, systems: List<Library.RomSystem> = Library.romSystems()): File? {
        val folder = RommPlatforms.folder(slug)
        val target = RommPlatforms.target(slug, systems)
        if (target == null && RetroArchCores.missing(folder) == null) return null
        if (target != null && Ports.byTarget(target.emulatorId) != null) return null
        val games = target?.let { RommPlatforms.directory(romsRoot, it) } ?: File(romsRoot, folder)
        if (target != null && target.emulatorId != RommSaves.EMULATOR) {
            return games.listFiles { f -> f.isDirectory && f.name.lowercase() in biosFolders }?.minByOrNull { it.name.lowercase() != "bios" } ?: File(games, "bios")
        }
        return if (fileName.lowercase() in RetroArchCores.firmwareFiles) games else File(rootfs, RetroArchCores.SYSTEM_DIR)
    }

    /** The files that are not here yet, each with the place it goes. */
    fun missing(files: List<RommFirmwareFile>, romsRoot: File, rootfs: File, slug: String, systems: List<Library.RomSystem> = Library.romSystems()): List<Pair<RommFirmwareFile, File>> =
        files.mapNotNull { f -> directory(romsRoot, rootfs, slug, f.name, systems)?.let { f to File(it, f.name) } }
            .filter { (f, to) -> !(to.isFile && (f.size == 0L || to.length() == f.size)) }

    /** Downloads one file to [to] and keeps it only when it is the file RomM says it holds. */
    fun fetch(server: String, token: String, file: RommFirmwareFile, to: File): Problem? {
        val dir = to.parentFile ?: return Problem.WRITE
        if (!dir.isDirectory && !dir.mkdirs()) return Problem.WRITE
        val part = File(dir, ".${file.name}.part")
        var conn: HttpURLConnection? = null
        try {
            conn = RommApi.open(RommApi.firmwareUrl(server, file.id, file.name), token).apply { setRequestProperty("Accept-Encoding", "identity") }
            if (conn.responseCode !in 200..299) return Problem.DOWNLOAD
            val digest = MessageDigest.getInstance("MD5")
            var done = 0L
            val buffer = ByteArray(64 * 1024)
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                    }
                }
            }
            val md5 = digest.digest().joinToString("") { "%02x".format(it) }
            if ((file.size > 0 && done != file.size) || (file.md5.isNotEmpty() && !file.md5.equals(md5, ignoreCase = true))) return Problem.MISMATCH
            to.delete()
            return if (part.renameTo(to)) null else Problem.WRITE
        } catch (e: java.io.FileNotFoundException) {
            return Problem.WRITE
        } catch (e: Exception) {
            return Problem.DOWNLOAD
        } finally {
            runCatching { conn?.disconnect() }
            part.delete()
        }
    }
}
