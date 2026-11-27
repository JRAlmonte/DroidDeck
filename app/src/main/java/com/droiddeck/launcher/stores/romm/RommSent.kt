package com.droiddeck.launcher.stores.romm

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONObject
import java.io.File

/**
 * Games sent to this device from RomM's web page. The server hands them only to the token bound to the device, so this
 * works for a sign-in made with a code. Each is installed like one chosen here, and the server is told how it went.
 */
internal object RommSent {
    private const val TAG = "RommSent"
    private const val TAKEN = "install_requests"

    private fun taken(context: Context): JSONObject =
        runCatching { JSONObject(RommPrefs.get(context).getString(TAKEN, "{}").orEmpty()) }.getOrDefault(JSONObject())

    private val noted = Any()

    private fun remember(context: Context, romId: String, request: String?) = synchronized(noted) {
        val all = taken(context)
        if (request == null) all.remove(romId) else all.put(romId, request)
        RommPrefs.get(context).edit().putString(TAKEN, all.toString()).apply()
    }

    private fun report(context: Context, device: String, request: String, status: String, reason: String? = null): RommAnswer {
        val body = JSONObject().put("status", status)
        if (reason != null) body.put("reason", reason.take(500))
        val (server, token) = RommAuth.both(context)
        return RommApi.call(server, "/api/devices/${RommApi.encode(device)}/installs/${RommApi.encode(request)}", token, "PUT", body.toString())
            .also { Log.i(TAG, "$status answered ${it.code}") }
    }

    /** Takes what the server holds for this device and starts each install; must not run on the main thread. */
    @Synchronized
    fun check(context: Context, install: (com.droiddeck.launcher.stores.CatalogItem) -> Unit = { item -> StoresState.post { RommBackend.install(context, item, File("")) } }) {
        if (!com.droiddeck.launcher.session.SessionPrefs.rommSent(context) || !RommSync.serverHas(context, RommSince.SENT)) return
        val device = RommSync.boundDevice(context) ?: return
        if (!RommSync.allows(context, "devices.write") || RommSync.access(context, "roms.read") != RommSync.Access.OK) return
        RommSync.deviceId(context)
        val (server, token) = RommAuth.both(context)
        val list = RommApi.call(server, "/api/devices/${RommApi.encode(device)}/installs/claim", token, body = "{}").takeIf { it.ok }?.array ?: return
        for (o in (0 until list.length()).mapNotNull { list.optJSONObject(it) }) {
            val request = o.optString("id")
            val rom = o.optString("rom_id")
            if (request.isEmpty() || !RommCatalog.ID.matches(rom)) continue
            if (RommInstalls.find(context, rom) != null) { report(context, device, request, "already_installed"); remember(context, rom, null); continue }
            if (taken(context).optString(rom) == request && StoresState.download("${Store.ROMM.id}:$rom")?.isActive == true) continue
            val record = RommApi.rom(server, token, rom).takeIf { it.ok }?.json ?: continue
            val item = RommCatalog.item(server, record) ?: continue
            val problem = when {
                !item.extra[RommCatalog.UNAVAILABLE].isNullOrEmpty() -> "The server has no file for this game"
                RommInstalls.romsRoot(context) == null -> "No ROMs folder is set up on the device"
                roomFor(context, item.sizeBytes) == false -> "Not enough free space on the device"
                else -> null
            }
            if (problem != null) { report(context, device, request, "failed", problem); continue }
            remember(context, rom, request)
            RommDetails.noteFiles(rom, record)
            install(RommCatalog.withExtras(item, named(o, record)))
        }
    }

    /**
     * The extra files a request names. RomM itself chooses them when the game is sent: every file of the game, of an update
     * and of DLC that it holds (`file_ids`), so the updates and DLC come with a game sent from its page.
     */
    private fun named(request: JSONObject, record: JSONObject): Set<String> {
        val ids = request.optJSONArray("file_ids")?.let { a -> (0 until a.length()).mapTo(HashSet()) { a.optString(it) } }.orEmpty()
        return RommCatalog.fileSet(record).extras.map { it.id }.filterTo(LinkedHashSet()) { it in ids }
    }

    private fun roomFor(context: Context, bytes: Long): Boolean? {
        val free = com.droiddeck.launcher.stores.StoreInstallRoot.availableBytes(RommInstalls.romsRoot(context) ?: return null)
        return if (free <= 0 || bytes <= 0) null else bytes <= free
    }

    /** Tells the server how an install it sent ended; [problem] is null when the game is installed. */
    fun finished(context: Context, romId: String, problem: String?) {
        val request = taken(context).optString(romId).ifEmpty { return }
        remember(context, romId, null)
        Thread({
            runCatching {
                val device = RommSync.boundDevice(context) ?: return@runCatching
                report(context, device, request, if (problem == null) "done" else "failed", problem)
            }
        }, "romm-sent").start()
    }

    fun clear(context: Context) {
        RommPrefs.get(context).edit().remove(TAKEN).apply()
    }
}
