package com.droiddeck.launcher.frontend

import android.content.Context

object RomControls {
    enum class Setting(val key: String, val env: String, val values: List<String>, val emulatorId: String) {
        PS1_PAD("ps1Pad", "DROIDDECK_PS1_PAD", listOf("AnalogController", "DigitalController"), "duckstation"),
        ANALOG_DPAD("analogDpad", "DROIDDECK_RA_ANALOG_DPAD", listOf("0", "1", "2"), "retroarch"),
    }

    fun forEmulator(emulatorId: String): List<Setting> = Setting.entries.filter { it.emulatorId == emulatorId }

    fun resolve(lookup: (String) -> String?, keys: List<String>, setting: Setting): String? =
        keys.firstNotNullOfOrNull { key -> lookup(name(key, setting))?.takeIf { it in setting.values } }

    fun env(lookup: (String) -> String?, keys: List<String>): List<String> =
        Setting.entries.mapNotNull { setting -> resolve(lookup, keys, setting)?.let { "${setting.env}=$it" } }

    private fun name(scope: String, setting: Setting) = "${setting.key}.$scope"

    private fun prefs(context: Context) = context.getSharedPreferences("rom-controls", Context.MODE_PRIVATE)

    fun get(context: Context, scope: String, setting: Setting): String? = prefs(context).getString(name(scope, setting), null)

    fun set(context: Context, scope: String, setting: Setting, value: String?) {
        val edit = prefs(context).edit()
        if (value == null) edit.remove(name(scope, setting)) else edit.putString(name(scope, setting), value)
        edit.apply()
    }

    fun resetAll(context: Context) {
        prefs(context).edit().clear().commit()
    }

    fun env(context: Context, keys: List<String>): List<String> = prefs(context).let { saved -> env({ saved.getString(it, null) }, keys) }
}
