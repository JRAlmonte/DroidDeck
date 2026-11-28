package com.droiddeck.launcher.frontend

import android.content.Context

object RomControls {
    enum class Group { CONTROLS, SCREEN }

    // The values are the ones each emulator keeps in its own settings file; droiddeck-pad-defaults writes them there.
    // A setting with no values takes what its pattern allows and has no chooser of its own.
    enum class Setting(val key: String, val env: String, val values: List<String>, val emulatorId: String, val group: Group = Group.CONTROLS,
        val pattern: Regex? = null) {
        PS1_PAD("ps1Pad", "DROIDDECK_PS1_PAD", listOf("AnalogController", "DigitalController"), "duckstation"),
        ANALOG_DPAD("analogDpad", "DROIDDECK_RA_ANALOG_DPAD", listOf("0", "1", "2"), "retroarch"),
        DS_LAYOUT("dsLayout", "DROIDDECK_DS_LAYOUT", listOf("0", "1", "2", "3"), "melonds", Group.SCREEN),
        DS_SIZING("dsSizing", "DROIDDECK_DS_SIZING", listOf("0", "1", "2", "4", "5"), "melonds", Group.SCREEN),
        DS_SWAP("dsSwap", "DROIDDECK_DS_SWAP", listOf("false", "true"), "melonds", Group.SCREEN),
        N3DS_LAYOUT("3dsLayout", "DROIDDECK_3DS_LAYOUT", listOf("0", "1", "2", "3", "5", ScreenRects.LAYOUT), "azahar", Group.SCREEN),
        N3DS_SWAP("3dsSwap", "DROIDDECK_3DS_SWAP", listOf("false", "true"), "azahar", Group.SCREEN),
        N3DS_SCREENS("3dsScreens", "DROIDDECK_3DS_CUSTOM", emptyList(), "azahar", Group.SCREEN, ScreenRects.PATTERN),
        PS1_ASPECT("ps1Aspect", "DROIDDECK_PS1_ASPECT", listOf("4:3", "16:9"), "duckstation", Group.SCREEN),
        RA_INTEGER("raInteger", "DROIDDECK_RA_INTEGER", listOf("false", "true"), "retroarch", Group.SCREEN);

        fun accepts(value: String) = pattern?.matches(value) ?: (value in values)
        val hasChooser get() = pattern == null
    }

    fun forEmulator(emulatorId: String, group: Group = Group.CONTROLS): List<Setting> =
        Setting.entries.filter { it.emulatorId == emulatorId && it.group == group }

    fun resolve(lookup: (String) -> String?, keys: List<String>, setting: Setting): String? =
        keys.firstNotNullOfOrNull { key -> lookup(name(key, setting))?.takeIf(setting::accepts) }

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
