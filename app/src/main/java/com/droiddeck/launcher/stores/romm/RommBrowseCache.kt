package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreLibraryCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The store's first page as it opens and what its menus show, kept from the server's last answer so the next start draws
 * games before the server is reached. It lies with the app's own files, because the file picker can open the cache folder,
 * and is named after the server and its user, so it is read back only for the account it came from. The sign-in is no part of it.
 */
internal object RommBrowseCache {
    class Kept(val sort: RommSort, val items: List<CatalogItem>, val platforms: List<RommPlatform>, val collections: List<RommCollection>, val totals: RommTotals?)

    private const val VERSION = 1
    private const val MOST_BYTES = 2 * 1024 * 1024
    private const val MOST_LISTED = 2_000

    private val lock = Any()
    private val epoch = java.util.concurrent.atomic.AtomicInteger()
    private val waiting = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>()
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor(com.droiddeck.launcher.stores.download.DownloadQueue.workerFactory("romm-browse-cache"))

    private fun folder(context: Context) = File(context.filesDir, "stores/romm/browse")

    internal fun file(context: Context, server: String, user: String): File {
        val name = java.security.MessageDigest.getInstance("SHA-256").digest("$server\n$user".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(folder(context), "$name.json")
    }

    /** Changes whenever the kept copy is deleted; a write that names an earlier one is dropped. */
    fun epoch(): Int = epoch.get()

    /** The copy kept for this account in this order, or null when there is none or it cannot be read; must not run on the main thread. */
    fun read(context: Context, server: String, user: String, sort: RommSort): Kept? {
        return runCatching {
            val file = file(context, server, user)
            if (!file.isFile) return null
            val text = file.inputStream().use { RommApi.atMost(it, MOST_BYTES) }?.toString(Charsets.UTF_8) ?: return null
            val o = JSONObject(text)
            if (o.optInt("v") != VERSION || o.optString("server") != server || o.optString("user") != user || o.optString("sort") != sort.name) return null
            val items = StoreLibraryCache.decode(Store.ROMM, o.getJSONArray("items").toString())
                .filter { RommCatalog.ID.matches(it.id) }.distinctBy { it.id }.take(RommApi.PAGE)
            if (items.isEmpty()) return null
            Kept(
                sort, items,
                RommCatalog.platforms(o.optJSONArray("platforms")).take(MOST_LISTED),
                RommCatalog.collections(o.optJSONArray("collections"), o.optJSONArray("smart"), user).take(MOST_LISTED),
                RommCatalog.totals(o.optJSONObject("stats")),
            )
        }.getOrNull()
    }

    /** Keeps [kept] for the account, later and off the caller's thread; of several asked for at once only the last is written. */
    fun write(context: Context, server: String, user: String, at: Int, kept: Kept) {
        waiting.set { store(context.applicationContext, server, user, at, kept) }
        writer.execute { waiting.getAndSet(null)?.invoke() }
    }

    private fun store(context: Context, server: String, user: String, at: Int, kept: Kept): Unit = synchronized(lock) {
        if (at != epoch.get()) return@synchronized
        val file = file(context, server, user)
        runCatching {
            val bytes = encode(server, user, kept).toByteArray(Charsets.UTF_8)
            if (bytes.size > MOST_BYTES) { file.delete(); return@synchronized }
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            // A sign-in that could no longer be opened was never signed out of, and its copy would otherwise stay.
            file.parentFile?.listFiles()?.forEach { if (it != file) it.delete() }
        }
    }

    // The menus are written the way the server sends them, so what checks a server's answer checks the kept copy too.
    private fun encode(server: String, user: String, kept: Kept): String {
        fun listed(kind: RommCollection.Kind) = JSONArray(kept.collections.filter { it.kind == kind }.map {
            JSONObject().put("id", it.id).put("name", it.name).put("rom_count", it.count).put("user_id", it.owner).put("is_favorite", it.favorite).put("description", it.about)
        })
        return JSONObject()
            .put("v", VERSION).put("server", server).put("user", user).put("sort", kept.sort.name)
            .put("items", JSONArray(StoreLibraryCache.encode(kept.items.take(RommApi.PAGE))))
            .put("platforms", JSONArray(kept.platforms.map {
                JSONObject().put("id", it.id).put("slug", it.slug).put("name", it.name).put("rom_count", it.count).put("first_release_year", it.year).put("fs_slug", it.fsSlug)
                    .put("family_name", it.family).put("generation", it.generation).put("fs_size_bytes", it.bytes)
            }))
            .put("collections", listed(RommCollection.Kind.REGULAR))
            .put("smart", listed(RommCollection.Kind.SMART))
            .apply { kept.totals?.let { put("stats", JSONObject().put("ROMS", it.games).put("PLATFORMS", it.platforms).put("TOTAL_FILESIZE_BYTES", it.bytes)) } }
            .toString()
    }

    fun clear(context: Context) {
        synchronized(lock) {
            epoch.incrementAndGet()
            folder(context).deleteRecursively()
        }
    }
}
