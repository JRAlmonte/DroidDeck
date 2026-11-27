package com.droiddeck.launcher.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.romm.RommCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class GamePageArtTest {
    @get:Rule val compose = createRobolectricComposeRule()

    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val large = "http://romm.example:8080/assets/romm/resources/roms/1/42/cover/big.png"
    private val small = "http://romm.example:8080/assets/romm/resources/roms/1/42/cover/small.png"
    private val shot = "http://romm.example:8080/assets/romm/resources/roms/1/42/screenshots/0.jpg"

    private fun game(image: String) = CatalogItem(Store.ROMM, "42", "Example Quest", image, large, owned = true, extra = mapOf(RommCatalog.COVER_SMALL to small))

    private var page by mutableStateOf<Pair<String, CatalogItem>?>(null)
    private var built: ImageRequest? = null

    private fun requestFor(art: String, item: CatalogItem): ImageRequest {
        if (page == null) compose.setContent { built = page?.let { (art, item) -> pageArtRequest(art, item) } }
        page = art to item
        compose.waitForIdle()
        return built!!
    }

    @Test fun aPageWhoseArtIsTheCoverShowsTheTilesSmallCoverUntilTheLargeOneHasCome() {
        val item = game(large)
        val pictures = listOf(small, large).associateWith { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) }
        val arrived = CompletableDeferred<Unit>()
        val loader = ImageLoader.Builder(app).dispatcher(Dispatchers.Unconfined).interceptorDispatcher(Dispatchers.Unconfined)
            .components {
                add(Fetcher.Factory<android.net.Uri> { address, _, _ ->
                    Fetcher {
                        if (address.toString() == large) arrived.await()
                        DrawableResult(BitmapDrawable(app.resources, pictures.getValue(address.toString())), false, DataSource.NETWORK)
                    }
                })
            }.build()
        try {
            assertEquals(small, RommCatalog.tile(item))
            val tile = runBlocking { loader.execute(ImageRequest.Builder(app).data(RommCatalog.tile(item)).build()) } as SuccessResult
            val page = requestFor(large, item)
            assertEquals("the page asks for the large cover", large, page.data)
            assertEquals("over the picture the tile has in memory", tile.memoryCacheKey, page.placeholderMemoryCacheKey)

            var under: Drawable? = null
            var shown: Drawable? = null
            loader.enqueue(page.newBuilder().target(onStart = { under = it }, onSuccess = { shown = it }).build())
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertSame("the small cover is up while the large one is on its way", pictures.getValue(small), (under as BitmapDrawable).bitmap)
            assertNull(shown)

            arrived.complete(Unit)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertSame(pictures.getValue(large), (shown as BitmapDrawable).bitmap)
        } finally {
            loader.shutdown()
        }
    }

    @Test fun aPageWhoseArtIsNotTheCoverKeepsTheLargeCoverUnderIt() {
        assertEquals(MemoryCache.Key(large), requestFor(shot, game(shot)).placeholderMemoryCacheKey)
        val other = CatalogItem(Store.ITCH, "7", "Other", shot, large, owned = true)
        assertEquals("another store's page is as it was", MemoryCache.Key(large), requestFor(shot, other).placeholderMemoryCacheKey)
        assertNull("the cover itself needs nothing under it", requestFor(large, other).placeholderMemoryCacheKey)
    }
}
