package com.droiddeck.launcher.frontend

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.runtime.DesktopCatalog

object CoreFetch {
    private val main = Handler(Looper.getMainLooper())

    var working by mutableStateOf(false)
        private set
    var stage by mutableStateOf<String?>(null)
        private set
    var failed by mutableStateOf(false)
        private set
    var done by mutableStateOf(0)
        private set

    fun start(context: Context) {
        if (working) return
        val app = context.applicationContext
        working = true; stage = null; failed = false
        Thread({
            val ok = DesktopCatalog.fetchCores(app) { text -> main.post { stage = text } }
            main.post { working = false; stage = null; failed = !ok; done++ }
        }, "core-fetch").start()
    }
}
