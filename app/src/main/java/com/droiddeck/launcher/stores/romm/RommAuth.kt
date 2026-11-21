package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONObject

internal object RommAuth {
    enum class Problem { UNREACHABLE, NOT_ROMM, TOO_OLD, BAD_TOKEN, BAD_LOGIN, NO_TOKEN }

    class Result(val server: String, val token: String, val user: JSONObject?, val problem: Problem?)

    private val SCOPES = listOf("me.read", "roms.read", "platforms.read", "collections.read", "firmware.read")
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

    /** Signs in with an account's password, which RomM turns into a client token, or with a token when no username is given. */
    fun verify(typed: String, username: String, secret: String, tokenName: String = "DroidDeck"): Result {
        val servers = candidates(typed)
        var problem = Problem.UNREACHABLE
        for (server in servers) {
            val beat = RommApi.heartbeat(server)
            if (beat.code == 0) continue
            val version = beat.json?.optJSONObject("SYSTEM")?.optString("VERSION").orEmpty()
            if (!beat.ok || version.isEmpty()) { problem = Problem.NOT_ROMM; continue }
            if (!supported(version)) return Result(server, "", null, Problem.TOO_OLD)
            return if (username.isBlank()) withToken(server, secret) else withPassword(server, username.trim(), secret, tokenName)
        }
        return Result(servers.firstOrNull().orEmpty(), "", null, problem)
    }

    private fun withToken(server: String, token: String): Result {
        val user = RommApi.me(server, RommApi.bearer(token)).takeIf { it.ok }?.json
        return if (user == null || user.optString("username").isEmpty()) Result(server, "", null, Problem.BAD_TOKEN) else Result(server, token, user, null)
    }

    private fun withPassword(server: String, username: String, password: String, tokenName: String): Result {
        val login = RommApi.basic(username, password)
        val user = RommApi.me(server, login).takeIf { it.ok }?.json
        if (user == null || user.optString("username").isEmpty()) return Result(server, "", null, Problem.BAD_LOGIN)
        val allowed = user.optJSONArray("oauth_scopes")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
        val scopes = if (allowed == null) SCOPES else SCOPES.filter { it in allowed }
        val token = RommApi.createToken(server, login, tokenName, scopes).takeIf { it.ok }?.json?.optString("raw_token").orEmpty()
        return if (scopes.isEmpty() || tokenIn(token) == null) Result(server, "", null, Problem.NO_TOKEN) else Result(server, token, user, null)
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
