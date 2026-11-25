package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import org.json.JSONArray
import org.json.JSONObject

internal class RommFile(val id: String, val path: String, val size: Long)

/** Another version of a game on the same platform: a region, a revision or a disc RomM holds as its own ROM. */
internal class RommVersion(val id: String, val name: String)

/** An order RomM can list games in; [by] and [dir] are its `order_by` and `order_dir`. */
internal enum class RommSort(val by: String, val dir: String) {
    RATING("average_rating", "desc"),
    NAME("name", "asc"),
    NAME_DESC("name", "desc"),
    NEWEST("first_release_date", "desc"),
    OLDEST("first_release_date", "asc"),
    ADDED("created_at", "desc"),
    PLAYED("last_played", "desc");

    companion object {
        val DEFAULT = RATING
    }
}

/** What narrows the list of games; [installed] is answered from this device, the rest by the server. */
internal data class RommFilter(
    val genres: Set<String> = emptySet(), val regions: Set<String> = emptySet(), val languages: Set<String> = emptySet(),
    val favorite: Boolean = false, val backlog: Boolean = false, val nowPlaying: Boolean = false,
    val played: Boolean? = null, val installed: Boolean = false,
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
    }
}

internal class RommFilterValues(val genres: List<String> = emptyList(), val regions: List<String> = emptyList(), val languages: List<String> = emptyList())

internal class RommCollection(val kind: Kind, val id: String, val name: String, val count: Int) {
    enum class Kind(val parameter: String) { REGULAR("collection_id"), SMART("smart_collection_id") }

    val key: String get() = "${kind.name}:$id"
}

internal class RommPlatform(val id: String, val slug: String, val name: String, val count: Int)

internal object RommCatalog {
    const val PLATFORM = "platform"
    const val PLATFORM_NAME = "platformName"
    const val PLATFORM_ID = "platformId"
    const val FILE = "file"
    const val SINGLE = "single"
    const val SHA1 = "sha1"
    const val VERSIONS = "versions"
    const val DISCS = "discs"
    const val GENRES = "genres"
    const val COMPANIES = "companies"
    const val FRANCHISES = "franchises"
    const val REGIONS = "regions"
    const val LANGUAGES = "languages"
    const val AGE_RATINGS = "ageRatings"
    const val GAME_MODES = "gameModes"
    const val PLAYERS = "players"
    const val SHOTS = "shots"
    const val RATING = "rating"
    const val RELEASED = "released"
    const val REVISION = "revision"
    const val LAST_PLAYED = "lastPlayed"

    private const val MOST_SHOTS = 12

    private const val RESOURCES = "/assets/romm/resources/"

    private val ID = Regex("[A-Za-z0-9_-]{1,64}")

    /** A ROM's id as the server gave it, or empty when it could not be part of a file name. */
    internal fun romId(o: JSONObject): String = o.optString("id").takeIf(ID::matches).orEmpty()

    fun platforms(array: JSONArray?): List<RommPlatform> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id")
            val slug = o.optString("slug")
            if (id.isEmpty() || slug.isEmpty()) null
            else RommPlatform(id, slug, text(o, "display_name").ifEmpty { text(o, "custom_name") }.ifEmpty { text(o, "name") }.ifEmpty { slug }, o.optInt("rom_count"))
        }.filter { it.count > 0 }.sortedWith(compareByDescending<RommPlatform> { RommPlatforms.released(it.slug) }.thenBy { it.name.lowercase() })
    }

    fun collections(regular: JSONArray?, smart: JSONArray?): List<RommCollection> {
        fun read(array: JSONArray?, kind: RommCollection.Kind) = if (array == null) emptyList() else
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
                val id = o.optString("id")
                val name = text(o, "name")
                val count = if (o.has("rom_count")) o.optInt("rom_count") else o.optJSONArray("rom_ids")?.length() ?: 0
                if (id.isEmpty() || name.isEmpty() || count <= 0) null else o.optBoolean("is_favorite") to RommCollection(kind, id, name, count)
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
        val cover = asset(server, text(rom, "path_cover_large")) ?: asset(server, text(rom, "path_cover_small")) ?: text(rom, "url_cover").takeIf { it.startsWith("http") }
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
            AGE_RATINGS to packed(strings(meta?.optJSONArray("age_ratings"))),
            GAME_MODES to packed(strings(meta?.optJSONArray("game_modes"))),
            PLAYERS to meta?.let { text(it, "player_count") }.orEmpty(),
            SHOTS to packed(shots),
            RATING to rating?.toString().orEmpty(),
            RELEASED to if (released > 0L) released.toString() else "",
            REVISION to text(rom, "revision"),
            LAST_PLAYED to rom.optJSONObject("rom_user")?.let { text(it, "last_played") }.orEmpty(),
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

    private fun siblings(rom: JSONObject): Map<String, String> {
        val array = rom.optJSONArray("sibling_roms") ?: return emptyMap()
        val list = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
            val id = romId(o)
            val name = text(o, "fs_name_no_ext").ifEmpty { text(o, "name") }
            if (id.isEmpty() || name.isEmpty()) null else JSONArray().put(id).put(name)
        }
        return if (list.isEmpty()) emptyMap() else mapOf(VERSIONS to JSONArray(list).toString())
    }

    fun versions(item: CatalogItem): List<RommVersion> {
        val array = item.extra[VERSIONS]?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONArray(it) }.map { RommVersion(it.optString(0), it.optString(1)) }
            .filter { ID.matches(it.id) && it.name.isNotEmpty() && it.id != item.id }
    }

    fun versionName(item: CatalogItem): String =
        item.extra[FILE].orEmpty().let { if (item.extra[SINGLE] == "1") it.substringBeforeLast('.') else it }.ifEmpty { item.title }

    private val discTag = Regex("(?i)\\s*[(\\[](?:disc|disk|cd)[ _-]?\\d+[^)\\]]*[)\\]]")

    fun discSets(versions: List<RommVersion>): List<Pair<String, List<RommVersion>>> =
        versions.filter { discTag.containsMatchIn(it.name) }.groupBy { it.name.replace(discTag, "").trim() }
            .filter { it.key.isNotEmpty() && it.value.size > 1 }.map { (base, discs) -> base to discs.sortedBy { it.name.lowercase() } }

    fun discSet(base: String, discs: List<CatalogItem>): CatalogItem? {
        val first = discs.firstOrNull() ?: return null
        if (discs.size < 2 || discs.any { RommInstaller.fileName(it) == null }) return null
        val list = discs.map { JSONArray().put(it.id).put(it.extra[FILE]).put(it.sizeBytes) }
        return first.copy(sizeBytes = discs.sumOf { it.sizeBytes }, extra = first.extra + mapOf(FILE to base, SINGLE to "0", SHA1 to "", DISCS to JSONArray(list).toString()))
    }

    fun discs(item: CatalogItem): List<RommFile> {
        val array = item.extra[DISCS]?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONArray(it) }.map { RommFile(it.optString(0), it.optString(1), it.optLong(2).coerceAtLeast(0)) }
            .filter { ID.matches(it.id) && it.path.isNotBlank() && it.path != "." && it.path != ".." && '/' !in it.path && '\\' !in it.path }
    }

    private fun safe(path: String): Boolean =
        path.length <= 400 && path.split('/').none { it.isEmpty() || it == "." || it == ".." || '\\' in it }

    /** The game's own files from a ROM's details, without its DLC, manuals and other extras when RomM marks them. */
    fun files(rom: JSONObject): List<RommFile> {
        val array = rom.optJSONArray("files") ?: return emptyList()
        val base = text(rom, "fs_path").trim('/') + "/" + text(rom, "fs_name")
        val all = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id")
            val name = text(o, "file_name")
            if (id.isEmpty() || name.isEmpty() || o.optBoolean("missing_from_fs")) return@mapNotNull null
            val full = text(o, "full_path").ifEmpty { text(o, "file_path").trim('/') + "/" + name }.trimStart('/')
            val path = if (full.startsWith("$base/")) full.removePrefix("$base/") else name
            if (safe(path)) text(o, "category") to RommFile(id, path, o.optLong("file_size_bytes").coerceAtLeast(0)) else null
        }
        val game = all.filter { it.first.isEmpty() || it.first == "game" }
        return game.ifEmpty { all }.map { it.second }.distinctBy { it.path.lowercase() }
    }

    private fun text(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key).trim()

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotEmpty() }

    private fun asset(server: String, path: String): String? = when {
        path.isEmpty() -> null
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/") -> server + path
        else -> server + RESOURCES + path
    }?.replace(" ", "%20")

    fun filterValues(page: JSONObject?): RommFilterValues {
        val values = page?.optJSONObject("filter_values") ?: return RommFilterValues()
        return RommFilterValues(strings(values.optJSONArray("genres")), strings(values.optJSONArray("regions")), strings(values.optJSONArray("languages")))
    }

    fun matches(item: CatalogItem, platformId: String?, search: String, filter: RommFilter): Boolean {
        fun any(wanted: Set<String>, key: String) = wanted.isEmpty() || list(item, key).any { it in wanted }
        return (platformId == null || item.extra[PLATFORM_ID] == platformId) &&
            (search.isBlank() || item.title.contains(search.trim(), ignoreCase = true)) &&
            any(filter.genres, GENRES) && any(filter.regions, REGIONS) && any(filter.languages, LANGUAGES)
    }

    fun sorted(items: List<CatalogItem>, sort: RommSort): List<CatalogItem> {
        val byName = compareBy<CatalogItem> { it.title.lowercase() }
        fun released(item: CatalogItem) = item.extra[RELEASED]?.toLongOrNull()
        return when (sort) {
            RommSort.NAME, RommSort.ADDED -> items.sortedWith(byName)
            RommSort.NAME_DESC -> items.sortedWith(byName.reversed())
            RommSort.RATING -> items.sortedWith(compareByDescending<CatalogItem> { rating(it) ?: -1f }.then(byName))
            RommSort.NEWEST -> items.sortedWith(compareByDescending<CatalogItem> { released(it) ?: Long.MIN_VALUE }.then(byName))
            RommSort.OLDEST -> items.sortedWith(compareBy<CatalogItem> { released(it) ?: Long.MAX_VALUE }.then(byName))
            RommSort.PLAYED -> items.sortedWith(compareByDescending<CatalogItem> { it.extra[LAST_PLAYED].orEmpty() }.then(byName))
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
