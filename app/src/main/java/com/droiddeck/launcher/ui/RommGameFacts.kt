package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.romm.RommCatalog

private const val MOST = 6

internal class Fact(val label: String, val value: String, val icon: ImageVector? = null)

@Composable
internal fun rommFacts(item: CatalogItem, icons: Boolean): List<Fact> {
    fun listed(key: String) = RommCatalog.list(item, key).take(MOST).joinToString(", ")
    val credited = setOf(item.developer.lowercase(), item.publisher.lowercase())
    return listOf(
        Triple(R.string.romm_fact_rating, RommIcons.Rating, RommCatalog.rating(item)?.let { stringResource(R.string.romm_fact_rating_value, it) }.orEmpty()),
        Triple(R.string.romm_fact_genres, RommIcons.Genre, listed(RommCatalog.GENRES)),
        Triple(R.string.romm_fact_franchise, RommIcons.Franchise, listed(RommCatalog.FRANCHISES)),
        Triple(R.string.romm_fact_companies, RommIcons.Companies, RommCatalog.list(item, RommCatalog.COMPANIES).filter { it.lowercase() !in credited }.take(MOST).joinToString(", ")),
        Triple(R.string.romm_fact_regions, RommIcons.Region, listed(RommCatalog.REGIONS)),
        Triple(R.string.romm_fact_languages, RommIcons.Language, listed(RommCatalog.LANGUAGES)),
        Triple(R.string.romm_fact_age, RommIcons.Age, listed(RommCatalog.AGE_RATINGS)),
        Triple(R.string.romm_fact_players, RommIcons.Players, item.extra[RommCatalog.PLAYERS].orEmpty()),
        Triple(R.string.romm_fact_modes, RommIcons.Modes, listed(RommCatalog.GAME_MODES)),
        Triple(R.string.romm_fact_size, RommIcons.Size, if (item.sizeBytes > 0L) formatBytes(item.sizeBytes) else ""),
    ).filter { it.third.isNotBlank() }.map { Fact(stringResource(it.first), it.third, it.second.takeIf { icons }) }
}
