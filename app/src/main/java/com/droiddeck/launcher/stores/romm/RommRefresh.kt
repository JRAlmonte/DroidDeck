package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.StoresState

/**
 * Brings the records of the installed games up to date without their pages being opened: it asks the server for the games
 * it changed since the last check that ran to its end. That time is the server's own, kept in [RommPrefs] and forgotten
 * with the account ([clear]). With no time kept, each installed game is read on its own, once. A server that sends no
 * `Date` gives no time to keep.
 */
internal object RommRefresh {
    internal const val PAGE = 100
    private const val CHECKED = "installs_checked"

    // The server's clock is read off the first answer, which it made after it had looked the games up. The next check starts
    // this long before that, so a game changed while the server was answering is asked for again and not passed over.
    internal const val OVERLAP_S = 60L

    private val lock = Any()
    private val epoch = java.util.concurrent.atomic.AtomicInteger()
    private val running = java.util.concurrent.atomic.AtomicBoolean()

    /** When the last whole check began by the server's clock, in seconds, or 0. */
    internal fun since(context: Context): Long = RommPrefs.get(context).getLong(CHECKED, 0)

    /** How many records the check changed, or null when it did not run to its end. Must not run on the main thread. */
    fun check(context: Context): Int? {
        if (!running.compareAndSet(false, true)) return null
        try {
            return look(context.applicationContext)
        } finally {
            running.set(false)
        }
    }

    private fun look(app: Context): Int? {
        val at = epoch.get()
        val (server, token) = RommAuth.both(app)
        if (server.isEmpty() || token.isEmpty()) return null
        val ids = RommInstalls.described(app)
        val since = since(app)
        if (ids.isEmpty()) {
            // Every game installed from here on is read after this moment, so the first check with games asks only for what
            // changed since, however many were installed or matched at once.
            if (since <= 0) synchronized(lock) { if (at == epoch.get()) keep(app, RommApi.heartbeat(server).date) }
            return 0
        }
        val fresh = LinkedHashMap<String, CatalogItem>()
        var clock = 0L
        var listed = -1
        var each = since <= 0
        var offset = 0
        while (!each) {
            val answer = RommApi.changed(server, token, since, offset, PAGE)
            val page = answer.takeIf { it.ok }?.json
            val items = page?.optJSONArray("items") ?: return gaveUp("the games changed since ${stamp(since)}", answer.code)
            val total = if (page.isNull("total")) -1 else page.optInt("total", -1)
            if (offset == 0) {
                clock = answer.date
                listed = total
                // A list longer than reading each installed game would be is not read: a server that was scanned anew, or
                // one that does not know the question and answers with every game it holds.
                if (total > ids.size.toLong() * PAGE) { each = true; break }
            }
            RommCatalog.items(server, items).forEach { if (it.id in ids) fresh[it.id] = it }
            offset += items.length()
            if (items.length() == 0 || (total >= 0 && offset >= total)) break
        }
        if (each) {
            var first = since <= 0
            for (id in ids) {
                val answer = RommApi.rom(server, token, id)
                if (first) { clock = answer.date; first = false }
                // A game the server no longer holds keeps its record: the file is still on the device.
                if (answer.code == 404) continue
                val rom = answer.takeIf { it.ok }?.json ?: return gaveUp("game $id", answer.code)
                RommCatalog.item(server, rom)?.let { fresh[id] = it }
            }
        }
        val changed = synchronized(lock) {
            if (at != epoch.get()) return null
            val changed = RommInstalls.renew(app, fresh.values)
            keep(app, clock)
            changed
        }
        StoresState.logLine(
            "RomM refresh: " + (if (since > 0) "$listed games changed since ${stamp(since)}" else "no check kept") +
                (if (each) ", ${ids.size} installed games read one by one" else "") + ", $changed of ${ids.size} records brought up to date",
        )
        return changed
    }

    private fun keep(app: Context, clock: Long) {
        if (clock > 0) RommPrefs.get(app).edit().putLong(CHECKED, clock / 1000 - OVERLAP_S).apply()
    }

    private fun gaveUp(what: String, code: Int): Int? {
        StoresState.logLine("RomM refresh: $what answered $code, the records stay as they are")
        return null
    }

    private fun stamp(seconds: Long): String = java.time.Instant.ofEpochSecond(seconds).toString()

    fun clear(context: Context) {
        synchronized(lock) {
            epoch.incrementAndGet()
            RommPrefs.get(context).edit().remove(CHECKED).apply()
        }
    }
}
