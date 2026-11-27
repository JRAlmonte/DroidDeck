package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.stores.isoDate
import org.json.JSONArray
import org.json.JSONObject

/**
 * A save or state the server holds for a game. [kind] is `saves` or `states`, [slot] the slot a save was put in and
 * [picture] the path on the server of the screenshot kept with it; each is empty when the server sent none.
 */
internal class RommAsset(
    val kind: String, val id: String, val name: String, val emulator: String, val slot: String, val size: Long, val updatedMs: Long, val picture: String,
)

/** What the server holds of a game's saves and states, for its Sync tab to list and to delete from. */
internal object RommAssets {
    private const val GONE = "cloud_gone_"
    private const val MOST_PICTURE = 4 * 1024 * 1024

    fun parse(kind: String, list: JSONArray?): List<RommAsset> = (0 until (list?.length() ?: 0)).mapNotNull { list!!.optJSONObject(it) }.mapNotNull { o ->
        val id = o.optLong("id")
        val name = RommCatalog.text(o, "file_name")
        if (id <= 0 || name.isEmpty()) return@mapNotNull null
        // The sign-in goes with the request for a picture, so only a path on the server itself is kept.
        val picture = o.optJSONObject("screenshot")?.let { RommCatalog.text(it, "download_path") }.orEmpty().takeIf { it.startsWith("/api/") }.orEmpty()
        RommAsset(
            kind, id.toString(), name, RommCatalog.text(o, "emulator"), RommCatalog.text(o, "slot"), o.optLong("file_size_bytes").coerceAtLeast(0),
            isoDate(RommCatalog.text(o, "updated_at").ifEmpty { null }), picture,
        )
    }

    /** The account's saves and states of a game, the newest first, or null when the server could not be asked. Must not run on the main thread. */
    fun list(context: Context, romId: String): List<RommAsset>? {
        val (server, token) = RommAuth.both(context)
        if (server.isEmpty() || token.isEmpty() || !RommCatalog.ID.matches(romId)) return null
        val found = ArrayList<RommAsset>()
        for (kind in RommSaves.KINDS) {
            val answer = RommApi.call(server, "/api/$kind?rom_id=${RommApi.encode(romId)}", token)
            if (!answer.ok) return null
            found += parse(kind, answer.array ?: answer.json?.optJSONArray("items"))
        }
        return found.sortedByDescending { it.updatedMs }
    }

    /** The screenshot at [path] on the server, or null. Must not run on the main thread. */
    fun picture(context: Context, path: String): ByteArray? {
        val (server, token) = RommAuth.both(context)
        if (server.isEmpty() || token.isEmpty() || !path.startsWith("/api/")) return null
        return RommApi.bytes(server, path.replace(" ", "%20"), token, MOST_PICTURE).second
    }

    /**
     * Deletes [asset] on the server. Nothing on the device is deleted or changed: the file stays where its emulator keeps it,
     * and sync is told the server's copy is gone ([gone]) so that the file is not sent back as it is. Must not run on the main thread.
     */
    fun delete(context: Context, romId: String, asset: RommAsset): RommAnswer {
        val (server, token) = RommAuth.both(context)
        val id = asset.id.toLongOrNull()
        if (id == null || asset.kind !in RommSaves.KINDS) return RommAnswer(400, "")
        val answer = RommApi.call(server, "/api/${asset.kind}/delete", token, body = JSONObject().put(asset.kind, JSONArray().put(id)).toString())
        if (answer.ok) note(context, romId) { it + mark(asset.kind, asset.emulator, asset.name) }
        else if (answer.code == 401 || answer.code == 403) RommSync.denied(context, "assets.write")
        return answer
    }

    private fun mark(kind: String, emulator: String, name: String) = "$kind|$emulator|$name"

    @Synchronized
    private fun note(context: Context, romId: String, change: (Set<String>) -> Set<String>) {
        val prefs = RommPrefs.get(context)
        val next = change(prefs.getStringSet(GONE + romId, null).orEmpty().toSet())
        prefs.edit().apply { if (next.isEmpty()) remove(GONE + romId) else putStringSet(GONE + romId, next) }.apply()
    }

    /** The files of [emulator] that were deleted on the server from here and not sent since, as kind and file name. */
    fun gone(context: Context, romId: String, emulator: String): Set<Pair<String, String>> =
        RommPrefs.get(context).getStringSet(GONE + romId, null).orEmpty().map { it.split('|', limit = 3) }
            .filter { it.size == 3 && it[1] == emulator }.mapTo(HashSet()) { it[0] to it[2] }

    /** Notes that a file is on the server again. */
    fun sent(context: Context, romId: String, emulator: String, kind: String, name: String) = note(context, romId) { it - mark(kind, emulator, name) }

    /** Forgets which of a game's files were deleted on the server, so that the next upload sends them again. */
    fun forget(context: Context, romId: String) = note(context, romId) { emptySet() }
}
