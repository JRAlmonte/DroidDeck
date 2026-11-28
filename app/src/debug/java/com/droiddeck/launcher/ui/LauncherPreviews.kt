package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.SecondScreenMode

/** The launcher as a whole: rail and page, opened on each tab through FrontEndState.navigate. */
@Composable
private fun Launcher(tab: String, games: List<com.droiddeck.launcher.frontend.Library.SteamGame> = sampleGames, ready: Boolean = true) =
    PreviewHost { FrontEndScreen(sampleState(games = games, navigate = tab, ready = ready), sampleActions) }

@DroidDeckPreviews @Composable private fun SteamTabPreview() = Launcher("steam")
@DroidDeckPreviews @Composable private fun SteamEmptyPreview() = Launcher("steam", games = emptyList())
@DroidDeckPreviews @Composable private fun LibraryPreview() = Launcher("games")
@DroidDeckPreviews @Composable private fun LibraryEmptyPreview() = Launcher("games", games = emptyList())
@DroidDeckPreviews @Composable private fun StoresPreview() = Launcher("stores")
@DroidDeckPreviews @Composable private fun DownloadsPreview() = Launcher("downloads")
@DroidDeckPreviews @Composable private fun SettingsPreview() = Launcher("setup")
@DroidDeckPreviews @Composable private fun RuntimeMissingPreview() = Launcher("setup", ready = false)

/** The launcher over a stand-in wallpaper, as the frosted background shows it. */
@Composable
private fun FrostedLauncher(tab: String) = DroidDeckTheme(frosted = true) {
    val wallpaper = Color(0xFF7A4BC2)
    val veil = frostVeil(LocalPalette.current, wallpaper)
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFE2725B), wallpaper, Color(0xFF2A9D8F)))).background(veil)) {
        CompositionLocalProvider(LocalFrost provides veil) {
            FrontEndScreen(sampleState(navigate = tab), sampleActions)
        }
    }
}

@DroidDeckPreviews @Composable private fun FrostedSteamTabPreview() = FrostedLauncher("steam")
@DroidDeckPreviews @Composable private fun FrostedLibraryPreview() = FrostedLauncher("games")

/** The in-game menu on one page, as a session shows it. */
@Composable
private fun SessionMenu(page: SessionDrawerPage, steam: Boolean = true, desktop: Boolean = false) = PreviewHost {
    SessionDrawer(true, page, controllerActive = true, onPageChange = {}, a = sampleDrawerActions(steam, desktop))
}

@DroidDeckPreviews @Composable private fun SessionMenuControllerPreview() = SessionMenu(SessionDrawerPage.CONTROLLER)
@DroidDeckPreviews @Composable private fun SessionMenuDisplayPreview() = SessionMenu(SessionDrawerPage.DISPLAY)
@DroidDeckPreviews @Composable private fun SessionMenuHudPreview() = SessionMenu(SessionDrawerPage.HUD)
@DroidDeckPreviews @Composable private fun SessionMenuSessionPreview() = SessionMenu(SessionDrawerPage.SESSION)
@DroidDeckPreviews @Composable private fun SessionMenuDesktopPreview() = SessionMenu(SessionDrawerPage.CONTROLLER, steam = false, desktop = true)

/** The in-game menu docked on the bottom screen, with the screen picker in its header. */
@Composable
private fun DockedMenu(page: SessionDrawerPage) = PreviewHost {
    SessionDrawer(true, page, controllerActive = false, onPageChange = {},
        a = sampleDrawerActions(secondScreen = SecondScreenMode.KEYBOARD_TRACKPAD), docked = true)
}

/** The bottom screen's bar on a panel: the panel's name is the screen picker. */
@Composable
private fun BottomPanelBar() = PreviewHost {
    val modes = secondScreenModes(false, deckPad = false, dashboard = true, desktop = false)
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        BottomScreenBar(BottomBarModel("Keyboard + trackpad", {}, {}, {}, {},
            listOf(BarExtra("Keyboard", icon = R.drawable.ic_bottom_keyboard) {}),
            picker = ScreenPicker(modes, SecondScreenMode.KEYBOARD_TRACKPAD, {}, onMenu = {})))
    }
}

@DroidDeckPreviews @Composable private fun SessionMenuDockedPreview() = DockedMenu(SessionDrawerPage.CONTROLLER)
@DroidDeckPreviews @Composable private fun SessionMenuDockedDisplayPreview() = DockedMenu(SessionDrawerPage.DISPLAY)
@DroidDeckPreviews @Composable private fun BottomPanelBarPreview() = BottomPanelBar()

/** A RomM game's action row before it is installed: [slug] and [file] as the server names them. */
@Composable
private fun RommGameRow(slug: String, file: String, sha1: String) = PreviewHost {
    val extra = with(com.droiddeck.launcher.stores.romm.RommCatalog) { mapOf(PLATFORM to slug, FILE to file, SINGLE to "1", SHA1 to sha1) }
    val item = com.droiddeck.launcher.stores.CatalogItem(com.droiddeck.launcher.stores.Store.ROMM, "1", "Sample Game", null, owned = true, sizeBytes = 32L shl 20, extra = extra)
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        RommGameActions("1", item, sampleState(), sampleActions, Modifier)
    }
}

@DroidDeckPreviews @Composable private fun RommGameNothingPlaysPreview() = RommGameRow("switch", "Sample Game.nsp", "cd".repeat(20))

/** The Details rows of a RomM game's page, once with words and once with icons. */
@Composable
private fun RommGameFactRows() = PreviewHost {
    val extra = with(com.droiddeck.launcher.stores.romm.RommCatalog) {
        mapOf(
            PLATFORM to "snes", FILE to "Sample Game (USA) (Beta) (Unl).sfc", SINGLE to "1", RATING to "84.5", GENRES to "[\"Platform\",\"Adventure\"]",
            REGIONS to "[\"USA\"]", LANGUAGES to "[\"En\"]", REVISION to "1", TAGS to "[\"Beta\",\"Unl\"]", PLAYERS to "1-2",
        )
    }
    val item = com.droiddeck.launcher.stores.CatalogItem(com.droiddeck.launcher.stores.Store.ROMM, "1", "Sample Game", null, owned = true, sizeBytes = 2L shl 20, extra = extra)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        for (icons in listOf(false, true)) rommFacts(item, icons).forEach { FactRow(it.label, it.value, labelWidth = 96.dp, icon = it.icon) }
    }
}

@DroidDeckPreviews @Composable private fun RommGameFactRowsPreview() = RommGameFactRows()

private val sampleRyujinx = com.droiddeck.launcher.frontend.EmulatorLinks.Link(
    "ryujinx", "Ryujinx", "Switch", listOf("switch"), setOf("nsp", "xci"), "flatpak:io.github.ryubing.Ryujinx",
    com.droiddeck.launcher.runtime.FlatpakManager.LAUNCHER, listOf("io.github.ryubing.Ryujinx"), listOf("--fullscreen"), null,
)

private val sampleEmulators = listOf(
    com.droiddeck.launcher.frontend.Library.Emulator("retroarch", "RetroArch", "many systems", "/usr/bin/retroarch", true, emptyList()),
    com.droiddeck.launcher.frontend.Library.Emulator("duckstation", "DuckStation", "PS1", "/opt/appimages/duckstation.AppImage", false, emptyList()),
    com.droiddeck.launcher.frontend.Library.Emulator(
        "ryujinx", "Ryujinx", "Switch", sampleRyujinx.program, true, emptyList(), sampleRyujinx.baseArgs, appKey = sampleRyujinx.appKey,
    ),
)

/** An inventory with one of each state: ready, core missing, not installed, BIOS needed and nothing to play it. */
@Composable
private fun sampleInventory(): com.droiddeck.launcher.frontend.EmulatorInventory.Result {
    val cache = androidx.compose.ui.platform.LocalContext.current.cacheDir
    return androidx.compose.runtime.remember {
        val rootfs = java.io.File(cache, "preview-rootfs")
        listOf("nestopia", "snes9x", "gambatte", "sameboy", "genesis_plus_gx", "mednafen_pce_fast").forEach {
            java.io.File(rootfs, "usr/lib/aarch64-linux-gnu/libretro/${it}_libretro.so").apply { parentFile?.mkdirs(); writeText("") }
        }
        com.droiddeck.launcher.frontend.EmulatorInventory.build(com.droiddeck.launcher.frontend.EmulatorInventory.Input(
            packages = setOf("emulators"), rootfs = rootfs, romsRoot = null,
            games = mapOf("snes" to 42, "gb" to 12, "gba" to 9, "psx" to 6, "nds" to 5, "switch" to 3, "segacd" to 2),
            platforms = listOf(
                com.droiddeck.launcher.frontend.EmulatorInventory.Platform("snes", "Super Nintendo", 310),
                com.droiddeck.launcher.frontend.EmulatorInventory.Platform("n64", "Nintendo 64", 88),
                com.droiddeck.launcher.frontend.EmulatorInventory.Platform("saturn", "Sega Saturn", 40),
            ),
            links = listOf(sampleRyujinx), choices = emptyMap(),
        ))
    }
}

@Composable
private fun EmulatorSettings() = PreviewHost {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        EmulatorsPanel(sampleInventory(), sampleState(emulators = sampleEmulators), sampleActions)
    }
}

@Composable
private fun EmulatorSetup() = PreviewHost {
    EmulatorSetupPage(sampleInventory(), sampleState(emulators = sampleEmulators), sampleActions) {}
}

/** The DS row's own page from the setup page: melonDS, the controller, and its screen layout, sizes and swap. */
@Composable
private fun EmulatorSystemDs() = PreviewHost {
    EmulatorSetupPage(sampleInventory(), sampleState(emulators = sampleEmulators), sampleActions, opened = "ds") {}
}

/** Arranging the 3DS screens over the faded on-screen buttons, started from a large top screen. */
@Composable
private fun ScreenArrange() = PreviewHost {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val buttons = androidx.compose.runtime.remember {
        com.droiddeck.launcher.input.InputLibraries.read(ctx).layout("game", com.droiddeck.launcher.input.InputRole.PRIMARY)
    }
    ScreenArrangeContent(com.droiddeck.launcher.frontend.ScreenRects.preset(2400, 1080, com.droiddeck.launcher.frontend.ScreenRects.Preset.LARGE_TOP),
        buttons, onDismiss = {}, onSave = {})
}

@DroidDeckPreviews @Composable private fun EmulatorSettingsPreview() = EmulatorSettings()
@DroidDeckPreviews @Composable private fun ScreenArrangePreview() = ScreenArrange()
@DroidDeckPreviews @Composable private fun EmulatorSystemDsPreview() = EmulatorSystemDs()
@DroidDeckPreviews @Composable private fun EmulatorSetupPreview() = EmulatorSetup()

/** A browse bar's dropdown open on [rows] values with one of them picked. */
@Composable
private fun BrowseMenu(rows: Int) = PreviewHost {
    androidx.compose.foundation.layout.Box(Modifier.padding(24.dp)) {
        ValueChip("Company", true) {}
        AnchoredListMenu(true, onDismiss = {}, count = rows, start = rows - 3, minWidth = 160.dp) { index, first ->
            MenuItem(if (index == 0) "Any company" else "Company number $index", index == rows - 3, focusRequester = first) {}
        }
    }
}

/** The RomM store's Sort menu, open on the order by when the server last changed a game. */
@Composable
private fun RommSortMenu() = PreviewHost {
    androidx.compose.foundation.layout.Box(Modifier.padding(24.dp)) {
        ValueChip("Sort: Recently updated", true) {}
        AnchoredMenu(true, onDismiss = {}, compact = true, minWidth = 160.dp) { first ->
            RommSortRows(com.droiddeck.launcher.stores.romm.RommSort.UPDATED, first) {}
        }
    }
}

/** The RomM store's browse bar while Installed is chosen, with words and with icons: the tag filter stays beside it. */
@Composable
private fun RommBrowseBarInstalled() = PreviewHost {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.remember {
        com.droiddeck.launcher.stores.romm.RommBrowseState.narrow(ctx, com.droiddeck.launcher.stores.romm.RommFilter(installed = true, tags = setOf("Beta", "Unl")))
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.droiddeck.launcher.stores.romm.RommBrowseState.reset() } }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        RommBrowseBar(icons = false)
        RommBrowseBar(icons = true)
    }
}

/** The far end of the RomM store's browse bar, with words and with icons: English games, and More filters counting a language and a tag chosen in it. */
@Composable
private fun RommBrowseBarServerFilters() = PreviewHost {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.remember {
        com.droiddeck.launcher.stores.romm.RommBrowseState.narrow(ctx, com.droiddeck.launcher.stores.romm.RommFilter(english = true, languages = setOf("English"), tags = setOf("Beta")))
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.droiddeck.launcher.stores.romm.RommBrowseState.reset() } }
    Column(Modifier.fillMaxSize().padding(24.dp).clipToBounds()) {
        for ((icons, width) in listOf(false to 1700.dp, true to 1100.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.wrapContentWidth(androidx.compose.ui.Alignment.End, unbounded = true).width(width)) { RommBrowseBar(icons) }
        }
    }
}

private val sampleExtras = listOf(
    com.droiddeck.launcher.stores.romm.RommFile("21", "Sample Game [UPD] (v1.1).nsp", 310L shl 20, category = "update"),
    com.droiddeck.launcher.stores.romm.RommFile("22", "dlc/Season Pass.nsp", 1200L shl 20, category = "dlc"),
    com.droiddeck.launcher.stores.romm.RommFile("23", "manual/Booklet.pdf", 18L shl 20, category = "manual"),
)

/** A RomM game's action row before it is installed, with the extra files the server keeps with it and one of them chosen. */
@Composable
private fun RommGameExtraFiles() = PreviewHost {
    val details = com.droiddeck.launcher.stores.romm.RommDetails
    androidx.compose.runtime.remember { details.offered["1"] = sampleExtras; details.chosen["1"] = setOf("22") }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { details.offered.remove("1"); details.chosen.remove("1") } }
    val extra = with(com.droiddeck.launcher.stores.romm.RommCatalog) { mapOf(PLATFORM to "switch", FILE to "Sample Game [0100]", SINGLE to "0") }
    val item = com.droiddeck.launcher.stores.CatalogItem(com.droiddeck.launcher.stores.Store.ROMM, "1", "Sample Game", null, owned = true, sizeBytes = 4L shl 30, extra = extra)
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        RommGameActions("1", item, sampleState(), sampleActions, Modifier)
    }
}

private val sampleGameFiles = listOf(
    com.droiddeck.launcher.stores.romm.RommFile("11", "Sample Game.xci", 7800L shl 20, category = "game"),
    com.droiddeck.launcher.stores.romm.RommFile("12", "Sample Game DLC.nsp", 110L shl 10, category = "game"),
    com.droiddeck.launcher.stores.romm.RommFile("13", "Sample Game Updates/Sample Game Upd v1.1.nsp", 310L shl 20),
    com.droiddeck.launcher.stores.romm.RommFile("14", "Sample Game Updates/Sample Game Upd v1.2.nsp", 1200L shl 20),
)

/** A RomM game's action row before it is installed, for a game of several files of its own with two of them left on the server. */
@Composable
private fun RommGameOwnFiles() = PreviewHost {
    val details = com.droiddeck.launcher.stores.romm.RommDetails
    androidx.compose.runtime.remember { details.gameFiles["2"] = sampleGameFiles; details.leftOut["2"] = setOf(sampleGameFiles[0].path, sampleGameFiles[3].path) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { details.gameFiles.remove("2"); details.leftOut.remove("2") } }
    val extra = with(com.droiddeck.launcher.stores.romm.RommCatalog) { mapOf(PLATFORM to "switch", FILE to "Sample Game", SINGLE to "0") }
    val item = com.droiddeck.launcher.stores.CatalogItem(com.droiddeck.launcher.stores.Store.ROMM, "2", "Sample Game", null, owned = true, sizeBytes = 9L shl 30, extra = extra)
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        RommGameActions("2", item, sampleState(), sampleActions, Modifier)
    }
}

/** The game-files menu of a RomM game's page before it is installed, open with the base game and the large update unticked. */
@Composable
private fun RommGameFilesMenu() = PreviewHost {
    androidx.compose.foundation.layout.Box(Modifier.padding(24.dp)) {
        val kept = sampleGameFiles.filter { it.id == "12" || it.id == "13" }
        ValueChip(rommGameFilesLabel(kept.size, sampleGameFiles.size, kept.sumOf { it.size }), true) {}
        AnchoredMenu(true, onDismiss = {}, compact = true, minWidth = 160.dp) { first ->
            sampleGameFiles.forEachIndexed { index, file -> RommGameFileRow(file, file in kept, first.takeIf { index == 0 }) {} }
        }
    }
}

/**
 * The game-files menu of an installed RomM game: the file Play starts, a file it has that was chosen once and asks before
 * it is deleted, another it has, and one left on the server.
 */
@Composable
private fun RommInstalledGameFilesMenu() = PreviewHost {
    androidx.compose.foundation.layout.Box(Modifier.padding(24.dp)) {
        ValueChip(rommGameFilesLabel(3, sampleGameFiles.size), true) {}
        AnchoredMenu(true, onDismiss = {}, compact = true, minWidth = 160.dp) { first ->
            sampleGameFiles.forEachIndexed { index, file ->
                val there = file.id != "14"
                val asking = androidx.compose.ui.res.stringResource(R.string.stores_romm_extra_remove_confirm).takeIf { file.id == "13" }
                RommGameFileRow(file, there, first.takeIf { index == 0 }, rommKeptFile(file, there, sampleGameFiles[0].path, 3), asking) {}
            }
        }
    }
}

/** The extra-files menu of a RomM game's page, open with one file chosen. */
@Composable
private fun RommExtraFilesMenu() = PreviewHost {
    androidx.compose.foundation.layout.Box(Modifier.padding(24.dp)) {
        ValueChip("Extra files: 1 of 3", true) {}
        AnchoredMenu(true, onDismiss = {}, compact = true, minWidth = 160.dp) { first ->
            sampleExtras.forEachIndexed { index, file -> RommExtraFileRow(file, file.id == "22", first.takeIf { index == 0 }) {} }
        }
    }
}

/** What an installed RomM game's action row adds for extra files: the menu, the note for a game kept as one file, and a failed fetch. */
@Composable
private fun RommInstalledExtraFilesRow() = PreviewHost {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        RommInstalledExtraFiles(sampleExtras, setOf("21"), add = {}, remove = {})
        ActionChip(androidx.compose.ui.res.stringResource(R.string.stores_romm_extras_reinstall), ok = false)
        ActionChip(androidx.compose.ui.res.stringResource(R.string.stores_romm_extras_failed, "RomM download failed (404)"), ok = false)
    }
}

/** The extra-files menu of an installed RomM game: one file it has, and one it has that was chosen once and asks before it is deleted. */
@Composable
private fun RommInstalledExtraFilesMenu() = PreviewHost {
    androidx.compose.foundation.layout.Box(Modifier.padding(24.dp)) {
        ValueChip("Extra files: 2 of 3", true) {}
        AnchoredMenu(true, onDismiss = {}, compact = true, minWidth = 160.dp) { first ->
            sampleExtras.forEachIndexed { index, file ->
                val note = androidx.compose.ui.res.stringResource(R.string.stores_romm_extra_remove_confirm).takeIf { file.id == "22" }
                RommExtraFileRow(file, file.id != "23", first.takeIf { index == 0 }, note) {}
            }
        }
    }
}

/** The versions menu of a RomM game's page: the version shown, and another the account marked as the main one on RomM. */
@Composable
private fun RommVersionMenu() = PreviewHost {
    androidx.compose.foundation.layout.Box(Modifier.padding(24.dp)) {
        ValueChip("(USA)", true) {}
        AnchoredMenu(true, onDismiss = {}, compact = true, minWidth = 160.dp) { first ->
            RommVersionRow("(USA)", picked = true, main = false, first = first) {}
            RommVersionRow("(Europe) (Rev 1)", picked = false, main = true, first = null) {}
            RommVersionRow("(Japan)", picked = false, main = false, first = null) {}
        }
    }
}

@DroidDeckPreviews @Composable private fun RommSortMenuPreview() = RommSortMenu()
@DroidDeckPreviews @Composable private fun RommVersionMenuPreview() = RommVersionMenu()
@DroidDeckPreviews @Composable private fun RommInstalledExtraFilesRowPreview() = RommInstalledExtraFilesRow()
@DroidDeckPreviews @Composable private fun RommInstalledExtraFilesMenuPreview() = RommInstalledExtraFilesMenu()
@DroidDeckPreviews @Composable private fun RommGameExtraFilesPreview() = RommGameExtraFiles()
@DroidDeckPreviews @Composable private fun RommExtraFilesMenuPreview() = RommExtraFilesMenu()
@DroidDeckPreviews @Composable private fun RommGameOwnFilesPreview() = RommGameOwnFiles()
@DroidDeckPreviews @Composable private fun RommGameFilesMenuPreview() = RommGameFilesMenu()
@DroidDeckPreviews @Composable private fun RommInstalledGameFilesMenuPreview() = RommInstalledGameFilesMenu()
@DroidDeckPreviews @Composable private fun RommBrowseBarInstalledPreview() = RommBrowseBarInstalled()
@DroidDeckPreviews @Composable private fun RommBrowseBarServerFiltersPreview() = RommBrowseBarServerFilters()
@DroidDeckPreviews @Composable private fun RommRegionMenuPreview() = RommRegionMenu()
private val samplePlatforms = listOf(
    com.droiddeck.launcher.stores.romm.RommPlatform("1", "switch", "Nintendo Switch", 212, family = "Nintendo", generation = 8, bytes = 1_900L shl 30),
    com.droiddeck.launcher.stores.romm.RommPlatform("2", "psx", "PlayStation", 148, family = "Sony", generation = 5, bytes = 96L shl 30),
    com.droiddeck.launcher.stores.romm.RommPlatform("3", "gba", "Game Boy Advance", 310, family = "Nintendo", generation = 6, bytes = 2_400L shl 20),
    com.droiddeck.launcher.stores.romm.RommPlatform("4", "snes", "Super Nintendo Entertainment System", 420, bytes = 760L shl 20),
    com.droiddeck.launcher.stores.romm.RommPlatform("5", "dos", "DOS", 12),
)

/** The RomM store's region menu, each region with its flag and USA ticked; a region that is no place keeps the flag's room empty. */
@Composable
private fun RommRegionMenu() = PreviewHost {
    Column(Modifier.width(260.dp).padding(16.dp)) {
        RommValueRow(androidx.compose.ui.res.stringResource(R.string.stores_romm_filter_region), false, null, flagged = true) {}
        for (region in listOf("USA", "Europe", "Japan", "World", "England", "Korea", "Brazil", "Unlicensed")) {
            RommValueRow(region, region == "USA", com.droiddeck.launcher.stores.romm.RommRegions.flag(region), flagged = true) {}
        }
    }
}

/** The RomM store's browse bar with three platforms chosen, with words and with icons. */
@Composable
private fun RommBrowseBarPlatforms() = PreviewHost {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.remember { com.droiddeck.launcher.stores.romm.RommBrowseState.choose(ctx, samplePlatforms.take(3)) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.droiddeck.launcher.stores.romm.RommBrowseState.reset() } }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        RommBrowseBar(icons = false)
        RommBrowseBar(icons = true)
    }
}

/**
 * The RomM store's platform menu with two platforms ticked, and under each name what the server says of the platform: all
 * of it, its size alone, and nothing. With [icons] each row leaves room for the platform's picture.
 */
@Composable
private fun RommPlatformMenu(icons: Boolean = false) = PreviewHost {
    androidx.compose.foundation.layout.Box(Modifier.padding(24.dp)) {
        ValueChip("PlayStation, Game Boy Advance", true) {}
        AnchoredListMenu(true, onDismiss = {}, count = samplePlatforms.size + 1, start = 2, minWidth = 160.dp) { index, first ->
            RommPlatformRow(index, samplePlatforms, samplePlatforms.subList(1, 3), icons = icons, first = first) {}
        }
    }
}

/** The saves and states RomM holds for a game, as its Sync tab lists them: a save in a slot, a state with its picture, one about to be deleted, and one a sign-in may not delete. */
@Composable
private fun RommAssetList() = PreviewHost {
    val shot = androidx.compose.runtime.remember {
        android.graphics.Bitmap.createBitmap(64, 40, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2E6F95.toInt()) }.asImageBitmap()
    }
    fun asset(kind: String, id: String, name: String, slot: String, size: Long) =
        com.droiddeck.launcher.stores.romm.RommAsset(kind, id, name, "retroarch", slot, size, 0, "")
    Column(Modifier.padding(24.dp).width(420.dp)) {
        SectionLabel(androidx.compose.ui.res.stringResource(R.string.romm_assets))
        RommAssetRow(asset("saves", "1", "Sample Game.srm", "2", 32L shl 10), null, armed = false) {}
        RommAssetRow(asset("states", "2", "Sample Game.state1", "", 3L shl 20), shot, armed = false) {}
        RommAssetRow(asset("states", "3", "Sample Game.state.auto", "", 3L shl 20), shot, armed = true) {}
        RommAssetRow(asset("saves", "4", "Sample Game (web).srm", "", 8L shl 10), null, armed = false, enabled = false) {}
    }
}

/** A game's Sync tab where the server refused the sign-in: the list of saves says so, and the notes and collections are not offered. */
@Composable
private fun RommSyncRefused() = PreviewHost {
    val again = androidx.compose.ui.res.stringResource(R.string.romm_sync_no_access)
    Column(Modifier.padding(24.dp).width(420.dp)) {
        SectionLabel(androidx.compose.ui.res.stringResource(R.string.romm_assets))
        RommAssetsRefused()
        SectionLabel(androidx.compose.ui.res.stringResource(R.string.romm_fact_collections))
        MenuItem("Couch co-op", checked = true, enabled = false, detail = again) {}
        MenuItem("To try", checked = false, enabled = false) {}
        SectionLabel(androidx.compose.ui.res.stringResource(R.string.romm_notes))
        MenuItem("Stuck in the well", checked = false, enabled = false, detail = "The key is behind the waterfall.") {}
        MenuItem(androidx.compose.ui.res.stringResource(R.string.romm_note_add), checked = false, enabled = false, detail = again) {}
    }
}

/** The notes of a RomM game's Sync tab: one of the account's own that is public, opened with one press, and its Delete chosen once. */
@Composable
private fun RommNoteList() = PreviewHost {
    Column(Modifier.padding(24.dp).width(420.dp)) {
        SectionLabel(androidx.compose.ui.res.stringResource(R.string.romm_notes))
        MenuItem("Stuck in the well", checked = false, detail = "The key is behind the waterfall. · " + androidx.compose.ui.res.stringResource(R.string.romm_note_shared)) {}
        RommNoteActions(public = true, armed = true, edit = {}, share = {}, delete = {})
        MenuItem("Speedrun route", checked = false, enabled = false, detail = "Skip the second dungeon. · another player") {}
        MenuItem(androidx.compose.ui.res.stringResource(R.string.romm_note_add), checked = false) {}
    }
}

/** "More like this" on a RomM game's page: each game with the reason the server gave for it, a long one, and a game with none. */
@Composable
private fun RommSimilarGames() = PreviewHost {
    fun game(id: String, title: String) = com.droiddeck.launcher.stores.CatalogItem(
        com.droiddeck.launcher.stores.Store.ROMM, id, title, null, owned = true,
        extra = with(com.droiddeck.launcher.stores.romm.RommCatalog) { mapOf(PLATFORM to "snes", FILE to "$title.sfc", SINGLE to "1", RATING to "84.5") },
    )
    fun why(facet: String, value: String) = com.droiddeck.launcher.stores.romm.RommReason(facet, value)
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        RommSimilarShelf(
            listOf(game("1", "Sample Quest II"), game("2", "Other Quest"), game("3", "Sample Kart"), game("4", "Far Away"), game("5", "Unexplained")),
            mapOf("1" to why("franchise", "Sample Quest"), "2" to why("developer", "A Studio With A Rather Long Name"), "3" to why("igdb", "similar"), "4" to why("decade", "1990")),
            icons = false, onOpen = {},
        )
    }
}

/** The account's RetroAchievements line of the RomM profile strip, at the width the strip gives it beside the search and with room to spare. */
@Composable
private fun RommProgressLines() = PreviewHost {
    Column(Modifier.padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(220.dp)) { RommProgressLine(com.droiddeck.launcher.stores.romm.RommProgress(137, 2412, 13870)) }
        RommProgressLine(com.droiddeck.launcher.stores.romm.RommProgress(1, 3, 12))
    }
}

/** The size line of the RomM profile strip at the width the strip gives it beside the search and with room to spare: with saves and states, with too many to fit, and with none. */
@Composable
private fun RommSizeLines() = PreviewHost {
    val totals = listOf(
        com.droiddeck.launcher.stores.romm.RommTotals(12345, 48, 1_500_000_000_000L, 312, 45),
        com.droiddeck.launcher.stores.romm.RommTotals(12345, 48, 1_500_000_000_000L, 1, 0),
        com.droiddeck.launcher.stores.romm.RommTotals(12345, 48, 1_500_000_000_000L, 1_234_567, 2_345_678),
        com.droiddeck.launcher.stores.romm.RommTotals(12345, 48, 1_500_000_000_000L),
    )
    Column(Modifier.padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        for (held in totals) {
            Column(Modifier.width(220.dp)) { RommSizeLine(held) }
            RommSizeLine(held)
        }
    }
}

private val sampleCollections = listOf(
    com.droiddeck.launcher.stores.romm.RommCollection(com.droiddeck.launcher.stores.romm.RommCollection.Kind.REGULAR, "1", "Favourites", 12, "5", favorite = true),
    com.droiddeck.launcher.stores.romm.RommCollection(com.droiddeck.launcher.stores.romm.RommCollection.Kind.REGULAR, "2", "Couch co-op", 7, "5"),
    com.droiddeck.launcher.stores.romm.RommCollection(com.droiddeck.launcher.stores.romm.RommCollection.Kind.REGULAR, "3", "To try", 0, "5"),
    com.droiddeck.launcher.stores.romm.RommCollection(com.droiddeck.launcher.stores.romm.RommCollection.Kind.SMART, "1", "Short games", 31, "5", about = "Under five hours"),
)

/** What the RomM store's browse bar adds for a chosen collection of the account's own: with words, with Delete chosen once, and with icons. */
@Composable
private fun RommCollectionChipRows() = PreviewHost {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        for ((icons, armed) in listOf(false to false, false to true, true to false)) ChipRow {
            ValueChip("Couch co-op", false) {}
            RommCollectionChips(icons, armed, rename = {}, delete = {})
        }
    }
}

/** The RomM store's collection menu: an empty collection of the account's own, and the row that makes a new one. */
@Composable
private fun RommCollectionMenu() = PreviewHost {
    androidx.compose.foundation.layout.Box(Modifier.padding(24.dp)) {
        ValueChip("Couch co-op", true) {}
        AnchoredListMenu(true, onDismiss = {}, count = sampleCollections.size + 2, start = 2, minWidth = 160.dp) { index, first ->
            RommCollectionRow(index, sampleCollections, sampleCollections[1], first, create = {}) {}
        }
    }
}

@DroidDeckPreviews @Composable private fun RommCollectionChipRowsPreview() = RommCollectionChipRows()
@DroidDeckPreviews @Composable private fun RommCollectionMenuPreview() = RommCollectionMenu()
@DroidDeckPreviews @Composable private fun RommNoteListPreview() = RommNoteList()
@DroidDeckPreviews @Composable private fun RommAssetListPreview() = RommAssetList()
@DroidDeckPreviews @Composable private fun RommProgressLinesPreview() = RommProgressLines()
@DroidDeckPreviews @Composable private fun RommSizeLinesPreview() = RommSizeLines()
@DroidDeckPreviews @Composable private fun RommSimilarGamesPreview() = RommSimilarGames()
@DroidDeckPreviews @Composable private fun RommSyncRefusedPreview() = RommSyncRefused()
@DroidDeckPreviews @Composable private fun RommBrowseBarPlatformsPreview() = RommBrowseBarPlatforms()
@DroidDeckPreviews @Composable private fun RommPlatformMenuPreview() = RommPlatformMenu()
@DroidDeckPreviews @Composable private fun RommPlatformMenuIconsPreview() = RommPlatformMenu(icons = true)
@DroidDeckPreviews @Composable private fun BrowseMenuShortPreview() = BrowseMenu(8)
@DroidDeckPreviews @Composable private fun BrowseMenuLongPreview() = BrowseMenu(300)

/** The Library and Stores tabs read store accounts, which load on a background thread. */
private val storesLoaded = { com.droiddeck.launcher.stores.StoresState.accountsLoaded }

/** Every preview above, for the render test. Add a case when adding a preview. */
internal val previewCases: List<PreviewCase> = listOf(
    PreviewCase("steam-tab") { Launcher("steam") },
    PreviewCase("library", storesLoaded) { Launcher("games") },
    PreviewCase("library-empty", storesLoaded) { Launcher("games", games = emptyList()) },
    PreviewCase("stores", storesLoaded) { Launcher("stores") },
    PreviewCase("downloads") { Launcher("downloads") },
    PreviewCase("settings") { Launcher("setup") },
    PreviewCase("runtime-missing") { Launcher("setup", ready = false) },
    PreviewCase("frosted-steam-tab") { FrostedLauncher("steam") },
    PreviewCase("frosted-library", storesLoaded) { FrostedLauncher("games") },
    PreviewCase("session-menu-controller") { SessionMenu(SessionDrawerPage.CONTROLLER) },
    PreviewCase("session-menu-display") { SessionMenu(SessionDrawerPage.DISPLAY) },
    PreviewCase("session-menu-hud") { SessionMenu(SessionDrawerPage.HUD) },
    PreviewCase("session-menu-session") { SessionMenu(SessionDrawerPage.SESSION) },
    PreviewCase("session-menu-desktop") { SessionMenu(SessionDrawerPage.CONTROLLER, steam = false, desktop = true) },
    PreviewCase("session-menu-docked") { DockedMenu(SessionDrawerPage.CONTROLLER) },
    PreviewCase("session-menu-docked-display") { DockedMenu(SessionDrawerPage.DISPLAY) },
    PreviewCase("bottom-panel-bar") { BottomPanelBar() },
    PreviewCase("romm-game-nothing-plays") { RommGameRow("switch", "Sample Game.nsp", "cd".repeat(20)) },
    PreviewCase("romm-game-facts") { RommGameFactRows() },
    PreviewCase("romm-sort-menu") { RommSortMenu() },
    PreviewCase("romm-browse-installed") { RommBrowseBarInstalled() },
    PreviewCase("romm-browse-server-filters") { RommBrowseBarServerFilters() },
    PreviewCase("romm-region-menu") { RommRegionMenu() },
    PreviewCase("romm-browse-platforms") { RommBrowseBarPlatforms() },
    PreviewCase("romm-platform-menu") { RommPlatformMenu() },
    PreviewCase("romm-platform-menu-icons") { RommPlatformMenu(icons = true) },
    PreviewCase("romm-sync-assets") { RommAssetList() },
    PreviewCase("romm-progress") { RommProgressLines() },
    PreviewCase("romm-server-size") { RommSizeLines() },
    PreviewCase("romm-similar") { RommSimilarGames() },
    PreviewCase("romm-sync-refused") { RommSyncRefused() },
    PreviewCase("romm-sync-notes") { RommNoteList() },
    PreviewCase("romm-collection-chips") { RommCollectionChipRows() },
    PreviewCase("romm-collection-menu") { RommCollectionMenu() },
    PreviewCase("romm-game-extra-files") { RommGameExtraFiles() },
    PreviewCase("romm-extra-files-menu") { RommExtraFilesMenu() },
    PreviewCase("romm-game-files") { RommGameOwnFiles() },
    PreviewCase("romm-game-files-menu") { RommGameFilesMenu() },
    PreviewCase("romm-installed-game-files-menu") { RommInstalledGameFilesMenu() },
    PreviewCase("romm-version-menu") { RommVersionMenu() },
    PreviewCase("romm-installed-extra-files") { RommInstalledExtraFilesRow() },
    PreviewCase("romm-installed-extra-files-menu") { RommInstalledExtraFilesMenu() },
    PreviewCase("emulator-settings") { EmulatorSettings() },
    PreviewCase("emulator-setup") { EmulatorSetup() },
    PreviewCase("emulator-system-ds") { EmulatorSystemDs() },
    PreviewCase("screen-arrange") { ScreenArrange() },
    PreviewCase("browse-menu-short") { BrowseMenu(8) },
    PreviewCase("browse-menu-long") { BrowseMenu(300) },
)
