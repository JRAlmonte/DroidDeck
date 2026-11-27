package com.droiddeck.launcher.stores.romm

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.GameFacts
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime

internal class RommNote(val id: String, val title: String, val content: String, val author: String, val userId: String, val public: Boolean)

internal class RommBadge(val raId: String, val title: String, val description: String, val points: Int, val badge: String?, val badgeLocked: String?, val order: Int)

/** What RomM knows about one game beyond what a tile shows, read from `/api/roms/{id}`. */
internal data class RommDetail(
    val id: String,
    val hltb: GameFacts.Hltb?,
    val logo: String?,
    val fanart: String?,
    val manual: String?,
    val alternativeNames: List<String>,
    val themes: List<String>,
    val perspectives: List<String>,
    val ratingCount: Int,
    val added: Long,
    val lastPlayed: Long,
    val verified: List<String>,
    val collections: List<RommCollection>,
    val notes: List<RommNote>,
    val raId: String,
    val badges: List<RommBadge>,
    val user: JSONObject,
    val playedMs: Long,
    val similar: List<CatalogItem>,
)

internal object RommDetails {
    /** The games whose details were read, by ROM id; a page draws from here and is drawn again when its game arrives. */
    val known = mutableStateMapOf<String, RommDetail>()

    private val asking = HashSet<String>()

    private val DUMPS = listOf(
        "nointro_match" to "No-Intro", "redump_match" to "Redump", "tosec_match" to "TOSEC", "mame_arcade_match" to "MAME",
        "mame_mess_match" to "MAME", "mame_redump_match" to "MAME", "fbneo_match" to "FBNeo", "whdload_match" to "WHDLoad", "puredos_match" to "Pure DOS",
    )

    private val KEPT = listOf(
        "id", "ra_id", "alternative_names", "hltb_metadata", "hasheous_metadata", "path_manual", "created_at", "rom_user",
        "user_collections", "all_user_notes", "merged_ra_metadata",
    )
    private val KEPT_IN = mapOf(
        "ss_metadata" to listOf("logo_path", "fanart_path"),
        "igdb_metadata" to listOf("themes", "player_perspectives", "total_rating_count"),
        "gamelist_metadata" to listOf("fanart_path"),
    )

    internal fun epoch(iso: String): Long {
        if (iso.isEmpty()) return 0
        return runCatching { OffsetDateTime.parse(iso).toEpochSecond() }.recoverCatching { Instant.parse(iso).epochSecond }.getOrDefault(0)
    }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    /** The parts of a ROM's answer this reads, so a game kept on the device does not keep the whole answer. */
    fun trimmed(rom: JSONObject): JSONObject {
        val out = JSONObject()
        for (key in KEPT) if (!rom.isNull(key)) out.put(key, rom.get(key))
        for ((key, inner) in KEPT_IN) rom.optJSONObject(key)?.let { from ->
            val kept = JSONObject()
            for (k in inner) if (!from.isNull(k)) kept.put(k, from.get(k))
            if (kept.length() > 0) out.put(key, kept)
        }
        return out
    }

    fun parse(server: String, rom: JSONObject, playedMs: Long = 0, similar: List<CatalogItem> = emptyList()): RommDetail? {
        val id = RommCatalog.romId(rom)
        if (id.isEmpty()) return null
        val text = RommCatalog::text
        val hltb = rom.optJSONObject("hltb_metadata")?.let {
            GameFacts.Hltb(it.optInt("main_story"), it.optInt("main_plus_extra"), it.optInt("completionist"), "")
        }?.takeUnless { it.isEmpty }
        val ss = rom.optJSONObject("ss_metadata")
        val igdb = rom.optJSONObject("igdb_metadata")
        val dumps = rom.optJSONObject("hasheous_metadata")
        val user = rom.optJSONObject("rom_user") ?: JSONObject()
        val ra = rom.optJSONObject("merged_ra_metadata")
        return RommDetail(
            id = id,
            hltb = hltb,
            logo = ss?.let { RommCatalog.asset(server, text(it, "logo_path")) },
            fanart = (ss?.let { text(it, "fanart_path") }.orEmpty().ifEmpty { rom.optJSONObject("gamelist_metadata")?.let { text(it, "fanart_path") }.orEmpty() })
                .let { RommCatalog.asset(server, it) },
            manual = RommCatalog.asset(server, text(rom, "path_manual")),
            alternativeNames = RommCatalog.strings(rom.optJSONArray("alternative_names")).distinct(),
            themes = RommCatalog.strings(igdb?.optJSONArray("themes")),
            perspectives = RommCatalog.strings(igdb?.optJSONArray("player_perspectives")),
            ratingCount = igdb?.optInt("total_rating_count") ?: 0,
            added = epoch(text(rom, "created_at")),
            lastPlayed = epoch(text(user, "last_played")),
            verified = DUMPS.filter { dumps?.optBoolean(it.first) == true }.map { it.second }.distinct(),
            collections = objects(rom.optJSONArray("user_collections")).mapNotNull { o ->
                val collection = o.optString("id")
                val name = text(o, "name")
                if (collection.isEmpty() || name.isEmpty()) null
                else RommCollection(if (o.optBoolean("is_smart")) RommCollection.Kind.SMART else RommCollection.Kind.REGULAR, collection, name, 0)
            },
            notes = objects(rom.optJSONArray("all_user_notes")).mapNotNull { o ->
                val note = o.optString("id")
                if (note.isEmpty()) null
                else RommNote(note, text(o, "title"), text(o, "content"), text(o, "username"), o.optString("user_id"), o.optBoolean("is_public"))
            },
            raId = text(rom, "ra_id"),
            badges = objects(ra?.optJSONArray("achievements")).mapNotNull { o ->
                val badge = text(o, "ra_id")
                val title = text(o, "title")
                if (badge.isEmpty() || title.isEmpty()) null
                else RommBadge(
                    badge, title, text(o, "description"), o.optInt("points"),
                    RommCatalog.asset(server, text(o, "badge_path")) ?: text(o, "badge_url").takeIf { it.startsWith("http") }?.let { RommCatalog.asset(server, it) },
                    RommCatalog.asset(server, text(o, "badge_path_lock")) ?: text(o, "badge_url_lock").takeIf { it.startsWith("http") }?.let { RommCatalog.asset(server, it) },
                    o.optInt("display_order"),
                )
            }.sortedBy { it.order },
            user = user,
            playedMs = playedMs,
            similar = similar,
        )
    }

    /** How long the account played a game, from the sessions RomM holds for it. */
    internal fun played(sessions: JSONArray?): Long = objects(sessions).sumOf { it.optLong("duration_ms").coerceAtLeast(0) }

    internal fun similar(server: String, array: JSONArray?): List<CatalogItem> =
        objects(array).mapNotNull { it.optJSONObject("rom") }.mapNotNull { RommCatalog.item(server, it) }.map(RommCatalog::alone)

    private fun file(context: Context, id: String) = File(context.filesDir, "stores/romm/details/$id.json")

    private val read = java.util.concurrent.ConcurrentHashMap<String, String>()

    private const val FILES_FRESH_MS = 10 * 60_000L
    private const val MOST_LISTED = 16

    private class Listed(val files: RommFiles, val at: Long)

    /** The files the server keeps with a game beyond the game's own, by ROM id, as its details last listed them. */
    val offered = mutableStateMapOf<String, List<RommFile>>()

    /** Which of those were chosen on the game's page, kept for the run so that an install tried again brings the same ones. */
    val chosen = mutableStateMapOf<String, Set<String>>()

    private val own = java.util.concurrent.ConcurrentHashMap<String, List<RommFile>>()

    /** The game's own files as its details last listed them, which say whether an installed game has a folder for the extra ones. */
    fun ownFiles(id: String): List<RommFile> = own[id].orEmpty()

    private val listed = object : LinkedHashMap<String, Listed>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Listed>): Boolean = size > MOST_LISTED
    }

    /**
     * A game's files as its page read them in the last minutes, handed out once: the install started from that page does not
     * ask the server for the game again, and an install tried again does.
     */
    fun takeFiles(id: String, nowMs: Long = android.os.SystemClock.elapsedRealtime()): RommFiles? =
        synchronized(listed) { listed.remove(id) }?.takeIf { nowMs - it.at in 0 until FILES_FRESH_MS }?.files

    /** Notes the files in a game's record as the server just sent it, for the install that may follow. */
    fun noteFiles(id: String, rom: JSONObject) {
        val files = RommCatalog.fileSet(rom).takeIf { it.own.isNotEmpty() } ?: return
        synchronized(listed) { listed[id] = Listed(files, android.os.SystemClock.elapsedRealtime()) }
        own[id] = files.own
        StoresState.post { if (files.extras.isEmpty()) offered.remove(id) else offered[id] = files.extras }
    }

    /**
     * Reads which files the server keeps with a game, once, for a page that shows a version it did not read itself: an
     * installed version of the game that was opened.
     */
    fun listFiles(context: Context, id: String) {
        if (own.containsKey(id) || !RommCatalog.ID.matches(id)) return
        synchronized(asking) { if (id in asking || !asking.add("files:$id")) return }
        val app = context.applicationContext
        Thread({
            val (server, token) = RommAuth.both(app)
            if (server.isNotEmpty() && token.isNotEmpty()) RommApi.rom(server, token, id).takeIf { it.ok }?.json?.let { noteFiles(id, it) }
            synchronized(asking) { asking.remove("files:$id") }
        }, "romm-files").start()
    }

    private fun keep(context: Context, id: String, text: String) {
        val f = file(context, id)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Keeps what was read of a game this visit with its install, so its page reads the same with the server out of reach. */
    fun keepRead(context: Context, id: String) {
        read[id]?.let { text -> runCatching { keep(context, id, text) } }
    }

    fun stored(context: Context, id: String): RommDetail? = runCatching {
        val o = JSONObject(file(context, id).readText())
        parse(o.optString("server"), o.getJSONObject("rom"), o.optLong("played"))
    }.getOrNull()

    /** Reads a game from the server, or from the copy kept with an installed game when the server cannot be reached. */
    /** [early] is given the game as soon as it is read, before its play time and the games like it are. */
    fun fetch(context: Context, id: String, early: (RommDetail) -> Unit = {}): RommDetail? {
        val (server, token) = RommAuth.both(context)
        if (server.isEmpty() || token.isEmpty() || !RommCatalog.ID.matches(id)) return stored(context, id)
        val rom = RommApi.rom(server, token, id).takeIf { it.ok }?.json ?: return stored(context, id)
        noteFiles(id, rom)
        parse(server, rom, 0)?.let(early)
        val sessions = java.util.concurrent.FutureTask { RommApi.playSessions(server, token, id) }
        Thread(sessions, "romm-detail-played").start()
        val alike = similar(server, RommApi.similar(server, token, id).takeIf { it.ok }?.array)
        val played = played(runCatching { sessions.get() }.getOrNull()?.takeIf { it.ok }?.array)
        val text = JSONObject().put("server", server).put("rom", trimmed(rom)).put("played", played).toString()
        read[id] = text
        if (RommInstalls.find(context, id) != null) runCatching {
            keep(context, id, text)
            val now = RommCatalog.item(server, rom)?.extra.orEmpty()
            RommInstalls.learn(context, id, listOf(RommCatalog.TITLE_ID, RommCatalog.SAVE_TARGET, RommCatalog.SAVE_LAYOUT, RommCatalog.ADDED, RommCatalog.UPDATED).associateWith { now[it].orEmpty() })
        }
        return parse(server, rom, played, alike)?.also { d ->
            StoresState.logLine(
                "RomM game $id: time to beat ${d.hltb != null}, achievements ${d.badges.size}, manual ${d.manual != null}, logo ${d.logo != null}, " +
                    "fan art ${d.fanart != null}, notes ${d.notes.size}, collections ${d.collections.size}, similar ${alike.size}, played ${played / 1000}s, " +
                    "title id ${!rom.isNull("title_id") && rom.optString("title_id").isNotEmpty()}",
            )
        }
    }

    fun load(context: Context, id: String, force: Boolean = false) {
        if (!force && id in known) return
        synchronized(asking) { if (!asking.add(id)) return }
        val app = context.applicationContext
        Thread({
            val read = runCatching { fetch(app, id) { first -> StoresState.post { if (id !in known) known[id] = first } } }.getOrNull()
            synchronized(asking) { asking.remove(id) }
            if (read != null) StoresState.post { known[id] = read }
        }, "romm-detail").start()
    }

    fun forget(context: Context, id: String) {
        file(context, id).delete()
    }

    fun clear(context: Context) {
        File(context.filesDir, "stores/romm/details").deleteRecursively()
        read.clear()
        own.clear()
        synchronized(listed) { listed.clear() }
        StoresState.post { known.clear(); offered.clear(); chosen.clear() }
    }

    /** A similar game opened from a page, so its own page can find it. */
    fun item(id: String): CatalogItem? = known.values.firstNotNullOfOrNull { d -> d.similar.firstOrNull { it.id == id } }
}
