package com.droiddeck.launcher.ui

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherFrostTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val graphite get() = Themes.byId(Themes.GRAPHITE)
    private val whiteRed get() = Themes.byId(Themes.WHITE_RED)

    @Before fun clear() {
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun contrast(a: Color, b: Color) = (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)

    @Test fun overADarkWallpaperTheVeilIsTheGamePagesTint() {
        assertEquals(graphite.background.copy(alpha = 0.58f), frostVeil(graphite, Color(0xFF101820)))
        assertEquals(whiteRed.background.copy(alpha = 0.7f), frostVeil(whiteRed, Color(0xFFF2F2F2)))
    }

    @Test fun aBrightWallpaperGetsAStrongerVeilUnderADarkThemeAndADarkOneUnderTheLightTheme() {
        assertTrue(frostVeil(graphite, Color.White).alpha > frostVeil(graphite, Color(0xFF101820)).alpha)
        assertTrue(frostVeil(whiteRed, Color.Black).alpha > frostVeil(whiteRed, Color(0xFFF2F2F2)).alpha)
    }

    @Test fun secondaryTextStaysReadableOverAnyWallpaperInEveryTheme() {
        val wallpapers = listOf(Color.White, Color.Black, Color(0xFF808080), Color(0xFFFFE600), Color(0xFF00008B), Color(0xFFE2725B))
        for (theme in Themes.all) for (wallpaper in wallpapers) {
            val veil = frostVeil(theme, wallpaper)
            assertTrue("${theme.id} veil ${veil.alpha}", veil.alpha in 0.58f..0.9f)
            assertTrue("${theme.id} over $wallpaper", contrast(theme.onSurfaceVariant, veil.compositeOver(wallpaper)) >= 4.5f)
        }
    }

    @Test fun anUnknownWallpaperCountsAsTheWorstOne() {
        assertEquals(frostVeil(graphite, Color.White), frostVeil(graphite, null))
        assertEquals(frostVeil(whiteRed, Color.Black), frostVeil(whiteRed, null))
    }

    @Test fun cardsGoSlightlySeeThroughAndThePageAndTextColoursStay() {
        for (theme in Themes.all) {
            val frosted = theme.frosted()
            assertTrue(theme.id, frosted.surface.alpha in 0.5f..0.8f && frosted.surfaceVariant.alpha == frosted.surface.alpha)
            assertEquals(theme.surface, frosted.surface.copy(alpha = 1f))
            assertEquals(theme.background, frosted.background)
            assertEquals(theme.onBackground, frosted.onBackground)
            assertEquals(theme.onSurfaceVariant, frosted.onSurfaceVariant)
            assertEquals(theme.primary, frosted.primary)
        }
    }

    @Test fun textOnASeeThroughCardReadsAtLeastAsWellAsOnThePage() {
        val wallpapers = listOf(Color.White, Color.Black, Color(0xFF808080), Color(0xFFFFE600), Color(0xFF00008B))
        for (theme in Themes.all) for (wallpaper in wallpapers) {
            val page = frostVeil(theme, wallpaper).compositeOver(wallpaper)
            val card = theme.frosted().surface.compositeOver(page)
            assertTrue("${theme.id} over $wallpaper", contrast(theme.onSurfaceVariant, card) >= 4.5f)
        }
    }

    @Test fun theFrostIsOffUntilChosenAndTheChoiceIsKept() {
        assertFalse(SessionPrefs.launcherFrost(app))
        SessionPrefs.setLauncherFrost(app, true)
        assertTrue(SessionPrefs.launcherFrost(app))
    }

    @Test fun aWindowThatWasNotPreparedKeepsItsBackgroundAndShowsNoWallpaper() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val before = activity.window.decorView.background
        val frost = LauncherFrost(activity)
        frost.prepare(false)
        frost.show(true)
        assertFalse(frost.prepared)
        assertEquals(before, activity.window.decorView.background)
        assertEquals(0, activity.window.attributes.flags and (WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER or WindowManager.LayoutParams.FLAG_BLUR_BEHIND))
    }
}
