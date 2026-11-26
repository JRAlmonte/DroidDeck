package com.droiddeck.launcher.stores.romm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState

internal object RommBrowseState {
    var platforms by mutableStateOf<List<RommPlatform>>(emptyList())
        private set
    var platform by mutableStateOf<RommPlatform?>(null)
        private set
    var collections by mutableStateOf<List<RommCollection>>(emptyList())
        private set
    var collection by mutableStateOf<RommCollection?>(null)
        private set
    var totals by mutableStateOf<RommTotals?>(null)
        private set
    var query by mutableStateOf("")
        private set
    var sort by mutableStateOf<RommSort?>(null)
        private set
    var filter by mutableStateOf(RommFilter())
        private set
    var values by mutableStateOf(RommFilterValues())
        private set
    var items by mutableStateOf<List<CatalogItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var ended by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    /** Whether the games shown are the copy kept on the device, which the server's answer has yet to replace. */
    var kept by mutableStateOf(false)
        private set
    /** The game "Random game" last drew, kept so its page can find it. */
    var drawn by mutableStateOf<CatalogItem?>(null)
        private set

    private var generation = 0
    private var offset = 0
    private var platformsRead = false
    private var collectionsRead = false
    private val valuesRead = HashMap<String, RommFilterValues>()
    private var front: Pair<RommSort, List<CatalogItem>>? = null

    // What the menus show belongs to the account and not to the page, so it outlives a change of platform or filter
    // and is dropped only when everything is read again.
    private var account = 0
    private val asking = HashSet<String>()
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(4, com.droiddeck.launcher.stores.download.DownloadQueue.workerFactory("romm-menus"))

    fun reset() {
        generation++
        account++
        platforms = emptyList()
        platformsRead = false
        platform = null
        collections = emptyList()
        collection = null
        collectionsRead = false
        totals = null
        front = null
        drawn = null
        valuesRead.clear()
        values = RommFilterValues()
        filter = RommFilter()
        query = ""
        clearItems()
    }

    /** Reads everything from the server again and keeps what the user chose: the platform, collection, filters, search and order. */
    fun refresh(context: Context) {
        generation++
        account++
        platformsRead = false
        collectionsRead = false
        valuesRead.clear()
        clearItems()
        more(context)
    }

    private fun clearItems() {
        items = emptyList()
        ended = false
        failed = false
        kept = false
        loading = false
        offset = 0
    }

    fun choose(context: Context, wanted: RommPlatform?) {
        if (wanted?.id == platform?.id) return
        generation++
        clearItems()
        platform = wanted
        more(context)
    }

    fun chooseCollection(context: Context, wanted: RommCollection?) {
        if (wanted?.key == collection?.key) return
        generation++
        clearItems()
        collection = wanted
        more(context)
    }

    fun narrow(context: Context, wanted: RommFilter) {
        if (wanted == filter) return
        generation++
        clearItems()
        filter = wanted
        more(context)
    }

    private fun onDevice(platformId: String?, order: RommSort): List<CatalogItem> =
        RommCatalog.sorted(RommInstalls.items().filter { RommCatalog.matches(it, platformId, query, filter) }, order)

    private const val SORT = "browse_sort"

    private fun savedSort(context: Context): RommSort =
        RommPrefs.get(context).getString(SORT, null)?.let { name -> RommSort.values().firstOrNull { it.name == name } } ?: RommSort.DEFAULT

    fun sortBy(context: Context, wanted: RommSort) {
        if (wanted == (sort ?: savedSort(context))) return
        RommPrefs.get(context).edit().putString(SORT, wanted.name).apply()
        generation++
        clearItems()
        sort = wanted
        more(context)
    }

    /** Asks the server for any one game of the chosen platform or collection. */
    fun random(context: Context, found: (CatalogItem?) -> Unit) {
        val app = context.applicationContext
        val (server, token) = RommAuth.both(app)
        val asked = platform?.id
        val within = collection
        Thread({
            val item = RommApi.random(server, token, asked, within).takeIf { it.ok }?.json?.let { RommCatalog.item(server, it) }
            StoresState.post { if (item != null) drawn = item; found(item) }
        }, "romm-random").start()
    }

    fun search(context: Context, text: String) {
        val wanted = text.trim()
        if (wanted == query) return
        generation++
        clearItems()
        query = wanted
        more(context)
    }

    fun more(context: Context, retry: Boolean = false) {
        if (loading || ended || (failed && !retry)) return
        val app = context.applicationContext
        val (server, token, user) = RommAuth.withUser(app)
        if (server.isEmpty() || token.isEmpty()) return
        loading = true
        failed = false
        val mine = generation
        val asked = platform?.id
        val term = query.ifEmpty { null }
        val order = sort ?: savedSort(app).also { sort = it }
        val from = offset
        val chosen = filter
        if (chosen.installed) {
            items = onDevice(asked, order)
            loading = false
            ended = true
            return
        }
        val scope = asked.orEmpty()
        valuesRead[scope]?.let { values = it }
        val within = collection
        val opening = from == 0 && asked == null && within == null && term == null && !chosen.active
        val at = RommBrowseCache.epoch()
        val keep = { front?.let { (sorted, games) -> RommBrowseCache.write(app, server, user, at, RommBrowseCache.Kept(sorted, games, platforms, collections, totals)) }; Unit }
        val held = front
        if (opening && items.isEmpty() && held != null && held.first == order && held.second.isNotEmpty()) {
            items = held.second
            kept = true
        }
        val look = opening && items.isEmpty() && held == null
        Thread({
            if (look) RommBrowseCache.read(app, server, user, order)?.let { copy ->
                StoresState.logLine("RomM browse: ${copy.items.size} games kept on the device are shown")
                StoresState.post { if (mine == generation) show(copy) }
            }
            val answer = RommApi.roms(server, token, asked, term, from, sort = order.takeUnless { term != null && it == RommSort.DEFAULT }, collection = within, filter = chosen)
            val page = if (answer.ok) answer.json else null
            val fresh = RommCatalog.items(server, page?.optJSONArray("items"))
            StoresState.logLine("RomM browse: offset $from answered ${answer.code} with ${fresh.size} games")
            StoresState.post {
                // The games come first and alone. A server that did not answer, or that turned the sign-in away, is not asked
                // five more times; one that answered with an error of its own still fills the menus, so another platform can be tried.
                if (answer.code != 0 && answer.code != 401 && answer.code != 403) menus(server, token, asked, order, keep)
                if (mine != generation) return@post
                loading = false
                if (answer.code == 401 || answer.code == 403) StoresState.markSignInExpired(Store.ROMM)
                if (page == null) { failed = true; return@post }
                val count = page.optJSONArray("items")?.length() ?: 0
                offset = from + count
                val total = if (page.isNull("total")) -1 else page.optInt("total", -1)
                if (count == 0 || (total >= 0 && offset >= total)) ended = true
                val before = if (kept) emptyList() else items
                kept = false
                val known = before.mapTo(HashSet()) { it.id }
                items = before + fresh.filter { known.add(it.id) }
                if (opening) {
                    front = order to items
                    keep()
                }
            }
        }, "romm-browse").start()
    }

    private fun show(copy: RommBrowseCache.Kept) {
        front = copy.sort to copy.items
        items = copy.items
        kept = true
        if (!platformsRead && platforms.isEmpty()) platforms = copy.platforms
        if (!collectionsRead && collections.isEmpty()) collections = copy.collections
        if (totals == null) totals = copy.totals
    }

    /** Reads what the platform, collection and filter menus show, each on its own and only what is not known yet. Main thread. */
    private fun menus(server: String, token: String, platformId: String?, order: RommSort, keep: () -> Unit) {
        val at = account
        fun ask(what: String, read: () -> (() -> Unit)?) {
            val key = "$at:$what"
            if (!asking.add(key)) return
            pool.execute {
                val apply = runCatching(read).getOrNull()
                StoresState.post {
                    asking.remove(key)
                    if (at == account) apply?.invoke()
                }
            }
        }
        if (!platformsRead) {
            ask("platforms") {
                RommCatalog.platforms(RommApi.platforms(server, token).array).takeIf { it.isNotEmpty() }?.let { listed -> { platforms = listed; platformsRead = true; keep() } }
            }
            ask("stats") { RommCatalog.totals(RommApi.stats(server, token).takeIf { it.ok }?.json)?.let { counted -> { totals = counted; keep() } } }
        }
        if (!collectionsRead) ask("collections") {
            RommApi.collections(server, token).takeIf { it.ok }?.let { regular ->
                val held = RommCatalog.collections(regular.array, RommApi.smartCollections(server, token).takeIf { it.ok }?.array)
                return@let { collections = held; collectionsRead = true; keep() }
            }
        }
        val scope = platformId.orEmpty()
        if (scope !in valuesRead) ask("values:$scope") {
            RommApi.roms(server, token, platformId, null, 0, limit = 1, sort = order, withFilterValues = true).takeIf { it.ok }?.let { RommCatalog.filterValues(it.json) }
                ?.let { offered -> { valuesRead[scope] = offered; if (scope == platform?.id.orEmpty()) values = offered } }
        }
    }
}
