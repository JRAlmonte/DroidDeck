package com.droiddeck.launcher.stores.romm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RommSavesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun layout(path: String, emulator: String, rootfs: File = File("/rootfs"), roms: File? = File("/roms")) =
        RommSaves.layout(rootfs, roms, RommInstalls.Entry("1", path, emulator))

    private fun duckStation(home: String, cards: String?): File {
        val rootfs = tmp.newFolder()
        File(rootfs, home).mkdirs()
        File(rootfs, "$home/settings.ini").writeText("[Main]\nCard1Type = PerGameFileTitle\n\n" + (cards?.let { "[MemoryCards]\nCard1Type = $it\nCard2Type = None\n\n" } ?: "") + "[GPU]\nRenderer = Vulkan\n")
        return rootfs
    }

    @Test fun aRetroArchGamesSavesAreTheFilesNamedAfterIt() {
        val game = layout("snes/Game.sfc", "retroarch")!!
        assertEquals(File("/rootfs/root/.config/retroarch"), game.root)
        assertEquals(listOf("saves", "states"), game.folders)
        assertEquals("retroarch", game.emulator)
        for (name in listOf("saves/Game.srm", "saves/Game.rtc")) assertEquals(name, "saves", game.kind(name))
        for (name in listOf("states/Game.state", "states/Game.state3", "states/Game.state.auto")) assertEquals(name, "states", game.kind(name))
        assertEquals("states/Game.state3", game.local("states", "Game.state3"))
    }

    @Test fun retroArchWithItsSavesMovedElsewhereIsNotSynced() {
        fun with(cfg: String): File = tmp.newFolder().also { File(it, "root/.config/retroarch").mkdirs(); File(it, "root/.config/retroarch/retroarch.cfg").writeText(cfg) }
        val kept = "savefile_directory = \"~/.config/retroarch/saves\"\nsavestate_directory = \"default\"\nsort_savefiles_enable = \"false\"\n"
        assertEquals(listOf("saves", "states"), layout("snes/Game.sfc", "retroarch", with(kept))!!.folders)
        assertNull(layout("snes/Game.sfc", "retroarch", with("savefile_directory = \"/root/ROMs/saves\"\n")))
        assertNull(layout("snes/Game.sfc", "retroarch", with(kept + "savefiles_in_content_dir = \"true\"\n")))
        assertNull(layout("snes/Game.sfc", "retroarch", with(kept + "sort_savestates_by_content_enable = \"true\"\n")))
    }

    @Test fun anotherGamesFilesAreLeftAlone() {
        val game = layout("snes/Game.sfc", "retroarch")!!
        val others = listOf(
            "saves/Game 2.srm", "saves/Game.v2.srm", "saves/Gamer.srm", "saves/sub/Game.srm", "states/Game.state.png", "states/Game.srm",
            "thumbnails/Game.srm", "saves/Game.", "saves/Game", "Game.srm",
        )
        for (name in others) assertNull(name, game.kind(name))
    }

    @Test fun theNameComesFromTheFileTheEmulatorOpens() {
        assertEquals("Game (USA)", RommSaves.base(RommInstalls.Entry("1", "snes/Game (USA).zip", "retroarch")))
        assertEquals("Game", RommSaves.base(RommInstalls.Entry("1", "psx/Game/Game.m3u", "retroarch", "psx/Game")))
        assertEquals("saves", layout("snes/Game (USA).zip", "retroarch")!!.kind("saves/Game (USA).srm"))
    }

    @Test fun duckStationKeepsACardPerGameFileOnceItIsSetTo() {
        val rootfs = duckStation("root/.config/duckstation", "PerGameFileTitle")
        val game = layout("psx/Game (USA).chd", "duckstation", rootfs)!!
        assertEquals(File(rootfs, "root/.config/duckstation"), game.root)
        assertEquals(listOf("memcards"), game.folders)
        assertEquals(listOf("saves"), game.kinds)
        assertEquals("saves", game.kind("memcards/Game (USA)_1.mcd"))
        assertEquals("saves", game.kind("memcards/Game (USA)_2.mcd"))
        assertEquals("memcards/Game (USA)_1.mcd", game.local("saves", "Game (USA)_1.mcd"))
        for (name in listOf("memcards/shared_card_1.mcd", "memcards/Game (USA) 2_1.mcd", "memcards/Game (USA)_3.mcd", "savestates/Game (USA)_1.sav", "Game (USA)_1.mcd")) {
            assertNull(name, game.kind(name))
        }
        val legacy = duckStation("root/.local/share/duckstation", "PerGameFileTitle")
        assertEquals(File(legacy, "root/.local/share/duckstation"), layout("psx/Game.chd", "duckstation", legacy)!!.root)
    }

    @Test fun duckStationOnASharedCardOrNotSetUpYetIsNotSynced() {
        assertNull(layout("psx/Game.chd", "duckstation", duckStation("root/.config/duckstation", "Shared")))
        assertNull(layout("psx/Game.chd", "duckstation", duckStation("root/.config/duckstation", "PerGameTitle")))
        assertNull(layout("psx/Game.chd", "duckstation", duckStation("root/.config/duckstation", null)))
        assertNull(layout("psx/Game.chd", "duckstation", tmp.newFolder()))
    }

    @Test fun melonDsKeepsSavesAndStatesBesideTheGame() {
        val game = layout("nds/Europe/Game.nds", "melonds")!!
        assertEquals(File("/roms/nds/Europe"), game.root)
        assertNull(game.folders)
        assertEquals("melonds", game.emulator)
        assertEquals("saves", game.kind("Game.sav"))
        assertEquals("states", game.kind("Game.ml1"))
        assertEquals("states", game.kind("Game.ml8"))
        assertEquals("Game.ml3", game.local("states", "Game.ml3"))
        for (name in listOf("Game.nds", "Game.ml9", "Game 2.sav", "Other/Game.sav", "Game.sav.bak")) assertNull(name, game.kind(name))
        assertNull(layout("nds/Game.nds", "melonds", roms = null))
    }

    @Test fun aPortIsSyncedOnlyWhenItsEntryNamesItsSaves() {
        fun port(id: String, vararg saves: String) = com.droiddeck.launcher.ports.TestPorts.zelda.let {
            com.droiddeck.launcher.ports.Port(id, it.name, it.game, it.repo, it.asset, it.entry, it.systemFolders, it.exts, it.form, it.romSize, it.romSha1, it.stagedRom, saves = saves.toList())
        }
        fun saves(emulator: String) = RommSync.portSaves(File("/rootfs"), RommInstalls.Entry("1", "n64/Game.z64", emulator))
        com.droiddeck.launcher.ports.Ports.use(listOf(port("named", "saves"), port("plain")))
        try {
            assertEquals("named.zip", saves("port:named")!!.fileName)
            assertEquals("port-named", saves("port:named")!!.emulator)
            assertEquals(listOf("opt/droiddeck-ports/named/saves"), saves("port:named")!!.folders)
            for (emulator in listOf("port:plain", "port:gone", "retroarch", "named")) assertNull(emulator, saves(emulator))
        } finally {
            com.droiddeck.launcher.ports.Ports.use(emptyList())
        }
    }

    @Test fun otherEmulatorsAreNotSynced() {
        for (emulator in listOf("dolphin", "armsx2", "ppsspp", "cemu", "rpcs3", "port:zelda64recomp")) assertNull(emulator, layout("x/Game.iso", emulator))
    }
}
