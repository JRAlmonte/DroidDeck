# Native ports

A native port is a decompilation or recompilation project that plays one specific game as a native program,
given the user's own ROM. When the ROMs folder holds the exact dump a known port needs, the Desktop page
shows a card for that port. It installs the port's own release build into the runtime and starts it with the
same run mode that starts emulators and user apps. DroidDeck ships no ROM and no port.

## Catalog

The ports are listed in `ports/ports.json` in this repository. An entry names the GitHub repository, the
release asset to take (a pattern), the program inside it, the ROM system folders and file types, the size and
SHA-1 of the ROM, the name the port keeps its copy of the ROM under, and marker files to create beside the
program. Adding a port is adding an entry to that file, with no new build, as long as the port publishes a
build the runtime can run and takes the ROM as a file.

The app has the same entries built in (`Ports.builtIn`), fetches the file from GitHub (`PortCatalog.URL`,
for now the `feat/romm-store` branch of the fork the work lives on; it has to name the main repository once
the branch is merged) at most
every six hours (`PortCatalog`), keeps the last good copy in `filesDir/ports/catalog.json`, and uses the
fetched entries first (`Ports.all`). An entry that is incomplete, or whose paths could leave the port's own
folder, is left out. A list is read up to 512 KB and 200 entries. Whatever the list says, a port is only ever
downloaded from a GitHub release on github.com and checked against the hash GitHub publishes for it. A
download GitHub lists no hash for is refused (`PortInstaller.refusal`), whichever list names it.

The one port so far is [Zelda 64: Recompiled](https://github.com/Zelda64Recomp/Zelda64Recomp) (Majora's Mask,
US N64 release), chosen because it publishes a Linux ARM64 build and reads the ROM directly with no asset
extraction step. It needs Vulkan 1.2.

## The user's own sources

The Port sources page (Desktop page, Files tab, and found by Settings search) lists where ports come from and lets the user add and remove
their own (`PortSources`, kept in the `port_sources` preferences). There are two kinds.

A list is a GitHub repository holding its own `ports/ports.json`, typed as `owner/name`, `owner/name@branch`
or a github.com address. It is fetched from the repository's default branch unless a branch is given, kept
in `filesDir/ports/sources/`, and read by the same `PortCatalog.parse` as our list, so every check above
applies.

A single port is added from a game's page (the Desktop ROM page, or an installed RomM game) with "Add a port
for this game". The user gives only the port's GitHub repository. A repository that is on a list already is refused for any
ROM but the one that port takes (`PortSources.otherGame`); for a repository no list names, the pairing is
the user's to get right. The ROM gives the system folder, file type,
size, SHA-1 and form (`PortRoms.facts`); the app reads the releases and takes the download named for ARM64
that is not for another system (`PortSources.offered`), asking which when there are several, and keeps a
pattern for its name that still fits the next version (`PortSources.assetPattern`). The program inside the
download and the name the port keeps its ROM under are optional. Without a program, the install takes the
ARM64 executable nearest the top of the download (`PortInstaller.findProgram`) and remembers it. Without a
staged name, no ROM copy is made and the port asks for the ROM itself; its page says where the file is. A
download that is a zip, a tar.gz, a tar.xz or a bare program is taken.

The user's ports come after ours in `Ports.all`, and one whose id is already taken is left out, so a user's
source never replaces a listed port. A port from a user's source says so on its card and page ("Native port
from owner/name", with a note that DroidDeck has not checked it). Removing a source removes its installed ports and keeps
their saves.

## Finding the ROM

`PortRoms.scan` looks in `ROMs/<system folder>` (`n64`, three levels deep) for files with the port's file
types and exact size, and takes the first whose SHA-1 matches. N64 dumps come in three byte orders (`.z64`,
`.v64`, `.n64`), told apart by the first four bytes (`N64Rom`); the hash is taken over the big-endian form,
so any of the three matches. Hashes are remembered in `filesDir/ports/hashes.tsv` by path, size and
modification time, so a file is read once.

## Install, play, remove

`PortInstaller.install` reads the repository's releases (`GithubReleases`, shared with user apps), downloads
the asset with `Downloader` checked against the SHA-256 digest GitHub publishes, and unpacks it (a zip
holding a tar.gz) into `/opt/droiddeck-ports/<id>` in the runtime through a temporary folder. It then writes
`portable.txt`, which makes the port keep its settings and saves in its own folder, and writes the ROM in
big-endian form as `mm.n64.us.1.0.z64`. That is the name and place the port's runtime
(`librecomp/src/recomp.cpp`, `GameEntry::stored_filename`) looks for a ROM it has already accepted, so the
game starts without asking for one. The release tag is kept in `.droiddeck-port`; a port counts as installed
while that file and the program exist.

Play starts `SessionActivity` in run mode with the program's path. If the port deleted its ROM copy, Play
writes it again from the ROMs folder first. Removing the port deletes everything in its folder except
`saves`, which the next install carries over. A reinstall moves the old folder aside before the new one
takes its place, so a failed swap leaves the old install and its saves as they were.

## Run with

When an emulator and a port can both run a ROM, the game's page (`RunWithActions`, on the Desktop ROM page
and on a RomM game) shows a Run with choice between them. `RunWith` keeps the choice per ROM path; with
nothing chosen, an installed port is used, else the emulator. With only one of the two available the page
shows that one's buttons and no choice.

N64 has an emulator only when the Emulators package fetched the `mupen64plus_next` libretro core (Ubuntu has no
N64 emulator built for arm64, so `droiddeck-emulators` takes the core from libretro's build server, and carries
on without it when that fails; see `retroarch-cores.md`). `RetroArchCores` lists N64 as a RetroArch system while that core's file is in the
runtime; until then Majora's Mask offers the port only.

## RomM

`RommPlatforms.target` falls back to a port when no emulator scans a platform's folder, so N64 games on a
RomM server can be installed into `ROMs/n64`. The install record names the port (`port:<id>`) where it
would name an emulator. The game's page then hashes the downloaded file: the right dump gets the port's
Install and Play buttons, any other N64 game says it is not the ROM the port needs.

RomM reports a SHA-1 for each file. When a plain big-endian dump (`.z64`) has a hash other than the port's,
`RommPlatforms.refusedBy` says so on the game's page instead of offering Install. Byte-swapped dumps and
archives cannot be judged from that hash, so they are still downloaded and checked afterwards.

## Not handled yet

Ports that only publish x86-64 Linux builds (Ship of Harkinian, 2Ship2Harkinian, Starship; they would run
through FEX), ports with a separate asset extraction step, building a port from source, update checks, save
sync and mods. A game page offers one port beside the emulator: a second port for a ROM that already has one
is not offered. A port published as an AppImage is unpacked as a bare program and has not been tried.

## Tests

`N64RomTest`, `PortRomsTest`, `PortInstallerTest`, `PortCatalogTest`, `PortSourcesTest`, `RunWithTest`, and the port case in `RommPlatformsTest`.
