package com.droiddeck.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CloudSaves
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.romm.RommActivity
import com.droiddeck.launcher.stores.romm.RommAsset
import com.droiddeck.launcher.stores.romm.RommAssets
import com.droiddeck.launcher.stores.romm.RommBrowseState
import com.droiddeck.launcher.stores.romm.RommCollection
import com.droiddeck.launcher.stores.romm.RommDetails
import com.droiddeck.launcher.stores.romm.RommInstalls
import com.droiddeck.launcher.stores.romm.RommSaves
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
    var open by remember(id) { mutableStateOf<String?>(null) }
    var armed by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(armed) { if (armed != null) { kotlinx.coroutines.delay(4000); armed = null } }
    val may = remember(id, RommSync.noted) { RommSync.may(context, RommActivity.PLAY_SCOPE) }
    val told: (Int?) -> Unit = { waits ->
        if (waits != null) android.widget.Toast.makeText(context, waits, android.widget.Toast.LENGTH_SHORT).show()
        else RommDetails.load(context, id, force = true)
    }
    SectionLabel(stringResource(R.string.romm_notes))
    for (note in detail.notes) {
        val mine = note.userId == me
        val shared = stringResource(R.string.romm_note_shared).takeIf { mine && note.public }
        val about = listOfNotNull(note.content.takeIf { it != note.title }, note.author.takeIf { !mine }, shared).filter { it.isNotEmpty() }.joinToString(" · ")
        MenuItem(note.title.ifEmpty { note.content.take(NOTE_TITLE) }, checked = false, enabled = mine && may, detail = about.ifEmpty { null }) {
            // The server has not named a note written a moment ago yet, so it can only be written again.
            if (note.id.isEmpty()) editing = note.id else open = note.id.takeIf { open != it }
        }
        if (mine && may && note.id.isNotEmpty() && open == note.id) RommNoteActions(
            note.public, armed == note.id,
            edit = { editing = note.id },
            share = {
                RommDetails.known[id] = detail.copy(notes = detail.notes.map {
                    if (it.id == note.id && it.userId == me) com.droiddeck.launcher.stores.romm.RommNote(it.id, it.title, it.content, it.author, it.userId, !note.public) else it
                })
                RommActivity.shareNote(context, id, note.id, !note.public, told)
            },
            delete = {
                if (armed != note.id) armed = note.id
                else {
                    armed = null
                    open = null
                    RommDetails.known[id] = detail.copy(notes = detail.notes.filterNot { it.id == note.id && it.userId == me })
                    RommActivity.dropNote(context, id, note.id, told)
                }
            },
        )
    }
    MenuItem(stringResource(R.string.romm_note_add), checked = false, enabled = may, detail = stringResource(R.string.romm_sync_no_access).takeIf { !may }) { editing = "" }
    val target = editing?.takeIf { may } ?: return
    val old = detail.notes.firstOrNull { it.id == target && it.userId == me }
    TextEntryDialog(stringResource(if (old == null) R.string.romm_note_add else R.string.romm_note_edit), old?.content.orEmpty(), onDismiss = { editing = null }) { text ->
        if (text.isEmpty() || text == old?.content) return@TextEntryDialog
        val title = old?.title?.ifEmpty { null } ?: text.take(NOTE_TITLE)
        val same = old ?: detail.notes.firstOrNull { it.userId == me && it.title == title }
        val written = com.droiddeck.launcher.stores.romm.RommNote(same?.id ?: "", title, text, same?.author.orEmpty(), me, same?.public ?: false)
        RommDetails.known[id] = detail.copy(notes = detail.notes.filterNot { it.id == written.id && it.userId == me && (same != null || it.title == title) } + written)
        RommActivity.note(context, id, same?.id?.ifEmpty { null }, title, text) { waits ->
            if (waits != null) android.widget.Toast.makeText(context, waits, android.widget.Toast.LENGTH_SHORT).show()
            else RommDetails.load(context, id, force = true)
        }
    }
}

/** What can be done with one of the account's own notes, under it: change it, let others read it, and delete it when that is chosen a second time. */
@Composable
internal fun RommNoteActions(public: Boolean, armed: Boolean, edit: () -> Unit, share: () -> Unit, delete: () -> Unit) {
    val under = Modifier.padding(start = 18.dp)
    MenuItem(stringResource(R.string.romm_note_edit), checked = false, modifier = under, onClick = edit)
    MenuItem(stringResource(R.string.romm_note_public), checked = public, modifier = under, onClick = share)
    MenuItem(stringResource(R.string.romm_note_delete), checked = false, modifier = under, detail = stringResource(R.string.romm_note_delete_confirm).takeIf { armed }, onClick = delete)
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
    val may = remember(id, RommSync.noted) { RommSync.may(context, com.droiddeck.launcher.stores.romm.RommCollections.SCOPE) }
    SectionLabel(stringResource(R.string.romm_fact_collections))
    for ((index, collection) in shown.withIndex()) {
        val held = detail.collections.any { it.kind == RommCollection.Kind.REGULAR && it.id == collection.id }
        MenuItem(collection.name, checked = held, enabled = may, detail = stringResource(R.string.romm_sync_no_access).takeIf { !may && index == 0 }) {
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
            // Asked for by hand, an upload also sends back what was deleted on the server from here.
            if (up) RommAssets.forget(context, id)
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
    RommAssetRows(id, tick)
}

/** The saves and states the server holds for the game. One is deleted there, and only there, when it is chosen a second time. */
@Composable
private fun RommAssetRows(id: String, tick: Int) {
    val context = LocalContext.current.applicationContext
    var reload by remember(id) { mutableStateOf(0) }
    val assets by produceState<List<RommAsset>?>(null, id, tick, reload) {
        withContext(Dispatchers.IO) { runCatching { RommAssets.list(context, id) }.getOrNull() }?.let { value = it }
    }
    if (!remember(id, RommSync.noted, assets) { RommSync.may(context, "assets.read") }) {
        SectionLabel(stringResource(R.string.romm_assets))
        RommAssetsRefused()
        return
    }
    val list = assets?.takeIf { it.isNotEmpty() } ?: return
    val may = remember(id, RommSync.noted) { RommSync.may(context, "assets.write") }
    var armed by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(armed) { if (armed != null) { kotlinx.coroutines.delay(4000); armed = null } }
    SectionLabel(stringResource(R.string.romm_assets))
    for (asset in list) {
        val key = asset.kind + ":" + asset.id
        RommAssetRow(asset, rommAssetPicture(asset), armed == key, enabled = may) {
            if (armed != key) { armed = key; return@RommAssetRow }
            armed = null
            Thread({
                val answer = runCatching { RommAssets.delete(context, id, asset) }.getOrNull()
                StoresState.post {
                    when (answer?.code ?: 0) {
                        in 200..299, 404 -> reload++
                        401, 403 -> android.widget.Toast.makeText(context, R.string.romm_sync_no_access, android.widget.Toast.LENGTH_SHORT).show()
                        else -> android.widget.Toast.makeText(context, R.string.romm_status_unreachable, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }, "romm-asset-delete").start()
        }
    }
}

/** Stands where the server's saves and states would be listed when it refused to list them. */
@Composable
internal fun RommAssetsRefused() =
    MenuItem(stringResource(R.string.romm_assets_refused), checked = false, enabled = false, detail = stringResource(R.string.romm_sync_no_access)) {}

/** One save or state on the server: its slot, size, emulator and age, or what choosing it again does when [armed]. */
@Composable
internal fun RommAssetRow(asset: RommAsset, picture: ImageBitmap?, armed: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val about = listOfNotNull(
        stringResource(if (asset.kind == RommSaves.STATES) R.string.romm_asset_state else R.string.romm_asset_save),
        asset.slot.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.romm_asset_slot, it) },
        asset.size.takeIf { it > 0 }?.let(::formatBytes),
        asset.emulator.takeIf { it.isNotEmpty() },
        asset.updatedMs.takeIf { it > 0 }?.let { android.text.format.DateUtils.getRelativeTimeSpanString(it).toString() },
    ).joinToString(" · ")
    MenuItem(
        asset.name, checked = false, enabled = enabled, detail = if (armed) stringResource(R.string.romm_asset_delete_confirm) else about,
        leading = picture?.let { shot -> {
            Image(shot, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp, 40.dp).clip(RoundedCornerShape(4.dp)))
        } },
        onClick = onClick,
    )
}

private const val PICTURE_PX = 256

/** The screenshot the server keeps with a save or state, scaled down while it is read. */
@Composable
private fun rommAssetPicture(asset: RommAsset): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    return produceState<ImageBitmap?>(null, asset.picture) {
        if (asset.picture.isNotEmpty()) value = withContext(Dispatchers.IO) {
            runCatching {
                val data = RommAssets.picture(context, asset.picture) ?: return@runCatching null
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = Integer.highestOneBit(maxOf(1, bounds.outWidth / PICTURE_PX)) }
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, options)?.asImageBitmap()
            }.getOrNull()
        }
    }.value
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
    val access by produceState(RommSync.Access.OK, id, data, RommSync.noted) {
        value = withContext(Dispatchers.IO) { runCatching { RommSync.access(context, RommActivity.PLAY_SCOPE) }.getOrDefault(RommSync.Access.OK) }
    }
    val ready = loaded && d != null && access != RommSync.Access.NO_ACCESS
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
