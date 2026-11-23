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
    var query by mutableStateOf("")
        private set
    var sort by mutableStateOf<RommSort?>(null)
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

    fun reset() {
        generation++
        platforms = emptyList()
        platform = null
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

    private const val SORT = "browse_sort"

    private fun savedSort(context: Context): RommSort =
        RommPrefs.get(context).getString(SORT, null)?.let { name -> RommSort.values().firstOrNull { it.name == name } } ?: RommSort.NAME

    /** Lists the games in another order, which is kept for the next visit. */
    fun sortBy(context: Context, wanted: RommSort) {
        if (wanted == (sort ?: savedSort(context))) return
        RommPrefs.get(context).edit().putString(SORT, wanted.name).apply()
        generation++
        clearItems()
        sort = wanted
        more(context)
    }

    /** Narrows the list to games matching [text] within the chosen platform; an empty text lists them all again. */
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
        val needPlatforms = platforms.isEmpty()
        Thread({
            val listed = if (needPlatforms) RommCatalog.platforms(RommApi.platforms(server, token).array) else null
            val answer = RommApi.roms(server, token, asked, term, from, sort = order)
            val page = if (answer.ok) answer.json else null
            val fresh = RommCatalog.items(server, page?.optJSONArray("items"))
            StoresState.logLine("RomM browse: offset $from answered ${answer.code} with ${fresh.size} games")
            StoresState.post {
                if (mine != generation) return@post
                loading = false
                if (listed != null && listed.isNotEmpty()) platforms = listed
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
