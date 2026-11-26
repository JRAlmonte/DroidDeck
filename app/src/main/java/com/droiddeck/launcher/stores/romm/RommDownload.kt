package com.droiddeck.launcher.stores.romm

import com.droiddeck.launcher.core.Hashes
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection

/** One file from the server into a part file, which a later request carries on from. */
internal object RommDownload {
    /** The answer ended before the file did, which is how a connection dropped on the way shows. */
    class CameShort(val code: Int) : java.io.IOException()

    /** The server answered [code] and sent no file, or with [tooLong] more bytes than the file may be. */
    class Refused(val code: Int, val tooLong: Boolean = false) : RuntimeException()

    private const val PROGRESS_EVERY = 256 * 1024

    /**
     * Downloads [url] to [part], carrying on from what is there, and tells [told] how much of the file is there and how long it is.
     * With [algorithm] it returns the whole file's hash, worked out as the bytes pass so a large file is not read a second time
     * to check it; null when nothing new came or [stopped] said to stop. The file is never let past [most] bytes.
     */
    fun once(
        url: String, token: String, part: File, expected: Long, algorithm: String? = null, most: Long = Long.MAX_VALUE,
        stopped: () -> Boolean = { false }, told: (Long, Long) -> Unit = { _, _ -> },
    ): String? {
        for (attempt in 0..1) {
            if (stopped()) return null
            val have = part.length()
            if (expected > 0 && have == expected) {
                told(have, have)
                return null
            }
            var conn: HttpURLConnection? = null
            try {
                conn = RommApi.open(url, token).apply {
                    setRequestProperty("Accept-Encoding", "identity")
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = conn.responseCode
                if (code == 416 && attempt == 0) {
                    part.delete()
                    continue
                }
                if (code !in 200..299) throw Refused(code)
                val append = code == 206 && have > 0
                val digest = algorithm?.let { java.security.MessageDigest.getInstance(it) }
                val buffer = ByteArray(128 * 1024)
                if (append && digest != null) part.inputStream().use { kept ->
                    while (true) { val read = kept.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
                }
                var done = if (append) have else 0L
                val size = conn.contentLengthLong.takeIf { it > 0 }?.let { if (append) it + have else it } ?: expected
                told(done, size)
                var last = done
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        while (true) {
                            if (stopped()) return null
                            val read = input.read(buffer)
                            if (read < 0) break
                            done += read
                            if (done > most || (size > 0 && done > size)) throw Refused(code, tooLong = true)
                            out.write(buffer, 0, read)
                            digest?.update(buffer, 0, read)
                            if (done - last >= PROGRESS_EVERY) { last = done; told(done, size) }
                        }
                    }
                }
                told(done, size)
                if (size > 0 && done < size) throw CameShort(code)
                return digest?.let { Hashes.hex(it.digest()) }
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        return null
    }
}
