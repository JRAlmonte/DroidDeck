package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONObject

internal object RommAuth {
    enum class Problem { UNREACHABLE, NOT_ROMM, TOO_OLD, BAD_TOKEN }

    class Result(val server: String, val user: JSONObject?, val problem: Problem?)

    private val TOKEN = Regex("rmm_[0-9a-fA-F]{64}")
    private val MINIMUM = listOf(4, 8)

    fun server(context: Context): String = StoreAccounts.read(context, Store.ROMM)?.optString("server").orEmpty()

    fun token(context: Context): String = StoreAccounts.read(context, Store.ROMM)?.optString("access_token").orEmpty()

    fun tokenIn(text: String): String? = TOKEN.find(text)?.value

    /** The addresses to try for what was typed: as given when it names a scheme, otherwise HTTPS then HTTP. */
    fun candidates(typed: String): List<String> {
        val raw = typed.trim()
        val named = "://" in raw
        val scheme = raw.substringBefore("://", "").lowercase()
        var rest = (if (named) raw.substringAfter("://") else raw).trimEnd('/')
        if (rest.endsWith("/api", ignoreCase = true)) rest = rest.dropLast(4).trimEnd('/')
        if (rest.isEmpty() || rest.any { it.isWhitespace() }) return emptyList()
        return when {
            scheme == "http" || scheme == "https" -> listOf("$scheme://$rest")
            named -> emptyList()
            else -> listOf("https://$rest", "http://$rest")
        }
    }

    fun supported(version: String): Boolean {
        val parts = version.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() }
        val major = parts.getOrNull(0) ?: return true
        val minor = parts.getOrNull(1) ?: 0
        return major > MINIMUM[0] || (major == MINIMUM[0] && minor >= MINIMUM[1])
    }

    fun verify(typed: String, token: String): Result {
        val servers = candidates(typed)
        var problem = Problem.UNREACHABLE
        for (server in servers) {
            val beat = RommApi.heartbeat(server)
            if (beat.code == 0) continue
            val version = beat.json?.optJSONObject("SYSTEM")?.optString("VERSION").orEmpty()
            if (!beat.ok || version.isEmpty()) { problem = Problem.NOT_ROMM; continue }
            if (!supported(version)) return Result(server, null, Problem.TOO_OLD)
            val user = RommApi.me(server, token).takeIf { it.ok }?.json
            return if (user == null || user.optString("username").isEmpty()) Result(server, null, Problem.BAD_TOKEN) else Result(server, user, null)
        }
        return Result(servers.firstOrNull().orEmpty(), null, problem)
    }

    fun save(context: Context, server: String, token: String, user: JSONObject) {
        StoreAccounts.write(
            context,
            Store.ROMM,
            JSONObject()
                .put("access_token", token)
                .put("server", server)
                .put("username", user.optString("username"))
                .put("user_id", user.optLong("id")),
        )
    }
}
