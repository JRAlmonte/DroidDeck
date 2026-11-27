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
        val app = context.applicationContext
        forget(app, StoreAccounts.read(app, Store.ROMM))
        StoresState.post {
            StoresState.library.remove(store)
            StoresState.shelves.remove(store)
            StoresState.found.remove(store)
            StoresState.problems.remove(store)
            StoresState.status.remove(store)
        }
    }

    /**
     * Drops what the device keeps of [account] and deletes its token on the server; the ROM files stay. ROM ids repeat from
     * one server to the next, so nothing recorded under them may outlive the server. [games] is false when the next
     * sign-in is another account on the same server, whose games the installed ones still are.
     */
    internal fun forget(app: Context, account: org.json.JSONObject?, games: Boolean = true) {
        Thread({ RommAuth.revokeOrTell(app, account) }, "romm-revoke").start()
        if (games) {
            RommInstalls.clear(app)
            RommAdopt.clear(app)
            RommManual.clear(app)
            RommPlatformIcons.clear(app)
        }
        RommBrowseCache.clear(app)
        RommRefresh.clear(app)
        RommActivity.clear(app)
        RommDetails.clear(app)
        RommSent.clear(app)
        RommAchievements.clear(app)
        RommSync.forgetSaves(app)
        val favorites = RommActivity.forgetFavorites(app)
        RommSync.signedIn(app)
        StoresState.post {
            com.droiddeck.launcher.ui.LibraryFavorites.fromRomm(app, emptySet(), favorites)
            RommBrowseState.reset()
            // A page asked for while the account was being forgotten can no longer be kept once this has run.
            RommBrowseCache.clear(app)
            RommShelvesState.reset()
            RommFirmwareState.reset()
        }
    }

    override fun syncLibrary(context: Context, force: Boolean) {
        val app = context.applicationContext
        Thread({
            RommInstalls.load(app)
            StoresState.post { StoresState.library[store] = RommInstalls.items() }
            runCatching { RommSync.learnVersion(app) }
            val favorites = com.droiddeck.launcher.session.SessionPrefs.libraryFavorites(app)
                .filter { it.startsWith(RommActivity.FAVORITE_PREFIX) }.map { it.removePrefix(RommActivity.FAVORITE_PREFIX) }.toSet()
            runCatching {
                RommActivity.syncFavorites(app, favorites) { add, remove ->
                    StoresState.post { com.droiddeck.launcher.ui.LibraryFavorites.fromRomm(app, add, remove) }
                }
            }.onFailure { StoresState.logLine("RomM favourites: ${it.javaClass.simpleName}") }
            runCatching { RommSent.check(app) }
            runCatching { RommRefresh.check(app) }
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
        val app = context.applicationContext
        StoresState.post {
            StoresState.shelves[store] = StoreShelves()
            if (force) {
                RommBrowseState.refresh(app)
                RommShelvesState.stale()
                RommShelvesState.load(app)
            }
        }
    }

    override fun search(context: Context, query: String): List<CatalogItem> {
        val (server, token) = RommAuth.both(context)
        if (server.isEmpty() || token.isEmpty()) return emptyList()
        val answer = RommApi.roms(server, token, emptyList(), query, 0, 100, RommSort.NAME)
        val items = RommCatalog.items(server, answer.takeIf { it.ok }?.json?.optJSONArray("items"))
        StoresState.logLine("RomM search: answered ${answer.code} with ${items.size} games")
        return items
    }

    override fun install(context: Context, item: CatalogItem, root: File) {
        val app = context.applicationContext
        val entry = DownloadEntry(store, item.id, item.title, cover = item.tallImageUrl ?: item.imageUrl, bytesTotal = item.sizeBytes, location = "ROMs")
        DownloadQueue.enqueue(app, entry) { RommInstaller(app, item) }
    }

    /** Brings [files] to the game as it is installed: extras the server keeps with [item], or with [own] files of the game left there at install. */
    internal fun addFiles(context: Context, item: CatalogItem, files: List<RommFile>, own: Boolean = false) {
        val app = context.applicationContext
        val name = app.getString(if (own) com.droiddeck.launcher.R.string.stores_romm_files_of else com.droiddeck.launcher.R.string.stores_romm_extras_of, item.title)
        val entry = DownloadEntry(store, item.id, name, cover = item.tallImageUrl ?: item.imageUrl, bytesTotal = files.sumOf { it.size }, location = "ROMs")
        DownloadQueue.enqueue(app, entry) { RommInstaller(app, item, files) }
    }

    override fun uninstall(context: Context, game: InstalledStoreGame) {}
}
