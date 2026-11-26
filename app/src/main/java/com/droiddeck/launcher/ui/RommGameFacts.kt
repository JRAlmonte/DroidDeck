package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.GameFacts
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.romm.RommCatalog
import com.droiddeck.launcher.stores.romm.RommDetail

private const val MOST = 6

internal class Fact(val label: String, val value: String, val icon: ImageVector? = null)

@Composable
private fun duration(seconds: Long): String =
    GameFacts.hours(seconds.toInt())?.let { stringResource(R.string.stores_hltb_hours, it) } ?: stringResource(R.string.stores_hltb_minutes, maxOf(1, (seconds / 60).toInt()))

private fun day(seconds: Long): String =
    if (seconds <= 0) "" else GameFacts.releaseLabel(java.time.Instant.ofEpochSecond(seconds).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()).orEmpty()

@Composable
internal fun rommFacts(item: CatalogItem, icons: Boolean, detail: RommDetail? = null): List<Fact> {
    fun listed(key: String) = RommCatalog.list(item, key).take(MOST).joinToString(", ")
    fun some(values: List<String>) = values.take(MOST).joinToString(", ")
    val credited = setOf(item.developer.lowercase(), item.publisher.lowercase())
    val rating = RommCatalog.rating(item)?.let {
        if (detail != null && detail.ratingCount > 0) stringResource(R.string.romm_fact_rating_votes, it, detail.ratingCount) else stringResource(R.string.romm_fact_rating_value, it)
    }.orEmpty()
    return listOf(
        Triple(R.string.romm_fact_rating, RommIcons.Rating, rating),
        Triple(R.string.romm_fact_play_time, RommIcons.PlayTime, detail?.playedMs?.takeIf { it >= 60_000 }?.let { duration(it / 1000) }.orEmpty()),
        Triple(R.string.romm_fact_last_played, RommIcons.Played, detail?.lastPlayed?.takeIf { it > 0 }?.let { android.text.format.DateUtils.getRelativeTimeSpanString(it * 1000).toString() }.orEmpty()),
        Triple(R.string.romm_fact_genres, RommIcons.Genre, listed(RommCatalog.GENRES)),
        Triple(R.string.romm_fact_themes, RommIcons.Themes, some(detail?.themes.orEmpty())),
        Triple(R.string.romm_fact_perspectives, RommIcons.Perspective, some(detail?.perspectives.orEmpty())),
        Triple(R.string.romm_fact_franchise, RommIcons.Franchise, listed(RommCatalog.FRANCHISES)),
        Triple(R.string.romm_fact_companies, RommIcons.Companies, RommCatalog.list(item, RommCatalog.COMPANIES).filter { it.lowercase() !in credited }.take(MOST).joinToString(", ")),
        Triple(R.string.romm_fact_alt_names, RommIcons.AltNames, some(detail?.alternativeNames.orEmpty().filter { !it.equals(item.title, ignoreCase = true) })),
        Triple(R.string.romm_fact_regions, RommIcons.Region, listed(RommCatalog.REGIONS)),
        Triple(R.string.romm_fact_languages, RommIcons.Language, listed(RommCatalog.LANGUAGES)),
        Triple(R.string.romm_fact_revision, RommIcons.Revision, item.extra[RommCatalog.REVISION].orEmpty()),
        Triple(R.string.romm_fact_verified, RommIcons.Verified, some(detail?.verified.orEmpty())),
        Triple(R.string.romm_fact_age, RommIcons.Age, listed(RommCatalog.AGE_RATINGS)),
        Triple(R.string.romm_fact_players, RommIcons.Players, item.extra[RommCatalog.PLAYERS].orEmpty()),
        Triple(R.string.romm_fact_modes, RommIcons.Modes, listed(RommCatalog.GAME_MODES)),
        Triple(R.string.romm_fact_collections, RommIcons.Collection, some(detail?.collections.orEmpty().map { it.name })),
        Triple(R.string.romm_fact_added, RommIcons.Added, day(detail?.added ?: 0)),
        Triple(R.string.romm_fact_size, RommIcons.Size, if (item.sizeBytes > 0L) formatBytes(item.sizeBytes) else ""),
    ).filter { it.third.isNotBlank() }.map { Fact(stringResource(it.first), it.third, it.second.takeIf { icons }) }
}
