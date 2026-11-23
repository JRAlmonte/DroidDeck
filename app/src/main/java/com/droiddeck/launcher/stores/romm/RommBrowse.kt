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

    private var generation = 0
    private var offset = 0
    private var collectionsRead = false
    private val valuesRead = HashMap<String, RommFilterValues>()

    fun reset() {
        generation++
        platforms = emptyList()
        platform = null
        collections = emptyList()
        collection = null
        collectionsRead = false
        valuesRead.clear()
        values = RommFilterValues()
        filter = RommFilter()
        query = ""
        clearItems()
    }

    private fun clearItems() {
        items = emptyList()
        ended = false
        failed = false
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
        val server = RommAuth.server(app)
        val token = RommAuth.token(app)
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
        val read = valuesRead[scope]
        if (read != null) values = read
        val needPlatforms = platforms.isEmpty()
        val within = collection
        val needCollections = !collectionsRead
        Thread({
            val listed = if (needPlatforms) RommCatalog.platforms(RommApi.platforms(server, token).array) else null
            val held = if (needCollections) RommApi.collections(server, token).takeIf { it.ok }?.let { regular ->
                RommCatalog.collections(regular.array, RommApi.smartCollections(server, token).takeIf { it.ok }?.array)
            } else null
            val offered = if (read == null) RommApi.roms(server, token, asked, null, 0, limit = 1, sort = order, withFilterValues = true)
                .takeIf { it.ok }?.let { RommCatalog.filterValues(it.json) } else null
            val answer = RommApi.roms(server, token, asked, term, from, sort = order, collection = within, filter = chosen)
            val page = if (answer.ok) answer.json else null
            val fresh = RommCatalog.items(server, page?.optJSONArray("items"))
            StoresState.logLine("RomM browse: offset $from answered ${answer.code} with ${fresh.size} games")
            StoresState.post {
                if (mine != generation) return@post
                loading = false
                if (listed != null && listed.isNotEmpty()) platforms = listed
                if (held != null) { collections = held; collectionsRead = true }
                if (offered != null) { valuesRead[scope] = offered; values = offered }
                if (answer.code == 401 || answer.code == 403) StoresState.markSignInExpired(Store.ROMM)
                if (page == null) { failed = true; return@post }
                val count = page.optJSONArray("items")?.length() ?: 0
                offset = from + count
                val total = if (page.isNull("total")) -1 else page.optInt("total", -1)
                if (count == 0 || (total >= 0 && offset >= total)) ended = true
                val known = items.mapTo(HashSet()) { it.id }
                items = items + fresh.filter { known.add(it.id) }
            }
        }, "romm-browse").start()
    }
}
