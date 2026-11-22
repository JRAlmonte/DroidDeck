package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.stores.CloudChanged
import com.droiddeck.launcher.stores.CloudFile
import com.droiddeck.launcher.stores.CloudTransport
import com.droiddeck.launcher.stores.isoDate
import java.io.IOException
import org.json.JSONObject

/** One game's RetroArch saves and states on a RomM server, named `saves/<file>` and `states/<file>`. */
internal class RommCloud(
    private val server: String,
    private val token: String,
    private val romId: String,
    private val deviceId: String?,
    private val base: String,
    private val onDenied: () -> Unit = {},
) : CloudTransport {
    private val ids = HashMap<String, String>()
    private val sent = HashMap<String, CloudFile>()

    override val folders: List<String> get() = RommSaves.KINDS

    override fun wants(name: String): Boolean = RommSaves.belongs(name, base)

    override fun committed(name: String): CloudFile? = sent[name]

    private fun text(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.optString(key).ifEmpty { null }

    private fun file(name: String, o: JSONObject) =
        CloudFile(name, isoDate(text(o, "updated_at")), text(o, "content_hash"))

    private fun failure(step: String, name: String, code: Int): IOException {
        if (code == 401 || code == 403) onDenied()
        return IOException("RomM $step $name HTTP $code")
    }

    private fun device(kind: String, first: Boolean): String =
        if (kind == RommSaves.SAVES && deviceId != null) (if (first) "?" else "&") + "device_id=" + RommApi.encode(deviceId) else ""

    override fun list(): List<CloudFile> {
        val found = LinkedHashMap<String, CloudFile>()
        for (kind in RommSaves.KINDS) {
            val answer = RommApi.call(server, "/api/$kind?rom_id=${RommApi.encode(romId)}", token)
            if (!answer.ok) throw failure("list", kind, answer.code)
            val items = answer.array ?: answer.json?.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                if (o.optString("emulator") != RommSaves.EMULATOR) continue
                val name = "$kind/${o.optString("file_name")}"
                if (!wants(name)) continue
                val next = file(name, o)
                if (found[name]?.let { it.modifiedMs >= next.modifiedMs } == true) continue
                found[name] = next
                ids[name] = o.optLong("id").toString()
            }
        }
        return found.values.toList()
    }

    override fun get(file: CloudFile): Pair<ByteArray, Long> {
        val kind = file.name.substringBefore('/')
        val id = ids[file.name] ?: run { list(); ids[file.name] } ?: throw IOException("RomM get ${file.name} is gone")
        val answer = RommApi.bytes(server, "/api/$kind/$id/content${device(kind, first = true)}", token)
        val data = answer.second ?: throw failure("get", file.name, answer.first)
        return data to file.modifiedMs
    }

    override fun put(files: Map<String, ByteArray>, modifiedMs: Map<String, Long>): Map<String, Long> {
        val done = LinkedHashMap<String, Long>()
        val refused = ArrayList<String>()
        for ((name, data) in files) {
            val kind = name.substringBefore('/')
            val field = if (kind == RommSaves.SAVES) "saveFile" else "stateFile"
            val id = ids[name]
            val answer =
                if (id != null) RommApi.upload(server, "/api/$kind/$id${device(kind, first = true)}", token, "PUT", field, name.substringAfter('/'), data)
                else RommApi.upload(
                    server, "/api/$kind?rom_id=${RommApi.encode(romId)}&emulator=${RommSaves.EMULATOR}${device(kind, first = false)}",
                    token, "POST", field, name.substringAfter('/'), data,
                )
            if (answer.code == 409) { refused += name; continue }
            val o = answer.takeIf { it.ok }?.json ?: throw failure("put", name, answer.code)
            val stored = file(name, o)
            sent[name] = stored
            if (o.has("id")) ids[name] = o.optLong("id").toString()
            done[name] = stored.modifiedMs
        }
        if (refused.isNotEmpty()) throw CloudChanged(done, refused)
        return done
    }
}
