package com.droiddeck.launcher.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.GithubReleases
import com.droiddeck.launcher.ports.PortCatalog
import com.droiddeck.launcher.ports.PortRoms
import com.droiddeck.launcher.ports.PortSources
import com.droiddeck.launcher.ports.Ports
import com.droiddeck.launcher.ports.PortsState
import com.droiddeck.launcher.runtime.UserApps
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
    var problem by remember { mutableStateOf<Int?>(null) }
    Rise(0) { Eyebrow(stringResource(R.string.ports_card)) }
    Rise(1) { Title(stringResource(R.string.ports_sources)) }
    Rise(2) { Lede(stringResource(R.string.ports_sources_lede)) }
    Rise(3) {
        Actions {
            PrimaryButton(stringResource(R.string.ports_sources_add), main = true) { problem = null; adding = true }
            problem?.let { ActionChip(stringResource(it), ok = false) }
        }
    }
    SettingsGroup(stringResource(R.string.ports_sources_default)) {
        SettingsRow(stringResource(R.string.ports_sources_default), stringResource(R.string.ports_sources_count, Ports.all.count { it.source == null })) {}
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
                ConfirmButton(stringResource(R.string.edit_remove), stringResource(R.string.ports_sources_remove_confirm), compact = true) { PortsState.removeSource(ctx, s) }
            }
        }
    }
    val singles = sources.filter { it.port != null }
    if (singles.isNotEmpty()) SettingsGroup(stringResource(R.string.ports_sources_singles)) {
        singles.forEach { s ->
            val port = s.port ?: return@forEach
            SettingsRow(port.optString("name"), stringResource(R.string.ports_sources_single_hint, port.optString("game"), s.repo)) {
                ConfirmButton(stringResource(R.string.edit_remove), stringResource(R.string.ports_sources_remove_confirm), compact = true) { PortsState.removeSource(ctx, s) }
            }
        }
    }
    if (adding) TextEntryDialog(stringResource(R.string.ports_sources_add_title), "", onDismiss = { adding = false }) { typed ->
        val source = PortSources.parse(typed)
        problem = when {
            source == null -> R.string.ports_sources_bad
            !PortSources.add(ctx, source) -> R.string.ports_sources_known
            else -> { PortsState.sourcesChanged(ctx, source); null }
        }
    }
}

@Composable
internal fun AddPortButton(game: String, rom: () -> File?) {
    var open by remember { mutableStateOf(false) }
    SecondaryButton(stringResource(R.string.ports_add)) { open = true }
    if (open) AddPortDialog(game, rom) { open = false }
}

@Composable
private fun AddPortDialog(game: String, rom: () -> File?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var repo by remember { mutableStateOf("") }
    var program by remember { mutableStateOf("") }
    var staged by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var offered by remember { mutableStateOf<List<String>>(emptyList()) }
    var pick by remember { mutableStateOf(0) }
    val repoId = UserApps.githubRepo(repo)

    fun submit() {
        val id = repoId ?: return
        if (busy) return
        busy = true
        problem = null
        val app = ctx.applicationContext
        val chosen = offered.getOrNull(pick)
        Thread({
            var choices = offered
            val failure: String? = run outcome@{
                val file = rom()
                val root = PortsState.romsRoot(app)
                val facts = (if (file == null || root == null) null else PortRoms.facts(root, file))
                    ?: return@outcome app.getString(R.string.ports_add_bad_rom)
                PortSources.otherGame(id, facts, Ports.all)?.let { return@outcome app.getString(R.string.ports_add_other_game, it.name, it.game) }
                val asset = chosen ?: run {
                    val releases = GithubReleases.list(id) ?: return@outcome app.getString(R.string.user_apps_github_unreachable, id)
                    choices = PortSources.offered(releases)
                    if (choices.isEmpty()) return@outcome app.getString(R.string.ports_add_no_build, id)
                    if (choices.size > 1) return@outcome null
                    choices.first()
                }
                val entry = PortSources.single(id, asset, game, facts, program, staged, Ports.all.map { p -> p.id }.toSet())
                    ?: return@outcome app.getString(R.string.ports_add_invalid)
                if (!PortSources.add(app, PortSources.Source(id, null, entry))) return@outcome app.getString(R.string.ports_sources_known)
                PortsState.sourcesChanged(app)
                choices = emptyList()
                null
            }
            StoresState.post {
                busy = false
                problem = failure
                offered = if (failure == null) choices else emptyList()
                pick = 0
                if (failure == null && choices.isEmpty()) onDismiss()
            }
        }, "port-add").start()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.ports_add_title, game)) },
        text = {
            Column {
                AdbTextField(
                    value = repo, onValueChange = { repo = it; problem = null; offered = emptyList() },
                    label = stringResource(R.string.ports_add_repo), keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next,
                    placeholder = "owner/name", focusOnShow = true,
                )
                Spacer(Modifier.height(8.dp))
                AdbTextField(
                    value = program, onValueChange = { program = it; problem = null },
                    label = stringResource(R.string.ports_add_program), keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next,
                )
                Spacer(Modifier.height(8.dp))
                AdbTextField(
                    value = staged, onValueChange = { staged = it; problem = null },
                    label = stringResource(R.string.ports_add_staged), keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, onDone = { submit() },
                )
                Spacer(Modifier.height(8.dp))
                if (offered.size > 1) {
                    Text(stringResource(R.string.ports_add_choose), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    SheetChips(offered, pick) { pick = it }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    problem ?: stringResource(if (busy) R.string.ports_add_checking else R.string.ports_add_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = repoId != null && !busy, onClick = { submit() }, modifier = Modifier.focusGlide(CircleShape)) {
                Text(stringResource(R.string.ports_add_confirm))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
