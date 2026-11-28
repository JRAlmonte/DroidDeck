package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.frontend.ScreenRects.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScreenRectsTest {
    private fun ScreenRects.inside(screen: Screen) = box(screen).let { it.x >= 0 && it.y >= 0 && it.x + it.w <= width && it.y + it.h <= height }
    private fun ScreenRects.shaped(screen: Screen) = box(screen).let { abs(it.w / it.h.toFloat() - screen.aspect) < .02f }

    @Test fun eachStartingArrangementFitsTheDisplayWithBothScreensInShape() {
        for ((w, h) in listOf(2400 to 1080, 1920 to 1080, 1080 to 2400)) for (preset in ScreenRects.Preset.entries) {
            val rects = ScreenRects.preset(w, h, preset)
            for (screen in Screen.entries) {
                assertTrue("$preset $w x $h $screen", rects.inside(screen))
                assertTrue("$preset $w x $h $screen", rects.shaped(screen))
            }
        }
        val side = ScreenRects.preset(2400, 1080, ScreenRects.Preset.SIDE_BY_SIDE)
        assertEquals(side.top.x + side.top.w, side.bottom.x)
        assertEquals(800, side.top.h)
        assertEquals(2400, side.top.w + side.bottom.w)
        assertEquals(ScreenRects.Preset.STACKED.let { ScreenRects.preset(1080, 2400, it) }, ScreenRects.default(1080, 2400))
    }

    @Test fun aScreenIsMovedAndResizedWithoutLeavingTheDisplayOrItsShape() {
        val rects = ScreenRects.default(2400, 1080)
        val far = rects.moved(Screen.BOTTOM, 5000, 5000).bottom
        assertEquals(2400 - far.w, far.x)
        assertEquals(1080 - far.h, far.y)
        val huge = rects.resized(Screen.TOP, 9000)
        assertEquals(1800, huge.top.w)
        assertTrue(huge.inside(Screen.TOP) && huge.shaped(Screen.TOP))
        val tiny = rects.scaled(Screen.BOTTOM, .01f)
        assertEquals(80, tiny.bottom.w)
        assertEquals(rects.top, tiny.top)
        val atEdge = rects.placed(Screen.BOTTOM, 2400, 0)
        assertEquals(atEdge.bottom.x, atEdge.resized(Screen.BOTTOM, 1000).bottom.x)
        val grown = atEdge.resized(Screen.BOTTOM, 1200).bottom
        assertEquals(2400, grown.x + grown.w)
    }

    @Test fun whatIsSavedReadsBackAndAnythingElseDoesNot() {
        val rects = ScreenRects.preset(2400, 1080, ScreenRects.Preset.LARGE_TOP)
        assertEquals(rects, ScreenRects.parse(rects.format()))
        for (bad in listOf(null, "", "1,2,3", "2400,1080,0,0,0,0,1,1,1,1", "0,1080,0,0,10,10,1,1,1,1", "a,b,c,d,e,f,g,h,i,j", "-1,1,1,1,1,1,1,1,1,1"))
            assertNull(bad, ScreenRects.parse(bad))
    }
}
