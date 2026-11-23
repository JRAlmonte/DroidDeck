package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.ports.Port
import com.droiddeck.launcher.ports.Ports
import com.droiddeck.launcher.stores.CloudChanged
import com.droiddeck.launcher.stores.CloudFile
import com.droiddeck.launcher.stores.CloudSaves
import com.droiddeck.launcher.stores.CloudTransport
import com.droiddeck.launcher.stores.isoDate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

class SaveTooLarge(name: String) : IOException("$name is too large for RomM save sync")

/**
 * The saves a port's entry names, as paths under [rootfs]. On the server they are one zip, [fileName], where a file in the
 * install folder is under `port/` and one in the runtime's home under `home/`, so the zip does not depend on the install's name.
 * RomM refuses an emulator name it could not use as a folder name, so [emulator] has no colon.
 */
internal class RommPortSaves(port: Port, val rootfs: File) {
    val emulator: String = "port-" + port.id
    val fileName: String = port.id + ".zip"
    private val install = Ports.GUEST_ROOT.removePrefix("/") + "/" + port.tool + "/"
    val folders: List<String> = port.saves.map { if (it.startsWith(Ports.HOME)) HOME + it.removePrefix(Ports.HOME) else install + it }

    fun wants(name: String): Boolean {
        if (folders.none { name == it || name.startsWith("$it/") } || name.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
        val base = File(rootfs, if (name.startsWith(install)) install else HOME).canonicalPath
        return File(rootfs, name).canonicalPath.startsWith(base + File.separator)
    }

    fun packed(name: String): String? = when {
        name.startsWith(install) -> IN_PORT + name.removePrefix(install)
        name.startsWith(HOME) -> IN_HOME + name.removePrefix(HOME)
        else -> null
    }

    fun local(packed: String): String? = when {
        packed.startsWith(IN_PORT) -> install + packed.removePrefix(IN_PORT)
        packed.startsWith(IN_HOME) -> HOME + packed.removePrefix(IN_HOME)
        else -> null
    }?.takeIf(::wants)

    private companion object {
        const val HOME = "root/"
        const val IN_PORT = "port/"
        const val IN_HOME = "home/"
    }
}

internal class RommPortCloud(
    private val server: String,
    private val token: String,
    private val romId: String,
    private val deviceId: String?,
    private val saves: RommPortSaves,
    private val onDenied: () -> Unit = {},
) : CloudTransport {
    private var id: String? = null
    private var modified = 0L
    private var held = LinkedHashMap<String, ByteArray>()
    private val sent = HashMap<String, CloudFile>()

    override val folders: List<String> get() = saves.folders

    override fun wants(name: String): Boolean = saves.wants(name)

    override fun committed(name: String): CloudFile? = sent[name]

    private fun updated(o: JSONObject): Long = isoDate(if (o.isNull("updated_at")) null else o.optString("updated_at").ifEmpty { null })

    private fun failure(step: String, code: Int): IOException {
        if (code == 401 || code == 403) onDenied()
        return IOException("RomM $step ${saves.fileName} HTTP $code")
    }

    private fun device(first: Boolean): String = if (deviceId != null) (if (first) "?" else "&") + "device_id=" + RommApi.encode(deviceId) else ""

    private fun unpack(zip: ByteArray): LinkedHashMap<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.isDirectory) continue
                val data = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_UNPACKED) throw SaveTooLarge(saves.fileName)
                    data.write(buffer, 0, read)
                }
                saves.local(entry.name)?.let { files[it] = data.toByteArray() }
            }
        }
        return files
    }

    private fun pack(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in files.mapNotNull { (name, data) -> saves.packed(name)?.let { it to data } }.sortedBy { it.first }) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    override fun list(): List<CloudFile> {
        val answer = RommApi.call(server, "/api/saves?rom_id=${RommApi.encode(romId)}", token)
        if (!answer.ok) throw failure("list", answer.code)
        val items = answer.array ?: answer.json?.optJSONArray("items")
        var newest: JSONObject? = null
        for (i in 0 until (items?.length() ?: 0)) {
            val o = items!!.optJSONObject(i) ?: continue
            if (o.optString("emulator") != saves.emulator || o.optString("file_name") != saves.fileName) continue
            if (newest == null || updated(o) > updated(newest)) newest = o
        }
        id = null; modified = 0L; held = LinkedHashMap()
        val o = newest ?: return emptyList()
        val found = o.optLong("id").toString()
        val got = RommApi.bytes(server, "/api/saves/$found/content${device(first = true)}", token)
        val zip = got.second ?: throw failure("get", got.first)
        if (zip.size >= RommApi.MAX_BYTES) throw SaveTooLarge(saves.fileName)
        held = try { unpack(zip) } catch (e: SaveTooLarge) { throw e } catch (e: Exception) { throw IOException("RomM ${saves.fileName} cannot be read", e) }
        id = found
        modified = updated(o)
        return held.map { CloudFile(it.key, modified, CloudSaves.md5(it.value)) }
    }

    override fun get(file: CloudFile): Pair<ByteArray, Long> =
        (held[file.name] ?: throw IOException("RomM get ${file.name} is gone")) to file.modifiedMs

    override fun put(files: Map<String, ByteArray>, modifiedMs: Map<String, Long>): Map<String, Long> {
        val mine = files.filterKeys(saves::wants)
        if (mine.isEmpty()) return emptyMap()
        val all = LinkedHashMap(held).apply { putAll(mine) }
        val zip = pack(all)
        if (zip.size >= RommApi.MAX_BYTES) throw SaveTooLarge(saves.fileName)
        val current = id
        val answer =
            if (current != null) RommApi.upload(server, "/api/saves/$current${device(first = true)}", token, "PUT", "saveFile", saves.fileName, zip)
            else RommApi.upload(
                server, "/api/saves?rom_id=${RommApi.encode(romId)}&emulator=${RommApi.encode(saves.emulator)}${device(first = false)}",
                token, "POST", "saveFile", saves.fileName, zip,
            )
        if (answer.code == 409) throw CloudChanged(emptyMap(), mine.keys.toList())
        val o = answer.takeIf { it.ok }?.json ?: throw failure("put", answer.code)
        if (o.has("id")) id = o.optLong("id").toString()
        modified = updated(o)
        held = all
        for ((name, data) in mine) sent[name] = CloudFile(name, modified, CloudSaves.md5(data))
        return mine.keys.associateWith { modified }
    }

    private companion object {
        const val MAX_UNPACKED = 64L * 1024 * 1024
    }
}
