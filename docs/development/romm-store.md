# RomM store

The RomM Store tab browses a self-hosted [RomM](https://github.com/rommapp/romm) server and downloads its
ROMs into the ROMs folder, where the Desktop emulator pages list and launch them. It browses like a store
but installs and launches like a ROM: there is no sidecar, no Steam shortcut and no Windows program.

## Sign-in

There is no central service, so the user gives a server address and an account (`RommConnectDialog`, opened
where itch opens its key dialog). `RommAuth.candidates` accepts an address with a scheme as typed. One without
a scheme is tried over HTTPS, and over HTTP after that only when it names a machine on the user's own network
(`RommAuth.local`: private and link-local ranges, names without a dot, `.local`, `.lan` and the like); a server on the
internet is reached over plain HTTP only when the user types `http://`. The dialog says so while the address typed
can be reached without encryption. `RommAuth.verify` reads `/api/heartbeat` (no sign-in needed) for
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

Signing out forgets the token here and leaves it on the server: deleting a client token needs the `me.write`
scope, which would also let the token change the account, so the store does not ask for it. The token is named
after the device and can be deleted in RomM's settings.

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
above a classic. The store opens on highest rated (`RommSort.DEFAULT`) until another order is chosen; games with no
rating follow the rated ones in whatever order the server gives. The choice is kept in `RommPrefs`.

A game carries what the server knows about it in `CatalogItem.extra`, read by `RommCatalog.item`: genres, companies,
franchises, age ratings, game modes, player count, rating and release date from `metadatum`, regions, languages and
revision from the ROM, up to twelve of `merged_screenshots`, and `rom_user.last_played`. Installed games keep them, since
`extra` is stored with the install record. The game's page lists them under Details (`rommFacts`), shows the rating out
of ten beside the platform (RomM keeps it out of a hundred), and shows the screenshots in a strip; choosing one makes it
the page's backdrop. Only the RomM server's data is shown for a RomM game unless asked otherwise: the
lookups other stores' pages make by title, a Metacritic score through Steam's store search and play times from
howlongtobeat.com, each have a switch in the Stores settings (`SessionPrefs.rommMetacritic`, `rommHltb`), off at first,
and the page's link buttons show only while one of them is on.

The store draws icons in place of many of its labels (filter chips and dropdowns, the tile and page rating, row titles,
the page's tabs and Details rows, and the Play, Install and Resume buttons) while the "Icons instead of words" switch in
the Stores settings is on (`SessionPrefs.rommIcons`, on at first). The icons are listed in `RommIcons`; each keeps the
word it replaces as its content description.

A second row narrows the list (`RommFilter`, sent by `RommApi.roms` under the server's own names): `genres`, `regions`
and `languages`, each repeatable and matching any of the chosen values, `favorite=true`, `statuses=backlogged` and
`statuses=now_playing`, and `last_played=true` or `false`. The values on offer come from `filter_values` of one
`limit=1` page asked for with `with_filter_values=true` for the chosen platform, kept for the visit; the pages of games
still ask without them. These names are RomM 5's (`backend/handler/database/rom_filters.py`); a server that does not
know one ignores it and answers with the unnarrowed list. Installed is answered here: the list becomes the installed
games (`RommCatalog.matches` and `sorted`), narrowed by platform, search, genre, region and language from what each
game carries, and what only the server knows about a game is not offered meanwhile. A game installed before it carried
those facts does not match a genre until it is installed again. What narrows the list lasts for the visit.

A tile says the game's rating, the year it came out and, when no platform is chosen, its platform
(`RommCatalog.cardLine`); its size is on the game's page.

While nothing narrows the list (no search, platform, collection or filter), three rows sit above it
(`RommShelvesState`): Continue playing (`last_played=true`, by `last_played`), Recently added (by `created_at`) and
Favourites (`favorite=true`), 24 games each, read when the store is shown and again after a game was played or a
favourite changed. A row with no games is left out. A game in a row and in the list has a tile in each.

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

`droiddeck-pad-defaults` writes RetroArch's save and state folders, with sorting into sub-folders off, into a config that
does not name them yet, and never changes one that does; a RetroArch whose saves were moved or sorted elsewhere is not
synced (`RommSaves.retroArchInPlace`). It sets DuckStation's first card to `PerGameFileTitle`, so a card is named after
the game's file, when the setting is still the default, no card has been written yet and the card folder was not moved;
any other install keeps its setting, and a game is synced only while the setting is `PerGameFileTitle`. melonDS is left as it is: beside the game is where it saves unless told otherwise.
The game's own file is never read, only the names beside it.

PPSSPP, Dolphin, ARMSX2, Cemu and RPCS3 are not synced: they keep saves as folders named by a disc or title id, or on one
card shared by every game, which needs that id read from the game and the folder packed for the server.

`Library.launchRom` calls `RommSync.beforeLaunch`, which fetches the server's files and starts the game when that is done or
after eight seconds, whichever is first. `SessionService.finishSessionStop` uploads what changed; an upload that was missed is
sent at the next app start. Sync can be turned off per game.

`RommActivity` sends play sessions (`/api/play-sessions`), favourites (the collection RomM marks `is_favorite`, made when
there is none) and a game's backlog, now playing, status and rating. What cannot be sent waits in a file and goes out at the
next session end or app start. Favourites made on the server are merged into the library's.

## Controls

A game started from a ROM (a RomM game, a local ROM or a port) can have its own controller choices. They are kept under
two names from `RomScope`: `rom:<path under the ROM folder>` for the game and `system:<folder>` for its system, the
folder being the first name its emulator knows it by, so `psx` and `ps1` are one system. `Library.launchRom` and
`PortsState.launch` pass both to the session as `EXTRA_ROM_SCOPE`, kept in `SessionState.romScope`.

The controller profile is chosen per game, per system or for all games. The input library holds the first two in
`InputLibrary.scoped`, stored under `scoped` only when there is one, with the library's version unchanged (15 and 16
mean an abandoned redesign to `InputLibraries`). `forScope` answers the library a session reads: the game's choice, else
its system's, else the Game mode one. An installed game's page has a Controller tab for it (`RomControlsTab`, the same
`InputQuickSettings` as the Controller page of the in-game menu, which gains an "Applies to" choice in a ROM's
session). Input settings list the systems that have a profile.

Two settings inside the emulators can be chosen the same way (`RomControls`, kept in the `rom-controls` preferences):
DuckStation's controller type for port 1, analog or digital, and RetroArch's stick as D-pad. The session passes what
applies as `DROIDDECK_PS1_PAD` and `DROIDDECK_RA_ANALOG_DPAD`, and `droiddeck-pad-defaults` writes it before the
emulator starts, keeping what was there beside the file (`<file>.droiddeck-<name>`) and putting it back at the next
start that asks for nothing. A setting nobody chose is never written.

The pad an emulator sees is always the Xbox 360 one. Presenting it as a PlayStation pad is not done: several seeded
mappings name the pad (`droiddeck-pad-defaults` for RPCS3, Dolphin, Cemu and RetroArch), and what each emulator's SDL
calls another pad has to be read from a running session first.

## Tests

`RommAuthTest` (addresses, version gate, password, token and code sign-in against a local socket), `RommCatalogTest`,
`RommPlatformsTest`, `RommInstallsTest`, `RommArchiveTest`, `RommInstallerTest` (a folder game downloaded file by file
from a local socket, resumed with `Range`, and failing without a record when a file is missing). `RommApiTest` (the order asked for). `RommFirmwareTest` (where each
emulator's BIOS goes, which files are missing, and a download kept only when it matches). `RommSavesTest` (which files
are a game's), `RommCloudTest` (listing, sending, a newer server copy, a refused token, and a full down and up run) and
`RommActivityTest` (play time, favourites and status, and the queue kept while offline). `RomScopeTest` (the names a game's
choices are kept under), `RomControlsTest` (a game's choice before its system's) and the scoped cases of
`InputLibraryTest` (a library stored as before, the order of choices, a removed profile).
