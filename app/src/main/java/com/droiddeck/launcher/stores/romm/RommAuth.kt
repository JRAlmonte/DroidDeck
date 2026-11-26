package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONObject

internal object RommAuth {
    enum class Problem { UNREACHABLE, UNTRUSTED, PLAIN, NOT_ROMM, TOO_OLD, BAD_TOKEN, BAD_LOGIN, NO_TOKEN, PAIR_UNSUPPORTED, PAIR_DENIED, PAIR_EXPIRED }

    class Pairing(val server: String, val version: String, val deviceCode: String, val userCode: String, val url: String, val expiresIn: Int, val interval: Int)

    /** [scopes] is what the token may do, known only for a token this sign-in created. */
    class Result(
        val server: String, val token: String, val user: JSONObject?, val problem: Problem?, val scopes: List<String>? = null, val version: String = "",
        val tokenId: Long? = null, val deviceId: String = "",
    )

    private val SCOPES = listOf(
        "me.read", "me.write", "roms.read", "platforms.read", "collections.read", "firmware.read",
        "assets.read", "assets.write", "devices.write", "roms.user.read", "roms.user.write", "collections.write",
    )
    private val TOKEN = Regex("rmm_[0-9a-fA-F]{64}")
    private val MINIMUM = listOf(4, 8)

    fun server(context: Context): String = StoreAccounts.read(context, Store.ROMM)?.optString("server").orEmpty()

    fun token(context: Context): String = StoreAccounts.read(context, Store.ROMM)?.optString("access_token").orEmpty()

    fun tokenIn(text: String): String? = TOKEN.find(text)?.value

    private val LOCAL_NAMES = listOf(".local", ".lan", ".home", ".internal", ".home.arpa")
    private val IPV4 = Regex("(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}")

    /** Whether an address without a scheme names a machine on the user's own network, where a server often has no certificate. */
    fun local(address: String): Boolean {
        val authority = address.substringBefore('/').substringAfterLast('@').lowercase()
        if (authority.startsWith("[")) {
            val v6 = authority.substring(1).substringBefore(']')
            return v6 == "::1" || v6.startsWith("fc") || v6.startsWith("fd") || v6.startsWith("fe80:")
        }
        val host = authority.substringBefore(':')
        IPV4.matchEntire(host)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        return host.isNotEmpty() && ('.' !in host || LOCAL_NAMES.any { host.endsWith(it) })
    }

    fun candidates(typed: String): List<String> {
        val raw = typed.trim()
        val named = "://" in raw
        val scheme = raw.substringBefore("://", "").lowercase()
        var rest = (if (named) raw.substringAfter("://") else raw).trimEnd('/')
        if (rest.endsWith("/api", ignoreCase = true)) rest = rest.dropLast(4).trimEnd('/')
        // A name and password in the address would be kept with it, in the clear, wherever the address is.
        if (rest.isEmpty() || rest.any { it.isWhitespace() } || '@' in rest.substringBefore('/')) return emptyList()
        return when {
            scheme == "http" || scheme == "https" -> listOf("$scheme://$rest")
            named -> emptyList()
            local(rest) -> listOf("https://$rest", "http://$rest")
            else -> listOf("https://$rest")
        }
    }

    /** Whether a sign-in to the address as typed goes out without encryption. */
    fun plain(typed: String): Boolean = candidates(typed).firstOrNull()?.startsWith("http://") == true

    fun supported(version: String): Boolean {
        val parts = version.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() }
        val major = parts.getOrNull(0) ?: return true
        val minor = parts.getOrNull(1) ?: 0
        return major > MINIMUM[0] || (major == MINIMUM[0] && minor >= MINIMUM[1])
    }

    /**
     * Finds the server without sending it anything secret. A certificate the device refuses ends the search, and a server
     * that answers only without encryption is named with [Problem.PLAIN] unless the address was typed with `http://`, so
     * nobody on the network can turn an encrypted sign-in into a readable one.
     */
    private fun reach(typed: String): Result {
        val servers = candidates(typed)
        var problem = Problem.UNREACHABLE
        for ((index, server) in servers.withIndex()) {
            val beat = RommApi.heartbeat(server)
            if (beat.untrusted) return Result(server, "", null, Problem.UNTRUSTED)
            if (beat.code == 0) continue
            val version = beat.json?.optJSONObject("SYSTEM")?.optString("VERSION").orEmpty()
            if (!beat.ok || version.isEmpty()) { problem = Problem.NOT_ROMM; continue }
            if (index > 0 && server.startsWith("http://")) return Result(server, "", null, Problem.PLAIN)
            return Result(server, "", null, if (supported(version)) null else Problem.TOO_OLD, version = version)
        }
        return Result(servers.firstOrNull().orEmpty(), "", null, problem)
    }

    fun verify(typed: String, username: String, secret: String, tokenName: String = "DroidDeck"): Result {
        val found = reach(typed)
        if (found.problem != null) return Result(found.server, "", null, found.problem)
        val result = if (username.isBlank()) withToken(found.server, secret) else withPassword(found.server, username.trim(), secret, tokenName)
        return Result(result.server, result.token, result.user, result.problem, result.scopes, found.version, result.tokenId)
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
        // The page to open is on the server that was asked: a path the server sends must stay a path there.
        val path = listOf(o.optString("verification_path_complete"), o.optString("verification_path")).firstOrNull(::pathOnly) ?: "/pair/device"
        return Pairing(found.server, found.version, device, code, found.server + path, o.optInt("expires_in", 600).coerceIn(30, 3600), o.optInt("interval", 5).coerceIn(1, 60)) to null
    }

    internal fun pathOnly(path: String): Boolean =
        path.startsWith("/") && !path.startsWith("//") && path.none { it == '\\' || it == '@' || it.isWhitespace() || it.isISOControl() }

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
                if (cancelled()) return null
                val scopes = o?.optJSONArray("scopes")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                val user = RommApi.me(pairing.server, RommApi.bearer(token)).takeIf { it.ok }?.json
                if (cancelled()) return null
                if (user == null || user.optString("username").isEmpty()) return failed(Problem.BAD_TOKEN)
                val device = o?.optString("device_id").orEmpty()
                val id = pairedTokenId(pairing.server, token, device)
                if (cancelled()) return null
                return Result(pairing.server, token, user, null, scopes, pairing.version, id, device)
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
        val created = RommApi.createToken(server, login, tokenName, scopes).takeIf { it.ok }?.json
        val token = created?.optString("raw_token").orEmpty()
        if (scopes.isEmpty() || tokenIn(token) == null) return Result(server, "", null, Problem.NO_TOKEN)
        return Result(server, token, user, null, scopes, tokenId = created?.optLong("id")?.takeIf { it > 0 })
    }

    // RomM's pairing answer names the device and not the token, so the newest token of that device is the one just made.
    private fun pairedTokenId(server: String, token: String, deviceId: String): Long? {
        if (deviceId.isEmpty()) return null
        val tokens = RommApi.tokens(server, token).takeIf { it.ok }?.array ?: return null
        return (0 until tokens.length()).mapNotNull { tokens.optJSONObject(it) }
            .filter { it.optString("device_id") == deviceId }
            .maxOfOrNull { it.optLong("id") }?.takeIf { it > 0 }
    }

    /** Deletes, on the server, the token a sign-in here created. A pasted token is the user's own and stays. */
    fun revoke(account: JSONObject?): Boolean {
        if (account == null || !revocable(account)) return false
        return RommApi.deleteToken(account.optString("server"), account.optString("access_token"), account.optLong("token_id")).ok
    }

    /** Whether [account]'s token was made by a sign-in here that may also delete it. */
    fun revocable(account: JSONObject?): Boolean {
        account ?: return false
        val scopes = account.optJSONArray("scopes") ?: return false
        return account.optLong("token_id") > 0 && (0 until scopes.length()).any { scopes.optString(it) == "me.write" } &&
            account.optString("server").isNotEmpty() && account.optString("access_token").isNotEmpty()
    }

    /** Deletes the token, asking again when the server does not answer, and tells the user when it is still there: it never runs out. */
    fun revokeOrTell(context: Context, account: JSONObject?, sleep: (Long) -> Unit = { Thread.sleep(it) }) {
        if (!revocable(account)) return
        for (wait in listOf(0L, 2_000L, 6_000L)) {
            if (wait > 0) sleep(wait)
            if (revoke(account)) return
        }
        com.droiddeck.launcher.stores.StoresState.logLine("RomM sign-out: the token was not deleted on the server")
        com.droiddeck.launcher.stores.StoresState.post {
            android.widget.Toast.makeText(context.applicationContext, com.droiddeck.launcher.R.string.stores_romm_token_kept, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** The device the server bound a token to when it was made by signing in with a code, or empty. */
    fun boundDevice(server: String, token: String, tokenId: Long): String {
        val list = RommApi.tokens(server, token).takeIf { it.ok }?.array ?: return ""
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.firstOrNull { it.optLong("id") == tokenId }
            ?.takeUnless { it.isNull("device_id") }?.optString("device_id").orEmpty()
    }

    fun save(context: Context, server: String, token: String, user: JSONObject, scopes: List<String>? = null, version: String = "", tokenId: Long? = null, deviceId: String = "") {
        val before = StoreAccounts.read(context, Store.ROMM)
        if (before != null) {
            val sameServer = before.optString("server") == server
            if (!sameServer || before.optLong("user_id") != user.optLong("id")) RommBackend.forget(context.applicationContext, before, games = !sameServer)
            else if (before.optString("access_token") != token) Thread({ revokeOrTell(context, before) }, "romm-revoke").start()
        }
        StoreAccounts.write(
            context,
            Store.ROMM,
            JSONObject()
                .put("access_token", token)
                .put("server", server)
                .put("username", user.optString("username"))
                .put("user_id", user.optLong("id"))
                .put("version", version)
                .apply { if (scopes != null) put("scopes", org.json.JSONArray(scopes)) }
                .apply { if (tokenId != null) put("token_id", tokenId) }
                .apply { if (deviceId.isNotEmpty()) put(RommSync.BOUND, deviceId) },
        )
        RommSync.signedIn(context)
    }
}
