package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import org.json.JSONArray
import org.json.JSONObject

/** [category] is what RomM takes a file for when it is not the game itself: `dlc`, `update`, `hack`, `manual` and the like. */
internal class RommFile(val id: String, val path: String, val size: Long, val sha1: String = "", val category: String = "")

/** A ROM's files: the game's [own], and the [extras] RomM keeps with it and marks as something else. */
internal class RommFiles(val own: List<RommFile>, val extras: List<RommFile>)

/**
 * Another version of a game on the same platform: a region, a revision or a disc RomM holds as its own ROM. [main] is set
 * for the one the account marked as the game's main version on RomM (`is_main_sibling`).
 */
internal class RommVersion(val id: String, val name: String, val main: Boolean = false)

/** An order RomM can list games in; [by] and [dir] are its `order_by` and `order_dir`. */
internal enum class RommSort(val by: String, val dir: String) {
    RATING("average_rating", "desc"),
    NAME("name", "asc"),
    NAME_DESC("name", "desc"),
    NEWEST("first_release_date", "desc"),
    OLDEST("first_release_date", "asc"),
    ADDED("created_at", "desc"),
    UPDATED("updated_at", "desc"),
    PLAYED("last_played", "desc"),
    SIZE("fs_size_bytes", "desc");

    companion object {
        val DEFAULT = RATING
    }
}

/** What narrows the list of games; [installed] is answered from this device, the rest by the server. */
internal data class RommFilter(
    val genres: Set<String> = emptySet(), val regions: Set<String> = emptySet(), val languages: Set<String> = emptySet(),
    val favorite: Boolean = false, val backlog: Boolean = false, val nowPlaying: Boolean = false,
    val played: Boolean? = null, val installed: Boolean = false,
    val franchises: Set<String> = emptySet(), val companies: Set<String> = emptySet(), val ageRatings: Set<String> = emptySet(),
    val players: Set<String> = emptySet(), val tags: Set<String> = emptySet(), val status: String = "",
    val saves: Boolean = false, val achievements: Boolean = false, val verified: Boolean = false,
    val states: Boolean = false, val matched: Boolean? = null, val duplicate: Boolean = false, val playable: Boolean = false, val missing: Boolean = false,
) {
    val active: Boolean get() = this != RommFilter()

    fun parameters(): List<Pair<String, String>> = buildList {
        genres.sorted().forEach { add("genres" to it) }
        regions.sorted().forEach { add("regions" to it) }
        languages.sorted().forEach { add("languages" to it) }
        if (favorite) add("favorite" to "true")
        if (backlog) add("statuses" to "backlogged")
        if (nowPlaying) add("statuses" to "now_playing")
        if (played != null) add("last_played" to played.toString())
        franchises.sorted().forEach { add("franchises" to it) }
        companies.sorted().forEach { add("companies" to it) }
        ageRatings.sorted().forEach { add("age_ratings" to it) }
        players.sorted().forEach { add("player_counts" to it) }
        tags.sorted().forEach { add("tags" to it) }
        if (status.isNotEmpty()) add("statuses" to status)
        if (saves) add("has_saves" to "true")
        if (achievements) add("has_ra" to "true")
        if (verified) add("verified" to "true")
        if (states) add("has_states" to "true")
        if (matched != null) add("matched" to matched.toString())
        if (duplicate) add("duplicate" to "true")
        if (playable) add("playable" to "true")
        if (missing) add("missing" to "true")
    }
}

internal class RommFilterValues(
    val genres: List<String> = emptyList(), val regions: List<String> = emptyList(), val languages: List<String> = emptyList(),
    val franchises: List<String> = emptyList(), val companies: List<String> = emptyList(), val ageRatings: List<String> = emptyList(),
    val players: List<String> = emptyList(), val tags: List<String> = emptyList(),
)

/** [owner] is the id of the account a collection belongs to, [favorite] marks the one RomM keeps favourites in, [about] is its description or a smart one's rule. */
internal class RommCollection(val kind: Kind, val id: String, val name: String, val count: Int, val owner: String = "", val favorite: Boolean = false, val about: String = "") {
    enum class Kind(val parameter: String) { REGULAR("collection_id"), SMART("smart_collection_id") }

    val key: String get() = "${kind.name}:$id"
}

/** How much the server holds, as it counts for the account. */
internal class RommTotals(val games: Int, val platforms: Int, val bytes: Long)

/** [fsSlug] is the platform's folder name on the server, which a server set up with its own folder names keeps apart from [slug]. */
internal class RommPlatform(val id: String, val slug: String, val name: String, val count: Int, val year: Int = 0, val fsSlug: String = "")

internal object RommCatalog {
    const val PLATFORM = "platform"
    const val PLATFORM_NAME = "platformName"
    const val PLATFORM_ID = "platformId"
    const val FILE = "file"
    const val SINGLE = "single"
    const val SHA1 = "sha1"
    const val VERSIONS = "versions"
    const val MAIN = "main"
    const val ALONE = "alone"
    const val DISCS = "discs"
    const val EXTRAS = "extras"
    const val GENRES = "genres"
    const val COMPANIES = "companies"
    const val FRANCHISES = "franchises"
    const val REGIONS = "regions"
    const val LANGUAGES = "languages"
    const val TAGS = "tags"
    const val AGE_RATINGS = "ageRatings"
    const val GAME_MODES = "gameModes"
    const val PLAYERS = "players"
    const val SHOTS = "shots"
    const val RATING = "rating"
    const val RELEASED = "released"
    const val REVISION = "revision"
    const val LAST_PLAYED = "lastPlayed"
    const val ADDED = "added"
    const val UPDATED = "updated"
    const val TITLE_ID = "titleId"
    const val SAVE_TARGET = "saveTarget"
    const val SAVE_LAYOUT = "saveLayout"
    const val UNAVAILABLE = "unavailable"
    const val MISSING = "missing"
    const val PHYSICAL = "physical"

    private const val MOST_SHOTS = 12

    private const val RESOURCES = "/assets/romm/resources/"

    internal val ID = Regex("[A-Za-z0-9_-]{1,64}")

    /** A ROM's id as the server gave it, or empty when it could not be part of a file name. */
    internal fun romId(o: JSONObject): String = o.optString("id").takeIf(ID::matches).orEmpty()

    fun platforms(array: JSONArray?): List<RommPlatform> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id")
            val slug = o.optString("slug")
            if (id.isEmpty() || slug.isEmpty()) null
            else RommPlatform(
                id, slug, text(o, "display_name").ifEmpty { text(o, "custom_name") }.ifEmpty { text(o, "name") }.ifEmpty { slug }, o.optInt("rom_count"),
                o.optInt("first_release_year").takeIf { it > 0 } ?: RommPlatforms.released(slug), text(o, "fs_slug"),
            )
        }.filter { it.count > 0 }.sortedWith(compareByDescending<RommPlatform> { it.year }.thenBy { it.name.lowercase() })
    }

    fun totals(stats: JSONObject?): RommTotals? =
        stats?.takeIf { it.optInt("ROMS") > 0 }?.let { RommTotals(it.optInt("ROMS"), it.optInt("PLATFORMS"), it.optLong("TOTAL_FILESIZE_BYTES")) }

    fun collections(regular: JSONArray?, smart: JSONArray?): List<RommCollection> {
        fun read(array: JSONArray?, kind: RommCollection.Kind) = if (array == null) emptyList() else
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
                val id = o.optString("id")
                val name = text(o, "name")
                val count = if (o.has("rom_count")) o.optInt("rom_count") else o.optJSONArray("rom_ids")?.length() ?: 0
                if (id.isEmpty() || name.isEmpty() || count <= 0) null
                else o.optBoolean("is_favorite") to RommCollection(kind, id, name, count, o.optString("user_id"), o.optBoolean("is_favorite"), text(o, "filter_summary").ifEmpty { text(o, "description") })
            }
        return (read(regular, RommCollection.Kind.REGULAR) + read(smart, RommCollection.Kind.SMART))
            .sortedWith(compareByDescending<Pair<Boolean, RommCollection>> { it.first }.thenBy { it.second.name.lowercase() }).map { it.second }
    }

    fun items(server: String, array: JSONArray?): List<CatalogItem> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { item(server, it) }
    }

    fun item(server: String, rom: JSONObject): CatalogItem? {
        val id = romId(rom)
        val file = text(rom, "fs_name")
        if (id.isEmpty() || file.isEmpty()) return null
        val meta = rom.optJSONObject("metadatum")
        val cover = asset(server, text(rom, "path_cover_large")) ?: asset(server, text(rom, "path_cover_small")) ?: text(rom, "url_cover").takeIf { it.startsWith("http") }?.let { asset(server, it) }
        val shots = strings(rom.optJSONArray("merged_screenshots")).mapNotNull { asset(server, it) }.distinct().take(MOST_SHOTS)
        val companies = strings(meta?.optJSONArray("companies"))
        val released = seconds(meta?.takeUnless { it.isNull("first_release_date") }?.optLong("first_release_date") ?: 0L)
        val rating = meta?.takeUnless { it.isNull("average_rating") }?.optDouble("average_rating")?.takeIf { it.isFinite() && it > 0.0 }
        val facts = mapOf(
            GENRES to packed(strings(meta?.optJSONArray("genres"))),
            COMPANIES to packed(companies),
            FRANCHISES to packed(strings(meta?.optJSONArray("franchises"))),
            REGIONS to packed(strings(rom.optJSONArray("regions"))),
            LANGUAGES to packed(strings(rom.optJSONArray("languages"))),
            TAGS to packed(strings(rom.optJSONArray("tags")).distinct()),
            AGE_RATINGS to packed(strings(meta?.optJSONArray("age_ratings"))),
            GAME_MODES to packed(strings(meta?.optJSONArray("game_modes"))),
            PLAYERS to meta?.let { text(it, "player_count") }.orEmpty(),
            SHOTS to packed(shots),
            RATING to rating?.toString().orEmpty(),
            RELEASED to if (released > 0L) released.toString() else "",
            REVISION to listOf(text(rom, "revision"), text(rom, "version")).filter { it.isNotEmpty() }.distinct().joinToString(" · "),
            UNAVAILABLE to if (rom.optBoolean("is_physical")) PHYSICAL else if (rom.optBoolean("missing_from_fs")) MISSING else "",
            LAST_PLAYED to rom.optJSONObject("rom_user")?.let { text(it, "last_played") }.orEmpty(),
            MAIN to if (rom.optJSONObject("rom_user")?.optBoolean("is_main_sibling") == true) "1" else "",
            ADDED to stamp(rom, "created_at"),
            UPDATED to stamp(rom, "updated_at"),
            TITLE_ID to text(rom, "title_id"),
            SAVE_TARGET to text(rom, "save_target"),
            SAVE_LAYOUT to text(rom, "save_target_layout"),
        ).filterValues { it.isNotEmpty() }
        return CatalogItem(
            store = Store.ROMM,
            id = id,
            title = text(rom, "name").ifEmpty { text(rom, "fs_name_no_ext") }.ifEmpty { file },
            imageUrl = shots.firstOrNull() ?: cover,
            tallImageUrl = cover,
            tags = (listOf(text(rom, "platform_display_name")) + strings(meta?.optJSONArray("genres")).take(3)).filter { it.isNotEmpty() }.joinToString(", "),
            storeUrl = "$server/rom/$id",
            developer = (strings(meta?.optJSONArray("developers")) + companies).firstOrNull().orEmpty(),
            publisher = strings(meta?.optJSONArray("publishers")).firstOrNull().orEmpty(),
            releaseDate = releaseDate(released),
            description = text(rom, "summary"),
            owned = true,
            sizeBytes = rom.optLong("fs_size_bytes"),
            extra = siblings(rom) + facts + mapOf(
                PLATFORM to text(rom, "platform_slug"),
                PLATFORM_NAME to text(rom, "platform_display_name"),
                PLATFORM_ID to text(rom, "platform_id"),
                FILE to file,
                SHA1 to text(rom, "sha1_hash"),
                SINGLE to if (rom.optBoolean("has_multiple_files") || rom.optBoolean("has_nested_single_file")) "0" else "1",
            ),
        )
    }

    private fun stamp(rom: JSONObject, key: String): String = RommDetails.epoch(text(rom, key)).takeIf { it > 0 }?.toString().orEmpty()

    private fun siblings(rom: JSONObject): Map<String, String> {
        val array = rom.optJSONArray("sibling_roms") ?: return emptyMap()
        val list = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
            val id = romId(o)
            val name = text(o, "fs_name_no_ext").ifEmpty { text(o, "name") }
            if (id.isEmpty() || name.isEmpty()) null else JSONArray().put(id).put(name).put(o.optBoolean("is_main_sibling"))
        }
        return if (list.isEmpty()) emptyMap() else mapOf(VERSIONS to JSONArray(list).toString())
    }

    fun versions(item: CatalogItem): List<RommVersion> {
        val array = item.extra[VERSIONS]?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONArray(it) }.map { RommVersion(it.optString(0), it.optString(1), it.optBoolean(2)) }
            .filter { ID.matches(it.id) && it.name.isNotEmpty() && it.id != item.id }
    }

    /** The version of [item]'s game the account marked as its main one on RomM, when that is another than [item]. */
    fun mainVersion(item: CatalogItem): RommVersion? = if (item.extra[MAIN] == "1") null else versions(item).firstOrNull { it.main }

    /**
     * [item] as a game the server was asked for on its own (a random one, one like another), and not as the one it chose
     * among a game's versions, as it does in a list.
     */
    fun alone(item: CatalogItem): CatalogItem = item.copy(extra = item.extra + (ALONE to "1"))

    fun versionName(item: CatalogItem): String =
        item.extra[FILE].orEmpty().let { if (item.extra[SINGLE] == "1") it.substringBeforeLast('.') else it }.ifEmpty { item.title }

    private val discTag = Regex("(?i)\\s*[(\\[](?:disc|disk|cd)[ _-]?\\d+[^)\\]]*[)\\]]")

    fun discSets(versions: List<RommVersion>): List<Pair<String, List<RommVersion>>> =
        versions.filter { discTag.containsMatchIn(it.name) }.groupBy { it.name.replace(discTag, "").trim() }
            .filter { it.key.isNotEmpty() && it.value.size > 1 }.map { (base, discs) -> base to discs.sortedBy { it.name.lowercase() } }

    fun discSet(base: String, discs: List<CatalogItem>): CatalogItem? {
        val first = discs.firstOrNull() ?: return null
        if (discs.size < 2 || discs.any { RommInstaller.fileName(it) == null }) return null
        val list = discs.map { JSONArray().put(it.id).put(it.extra[FILE]).put(it.sizeBytes).put(it.extra[SHA1].orEmpty()) }
        return first.copy(sizeBytes = discs.sumOf { it.sizeBytes }, extra = first.extra + mapOf(FILE to base, SINGLE to "0", SHA1 to "", DISCS to JSONArray(list).toString()))
    }

    fun discs(item: CatalogItem): List<RommFile> {
        val array = item.extra[DISCS]?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONArray(it) }.map { RommFile(it.optString(0), it.optString(1), it.optLong(2).coerceAtLeast(0), it.optString(3)) }
            .filter { ID.matches(it.id) && plainName(it.path) }
    }

    /** Whether [name] names one file or folder and nothing else: it ends up in a path, a playlist and a request. */
    internal fun plainName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.isISOControl() }

    private fun safe(path: String): Boolean = path.length <= 400 && path.split('/').all(::plainName)

    /** The game's own files from a ROM's details, without its DLC, manuals and other extras when RomM marks them. */
    fun files(rom: JSONObject): List<RommFile> = fileSet(rom).own

    /**
     * A ROM's files from its details. RomM gives a file a `category` from the folder it lies in below the ROM's own (`dlc`,
     * `update`, `hack`, `manual` and ten more) or from what the file holds; one with none, or `game`, is the game's own.
     * When no file is, all of them are.
     */
    fun fileSet(rom: JSONObject): RommFiles {
        val array = rom.optJSONArray("files") ?: return RommFiles(emptyList(), emptyList())
        val base = text(rom, "fs_path").trim('/') + "/" + text(rom, "fs_name")
        val all = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id")
            val name = text(o, "file_name")
            if (id.isEmpty() || name.isEmpty() || o.optBoolean("missing_from_fs")) return@mapNotNull null
            val full = text(o, "full_path").ifEmpty { text(o, "file_path").trim('/') + "/" + name }.trimStart('/')
            val path = if (full.startsWith("$base/")) full.removePrefix("$base/") else name
            if (safe(path)) RommFile(id, path, o.optLong("file_size_bytes").coerceAtLeast(0), text(o, "sha1_hash"), text(o, "category").lowercase()) else null
        }.distinctBy { it.path.lowercase() }
        val (own, extras) = all.partition { it.category.isEmpty() || it.category == "game" }
        return if (own.isEmpty()) RommFiles(all, emptyList()) else RommFiles(own, extras)
    }

    /** [item] as it is installed with the extra files of those [ids], or with none. */
    fun withExtras(item: CatalogItem, ids: Set<String>): CatalogItem =
        item.copy(extra = if (ids.isEmpty()) item.extra - EXTRAS else item.extra + (EXTRAS to JSONArray(ids.sorted()).toString()))

    fun chosenExtras(item: CatalogItem): Set<String> = list(item, EXTRAS).toSet()

    internal fun text(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key).trim()

    internal fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotEmpty() }

    internal fun asset(server: String, path: String): String? = when {
        path.isEmpty() -> null
        // Only the user's own server is ever asked without encryption, and only when the user signed in to it that way.
        path.startsWith("http://") && (server.startsWith("https://") || host(path) != host(server)) -> "https://" + path.removePrefix("http://")
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/") -> server + path
        else -> server + RESOURCES + path
    }?.replace(" ", "%20")

    private fun host(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()

    fun filterValues(page: JSONObject?): RommFilterValues {
        val values = page?.optJSONObject("filter_values") ?: return RommFilterValues()
        fun of(key: String) = strings(values.optJSONArray(key))
        return RommFilterValues(of("genres"), of("regions"), of("languages"), of("franchises"), of("companies"), of("age_ratings"), of("player_counts"), of("tags"))
    }

    fun matches(item: CatalogItem, platformIds: Set<String>, search: String, filter: RommFilter): Boolean {
        fun any(wanted: Set<String>, key: String) = wanted.isEmpty() || list(item, key).any { it in wanted }
        return (platformIds.isEmpty() || item.extra[PLATFORM_ID] in platformIds) &&
            (search.isBlank() || item.title.contains(search.trim(), ignoreCase = true)) &&
            any(filter.genres, GENRES) && any(filter.regions, REGIONS) && any(filter.languages, LANGUAGES) &&
            any(filter.franchises, FRANCHISES) && any(filter.companies, COMPANIES) && any(filter.ageRatings, AGE_RATINGS) &&
            any(filter.tags, TAGS) && (filter.players.isEmpty() || item.extra[PLAYERS] in filter.players)
    }

    fun sorted(items: List<CatalogItem>, sort: RommSort): List<CatalogItem> {
        val byName = compareBy<CatalogItem> { it.title.lowercase() }
        fun released(item: CatalogItem) = item.extra[RELEASED]?.toLongOrNull()
        fun latest(key: String) = items.sortedWith(compareByDescending<CatalogItem> { it.extra[key]?.toLongOrNull() ?: Long.MIN_VALUE }.then(byName))
        return when (sort) {
            RommSort.NAME -> items.sortedWith(byName)
            RommSort.ADDED -> latest(ADDED)
            RommSort.UPDATED -> latest(UPDATED)
            RommSort.NAME_DESC -> items.sortedWith(byName.reversed())
            RommSort.RATING -> items.sortedWith(compareByDescending<CatalogItem> { rating(it) ?: -1f }.then(byName))
            RommSort.NEWEST -> items.sortedWith(compareByDescending<CatalogItem> { released(it) ?: Long.MIN_VALUE }.then(byName))
            RommSort.OLDEST -> items.sortedWith(compareBy<CatalogItem> { released(it) ?: Long.MAX_VALUE }.then(byName))
            RommSort.PLAYED -> items.sortedWith(compareByDescending<CatalogItem> { it.extra[LAST_PLAYED].orEmpty() }.then(byName))
            RommSort.SIZE -> items.sortedWith(compareByDescending<CatalogItem> { it.sizeBytes }.then(byName))
        }
    }

    private fun packed(values: List<String>): String = if (values.isEmpty()) "" else JSONArray(values).toString()

    fun list(item: CatalogItem, key: String): List<String> =
        strings(item.extra[key]?.let { runCatching { JSONArray(it) }.getOrNull() })

    /** The game's rating out of ten; RomM keeps it out of a hundred. */
    fun rating(item: CatalogItem): Float? =
        item.extra[RATING]?.toFloatOrNull()?.takeIf { it > 0f }?.let { if (it > 10f) it / 10f else it }

    fun ratingText(item: CatalogItem): String? = rating(item)?.let { String.format(java.util.Locale.ROOT, "%.1f", it) }

    fun cardLine(item: CatalogItem, withPlatform: Boolean, rated: Boolean = true): String = listOfNotNull(
        ratingText(item)?.takeIf { rated }?.let { "★ $it" },
        year(item)?.toString(),
        item.extra[PLATFORM]?.takeIf { withPlatform && it.isNotEmpty() }?.uppercase(),
    ).joinToString(" · ")

    fun year(item: CatalogItem): Int? =
        item.extra[RELEASED]?.toLongOrNull()?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneOffset.UTC).year }

    private fun seconds(stamp: Long): Long = if (stamp < 100_000_000_000L) stamp else stamp / 1000

    private fun releaseDate(seconds: Long): String =
        if (seconds <= 0L) "" else java.time.Instant.ofEpochSecond(seconds).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
}
