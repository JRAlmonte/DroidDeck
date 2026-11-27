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
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

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
    val PlayTime: ImageVector get() = Icons.Outlined.Timer
    val Themes: ImageVector get() = Icons.Outlined.Palette
    val Perspective: ImageVector get() = Icons.Outlined.Visibility
    val AltNames: ImageVector get() = Icons.AutoMirrored.Outlined.Label
    val Revision: ImageVector get() = Icons.Outlined.Tag
    val Tags: ImageVector get() = Icons.Outlined.Sell
    val Verified: ImageVector get() = Icons.Outlined.Verified
    val Achievements: ImageVector get() = Icons.Outlined.EmojiEvents
    val Random: ImageVector get() = Icons.Outlined.Casino
    val Status: ImageVector get() = Icons.Outlined.Flag
    val Adopt: ImageVector get() = Icons.Outlined.Link
    val Manual: ImageVector get() = Icons.AutoMirrored.Outlined.MenuBook
    val States: ImageVector get() = Icons.Outlined.Save
    val Matched: ImageVector get() = Icons.Outlined.Fingerprint
    val Duplicate: ImageVector get() = Icons.Outlined.ContentCopy
    val Playable: ImageVector get() = Icons.Outlined.OpenInBrowser
    val Missing: ImageVector get() = Icons.Outlined.CloudOff
}
