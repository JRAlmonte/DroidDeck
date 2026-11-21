package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import org.json.JSONArray
import org.json.JSONObject

internal class RommPlatform(val id: String, val slug: String, val name: String, val count: Int)

internal object RommCatalog {
    const val PLATFORM = "platform"
    const val PLATFORM_NAME = "platformName"
    const val FILE = "file"
    const val SINGLE = "single"

    private const val RESOURCES = "/assets/romm/resources/"

    fun platforms(array: JSONArray?): List<RommPlatform> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id")
            val slug = o.optString("slug")
            if (id.isEmpty() || slug.isEmpty()) null
            else RommPlatform(id, slug, text(o, "display_name").ifEmpty { text(o, "custom_name") }.ifEmpty { text(o, "name") }.ifEmpty { slug }, o.optInt("rom_count"))
        }.sortedBy { it.name.lowercase() }
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
            tags = strings(meta?.optJSONArray("genres")).take(4).joinToString(", "),
            storeUrl = "$server/rom/$id",
            developer = strings(meta?.optJSONArray("companies")).firstOrNull().orEmpty(),
            releaseDate = releaseDate(meta?.optLong("first_release_date") ?: 0L),
            description = text(rom, "summary"),
            owned = true,
            sizeBytes = rom.optLong("fs_size_bytes"),
            extra = mapOf(
                PLATFORM to text(rom, "platform_slug"),
                PLATFORM_NAME to text(rom, "platform_display_name"),
                FILE to file,
                SINGLE to if (rom.optBoolean("has_multiple_files") || rom.optBoolean("has_nested_single_file")) "0" else "1",
            ),
        )
    }

    private fun text(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key).trim()

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotEmpty() }

    private fun asset(server: String, path: String): String? = when {
        path.isEmpty() -> null
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/") -> server + path
        else -> server + RESOURCES + path
    }

    private fun releaseDate(stamp: Long): String {
        if (stamp <= 0L) return ""
        val millis = if (stamp < 100_000_000_000L) stamp * 1000 else stamp
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(millis))
    }
}
