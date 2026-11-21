package com.droiddeck.launcher.stores.romm

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal class RommAnswer(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
    val json: JSONObject? get() = runCatching { JSONObject(body) }.getOrNull()
    val array: JSONArray? get() = runCatching { JSONArray(body) }.getOrNull()
}

internal object RommApi {
    const val USER_AGENT = "DroidDeck"
    const val PAGE = 60

    private const val MAX_BYTES = 8_000_000

    fun heartbeat(server: String): RommAnswer = get(server, "/api/heartbeat", "")

    fun me(server: String, token: String): RommAnswer = get(server, "/api/users/me", token)

    fun platforms(server: String, token: String): RommAnswer = get(server, "/api/platforms", token)

    fun roms(server: String, token: String, platformId: String?, search: String?, offset: Int, limit: Int = PAGE): RommAnswer {
        val query = StringBuilder("limit=$limit&offset=$offset&with_char_index=false&with_filter_values=false&with_rom_id_index=false&order_by=name&order_dir=asc")
        if (!platformId.isNullOrEmpty()) query.append("&platform_ids=").append(encode(platformId))
        if (!search.isNullOrBlank()) query.append("&search_term=").append(encode(search))
        return get(server, "/api/roms?$query", token)
    }

    fun contentUrl(server: String, romId: String, fileName: String): String =
        "$server/api/roms/${encode(romId)}/content/${encode(fileName).replace("+", "%20")}"

    fun open(url: String, token: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", USER_AGENT)
        if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun get(server: String, path: String, token: String): RommAnswer {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(server + path, token).apply {
                readTimeout = 25_000
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            RommAnswer(code, stream?.use { readBounded(it) }.orEmpty())
        } catch (e: Exception) {
            RommAnswer(0, "")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun readBounded(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < MAX_BYTES) {
            val read = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
