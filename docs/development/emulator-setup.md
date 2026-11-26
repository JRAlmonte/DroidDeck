# Emulator setup

Settings › Emulators (`droiddeck://settings/emulators`) says, per system, what plays it, whether that is
installed and what is still missing. **Set up emulators** on that page, on the Desktop page and on a RomM game
nothing plays opens the same information as an ordered checklist.

## What plays a system

Three kinds of emulator can own a ROM folder, and `Library.romSystems()` lists them in this order:

1. **DroidDeck's own packages**: the table `Library.specs` (RPCS3, ARMSX2, Dolphin, DuckStation, melonDS, Cemu,
   PPSSPP). Installed means the package marker exists (`DesktopCatalog.installed`).
2. **RetroArch cores**: `RetroArchCores.systems`, one default core per system (see `retroarch-cores.md`).
3. **Linked apps**: an emulator the user installed as a Flatpak (the Linux store) or an AppImage.
   `EmulatorProfiles` is the table of those DroidDeck knows by name:

| Emulator | System | Folders | Flathub id | Arguments before the ROM |
|---|---|---|---|---|
| Ryujinx (Ryubing) | Switch | `switch` | `io.github.ryubing.Ryujinx` | `--fullscreen` |
| Eden, Citron, Sudachi, Suyu, yuzu | Switch | `switch` | `dev.eden_emu.eden` | `-f -g` |
| Azahar | 3DS | `3ds`, `n3ds`, `new-nintendo-3ds` | `org.azahar_emu.Azahar` | none |
| Flycast | Dreamcast | `dreamcast`, `dc` | `org.flycast.Flycast` | none |
| Rosalie's Mupen GUI | N64 | `n64` | `com.github.Rosalie241.RMG` | `--nogui -q` |
| simple64 | N64 | `n64` | `io.github.simple64.simple64` | `--nogui` |
| xemu | Xbox | `xbox` | `app.xemu.xemu` | `-dvd_path` |

The arguments are the ones ES-DE's Linux system list uses, and Ryujinx's `--fullscreen` is from its
`CommandLineState`. The file types are that list's, without archives and launcher files.

`EmulatorLinks.refresh` matches `UserApps.list` (imported AppImages and every installed Flatpak) against the
table by Flatpak id or app name, on the library scan and whenever the list of installed apps changes. A match
becomes an `EmulatorLinks.Link`, and from there the rest follows without special cases: `Library.emulators`
lists the link as an emulator with the games of its folders, `Library.launchRom` starts
`droiddeck-flatpak-run <id> <arguments> <rom>` (or `droiddeck-appimage-run <dir> ...`), and
`RommPlatforms.target` makes the platform installable and playable in the RomM store. A Flatpak link gets
`filesystems=/root/ROMs;` in `~/.local/share/flatpak/overrides/<id>`, so its sandbox can read the game.

A link can be turned off (**Don't use**), and any other installed app can be assigned to a system by hand
(**Use another app as an emulator**): it then gets the system's known file types and the game's path as its
only argument. Both are kept in the `emulators` preferences, which "Reset all settings" clears.

## Picking the emulator for a system

Where more than one emulator can play a system, its row has a chooser. The pick is stored per ROM folder
(`choice:<folder>` holds an emulator id, or `retroarch:<core>` for a core) and `Library.romSystems()` puts the
picked emulator first for that folder, which is all `RommPlatforms`, `RomScope` and the scan look at. A folder's
games are listed under the emulator that owns it only. A RomM game follows the pick too: `RommPlatforms.runner`
prefers it over the emulator recorded when the game was installed, and re-resolves a game whose emulator is
gone.

Second cores (`RetroArchCores.alternatives`) are the ones the Emulators package installs and no system uses by
default: `bsnes_mercury_performance` (SNES), `sameboy` (Game Boy, Game Boy Color), `desmume` (DS) and
`mednafen_psx` (PS1, which needs `scph5501.bin`, `scph5500.bin` or `scph5502.bin`). One is offered once its file
is on disk.

## Inventory

`EmulatorInventory.build` is a pure function of the installed packages, the runtime folder, the ROMs folder,
the game counts, the RomM server's platforms and the links and picks. It returns:

- one row per system: everything a package, a core or a profile covers, plus each folder in the ROMs folder and
  each platform the server holds that nothing covers. A row has the emulators that could play it, the one in
  use, the games on the device and on the server, and a state: `READY`, `NOT_INSTALLED` (package missing),
  `CORE_MISSING` (the `.so` is not in `/usr/lib/aarch64-linux-gnu/libretro`), `NEEDS_FILES` or `NOTHING`;
- one row per core: those the tables use, present or not, then any other core found on disk, named from its
  `.info` file.

`NEEDS_FILES` covers a RetroArch BIOS (`RetroArchCores.firmware`) and what a profile lists in `needs`. Ryujinx
needs `prod.keys` in its `system` folder and installed firmware. Keys found in `ROMs/switch/bios` (where
**Get BIOS files** puts what the user's RomM server holds) are copied with one button. Firmware has to be
installed from inside Ryujinx, so that row opens Ryujinx.

A system nothing plays offers **Get <emulator>**, which opens that emulator's page in the Linux store. Nothing
is installed without the user doing it there.

## Where the games are

DroidDeck starts a game by handing the emulator its path, so no emulator has to be told where the ROMs are for
that. An emulator's own game list is another matter: `droiddeck-pad-defaults` points DuckStation, ARMSX2, Dolphin
and the others at their system folder, and does the same for Ryujinx by putting `/root/ROMs/switch` in an empty
`game_dirs`, once (marker `.droiddeck-games`). The yuzu family's list is not set.

## Controller

`droiddeck-pad-defaults` binds Player 1 in the yuzu family's `qt-config.ini` as before, and now in Ryujinx's
`Config.json` (native and Flatpak): when Player 1 is still the stock keyboard it becomes the virtual pad. Ryujinx
names a pad by SDL's GUID, which holds a CRC-16 of the device name, so the id is worked out from the pad in
`/sys/class/input` and follows it if the pad's node changes; a controller the user set up is left alone.

## Limits

- RomM save sync does not cover linked emulators.
- Launching a linked emulator, the Flatpak permission and the Ryujinx pad id are covered by unit tests of the
  arguments and files written, and have not been run on a device.
- An app assigned by hand gets no arguments but the game's path.

## Tests

`EmulatorLinksTest`, `EmulatorInventoryTest`, `LibraryRomScanTest`, `RetroArchCoresTest`, `RommPlatformsTest`,
`SettingsMapTest`, `PreviewRenderTest` (`emulator-settings`, `emulator-setup`) and
`tools/tests/test_pad_defaults_ryujinx.py`.
