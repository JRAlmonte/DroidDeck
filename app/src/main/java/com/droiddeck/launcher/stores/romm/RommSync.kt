package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONObject

/** Whether this sign-in may write to the server, and the device the server knows this install as. */
internal object RommSync {
    enum class Access { OK, SIGNED_OUT, OLD_SERVER, NO_ACCESS }

    private val MINIMUM = listOf(4, 9)
    private const val DENIED = "sync_denied"
    private const val VERSION = "server_version"
    private const val DEVICE = "device_id"

    fun signedIn(context: Context) {
        RommPrefs.get(context).edit().remove(DENIED).remove(VERSION).remove(DEVICE).apply()
    }

    fun denied(context: Context) {
        RommPrefs.get(context).edit().putBoolean(DENIED, true).apply()
    }

    internal fun recent(version: String): Boolean {
        val parts = version.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() }
        val major = parts.getOrNull(0) ?: return true
        val minor = parts.getOrNull(1) ?: 0
        return major > MINIMUM[0] || (major == MINIMUM[0] && minor >= MINIMUM[1])
    }

    private fun scopes(account: JSONObject): Set<String>? =
        account.optJSONArray("scopes")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }

    fun allows(context: Context, scope: String): Boolean =
        StoreAccounts.read(context, Store.ROMM)?.let(::scopes)?.contains(scope) ?: true

    /** Asks the server its version when the sign-in did not record one, so it must not run on the main thread. */
    fun access(context: Context): Access {
        val account = StoreAccounts.read(context, Store.ROMM)
        val server = account?.optString("server").orEmpty()
        if (server.isEmpty() || account?.optString("access_token").isNullOrEmpty()) return Access.SIGNED_OUT
        val prefs = RommPrefs.get(context)
        if (prefs.getBoolean(DENIED, false) || scopes(account!!)?.contains("assets.write") == false) return Access.NO_ACCESS
        val version = account.optString("version").ifEmpty { prefs.getString(VERSION, "").orEmpty() }.ifEmpty {
            RommApi.heartbeat(server).takeIf { it.ok }?.json?.optJSONObject("SYSTEM")?.optString("VERSION").orEmpty()
                .also { if (it.isNotEmpty()) prefs.edit().putString(VERSION, it).apply() }
        }
        return if (version.isNotEmpty() && !recent(version)) Access.OLD_SERVER else Access.OK
    }

    /** Registers this install with the server once; saves still sync without it, the server then cannot say which device has which. */
    @Synchronized
    fun deviceId(context: Context): String? {
        val prefs = RommPrefs.get(context)
        prefs.getString(DEVICE, null)?.let { return it.ifEmpty { null } }
        if (!allows(context, "devices.write")) return null
        val body = JSONObject().put("name", android.os.Build.MODEL ?: "Android").put("platform", "android").put("client", RommApi.USER_AGENT)
            .put("allow_existing", true)
        val answer = RommApi.call(RommAuth.server(context), "/api/devices", RommAuth.token(context), body = body.toString())
        val id = answer.takeIf { it.ok }?.json?.optString("device_id").orEmpty()
        if (id.isNotEmpty() || answer.code == 401 || answer.code == 403) prefs.edit().putString(DEVICE, id).apply()
        return id.ifEmpty { null }
    }

    fun enabled(context: Context, id: String): Boolean = !RommPrefs.get(context).getBoolean("cloud_off_$id", false)

    fun setEnabled(context: Context, id: String, on: Boolean) {
        RommPrefs.get(context).edit().apply { if (on) remove("cloud_off_$id") else putBoolean("cloud_off_$id", true) }.apply()
    }

    private const val LAUNCH_WAIT_MS = 8_000L

    /**
     * Opens the play session and, for a RetroArch game, takes newer saves from the server before [start] runs.
     * The game starts after [LAUNCH_WAIT_MS] whatever the server does; saves that arrive later are left for the next launch.
     */
    fun beforeLaunch(context: Context, emulatorId: String, guestPath: String, start: () -> Unit) {
        val app = context.applicationContext
        val entry = RommInstalls.byGuestPath(app, guestPath)
        if (entry == null) { start(); return }
        RommActivity.started(app, entry.id)
        if (emulatorId != RommSaves.EMULATOR || RommSaves.base(entry) == null || !enabled(app, entry.id)) { start(); return }
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

    fun cloud(context: Context, id: String, base: String): RommCloud =
        RommCloud(RommAuth.server(context), RommAuth.token(context), id, deviceId(context), base) { denied(context) }
}
