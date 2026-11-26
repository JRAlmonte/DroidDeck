package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            games = mapOf("snes" to 42, "gb" to 12, "gba" to 9, "psx" to 6, "switch" to 3, "segacd" to 2),
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

@DroidDeckPreviews @Composable private fun EmulatorSettingsPreview() = EmulatorSettings()
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
    PreviewCase("session-menu-controller") { SessionMenu(SessionDrawerPage.CONTROLLER) },
    PreviewCase("session-menu-display") { SessionMenu(SessionDrawerPage.DISPLAY) },
    PreviewCase("session-menu-hud") { SessionMenu(SessionDrawerPage.HUD) },
    PreviewCase("session-menu-session") { SessionMenu(SessionDrawerPage.SESSION) },
    PreviewCase("session-menu-desktop") { SessionMenu(SessionDrawerPage.CONTROLLER, steam = false, desktop = true) },
    PreviewCase("session-menu-docked") { DockedMenu(SessionDrawerPage.CONTROLLER) },
    PreviewCase("session-menu-docked-display") { DockedMenu(SessionDrawerPage.DISPLAY) },
    PreviewCase("bottom-panel-bar") { BottomPanelBar() },
    PreviewCase("romm-game-nothing-plays") { RommGameRow("switch", "Sample Game.nsp", "cd".repeat(20)) },
    PreviewCase("emulator-settings") { EmulatorSettings() },
    PreviewCase("emulator-setup") { EmulatorSetup() },
    PreviewCase("browse-menu-short") { BrowseMenu(8) },
    PreviewCase("browse-menu-long") { BrowseMenu(300) },
)
