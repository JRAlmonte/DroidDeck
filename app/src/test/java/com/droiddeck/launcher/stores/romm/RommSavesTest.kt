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

    private fun psp(extra: Map<String, String>) = RommSaves.layout(
        File("/rootfs"), File("/roms"),
        RommInstalls.Entry("1", "psp/Game.iso", "ppsspp", item = com.droiddeck.launcher.stores.CatalogItem(com.droiddeck.launcher.stores.Store.ROMM, "1", "Game", null, extra = extra)),
    )

    @Test fun ppssppKeepsASaveFolderPerSaveNamedByTheGamesId() {
        val game = psp(mapOf(RommCatalog.TITLE_ID to "ULUS-10336"))!!
        assertEquals(File("/rootfs/root/.config/ppsspp/PSP"), game.root)
        assertEquals(listOf(".droiddeck-saves", "PPSSPP_STATE"), game.folders)
        assertEquals("ppsspp", game.emulator)
        assertEquals(File("/rootfs/root/.config/ppsspp/PSP/SAVEDATA"), game.packed!!.source)
        assertEquals("ULUS10336", game.packed!!.prefix)
        for (name in listOf(".droiddeck-saves/ULUS10336DATA00.zip", ".droiddeck-saves/ULUS10336.zip")) assertEquals(name, "saves", game.kind(name))
        assertEquals("states", game.kind("PPSSPP_STATE/ULUS10336_1.00_0.ppst"))
        for (name in listOf(".droiddeck-saves/ULES00151DATA00.zip", "SAVEDATA/ULUS10336DATA00/PARAM.SFO", "PPSSPP_STATE/ULUS10336_1.00_0.jpg", "PPSSPP_STATE/ULES00151_1.00_0.ppst"))
            assertNull(name, game.kind(name))
        assertEquals(".droiddeck-saves/ULUS10336DATA00.zip", game.local("saves", "ULUS10336DATA00.zip"))
    }

    @Test fun ppssppSavesGoByTheNameRommGivesThemWhenItKnowsOne() {
        val named = psp(mapOf(RommCatalog.TITLE_ID to "NPUH10047", RommCatalog.SAVE_TARGET to "ULUS10041", RommCatalog.SAVE_LAYOUT to "folder-prefix"))!!
        assertEquals("saves", named.kind(".droiddeck-saves/ULUS10041SLOT1.zip"))
        assertNull(named.kind(".droiddeck-saves/NPUH10047SLOT1.zip"))
        assertEquals("states", named.kind("PPSSPP_STATE/NPUH10047_1.00_0.ppst"))
        val other = psp(mapOf(RommCatalog.TITLE_ID to "NPUH10047", RommCatalog.SAVE_TARGET to "something.bin", RommCatalog.SAVE_LAYOUT to "file-exact"))!!
        assertEquals("NPUH10047", other.packed!!.prefix)
        assertNull(psp(emptyMap()))
        assertNull(psp(mapOf(RommCatalog.TITLE_ID to "12")))
    }

    @Test fun otherEmulatorsAreNotSynced() {
        for (emulator in listOf("dolphin", "armsx2", "ppsspp", "cemu", "rpcs3")) assertNull(emulator, layout("x/Game.iso", emulator))
    }
}
