package com.droiddeck.launcher.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.files.InAppFilePicker
import com.droiddeck.launcher.ports.Port
import com.droiddeck.launcher.ports.PortCatalog
import com.droiddeck.launcher.ports.PortLaunch
import com.droiddeck.launcher.ports.PortRoms
import com.droiddeck.launcher.ports.PortSearch
import com.droiddeck.launcher.ports.PortSources
import com.droiddeck.launcher.ports.Ports
import com.droiddeck.launcher.ports.PortsState
import com.droiddeck.launcher.stores.StoresState
import java.io.File
import com.droiddeck.launcher.ui.GlideAlertDialog as AlertDialog

internal const val PORT_SOURCES_PAGE = "port:+sources"

@Composable
internal fun PortSourcesPage() {
    val ctx = LocalContext.current
    val tick = PortsState.sources
    val sources = remember(tick) { PortSources.list(ctx) }
    val counts = remember(tick) { sources.filter { it.port == null }.associate { it.key to PortCatalog.cached(ctx, it) } }
    var adding by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val pickImport = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val file = InAppFilePicker.pickedFile(r.data)?.takeIf { r.resultCode == Activity.RESULT_OK } ?: return@rememberLauncherForActivityResult
        PortsState.import(ctx, file) { got ->
            notice = when {
                got == null -> ctx.getString(R.string.ports_import_bad) to false
                got.skipped > 0 -> ctx.getString(R.string.ports_imported_skipped, got.added, got.skipped) to (got.added > 0)
                else -> ctx.getString(R.string.ports_imported, got.added) to true
            }
        }
    }
    val pickExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val app = ctx.applicationContext
        Thread({
            val count = PortCatalog.user(app).distinctBy { it.id }.size
            val saved = runCatching {
                app.contentResolver.openOutputStream(uri)?.use { it.write(PortSources.export(app).toByteArray()) } ?: error("no destination")
            }.isSuccess
            StoresState.post { notice = if (saved) ctx.getString(R.string.ports_exported, count) to true else ctx.getString(R.string.ports_export_failed) to false }
        }, "port-export").start()
    }
    Rise(0) { Title(stringResource(R.string.ports_sources)) }
    Rise(1) {
        Actions {
            PrimaryButton(stringResource(R.string.ports_sources_add), main = true, icon = Icons.Filled.Add) { notice = null; adding = true }
            IconAction(Icons.Outlined.FileOpen, stringResource(R.string.ports_import), "portsource:import") {
                notice = null
                pickImport.launch(InAppFilePicker.buildIntent(ctx, listOf("json"), ctx.getString(R.string.ports_import_pick)))
            }
            IconAction(Icons.Outlined.Save, stringResource(R.string.ports_export), "portsource:export", enabled = sources.isNotEmpty()) {
                notice = null
                pickExport.launch("droiddeck-ports.json")
            }
            notice?.let { ActionChip(it.first, ok = it.second) }
        }
    }
    val lists = sources.filter { it.port == null }
    if (lists.isNotEmpty()) SettingsGroup(stringResource(R.string.ports_sources_lists)) {
        lists.forEach { s ->
            val found = counts[s.key].orEmpty()
            val used = found.count { p -> Ports.all.any { it.id == p.id && it.source == s.repo } }
            val hint = when {
                found.isEmpty() && s.key in PortCatalog.failed -> stringResource(R.string.ports_sources_failed)
                found.isEmpty() -> stringResource(R.string.ports_sources_reading)
                used < found.size -> stringResource(R.string.ports_sources_count_listed, used, found.size - used)
                else -> stringResource(R.string.ports_sources_count, used)
            }
            SettingsRow(s.repo + (s.branch?.let { " @ $it" } ?: ""), hint) {
                ConfirmIconAction(Icons.Outlined.Delete, stringResource(R.string.edit_remove), stringResource(R.string.ports_sources_remove_confirm), "portsource:${s.key}") { PortsState.removeSource(ctx, s) }
            }
        }
    }
    val singles = sources.filter { it.port != null }
    if (singles.isNotEmpty()) SettingsGroup(stringResource(R.string.ports_sources_singles)) {
        singles.forEach { s ->
            val port = s.port ?: return@forEach
            SettingsRow(port.optString("name"), stringResource(R.string.ports_sources_single_hint, port.optString("game"), s.repo)) {
                ConfirmIconAction(Icons.Outlined.Delete, stringResource(R.string.edit_remove), stringResource(R.string.ports_sources_remove_confirm), "portsource:${s.key}") { PortsState.removeSource(ctx, s) }
            }
        }
    }
    if (adding) TextEntryDialog(stringResource(R.string.ports_sources_add_title), "", onDismiss = { adding = false }) { typed ->
        val source = PortSources.parse(typed)
        notice = when {
            source == null -> ctx.getString(R.string.ports_sources_bad) to false
            !PortSources.add(ctx, source) -> ctx.getString(R.string.ports_sources_known) to false
            else -> { PortsState.sourcesChanged(ctx, source); null }
        }
    }
}

@Composable
internal fun AddPortButton(game: String, rom: () -> File?) {
    var open by remember { mutableStateOf(false) }
    IconAction(Icons.Outlined.Extension, stringResource(R.string.ports_add), "port:add") { open = true }
    if (open) AddPortDialog(game, rom) { open = false }
}

@Composable
private fun AddPortDialog(game: String, rom: () -> File?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    var words by remember { mutableStateOf(PortSearch.terms(game)) }
    var program by remember { mutableStateOf("") }
    var staged by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var env by remember { mutableStateOf("") }
    var more by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<PortSearch.Candidate>>(emptyList()) }
    var picked by remember { mutableStateOf<String?>(null) }
    var asset by remember { mutableStateOf(0) }
    var armed by remember { mutableStateOf(false) }
    var facts by remember { mutableStateOf<PortRoms.Facts?>(null) }
    var round by remember { mutableStateOf(0) }
    var retype by remember { mutableStateOf(0) }
    val choice = found.firstOrNull { it.hit.repo == picked }?.takeIf { it.addable }

    fun search() {
        val asked = words.trim()
        if (busy || asked.isEmpty()) return
        busy = true; problem = null; found = emptyList(); picked = null; armed = false; asset = 0
        val seq = ++round
        val known = facts
        Thread({
            val read = known ?: run {
                val file = rom()
                val root = PortsState.romsRoot(app)
                if (file == null || root == null) null else PortRoms.facts(root, file)
            }
            if (read == null) {
                StoresState.post { busy = false; problem = app.getString(R.string.ports_add_bad_rom) }
                return@Thread
            }
            val outcome = PortSearch.find(asked, read) { c -> StoresState.post { if (seq == round) found = found + c } }
            val limit = outcome.limited
            val message = when {
                limit != null -> limit.resetAt
                    ?.let { app.getString(R.string.ports_search_limited, android.text.format.DateFormat.getTimeFormat(app).format(java.util.Date(it * 1000))) }
                    ?: app.getString(R.string.ports_search_limited_soon)
                outcome.unreachable -> app.getString(R.string.ports_search_unreachable)
                outcome.candidates.isEmpty() -> app.getString(R.string.ports_search_none)
                else -> null
            }
            StoresState.post {
                facts = read
                if (seq != round) return@post
                busy = false; problem = message; found = outcome.candidates
                picked = outcome.candidates.singleOrNull { it.addable }?.hit?.repo
                if (outcome.candidates.none { it.addable }) retype++
            }
        }, "port-search").start()
    }

    fun add() {
        val c = choice ?: return
        val known = facts ?: return
        val name = c.assets.getOrNull(asset) ?: return
        if (busy) return
        if (c.verdict != PortSearch.Verdict.CONFIRMED && !armed) { armed = true; return }
        busy = true; problem = null
        Thread({
            val failure: String? = run outcome@{
                PortSources.otherGame(c.hit.repo, known, PortCatalog.listed(app))?.let { return@outcome app.getString(R.string.ports_add_other_game, it.name, it.game) }
                val typedArgs = PortLaunch.args(args) ?: return@outcome app.getString(R.string.ports_add_invalid)
                val typedEnv = PortLaunch.env(env) ?: return@outcome app.getString(R.string.ports_add_invalid)
                val entry = PortSources.single(c.hit.repo, name, game, known, program, staged, Ports.all.map { p -> p.id }.toSet(), typedArgs, typedEnv)
                    ?: return@outcome app.getString(R.string.ports_add_invalid)
                if (!PortSources.add(app, PortSources.Source(c.hit.repo, null, entry))) return@outcome app.getString(R.string.ports_sources_known)
                PortsState.sourcesChanged(app)
                null
            }
            StoresState.post { busy = false; armed = false; problem = failure; if (failure == null) onDismiss() }
        }, "port-add").start()
    }

    LaunchedEffect(Unit) { search() }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.ports_add_title, game)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.weight(1f)) {
                        key(retype) {
                            AdbTextField(
                                value = words, onValueChange = { if (it != words) { words = it.take(200); problem = null; armed = false } },
                                label = stringResource(R.string.ports_search_label), keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search,
                                onDone = { search() }, focusOnShow = retype > 0,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.padding(bottom = 9.dp)) { SmallIconButton(Icons.Filled.Search, stringResource(R.string.ports_search), MaterialTheme.colorScheme.onSurfaceVariant) { search() } }
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.padding(bottom = 9.dp)) { SmallIconButton(Icons.Filled.Tune, stringResource(R.string.ports_add_more), MaterialTheme.colorScheme.onSurfaceVariant) { more = !more } }
                }
                Spacer(Modifier.height(8.dp))
                if (more) {
                    AdbTextField(
                        value = program, onValueChange = { program = it; problem = null },
                        label = stringResource(R.string.ports_add_program), keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next,
                    )
                    Spacer(Modifier.height(8.dp))
                    AdbTextField(
                        value = staged, onValueChange = { staged = it; problem = null },
                        label = stringResource(R.string.ports_add_staged), keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next,
                    )
                    Spacer(Modifier.height(8.dp))
                    AdbTextField(
                        value = args, onValueChange = { args = it.take(600); problem = null },
                        label = stringResource(R.string.ports_add_args), keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next,
                    )
                    Spacer(Modifier.height(8.dp))
                    AdbTextField(
                        value = env, onValueChange = { env = it.take(600); problem = null },
                        label = stringResource(R.string.ports_add_env), keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (found.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 232.dp).verticalScroll(rememberScrollState())) {
                        found.forEach { c -> CandidateRow(c, c.hit.repo == picked) { picked = c.hit.repo; armed = false; asset = 0; problem = null } }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (choice != null && choice.assets.size > 1) {
                    Text(stringResource(R.string.ports_add_choose), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    SheetChips(choice.assets, asset) { asset = it }
                    Spacer(Modifier.height(8.dp))
                }
                val warning = problem ?: choice?.takeIf { armed }?.let { stringResource(R.string.ports_add_unconfirmed, it.hit.repo) }
                Text(
                    warning ?: stringResource(if (busy) R.string.ports_add_checking else R.string.ports_add_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warning != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = choice != null && !busy, onClick = { add() }, modifier = Modifier.focusGlide(CircleShape)) {
                Text(stringResource(if (armed) R.string.ports_add_anyway else R.string.ports_add_confirm))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun CandidateRow(c: PortSearch.Candidate, selected: Boolean, onPick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val verdict = stringResource(
        when (c.verdict) {
            PortSearch.Verdict.CONFIRMED -> R.string.ports_hash_found
            PortSearch.Verdict.UNCONFIRMED -> R.string.ports_hash_unconfirmed
            PortSearch.Verdict.NO_BUILD -> R.string.ports_search_no_build
            PortSearch.Verdict.NO_HASH -> R.string.ports_search_no_hash
            PortSearch.Verdict.UNREACHABLE -> R.string.ports_search_unchecked
        },
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(Shape12)
            .background(if (selected) LocalPalette.current.signal.copy(alpha = 0.14f) else Color.Transparent)
            .focusGlide(Shape12)
            .alpha(if (c.addable) 1f else 0.55f)
            .clickable(enabled = c.addable, role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.hit.repo, fontSize = 14.sp, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.hit.about.isNotEmpty()) Text(c.hit.about, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Chip(verdict, ok = c.verdict == PortSearch.Verdict.CONFIRMED)
    }
}

/** Launch settings of a port the user added themselves; a port of a list has none to change here. */
@Composable
internal fun PortSettingsButton(port: Port) {
    val ctx = LocalContext.current
    val tick = PortsState.sources
    val own = remember(port.id, tick) { PortSources.own(ctx, port.id)?.takeIf { PortCatalog.listed(ctx).none { p -> p.id == port.id } } } ?: return
    var open by remember { mutableStateOf(false) }
    IconAction(Icons.Filled.Tune, stringResource(R.string.ports_settings), "port:settings") { open = true }
    if (!open) return
    val now = remember(own) { PortCatalog.single(own, port.repo) ?: port }
    var program by remember { mutableStateOf(now.entry) }
    var staged by remember { mutableStateOf(now.stagedRom) }
    var args by remember { mutableStateOf(PortLaunch.show(now.args)) }
    var env by remember { mutableStateOf(PortLaunch.show(now.env)) }
    var problem by remember { mutableStateOf(false) }
    fun save() {
        val typedArgs = PortLaunch.args(args)
        val typedEnv = PortLaunch.env(env)
        if (typedArgs == null || typedEnv == null || !PortSources.setLaunch(ctx, port.id, program, staged, typedArgs, typedEnv)) { problem = true; return }
        PortsState.sourcesChanged(ctx)
        open = false
    }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.ports_settings_title, port.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AdbTextField(value = args, onValueChange = { args = it.take(600); problem = false }, label = stringResource(R.string.ports_add_args), keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next)
                Spacer(Modifier.height(8.dp))
                AdbTextField(value = env, onValueChange = { env = it.take(600); problem = false }, label = stringResource(R.string.ports_add_env), keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next)
                Spacer(Modifier.height(8.dp))
                AdbTextField(value = program, onValueChange = { program = it.take(200); problem = false }, label = stringResource(R.string.ports_add_program), keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next)
                Spacer(Modifier.height(8.dp))
                AdbTextField(value = staged, onValueChange = { staged = it.take(200); problem = false }, label = stringResource(R.string.ports_add_staged), keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, onDone = { save() })
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (problem) R.string.ports_add_invalid else R.string.ports_settings_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { save() }, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.edit_app_save)) } },
        dismissButton = { TextButton(onClick = { open = false }, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_cancel)) } },
    )
}
