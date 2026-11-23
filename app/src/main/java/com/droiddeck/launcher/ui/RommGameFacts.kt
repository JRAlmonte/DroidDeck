package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.romm.RommCatalog

private const val MOST = 6

@Composable
internal fun rommFacts(item: CatalogItem): List<Pair<String, String>> {
    fun listed(key: String) = RommCatalog.list(item, key).take(MOST).joinToString(", ")
    val credited = setOf(item.developer.lowercase(), item.publisher.lowercase())
    return listOf(
        R.string.romm_fact_rating to RommCatalog.rating(item)?.let { stringResource(R.string.romm_fact_rating_value, it) }.orEmpty(),
        R.string.romm_fact_genres to listed(RommCatalog.GENRES),
        R.string.romm_fact_franchise to listed(RommCatalog.FRANCHISES),
        R.string.romm_fact_companies to RommCatalog.list(item, RommCatalog.COMPANIES).filter { it.lowercase() !in credited }.take(MOST).joinToString(", "),
        R.string.romm_fact_regions to listed(RommCatalog.REGIONS),
        R.string.romm_fact_languages to listed(RommCatalog.LANGUAGES),
        R.string.romm_fact_age to listed(RommCatalog.AGE_RATINGS),
        R.string.romm_fact_players to item.extra[RommCatalog.PLAYERS].orEmpty(),
        R.string.romm_fact_modes to listed(RommCatalog.GAME_MODES),
        R.string.romm_fact_size to if (item.sizeBytes > 0L) formatBytes(item.sizeBytes) else "",
    ).filter { it.second.isNotBlank() }.map { stringResource(it.first) to it.second }
}
