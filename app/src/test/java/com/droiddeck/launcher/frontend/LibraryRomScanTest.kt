package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryRomScanTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var roms: File

    @Before fun romsFolder() {
        roms = File(app.cacheDir, "roms-" + System.nanoTime()).apply { mkdirs() }
        SessionPrefs.setRomsDir(app, roms.path)
    }

    private fun put(path: String) = File(roms, path).apply { parentFile!!.mkdirs(); writeText("x") }

    private fun games(emulator: String) = Library.emulators(app) { true }.first { it.id == emulator }.games

    @Test fun retroArchListsGamesFromItsSystemFolders() {
        put("snes/Game A (USA).sfc")
        put("GBA/Game B.zip")
        put("genesis/Europe/Game C.md")
        put("snes/notes.txt")
        assertEquals(listOf("/root/ROMs/GBA/Game B.zip", "/root/ROMs/genesis/Europe/Game C.md", "/root/ROMs/snes/Game A (USA).sfc"), games("retroarch").map { it.guestPath })
        assertEquals(listOf("Game B", "Game C", "Game A"), games("retroarch").map { it.name })
    }

    @Test fun retroArchIgnoresLooseFilesAndOtherFolders() {
        put("Game.zip")
        put("misc/Game.sfc")
        put("psx/Game.zip")
        assertEquals(emptyList<String>(), games("retroarch").map { it.guestPath })
    }

    @Test fun otherEmulatorsLeaveRetroArchFoldersAlone() {
        put("genesis/Game.bin")
        put("psx/Game.chd")
        put("misc/Loose.chd")
        assertEquals(listOf("/root/ROMs/misc/Loose.chd", "/root/ROMs/psx/Game.chd"), games("duckstation").map { it.guestPath }.sorted())
    }
}
