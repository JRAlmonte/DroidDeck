package com.droiddeck.launcher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** The icons the RomM store draws in place of words while `SessionPrefs.rommIcons` is on. */
internal object RommIcons {
    val StarTint = Color(0xFFFFC94D)

    val Rating: ImageVector get() = Icons.Filled.Star
    val Installed: ImageVector get() = Icons.Filled.Check
    val Favourites: ImageVector get() = Icons.Filled.Favorite
    val Backlog: ImageVector get() = Icons.Filled.Bookmark
    val NowPlaying: ImageVector get() = Icons.Outlined.SportsEsports
    val Played: ImageVector get() = Icons.Outlined.History
    val Platform: ImageVector get() = Icons.Outlined.VideogameAsset
    val Collection: ImageVector get() = CollectionsIcon
    val Sort: ImageVector get() = Icons.AutoMirrored.Filled.Sort
    val Genre: ImageVector get() = Icons.Outlined.Category
    val Region: ImageVector get() = Icons.Outlined.Public
    val Language: ImageVector get() = Icons.Outlined.Translate
    val Bios: ImageVector get() = Icons.Outlined.Memory
    val Retry: ImageVector get() = Icons.Filled.Refresh
    val More: ImageVector get() = Icons.Filled.ExpandMore
    val Added: ImageVector get() = Icons.Outlined.NewReleases
    val Franchise: ImageVector get() = Icons.Outlined.AutoStories
    val Companies: ImageVector get() = Icons.Outlined.Business
    val Age: ImageVector get() = Icons.Outlined.Shield
    val Players: ImageVector get() = Icons.Outlined.Group
    val Modes: ImageVector get() = Icons.Outlined.Tune
    val Size: ImageVector get() = Icons.Outlined.SdStorage
    val About: ImageVector get() = Icons.Outlined.Info
    val Controller: ImageVector get() = Icons.Outlined.SportsEsports
    val SyncTab: ImageVector get() = Icons.Outlined.Sync
}
