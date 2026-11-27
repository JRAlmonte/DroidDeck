package com.droiddeck.launcher.stores.romm

import android.content.Context

/**
 * Making, renaming and deleting the account's own collections. Each asks the server at once and none waits for a server
 * that is out of reach: a collection made later would have no id for the games put in it meanwhile.
 */
internal object RommCollections {
    /** The longest name RomM 5.4 takes. */
    const val MOST_NAME = 400
    const val SCOPE = "collections.write"

    /** Whether [collection] is one the account [me] may rename and delete: a plain collection of its own that is not its favourites. */
    fun own(collection: RommCollection, me: String): Boolean =
        me.isNotEmpty() && collection.kind == RommCollection.Kind.REGULAR && !collection.favorite && collection.owner == me

    fun name(text: String): String = text.trim().take(MOST_NAME)

    /** Makes an empty collection of the account's own, which no other user sees. Must not run on the main thread. */
    fun create(context: Context, name: String): RommAnswer {
        val (server, token) = RommAuth.both(context)
        if (server.isEmpty() || token.isEmpty() || name.isEmpty()) return RommAnswer(400, "")
        return RommSync.noting(context, SCOPE, RommApi.form(server, "/api/collections?is_public=false&is_favorite=false", token, mapOf("name" to name, "description" to "")))
    }

    /**
     * Gives a collection another name. RomM replaces a collection's games with the `rom_ids` that come with a change and
     * leaves them as they are only for `null` (`update_collection` in its `collections_handler.py`), so that is what is
     * sent: the games are never listed from here, where the list may be an old one. Must not run on the main thread.
     */
    fun rename(context: Context, id: String, name: String): RommAnswer {
        val (server, token) = RommAuth.both(context)
        if (server.isEmpty() || token.isEmpty() || name.isEmpty() || !RommCatalog.ID.matches(id)) return RommAnswer(400, "")
        return RommSync.noting(context, SCOPE, RommApi.form(server, "/api/collections/${RommApi.encode(id)}", token, mapOf("rom_ids" to "null", "name" to name), "PUT"))
    }

    /** Deletes a collection on the server; its games stay on the server and on the device. Must not run on the main thread. */
    fun delete(context: Context, id: String): RommAnswer {
        val (server, token) = RommAuth.both(context)
        if (server.isEmpty() || token.isEmpty() || !RommCatalog.ID.matches(id)) return RommAnswer(400, "")
        return RommSync.noting(context, SCOPE, RommApi.call(server, "/api/collections/${RommApi.encode(id)}", token, "DELETE"))
    }
}
