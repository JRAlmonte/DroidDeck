package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.romm.RommBrowseState
import com.droiddeck.launcher.stores.romm.RommFirmwareState
import com.droiddeck.launcher.stores.romm.RommSort

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
                for (platform in RommBrowseState.platforms) MenuItem("${platform.name} (${platform.count})", chosen?.id == platform.id) {
                    RommBrowseState.choose(ctx, platform)
                    close()
                }
            }
            if (RommBrowseState.collections.isNotEmpty()) {
                val within = RommBrowseState.collection
                val every = stringResource(R.string.stores_romm_all_collections)
                BrowseDropdown(within?.name ?: every) { first, close ->
                    MenuItem(every, within == null, focusRequester = first) {
                        RommBrowseState.chooseCollection(ctx, null)
                        close()
                    }
                    for (collection in RommBrowseState.collections) MenuItem("${collection.name} (${collection.count})", within?.key == collection.key) {
                        RommBrowseState.chooseCollection(ctx, collection)
                        close()
                    }
                }
            }
            val sort = RommBrowseState.sort ?: RommSort.NAME
            BrowseDropdown(stringResource(R.string.stores_romm_sort, stringResource(sortLabel(sort)))) { first, close ->
                for (order in RommSort.values()) MenuItem(stringResource(sortLabel(order)), order == sort, focusRequester = if (order == RommSort.NAME) first else null) {
                    RommBrowseState.sortBy(ctx, order)
                    close()
                }
            }
            if (chosen != null) {
                LaunchedEffect(chosen.id) { RommFirmwareState.check(ctx, chosen.slug, chosen.id) }
                val bios = RommFirmwareState.status[chosen.id]
                when {
                    RommFirmwareState.working == chosen.id -> BrowseNote(stringResource(R.string.stores_romm_bios_getting))
                    bios == null || bios.held == 0 -> {}
                    bios.missing > 0 -> BrowseChip(stringResource(R.string.stores_romm_bios_get, bios.missing), false) { RommFirmwareState.fetch(ctx, chosen.slug, chosen.id) }
                    else -> BrowseNote(stringResource(R.string.stores_romm_bios_here))
                }
                if (RommFirmwareState.working == null) RommFirmwareState.problem?.let { BrowseNote(it) }
            }
        }
    }
}

private fun sortLabel(sort: RommSort): Int = when (sort) {
    RommSort.NAME -> R.string.stores_romm_sort_name
    RommSort.NAME_DESC -> R.string.stores_romm_sort_name_desc
    RommSort.RATING -> R.string.stores_romm_sort_rating
    RommSort.NEWEST -> R.string.stores_romm_sort_newest
    RommSort.OLDEST -> R.string.stores_romm_sort_oldest
    RommSort.ADDED -> R.string.stores_romm_sort_added
    RommSort.PLAYED -> R.string.stores_romm_sort_played
}

@Composable
private fun BrowseNote(text: String) =
    Text(text, fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))

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
