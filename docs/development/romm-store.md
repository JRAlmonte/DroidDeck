# RomM store

The RomM Store tab browses a self-hosted [RomM](https://github.com/rommapp/romm) server and downloads its
ROMs into the ROMs folder, where the Desktop emulator pages list and launch them. It browses like a store
but installs and launches like a ROM: there is no sidecar, no Steam shortcut and no Windows program.

## Sign-in

There is no central service, so the user gives a server address and a client API token (`RommConnectDialog`,
opened where itch opens its key dialog). `RommAuth.candidates` accepts an address with a scheme as typed and
tries HTTPS then HTTP for one without. `RommAuth.verify` reads `/api/heartbeat` (no sign-in needed) for
`SYSTEM.VERSION`, refuses servers older than 4.8 (the first with client tokens), then asks `/api/users/me`
with the token. The dialog tells apart an unreachable address, an address that is not RomM, an old server and
a rejected token.

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

## Install, play and remove

`RommGameActions` is the game page's action row for this store. Install queues a `RommInstaller` on the
shared `DownloadQueue`: it downloads `/api/roms/{id}/content/{file}` to a hidden `.romm-<id>.part` beside the
target (resuming with `Range`), renames it to `ROMs/<system>/<file>`, stores RomM's cover where `CoverArt`
caches box art, and records the game in `filesDir/stores/romm/installed.json` (`RommInstalls`). A game counts
as installed while its record and its file both exist. Play calls `Library.launchRom`, the same launch the
Desktop page uses. Uninstall deletes the file and the record. Signing out forgets the records and keeps the
files.

Only ROMs that are one plain file are installed. Games made of several files, collections, pairing-code
sign-in, BIOS files, save sync and playtime are not handled yet.

## Tests

`RommAuthTest` (addresses, version gate, sign-in against a local socket), `RommCatalogTest`,
`RommPlatformsTest`, `RommInstallsTest`.
