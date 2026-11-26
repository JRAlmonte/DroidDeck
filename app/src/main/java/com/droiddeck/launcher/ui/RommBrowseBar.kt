package com.droiddeck.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.romm.RommAdopt
import com.droiddeck.launcher.stores.romm.RommBrowseState
import com.droiddeck.launcher.stores.romm.RommFilter
import com.droiddeck.launcher.stores.romm.RommFirmwareState
import com.droiddeck.launcher.stores.romm.RommSort

@Composable
internal fun RommBrowseBar(icons: Boolean, onOpen: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val chosen = RommBrowseState.platform
    val all = stringResource(R.string.stores_romm_all_platforms)
    LaunchedEffect(Unit) { if (RommBrowseState.items.isEmpty()) RommBrowseState.more(ctx) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val pick: @Composable () -> Unit = {
            val logo = if (icons) rommPlatformLogo(chosen) else null
            BrowseDropdown(
                if (icons) chosen?.name.orEmpty().takeIf { logo == null }.orEmpty() else chosen?.name ?: all, RommIcons.Platform.takeIf { icons }, (chosen?.name ?: all).takeIf { icons },
                leading = logo?.let { picture -> { Image(picture, contentDescription = null, modifier = Modifier.size(28.dp)) } },
            ) { first, close ->
                MenuItem(all, chosen == null, focusRequester = first, leading = if (icons) ({ Box(Modifier.size(24.dp)) }) else null) {
                    RommBrowseState.choose(ctx, null)
                    close()
                }
                for (platform in RommBrowseState.platforms) MenuItem(
                    "${platform.name} (${platform.count})", chosen?.id == platform.id, leading = if (icons) ({ RommPlatformLogo(platform) }) else null,
                ) {
                    RommBrowseState.choose(ctx, platform)
                    close()
                }
            }
            if (RommBrowseState.collections.isNotEmpty()) {
                val within = RommBrowseState.collection
                val every = stringResource(R.string.stores_romm_all_collections)
                BrowseDropdown(if (icons) within?.name.orEmpty() else within?.name ?: every, RommIcons.Collection.takeIf { icons }, (within?.name ?: every).takeIf { icons }) { first, close ->
                    MenuItem(every, within == null, focusRequester = first) {
                        RommBrowseState.chooseCollection(ctx, null)
                        close()
                    }
                    for (collection in RommBrowseState.collections) MenuItem("${collection.name} (${collection.count})", within?.key == collection.key, detail = collection.about.takeIf { it.isNotEmpty() }) {
                        RommBrowseState.chooseCollection(ctx, collection)
                        close()
                    }
                }
            }
            val sort = RommBrowseState.sort ?: RommSort.DEFAULT
            val sorted = stringResource(R.string.stores_romm_sort, stringResource(sortLabel(sort)))
            BrowseDropdown(if (icons) stringResource(sortLabel(sort)) else sorted, RommIcons.Sort.takeIf { icons }, sorted.takeIf { icons }) { first, close ->
                for (order in RommSort.values()) MenuItem(stringResource(sortLabel(order)), order == sort, focusRequester = if (order == sort) first else null) {
                    RommBrowseState.sortBy(ctx, order)
                    close()
                }
            }
            BrowseChip(stringResource(R.string.stores_romm_random), false, RommIcons.Random.takeIf { icons }) {
                RommBrowseState.random(ctx) { item ->
                    if (item != null) onOpen(item.key) else android.widget.Toast.makeText(ctx, R.string.romm_status_unreachable, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        val narrow: @Composable () -> Unit = {
            val filter = RommBrowseState.filter
            val values = RommBrowseState.values
            fun toggle(set: Set<String>, value: String): Set<String> = if (value in set) set - value else set + value
            @Composable fun Values(label: Int, icon: ImageVector, all: List<String>, picked: Set<String>, change: (Set<String>) -> RommFilter) {
                if (all.isEmpty() && picked.isEmpty()) return
                val any = stringResource(label)
                val summary = browseSummary(picked.sorted(), any)
                BrowseDropdown(if (icons) browseSummary(picked.sorted(), "") else summary, icon.takeIf { icons }, summary.takeIf { icons }) { first, _ ->
                    MenuItem(any, picked.isEmpty(), focusRequester = first) { RommBrowseState.narrow(ctx, change(emptySet())) }
                    for (value in all) MenuItem(value, value in picked) { RommBrowseState.narrow(ctx, change(toggle(picked, value))) }
                }
            }
            Values(R.string.stores_romm_filter_genre, RommIcons.Genre, values.genres, filter.genres) { filter.copy(genres = it) }
            Values(R.string.stores_romm_filter_region, RommIcons.Region, values.regions, filter.regions) { filter.copy(regions = it) }
            Values(R.string.stores_romm_filter_language, RommIcons.Language, values.languages, filter.languages) { filter.copy(languages = it) }
            Values(R.string.stores_romm_filter_franchise, RommIcons.Franchise, values.franchises, filter.franchises) { filter.copy(franchises = it) }
            Values(R.string.stores_romm_filter_company, RommIcons.Companies, values.companies, filter.companies) { filter.copy(companies = it) }
            Values(R.string.stores_romm_filter_age, RommIcons.Age, values.ageRatings, filter.ageRatings) { filter.copy(ageRatings = it) }
            Values(R.string.stores_romm_filter_players, RommIcons.Players, values.players, filter.players) { filter.copy(players = it) }
            BrowseChip(stringResource(R.string.stores_romm_filter_installed), filter.installed, RommIcons.Installed.takeIf { icons }) { RommBrowseState.narrow(ctx, filter.copy(installed = !filter.installed)) }
            if (!filter.installed) {
                BrowseChip(stringResource(R.string.stores_romm_filter_favorites), filter.favorite, RommIcons.Favourites.takeIf { icons }) { RommBrowseState.narrow(ctx, filter.copy(favorite = !filter.favorite)) }
                BrowseChip(stringResource(R.string.romm_backlog), filter.backlog, RommIcons.Backlog.takeIf { icons }) { RommBrowseState.narrow(ctx, filter.copy(backlog = !filter.backlog)) }
                BrowseChip(stringResource(R.string.romm_now_playing), filter.nowPlaying, RommIcons.NowPlaying.takeIf { icons }) { RommBrowseState.narrow(ctx, filter.copy(nowPlaying = !filter.nowPlaying)) }
                val played = listOf(null to R.string.stores_romm_filter_played_any, true to R.string.stores_romm_filter_played_yes, false to R.string.stores_romm_filter_played_no)
                val playedLabel = stringResource(played.first { it.first == filter.played }.second)
                BrowseDropdown(if (icons && filter.played == null) "" else playedLabel, RommIcons.Played.takeIf { icons }, playedLabel.takeIf { icons }) { first, close ->
                    played.forEachIndexed { index, (value, label) ->
                        MenuItem(stringResource(label), filter.played == value, focusRequester = if (index == 0) first else null) {
                            RommBrowseState.narrow(ctx, filter.copy(played = value))
                            close()
                        }
                    }
                }
                val statuses = listOf(
                    "" to R.string.stores_romm_filter_status, "incomplete" to R.string.romm_status_incomplete, "finished" to R.string.romm_status_finished,
                    "completed_100" to R.string.romm_status_completed, "retired" to R.string.romm_status_retired, "never_playing" to R.string.romm_status_never,
                    "hidden" to R.string.romm_hidden,
                )
                val statusLabel = stringResource(statuses.first { it.first == filter.status }.second)
                BrowseDropdown(if (icons && filter.status.isEmpty()) "" else statusLabel, RommIcons.Status.takeIf { icons }, statusLabel.takeIf { icons }) { first, close ->
                    statuses.forEachIndexed { index, (value, label) ->
                        MenuItem(stringResource(label), filter.status == value, focusRequester = if (index == 0) first else null) {
                            RommBrowseState.narrow(ctx, filter.copy(status = value))
                            close()
                        }
                    }
                }
                Values(R.string.stores_romm_filter_tag, RommIcons.Revision, values.tags, filter.tags) { filter.copy(tags = it) }
                BrowseChip(stringResource(R.string.stores_romm_filter_saves), filter.saves, RommIcons.SyncTab.takeIf { icons }) { RommBrowseState.narrow(ctx, filter.copy(saves = !filter.saves)) }
                BrowseChip(stringResource(R.string.achievements_title), filter.achievements, RommIcons.Achievements.takeIf { icons }) { RommBrowseState.narrow(ctx, filter.copy(achievements = !filter.achievements)) }
                BrowseChip(stringResource(R.string.romm_fact_verified), filter.verified, RommIcons.Verified.takeIf { icons }) { RommBrowseState.narrow(ctx, filter.copy(verified = !filter.verified)) }
            }
            val installed = com.droiddeck.launcher.stores.romm.RommInstalls.entries
            LaunchedEffect(installed, RommAdopt.busy) { RommAdopt.count(ctx, installed) }
            val unmatched = RommAdopt.waiting
            if (RommAdopt.busy) BrowseNote(stringResource(R.string.stores_romm_adopt_busy, RommAdopt.checked, RommAdopt.total))
            else if (unmatched > 0) BrowseChip(stringResource(R.string.stores_romm_adopt, unmatched), false, RommIcons.Adopt.takeIf { icons }, unmatched.toString()) {
                RommAdopt.run(ctx) { found ->
                    android.widget.Toast.makeText(ctx, ctx.resources.getQuantityString(R.plurals.stores_romm_adopted, found, found), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            if (chosen != null) {
                LaunchedEffect(chosen.id) { RommFirmwareState.check(ctx, chosen.slug, chosen.id) }
                val bios = RommFirmwareState.status[chosen.id]
                when {
                    RommFirmwareState.working == chosen.id -> BrowseNote(stringResource(R.string.stores_romm_bios_getting))
                    bios == null || bios.held == 0 -> {}
                    bios.missing > 0 -> BrowseChip(stringResource(R.string.stores_romm_bios_get, bios.missing), false, RommIcons.Bios.takeIf { icons }, bios.missing.toString()) { RommFirmwareState.fetch(ctx, chosen.slug, chosen.id) }
                    else -> BrowseNote(stringResource(R.string.stores_romm_bios_here))
                }
                if (RommFirmwareState.working == null) RommFirmwareState.problemOf(chosen.id)?.let { BrowseNote(it) }
            }
        }
        if (icons) ChipRow { pick(); narrow() }
        else {
            ChipRow { pick() }
            ChipRow { narrow() }
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
    RommSort.SIZE -> R.string.stores_romm_sort_size
}

@Composable
private fun BrowseNote(text: String) =
    Text(text, fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))

@Composable
internal fun RommLoadMore(icons: Boolean) {
    val ctx = LocalContext.current
    val count = RommBrowseState.items.size
    LaunchedEffect(Unit) { RommBrowseState.more(ctx) }
    if (RommBrowseState.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
    else if (RommBrowseState.failed) Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        BrowseChip(stringResource(R.string.stores_dl_retry), false, RommIcons.Retry.takeIf { icons }) { RommBrowseState.more(ctx, retry = true) }
    }
    else if (!RommBrowseState.ended && count > 0) Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        BrowseChip(stringResource(R.string.stores_itch_load_more), false, RommIcons.More.takeIf { icons }) { RommBrowseState.more(ctx) }
    }
}
