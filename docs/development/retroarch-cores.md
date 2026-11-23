# RetroArch cores

RetroArch is one emulator for many systems, so a game needs a core as well as a path. `RetroArchCores`
(`frontend/RetroArchCores.kt`) is the one table that says which ROMs folder belongs to which system, which
file types it lists, which core plays it and which libretro-thumbnails collection has its box art.

| System | Folders | Core |
|---|---|---|
| NES | `nes`, `famicom` | `nestopia` |
| SNES | `snes`, `sfc` | `snes9x` |
| Game Boy | `gb` | `gambatte` |
| Game Boy Color | `gbc` | `gambatte` |
| Game Boy Advance | `gba` | `mgba` |
| Genesis / Mega Drive | `genesis`, `megadrive`, `md` | `genesis_plus_gx` |
| Master System | `mastersystem`, `sms` | `genesis_plus_gx` |
| Game Gear | `gamegear`, `gg` | `genesis_plus_gx` |
| SG-1000 | `sg1000` | `genesis_plus_gx` |
| PC Engine / TurboGrafx-16 | `pcengine`, `pce`, `tg16` | `mednafen_pce_fast` |
| Sega CD / Mega-CD | `segacd`, `megacd` | `genesis_plus_gx` |
| PC Engine CD / TurboGrafx-CD | `pcenginecd`, `pcecd`, `tgcd` | `mednafen_pce_fast` |
| Virtual Boy | `virtualboy`, `vb` | `mednafen_vb` |
| WonderSwan | `wonderswan`, `wswan` | `mednafen_wswan` |
| WonderSwan Color | `wonderswancolor`, `wsc` | `mednafen_wswan` |
| Nintendo 64 | `n64` | `mupen64plus_next` (fetched) |
| Dreamcast | `dc`, `dreamcast` | `flycast` (fetched) |
| Arcade | `arcade`, `fbneo` | `fbneo` (fetched) |
| Neo Geo | `neogeo`, `neogeoaes`, `neogeomvs` | `fbneo` (fetched) |
| Atari 2600 | `atari2600` | `stella` (fetched) |
| Atari 5200 | `atari5200` | `a5200` (fetched) |
| Atari 7800 | `atari7800` | `prosystem` (fetched) |
| Jaguar | `jaguar`, `atarijaguar` | `virtualjaguar` (fetched) |

## Fetched cores

Ubuntu 26.04 packages none of the fetched cores for arm64 (it has no N64 or Dreamcast emulator at all, and
only standalone MAME, Stella, Atari800 and Virtual Jaguar). `droiddeck-emulators` takes them from libretro's
build server, `buildbot.libretro.com/nightly/linux/aarch64/latest`, one zip per core, and carries on without
a core that fails. That address is not pinned and publishes no checksums: the build server keeps only the
latest build of each core, the same one RetroArch's own core downloader takes, so pinning would mean
hosting copies of the cores ourselves. `DROIDDECK_CORES_URL` points the
script elsewhere; empty fetches nothing.

A fetched system is listed only while its core's file is in the runtime's libretro folder
(`RetroArchCores.coresDir`). `droiddeck-emulators install` fetches every core; `droiddeck-emulators cores`
fetches only the missing ones and touches nothing else. A RomM game of a system whose core is missing shows
"Get the <system> core" (`CoreFetch`, `DesktopCatalog.fetchCores`) when the Emulators package is installed,
and "Install RetroArch" when it is not.

The file types are the ones each core's `.info` file lists, without archives for the disc and cartridge
cores, so RomM unpacks a zipped Dreamcast or Atari game. Arcade and Neo Geo sets stay zipped: FinalBurn Neo
reads the set by its file name and wants the set its own version expects.

BIOS files: Flycast, a5200, ProSystem and Virtual Jaguar start without any. `RetroArchCores.firmwareFor`
names what the other systems need, any one file of each list: Neo Geo needs `neogeo.zip`, which FinalBurn Neo
reads from beside the games; Sega CD needs `bios_CD_U.bin`, `bios_CD_E.bin` or `bios_CD_J.bin` and PC Engine CD
needs `syscard3.pce`, both read from RetroArch's system folder, which `droiddeck-pad-defaults` pins to
`~/.config/retroarch/system`. They are copyrighted, so the app ships none: a RomM game of such a folder says
"Needs your own <file> in <place>" until one is there, or offers to get the platform's BIOS files from the
user's RomM server when it holds some (see `romm-store.md`). The scan leaves the arcade BIOS sets
(`RetroArchCores.firmwareFiles`) out of the game list.

Only cores that `droiddeck-emulators` installs are listed. PS1 and DS stay with DuckStation and melonDS.
Sega CD and PC Engine CD play in the cores of their cartridge systems, from `cue`, `chd` and `m3u`. Saturn and
Neo Geo CD are left out: no installed core plays them.

## Scan

`Library.emulators` lists RetroArch games only from these folders (three levels deep, like the other
emulators), each with its own file types, zips included: the cores load a zipped cartridge. RetroArch does not
scan the ROMs root or unknown folders, where a `.zip` or `.bin` says nothing about its system. The folder
names also count as system folders for the other emulators, so a Genesis `.bin` is no longer offered to
DuckStation.

## Launch

`Library.launchArgs("retroarch", path)` gives `-f -L <core> <path>`, with the core as a full path under
`/usr/lib/aarch64-linux-gnu/libretro`, where the Ubuntu libretro packages put them. A path outside the system
folders is handed over bare, as before, so "Open RetroArch" and its own file browser are unchanged.

## Adding a system

Add a row to the table in `RetroArchCores` and its core to `droiddeck-emulators`: the package to `cores`, or
the core's name to `fetched` with `optional = true` on the row. No folder name may
appear twice (`RetroArchCoresTest`).

## Tests

`RetroArchCoresTest`, `LibraryRomScanTest`, `RommPlatformsTest`, `tools/tests/test_emulators_install.py`.
