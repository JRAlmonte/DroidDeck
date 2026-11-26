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

    /** The platform's picture, from the device once it was fetched, or null when the server has none; must not run on the main thread. */
    fun load(context: Context, platform: RommPlatform): Bitmap? {
        val slug = platform.slug.lowercase()
        known[slug]?.let { return it }
        if (slug in none || !NAME.matches(slug)) return null
        val server = RommAuth.server(context).ifEmpty { return null }
        val kept = File(folder(context), "${Integer.toHexString(server.hashCode())}-$slug.png")
        var bitmap = kept.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        var reached = true
        if (bitmap == null) for (url in addresses(server, platform)) {
            val (code, bytes) = RommApi.picture(url)
            if (code == 0) { reached = false; break }
            bitmap = bytes?.takeIf { it.size <= MOST_BYTES }?.let(::decode) ?: continue
            keep(bitmap, kept)
            break
        }
        if (bitmap == null) {
            if (reached) none += slug
            return null
        }
        known[slug] = bitmap
        return bitmap
    }

    internal fun decode(bytes: ByteArray): Bitmap? = runCatching {
        if ("<svg" in String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)) drawn(bytes) else pictured(bytes)
    }.getOrNull()

    private fun drawn(bytes: ByteArray): Bitmap {
        val svg = SVG.getFromInputStream(bytes.inputStream())
        if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        return Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
    }

    private fun pictured(bytes: ByteArray): Bitmap? {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
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
