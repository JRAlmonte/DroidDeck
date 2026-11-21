package com.droiddeck.launcher.ports

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object RunWith {
    const val EMULATOR = "emulator"
    const val PORT = "port"
    private const val PREFS = "run_with"

    private var choices by mutableStateOf<Map<String, String>?>(null)

    fun choose(stored: String?, hasEmulator: Boolean, hasPort: Boolean, portInstalled: Boolean): String = when {
        !hasPort -> EMULATOR
        !hasEmulator -> PORT
        stored == EMULATOR -> EMULATOR
        stored == PORT -> PORT
        portInstalled -> PORT
        else -> EMULATOR
    }

    private fun load(context: Context): Map<String, String> = choices
        ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap().also { choices = it }

    fun stored(context: Context, guestPath: String): String? = load(context)[guestPath]

    fun set(context: Context, guestPath: String, choice: String) {
        choices = load(context) + (guestPath to choice)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(guestPath, choice).apply()
    }
}
