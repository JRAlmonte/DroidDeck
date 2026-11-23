package com.droiddeck.launcher.stores.romm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.StoresState

/** The rows above the list of games: what was played last, what the server got last, and the favourites. */
internal object RommShelvesState {
    enum class Row(val sort: RommSort, val filter: RommFilter) {
        PLAYING(RommSort.PLAYED, RommFilter(played = true)),
        ADDED(RommSort.ADDED, RommFilter()),
        FAVOURITES(RommSort.NAME, RommFilter(favorite = true)),
    }

    const val MOST = 24

    var rows by mutableStateOf<Map<Row, List<CatalogItem>>>(emptyMap())
        private set

    val all: List<CatalogItem> get() = rows.values.flatten()

    private var generation = 0
    private var loading = false
    private var fresh = false

    fun reset() {
        generation++
        rows = emptyMap()
        loading = false
        fresh = false
    }

    /** Has the rows read again the next time they are shown; what is shown stays until then. */
    fun stale() { fresh = false }

    fun load(context: Context) {
        if (loading || fresh) return
        val app = context.applicationContext
        val server = RommAuth.server(app)
        val token = RommAuth.token(app)
        if (server.isEmpty() || token.isEmpty()) return
        loading = true
        val mine = generation
        Thread({
            val read = Row.values().associateWith { row ->
                RommApi.roms(server, token, null, null, 0, limit = MOST, sort = row.sort, filter = row.filter).takeIf { it.ok }
                    ?.let { RommCatalog.items(server, it.json?.optJSONArray("items")) }
            }
            StoresState.post {
                if (mine != generation) return@post
                loading = false
                fresh = read.values.none { it == null }
                rows = Row.values().associateWith { read[it] ?: rows[it].orEmpty() }
            }
        }, "romm-shelves").start()
    }
}
