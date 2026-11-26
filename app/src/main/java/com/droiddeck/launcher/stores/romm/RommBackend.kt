package com.droiddeck.launcher.stores.romm

import android.content.Context
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
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
        val account = StoreAccounts.read(context.applicationContext, Store.ROMM)
        Thread({ RommAuth.revoke(account) }, "romm-revoke").start()
        RommInstalls.clear(context.applicationContext)
        RommActivity.clear(context.applicationContext)
        RommDetails.clear(context.applicationContext)
        RommSent.clear(context.applicationContext)
        RommAdopt.clear(context.applicationContext)
        RommAchievements.clear(context.applicationContext)
        val favorites = RommActivity.forgetFavorites(context.applicationContext)
        RommSync.signedIn(context.applicationContext)
        StoresState.post {
            com.droiddeck.launcher.ui.LibraryFavorites.fromRomm(context.applicationContext, emptySet(), favorites)
            RommBrowseState.reset()
            RommShelvesState.reset()
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
            runCatching { RommSent.check(app) }
        }, "romm-sync").start()
    }

    fun sessionEnded(context: Context) {
        val played = runCatching { RommActivity.ended(context) }.getOrNull()
        runCatching { RommActivity.rest(context); RommActivity.flush(context) }
        RommShelvesState.stale()
        if (played == null) return
        runCatching { RommAchievements.refresh(context, played) }
        RommDetails.load(context, played, force = true)
    }

    fun recover(context: Context) {
        runCatching {
            if (!com.droiddeck.launcher.session.SessionState.running) RommActivity.closeLost(context)
            RommActivity.flush(context)
            RommSent.check(context)
        }
    }

    override fun loadShelves(context: Context, force: Boolean) {
        StoresState.post {
            StoresState.shelves[store] = StoreShelves()
            if (force) { RommBrowseState.reset(); RommShelvesState.reset() }
        }
    }

    override fun search(context: Context, query: String): List<CatalogItem> {
        val server = RommAuth.server(context)
        val token = RommAuth.token(context)
        if (server.isEmpty() || token.isEmpty()) return emptyList()
        val answer = RommApi.roms(server, token, null, query, 0, 100, RommSort.NAME)
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
