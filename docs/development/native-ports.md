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
(`markers`), launch settings (`env`, `args`), the install it shares (`tool`) and where the program keeps its
saves (`saves`). Without `entry`, the install takes the
ARM64 executable nearest the top of the download, or without one the x86-64 executable (`PortInstaller.findProgram`), and records it in the install folder. Without `stagedRom`, no ROM copy is made and the port asks
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

From scratch. A game's page (the Desktop ROM page, or a RomM game, installed or not) has an "Add a port for this
game" button. The dialog searches GitHub repositories for the game's title with "recomp", "decomp" or "port"
in the name or description (`PortSearch`), and takes `owner/name` or a github.com address typed in the same
field as one repository to check. Each repository found is checked (`PortSearch.check`):

- Its releases must hold a download this device takes (`PortSources.offering`), with a hash from GitHub: one
  named for ARM64 that is not for another system, or, when the release has none, a Linux one for x86-64 PCs
  (named for Linux or an AppImage, for no other processor, and of a type the install unpacks). Without one it
  is shown and cannot be picked. A PC download is marked in the dialog: it runs through FEX.
- A `ports/ports.json` of its own is read. An entry there for this repository, the user's ROM and one of those downloads
  gives the port's settings: the row says "Settings from the project", the details are filled in from it, and adding
  takes that entry under an id of the user's (`PortSources.adopted`). It costs no API request.
- Otherwise its README, and the small text files in its tree whose names say checksum, hash, sha1, md5 or baserom, are
  read for the ROM's SHA-1 or MD5 (`PortRoms.facts`, taken over the big-endian form for an N64 dump). It is
  marked "ROM hash found" or "Not confirmed". Recompilation projects that keep the ROM's hash only in their
  source, in another form, come out as not confirmed even for the right ROM.

Picking a confirmed repository adds it. An unconfirmed one needs a second press on "Add anyway", under a
warning that it may be a port of another game. A repository that one of the user's lists gives for another
ROM is refused (`PortSources.otherGame`). The ROM gives the system folder, file type, size, SHA-1 and form;
with several downloads the user picks one, and the entry keeps a pattern for its name that still fits the
next version (`PortSources.assetPattern`). The program and the staged name are optional details. A download
that is a zip, a tar.gz, a tar.xz or a bare program is taken.

The releases are read while the ROM is still being hashed. A file RomM records no hash for (RomM hashes no
Switch, PS3 or other large-disc platform) is hashed on the device, SHA-1 and MD5 in one read on two threads
(`PortRoms.digests`), and a 16 GB file takes a while: the dialog shows how far it is, lists the repositories
meanwhile, and marks the ones with a download "Checking…" until the hash is there (`PortSearch.find`,
`Verdict.WAITING`). Closing the dialog stops the read. The result is kept while the file stays as it is, and
its SHA-1 goes to `hashes.tsv` so the scan does not read the file again (`PortsState.facts`).

GitHub is read without an account. Measured on 2026-11-22 from `api.github.com/rate_limit`: 60 requests an
hour to the API and 10 searches a minute, per address; `raw.githubusercontent.com`, where the README and
hash files come from, counts against neither. A search costs one search request and, for each of up to six
repositories, one request for releases and one for the tree when the README did not settle it. What the
releases offer is kept for ten minutes by repository, and a verdict by repository and ROM. When GitHub answers 403 or 429 with nothing remaining, the dialog says so and gives
the time the limit lifts (`PortSearch.limited`); what was found before the limit stays on screen.

## Finding the ROM

`PortRoms.scan` looks in `ROMs/<system folder>` (`n64`, three levels deep) for files with the port's file
types and exact size, and takes the first whose SHA-1 matches. N64 dumps come in three byte orders (`.z64`,
`.v64`, `.n64`), told apart by the first four bytes (`N64Rom`); the hash is taken over the big-endian form,
so any of the three matches. Hashes are remembered in `filesDir/ports/hashes.tsv` by path, size and
modification time, so a file is read once.

A `.zip` in a system folder counts through the one file it holds (`PortRoms.inner`): that file's type and size are
checked and its bytes are hashed, so a zipped dump matches as the plain one does. A zip holding several files is not
taken for a ROM, and `.7z` is not read. A port that reads the ROM itself (no staged name, or `{rom}` in its settings)
gets the file unpacked to `roms/` in its install folder, at install and again at Play when it is gone
(`PortInstaller.prepare`); `{rom}` and the note on the port's page name that copy.

## Install, play, remove

`PortInstaller.install` reads the repository's releases (`GithubReleases`, shared with user apps), downloads
the asset with `Downloader` checked against the SHA-256 digest GitHub publishes, and unpacks it into
`/opt/droiddeck-ports/<tool>` in the runtime through a temporary folder; `tool` is the port's id unless the entry names one. It then writes the entry's marker
files and the ROM under the staged name. For Zelda 64: Recompiled that is `portable.txt`, which makes the
port keep its settings and saves in its own folder, and the ROM in big-endian form as `mm.n64.us.1.0.z64`,
the name and place the port's runtime (`librecomp/src/recomp.cpp`, `GameEntry::stored_filename`) looks for a
ROM it has already accepted, so the game starts without asking for one. It needs Vulkan 1.2. The release tag is kept in `.droiddeck-port`; a port counts as installed
while that file and the program exist. The program found is kept in `.droiddeck-entry`.

Ports of one repository that name the same `tool` share one install: a tool that plays several games (Gen1Recomp) is
downloaded once. A port added from scratch takes the `tool` of a port the user already has for the same repository and
download (`PortSources.single`). Each port keeps its own start script, marker files and ROM copy in the shared folder;
a ROM copy carries a note of the port that made it (`<staged name>.droiddeck-owner`), and Play writes it again when
another game of the tool last used that name. Uninstall removes the install for every port that shares it. A `tool`
already used by a port of another repository is left out (`Ports.use`).

A download that is an AppImage cannot mount itself in the runtime, so `AppImageManager.unpackInto` unpacks it into `app/` in the
install folder, as it does for the user's AppImages, and the program recorded is `app/AppRun`; the entry need not say so. An
archive whose program is an AppImage (Ship of Harkinian's `SoH-Linux.zip` holds `soh.appimage`) is unpacked the same way. An
install whose program is the AppImage file itself counts as not installed.

A port built for x86-64 PCs runs through FEX, as the user's x86 AppImages and programs do: an AppImage through
`droiddeck-appimage-run`, which reads the `arch` file the unpacking wrote, and a plain program through
`droiddeck-fex run --mode on` in its start script (`PortInstaller.writeStarter` reads the program's ELF header).
Until FEX is set up the port's page says so, with the text the user's x86 apps get (`PortInstaller.x86Arch`).

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
writes it again from the ROMs folder first.

`saves` in an entry lists the files and folders the program keeps its saves in, up to 8. A plain path is
inside the install folder; one that begins with `~/` is inside the runtime's home (`/root`). Removing the port
deletes everything in its folder except those paths, and the next install carries them over. An entry that
names none in its folder keeps a folder called `saves`. An install that several ports share keeps the paths
of all of them (`PortInstaller.kept`). Paths in the home are outside the install folder, so neither an
install nor a removal touches them. The same paths are what RomM save sync sends for a RomM game. A reinstall moves the old folder aside before the new one takes its
place, so a failed swap leaves the old install and its saves as they were.

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
`RommPlatforms.refusedBy` says so on the game's page, beside Install. Byte-swapped dumps and
archives cannot be judged from that hash, so they are still downloaded and checked afterwards.

The same hash finds a port before the ROM is downloaded. `RommPlatforms.portFor` names a port the user has
whose ROM is this file, on any platform, and the page says "Plays with" it. When there is none, "Add a port
for this game" is on the page already: the dialog takes the folder, type, size, SHA-1 and MD5 from RomM's
record (`RommPlatforms.facts`, `PortRoms.recorded`) where it would hash the local file. That works for a game
that is one plain file; an archive, a folder, a disc set or a byte-swapped N64 dump has no usable hash until
it is downloaded. A ROM whose platform nothing plays can be downloaded first and a port added afterwards (see
[the RomM store](romm-store.md), Install, play and remove).

## Not handled yet

Ports with a separate asset extraction step, a port whose download is a setup program that puts the game
somewhere else (the start script runs the setup again, not the game), building a port from source, update
checks and mods. No x86-64 port has been run on a device yet: the path is the one the user's x86 AppImages
take. Saves are synced only for a RomM game whose port names them (see [the RomM store](romm-store.md), Sync). A game page offers one port beside the emulator: a second port for a ROM that already has one
is not offered.

## Tests

`N64RomTest`, `PortRomsTest`, `PortInstallerTest`, `PortCatalogTest`, `PortLaunchTest`, `PortSourcesTest`, `PortSearchTest`, `RunWithTest`, and the port cases in `RommPlatformsTest` and `RommActivityTest`.
