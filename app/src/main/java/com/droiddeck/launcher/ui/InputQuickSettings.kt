package com.droiddeck.launcher.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.*

@Composable
internal fun InputQuickSettings(host: MenuHost, desktop: Boolean, onChanged: (InputRole?) -> Unit = {}, onEdit: () -> Unit = {}, scope: List<String> = emptyList(), initial: String? = null, track: (String) -> Modifier = { Modifier }) {
    val context = LocalContext.current
    var library by remember { mutableStateOf(InputLibraries.read(context)) }
    var expanded by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    DisposableEffect(context) {
        val preferences = context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "library") library = InputLibraries.read(context) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun update(role: InputRole?, change: (InputLibrary) -> InputLibrary) {
        runCatching { InputLibraries.update(context, change) }.onSuccess { library = it; onChanged(role) }
            .onFailure { failure = it.inputFailure(context) }
    }
    val scopes = if (desktop) emptyList() else scope
    var target by remember(scopes) { mutableStateOf(scopes.firstOrNull { it in library.scoped } ?: initial?.takeIf { it in scopes }) }
    val inherited = target?.let { scopes.drop(scopes.indexOf(it) + 1) }.orEmpty()
    val view = target?.let { library.forScope(listOf(it) + inherited) } ?: library
    val own = target?.let { library.scoped[it] }
    val selection = if (target == null) library.selection(desktop) else own ?: InputSelection()
    val mixed = selection.parts.isNotEmpty()
    val active = view.profile(view.activeId(desktop))
    val first = if (target == null) InputProfiles.AUTO to stringResource(R.string.input_quick_auto, if (desktop) library.profile(library.desktop).name else library.profile(library.steam).name)
        else INHERIT to stringResource(R.string.input_quick_inherit, library.forScope(inherited).let { it.profile(it.activeId(false)).name })
    val profiles = listOf(first) +
        library.profiles.map { it.id to it.name } + if (mixed) listOf("mixed" to stringResource(R.string.input_quick_mixed, active.name)) else emptyList()
    if (scopes.isNotEmpty()) ChoiceRow(host, "input-scope", stringResource(R.string.input_scope), null,
        listOf<Pair<String?, String>>(null to stringResource(R.string.input_scope_all)) + scopes.map { it to scopeLabel(it) }, target,
        chipModifier = track("input-scope")) { target = it }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).focusRow { expanded = !expanded }.padding(14.dp).testTag("input-profile-expand"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.input_profile), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Box(Modifier.padding(end = 14.dp).widthIn(max = 280.dp)) {
            ChoiceChip(host, "input-profile", stringResource(R.string.input_profile), profiles, if (mixed) "mixed" else if (target != null && own == null) INHERIT else selection.profile,
                modifier = track("input-profile")) { id ->
                val key = target
                if (id != "mixed") update(null) { if (key == null) it.selectProfile(desktop, id) else if (id == INHERIT) it.clearScope(key) else it.selectScopedProfile(key, id) }
            }
        }
    }
    if (expanded) Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, bottom = 14.dp).testTag("input-profile-parts"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = .08f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .35f)),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
    for (role in InputRole.entries) {
        val options = library.bindings.filter { binding ->
            if (role == InputRole.PHYSICAL) binding.physical
            else !binding.physical && library.layouts.any { it.id == binding.layoutId && it.supports(role) }
        }.map { it.id to it.name }
        ChoiceRow(host, "input-part-${role.name}", roleLabel(role), null, options, view.activePart(desktop, role).bindings,
            chipModifier = track("input-part-${role.name}")) { id ->
            val key = target
            update(role) { if (key == null) it.selectPart(desktop, role, id) else it.selectScopedPart(key, inherited, role, id) }
        }
    }
    if (mixed) SecondaryButton(stringResource(R.string.input_save_combination)) { naming = true }
        ActionRow(stringResource(R.string.input_settings), null, stringResource(R.string.ctrl_edit), onEdit,
            modifier = track("input-settings").testTag("input-profile-edit"))
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { naming = false }, title = { Text(stringResource(R.string.input_save_combination)) },
            text = { OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text(stringResource(R.string.input_name)) }) },
            confirmButton = { PadTextButton(enabled = name.isNotBlank(), onClick = { val key = target; update(null) { if (key == null) it.saveSelection(desktop, name) else it.saveScopedSelection(key, inherited, name) }; naming = false }) { Text(stringResource(R.string.gesture_save_changes)) } },
            dismissButton = { PadTextButton(onClick = { naming = false }) { Text(stringResource(R.string.common_cancel)) } })
    }
    failure?.let { message -> AlertDialog(onDismissRequest = { failure = null }, title = { Text(stringResource(R.string.input_save_failed)) },
        text = { Text(message) }, confirmButton = { PadTextButton(onClick = { failure = null }) { Text(stringResource(R.string.common_ok)) } }) }
}

private const val INHERIT = "inherit"

@Composable
internal fun scopeLabel(key: String): String =
    if (key.startsWith("system:")) stringResource(R.string.input_scope_system, key.removePrefix("system:").uppercase()) else stringResource(R.string.input_scope_game)
