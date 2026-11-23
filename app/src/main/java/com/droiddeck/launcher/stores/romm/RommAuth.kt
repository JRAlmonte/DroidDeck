package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONObject

internal object RommAuth {
    enum class Problem { UNREACHABLE, NOT_ROMM, TOO_OLD, BAD_TOKEN, BAD_LOGIN, NO_TOKEN, PAIR_UNSUPPORTED, PAIR_DENIED, PAIR_EXPIRED }

    /** A code waiting for the user to approve it at [url]; [deviceCode] is what the server is then asked with. */
    class Pairing(val server: String, val version: String, val deviceCode: String, val userCode: String, val url: String, val expiresIn: Int, val interval: Int)

    /** [scopes] is what the token may do, known only for a token this sign-in created. */
    class Result(val server: String, val token: String, val user: JSONObject?, val problem: Problem?, val scopes: List<String>? = null, val version: String = "")

    private val SCOPES = listOf(
        "me.read", "roms.read", "platforms.read", "collections.read", "firmware.read",
        "assets.read", "assets.write", "devices.read", "devices.write", "roms.user.read", "roms.user.write", "collections.write",
    )
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

    /** The RomM server at what was typed and its version, or why there is none. */
    private fun reach(typed: String): Result {
        val servers = candidates(typed)
        var problem = Problem.UNREACHABLE
        for (server in servers) {
            val beat = RommApi.heartbeat(server)
            if (beat.code == 0) continue
            val version = beat.json?.optJSONObject("SYSTEM")?.optString("VERSION").orEmpty()
            if (!beat.ok || version.isEmpty()) { problem = Problem.NOT_ROMM; continue }
            return Result(server, "", null, if (supported(version)) null else Problem.TOO_OLD, version = version)
        }
        return Result(servers.firstOrNull().orEmpty(), "", null, problem)
    }

    /** Signs in with an account's password, which RomM turns into a client token, or with a token when no username is given. */
    fun verify(typed: String, username: String, secret: String, tokenName: String = "DroidDeck"): Result {
        val found = reach(typed)
        if (found.problem != null) return Result(found.server, "", null, found.problem)
        val result = if (username.isBlank()) withToken(found.server, secret) else withPassword(found.server, username.trim(), secret, tokenName)
        return Result(result.server, result.token, result.user, result.problem, result.scopes, found.version)
    }

    /** Asks the server for a code to approve in its web UI. A server without that sign-in answers [Problem.PAIR_UNSUPPORTED]. */
    fun pair(typed: String, deviceId: String, name: String = "DroidDeck"): Pair<Pairing?, Problem?> {
        val found = reach(typed)
        if (found.problem != null) return null to found.problem
        val answer = RommApi.deviceInit(found.server, deviceId, name, SCOPES)
        if (answer.code == 0) return null to Problem.UNREACHABLE
        val o = answer.takeIf { it.ok }?.json
        val device = o?.optString("device_code").orEmpty()
        val code = o?.optString("user_code").orEmpty()
        if (o == null || device.isEmpty() || code.isEmpty()) return null to Problem.PAIR_UNSUPPORTED
        val path = o.optString("verification_path_complete").ifEmpty { o.optString("verification_path") }.ifEmpty { "/pair/device" }
        return Pairing(found.server, found.version, device, code, found.server + path, o.optInt("expires_in", 600).coerceIn(30, 3600), o.optInt("interval", 5).coerceIn(1, 60)) to null
    }

    /** Asks until the code is approved, refused or run out; null when [cancelled] says to stop. */
    fun awaitPairing(pairing: Pairing, cancelled: () -> Boolean, sleep: (Long) -> Unit = { Thread.sleep(it) }): Result? {
        fun failed(problem: Problem) = Result(pairing.server, "", null, problem)
        var interval = pairing.interval
        var waited = 0
        while (waited < pairing.expiresIn) {
            for (second in 0 until interval) { if (cancelled()) return null; sleep(1000) }
            waited += interval
            if (cancelled()) return null
            val answer = RommApi.deviceToken(pairing.server, pairing.deviceCode)
            if (answer.ok) {
                val o = answer.json
                val token = o?.optString("access_token").orEmpty()
                if (tokenIn(token) == null) return failed(Problem.NO_TOKEN)
                val scopes = o?.optJSONArray("scopes")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                val user = RommApi.me(pairing.server, RommApi.bearer(token)).takeIf { it.ok }?.json
                return if (user == null || user.optString("username").isEmpty()) failed(Problem.BAD_TOKEN) else Result(pairing.server, token, user, null, scopes, pairing.version)
            }
            when (answer.json?.optString("detail")) {
                "access_denied" -> return failed(Problem.PAIR_DENIED)
                "expired_token" -> return failed(Problem.PAIR_EXPIRED)
                "slow_down" -> interval += 5
            }
        }
        return failed(Problem.PAIR_EXPIRED)
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
        return if (scopes.isEmpty() || tokenIn(token) == null) Result(server, "", null, Problem.NO_TOKEN) else Result(server, token, user, null, scopes)
    }

    fun save(context: Context, server: String, token: String, user: JSONObject, scopes: List<String>? = null, version: String = "") {
        StoreAccounts.write(
            context,
            Store.ROMM,
            JSONObject()
                .put("access_token", token)
                .put("server", server)
                .put("username", user.optString("username"))
                .put("user_id", user.optLong("id"))
                .put("version", version)
                .apply { if (scopes != null) put("scopes", org.json.JSONArray(scopes)) },
        )
        RommSync.signedIn(context)
    }
}
