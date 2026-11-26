package com.droiddeck.launcher.stores.romm

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.stores.StoresState
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

internal object RommActivity {
    private const val TAG = "RommActivity"
    private const val PLAYING = "playing"
    private const val ALIVE = "playing_alive"
    private const val BEAT_MS = 60_000L
    private const val LOST_AFTER_MS = 3 * BEAT_MS
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
    internal fun pending(context: Context): Int = read(context).size

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        RommPrefs.get(context).edit().remove(PLAYING).remove(ALIVE).remove(FAVORITES).apply()
    }

    /** Drops this account's favourites from the device and returns their ROM ids; the next sign-in reads them from its server. */
    fun forgetFavorites(context: Context): Set<String> {
        val keys = com.droiddeck.launcher.session.SessionPrefs.libraryFavorites(context)
        val ours = keys.filterTo(HashSet()) { it.startsWith(FAVORITE_PREFIX) }
        if (ours.isNotEmpty()) com.droiddeck.launcher.session.SessionPrefs.setLibraryFavorites(context, keys - ours)
        return ours.mapTo(HashSet()) { it.removePrefix(FAVORITE_PREFIX) }
    }

    fun started(context: Context, romId: String, nowMs: Long = System.currentTimeMillis()) {
        closeLost(context, nowMs, force = true)
        RommPrefs.get(context).edit().putString(PLAYING, "$romId|$nowMs").putLong(ALIVE, nowMs).apply()
    }

    /** Notes that the open play session still runs, so one that ends with the app killed is known to have lasted this long. */
    fun alive(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val prefs = RommPrefs.get(context)
        if (prefs.contains(PLAYING)) prefs.edit().putLong(ALIVE, nowMs).apply()
    }

    private fun close(context: Context, endMs: (Long) -> Long): String? {
        val prefs = RommPrefs.get(context)
        val playing = prefs.getString(PLAYING, null) ?: return null
        val end = endMs(prefs.getLong(ALIVE, 0))
        prefs.edit().remove(PLAYING).remove(ALIVE).apply()
        val romId = playing.substringBefore('|')
        val start = playing.substringAfter('|').toLongOrNull() ?: return null
        if (romId.isEmpty()) return null
        if (end - start >= SHORTEST_MS) queue(context, JSONObject().put("type", "session").put("rom", romId).put("start", start).put("end", end))
        return romId
    }

    /** Closes the play session a launch opened and returns its ROM id. */
    fun ended(context: Context, nowMs: Long = System.currentTimeMillis()): String? = close(context) { nowMs }

    /**
     * Closes a play session whose end was never seen because the app was killed: it ends when it was last known to run.
     * Without [force] a session still noted as running a moment ago is left alone.
     */
    fun closeLost(context: Context, nowMs: Long = System.currentTimeMillis(), force: Boolean = false) {
        val prefs = RommPrefs.get(context)
        if (!prefs.contains(PLAYING) || (!force && nowMs - prefs.getLong(ALIVE, 0) < LOST_AFTER_MS)) return
        close(context) { it }
    }

    private val clock = Executors.newSingleThreadScheduledExecutor { Thread(it, "romm-presence").apply { isDaemon = true } }
    private var beat: java.util.concurrent.ScheduledFuture<*>? = null

    private fun presence(context: Context, romId: String?) {
        if (RommSync.access(context, PLAY_SCOPE) != RommSync.Access.OK) return
        val device = RommSync.deviceId(context) ?: return
        val server = RommAuth.server(context)
        val token = RommAuth.token(context)
        if (romId == null) RommApi.call(server, "/api/activity/heartbeat?device_id=${RommApi.encode(device)}", token, "DELETE")
        else RommApi.call(server, "/api/activity/heartbeat", token, body = JSONObject().put("rom_id", romNumber(romId)).put("device_id", device).toString())
    }

    private val beats = Any()

    /** While a game runs, tells the server every minute that this device is playing it. */
    fun beat(context: Context, romId: String) {
        val app = context.applicationContext
        synchronized(beats) {
            beat?.cancel(false)
            beat = clock.scheduleWithFixedDelay({ runCatching { alive(app); presence(app, romId) } }, 0, BEAT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    fun rest(context: Context) {
        val app = context.applicationContext
        val running = synchronized(beats) { beat.also { beat = null } } ?: return
        running.cancel(false)
        clock.execute { runCatching { presence(app, null) } }
    }

    fun favorite(context: Context, romId: String, on: Boolean) {
        RommShelvesState.stale()
        val app = context.applicationContext
        worker.execute {
            queue(app, JSONObject().put("type", "favorite").put("rom", romId).put("on", on))
            flush(app)
        }
    }

    /** What to tell the user when a change still waits: that the sign-in may not make it, that the server is too old, or that it goes out later. */
    private fun waiting(context: Context, scope: String): Int = when (RommSync.access(context, scope)) {
        RommSync.Access.NO_ACCESS, RommSync.Access.SIGNED_OUT -> com.droiddeck.launcher.R.string.romm_sync_no_access
        RommSync.Access.OLD_SERVER -> com.droiddeck.launcher.R.string.romm_sync_old_server
        RommSync.Access.OK -> com.droiddeck.launcher.R.string.romm_status_queued
    }

    /** Puts a game in one of the account's own collections or takes it out. [done] is given nothing when the server took the change, else the message that says why it waits. */
    fun collect(context: Context, romId: String, collectionId: String, on: Boolean, done: (Int?) -> Unit = {}) {
        val app = context.applicationContext
        worker.execute {
            queue(app, JSONObject().put("type", "collection").put("rom", romId).put("id", collectionId).put("on", on))
            val waits = if (flush(app) == 0) null else waiting(app, "collections.write")
            StoresState.post { done(waits) }
        }
    }

    /** Writes a note on a game: a new one when [noteId] is null, else that note's text. */
    fun note(context: Context, romId: String, noteId: String?, title: String, content: String, done: (Int?) -> Unit = {}) {
        val app = context.applicationContext
        worker.execute {
            queue(app, JSONObject().put("type", "note").put("rom", romId).put("note", noteId ?: "").put("title", title).put("content", content))
            val waits = if (flush(app) == 0) null else waiting(app, PLAY_SCOPE)
            StoresState.post { done(waits) }
        }
    }

    fun setStatus(context: Context, romId: String, data: JSONObject, done: (Int?) -> Unit = {}) {
        val app = context.applicationContext
        worker.execute {
            queue(app, JSONObject().put("type", "props").put("rom", romId).put("data", data))
            val waits = if (flush(app) == 0) null else waiting(app, PLAY_SCOPE)
            StoresState.post { done(waits) }
        }
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
                RommApi.call(server, "/api/play-sessions", token, body = body.toString())
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
            "collection" -> RommApi.call(
                server, "/api/collections/${RommApi.encode(op.optString("id"))}/roms", token, if (op.optBoolean("on")) "POST" else "DELETE",
                JSONObject().put("rom_ids", JSONArray().put(romNumber(rom))).toString(),
            )
            "note" -> {
                val note = op.optString("note")
                val body = JSONObject().put("title", op.optString("title")).put("content", op.optString("content"))
                if (note.isEmpty()) RommApi.call(server, "/api/roms/${RommApi.encode(rom)}/notes", token, body = body.put("is_public", false).toString())
                else RommApi.call(server, "/api/roms/${RommApi.encode(rom)}/notes/${RommApi.encode(note)}", token, "PUT", body.toString())
            }
            "props" -> RommApi.call(server, "/api/roms/${RommApi.encode(rom)}/props", token, "PUT", (op.optJSONObject("data") ?: JSONObject()).toString())
            else -> RommAnswer(400, "")
        }
    }

    internal const val PLAY_SCOPE = "roms.user.write"

    private fun scope(op: JSONObject): String = if (op.optString("type") in listOf("favorite", "collection")) "collections.write" else PLAY_SCOPE

    private val sending = Any()

    @Synchronized
    private fun sent(context: Context, op: JSONObject) {
        val all = read(context)
        val text = op.toString()
        val at = all.indexOfFirst { it.toString() == text }
        if (at >= 0) write(context, all.filterIndexed { i, _ -> i != at })
    }

    /**
     * Sends what is waiting, oldest first, and returns how much of it still waits. A request the server refuses for good is dropped,
     * and one the sign-in may not make waits without holding back the others. Only [sending] is held while the server is asked,
     * so a launch that notes its play session from the main thread never waits for the network.
     */
    fun flush(context: Context): Int {
        synchronized(sending) {
            val ops = synchronized(this) { read(context) }
            if (ops.isEmpty()) return 0
            val server = RommAuth.server(context)
            val token = RommAuth.token(context)
            var waits = 0
            var stopped = false
            for (op in ops) {
                if (stopped || RommSync.access(context, scope(op)) != RommSync.Access.OK) { waits++; continue }
                val answer = runCatching { send(context, server, token, op) }.getOrElse { RommAnswer(0, "") }
                val type = op.optString("type")
                Log.i(TAG, "$type answered ${answer.code}")
                when {
                    answer.code == 403 && type == "collection" -> Log.i(TAG, "the collection is not this account's to change")
                    answer.code == 401 || answer.code == 403 -> { RommSync.denied(context, scope(op)); waits++; continue }
                    !answer.ok && (answer.code == 0 || answer.code == 408 || answer.code == 429 || answer.code >= 500) -> { stopped = true; waits++; continue }
                }
                sent(context, op)
            }
            return waits
        }
    }

    internal class Merge(val add: Set<String>, val remove: Set<String>, val send: Set<String>)

    /** What changes here and on the server so both hold the same favourites; [synced] is what both held last time, null the first time. */
    internal fun mergeFavorites(local: Set<String>, remote: Set<String>, synced: Set<String>?): Merge =
        if (synced == null) Merge(remote - local, emptySet(), local - remote)
        else Merge((remote - synced) - local, (synced - remote) intersect local, emptySet())

    fun syncFavorites(context: Context, local: Set<String>, apply: (add: Set<String>, remove: Set<String>) -> Unit) {
        synchronized(sending) {
            if (flush(context) != 0 || RommSync.access(context, "collections.write") != RommSync.Access.OK) return
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
}
