package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CloudSaves
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.romm.RommActivity
import com.droiddeck.launcher.stores.romm.RommBrowseState
import com.droiddeck.launcher.stores.romm.RommCollection
import com.droiddeck.launcher.stores.romm.RommDetails
import com.droiddeck.launcher.stores.romm.RommInstalls
import com.droiddeck.launcher.stores.romm.RommSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun RommSyncTab(id: String) {
    Column(Modifier.fillMaxWidth().tabPanel(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel(stringResource(R.string.romm_sync_saves))
        RommSaveRows(id)
        SectionLabel(stringResource(R.string.romm_on_server))
        RommStatusRows(id)
        RommCollectionRows(id)
        RommNoteRows(id)
    }
}

private const val NOTE_TITLE = 60

@Composable
private fun RommNoteRows(id: String) {
    val context = LocalContext.current.applicationContext
    val detail = RommDetails.known[id] ?: return
    val me = remember { com.droiddeck.launcher.stores.StoreAccounts.read(context, Store.ROMM)?.optString("user_id").orEmpty() }
    var editing by remember(id) { mutableStateOf<String?>(null) }
    SectionLabel(stringResource(R.string.romm_notes))
    for (note in detail.notes) {
        val mine = note.userId == me
        val about = if (mine) note.content else listOf(note.content, note.author).filter { it.isNotEmpty() }.joinToString(" · ")
        MenuItem(note.title.ifEmpty { note.content.take(NOTE_TITLE) }, checked = false, enabled = mine, detail = about.takeIf { it.isNotEmpty() && it != note.title }) { editing = note.id }
    }
    MenuItem(stringResource(R.string.romm_note_add), checked = false) { editing = "" }
    val target = editing ?: return
    val old = detail.notes.firstOrNull { it.id == target && it.userId == me }
    TextEntryDialog(stringResource(if (old == null) R.string.romm_note_add else R.string.romm_note_edit), old?.content.orEmpty(), onDismiss = { editing = null }) { text ->
        if (text.isEmpty() || text == old?.content) return@TextEntryDialog
        val title = old?.title?.ifEmpty { null } ?: text.take(NOTE_TITLE)
        val same = old ?: detail.notes.firstOrNull { it.userId == me && it.title == title }
        val written = com.droiddeck.launcher.stores.romm.RommNote(same?.id ?: "", title, text, same?.author.orEmpty(), me, same?.public ?: false, System.currentTimeMillis() / 1000)
        RommDetails.known[id] = detail.copy(notes = detail.notes.filterNot { it.id == written.id && it.userId == me && (same != null || it.title == title) } + written)
        RommActivity.note(context, id, same?.id?.ifEmpty { null }, title, text) { waits ->
            if (waits != null) android.widget.Toast.makeText(context, waits, android.widget.Toast.LENGTH_SHORT).show()
            else RommDetails.load(context, id, force = true)
        }
    }
}

@Composable
private fun RommCollectionRows(id: String) {
    val context = LocalContext.current.applicationContext
    val detail = RommDetails.known[id] ?: return
    val me = remember { com.droiddeck.launcher.stores.StoreAccounts.read(context, Store.ROMM)?.optString("user_id").orEmpty() }
    val mine = RommBrowseState.collections.filter { it.kind == RommCollection.Kind.REGULAR && !it.favorite && it.owner == me }
    val shown = (mine + detail.collections.filter { c -> c.kind == RommCollection.Kind.REGULAR && mine.none { it.id == c.id } && !RommBrowseState.collections.any { it.id == c.id && (it.favorite || it.owner != me) } })
        .distinctBy { it.id }.sortedBy { it.name.lowercase() }
    if (shown.isEmpty()) return
    SectionLabel(stringResource(R.string.romm_fact_collections))
    for (collection in shown) {
        val held = detail.collections.any { it.kind == RommCollection.Kind.REGULAR && it.id == collection.id }
        MenuItem(collection.name, checked = held) {
            val next = if (held) detail.collections.filterNot { it.kind == RommCollection.Kind.REGULAR && it.id == collection.id } else detail.collections + collection
            RommDetails.known[id] = detail.copy(collections = next)
            RommActivity.collect(context, id, collection.id, !held) { waits ->
                if (waits != null) android.widget.Toast.makeText(context, waits, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun RommSaveRows(id: String) {
    val context = LocalContext.current.applicationContext
    val installed = RommInstalls.entries[id] != null
    var busy by remember(id) { mutableStateOf(false) }
    var tick by remember(id) { mutableStateOf(0) }
    var enabled by remember(id) { mutableStateOf(RommSync.enabled(context, id)) }
    val status by produceState<CloudSaves.Status?>(null, id, installed, tick) {
        value = withContext(Dispatchers.IO) { runCatching { CloudSaves.status(context, Store.ROMM, id) }.getOrNull() }
    }
    val st = status
    val blocked = when (st?.reason) {
        null, "ok" -> null
        "not-installed" -> R.string.romm_sync_not_installed
        "other-emulator" -> R.string.romm_sync_other_emulator
        "no-title-id" -> R.string.romm_sync_no_title_id
        "old-server" -> R.string.romm_sync_old_server
        "no-access" -> R.string.romm_sync_no_access
        "not-signed-in" -> R.string.cloud_signed_out
        else -> R.string.cloud_not_synced
    }
    val conflict = st != null && st.conflicts.isNotEmpty()
    fun sync(up: Boolean, force: Boolean) {
        if (busy) return
        busy = true
        Thread({
            val r = if (up) CloudSaves.upload(context, Store.ROMM, id, force) else CloudSaves.download(context, Store.ROMM, id, force)
            StoresState.post { busy = false; tick++; android.widget.Toast.makeText(context, cloudMessage(context, r), android.widget.Toast.LENGTH_SHORT).show() }
        }, "cloud-manual").start()
    }
    MenuItem(stringResource(R.string.romm_sync_saves_toggle), checked = enabled && blocked == null, enabled = st != null && blocked == null,
        detail = blocked?.let { stringResource(it) }) {
        enabled = !enabled
        RommSync.setEnabled(context, id, enabled)
    }
    val ready = st != null && blocked == null && enabled && !busy
    val pending = stringResource(R.string.cloud_pending)
    val summary = when {
        st == null || busy -> pending
        blocked != null -> null
        conflict -> stringResource(R.string.cloud_conflict_files, st.conflicts.size)
        st.lastSync > 0 -> stringResource(R.string.cloud_last_sync, android.text.format.DateUtils.getRelativeTimeSpanString(st.lastSync).toString())
        else -> stringResource(R.string.cloud_never)
    }
    MenuItem(stringResource(if (conflict) R.string.cloud_keep_local else R.string.romm_upload), checked = false, enabled = ready, detail = summary) { sync(up = true, force = conflict) }
    MenuItem(stringResource(if (conflict) R.string.romm_keep_server else R.string.romm_download), checked = false, enabled = ready) { sync(up = false, force = conflict) }
}

private val STATUSES = listOf(
    "" to R.string.romm_status_none,
    "incomplete" to R.string.romm_status_incomplete,
    "finished" to R.string.romm_status_finished,
    "completed_100" to R.string.romm_status_completed,
    "retired" to R.string.romm_status_retired,
    "never_playing" to R.string.romm_status_never,
)

@Composable
private fun RommStatusRows(id: String) {
    val context = LocalContext.current.applicationContext
    var data by remember(id) { mutableStateOf<JSONObject?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    val loaded by produceState(false, id) {
        val got = RommDetails.known[id]?.user ?: withContext(Dispatchers.IO) { runCatching { RommDetails.fetch(context, id)?.user }.getOrNull() }
        data = got ?: JSONObject()
        failed = got == null
        value = true
    }
    val d = data
    fun text(key: String) = d?.takeIf { !it.isNull(key) }?.optString(key).orEmpty()
    fun change(key: String, value: Any) {
        val next = JSONObject(d?.toString() ?: "{}").put(key, value)
        data = next
        RommDetails.known[id]?.user?.put(key, value)
        RommActivity.setStatus(context, id, JSONObject().put(key, value)) { waits ->
            if (waits != null) android.widget.Toast.makeText(context, waits, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val ready = loaded && d != null
    val access by produceState(RommSync.Access.OK, id, data) {
        value = withContext(Dispatchers.IO) { runCatching { RommSync.access(context, RommActivity.PLAY_SCOPE) }.getOrDefault(RommSync.Access.OK) }
    }
    val unreachable = when {
        access == RommSync.Access.NO_ACCESS -> stringResource(R.string.romm_sync_no_access)
        access == RommSync.Access.OLD_SERVER -> stringResource(R.string.romm_sync_old_server)
        loaded && failed -> stringResource(R.string.romm_status_unreachable)
        else -> null
    }
    MenuItem(stringResource(R.string.romm_backlog), checked = d?.optBoolean("backlogged") == true, enabled = ready, detail = unreachable) {
        change("backlogged", d?.optBoolean("backlogged") != true)
    }
    MenuItem(stringResource(R.string.romm_now_playing), checked = d?.optBoolean("now_playing") == true, enabled = ready) {
        change("now_playing", d?.optBoolean("now_playing") != true)
    }
    val status = STATUSES.indexOfFirst { it.first == text("status") }.coerceAtLeast(0)
    MenuItem(stringResource(R.string.romm_status), checked = false, enabled = ready, detail = stringResource(STATUSES[status].second)) {
        val next = STATUSES[(status + 1) % STATUSES.size].first
        change("status", if (next.isEmpty()) JSONObject.NULL else next)
    }
    val rating = (d?.optInt("rating", 0) ?: 0).coerceIn(0, 10)
    MenuItem(stringResource(R.string.romm_rating), checked = false, enabled = ready,
        detail = if (rating == 0) stringResource(R.string.romm_rating_none) else stringResource(R.string.romm_rating_value, rating)) {
        change("rating", (rating + 1) % 11)
    }
    val difficulty = (d?.optInt("difficulty", 0) ?: 0).coerceIn(0, 10)
    MenuItem(stringResource(R.string.romm_difficulty), checked = false, enabled = ready,
        detail = if (difficulty == 0) stringResource(R.string.romm_status_none) else stringResource(R.string.romm_rating_value, difficulty)) {
        change("difficulty", (difficulty + 1) % 11)
    }
    val completion = (d?.optInt("completion", 0) ?: 0).coerceIn(0, 100)
    MenuItem(stringResource(R.string.romm_completion), checked = false, enabled = ready, detail = stringResource(R.string.romm_completion_value, completion)) {
        change("completion", if (completion >= 100) 0 else (completion / 10 + 1) * 10)
    }
    MenuItem(stringResource(R.string.romm_hidden), checked = d?.optBoolean("hidden") == true, enabled = ready) {
        change("hidden", d?.optBoolean("hidden") != true)
    }
}
