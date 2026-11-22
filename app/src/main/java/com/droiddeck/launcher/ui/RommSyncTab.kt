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
        val got = withContext(Dispatchers.IO) { runCatching { RommActivity.status(context, id) }.getOrNull() }
        data = got ?: JSONObject()
        failed = got == null
        value = true
    }
    val d = data
    fun text(key: String) = d?.takeIf { !it.isNull(key) }?.optString(key).orEmpty()
    fun change(key: String, value: Any) {
        val next = JSONObject(d?.toString() ?: "{}").put(key, value)
        data = next
        RommActivity.setStatus(context, id, JSONObject().put(key, value)) { sent ->
            if (!sent) android.widget.Toast.makeText(context, context.getString(R.string.romm_status_queued), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val ready = loaded && d != null
    val unreachable = if (loaded && failed) stringResource(R.string.romm_status_unreachable) else null
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
}
