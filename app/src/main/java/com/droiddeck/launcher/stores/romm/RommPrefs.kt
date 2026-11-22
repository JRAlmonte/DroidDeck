package com.droiddeck.launcher.stores.romm

import android.content.Context
import android.content.SharedPreferences

object RommPrefs {
    @JvmStatic
    fun get(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences("stores.romm", Context.MODE_PRIVATE)
}
