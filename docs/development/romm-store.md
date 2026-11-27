# RomM store

The RomM Store tab browses a self-hosted [RomM](https://github.com/rommapp/romm) server and downloads its
ROMs into the ROMs folder, where the Desktop emulator pages list and launch them. It browses like a store
but installs and launches like a ROM: there is no sidecar, no Steam shortcut and no Windows program.

## Sign-in

There is no central service, so the user gives a server address and an account (`RommConnectDialog`, opened
where itch opens its key dialog). `RommAuth.candidates` accepts an address with a scheme as typed. One without
a scheme is tried over HTTPS. When no server answers there and the address names a machine on the user's own network
(`RommAuth.local`: private and link-local ranges, names without a dot, `.local`, `.lan` and the like), plain HTTP is
tried for the heartbeat alone (`RommAuth.reach`). Nothing secret goes with it: the dialog says only an unencrypted server
answered (`Problem.PLAIN`), rewrites the address with `http://`, and the user sends it again to sign in that way. A
certificate the device refuses ends the search (`Problem.UNTRUSTED`) and plain HTTP is not tried. A password, token or
pairing code therefore goes out unencrypted only to an address that says `http://`, and nobody on the network can
turn an encrypted sign-in into a readable one by breaking the first attempt. The dialog says so while such an address
is typed, and the profile strip says "not encrypted" beside the name of an account signed in that way. Android cannot allow plain HTTP for private addresses alone, so
`network_security_config.xml` allows it in general and forbids it for every domain the app itself talks to; a new store
or service adds its domain there. A cover or screenshot address an HTTPS server gives with `http://` is asked for over
HTTPS (`RommCatalog.asset`). `RommAuth.verify` reads `/api/heartbeat` (no sign-in needed) for
`SYSTEM.VERSION` and refuses servers older than 4.8, the first with client tokens.

With a username, the password is sent once as HTTP Basic to `/api/users/me` and then to
`POST /api/client-tokens`, which returns a client token limited to the scopes the store uses (`RommAuth.SCOPES`) and
the account has. Only that token and its id are kept. With no username, the secret is taken as a client token made in RomM's
web UI, the route for accounts without a password. The dialog tells apart an unreachable address, an address
that is not RomM, an old server, a wrong password, a rejected token and an account RomM will not issue a
token to.

"Sign in with a code" needs only the address (`RommAuth.pair`, `awaitPairing`), for accounts that sign in to RomM through
SSO or would rather not type a password here. The app asks `POST /api/auth/device/init` for a code, naming the scopes it
wants, and shows it with the address of RomM's pairing page; the user approves it there, in a browser where they are
signed in. The page opened is always on the server that was asked: a path from the answer is used only when it is a
path and nothing more (`RommAuth.pathOnly`). Meanwhile the app asks `POST /api/auth/device/token` at the pace the server set until it answers with a client
token, a refusal, or that the code ran out (ten minutes). A server without these routes says so and the other two ways
stay.

Signing out deletes the token on the server (`RommAuth.revoke`, `DELETE /api/client-tokens/{id}`) when the sign-in
here made it; RomM keeps at most 25 tokens for an account, so tokens left behind would in time stop new sign-ins.
Deleting needs the `me.write` scope, which the store asks for only for this and for the RetroAchievements refresh,
and the token's id. A password sign-in
is given the id with the token. The pairing answer names the device and not the token, so the id is read from
`GET /api/client-tokens` as the newest token of that device. A pasted token is the user's own and stays, as does the
token of a sign-in made before this existed or without `me.write`; those are named after the device and can be
deleted in RomM's settings. The server is asked in the background, three times over a few seconds
(`RommAuth.revokeOrTell`); a server that still cannot be reached keeps the token, and a notice says to delete it in
RomM's settings.

The token is made to never run out (`expires_in: "never"`), so that a handheld left for months still syncs. What it can
do is therefore kept to the scopes some request here needs: `me.write` is the widest of them, and nothing asks for
`devices.read`, `users.*` or any scan or task scope. RomM can make a token again in place
(`PUT /api/client-tokens/{id}/regenerate`); nothing here uses that yet.

Signing in while signed in replaces the account (`RommAuth.save`). ROM ids repeat from one server to the next, so a
sign-in to another server first does what signing out does (`RommBackend.forget`): the installed records, the changes
waiting to be sent, the kept game pages, the kept first page of the store, the save-sync records and the favourites
are dropped and the old token is deleted, and nothing recorded for one server is ever sent to another. Another account on the same server keeps the
installed records, which are still that server's games, and drops the rest. The same account signing in again only has
its old token deleted.

The address and token are stored through `StoreAccounts` (encrypted; RomM is never written to the plain-file fallback).
An address that carries a name and password (`user:pass@host`) is not accepted, since the address is kept in the clear
with every game's cover address.
One server and one account at a time. Requests send `Authorization: Bearer <token>` and never cookies: a RomM
session cookie would take priority over the token and turn its CSRF check back on.

## Data

`RommApi` is given the server address and token on every call; a caller reads the two together (`RommAuth.both`), so a
request never carries one account's token to another's server. Opening the account asks the Android Keystore, so
`StoreAccounts` keeps the opened text for as long as the file is the one it came from and hands each caller a copy of
its own. A request that is not a download gives up on an address nothing answers at after eight seconds.

The store's first page is asked for first and alone (`RommBrowseState.more`), so the grid fills after one round trip.
Once it answers, what the menus show is read beside it and each lands on its own (`menus`): the platforms, the totals,
the collections and the filter values. Those belong to the account and not to the page, so a change of platform or
filter keeps them, and a server that does not answer is asked once and not six times. The next page is asked for two
rows before the last one shows (`Storefront`), so scrolling does not stop at each page's end.

Another platform, collection, filter, order or search starts a new view (`RommBrowseState.turn`). The page the view
left was still waiting for is not only ignored: its connection is closed (`RommCall.cancel`, off the main thread), so
quick changes leave the server one query to answer and not one for each. A request given up comes back as code 0 with
`RommAnswer.cancelled` set. That says nothing about the server, so it does not count as a failed page, does not hold
the menus back and leaves the kept first page as it was. The menu requests are not given up: what they read is used by
every view.

The first page of the store as it opens (no platform, collection, search or filter, in the saved order) is kept on the
device with the platforms, the collections and the totals (`RommBrowseCache`). A start shows that copy at once
(`RommBrowseState.kept`), asks the server as before and puts the answer in its place; the menus are replaced as each
arrives. When the server does not answer or refuses, the copy stays, the page says the games could not be loaded
(`romm-stale`) and the retry at the end of the grid asks again. Later pages and narrowed views are never kept. Coming
back to the first page in the same run shows the last answer held in memory while it is asked for again.

The copy is one file under `files/stores/romm/browse/`, not under the cache folder, which `AppFilesProvider` shows to
the file picker. Its name is a hash of the server address and the user's id, and both are written inside and compared
when it is read, with the order, so another account, server or order never sees it. The token is no part of it. The
games are written with `StoreLibraryCache.encode` and the menus in the server's own shape, so they are read back
through `RommCatalog`'s parsers; a file over 2 MB, one that does not parse, or a game whose id could not be a file name
counts as no copy. `RommBackend.forget` deletes it (sign-out, another server, another user), a write that was asked for
before a deletion is dropped (`epoch`), and writing an account's copy removes any other left in the folder.

A game's page is given the game as soon as `/api/roms/{id}` answers (`RommDetails.fetch`, `early`); its play time and
the games like it are read side by side after that. The three shelf rows are read side by side too. A platform's
picture is asked for once however many places show it, four at a time at most, and a platform the server has no
picture for is not asked about again for a week (`RommPlatformIcons`).

`/api/platforms` fills the platform menu
(`RommBrowseBar`); `/api/roms` pages by `limit` and `offset`, filtered by `platform_ids` (once for each platform
chosen) or `search_term`,
with the index, filter and per-provider metadata extras switched off (`with_provider_metadata=false`; a server
older than that switch ignores it and sends them). `RommCatalog` turns a ROM into a `CatalogItem` that is owned
and has no price; `extra` carries the platform slug, the file name and whether the ROM is one plain file.
Cover paths are served by the RomM server without sign-in, so the shared image loader shows them as is. RomM keeps
each cover twice: `path_cover_large` and `path_cover_small`, which a scan makes from it at a fifth of its size, or two
fifths when it is under 1000 pixels high (`resize_cover_to_small`). A game's page, the Library and the cover kept at
install use the large one. A tile of the store's grid and shelves shows the picture the page opens with, a screenshot
when the server has one; when that picture is the cover, the tile asks for the small copy (`RommCatalog.tile`,
`COVER_SMALL`) and the page shows it until the large one is there. A game with one of the two has that one everywhere. A cover,
screenshot or badge can also be a full address on another host (`RommCatalog.asset`); those are asked for without the
token and always over HTTPS, so only the user's own server is ever reached without encryption.

## Platforms and emulators

`RommPlatforms` maps a RomM platform slug to a ROMs system folder and takes the emulator and its file types
from `Library.romSystems()`, so a system the Desktop page learns to scan becomes installable here with no
change. A slug is its own folder name unless listed otherwise (`ngc` is `gamecube`). A game whose platform no
emulator scans, or whose file type that emulator does not list, shows a note instead of Install.
The folder mappings of `/api/config` (`PLATFORMS_BINDING`, `PLATFORMS_VERSIONS`) are not read: a scan puts each of the
server's folders through them to make the `slug` that `/api/platforms` and every ROM's `platform_slug` send
(`scan_platform` in `backend/handler/scan_handler.py`, the same from 4.8.0 to 5.4.1), and that slug is what picks the folder here.

An emulator the user installed from the Linux store or as an AppImage counts as well once it is linked to its
system (Ryujinx for `switch`, Flycast for `dc`, and so on), and where several emulators can play a platform the
one picked in Settings › Emulators runs the game. `emulator-setup.md` has the table and the rules.

Cartridge systems go to RetroArch. `RetroArchCores` lists, per system folder, the libretro core the Emulators
package installs and the file types it takes (see `retroarch-cores.md`): SNES (snes9x), NES (nestopia), Game Boy and Game Boy Color
(gambatte), Game Boy Advance (mgba), Genesis, Master System and Game Gear (genesis_plus_gx), PC Engine
(mednafen_pce_fast), Virtual Boy (mednafen_vb), WonderSwan (mednafen_wswan) and SG-1000 (genesis_plus_gx), and the CD systems
Sega CD (genesis_plus_gx) and PC Engine CD (mednafen_pce_fast). Only the cores DroidDeck's Emulators package
installs are used, so a system it has no core for (Nintendo 64, Dreamcast, Arcade, Neo Geo, Atari, Jaguar) counts as
one nothing here plays. A ROM in a listed system's folder starts as `retroarch -f -L <core> <rom>`. RetroArch only lists files in those folders, never loose files in
the ROMs root.

RomM groups the versions of a game on one platform (regions, revisions, discs kept as separate ROMs) when asked
with `group_by_meta_id`, and the store asks: the list shows one tile per game, and each entry names its
`sibling_roms`. The game's page then has a menu of the versions (`RommCatalog.versions`, by file name so the
region and disc tags show); choosing one reads that ROM from `/api/roms/{id}` and the page's buttons act on it.
A page whose first version is not installed opens on an installed one.

RomM lets an account mark one version of a game as its main one: `rom_user.is_main_sibling` on a ROM, and
`is_main_sibling` on each of its `sibling_roms` (`RommCatalog.MAIN`, `RommVersion.main`). In a list asked for with
`group_by_meta_id` the server puts that version first itself, among the ones the list's filters let through
(`roms_handler.py`: visible to the user, then the main one, then a full release, the region order, the name), so a tile
is the main version already and its page stays on it. A game the server was asked for on its own is whichever version
came: "Random game" and the games under "More like this" (`RommCatalog.alone`). Such a game's page, when none of its
versions is installed, reads the main version and shows that one (`RommCatalog.mainVersion`), as choosing it in the menu
would; when it cannot be read the page stays as it opened. The menu says which version is the main one.

When several versions differ only by a disc tag (`RommCatalog.discSets`) and the platform's emulator takes a
playlist, the menu also offers "all N discs". It reads each disc's ROM, and when each is one file makes one
item of them (`RommCatalog.discSet`). `RommInstaller` downloads the discs into `ROMs/<system>/<name>/`, unpacking
a zipped disc like any other, keeps a disc already there in full, and writes an `m3u` naming them, which is
the launch file. The game is recorded under the first disc's ROM.

Searching in the store narrows the same paged list (`RommBrowseState.search`), so it stays within the chosen
platforms and loads further pages like browsing does. While the order is the default one, a search sends no `order_by`,
and the server then ranks by how well the name matches; any other order chosen is kept.

When the account has collections, a second menu lists them (`RommCatalog.collections`): the ones from `/api/collections`
and the smart ones from `/api/collections/smart` that hold games, favourites first. Choosing one adds `collection_id` or
`smart_collection_id` to the same list, so it combines with the platform and the search. Virtual collections (one per
genre, company, franchise and so on) are not listed: a server has thousands of them.

The Sort menu beside them (`RommSort`, sent as `order_by` and `order_dir`) lists games by name either way, highest rated
(`average_rating`, the rating in the game's metadata), newest or oldest release, recently added (`created_at`), recently
updated (`updated_at`, when the server last changed the game's record: a rescan, new metadata or an edit) or recently played. RomM
keeps no sales or popularity figures and cannot sort by how many ratings stand behind a score, so a little-known game
with one high score sorts above a classic. The store opens on highest rated (`RommSort.DEFAULT`) until another order is chosen; games with no
rating follow the rated ones in whatever order the server gives. The choice is kept in `RommPrefs`.

A game carries what the server knows about it in `CatalogItem.extra`, read by `RommCatalog.item`: genres, companies,
franchises, age ratings, game modes, player count, rating and release date from `metadatum`, regions, languages,
revision and `tags` from the ROM, up to twelve of `merged_screenshots`, and `rom_user.last_played`. Installed games keep them, since
`extra` is stored with the install record. A ROM's tags are what RomM read in the brackets of its file name and took for
neither a region, a language nor a revision (Beta, Proto, Unl and the like); they are the values the tag filter offers,
and the page lists them in a Tags row under the same icon as that filter. The game's page lists them under Details (`rommFacts`), shows the rating out
of ten beside the platform (RomM keeps it out of a hundred), and shows the screenshots in a strip; choosing one makes it
the page's backdrop. Only the RomM server's data is shown for a RomM game: the lookups other stores' pages make by
title (a Metacritic score through Steam's store search, play times from howlongtobeat.com) and the page's link
buttons are left out, and `CoverArt` looks up no box art for a game RomM installed.

### A game's page

A tile carries only what the list sends. Opening a game reads `/api/roms/{id}` once (`RommDetails.load`) into a
`RommDetail`, kept in `RommDetails.known` for the visit and, for an installed game, as a trimmed copy under
`filesDir/stores/romm/details/<id>.json` so its page reads the same with the server out of reach (written when the page is
read while the game is installed, and at install from what the page read). From it the page shows:

- the times to beat from `hltb_metadata` (main, with extras, completionist), in the row and Details lines other stores
  fill from howlongtobeat.com (`GameScores`);
- the logo (`ss_metadata.logo_path`) in place of the title and the fan art (`fanart_path`) as backdrop, when the server has them;
- more Details rows: the number of ratings behind the score (`igdb_metadata.total_rating_count`), themes, perspective,
  other names, revision, which dump sets the file matched (`hasheous_metadata`), the collections it is in
  (`user_collections`), when it was added, when the account last played it and for how long (the sum of `duration_ms`
  over `/api/play-sessions?rom_id=`, which needs `devices.read`);
- a Manual button when `path_manual` is set: `RommManual` fetches the file once into the cache and hands it to whatever
  app opens it. The kept file is named by the ROM id and its address, since ids repeat across servers, and sign-out
  deletes them all;
- "More like this" from `/api/roms/{id}/similar`, games the server itself holds, the closest first by the `score` RomM
  gives each (`RommDetails.similar`), and under each tile the first of the `reasons` it gives (`SimilarityReasonSchema`:
  a `facet` and its `value`, strongest first), worded by `rommSimilarWhy`: "Franchise: Metroid", "Series: …" for RomM's
  `collection`, "Tag: …" for a `keyword`, "From the 1990s" for a `decade`, and "Similar on IGDB" for `igdb`, which has no
  value of its own. A game RomM gives no reason for has no line, and a facet this does not know shows its value alone;
- an Achievements tab when the game has RetroAchievements (`merged_ra_metadata.achievements`): `RommAchievements` is a
  `StoreAchievements` source like GOG's and Epic's, and marks as earned what `ra_progression` of `/api/users/me` lists
  for the game's `ra_id`. Badges are the copies the server keeps.

A game the server marks `missing_from_fs` or `is_physical` has no file to send; its page says so and offers no Install.
`youtube_video_id` and the outside links some providers attach are not used: they lead off the server.

The store draws icons in place of many of its labels (filter chips and dropdowns, the tile and page rating, row titles,
the page's tabs and Details rows, and the Play, Install and Resume buttons) while the "Icons instead of words" switch in
the Stores settings is on (`SessionPrefs.rommIcons`, on at first). The icons are listed in `RommIcons`; each keeps the
word it replaces as its content description.

A second row narrows the list (`RommFilter`, sent by `RommApi.roms` under the server's own names): `genres`, `regions`
and `languages`, each repeatable and matching any of the chosen values, `favorite=true`, `statuses=backlogged` and
`statuses=now_playing`, and `last_played=true` or `false`. The values on offer come from `filter_values` of one
`limit=1` page asked for with `with_filter_values=true` for the chosen platforms, kept for the visit; the pages of games
still ask without them. These are the names of RomM's list endpoint (`backend/handler/database/rom_filters.py` in RomM 5);
which of them a server before RomM 5 lacks, and what is then not offered, is under "Server versions". Installed is answered here: the list becomes the installed
games (`RommCatalog.matches` and `sorted`), narrowed by platform, search, genre, region, language and tag from what each
game carries, and what only the server knows about a game is not offered meanwhile. A game installed before it carried
those facts does not match a genre or a tag until its record is brought up to date (see "Installed games' records"). What
narrows the list lasts for the visit.

The installed list is ordered here as well (`RommCatalog.sorted`). Recently added and recently updated go by the
`created_at` and `updated_at` each game's record carries, kept as seconds (`RommCatalog.ADDED`, `UPDATED`),
newest first; they say when the server got or changed the game and not when it was installed here. A game recorded
before it carried them comes after the others, by name, and gets them the next time its page is read
(`RommInstalls.learn`) or its record is brought up to date.

### Installed games' records

A record holds what the server said of the game when it was installed. `RommRefresh.check` brings the records up to date
without the games' pages being opened. It runs with the library (`RommBackend.syncLibrary`): when the library is first
read in a run and at each refresh. It asks `/api/roms?updated_after=<time>` for the games the server changed since the
last check that ran to its end, a hundred at a time, the last changed first (a game changed meanwhile then moves to the
part already read and none is skipped) and ungrouped (`group_by_meta_id=false`), since any version of a game can be the
installed one. Each game in the answer that is installed gets a new record in one write (`RommInstalls.renew`).

The new record is the server's: title, art, description, the details and tags, the other versions, the platform's name
and id and both times, and a detail the server no longer has is dropped. Three things stay as they were
(`RommInstalls.renewed`):

- the file's name, hash and size, whether it is one file, its discs, the extra files that came with it and the platform
  slug. The file on the device is the
  one that was installed whatever the server holds under that id now, and the slug named its folder and its emulator;
- the title id, the save target and its layout once they are known. Save sync names a game's files by them
  (`RommSaves`), so a changed one would leave the saves already synced under the old name; one that was not known is
  filled in, as `RommInstalls.learn` does;
- the cover kept for the Desktop page at install (`CoverArt`), which is not fetched again.

The time is the server's own clock and never the device's: the `Date` of the check's first answer, less a minute
(`RommRefresh.OVERLAP_S`), since the server reads that clock after it has looked the games up and a game changed in
between must not be passed over. A game changed in that minute is answered twice, which changes nothing the second
time. It is kept as seconds under `installs_checked` in the `stores.romm` preferences (`RommPrefs`).
`RommBackend.forget` forgets it (`RommRefresh.clear`) at sign-out and at a sign-in to another server or as another
user, and an answer that arrives after that is dropped.

With no time kept (the first check of a sign-in that already has games, or a server that sends no `Date`) each installed
game is read on its own from `/api/roms/{id}`, once. With nothing installed only `/api/heartbeat` is asked, for the time:
every game installed afterwards is read after that moment, so a first check after many games were installed or matched
at once asks for the changes alone. A list longer than reading each installed game would be (more than a hundred changed
games for each installed one, as after the server scanned everything anew, or from a server that does not know
`updated_after` and answers with all it holds) is not read, and each installed game is read on its own instead.

A check the server does not answer to its end changes no record and keeps the time, so the next one asks for the same
span; it is not tried again by itself. A game the server no longer holds (404) keeps its record, since its file is still
on the device.

Further dropdowns narrow by franchise, company, age rating, player count and tag (`franchises`, `companies`,
`age_ratings`, `player_counts`, `tags`), which the installed list also answers from what each game carries: a game
matches when it has any of the chosen tags, as on the server (`tags_logic` is `any` unless asked otherwise). The tag
menu therefore stays beside Installed, with the values the server offers for the platform. From the server only, the
list narrows by the status the account gave a game (`statuses`, which with `hidden` shows the games hidden on RomM), and by
whether it has saves (`has_saves`), save states (`has_states`), achievements (`has_ra`) or a verified dump (`verified`).
Four more come from the server only, each under the name `RomFilterParams` gives it in RomM 5.4.1
(`backend/handler/database/rom_filters.py`): whether RomM matched the game with a metadata source or with none
(`matched=true` or `false`, one menu), whether it has other versions (`duplicate`, RomM's word for a ROM with sibling
ROMs), whether RomM's own web player runs its platform (`playable`: EmulatorJS or Ruffle, which says nothing of the
emulators here), and whether its file is gone from the server's disk (`missing`). Like the rest of what only the server
knows they are not offered while Installed is chosen. The Sort menu also has
largest first (`fs_size_bytes`). "Random game" opens one game of the chosen platforms or collection
(`/api/roms/random`). The collection menu shows a collection's description, or a smart collection's rule
(`filter_summary`), under its name.

The profile strip says how much the server holds for the account (`/api/stats`: games, platforms and the size of their files).
Under that it says how far the account is with its achievements (`RommAchievements.summary`, `RommProgressLine`): the
`num_awarded` and `max_possible` of every game in the `ra_progression` of `/api/users/me`, added up, and how many games
that is. RomM fills `ra_progression` from the RetroAchievements user the account names, and RetroAchievements itself is
never asked from here; an account that names none has no such line. It is read with the menus, needs `me.read`, and is
kept with the opening page for the next start.

A tile says the game's rating, the year it came out and, unless one platform alone is chosen, its platform
(`RommCatalog.cardLine`); its size is on the game's page.

With icons on, the menus and the filters share one line that scrolls sideways; with icons off they are two.
The platform, collection and value menus are `AnchoredListMenu`s (`BrowseListDropdown`). Up to 24 rows such a menu is
the `AnchoredMenu` every other screen uses, as wide as its widest row. A longer one (a server's every company or tag)
is a `LazyColumn` that holds only the rows in view, so it has the fixed width of 260 dp, the widest a compact menu gets.
A controller starts on its first row and walks it row by row; opened by touch it starts at the row that is picked.
With icons on, the platform menu shows the picture of the one platform chosen in place of its name, and one beside each name in the
list. The picture is the server's own (`RommPlatformIcons`: `/assets/platforms/<fs_slug or slug>.svg`, then `.ico`, the
order RomM's web page uses), kept in the cache once fetched; a platform the server has no picture for keeps its name.
Under each platform's name the menu says what `/api/platforms` sends of it (`rommPlatformAbout`): its `family_name`
(Sony), its `generation` and the size of its files (`fs_size_bytes`), each only when the server has one. RomM takes the
first two from its own table of platforms, where a generation it does not know is -1, and has neither for a platform
outside the table, whose row then says its size alone. The server's `category` (Console, Portable Console, Computer,
Arcade) is not shown: it says little the name does not, and with it every row of the menu took a second line. The menu's
order is as it was, and the kept copy of the opening page carries the three with the platforms. The
store's header has no title, only the search. On a wide pane the search and the display button sit in the
profile strip, between the name and the refresh and sign-out buttons; a narrow pane keeps them on their own row.

While nothing narrows the list (no search, platform, collection or filter), one row sits above it
(`RommShelvesState.store`): Recently added (by `created_at`), 24 games, read when the store is shown and again
after a game was played or a favourite changed. A row with no games is left out. A game in the row and in the
list has a tile in each.

The Library's RomM tab stays once signed in, with nothing installed too. Above the installed games it has
Recently played (`last_played=true`, by `last_played`), Recently added and Favourites (`favorite=true`)
(`RommShelvesState.library`), read with the store's row; a game opened from them has its page in the Library.

The platform menu lists platforms newest first by the year they came out: the server's `first_release_year` when it
sends one (newer than RomM 5.4), else a table in `RommPlatforms`. Platforms neither knows come last, by name.

More than one platform can be chosen at once (`RommBrowseState.chosen`). A row of the menu ticks its platform or takes
it out again (`toggle`) and the menu stays open, as the value menus do; "All platforms" clears the choice. The list is
then of the games of any of the chosen platforms: `platform_ids` is sent once for each, as RomM's list and
`/api/roms/random` both take it, the filter values on offer are those of the chosen platforms together, and the
installed list is narrowed the same way. The menu's button names up to two platforms, or the first and how many more.
What belongs to one platform is there only while exactly one is chosen (`RommBrowseState.platform`): its picture on
the button, and "Get BIOS files".

## Install, play and remove

`RommGameActions` is the game page's action row for this store. With no ROMs folder chosen, Install first sets it to
`ROMs` on internal storage (`FrontEndActivity.useDefaultRoms`), asking for all-files access when that is missing and
falling back to the folder picker when the folder cannot be made. Install queues a `RommInstaller` on the
shared `DownloadQueue`: it downloads `/api/roms/{id}/content/{file}` to a hidden `.romm-<id>.part` beside the
target (resuming with `Range`; a part already at its full size is not asked for again), renames it to
`ROMs/<system>/<file>`, stores RomM's cover where `CoverArt` keeps covers, and records the game in `filesDir/stores/romm/installed.json` (`RommInstalls`). A game counts
as installed while its record and its file both exist. An id from the server is used in file names and addresses only
when it is a plain id (`RommCatalog.ID`); a game with any other id is left out. The system folder comes from the server's
platform slug, so an install is refused unless that names one folder under the ROMs folder (`RommPlatforms.safe`). The
cover is filed under the emulator that plays the game; when another one plays it later, `CoverArt.fetchMissing` asks the
server for it again.

A game that is one file is checked against the SHA-1 RomM recorded for it before it takes its name, and a file that
differs is deleted and the install fails. RomM hashes what is inside a zip, tar, gzip, 7z or bzip2 file (told by the
name or the first bytes) and not the file itself, so those are not checked (`RommInstaller.hashedWhole`), with one
exception: a zip kept as sent that holds one file. RomM's hashes of a zip are of what it holds, so its `crc_hash` for
such a zip is that one file's CRC-32, which the zip's own list records (`RommAdopt.zipCrc`, the way files already on the
device are matched). `RommInstaller.zipAsRecorded` compares the two and fails the install like a wrong SHA-1. A zip
RomM cannot read inside, or whose one file its scan leaves out (`.DS_Store`, `gamelist.xml`, a `.bak` and the like), it
hashes as it is (`get_rom_files` in its `roms_handler.py`), so the zip's own CRC-32 passes too; that one is read from
the file only when the first comparison fails. The data inside the zip is not unpacked to check it. A zip of several
files, a zip that cannot be opened here, one with no `crc_hash`, and every other archive are still not checked, and a
zip that is unpacked for the emulator is not either. Each file of a
game held as a folder is checked the same way against its own `files[].sha1_hash`, and each disc of a disc set against
that disc's hash. The hash is worked out as the bytes arrive (`RommInstaller.download`), with what a resumed part
already held read first, so a large file is not read a second time to check it. A connection that drops, or an answer
that ends before the file does, is carried on from what came up to three times before the install fails.

The record keeps the game's title, art and details, so the Library page lists installed RomM games under a
RomM tab and the store's profile strip counts them. A RomM server can hold tens of thousands of games, so only
installed ones are in the library; the rest are browsed in the store.

A zip or 7z whose platform's emulator does not open it is unpacked instead (`RommArchive`): one file goes straight into the system
folder, several go into a folder named after the archive, and the launch file is a disc sheet (`m3u`, `cue`)
when there is one. The record keeps both the launch file and what to delete. Play calls `Library.launchRom`, the same launch the
Desktop page uses. Uninstall deletes the file and the record. Signing out forgets the records and keeps the
files.

The server names every file and folder, so a name that is already in the ROMs folder may be the user's own.
`RommInstaller` writes over a file, or into a folder, only when an installed game is recorded there or the folder was
made for this game by an install that was cut short (`RommInstalls.claim`, `owns`); anything else stops the install
with the name that is in the way. Clearing an install that failed or was paused removes the folder it made for the game,
as cancelling a running one does (`RommInstalls.claimed`), so the next install is not turned away by what the last one
left. A game kept in a folder is recorded with the files it was given
(`filesDir/stores/romm/files/<id>`), and uninstalling deletes those and then the folders left empty, so a file put
beside them stays. A record older than that list has its folder to itself and loses it whole.

A rar is unpacked the same way by the runtime's `bsdtar` (`StoreArchiveUnpacker`, shared with itch.io and Game Jolt), into a hidden
folder beside the target and then placed like a zip's files. The game's page says to set DroidDeck up first when the runtime is not
there, before anything is downloaded.

What the server sends is bounded wherever it is kept. An archive must fit the free space by its own list, hold no more than
20,000 files, and unpack to no more than it lists (`RommInstaller.unpack`, `room`); a rar that leaves a link behind is refused,
and its work folder is read and removed without following links (`RommArchive.plainFiles`, `remove`). A download stops once it
passes its announced size. Pictures, saves, manuals and BIOS files each have a most they may be (`RommApi.atMost`), and a
zipped save folder is counted as it unpacks (`RommPack`). A file name from the server is used only when it names one file
and holds no control character (`RommCatalog.plainName`).

A ROM that RomM holds as a folder is installed file by file. `RommInstaller` takes the ROM's `files`
(`RommCatalog.files`: each file's id, size and place inside the folder, leaving out files RomM marks as DLC, manuals and other extras
unless they were chosen (see "Extra files"), and any path that would leave the folder) and downloads each from `/api/roms/{id}/content/{name}?file_ids=<id>`, which RomM answers
with that one file and honours `Range` for. The files are the ones the game's page, or `RommSent` for a game sent
from the web page, read from `/api/roms/{id}` when that was in the last ten minutes (`RommDetails.takeFiles`, handed out once),
so such an install does not ask for the ROM again; any other install, and one tried again, asks the server. Files go into `ROMs/<system>/<folder name>/`, each through its own `.part`, and a file
already there at its full size is kept, so a stopped install carries on where it was. Three files come at a time
(`RommInstaller.several`), each resumed, bounded and checked against its SHA-1 as a single file is, with one count of
bytes for the progress. The first file that fails stops the others where they are and fails the install with no record
made; what came of each stays as its `.part` for the next try. The launch file is a disc sheet when there is
one. Several discs with no playlist get an `m3u` named after the folder when the emulator takes one (`RommPlatforms.discs`). A folder
holding one file is installed like a plain file, unless extra files come with it.

### Extra files

RomM gives each of a ROM's files a `category` (`RomFileCategory` in RomM 5.4.1: `game`, `dlc`, `hack`, `manual`,
`walkthrough`, `patch`, `update`, `mod`, `demo`, `translation`, `prototype`, `cheat`, `soundtrack`, `screenshot`). It
comes from the first folder the file lies in below the ROM's own (`dlc`, `updates`, `hacks` and so on) or, for a Switch
file, from the kind of content RomM reads in it; a file at the top of the folder with none counts as `game`.
`RommCatalog.fileSet` splits the list: a file with no category or `game` is the game's own, every other one is an extra,
and when no file is the game's own all of them are.

A game's page lists the extras once `/api/roms/{id}` has answered (`RommDetails.offered`), in an "Extra files" menu in
the row with Install: each by its place in the ROM's folder, with RomM's word for what it is and its size. The ones
ticked (`RommDetails.chosen`, kept for the run) go with the install as `RommCatalog.EXTRAS` in the item, which the record
keeps. `RommInstaller.many` downloads them with the game's files and under the rules those follow: a path is used only
when each part of it is a plain name, each file comes through its own `.part` and is checked against its `sha1_hash`, the
folder has to be the game's own (`RommInstalls.owns`), and the files are listed with the record, so uninstalling deletes
them and leaves a file put beside them. A game of one file that has extras chosen goes into
`ROMs/<system>/<folder name>/` like a game of several files, not into the system folder as a plain file. Only the game's
own files can be the launch file or a disc of its playlist, so an update or a hack of a type the emulator opens is never
what Play starts. A chosen file the server no longer lists stops the install before anything is asked for.

An install tried again carries on in the folder the first try brought files into: an extra that came whole is kept and
recorded even when it is not chosen this time, and the part of one that is not chosen is deleted, so nothing an install
brought stays behind unrecorded.

An installed game's page has the same menu when the game lies in a folder of its own. A page that shows an installed
version it did not read itself (the game was opened by another of its versions) asks for that version's files once
(`RommDetails.listFiles`). The files it has are ticked: the
ones its record lists that are on the device (`RommInstalls.extras`), told by their place and not by RomM's file id, which
a rescan can change. Choosing one it lacks queues a download of that file alone (`RommBackend.addExtras`, a
`RommInstaller` given the files to add): it asks the server for the game's files again unless the page just read them,
takes the file only if the server still lists it under that id at that place, and brings it in through its `.part` with
the SHA-1 check. The game's own files are not asked for, and its record keeps its launch file, root and emulator. The
file is put on the record's list before it is asked for and taken off again if it did not come, so nothing is ever in
the folder without being listed, and uninstalling takes it along. A file at that place which the record does not list is
the user's own, and the download stops with its name. Giving the download up deletes what came of it and leaves the
game. Choosing a ticked file asks once more in its row and then deletes it (`RommInstalls.dropExtra`): only a file the
record lists that is neither one of the game's own nor the launch file, with the folders that leaves empty, and the
record's list and `EXTRAS` are brought up to date. A download that fails says why beside Uninstall.

Which installed games have such a folder is `RommInstalls.folder`: the record lists its files, its root is the folder the
server names the ROM by, and every one of the game's own files as the server lists them now is on that list. A disc set
and an unpacked archive are not, and neither is a game of one file installed without extras, which lies in the system
folder as a plain file. That one is not moved into a folder afterwards: its controller choices (`rom:<path>`), its
cover, the emulator's own settings for it and, with melonDS, its saves are all kept by its path. Its page says to
uninstall it and install it again with the files chosen. The menu needs the server's list, so with the server out of
reach it is not shown, and an extra the server no longer lists stays until the game is uninstalled.

Not done: choosing among the game's own files. The Desktop page lists ROM files three folders deep, so an extra of a type an emulator opens (a
hack, a demo) shows there as a ROM of its own. Nothing is done with an extra once it is placed: an update or DLC still
has to be installed from inside its emulator.

A game whose platform has an emulator but whose file type that emulator does not open says which type it is.

A game nothing here plays can still be installed: its page keeps the reason (no emulator, or the file type)
and offers Install beside it. `RommInstaller` then uses `RommPlatforms.held`: the file, or every
file of a folder or disc set, goes into `ROMs/<platform folder>` as the server sent it (the SHA-1 check still runs,
nothing is unpacked and no `m3u` is written), and the record's emulator is empty. `RommInstalls.Entry.runner` is what
runs an installed game: the recorded emulator, or for such a record whatever plays its file now
(`RommPlatforms.runner`), so a game kept early gets Play once an emulator for it is there. Until then its page
says no emulator plays it and offers Uninstall.

Archives inside a folder ROM are not unpacked.

## ROMs already on the device

`RommAdopt` matches ROM files the user put in the ROMs folder themselves with the games the server holds. The browse bar
offers "Match my ROMs with RomM (N)" while there are files no RomM game is recorded for. Each is asked for by its hash as the
`search_term` of `/api/roms`, which looks a term shaped like a hash up in the hash columns of games and of their files
(`/api/roms/by-hash` is not used: it reads the whole library and took over 25 seconds on one of 20,000 games). A zip
holding one file goes by that file's CRC, read from the zip's own list, any other file by its SHA-1, and a 7z, rar or
other packed file is not asked about. A file is matched only when the search answers with exactly one game. A file the server knows is recorded like a game installed from it, with
its launch file alone as what removing it deletes, and gets the server's cover, page and save sync. A file the server
does not know is remembered by its path, size and time and not asked about again until it changes or the account does.
Counting the files walks the whole ROMs folder, so it is done once for each state of the installed games
(`RommAdopt.count`) and not each time the bar is drawn, and a run writes its matches and misses every 25 files
(`RommInstalls.recordAll`) instead of rewriting the records after each one.

## BIOS files

RomM keeps BIOS and firmware files per platform (`/api/firmware?platform_id=`), and the store fetches the ones the server
holds; it never looks for them anywhere else. With one platform chosen, the browse bar offers "Get BIOS files (N)" for the
files that are not on the device yet, and a game whose system needs a BIOS (`RetroArchCores.firmwareFor`) offers the same on
its page in place of "Needs your own …" when the server has files for its platform.

`RommFirmware.directory` decides where a file goes. A standalone emulator's go to `ROMs/<system>/bios` (or a `BIOS` or
`firmware` folder already there), where `droiddeck-pad-defaults` points DuckStation, ARMSX2 and melonDS. RetroArch's go to
its system folder in the runtime, `~/.config/retroarch/system`, which the same script pins as `system_directory`; that needs
DroidDeck set up first. A platform nothing here plays gets none.

`RommFirmware.fetch` downloads to a hidden part file (`.<name>.part`) and keeps the file only when its size and MD5 are
the ones RomM reports. A file already there at its full size is left alone. Files keep the names they have on the server,
so a BIOS has to carry the name its emulator looks for.

The download is the one a ROM's is (`RommDownload.once`, shared with `RommInstaller`): it asks with `Range` from what the
part holds, works the MD5 out as the bytes arrive, and stops once the file passes the size RomM lists, or 2 GB when it
lists none. A connection that drops after something came is carried on up to three times. When that is not enough the
part stays for the next "Get BIOS files", but only for a file RomM gave both a size and an MD5, since a part can be
trusted only by checking the whole file. Any other failure leaves nothing behind: a refusal from the server, a file
that is too long, the wrong size or the wrong MD5, and a file with no hash to check. A part that was carried on from
and gives the wrong file (the server's file was replaced meanwhile) is dropped and the file is asked for once more,
whole, before the download is called a mismatch.

## Sync

Sign-in asks for a token that may also write (`RommAuth.SCOPES`) and keeps the server's version and the granted scopes with
the account. Everything here needs RomM 4.9 or newer, and each part the scope it writes under (`RommSync.access`): saves
and states `assets.write`, play data `roms.user.write`, favourites and collections `collections.write`. The game's Sync tab
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
| PPSSPP | each `PSP/SAVEDATA/<id>…` folder, as one zip | `PSP/PPSSPP_STATE/<id>_….ppst` |

`droiddeck-pad-defaults` writes RetroArch's save and state folders, with sorting into sub-folders off, into a config that
does not name them yet, and never changes one that does; a RetroArch whose saves were moved or sorted elsewhere is not
synced (`RommSaves.retroArchInPlace`). It sets DuckStation's first card to `PerGameFileTitle`, so a card is named after
the game's file, when the setting is still the default, no card has been written yet and the card folder was not moved;
any other install keeps its setting, and a game is synced only while the setting is `PerGameFileTitle`. melonDS is left as it is: beside the game is where it saves unless told otherwise.
The game's own file is never read, only the names beside it.

PPSSPP names a game's save folders and states by the game's id, which the server reads from the game and sends as
`title_id` (kept with the install record; a game installed before that learns it the next time its page is read,
`RommInstalls.learn`). The folders start with `save_target` when the server gives one with a folder layout, else with
the title id. RomM holds a save as one file, so each folder is kept as a zip named after it in `PSP/.droiddeck-saves`
(`RommPack.stage`, before every sync), the zips are what the engine syncs, and a zip a download replaced is unpacked
over its folder afterwards (`unstage`). A zip is rewritten only when its folder's content changed, told by the same
hash RomM gives a zipped save (the MD5 of its sorted `name:md5` lines), and one that no download touched is never
unpacked, so a save the game wrote meanwhile is not replaced by an older copy. A game the server read no id for says so
on its Sync tab. This is tested against a stand-in server and not yet with PPSSPP on a device.

Dolphin, ARMSX2, Cemu and RPCS3 are not synced: they keep saves on a card shared by every game or in folders this does
not map yet.

`Library.launchRom` calls `RommSync.beforeLaunch`, which fetches the server's files and starts the game when that is done or
after eight seconds, whichever is first. `SessionService.finishSessionStop` uploads what changed; an upload that was missed is
sent at the next app start. Sync can be turned off per game.

The Sync tab lists what the server holds of the game's saves and states (`RommAssets.list`: `/api/saves` and
`/api/states` for the ROM, whatever emulator wrote them, the newest first). A row says whether it is a save or a state,
a save's `slot`, its `file_size_bytes`, its emulator and how old it is, and shows the `screenshot` RomM keeps with a
save or state that has one. The picture is read with the sign-in from the screenshot's `download_path`, only when that
is a path under `/api/` on the server itself (`/api/raw/assets/…` up to RomM 4.9, `/api/screenshots/{id}/content`
since), and is scaled down as it is decoded.

A row chosen once asks, and chosen again within four seconds deletes that one save or state on the server
(`RommAssets.delete`: `POST /api/saves/delete` with `{"saves":[id]}`, `POST /api/states/delete` with `{"states":[id]}`).
It needs `assets.write`; the rows of a sign-in without it cannot be chosen. Nothing on the device is deleted or changed.
So that sync does not put the file straight back, its name is kept with the game (`RommAssets.gone`) and `RommCloud`
lists it as deleted for as long as the server lists no file of that name: an upload then passes over the device's file
while it is as it was last synced (`CloudPlan.up`) and sends it again once the game has written it, which also forgets
the name. "Upload to RomM" on the tab forgets the names first and so sends the file at once. A download reads only what
the server holds, so it never takes the device's file away.

`RommActivity` sends play sessions (`/api/play-sessions`), favourites (the collection RomM marks `is_favorite`, made when
there is none) and a game's backlog, now playing, status and rating. What cannot be sent waits in a file and goes out at the
next session end or app start. Favourites made on the server are merged into the library's. Signing out removes RomM
favourites from the library, so they are not sent to the next server or account; a sign-in reads them back from its server.

The favourites that wait are sent together (`sendFavorites`): the collection is looked up once for a flush, and not at
all when `syncFavorites` has just read it, the games it gains go in one `POST /api/collections/{id}/roms` and the ones
it loses in one `DELETE`. A game changed more than once ends as its last change says. RomM skips ids it does not hold
but answers 400 or 422 to the whole list for one it cannot read, so after such an answer each game is sent on its own
and only the one refused is dropped.

Play data needs only `roms.user.write`, and favourites `collections.write` (`RommSync.access(context, scope)`): a sign-in
that may not write saves still sends its play time, and a request the sign-in may not make waits without holding back the
ones behind it. A 401 or 403 is noted for the scope of the request it answered (`RommSync.denied`): nothing under that
scope is sent for a day or until the next sign-in, and the other scopes keep syncing.

What a refused scope covers is not offered either while the refusal stands (`RommSync.may`: the sign-in holds the scope,
or recorded none, and the server has not refused it). A sign-in made before the scopes were kept records none, so it is
offered everything until the server says otherwise. `collections.write` takes "New collection", Rename and Delete out of
the store's collection menu and bar and greys the collections of a game's Sync tab; `roms.user.write` greys its notes,
"Add a note" and the status rows; `assets.write` greys the rows of the saves and states RomM holds. `GET /api/saves` and
`/api/states` need `assets.read`: when that is what the server refuses, the list is not asked for again and the tab
says "RomM did not list its saves and states" with the advice to sign out and in again, where it used to show nothing.
Sync notes a refused list under both `assets.read` and `assets.write`, since it needs both.

RomM answers 403 in two ways, told apart by the `detail` of the answer at every release from 4.8.0 to 5.4.1:
`Forbidden` for a request outside the token's scopes (`decorators/auth.py`), and `Permission denied for collection with
id '…'` for a collection that belongs to another account (`CollectionPermissionError`). Only the first is a refused
scope (`RommSync.foreign` is the second); a collection that turns out to be someone else's leaves the sign-in as it was. A launch opens its play session
only when the session screen will start that game; with another session running it brings that one back and notes nothing. Taking a session, the server itself sets the game's last-played time to the session's end and marks it
now playing, so nothing else is sent for that; a session sent days late keeps its real time.

The game's Sync tab also sets its difficulty, completion and hidden flag (`PUT /api/roms/{id}/props`), and lists the
account's own collections to put the game in or take it out of (`POST`/`DELETE /api/collections/{id}/roms`); a
collection that turns out to be someone else's is left alone. Notes on the game (`all_user_notes`) are listed there too;
the account's own can be changed and new ones added (`POST /api/roms/{id}/notes`, `PUT .../notes/{note}`), a note's
title being its first words since RomM wants one title per game and account. One of the account's own opens with one
press to what can be done with it: write it again, let every user of the server read it or keep it to the account
(`RommActivity.shareNote`: the same `PUT` with `is_public` alone, so the note's words are not sent), and delete it,
which asks once more first (`dropNote`: `DELETE .../notes/{note}`). Both wait like the rest for a server that is out of
reach, and a note that is gone already (404) is not asked for again. A change the sign-in may not make says
so (sign in again) instead of saying it will go out later.

The store's collection menu also manages the account's own collections other than its favourites
(`RommCollections`, each needing `collections.write`). Its last row, "New collection", asks for a name and makes an
empty collection no other user sees (`POST /api/collections?is_public=false&is_favorite=false`, the name as a form
field). While a collection of the account's own is chosen, the bar has Rename and Delete beside the menu. Rename sends
the new name with `rom_ids=null` (`PUT /api/collections/{id}`): RomM replaces a collection's games with the `rom_ids`
of a change and leaves them alone only for `null` (`update_collection` in its `collections_handler.py`, at every
release from 4.8.0 to 5.4.1), so a list of games is never sent from here, where it may be an old one. Delete asks once
more and then deletes the collection on the server (`DELETE /api/collections/{id}`); the games stay on the server and
on the device. None of the three waits for a server that is out of reach: each says how it went, and the menu is read
again (`RommBrowseState.collectionsChanged`), where a renamed collection keeps its place and one that is gone gives
way to every collection. The copy of the opening page kept on the device (`RommBrowseCache`) is written again with the
menu as it then is, so the next start does not show the old one until the server answers. A name another collection of the account has is turned down before the server is asked:
RomM 5.4 answers it with 409, and an older server with an error of its own for a new collection and not at all for a
renamed one. Smart collections, the favourites and other users' collections have none of this, and neither has the
list while Installed is chosen, since only the server can do it. An empty collection of the account's own is listed
(`RommCatalog.collections`), in the menu and on a game's Sync tab, so that games can be put in one just made; any
other empty collection is still left out.

A sign-in with a code binds its token to a device on the server, and the answer names it; the account keeps that id
(`RommSync.boundDevice`; a sign-in made before it was kept is asked once through `GET /api/client-tokens`). Play time,
saves and presence go under that device, not under a second one registered beside it. The device is told the app's
version and that it takes installs (`PUT /api/devices/{id}`, `capabilities.remote_install`).

`RommSent` installs what RomM's web page sends to the device: at app start and when the library is read it claims the
requests (`POST /api/devices/{id}/installs/claim`, which the server allows only to the token bound to the device, so a
password or pasted-token sign-in gets none), queues each like an install chosen here, and reports `done`,
`already_installed` or `failed` with the reason (`PUT /api/devices/{id}/installs/{request}`). A request names files
(`file_ids`). The web page does not choose them: it sends the ROM's id alone, and the server lists every file of the ROM
that it holds and takes for the game itself, an update or DLC (`select_install_files` in RomM's
`handler/device_install/policy.py`). The ones among them that are extra files here (`RommSent.named`) are installed with
the game, as if they had been ticked on its page, so a game sent from RomM comes with its updates and DLC and without
its hacks, manuals and the rest. An id the ROM no longer lists is passed over. A game that is installed already and
sent again gets the updates and DLC the request names that it lacks (`RommSent.lacking`), by the same job as a file
chosen in the Extra files menu of its page (`RommBackend.addExtras`) and under its rules, so only a game kept in a
folder of its own takes them; the request is reported `done` when they have come and `failed` when one did not. A game
that has them all, or is kept as one plain file, a disc set or an unpacked archive, is reported `already_installed` and
gets nothing added. A game larger than the
free space is reported as failed without being queued, the reason sent is one of the installer's own messages and never
a path on the device, and "Install games sent from RomM" in the Stores settings turns the whole thing off
(`SessionPrefs.rommSent`), which leaves the requests waiting on the server.

While a game runs, `RommActivity.beat` notes every minute that it still does and tells the server this device is
playing it (`POST /api/activity/heartbeat`, `DELETE` when the game ends; an older server than RomM 5.0 has neither and is not asked). A session whose end is never
seen, because the app was killed, is closed at the last such note (`closeLost`) at the next app start or launch instead of
being lost. After a session of a game with achievements, the server is asked to read the account's RetroAchievements
progress again (`POST /api/users/{id}/ra/refresh`, needs `me.write`), and the game's details are read again.

## Controls

A game started from a ROM (a RomM game or a local ROM) can have its own controller choices. They are kept under
two names from `RomScope`: `rom:<path under the ROM folder>` for the game and `system:<folder>` for its system, the
folder being the first name its emulator knows it by, so `psx` and `ps1` are one system. `Library.launchRom`
passes both to the session as `EXTRA_ROM_SCOPE`, kept in `SessionState.romScope`.

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

## Server versions

Sign-in refuses a server older than 4.8, and sync needs 4.9. The server's version is kept with the account at sign-in and
read again from `/api/heartbeat` each time the library is read (`RommSync.learnVersion`), since a server is upgraded
without anyone signing in to it again; `RommSync.version` is the one read last. A server whose version is not known, or
is not a number (a development build), counts as new.

What a later release added is used only on a server that has it (`RommSince`, asked through `RommSync.serverHas`). The
releases were read off RomM's source at each release tag from 4.8.0 to 5.4.1:

| What | Since | On an older server |
|---|---|---|
| `POST`, `DELETE /api/activity/heartbeat` (`backend/endpoints/activity.py`) | 5.0 | not sent; play sessions are recorded as before |
| `has_saves`, `has_states` of `GET /api/roms` | 5.0 | the two filters are not offered |
| `tags` of `GET /api/roms` | 5.0 | `filter_values` has no `tags`, so the menu has nothing to offer and is not shown |
| `GET /api/roms/random` | 5.2 | "Random game" is not offered |
| `GET /api/roms/{id}/similar`, with each game's `score` and `reasons` | 5.3 | not asked, and the page has no "More like this" |
| install requests (`backend/endpoints/device/install.py`) | 5.4 | not asked |

Not checked, since 4.8.0 has them already: `updated_after`, which the refresh of installed games' records asks with
(`Rom.updated_at > updated_after` in `roms_handler.py`), `matched`, `duplicate`, `playable`, `missing`, `verified`,
`has_ra`, `statuses`, `favorite`, `last_played`, `platform_ids` given more than once, a save's `slot`, a save's or state's `screenshot` and
`file_size_bytes`, `POST /api/saves/delete` and `/api/states/delete`, a note's `is_public` and `DELETE /api/roms/{id}/notes/{note}`, `POST`, `PUT` and `DELETE` of a collection, the value filters other than `tags`, `group_by_meta_id`,
`with_char_index`, `with_filter_values`, a ROM's and a file's `crc_hash`, a ROM's `path_cover_small`, the `ra_progression` of `/api/users/me`, a platform's `family_name`, `generation` and `fs_size_bytes`, and the smart collections. Two switches an older server does not know are sent to
it all the same, since leaving them out changes nothing it answers: `with_rom_id_index` (5.1) and
`with_provider_metadata` (not in any release up to 5.4.1). Whether a server takes a sign-in with a code
(`/api/auth/device/init`, 5.4) is told by its answer.

## Tests

`RommAuthTest` (addresses, version gate, password, token and code sign-in against a local socket, a server that answers
only without encryption, a certificate that is refused, the pairing page's address, and the token deleted at sign-out),
`RommCatalogTest` (with the ids and file names that are refused, the files RomM marks as extras, the version an account marked as a game's main one, the installed games narrowed by tag, and ordered by when the server got or changed them, a platform's family, generation and size, and the small cover a tile shows),
`RommPlatformsTest`, `RommInstallsTest`, `RommArchiveTest`, `RommInstallerTest` (a folder game downloaded file by file
from a local socket, three files at a time and never more, one failing file among several, the files a game's page read not asked for again, resumed with `Range`, a whole part not asked for again, a file checked against its SHA-1, a game nothing plays kept as sent, a zip kept as sent that holds one file checked against that file's CRC-32, and
failing without a record when a file is missing, the extra files chosen on a game's page coming with it and going with it, an extra that is not the one
on record or no longer listed, an install tried again keeping the extras that came, an extra added to an installed game and removed from it, a game kept as one file
taking none, an extra never put over the user's own file, one that does not come leaving the game as it was, a file or folder of the user's own left alone, a failed install cleared and installed again, an archive that unpacks to
more than it lists, and removing a folder game). `RommApiTest` (the order asked for, each of several platforms named, what narrows the list under the server's names, and a body longer than it may be).
`RommBrowseTest` (what a refresh reads again, several platforms chosen together for the server's list and for the installed one, a view that is left giving its request up while the next one is answered, the kept first page shown before the server answers and replaced by the
answer, kept when the refresh fails, deleted at sign-out and at a change of server or user, holding no token, not shown
to another user, ignored when it cannot be read, and a tile's small cover read from it as from the server), `RommDetailsTest` (what a game's page reads), `RommAdoptTest` (files already
on the device matched with the server's games), `RommRefreshTest` (installed games' records brought up to date: each game
read once with no check kept, then only what changed since, page by page, a list too long to read, a check that is
refused, the time forgotten with the account, an answer that comes after that, and what a record keeps), `RommSentTest` (games sent from RomM's web page, with the updates and DLC the request names, and a game sent again while installed getting the ones it lacks) and `RommPackTest` (a save
kept as a folder). `RommCollectionsTest` (a collection made, renamed without its games being sent, and deleted, a refusal noted and one about another account's collection not, which collections are the account's own to change, an empty one of its own listed, and the menu read again after a change and kept for the next start). `RommFirmwareTest` (where each
emulator's BIOS goes, which files are missing, a download kept only when it matches, one that drops carried on with
`Range`, a failed one carried on by the next, a part of another file dropped, a file with no hash never carried over, and
a refusal leaving nothing behind). `RommSavesTest` (which files
are a game's), `RommCloudTest` (listing, sending, a newer server copy, a refused token, a full down and up run, what the server holds listed with slot, size and picture, a list the server refuses noted and not asked for again, and a save deleted on the server staying on the device and not sent back until it changes) and
`RommActivityTest` (play time, favourites and status, a note made public or private and deleted, a collection change the sign-in may not make noted as refused and one to another account's collection not, the server's version read again and a server before 5.0 not told what is played, the favourites that wait sent in one request each way, the queue kept while offline, and what a sign-in to another server
forgets). `RomScopeTest` (the names a game's
choices are kept under), `RomControlsTest` (a game's choice before its system's) and the scoped cases of
`InputLibraryTest` (a library stored as before, the order of choices, a removed profile).
