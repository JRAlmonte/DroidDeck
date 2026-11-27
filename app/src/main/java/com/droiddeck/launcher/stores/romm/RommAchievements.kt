package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAchievements
import org.json.JSONObject
import java.io.File

/** A game's RetroAchievements as its RomM server holds them, and the ones the account's RetroAchievements user earned. */
object RommAchievements : StoreAchievements(Store.ROMM.id, "RetroAchievements") {
    private val ZONED = Regex(".*(Z|[+-]\\d{2}:?\\d{2})$")

    private fun stamp(date: String): Long {
        val text = date.trim().replace(' ', 'T')
        return if (text.isEmpty()) 0 else epochSeconds(if (ZONED.matches(text)) text else text + "Z")
    }

    /** When each achievement of the game [raId] was earned, by achievement id, from the account's `ra_progression`. */
    internal fun earned(me: JSONObject?, raId: String): Map<String, Long> {
        val results = me?.optJSONObject("ra_progression")?.optJSONArray("results") ?: return emptyMap()
        val game = (0 until results.length()).mapNotNull { results.optJSONObject(it) }.firstOrNull { it.optString("rom_ra_id") == raId } ?: return emptyMap()
        val list = game.optJSONArray("earned_achievements") ?: return emptyMap()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("ra_id").ifEmpty { o.optString("id") }
            if (id.isEmpty()) null else id to stamp(o.optString("date_hardcore").ifEmpty { o.optString("date") })
        }.toMap()
    }

    /**
     * The account's progress over every game in `ra_progression`, which RomM fills from the RetroAchievements user the
     * account names. Null for an account that names none, or whose list is empty.
     */
    internal fun summary(me: JSONObject?): RommProgress? {
        val results = me?.optJSONObject("ra_progression")?.optJSONArray("results") ?: return null
        val games = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
        if (games.isEmpty()) return null
        return RommProgress(games.size, games.sumOf { it.optInt("num_awarded").coerceAtLeast(0) }, games.sumOf { it.optInt("max_possible").coerceAtLeast(0) })
    }

    internal fun list(detail: RommDetail, earned: Map<String, Long>, points: (Int) -> String): List<Achievement> = detail.badges.map { b ->
        val about = listOf(b.description, if (b.points > 0) points(b.points) else "").filter { it.isNotEmpty() }.joinToString(" · ")
        Achievement(b.raId, b.title, about, hidden = false, unlocked = b.raId in earned, unlockedAt = earned[b.raId] ?: 0, icon = b.badge, iconLocked = b.badgeLocked ?: b.badge)
    }

    override fun request(context: Context, game: Game): Result {
        val (server, token) = RommAuth.both(context)
        if (server.isEmpty() || token.isEmpty()) return Result.Failed(Reason.SIGNED_OUT)
        val detail = RommDetails.known[game.id] ?: RommDetails.fetch(context, game.id) ?: return Result.Failed(Reason.UNREACHABLE)
        if (detail.badges.isEmpty()) return Result.Failed(Reason.NO_SERVICE)
        val me = RommApi.me(server, RommApi.bearer(token))
        if (me.code == 401 || me.code == 403) return Result.Failed(Reason.SIGNED_OUT)
        if (!me.ok) return Result.Failed(Reason.UNREACHABLE)
        return Result.Found(list(detail, earned(me.json, detail.raId)) { context.resources.getQuantityString(R.plurals.romm_achievement_points, it, it) })
    }

    /**
     * After a game with achievements was played, has the server read the account's progress from RetroAchievements again.
     * A server whose account names no RetroAchievements user answers that there is none, and nothing changes.
     */
    internal fun refresh(context: Context, romId: String) {
        val detail = RommDetails.known[romId] ?: RommDetails.stored(context, romId)
        if (detail == null || detail.raId.isEmpty() || !RommSync.allows(context, "me.write")) return
        val user = com.droiddeck.launcher.stores.StoreAccounts.read(context, Store.ROMM)?.optString("user_id").orEmpty()
        if (user.toLongOrNull() == null) return
        val (server, token) = RommAuth.both(context)
        val answer = RommApi.call(server, "/api/users/$user/ra/refresh", token, body = JSONObject().put("incremental", true).toString())
        if (answer.ok) com.droiddeck.launcher.stores.StoresState.post { invalidate() }
    }

    internal fun clear(context: Context) {
        File(context.filesDir, "achievements/$source").deleteRecursively()
    }
}
