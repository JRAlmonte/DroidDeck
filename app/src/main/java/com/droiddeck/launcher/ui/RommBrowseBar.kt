package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.romm.RommBrowseState

@Composable
internal fun RommBrowseBar() {
    val ctx = LocalContext.current
    val chosen = RommBrowseState.platform
    val all = stringResource(R.string.stores_romm_all_platforms)
    LaunchedEffect(Unit) { if (RommBrowseState.items.isEmpty()) RommBrowseState.more(ctx) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)) {
        ChipRow {
            BrowseDropdown(chosen?.name ?: all) { first, close ->
                MenuItem(all, chosen == null, focusRequester = first) {
                    RommBrowseState.choose(ctx, null)
                    close()
                }
                for (platform in RommBrowseState.platforms) MenuItem(platform.name, chosen?.id == platform.id, detail = platform.count.toString()) {
                    RommBrowseState.choose(ctx, platform)
                    close()
                }
            }
        }
    }
}

@Composable
internal fun RommLoadMore() {
    val ctx = LocalContext.current
    val count = RommBrowseState.items.size
    LaunchedEffect(Unit) { RommBrowseState.more(ctx) }
    if (RommBrowseState.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
    else if (RommBrowseState.failed) Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        BrowseChip(stringResource(R.string.stores_dl_retry), false) { RommBrowseState.more(ctx, retry = true) }
    }
    else if (!RommBrowseState.ended && count > 0) Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        BrowseChip(stringResource(R.string.stores_itch_load_more), false) { RommBrowseState.more(ctx) }
    }
}
