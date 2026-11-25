# RetroArch cores

RetroArch is one emulator for many systems, so a game needs a core as well as a path. `RetroArchCores`
(`frontend/RetroArchCores.kt`) is the one table that says which ROMs folder belongs to which system, which
file types it lists and which core plays it. RetroArch games get no box art from the web: a game RomM
installed shows the cover its server sent.

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

The file types are the ones each core's `.info` file lists.

BIOS files: `RetroArchCores.firmwareFor` names what a system needs, any one file of each list: Sega CD needs
`bios_CD_U.bin`, `bios_CD_E.bin` or `bios_CD_J.bin` and PC Engine CD needs `syscard3.pce`, both read from
RetroArch's system folder, which `droiddeck-pad-defaults` pins to `~/.config/retroarch/system`. They are
copyrighted, so the app ships none: a RomM game of such a folder says "Needs your own <file> in <place>" until
one is there, or offers to get the platform's BIOS files from the user's RomM server when it holds some (see
`romm-store.md`).

Only cores that `droiddeck-emulators` installs from Ubuntu's packages are listed; nothing is downloaded from
libretro's build server. Ubuntu 26.04 packages no arm64 core for Nintendo 64, Dreamcast, Arcade, Neo Geo, the
Atari consoles or Jaguar, so those systems are not listed. PS1 and DS stay with DuckStation and melonDS.
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

Add a row to the table in `RetroArchCores` and its core's package to `cores` in `droiddeck-emulators`. No
folder name may appear twice (`RetroArchCoresTest`).

## Tests

`RetroArchCoresTest`, `LibraryRomScanTest`, `RommPlatformsTest`, `CoverArtKeyTest` (two systems with one file name keep
their own covers), `tools/tests/test_emulators_install.py`, `tools/tests/test_pad_defaults_saves.py`.
