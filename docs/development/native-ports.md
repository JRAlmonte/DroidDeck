# Native ports

A native port is a decompilation or recompilation project that plays one specific game as a native program,
given the user's own ROM. When the ROMs folder holds the exact dump a port needs, the Desktop page shows a
card for that port. It installs the port's own release build into the runtime and starts it with the same run
mode that starts emulators and user apps. DroidDeck ships no ROM and no port, and keeps no list of ports: the
user brings them, in one of three ways that all use the same entry format.

## The entry format

A list is a JSON file holding `{"ports": [ ... ]}`. `ports/ports.json` in this repository is an example:
[Zelda 64: Recompiled](https://github.com/Zelda64Recomp/Zelda64Recomp) (Majora's Mask, US N64 release) and the
games of [Gen1Recomp](https://github.com/bryanthaboi/gen1recomp), one entry for each ROM it accepts; the app does not read it unless the user adds this repository as a list. An entry names the GitHub
repository (`repo`), the release asset to take (`asset`, a pattern), the ROM system folders and file types
(`folders`, `types`), the size and SHA-1 of the ROM (`romSize`, `romSha1`), and optionally the ROM's form
(`form`, `n64` for a dump whose byte order varies), the program inside the download (`entry`), the name the
port keeps its copy of the ROM under (`stagedRom`), and marker files to create beside the program
(`markers`). Without `entry`, the install takes the ARM64 executable nearest the top of the download
(`PortInstaller.findProgram`) and remembers it. Without `stagedRom`, no ROM copy is made and the port asks
for the ROM itself; its page says where the file is.

`PortCatalog.parse` reads a list and `PortCatalog.write` writes one that reads back the same. An entry that
is incomplete, or whose paths could leave the port's own folder, is left out. A list is read up to 512 KB
and 200 entries. Whatever a list says, a port is only ever downloaded from a GitHub release on github.com
and checked against the hash GitHub publishes for it. A download GitHub lists no hash for is refused
(`PortInstaller.refusal`).

## Where ports come from

The Port sources page (Desktop page, Files tab, and found by Settings search) shows the user's sources and
lets them add and remove them (`PortSources`, kept in the `port_sources` preferences). `Ports.all` is the
ports of the lists in the order added, then the single ports; of two entries with one id the first is kept.
Every port says where it is from on its card and page ("Native port from owner/name", with a note that
DroidDeck has not checked it). Removing a source removes its installed ports and keeps their saves.

A repository. "Add a list" takes a GitHub repository holding its own `ports/ports.json`, typed as
`owner/name`, `owner/name@branch` or a github.com address. It is fetched from the repository's default
branch unless a branch is given, at most every six hours, and the last good copy is kept in
`filesDir/ports/sources/`. Up to 50 lists.

A file. The page saves every port the user has, from lists and single ports alike, to one file in the list
format (`PortSources.export`), and reads such a file back (`PortSources.import`), adding each entry as a
single port. An entry whose id the user already has is left out and counted. So a backup, a list in a
repository and a file someone shared are the same thing. A saved file holds the entries of the user's lists,
not the fact that they follow those lists. Up to 200 single ports.

From scratch. A game's page (the Desktop ROM page, or an installed RomM game) has an "Add a port for this
game" button. The dialog searches GitHub repositories for the game's title with "recomp", "decomp" or "port"
in the name or description (`PortSearch`), and takes `owner/name` or a github.com address typed in the same
field as one repository to check. Each repository found is checked (`PortSearch.check`):

- Its releases must hold a download named for ARM64 that is not for another system
  (`PortSources.offering`), with a hash from GitHub. Without one it is shown and cannot be picked.
- Its README, and the small text files in its tree whose names say checksum, hash, sha1, md5 or baserom, are
  read for the ROM's SHA-1 or MD5 (`PortRoms.facts`, taken over the big-endian form for an N64 dump). It is
  marked "ROM hash found" or "Not confirmed". Recompilation projects that keep the ROM's hash only in their
  source, in another form, come out as not confirmed even for the right ROM.

Picking a confirmed repository adds it. An unconfirmed one needs a second press on "Add anyway", under a
warning that it may be a port of another game. A repository that one of the user's lists gives for another
ROM is refused (`PortSources.otherGame`). The ROM gives the system folder, file type, size, SHA-1 and form;
with several downloads the user picks one, and the entry keeps a pattern for its name that still fits the
next version (`PortSources.assetPattern`). The program and the staged name are optional details. A download
that is a zip, a tar.gz, a tar.xz or a bare program is taken.

GitHub is read without an account. Measured on 2026-11-22 from `api.github.com/rate_limit`: 60 requests an
hour to the API and 10 searches a minute, per address; `raw.githubusercontent.com`, where the README and
hash files come from, counts against neither. A search costs one search request and, for each of up to six
repositories, one request for releases and one for the tree when the README did not settle it. Results are
kept for ten minutes. When GitHub answers 403 or 429 with nothing remaining, the dialog says so and gives
the time the limit lifts (`PortSearch.limited`); what was found before the limit stays on screen.

## Finding the ROM

`PortRoms.scan` looks in `ROMs/<system folder>` (`n64`, three levels deep) for files with the port's file
types and exact size, and takes the first whose SHA-1 matches. N64 dumps come in three byte orders (`.z64`,
`.v64`, `.n64`), told apart by the first four bytes (`N64Rom`); the hash is taken over the big-endian form,
so any of the three matches. Hashes are remembered in `filesDir/ports/hashes.tsv` by path, size and
modification time, so a file is read once.

## Install, play, remove

`PortInstaller.install` reads the repository's releases (`GithubReleases`, shared with user apps), downloads
the asset with `Downloader` checked against the SHA-256 digest GitHub publishes, and unpacks it into
`/opt/droiddeck-ports/<id>` in the runtime through a temporary folder. It then writes the entry's marker
files and the ROM under the staged name. For Zelda 64: Recompiled that is `portable.txt`, which makes the
port keep its settings and saves in its own folder, and the ROM in big-endian form as `mm.n64.us.1.0.z64`,
the name and place the port's runtime (`librecomp/src/recomp.cpp`, `GameEntry::stored_filename`) looks for a
ROM it has already accepted, so the game starts without asking for one. It needs Vulkan 1.2. The release tag is kept in `.droiddeck-port`; a port counts as installed
while that file and the program exist.

A download that is an AppImage cannot mount itself in the runtime, so `AppImageManager.unpackInto` unpacks it into `app/` in the
port's folder, as it does for the user's AppImages, and the port's program is `app/AppRun`. An install made before that, whose
program is the AppImage file itself, counts as not installed.

An entry may carry launch settings: `env`, names and values set for the program, and `args`, given to it in order. `{rom}`
in either is the ROM's path in the runtime (`/root/ROMs/...`). Gen1Recomp takes its game from `POKEPORT_GAME` and imports the
ROM named by `POKEPORT_IMPORT_ROM` on the first start (`main.lua` of its 0.4.8 release), so it starts in the game with no file
chooser. A port with no settings and no staged name starts plainly and asks for the ROM itself. Every port starts through
`.droiddeck-start-<id>`, a script `PortsState.launch` writes in its folder: the exports, then `droiddeck-appimage-run <folder>`
or the program, with the arguments.

The user types the settings of a port they add themselves in the dialog's details (arguments on one line, variables as
`NAME=value`, quotes keeping a value with spaces together; `PortLaunch`), and changes them later with Launch settings on
the port's page (`PortSources.setLaunch`). A port of a list shows its settings and cannot be changed there.

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
runtime; until then an N64 game offers a port only.

## RomM

`RommPlatforms.target` falls back to a port the user has when no emulator scans a platform's folder, so with
an N64 port added, N64 games on a RomM server can be installed into `ROMs/n64`. The install record names the port (`port:<id>`) where it
would name an emulator. The game's page then hashes the downloaded file: the right dump gets the port's
Install and Play buttons, any other N64 game says it is not the ROM the port needs.

RomM reports a SHA-1 for each file. When a plain big-endian dump (`.z64`) has a hash other than the port's,
`RommPlatforms.refusedBy` says so on the game's page instead of offering Install. Byte-swapped dumps and
archives cannot be judged from that hash, so they are still downloaded and checked afterwards.

## Not handled yet

Ports that only publish x86-64 Linux builds (Ship of Harkinian, 2Ship2Harkinian, Starship; they would run
through FEX), ports with a separate asset extraction step, building a port from source, update checks, save
sync and mods. A game page offers one port beside the emulator: a second port for a ROM that already has one
is not offered. Ports that share one repository (Gen1Recomp's games) are each installed in a folder of their own.

## Tests

`N64RomTest`, `PortRomsTest`, `PortInstallerTest`, `PortCatalogTest`, `PortLaunchTest`, `PortSourcesTest`, `PortSearchTest`, `RunWithTest`, and the port case in `RommPlatformsTest`.
