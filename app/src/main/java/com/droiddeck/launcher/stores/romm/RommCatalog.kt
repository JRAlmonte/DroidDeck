package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import org.json.JSONArray
import org.json.JSONObject

/** One file of a ROM; [path] is where it sits inside the game's folder. */
internal class RommFile(val id: String, val path: String, val size: Long)

/** Another version of a game on the same platform: a region, a revision or a disc RomM holds as its own ROM. */
internal class RommVersion(val id: String, val name: String)

internal class RommPlatform(val id: String, val slug: String, val name: String, val count: Int)

internal object RommCatalog {
    const val PLATFORM = "platform"
    const val PLATFORM_NAME = "platformName"
    const val FILE = "file"
    const val SINGLE = "single"
    const val SHA1 = "sha1"
    const val VERSIONS = "versions"
    const val DISCS = "discs"

    private const val RESOURCES = "/assets/romm/resources/"

    fun platforms(array: JSONArray?): List<RommPlatform> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id")
            val slug = o.optString("slug")
            if (id.isEmpty() || slug.isEmpty()) null
            else RommPlatform(id, slug, text(o, "display_name").ifEmpty { text(o, "custom_name") }.ifEmpty { text(o, "name") }.ifEmpty { slug }, o.optInt("rom_count"))
        }.filter { it.count > 0 }.sortedWith(compareByDescending<RommPlatform> { RommPlatforms.released(it.slug) }.thenBy { it.name.lowercase() })
    }

    fun items(server: String, array: JSONArray?): List<CatalogItem> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { item(server, it) }
    }

    fun item(server: String, rom: JSONObject): CatalogItem? {
        val id = rom.optString("id")
        val file = text(rom, "fs_name")
        if (id.isEmpty() || file.isEmpty()) return null
        val meta = rom.optJSONObject("metadatum")
        val cover = asset(server, text(rom, "path_cover_large")) ?: asset(server, text(rom, "path_cover_small")) ?: text(rom, "url_cover").takeIf { it.startsWith("http") }
        val shot = rom.optJSONArray("merged_screenshots")?.optString(0).orEmpty()
        return CatalogItem(
            store = Store.ROMM,
            id = id,
            title = text(rom, "name").ifEmpty { text(rom, "fs_name_no_ext") }.ifEmpty { file },
            imageUrl = asset(server, shot) ?: cover,
            tallImageUrl = cover,
            tags = (listOf(text(rom, "platform_display_name")) + strings(meta?.optJSONArray("genres")).take(3)).filter { it.isNotEmpty() }.joinToString(", "),
            storeUrl = "$server/rom/$id",
            developer = strings(meta?.optJSONArray("companies")).firstOrNull().orEmpty(),
            releaseDate = releaseDate(meta?.optLong("first_release_date") ?: 0L),
            description = text(rom, "summary"),
            owned = true,
            sizeBytes = rom.optLong("fs_size_bytes"),
            extra = siblings(rom) + mapOf(
                PLATFORM to text(rom, "platform_slug"),
                PLATFORM_NAME to text(rom, "platform_display_name"),
                FILE to file,
                SHA1 to text(rom, "sha1_hash"),
                SINGLE to if (rom.optBoolean("has_multiple_files") || rom.optBoolean("has_nested_single_file")) "0" else "1",
            ),
        )
    }

    private fun siblings(rom: JSONObject): Map<String, String> {
        val array = rom.optJSONArray("sibling_roms") ?: return emptyMap()
        val list = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id")
            val name = text(o, "fs_name_no_ext").ifEmpty { text(o, "name") }
            if (id.isEmpty() || name.isEmpty()) null else JSONArray().put(id).put(name)
        }
        return if (list.isEmpty()) emptyMap() else mapOf(VERSIONS to JSONArray(list).toString())
    }

    /** The other versions RomM groups with this game, in RomM's order. */
    fun versions(item: CatalogItem): List<RommVersion> {
        val array = item.extra[VERSIONS]?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONArray(it) }.map { RommVersion(it.optString(0), it.optString(1)) }
            .filter { it.id.isNotEmpty() && it.name.isNotEmpty() && it.id != item.id }
    }

    /** What tells this version from the others: its file name with the region, revision and disc tags. */
    fun versionName(item: CatalogItem): String =
        item.extra[FILE].orEmpty().let { if (item.extra[SINGLE] == "1") it.substringBeforeLast('.') else it }.ifEmpty { item.title }

    private val discTag = Regex("(?i)\\s*[(\\[](?:disc|disk|cd)[ _-]?\\d+[^)\\]]*[)\\]]")

    /** The versions that are the discs of one game: names alike but for the disc tag, in disc order, by the name without it. */
    fun discSets(versions: List<RommVersion>): List<Pair<String, List<RommVersion>>> =
        versions.filter { discTag.containsMatchIn(it.name) }.groupBy { it.name.replace(discTag, "").trim() }
            .filter { it.key.isNotEmpty() && it.value.size > 1 }.map { (base, discs) -> base to discs.sortedBy { it.name.lowercase() } }

    /** One game made of [discs], each a ROM of one file, to install together under [base]; null when a disc is not one file. */
    fun discSet(base: String, discs: List<CatalogItem>): CatalogItem? {
        val first = discs.firstOrNull() ?: return null
        if (discs.size < 2 || discs.any { RommInstaller.fileName(it) == null }) return null
        val list = discs.map { JSONArray().put(it.id).put(it.extra[FILE]).put(it.sizeBytes) }
        return first.copy(sizeBytes = discs.sumOf { it.sizeBytes }, extra = first.extra + mapOf(FILE to base, SINGLE to "0", SHA1 to "", DISCS to JSONArray(list).toString()))
    }

    /** The discs of an item made by [discSet]: each ROM's id, its file name and its size. */
    fun discs(item: CatalogItem): List<RommFile> {
        val array = item.extra[DISCS]?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONArray(it) }.map { RommFile(it.optString(0), it.optString(1), it.optLong(2).coerceAtLeast(0)) }
            .filter { it.id.isNotEmpty() && it.path.isNotBlank() && it.path != "." && it.path != ".." && '/' !in it.path && '\\' !in it.path }
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

    private fun releaseDate(stamp: Long): String {
        if (stamp <= 0L) return ""
        val millis = if (stamp < 100_000_000_000L) stamp * 1000 else stamp
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(millis))
    }
}
