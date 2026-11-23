package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PicturePositionPrefsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearSettings() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun thePictureIsCentredUntilAnEdgeIsChosen() {
        assertEquals("center", SessionPrefs.picturePosition(context))
        assertEquals("center", SessionPrefs.picturePosition(context, SessionService.MODE_RUN))
    }

    @Test fun anEmulatorGameKeepsItsOwnEdge() {
        SessionPrefs.setPicturePosition(context, "top", SessionService.MODE_RUN)
        assertEquals("top", SessionPrefs.picturePosition(context, SessionService.MODE_RUN))
        assertEquals("center", SessionPrefs.picturePosition(context))
        SessionPrefs.setPicturePosition(context, "left")
        assertEquals("left", SessionPrefs.picturePosition(context))
        assertEquals("top", SessionPrefs.picturePosition(context, SessionService.MODE_RUN))
    }

    @Test fun anEdgeThatDoesNotExistIsCentre() {
        SessionPrefs.setPicturePosition(context, "middle", SessionService.MODE_RUN)
        assertEquals("center", SessionPrefs.picturePosition(context, SessionService.MODE_RUN))
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putString("picturePosition", "diagonal").commit()
        assertEquals("center", SessionPrefs.picturePosition(context))
    }
}
