package com.droiddeck.launcher.stores.romm

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.stores.StoresState
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** What is sent to RomM besides saves: play sessions, favourites and a game's status. It waits in a file while the server is out of reach. */
internal object RommActivity {
    private const val TAG = "RommActivity"
    private const val PLAYING = "playing"
    private const val FAVORITES = "favorites_synced"
    private const val SHORTEST_MS = 5_000L
    const val FAVORITE_PREFIX = "romm:"

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "romm-activity").apply { isDaemon = true } }

    private fun file(context: Context) = File(context.filesDir, "stores/romm/pending.json")

    private fun read(context: Context): List<JSONObject> = runCatching {
        JSONArray(file(context).readText()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
    }.getOrDefault(emptyList())

    private fun write(context: Context, ops: List<JSONObject>) {
        val f = file(context)
        if (ops.isEmpty()) { f.delete(); return }
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(JSONArray(ops).toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    @Synchronized
    internal fun queue(context: Context, op: JSONObject) = write(context, read(context) + op)

    @Synchronized
    fun pending(context: Context): Int = read(context).size

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        RommPrefs.get(context).edit().remove(PLAYING).remove(FAVORITES).apply()
    }

    fun started(context: Context, romId: String, nowMs: Long = System.currentTimeMillis()) {
        RommPrefs.get(context).edit().putString(PLAYING, "$romId|$nowMs").apply()
    }

    /** Closes the play session a launch opened; one that ended with the app killed has no end and is dropped at the next launch. */
    fun ended(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val prefs = RommPrefs.get(context)
        val playing = prefs.getString(PLAYING, null) ?: return
        prefs.edit().remove(PLAYING).apply()
        val romId = playing.substringBefore('|')
        val start = playing.substringAfter('|').toLongOrNull() ?: return
        if (romId.isEmpty() || nowMs - start < SHORTEST_MS) return
        queue(context, JSONObject().put("type", "session").put("rom", romId).put("start", start).put("end", nowMs))
    }

    fun favorite(context: Context, romId: String, on: Boolean) {
        RommShelvesState.stale()
        val app = context.applicationContext
        worker.execute {
            queue(app, JSONObject().put("type", "favorite").put("rom", romId).put("on", on))
            flush(app)
        }
    }

    fun setStatus(context: Context, romId: String, data: JSONObject, done: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        worker.execute {
            queue(app, JSONObject().put("type", "props").put("rom", romId).put("data", data))
            val left = flush(app)
            StoresState.post { done(left == 0) }
        }
    }

    fun flushSoon(context: Context) {
        val app = context.applicationContext
        worker.execute { flush(app) }
    }

    /** A game's own data for the signed-in user: backlog, now playing, status, rating. */
    fun status(context: Context, romId: String): JSONObject? {
        val server = RommAuth.server(context)
        val token = RommAuth.token(context)
        if (server.isEmpty() || token.isEmpty()) return null
        return RommApi.rom(server, token, romId).takeIf { it.ok }?.json?.optJSONObject("rom_user")
    }

    private fun stamp(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    private fun romNumber(id: String): Any = id.toLongOrNull() ?: id

    private fun favorites(server: String, token: String): Pair<RommAnswer, JSONObject?> {
        val answer = RommApi.call(server, "/api/collections", token)
        val list = answer.array
        return answer to (0 until (list?.length() ?: 0)).mapNotNull { list!!.optJSONObject(it) }.firstOrNull { it.optBoolean("is_favorite", false) }
    }

    private fun send(context: Context, server: String, token: String, op: JSONObject): RommAnswer {
        val rom = op.optString("rom")
        return when (op.optString("type")) {
            "session" -> {
                val start = op.optLong("start")
                val end = op.optLong("end")
                val body = JSONObject().put("sessions", JSONArray().put(
                    JSONObject().put("rom_id", romNumber(rom)).put("start_time", stamp(start)).put("end_time", stamp(end)).put("duration_ms", end - start),
                ))
                RommSync.deviceId(context)?.let { body.put("device_id", it) }
                RommApi.call(server, "/api/play-sessions", token, body = body.toString()).also {
                    if (it.ok) RommApi.call(server, "/api/roms/${RommApi.encode(rom)}/props?update_last_played=true", token, "PUT", "{}")
                }
            }
            "favorite" -> {
                val on = op.optBoolean("on")
                val (listed, found) = favorites(server, token)
                if (!listed.ok) return listed
                val collection = found ?: if (!on) return listed else {
                    val made = RommApi.form(server, "/api/collections?is_favorite=true&is_public=false", token, mapOf("name" to "Favourites", "description" to ""))
                    made.takeIf { it.ok }?.json ?: return made
                }
                val body = JSONObject().put("rom_ids", JSONArray().put(romNumber(rom))).toString()
                RommApi.call(server, "/api/collections/${collection.optLong("id")}/roms", token, if (on) "POST" else "DELETE", body)
            }
            "props" -> RommApi.call(server, "/api/roms/${RommApi.encode(rom)}/props", token, "PUT", (op.optJSONObject("data") ?: JSONObject()).toString())
            else -> RommAnswer(400, "")
        }
    }

    /** Sends what is waiting, oldest first, and returns how much still waits. A request the server refuses for good is dropped. */
    @Synchronized
    fun flush(context: Context): Int {
        var ops = read(context)
        if (ops.isEmpty()) return 0
        if (RommSync.access(context) != RommSync.Access.OK) return ops.size
        val server = RommAuth.server(context)
        val token = RommAuth.token(context)
        while (ops.isNotEmpty()) {
            val answer = runCatching { send(context, server, token, ops.first()) }.getOrElse { RommAnswer(0, "") }
            Log.i(TAG, "${ops.first().optString("type")} answered ${answer.code}")
            if (answer.code == 401 || answer.code == 403) { RommSync.denied(context); break }
            if (!answer.ok && (answer.code == 0 || answer.code == 408 || answer.code == 429 || answer.code >= 500)) break
            ops = ops.drop(1)
            write(context, ops)
        }
        return ops.size
    }

    internal class Merge(val add: Set<String>, val remove: Set<String>, val send: Set<String>)

    /** What changes here and on the server so both hold the same favourites; [synced] is what both held last time, null the first time. */
    internal fun mergeFavorites(local: Set<String>, remote: Set<String>, synced: Set<String>?): Merge =
        if (synced == null) Merge(remote - local, emptySet(), local - remote)
        else Merge((remote - synced) - local, (synced - remote) intersect local, emptySet())

    /** Brings favourites made on the server here, and the first time sends the ones made here. [apply] gets the RomM ids to add and remove. */
    @Synchronized
    fun syncFavorites(context: Context, local: Set<String>, apply: (add: Set<String>, remove: Set<String>) -> Unit) {
        if (flush(context) != 0 || RommSync.access(context) != RommSync.Access.OK) return
        val (listed, found) = favorites(RommAuth.server(context), RommAuth.token(context))
        if (!listed.ok) return
        val ids = found?.optJSONArray("rom_ids")
        val remote = (0 until (ids?.length() ?: 0)).map { ids!!.optString(it) }.toSet()
        val prefs = RommPrefs.get(context)
        val merge = mergeFavorites(local, remote, prefs.getStringSet(FAVORITES, null)?.toSet())
        for (id in merge.send) queue(context, JSONObject().put("type", "favorite").put("rom", id).put("on", true))
        if (merge.add.isNotEmpty() || merge.remove.isNotEmpty()) apply(merge.add, merge.remove)
        prefs.edit().putStringSet(FAVORITES, (local + merge.add) - merge.remove).apply()
        flush(context)
    }
}
