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

"Sign in with a code" needs only the address (`RommAuth.pair`, `awaitPairing`), for accounts that sign in to RomM through
SSO or would rather not type a password here. The app asks `POST /api/auth/device/init` for a code, naming the scopes it
wants, and shows it with the address of RomM's pairing page; the user approves it there, in a browser where they are
signed in. Meanwhile the app asks `POST /api/auth/device/token` at the pace the server set until it answers with a client
token, a refusal, or that the code ran out (ten minutes). A server without these routes says so and the other two ways
stay.

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
(mednafen_pce_fast), Virtual Boy (mednafen_vb), WonderSwan (mednafen_wswan) and SG-1000 (genesis_plus_gx), and the CD systems
Sega CD (genesis_plus_gx) and PC Engine CD (mednafen_pce_fast).
Nintendo 64, Dreamcast, Arcade, Neo Geo, Atari 2600, 5200 and 7800 and Jaguar are listed only while their fetched core's file is
in the runtime; until then the game's page offers to get the core. A ROM in one of those folders
starts as `retroarch -f -L <core> <rom>`. RetroArch only lists files in those folders, never loose files in
the ROMs root.

RomM groups the versions of a game on one platform (regions, revisions, discs kept as separate ROMs) when asked
with `group_by_meta_id`, and the store asks: the list shows one tile per game, and each entry names its
`sibling_roms`. The game's page then has a menu of the versions (`RommCatalog.versions`, by file name so the
region and disc tags show); choosing one reads that ROM from `/api/roms/{id}` and the page's buttons act on it.
A page whose first version is not installed opens on an installed one.

When several versions differ only by a disc tag (`RommCatalog.discSets`) and the platform's emulator takes a
playlist, the menu also offers "all N discs". It reads each disc's ROM, and when each is one file makes one
item of them (`RommCatalog.discSet`). `RommInstaller` downloads the discs into `ROMs/<system>/<name>/`, unpacking
a zipped disc like any other, keeps a disc already there in full, and writes an `m3u` naming them, which is
the launch file. The game is recorded under the first disc's ROM.

Searching in the store narrows the same paged list (`RommBrowseState.search`), so it stays within the chosen
platform and loads further pages like browsing does.

When the account has collections, a second menu lists them (`RommCatalog.collections`): the ones from `/api/collections`
and the smart ones from `/api/collections/smart` that hold games, favourites first. Choosing one adds `collection_id` or
`smart_collection_id` to the same list, so it combines with the platform and the search. Virtual collections (one per
genre, company, franchise and so on) are not listed: a server has thousands of them.

The Sort menu beside them (`RommSort`, sent as `order_by` and `order_dir`) lists games by name either way, highest rated
(`average_rating`, the rating in the game's metadata), newest or oldest release, recently added or recently played. RomM
keeps no sales or popularity figures, and its rating has no vote count, so a little-known game with one high score sorts
above a classic. The choice is kept in `RommPrefs`.

The platform menu lists platforms newest first by the year they came out, from a table in `RommPlatforms` because RomM
reports a generation but no date. Platforms the table does not know come last, by name.

## Install, play and remove

`RommGameActions` is the game page's action row for this store. With no ROMs folder chosen, Install first sets it to
`ROMs` on internal storage (`FrontEndActivity.useDefaultRoms`), asking for all-files access when that is missing and
falling back to the folder picker when the folder cannot be made. Install queues a `RommInstaller` on the
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

Archives inside a folder ROM are not unpacked.

## BIOS files

RomM keeps BIOS and firmware files per platform (`/api/firmware?platform_id=`), and the store fetches the ones the server
holds; it never looks for them anywhere else. With a platform chosen, the browse bar offers "Get BIOS files (N)" for the
files that are not on the device yet, and a game whose system needs a BIOS (`RetroArchCores.firmwareFor`) offers the same on
its page in place of "Needs your own …" when the server has files for its platform.

`RommFirmware.directory` decides where a file goes. A standalone emulator's go to `ROMs/<system>/bios` (or a `BIOS` or
`firmware` folder already there), where `droiddeck-pad-defaults` points DuckStation, ARMSX2 and melonDS. RetroArch's go to
its system folder in the runtime, `~/.config/retroarch/system`, which the same script pins as `system_directory`; that needs
DroidDeck set up first. The arcade sets FinalBurn Neo reads (`RetroArchCores.firmwareFiles`, `neogeo.zip` among them) go
beside the games. A platform nothing here plays, or only a port plays, gets none.

`RommFirmware.fetch` downloads to a hidden part file and keeps the file only when its size and MD5 are the ones RomM
reports. A file already there at its full size is left alone. Files keep the names they have on the server, so a BIOS has
to carry the name its emulator looks for.

## Sync

Sign-in asks for a token that may also write (`RommAuth.SCOPES`) and keeps the server's version and the granted scopes with
the account. Everything here needs RomM 4.9 or newer and the `assets.write` scope (`RommSync.access`); the game's Sync tab
(`RommSyncTab`) says which of the two is missing. An account signed in before this existed has to sign in again.

Saves and states go through the stores' cloud-save engine (`CloudSaves`, `CloudPlan`) with `RommCloud` as the transport, so
baselines, the three-way plan, conflicts that wait for a choice and backups of replaced files are the shared ones.
`RommCloud` lists `/api/saves` and `/api/states` for the ROM, keeps the entries of the game's emulator, and sends a file as a
multipart `PUT` to a known entry or `POST` for a new one. Saves carry the device id from `POST /api/devices`; a 409 means
the server's copy is newer and is left alone. States have no content hash on the server, so they are compared by time.

`RommSaves.layout` says where the game's emulator keeps its files and which are this game's, by the launch file's name
without its extension:

| Emulator | Saves | States |
|---|---|---|
| RetroArch | `~/.config/retroarch/saves/<name>.<ext>` | `~/.config/retroarch/states/<name>.state`, `.stateN`, `.state.auto` |
| DuckStation | `memcards/<name>_1.mcd` and `_2.mcd` in its data folder | not synced: it names them by the disc's serial |
| melonDS | `<name>.sav` beside the game | `<name>.ml1` to `.ml8` beside the game |

`droiddeck-pad-defaults` pins RetroArch's save and state folders and turns off sorting into sub-folders. It sets
DuckStation's first card to `PerGameFileTitle`, so a card is named after the game's file, when the setting is still the
default and no card has been written yet; an install that already has cards keeps its setting, and a game is synced only
while the setting is `PerGameFileTitle`. melonDS is left as it is: beside the game is where it saves unless told otherwise.
The game's own file is never read, only the names beside it.

PPSSPP, Dolphin, ARMSX2, Cemu and RPCS3 are not synced: they keep saves as folders named by a disc or title id, or on one
card shared by every game, which needs that id read from the game and the folder packed for the server.

`Library.launchRom` calls `RommSync.beforeLaunch`, which fetches the server's files and starts the game when that is done or
after eight seconds, whichever is first. `SessionService.finishSessionStop` uploads what changed; an upload that was missed is
sent at the next app start. Sync can be turned off per game.

`RommActivity` sends play sessions (`/api/play-sessions`), favourites (the collection RomM marks `is_favorite`, made when
there is none) and a game's backlog, now playing, status and rating. What cannot be sent waits in a file and goes out at the
next session end or app start. Favourites made on the server are merged into the library's.

## Tests

`RommAuthTest` (addresses, version gate, password, token and code sign-in against a local socket), `RommCatalogTest`,
`RommPlatformsTest`, `RommInstallsTest`, `RommArchiveTest`, `RommInstallerTest` (a folder game downloaded file by file
from a local socket, resumed with `Range`, and failing without a record when a file is missing). `RommApiTest` (the order asked for). `RommFirmwareTest` (where each
emulator's BIOS goes, which files are missing, and a download kept only when it matches). `RommSavesTest` (which files
are a game's), `RommCloudTest` (listing, sending, a newer server copy, a refused token, and a full down and up run) and
`RommActivityTest` (play time, favourites and status, and the queue kept while offline).
