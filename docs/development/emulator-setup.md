# Emulator setup

Everything about emulators is on one page, **Set up emulators** (`EmulatorSetupPage`): per system, what plays it,
whether that is installed, what is still missing, and the system's own controller and screen settings. It opens from
**Emulators** on the RomM store's server card, from Settings › Emulators (`droiddeck://settings/emulators`, which shows
only how many systems are ready and the button), from the Desktop page and from a RomM game nothing plays.

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

One game can have an emulator of its own. Its page (the ROM's page on the Desktop side, the Launch tab of a RomM
game) has **Emulator for this game** whenever more than one installed emulator or core takes its file. The pick
is stored by the game's path below the ROMs folder (`game:<path>`) and `Library.gamePick` reads it: the scan
lists the game under the picked emulator, `RetroArchCores.forGuestPath` uses a picked core, and
`RommPlatforms.runner` puts it before everything else. A pick whose emulator is gone or does not take the file
is ignored.

Second cores (`RetroArchCores.alternatives`) are the ones the Emulators package installs and no system uses by
default: `bsnes_mercury_performance` (SNES), `sameboy` (Game Boy, Game Boy Color), `desmume` (DS) and
`mednafen_psx` (PS1, which needs `scph5501.bin`, `scph5500.bin` or `scph5502.bin`). One is offered once its file
is on disk.

## A system's own page

**Controls & screen** on a system's row opens that system's page (`SystemSettingsPage`): the emulator in use, with the
chooser when more than one can play it, the controller profile (`InputQuickSettings`), and the emulator's own settings
DroidDeck can set (`RomSettingsGroup`, the table `RomControls.Setting`). All of them are kept under the system's key
(`RomScope.system`, `system:<first folder>`), which a game's own choice on its page comes before. The same groups show on
an installed RomM game's Launch tab for the game and its system.

The screen settings, written into the emulator's own file by `droiddeck-pad-defaults` before the game starts and put
back at the next start that asks for none (as the controller ones are):

| System | Emulator | Setting | Where | Values |
|---|---|---|---|---|
| DS | melonDS | Screen layout, sizes, swap | `melonDS.toml`, `[Instance0.Window0]` `ScreenLayout`, `ScreenSizing`, `ScreenSwap` | natural, top above bottom, side by side, hybrid (0 to 3); even, bigger top, bigger bottom, top only, bottom only (0, 1, 2, 4, 5); swap |
| 3DS | Azahar | Screen layout, swap | `azahar-emu/qt-config.ini` (native or Flatpak), `[Layout]` `layout_option`, `swap_screen`, each with its `\default` | top above bottom, one screen, large top, side by side, hybrid (0, 1, 2, 3, 5); swap |
| PS1 | DuckStation | Aspect ratio | `settings.ini`, `[Display]` `AspectRatio` | 4:3, 16:9; back to `Auto (Game Native)` |
| RetroArch systems | RetroArch | Integer scaling | `retroarch.cfg`, `video_scale_integer` | on, off |

The values are those of melonDS's `ScreenLayout.h` and `Config.cpp`, Azahar's `LayoutOption` (`common/settings.h`, the
desktop numbering, where 4 is separate windows) and `QtConfig::ReadLayoutValues`, and DuckStation's `settings.cpp`.
Azahar is set only once it has written its own `qt-config.ini`, after its first start. A system whose emulator has none
of these says so on its page. Putting the DS bottom screen on a second display is not done here: melonDS draws both
screens in one window.

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
`game_dirs`, once (marker `.droiddeck-games`). The emulators from the Linux store get theirs from the tuning below.

## Paths and settings for the device

`droiddeck-pad-defaults` also writes, one time per settings file (marker `.droiddeck-tuned-<emulator>` beside it), each
emulator's game folder, BIOS folder, Vulkan and a rendering resolution picked for the chip. A value is written only where
the file has none or the emulator's own default, so what the user set in the emulator stays. Native files are changed
where their folder exists; a Flatpak's are written under `~/.var/app/<id>` once the app is installed, before its first
start. The chip comes from `DD_SOC_NAME` (`SocNames.plainName`), sorted by its model in `SOC_TIERS`: high is the
Snapdragon 8 Gen 3 and the 8 Elite family, mid the 8 Gen 1 to 8 Gen 2, the 8s parts, the 7+ Gen 3, the G3 Gen 3 and the
Q-7790, and low everything else, unknown chips included.

| Emulator | Paths | Renderer and resolution (high / mid / low) | Keys |
|---|---|---|---|
| DuckStation | already set at first start | 4x / 3x / 2x | `[GPU] ResolutionScale` |
| ARMSX2 | already set at first start | 2x / 1x / 1x | `[EmuCore/GS] upscale_multiplier` |
| Dolphin | already set at first start | 2x / 1x / 1x, shaders asynchronous with ubershaders / skipping drawing | `GFX.ini [Settings] InternalResolution`, `ShaderCompilationMode` 2 / 3 |
| PPSSPP | `ROMs/psp` as the browser's folder | Vulkan, 4x / 3x / 2x | `[General] CurrentDirectory`, `[Graphics] GraphicsBackend = 3 (VULKAN)`, `InternalResolution` |
| Azahar | `ROMs/3ds`, after its own INSTALLED and SYSTEM entries | Vulkan, asynchronous shaders, 3x / 2x / 1x | `[UI] Paths\gamedirs\N\…`, `[Renderer] graphics_api = 2`, `async_shader_compilation`, `resolution_factor` |
| Eden | `ROMs/switch`, after SDMC, UserNAND and SysNAND | asynchronous shaders, 1x / 1x / 0.75x | `[UI] Paths\gamedirs\N\…`, `[Renderer] use_asynchronous_shaders`, `resolution_setup` 3 / 3 / 2, each with `\default=false` |
| Citron, Sudachi, Suyu, yuzu | as Eden, only once they wrote their own `qt-config.ini` | as Eden | as Eden |
| Flycast | `ROMs/dreamcast`, its BIOS folder | Vulkan, 1440 / 960 / 720 lines | `[config] Dreamcast.ContentPath`, `Dreamcast.BiosPath`, `pvr.rend = 4`, `rend.Resolution` |
| RMG | `ROMs/n64` | (its video plugin's own) | `[Rosalie's Mupen GUI RomBrowser] Directory`, `Recursive` |
| xemu | `ROMs/xbox`, and the MCPX boot ROM, flash and hard disk image found in its BIOS folder | Vulkan, 2x / 1x / 1x | `[general] games_dir`, `show_welcome`, `[sys.files] bootrom_path`, `flashrom_path`, `hdd_path`, `[display] renderer`, `[display.quality] surface_scale` |
| RPCS3 | `ROMs/ps3` as `/games/` in a new `vfs.yml` | 150% / 100% / 100% | `config.yml Video: Resolution Scale` |

The keys were read off each emulator's source (PPSSPP `Core/Config.cpp`, DuckStation `src/core/settings.cpp`, PCSX2
`Pcsx2Config.cpp`, Dolphin `GraphicsSettings.cpp` and `VideoConfig.h`, Azahar `citra_qt/configuration/config.cpp` and
`common/settings.h`, Eden `qt_common/config/qt_config.cpp` and `common/settings.h`, Flycast `option.cpp`, RMG
`RMG-Core/Settings.cpp`, xemu `config_spec.yml`, RPCS3 `system_config.h` and `vfs_config.h`). Azahar and Eden list
their own entries only while the game list is empty, so those are written with the folder. Eden resets a setting
without its `\default=false` line. Ryujinx's `Config.json` is changed only once Ryujinx wrote a whole one (a missing
field loads as zero), and its resolution is left at 1x. melonDS keeps its software renderer: its OpenGL one is the
only one that scales, and was not tried here. Cemu has no resolution setting of its own (graphic packs set it).

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
- The screen settings and the tuning are covered by `tools/tests/test_pad_defaults_screen.py` and
  `test_pad_defaults_tuning.py` on the files written; no emulator was started with them here, so how each tier plays
  is still to be seen on a device.

## Tests

`EmulatorLinksTest`, `EmulatorInventoryTest`, `LibraryRomScanTest`, `RetroArchCoresTest`, `RommPlatformsTest`,
`RomControlsTest`, `SettingsMapTest`, `PreviewRenderTest` (`emulator-settings`, `emulator-setup`, `emulator-system-ds`),
`tools/tests/test_pad_defaults_ryujinx.py`, `test_pad_defaults_screen.py` and `test_pad_defaults_tuning.py`.
