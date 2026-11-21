# Native ports

A native port is a decompilation or recompilation project that plays one specific game as a native program,
given the user's own ROM. When the ROMs folder holds the exact dump a known port needs, the Desktop page
shows a card for that port. It installs the port's own release build into the runtime and starts it with the
same run mode that starts emulators and user apps. DroidDeck ships no ROM and no port.

## Catalog

`Ports.all` (`ports/Port.kt`) lists the ports. An entry names the GitHub repository, the release asset to
take, the program inside it, the ROM system folders and file types, the size and SHA-1 of the ROM, the name
the port keeps its copy of the ROM under, and marker files to create beside the program. Adding a port is
adding an entry, as long as it publishes a build the runtime can run and takes the ROM as a file.

The one port so far is [Zelda 64: Recompiled](https://github.com/Zelda64Recomp/Zelda64Recomp) (Majora's Mask,
US N64 release), chosen because it publishes a Linux ARM64 build and reads the ROM directly with no asset
extraction step. It needs Vulkan 1.2.

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
shows that one's buttons and no choice. No emulator here plays N64 yet, so Majora's Mask offers the port only.

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
sync and mods.

## Tests

`N64RomTest`, `PortRomsTest`, `PortInstallerTest`, `RunWithTest`, and the port case in `RommPlatformsTest`.
