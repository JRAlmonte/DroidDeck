package com.droiddeck.launcher.stores.romm

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** [untrusted] says the server answered over TLS with a certificate this device refuses; [code] is then 0. */
internal class RommAnswer(val code: Int, val body: String, val untrusted: Boolean = false) {
    val ok: Boolean get() = code in 200..299
    val json: JSONObject? get() = runCatching { JSONObject(body) }.getOrNull()
    val array: JSONArray? get() = runCatching { JSONArray(body) }.getOrNull()
}

internal object RommApi {
    const val USER_AGENT = "DroidDeck"
    const val PAGE = 60

    private const val MAX_BYTES = 8_000_000
    // A request that is not a download gives up on an address nothing answers at sooner, so the store says so in seconds.
    private const val API_CONNECT_MS = 8_000
    const val MOST_PICTURE = 16 * 1024 * 1024
    const val MOST_SAVE = 256 * 1024 * 1024

    fun bearer(token: String): String = if (token.isEmpty()) "" else "Bearer $token"

    fun basic(username: String, password: String): String =
        "Basic " + java.util.Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))

    fun heartbeat(server: String): RommAnswer = send(server, "/api/heartbeat", "", null)

    fun me(server: String, authorization: String): RommAnswer = send(server, "/api/users/me", authorization, null)

    fun createToken(server: String, authorization: String, name: String, scopes: List<String>): RommAnswer =
        send(server, "/api/client-tokens", authorization, JSONObject().put("name", name).put("scopes", JSONArray(scopes)).put("expires_in", "never").toString())

    fun tokens(server: String, token: String): RommAnswer = get(server, "/api/client-tokens", token)

    fun deleteToken(server: String, token: String, id: Long): RommAnswer = call(server, "/api/client-tokens/$id", token, "DELETE")

    fun deviceInit(server: String, deviceId: String, name: String, scopes: List<String>): RommAnswer = send(
        server, "/api/auth/device/init", "",
        JSONObject().put("client_device_identifier", deviceId).put("name", name).put("client", USER_AGENT).put("platform", "android")
            .put("requested_scopes", JSONArray(scopes)).toString(),
    )

    fun deviceToken(server: String, deviceCode: String): RommAnswer =
        send(server, "/api/auth/device/token", "", JSONObject().put("device_code", deviceCode).toString())

    fun platforms(server: String, token: String): RommAnswer = get(server, "/api/platforms", token)

    fun stats(server: String, token: String): RommAnswer = get(server, "/api/stats", token)

    fun collections(server: String, token: String): RommAnswer = get(server, "/api/collections", token)

    fun smartCollections(server: String, token: String): RommAnswer = get(server, "/api/collections/smart", token)

    /** One page of games; versions of the same game on a platform come as one entry that names the others. */
    fun roms(server: String, token: String, platformId: String?, search: String?, offset: Int, limit: Int = PAGE, sort: RommSort?, collection: RommCollection? = null, filter: RommFilter = RommFilter(), withFilterValues: Boolean = false): RommAnswer {
        val query = StringBuilder("limit=$limit&offset=$offset&with_char_index=false&with_filter_values=$withFilterValues&with_rom_id_index=false&with_provider_metadata=false&group_by_meta_id=true")
        if (sort != null) query.append("&order_by=${sort.by}&order_dir=${sort.dir}")
        if (!platformId.isNullOrEmpty()) query.append("&platform_ids=").append(encode(platformId))
        if (collection != null) query.append("&${collection.kind.parameter}=").append(encode(collection.id))
        if (!search.isNullOrBlank()) query.append("&search_term=").append(encode(search))
        for ((name, value) in filter.parameters()) query.append("&$name=").append(encode(value))
        return get(server, "/api/roms?$query", token)
    }

    fun rom(server: String, token: String, id: String): RommAnswer = get(server, "/api/roms/${encode(id)}", token)

    fun random(server: String, token: String, platformId: String?, collection: RommCollection?): RommAnswer {
        val query = listOfNotNull(platformId?.takeIf { it.isNotEmpty() }?.let { "platform_ids=" + encode(it) }, collection?.let { "${it.kind.parameter}=" + encode(it.id) })
        return get(server, "/api/roms/random" + if (query.isEmpty()) "" else "?" + query.joinToString("&"), token)
    }

    fun similar(server: String, token: String, id: String, limit: Int = 12): RommAnswer = get(server, "/api/roms/${encode(id)}/similar?limit=$limit", token)

    fun playSessions(server: String, token: String, romId: String): RommAnswer = get(server, "/api/play-sessions?rom_id=${encode(romId)}&limit=1000", token)

    /** A ROM's content; [fileId] picks one file of a ROM held as several, which RomM then serves as it is. */
    fun contentUrl(server: String, romId: String, fileName: String, fileId: String? = null): String =
        "$server/api/roms/${encode(romId)}/content/${encode(fileName).replace("+", "%20")}" + (fileId?.let { "?file_ids=" + encode(it) } ?: "")

    fun firmware(server: String, token: String, platformId: String): RommAnswer = get(server, "/api/firmware?platform_id=${encode(platformId)}", token)

    fun firmwareUrl(server: String, id: String, fileName: String): String =
        "$server/api/firmware/${encode(id)}/content/${encode(fileName).replace("+", "%20")}"

    fun open(url: String, token: String): HttpURLConnection = connect(url, bearer(token))

    private fun connect(url: String, authorization: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", USER_AGENT)
        if (authorization.isNotEmpty()) setRequestProperty("Authorization", authorization)
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun call(server: String, path: String, token: String, method: String? = null, body: String? = null): RommAnswer =
        send(server, path, bearer(token), body, method)

    /** A form the way a browser posts one, for the endpoints that take fields rather than JSON. */
    fun form(server: String, path: String, token: String, fields: Map<String, String>): RommAnswer =
        send(server, path, bearer(token), fields.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }, "POST", "application/x-www-form-urlencoded")

    /** A body of at most [most] bytes with its HTTP code; a longer one is answered as 413, and 0 means the server was not reached. */
    fun bytes(server: String, path: String, token: String, most: Int): Pair<Int, ByteArray?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = connect(server + path, bearer(token)).apply { connectTimeout = API_CONNECT_MS }
            val code = conn.responseCode
            if (code !in 200..299) {
                // Read to its end, a refusal leaves the connection fit for the next request.
                runCatching { conn.errorStream?.use { atMost(it, 64 * 1024) } }
                return code to null
            }
            val body = conn.inputStream.use { atMost(it, most) }
            if (body == null) 413 to null else code to body
        } catch (e: Exception) {
            0 to null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** A picture at [url], with the HTTP code, or 0 when the server was not reached. Covers are served without a sign-in. */
    fun picture(url: String, most: Int = MOST_PICTURE): Pair<Int, ByteArray?> = bytes(url, "", "", most)

    /** What [input] holds, or null when that is more than [most] bytes; the server chooses how much it sends. */
    internal fun atMost(input: java.io.InputStream, most: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > most) return null
            out.write(buffer, 0, read)
        }
    }

    /** Sends one file as a multipart form, the way RomM takes saves and states. */
    fun upload(server: String, path: String, token: String, method: String, field: String, fileName: String, data: ByteArray): RommAnswer {
        val boundary = "DroidDeck" + java.util.UUID.randomUUID().toString().replace("-", "")
        val safeName = fileName.replace("\\", "\\\\").replace("\"", "\\\"")
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$safeName\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        var conn: HttpURLConnection? = null
        return try {
            conn = connect(server + path, bearer(token)).apply {
                requestMethod = method
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setFixedLengthStreamingMode(head.size.toLong() + data.size + tail.size)
            }
            conn.outputStream.use { it.write(head); it.write(data); it.write(tail) }
            answer(conn)
        } catch (e: Exception) {
            RommAnswer(0, "")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun answer(conn: HttpURLConnection): RommAnswer {
        val code = conn.responseCode
        val stream = if (code in 200..399) conn.inputStream else conn.errorStream
        return RommAnswer(code, stream?.use { readBounded(it) }.orEmpty())
    }

    private fun get(server: String, path: String, token: String): RommAnswer = send(server, path, bearer(token), null)

    private fun send(server: String, path: String, authorization: String, body: String?, method: String? = null, type: String = "application/json"): RommAnswer {
        var conn: HttpURLConnection? = null
        return try {
            conn = connect(server + path, authorization).apply {
                connectTimeout = API_CONNECT_MS
                readTimeout = 25_000
                setRequestProperty("Accept", "application/json")
                if (method != null) requestMethod = method
                if (body != null) {
                    if (method == null) requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", type)
                }
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            answer(conn)
        } catch (e: Exception) {
            RommAnswer(0, "", untrusted = refusedCertificate(e))
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    internal fun refusedCertificate(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(8).any { it is java.security.cert.CertificateException || it is javax.net.ssl.SSLPeerUnverifiedException }

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
