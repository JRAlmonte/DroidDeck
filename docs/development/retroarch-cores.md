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
| Virtual Boy | `virtualboy`, `vb` | `mednafen_vb` |
| WonderSwan | `wonderswan`, `wswan` | `mednafen_wswan` |
| WonderSwan Color | `wonderswancolor`, `wsc` | `mednafen_wswan` |
| Nintendo 64 | `n64` | `mupen64plus_next` (optional) |

Ubuntu has no N64 emulator built for arm64, so `droiddeck-emulators` takes the `mupen64plus_next` core from
libretro's build server and carries on without it when that fails. An optional system is listed only while
its core's file is in the runtime's libretro folder (`RetroArchCores.coresDir`).

Only cores that `droiddeck-emulators` installs are listed. PS1 and DS stay with DuckStation and melonDS. CD
systems are left out: they need BIOS files the app does not manage.

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

Add a row to the table in `RetroArchCores` and its core package to `droiddeck-emulators`. No folder name may
appear twice (`RetroArchCoresTest`).

## Tests

`RetroArchCoresTest`, `LibraryRomScanTest`.
