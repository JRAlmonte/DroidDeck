package com.droiddeck.launcher.stores.romm

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.StoresState
import java.io.File
import java.net.HttpURLConnection

/** A game's manual as its RomM server holds it, fetched once and handed to whatever app here reads such a file. */
internal object RommManual {
    private val TYPES = mapOf("pdf" to "application/pdf", "cbz" to "application/vnd.comicbook+zip", "epub" to "application/epub+zip", "txt" to "text/plain", "htm" to "text/html", "html" to "text/html")
    private const val MOST_BYTES = 512L * 1024 * 1024
    private val busy = HashSet<String>()

    internal fun extension(url: String): String =
        url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it in TYPES } ?: "pdf"

    private fun folder(context: Context) = File(context.cacheDir, "share/manuals")

    // ROM ids repeat from one server to the next, so the address is part of the name.
    internal fun file(context: Context, id: String, url: String) = File(folder(context), "$id-${Integer.toHexString(url.hashCode())}.${extension(url)}")

    fun clear(context: Context) {
        folder(context).deleteRecursively()
    }

    private fun fetch(context: Context, url: String, out: File): Boolean {
        if (out.isFile && out.length() > 0) return true
        val server = RommAuth.server(context)
        var conn: HttpURLConnection? = null
        return try {
            conn = RommApi.open(url, if (server.isNotEmpty() && url.startsWith("$server/")) RommAuth.token(context) else "")
            if (conn.responseCode !in 200..299) return false
            out.parentFile?.mkdirs()
            val part = File(out.path + ".part")
            val whole = conn.inputStream.use { input -> part.outputStream().use { copied(input, it) } }
            if (!whole) part.delete()
            whole && part.length() > 0 && part.renameTo(out)
        } catch (e: Exception) {
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun copied(input: java.io.InputStream, out: java.io.OutputStream): Boolean {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return true
            done += read
            if (done > MOST_BYTES) return false
            out.write(buffer, 0, read)
        }
    }

    fun open(context: Context, id: String, url: String) {
        val app = context.applicationContext
        synchronized(busy) { if (!busy.add(id)) return }
        val out = file(app, id, url)
        if (!out.isFile) Toast.makeText(app, R.string.romm_manual_getting, Toast.LENGTH_SHORT).show()
        Thread({
            val got = fetch(app, url, out)
            synchronized(busy) { busy.remove(id) }
            StoresState.post {
                if (!got) { Toast.makeText(app, R.string.romm_manual_failed, Toast.LENGTH_SHORT).show(); return@post }
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(FileProvider.getUriForFile(app, app.packageName + ".logs", out), TYPES.getValue(extension(url)))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { app.startActivity(view) }.onFailure { Toast.makeText(app, R.string.romm_manual_no_viewer, Toast.LENGTH_SHORT).show() }
            }
        }, "romm-manual").start()
    }
}
