package com.droiddeck.launcher.stores.romm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.StoresState

internal object RommShelvesState {
    enum class Row(val sort: RommSort, val filter: RommFilter) {
        PLAYING(RommSort.PLAYED, RommFilter(played = true)),
        ADDED(RommSort.ADDED, RommFilter()),
        FAVOURITES(RommSort.NAME, RommFilter(favorite = true)),
    }

    val store = listOf(Row.ADDED)
    val library = listOf(Row.PLAYING, Row.ADDED, Row.FAVOURITES)

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

    fun stale() { fresh = false }

    fun load(context: Context) {
        if (loading || fresh) return
        val app = context.applicationContext
        val (server, token) = RommAuth.both(app)
        if (server.isEmpty() || token.isEmpty()) return
        loading = true
        val mine = generation
        Thread({
            val asked = Row.values().associateWith { row ->
                java.util.concurrent.FutureTask {
                    RommApi.roms(server, token, emptyList(), null, 0, limit = MOST, sort = row.sort, filter = row.filter).takeIf { it.ok }
                        ?.let { RommCatalog.items(server, it.json?.optJSONArray("items")) }
                }.also { Thread(it, "romm-shelf-${row.name.lowercase()}").start() }
            }
            val read = asked.mapValues { runCatching { it.value.get() }.getOrNull() }
            StoresState.post {
                if (mine != generation) return@post
                loading = false
                fresh = read.values.none { it == null }
                rows = Row.values().associateWith { read[it] ?: rows[it].orEmpty() }
            }
        }, "romm-shelves").start()
    }
}
