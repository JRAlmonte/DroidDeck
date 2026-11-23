package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreBackend
import com.droiddeck.launcher.stores.StoreShelves
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import java.io.File

object RommBackend : StoreBackend {
    override val store: Store = Store.ROMM

    override fun signIn(context: Context) {}

    override fun signOut(context: Context) {
        RommInstalls.clear(context.applicationContext)
        RommActivity.clear(context.applicationContext)
        RommSync.signedIn(context.applicationContext)
        StoresState.post {
            RommBrowseState.reset()
            RommFirmwareState.reset()
            StoresState.library.remove(store)
            StoresState.shelves.remove(store)
            StoresState.found.remove(store)
            StoresState.problems.remove(store)
            StoresState.status.remove(store)
        }
    }

    override fun syncLibrary(context: Context, force: Boolean) {
        val app = context.applicationContext
        Thread({
            RommInstalls.load(app)
            StoresState.post { StoresState.library[store] = RommInstalls.items() }
            val favorites = com.droiddeck.launcher.session.SessionPrefs.libraryFavorites(app)
                .filter { it.startsWith(RommActivity.FAVORITE_PREFIX) }.map { it.removePrefix(RommActivity.FAVORITE_PREFIX) }.toSet()
            runCatching {
                RommActivity.syncFavorites(app, favorites) { add, remove ->
                    StoresState.post { com.droiddeck.launcher.ui.LibraryFavorites.fromRomm(app, add, remove) }
                }
            }.onFailure { StoresState.logLine("RomM favourites: ${it.javaClass.simpleName}") }
        }, "romm-sync").start()
    }

    /** Closes the play session of a RomM game and sends it. */
    fun sessionEnded(context: Context) {
        runCatching { RommActivity.ended(context); RommActivity.flush(context) }
    }

    fun recover(context: Context) {
        runCatching { RommActivity.flush(context) }
    }

    override fun loadShelves(context: Context, force: Boolean) {
        StoresState.post {
            StoresState.shelves[store] = StoreShelves()
            if (force) RommBrowseState.reset()
        }
    }

    override fun search(context: Context, query: String): List<CatalogItem> {
        val server = RommAuth.server(context)
        val token = RommAuth.token(context)
        if (server.isEmpty() || token.isEmpty()) return emptyList()
        val answer = RommApi.roms(server, token, null, query, 0, 100)
        val items = RommCatalog.items(server, answer.takeIf { it.ok }?.json?.optJSONArray("items"))
        StoresState.logLine("RomM search: answered ${answer.code} with ${items.size} games")
        return items
    }

    override fun install(context: Context, item: CatalogItem, root: File) {
        val app = context.applicationContext
        val entry = DownloadEntry(store, item.id, item.title, cover = item.tallImageUrl ?: item.imageUrl, bytesTotal = item.sizeBytes, location = "ROMs")
        DownloadQueue.enqueue(app, entry) { RommInstaller(app, item) }
    }

    override fun uninstall(context: Context, game: InstalledStoreGame) {}
}
