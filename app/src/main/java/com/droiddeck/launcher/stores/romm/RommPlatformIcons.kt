package com.droiddeck.launcher.stores.romm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The picture a RomM server keeps for each platform, the one its own web page shows beside the platform's name. */
internal object RommPlatformIcons {
    private const val SIZE = 128
    private const val MOST_BYTES = 2 * 1024 * 1024
    private val NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val known = ConcurrentHashMap<String, Bitmap>()
    private val none: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Where the server's web page looks, in its order: the folder's name before the slug, a drawing before an icon file. */
    fun addresses(server: String, platform: RommPlatform): List<String> =
        listOf(platform.fsSlug, platform.slug).map { it.lowercase() }.filter(NAME::matches).distinct()
            .flatMap { listOf("$server/assets/platforms/$it.svg", "$server/assets/platforms/$it.ico") }

    private fun folder(context: Context) = File(context.cacheDir, "romm-platforms")

    fun ready(platform: RommPlatform): Bitmap? = known[platform.slug.lowercase()]

    private const val MISS_RETRY_MS = 7L * 24 * 3600 * 1000
    private val gate = java.util.concurrent.Semaphore(4)
    private val locks = ConcurrentHashMap<String, Any>()

    /** The platform's picture, from the device once it was fetched, or null when the server has none; must not run on the main thread. */
    fun load(context: Context, platform: RommPlatform): Bitmap? {
        val slug = platform.slug.lowercase()
        known[slug]?.let { return it }
        if (slug in none || !NAME.matches(slug)) return null
        // A platform shown in two places at once is still asked for once.
        return synchronized(locks.getOrPut(slug) { Any() }) { known[slug] ?: if (slug in none) null else read(context, platform, slug) }
    }

    private fun read(context: Context, platform: RommPlatform, slug: String): Bitmap? {
        val server = RommAuth.server(context).ifEmpty { return null }
        val kept = File(folder(context), "${Integer.toHexString(server.hashCode())}-$slug.png")
        // A server without a picture for the platform is not asked again at every start.
        val missed = File(kept.path + ".none")
        if (missed.isFile && System.currentTimeMillis() - missed.lastModified() in 0 until MISS_RETRY_MS) { none += slug; return null }
        var bitmap = kept.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        var reached = true
        if (bitmap == null) {
            // The menu lists every platform at once, and the server is often a small machine at home.
            gate.acquire()
            try {
                for (url in addresses(server, platform)) {
                    val (code, bytes) = RommApi.picture(url, MOST_BYTES)
                    if (code == 0) { reached = false; break }
                    bitmap = bytes?.let(::decode) ?: continue
                    keep(bitmap, kept)
                    break
                }
            } finally {
                gate.release()
            }
        }
        if (bitmap == null) {
            if (reached) {
                none += slug
                runCatching { missed.parentFile?.mkdirs(); missed.writeText("") }
            }
            return null
        }
        known[slug] = bitmap
        return bitmap
    }

    internal fun decode(bytes: ByteArray): Bitmap? = runCatching {
        if ("<svg" in String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)) drawn(bytes) else pictured(bytes)
    }.getOrNull()

    // A drawing that declares entities can grow without end when it is read, and a platform's picture has no use for them.
    private fun declares(bytes: ByteArray): Boolean = String(bytes, Charsets.ISO_8859_1).uppercase().let { "<!DOCTYPE" in it || "<!ENTITY" in it }

    private fun drawn(bytes: ByteArray): Bitmap? {
        if (declares(bytes)) return null
        val svg = SVG.getFromInputStream(bytes.inputStream())
        if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        return Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
    }

    private fun pictured(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = SIZE.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale >= 1f) return decoded
        return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun keep(bitmap: Bitmap, file: File) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    fun clear(context: Context) {
        known.clear()
        none.clear()
        folder(context).deleteRecursively()
    }
}
