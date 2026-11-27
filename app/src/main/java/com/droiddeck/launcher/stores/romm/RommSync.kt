package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONObject

internal object RommSync {
    enum class Access { OK, SIGNED_OUT, OLD_SERVER, NO_ACCESS }

    private val MINIMUM = listOf(4, 9)
    private const val REFUSED = "sync_refused."
    private const val REFUSED_MS = 24L * 3600 * 1000
    private const val VERSION = "server_version"
    private const val DEVICE = "device_id"
    private const val TOLD = "device_told"
    private const val LOOKED = "device_looked"
    internal const val BOUND = "device_id"

    fun signedIn(context: Context) {
        val prefs = RommPrefs.get(context)
        prefs.edit().remove(VERSION).remove(DEVICE).remove(TOLD).remove(LOOKED)
            .apply { prefs.all.keys.filter { it.startsWith(REFUSED) }.forEach { remove(it) } }.apply()
    }

    /** Notes that the server refused what [scope] covers. Nothing under that scope is sent for a day; the rest still syncs. */
    fun denied(context: Context, scope: String) {
        RommPrefs.get(context).edit().putLong(REFUSED + scope, System.currentTimeMillis()).apply()
    }

    /** The server's version as it was last read from the server ([learnVersion]), else as the sign-in recorded it, else empty. */
    fun version(context: Context): String =
        RommPrefs.get(context).getString(VERSION, "").orEmpty().ifEmpty { StoreAccounts.read(context, Store.ROMM)?.optString("version").orEmpty() }

    /** Whether the server is [minimum] (major, minor, see [RommSince]) or newer. One whose version is not known counts as new. */
    fun serverHas(context: Context, minimum: List<Int>): Boolean = RommAuth.atLeast(version(context), minimum)

    /** Reads the server's version again: a server is upgraded without anyone signing in to it again. Must not run on the main thread. */
    fun learnVersion(context: Context) {
        val server = RommAuth.server(context).ifEmpty { return }
        val found = RommApi.heartbeat(server).takeIf { it.ok }?.json?.optJSONObject("SYSTEM")?.optString("VERSION").orEmpty()
        if (found.isNotEmpty() && RommAuth.server(context) == server) RommPrefs.get(context).edit().putString(VERSION, found).apply()
    }

    private fun scopes(account: JSONObject): Set<String>? =
        account.optJSONArray("scopes")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }

    fun allows(context: Context, scope: String): Boolean =
        StoreAccounts.read(context, Store.ROMM)?.let(::scopes)?.contains(scope) ?: true

    /**
     * Whether the sign-in may do what [scope] covers; saves need `assets.write`, play data and favourites less.
     * Asks the server its version when the sign-in did not record one, so it must not run on the main thread.
     */
    fun access(context: Context, scope: String = "assets.write", nowMs: Long = System.currentTimeMillis()): Access {
        val account = StoreAccounts.read(context, Store.ROMM)
        val server = account?.optString("server").orEmpty()
        if (server.isEmpty() || account?.optString("access_token").isNullOrEmpty()) return Access.SIGNED_OUT
        val prefs = RommPrefs.get(context)
        val refused = prefs.getLong(REFUSED + scope, 0)
        if ((refused > 0 && nowMs - refused in 0 until REFUSED_MS) || scopes(account!!)?.contains(scope) == false) return Access.NO_ACCESS
        val version = prefs.getString(VERSION, "").orEmpty().ifEmpty { account.optString("version") }.ifEmpty {
            RommApi.heartbeat(server).takeIf { it.ok }?.json?.optJSONObject("SYSTEM")?.optString("VERSION").orEmpty()
                .also { if (it.isNotEmpty()) prefs.edit().putString(VERSION, it).apply() }
        }
        return if (version.isNotEmpty() && !RommAuth.atLeast(version, MINIMUM)) Access.OLD_SERVER else Access.OK
    }

    /**
     * The device a sign-in with a code bound its token to. Only that token may take what RomM's web page sends to the device,
     * and play time and saves belong under it. A sign-in made before this was kept is asked once which device its token has.
     */
    @Synchronized
    fun boundDevice(context: Context): String? {
        val account = StoreAccounts.read(context, Store.ROMM) ?: return null
        account.optString(BOUND).takeIf { it.isNotEmpty() }?.let { return it }
        val tokenId = account.optLong("token_id")
        val prefs = RommPrefs.get(context)
        if (tokenId <= 0 || prefs.getBoolean(LOOKED, false)) return null
        val server = account.optString("server")
        val token = account.optString("access_token")
        if (RommApi.heartbeat(server).code == 0) return null
        val found = RommAuth.boundDevice(server, token, tokenId)
        prefs.edit().putBoolean(LOOKED, true).apply()
        if (found.isEmpty()) return null
        runCatching { StoreAccounts.write(context, Store.ROMM, account.put(BOUND, found)) }
        return found
    }

    /** Tells the server which app version runs on the bound device and that it takes installs sent to it, once per version. */
    private fun describe(context: Context, id: String) {
        val prefs = RommPrefs.get(context)
        val mark = id + "|" + com.droiddeck.launcher.BuildConfig.VERSION_NAME
        if (prefs.getString(TOLD, "") == mark || !allows(context, "devices.write")) return
        val body = JSONObject().put("client", RommApi.USER_AGENT).put("platform", "android").put("client_version", com.droiddeck.launcher.BuildConfig.VERSION_NAME)
            .put("capabilities", JSONObject().put("remote_install", true))
        val (server, token) = RommAuth.both(context)
        val answer = RommApi.call(server, "/api/devices/${RommApi.encode(id)}", token, "PUT", body.toString())
        if (answer.code != 0 && answer.code < 500) prefs.edit().putString(TOLD, mark).apply()
    }

    /** This install's device on the server: the one its token is bound to, or one registered once; saves still sync without it, the server then cannot say which device has which. */
    @Synchronized
    fun deviceId(context: Context): String? {
        boundDevice(context)?.let { describe(context, it); return it }
        val prefs = RommPrefs.get(context)
        prefs.getString(DEVICE, null)?.let { return it.ifEmpty { null } }
        if (!allows(context, "devices.write")) return null
        val body = JSONObject().put("name", android.os.Build.MODEL ?: "Android").put("platform", "android").put("client", RommApi.USER_AGENT)
            .put("client_version", com.droiddeck.launcher.BuildConfig.VERSION_NAME).put("allow_existing", true)
        val (server, token) = RommAuth.both(context)
        val answer = RommApi.call(server, "/api/devices", token, body = body.toString())
        val id = answer.takeIf { it.ok }?.json?.optString("device_id").orEmpty()
        if (id.isNotEmpty() || answer.code == 401 || answer.code == 403) prefs.edit().putString(DEVICE, id).apply()
        return id.ifEmpty { null }
    }

    /** Forgets what was synced of every game's saves, which is recorded under ROM ids that mean other games on another server. */
    fun forgetSaves(context: Context) {
        val prefs = RommPrefs.get(context)
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith("cloud_") }.forEach { remove(it) } }.apply()
        com.droiddeck.launcher.stores.CloudSaves.forgetDirty(context, Store.ROMM)
    }

    fun enabled(context: Context, id: String): Boolean = !RommPrefs.get(context).getBoolean("cloud_off_$id", false)

    fun setEnabled(context: Context, id: String, on: Boolean) {
        RommPrefs.get(context).edit().apply { if (on) remove("cloud_off_$id") else putBoolean("cloud_off_$id", true) }.apply()
    }

    private const val LAUNCH_WAIT_MS = 8_000L

    /**
     * Opens the play session and, for a game whose emulator's saves are synced, takes newer saves from the server before [start] runs.
     * The game starts after [LAUNCH_WAIT_MS] whatever the server does; saves that arrive later are left for the next launch.
     */
    fun beforeLaunch(context: Context, emulatorId: String, guestPath: String, start: () -> Unit) {
        val app = context.applicationContext
        val entry = RommInstalls.byGuestPath(app, guestPath)
        val session = com.droiddeck.launcher.session.SessionState
        val takes = !session.running && session.phase in setOf(com.droiddeck.launcher.session.SessionPhase.IDLE, com.droiddeck.launcher.session.SessionPhase.FAILED)
        if (entry == null || !takes) { start(); return }
        RommActivity.started(app, entry.id)
        RommActivity.beat(app, entry.id)
        if (emulatorId != entry.runner || RommSaves.layout(app, entry) == null || !enabled(app, entry.id)) { start(); return }
        com.droiddeck.launcher.stores.CloudSaves.markDirty(app, Store.ROMM, entry.id)
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val go = Runnable { if (started.compareAndSet(false, true)) start() }
        main.postDelayed(go, LAUNCH_WAIT_MS)
        Thread({
            runCatching { com.droiddeck.launcher.stores.CloudSaves.download(app, Store.ROMM, entry.id, claim = { !started.get() }) }
            main.post(go)
        }, "romm-cloud-down").start()
    }

    fun cloud(context: Context, id: String, layout: RommSaves.Layout): RommCloud {
        val (server, token) = RommAuth.both(context)
        return RommCloud(server, token, id, deviceId(context), layout) { denied(context, "assets.write") }
    }
}
