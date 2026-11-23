package com.droiddeck.launcher.stores.romm

import android.content.Context
import android.content.SharedPreferences

object RommPrefs {
    @JvmStatic
    fun get(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences("stores.romm", Context.MODE_PRIVATE)

    /** The name this install gives a RomM server when it asks to be paired, the same each time. */
    fun pairingId(context: Context): String {
        val prefs = get(context)
        prefs.getString("pairing_id", null)?.let { return it }
        return java.util.UUID.randomUUID().toString().also { prefs.edit().putString("pairing_id", it).apply() }
    }
}
