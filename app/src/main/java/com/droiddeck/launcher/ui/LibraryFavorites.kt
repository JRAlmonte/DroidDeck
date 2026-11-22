package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.romm.RommActivity

internal const val LIBRARY_FAVORITES = "favorites"

internal object LibraryFavorites {
    var keys by mutableStateOf<Set<String>>(emptySet())
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        keys = SessionPrefs.libraryFavorites(context)
    }

    fun toggle(context: Context, key: String) {
        load(context)
        keys = toggled(keys, key)
        SessionPrefs.setLibraryFavorites(context, keys)
        if (key.startsWith(RommActivity.FAVORITE_PREFIX)) RommActivity.favorite(context, key.removePrefix(RommActivity.FAVORITE_PREFIX), key in keys)
    }

    /** Takes favourites changed on the RomM server; these are not sent back. */
    fun fromRomm(context: Context, add: Set<String>, remove: Set<String>) {
        load(context)
        keys = keys + add.map { RommActivity.FAVORITE_PREFIX + it } - remove.map { RommActivity.FAVORITE_PREFIX + it }.toSet()
        SessionPrefs.setLibraryFavorites(context, keys)
    }
}

internal fun toggled(keys: Set<String>, key: String): Set<String> = if (key in keys) keys - key else keys + key

internal fun inLibrarySource(source: String, entry: LibraryEntry, favorites: Set<String>, hidden: (LibraryEntry) -> Boolean = { false }): Boolean {
    val concealed = hidden(entry)
    return when (source) {
        LIBRARY_HIDDEN -> concealed
        LIBRARY_ALL -> !concealed
        LIBRARY_FAVORITES -> !concealed && entry.favoriteKey in favorites
        else -> !concealed && entry.source == source
    }
}
