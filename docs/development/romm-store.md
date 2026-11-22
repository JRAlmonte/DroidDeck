# RomM store

The RomM Store tab browses a self-hosted [RomM](https://github.com/rommapp/romm) server and downloads its
ROMs into the ROMs folder, where the Desktop emulator pages list and launch them. It browses like a store
but installs and launches like a ROM: there is no sidecar, no Steam shortcut and no Windows program.

## Sign-in

There is no central service, so the user gives a server address and an account (`RommConnectDialog`, opened
where itch opens its key dialog). `RommAuth.candidates` accepts an address with a scheme as typed and tries
HTTPS then HTTP for one without. `RommAuth.verify` reads `/api/heartbeat` (no sign-in needed) for
`SYSTEM.VERSION` and refuses servers older than 4.8, the first with client tokens.

With a username, the password is sent once as HTTP Basic to `/api/users/me` and then to
`POST /api/client-tokens`, which returns a client token limited to the read scopes the store uses and the
account has. Only that token is kept. With no username, the secret is taken as a client token made in RomM's
web UI, the route for accounts without a password. The dialog tells apart an unreachable address, an address
that is not RomM, an old server, a wrong password, a rejected token and an account RomM will not issue a
token to.

The address and token are stored through `StoreAccounts` (encrypted; RomM refuses the plain-file fallback).
One server and one account at a time. Requests send `Authorization: Bearer <token>` and never cookies: a RomM
session cookie would take priority over the token and turn its CSRF check back on.

## Data

`RommApi` reads the server address on every call. `/api/platforms` fills the platform menu
(`RommBrowseBar`); `/api/roms` pages by `limit` and `offset`, filtered by `platform_ids` or `search_term`,
with the index and filter extras switched off. `RommCatalog` turns a ROM into a `CatalogItem` that is owned
and has no price; `extra` carries the platform slug, the file name and whether the ROM is one plain file.
Cover paths are served by the RomM server without sign-in, so the shared image loader shows them as is.

## Platforms and emulators

`RommPlatforms` maps a RomM platform slug to a ROMs system folder and takes the emulator and its file types
from `Library.romSystems()`, so a system the Desktop page learns to scan becomes installable here with no
change. A slug is its own folder name unless listed otherwise (`ngc` is `gamecube`). A game whose platform no
emulator scans, or whose file type that emulator does not list, shows a note instead of Install.

Cartridge systems go to RetroArch. `RetroArchCores` lists, per system folder, the libretro core the Emulators
package installs and the file types it takes (see `retroarch-cores.md`): SNES (snes9x), NES (nestopia), Game Boy and Game Boy Color
(gambatte), Game Boy Advance (mgba), Genesis, Master System and Game Gear (genesis_plus_gx), PC Engine
(mednafen_pce_fast), Virtual Boy (mednafen_vb), WonderSwan (mednafen_wswan) and SG-1000 (genesis_plus_gx).
Nintendo 64, Dreamcast, Arcade, Neo Geo, Atari 2600, 5200 and 7800 and Jaguar are listed only while their fetched core's file is
in the runtime; until then the game's page offers to get the core. A ROM in one of those folders
starts as `retroarch -f -L <core> <rom>`. RetroArch only lists files in those folders, never loose files in
the ROMs root.

RomM groups the versions of a game on one platform (regions, revisions, discs kept as separate ROMs) when asked
with `group_by_meta_id`, and the store asks: the list shows one tile per game, and each entry names its
`sibling_roms`. The game's page then has a menu of the versions (`RommCatalog.versions`, by file name so the
region and disc tags show); choosing one reads that ROM from `/api/roms/{id}` and the page's buttons act on it.
A page whose first version is not installed opens on an installed one.

Searching in the store narrows the same paged list (`RommBrowseState.search`), so it stays within the chosen
platform and loads further pages like browsing does.

The platform menu lists platforms newest first by the year they came out, from a table in `RommPlatforms` because RomM
reports a generation but no date. Platforms the table does not know come last, by name.

## Install, play and remove

`RommGameActions` is the game page's action row for this store. Install queues a `RommInstaller` on the
shared `DownloadQueue`: it downloads `/api/roms/{id}/content/{file}` to a hidden `.romm-<id>.part` beside the
target (resuming with `Range`), renames it to `ROMs/<system>/<file>`, stores RomM's cover where `CoverArt`
caches box art, and records the game in `filesDir/stores/romm/installed.json` (`RommInstalls`). A game counts
as installed while its record and its file both exist.

The record keeps the game's title, art and details, so the Library page lists installed RomM games under a
RomM tab and the store's profile strip counts them. A RomM server can hold tens of thousands of games, so only
installed ones are in the library; the rest are browsed in the store.

A zip or 7z whose platform's emulator does not open it is unpacked instead (`RommArchive`): one file goes straight into the system
folder, several go into a folder named after the archive, and the launch file is a disc sheet (`m3u`, `cue`)
when there is one. The record keeps both the launch file and what to delete. Play calls `Library.launchRom`, the same launch the
Desktop page uses. Uninstall deletes the file and the record. Signing out forgets the records and keeps the
files.

A rar is unpacked the same way by the runtime's `bsdtar` (`StoreArchiveUnpacker`, shared with itch.io and Game Jolt), into a hidden
folder beside the target and then placed like a zip's files. The game's page says to set DroidDeck up first when the runtime is not
there, before anything is downloaded.

A ROM that RomM holds as a folder is installed file by file. `RommInstaller` reads `/api/roms/{id}` for the ROM's `files`
(`RommCatalog.files`: each file's id, size and place inside the folder, leaving out files RomM marks as DLC, manuals and other extras,
and any path that would leave the folder) and downloads each from `/api/roms/{id}/content/{name}?file_ids=<id>`, which RomM answers
with that one file and honours `Range` for. Files go into `ROMs/<system>/<folder name>/`, each through its own `.part`, and a file
already there at its full size is kept, so a stopped install carries on where it was. The launch file is a disc sheet when there is
one. Several discs with no playlist get an `m3u` named after the folder when the emulator takes one (`RommPlatforms.discs`). A folder
holding one file is installed like a plain file.

A game whose platform has an emulator but whose file type that emulator does not open says which type it is.

Collections, pairing-code sign-in, BIOS files, save sync and playtime are not handled yet. Archives inside a folder ROM are not
unpacked.

## Tests

`RommAuthTest` (addresses, version gate, password and token sign-in against a local socket), `RommCatalogTest`,
`RommPlatformsTest`, `RommInstallsTest`, `RommArchiveTest`, `RommInstallerTest` (a folder game downloaded file by file
from a local socket, resumed with `Range`, and failing without a record when a file is missing).
