package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.frontend.EmulatorInventory.State
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorInventoryTest {
    private val rootfs: File = Files.createTempDirectory("inventory-rootfs").toFile()
    private val roms: File = Files.createTempDirectory("inventory-roms").toFile()

    private fun core(id: String, name: String? = null, system: String? = null) {
        File(rootfs, "usr/lib/aarch64-linux-gnu/libretro/${id}_libretro.so").apply { parentFile!!.mkdirs(); writeText("") }
        if (name != null) File(rootfs, "usr/share/libretro/info/${id}_libretro.info").apply {
            parentFile!!.mkdirs()
            writeText("display_name = \"x\"\ncorename = \"$name\"\n" + (system?.let { "systemname = \"$it\"\n" } ?: ""))
        }
    }

    private val ryujinx = EmulatorLinks.Link("ryujinx", "Ryujinx", "Switch", listOf("switch"), setOf("nsp", "xci"),
        "flatpak:io.github.ryubing.Ryujinx", "/usr/local/bin/droiddeck-flatpak-run", listOf("io.github.ryubing.Ryujinx"), listOf("--fullscreen"), null)

    private fun build(
        packages: Set<String> = setOf("emulators"), games: Map<String, Int> = emptyMap(),
        platforms: List<EmulatorInventory.Platform> = emptyList(), links: List<EmulatorLinks.Link> = emptyList(),
        choices: Map<String, String> = emptyMap(),
    ) = EmulatorInventory.build(EmulatorInventory.Input(packages, rootfs, roms, games, platforms, links, choices))

    private fun EmulatorInventory.Result.row(folder: String) = systems.first { folder in it.folders }

    @Test fun aSystemIsReadyOnlyWhenItsCoreIsOnDisk() {
        core("snes9x", "Snes9x")
        val result = build()
        assertEquals(State.READY, result.row("snes").state)
        assertEquals("RetroArch · Snes9x", result.row("snes").chosen?.name)
        assertEquals(State.CORE_MISSING, result.row("gba").state)
        assertEquals(State.NOT_INSTALLED, build(packages = emptySet()).row("snes").state)
    }

    @Test fun aStandaloneEmulatorNeedsItsPackage() {
        assertEquals(State.NOT_INSTALLED, build().row("psx").state)
        assertEquals("duckstation", build().row("psx").chosen?.key)
        assertEquals(State.READY, build(packages = setOf("duckstation")).row("ps1").state)
    }

    @Test fun aSystemNothingPlaysNamesWhatCould() {
        File(roms, "switch").mkdirs()
        File(roms, "switch/Game.nsp").writeText("x")
        File(roms, "switch/bios").mkdirs()
        File(roms, "switch/bios/prod.keys").writeText("x")
        val row = build().row("switch")
        assertEquals(State.NOTHING, row.state)
        assertEquals(1, row.games)
        assertEquals(listOf("ryujinx", "eden"), row.suggestions.map { it.id })
    }

    @Test fun aLinkedEmulatorMakesItsSystemReady() {
        val row = build(links = listOf(ryujinx), games = mapOf("switch" to 3)).row("switch")
        assertEquals(State.READY, row.state)
        assertEquals("Ryujinx", row.chosen?.name)
        assertEquals(3, row.games)
        assertEquals(emptyList<String>(), row.suggestions.map { it.id })
    }

    @Test fun aSecondCoreIsOfferedOnceItIsOnDisk() {
        core("gambatte")
        assertEquals(listOf("retroarch:gambatte"), build().row("gb").candidates.map { it.key })
        core("sameboy", "SameBoy")
        val both = build().row("gb")
        assertEquals(listOf("retroarch:gambatte", "retroarch:sameboy"), both.candidates.map { it.key })
        assertEquals("retroarch:gambatte", both.chosen?.key)
        assertEquals("retroarch:sameboy", build(choices = mapOf("gb" to "retroarch:sameboy")).row("gb").chosen?.key)
    }

    @Test fun aCoreForPs1SitsBesideDuckStationAndNeedsItsBios() {
        core("mednafen_psx", "Beetle PSX")
        val row = build(choices = mapOf("psx" to "retroarch:mednafen_psx")).row("psx")
        assertEquals(listOf("duckstation", "retroarch:mednafen_psx"), row.candidates.map { it.key })
        assertEquals(State.NEEDS_FILES, row.state)
        assertEquals(listOf("scph5501.bin", "scph5500.bin", "scph5502.bin"), row.missing)
        File(rootfs, RetroArchCores.SYSTEM_DIR).mkdirs()
        File(rootfs, RetroArchCores.SYSTEM_DIR + "/scph5500.bin").writeText("x")
        assertEquals(State.READY, build(choices = mapOf("psx" to "retroarch:mednafen_psx")).row("psx").state)
    }

    @Test fun whatTheServerAndTheRomsFolderHoldIsListedEvenWhenNothingPlaysIt() {
        File(roms, "Saturn").mkdirs()
        File(roms, "Saturn/Game.chd").writeText("x")
        File(roms, "bios").mkdirs()
        val result = build(platforms = listOf(EmulatorInventory.Platform("jaguar", "Atari Jaguar", 12), EmulatorInventory.Platform("snes", "SNES", 40)))
        assertEquals(State.NOTHING, result.row("jaguar").state)
        assertEquals(12, result.row("jaguar").onServer)
        assertEquals(40, result.row("snes").onServer)
        assertEquals(1, result.row("saturn").games)
        assertTrue(result.systems.none { "bios" in it.folders })
        assertTrue(result.row("jaguar").wanted)
        assertFalse(result.row("gba").wanted)
    }

    @Test fun coresAreListedWithWhatUsesThem() {
        core("genesis_plus_gx", "Genesis Plus GX")
        core("mame2003_plus", "MAME 2003-Plus", "Arcade (various)")
        val cores = build().cores
        val genesis = cores.first { it.id == "genesis_plus_gx" }
        assertTrue(genesis.present)
        assertEquals(listOf("Genesis", "Master System", "Game Gear", "SG-1000", "Sega CD"), genesis.systems)
        assertFalse(cores.first { it.id == "snes9x" }.present)
        val extra = cores.first { it.id == "mame2003_plus" }
        assertEquals("MAME 2003-Plus", extra.name)
        assertEquals(listOf("Arcade (various)"), extra.systems)
        assertFalse(extra.used)
    }

    private fun ryujinxWithNeeds(appKey: String): EmulatorLinks.Link {
        val profile = EmulatorProfiles.of("ryujinx")!!
        return EmulatorLinks.Link("ryujinx", "Ryujinx", profile.system, profile.folders, profile.exts, appKey, "/usr/local/bin/droiddeck-flatpak-run",
            listOf("io.github.ryubing.Ryujinx"), profile.romArgs, null, profile.config, profile.needs)
    }

    @Test fun ryujinxNeedsItsKeysAndThenItsFirmware() {
        val link = ryujinxWithNeeds("flatpak:io.github.ryubing.Ryujinx")
        fun row() = build(links = listOf(link)).row("switch")
        File(roms, "Switch/bios").mkdirs()
        assertEquals(State.NEEDS_FILES, row().state)
        assertEquals(listOf("prod.keys", "firmware"), row().needs.map { it.name })
        assertEquals("~/.var/app/io.github.ryubing.Ryujinx/config/Ryujinx/system", row().needs.first().where)
        assertTrue(row().needs.first().copies.isEmpty())

        File(roms, "Switch/bios/title.keys").writeText("t")
        assertTrue(row().needs.first().copies.isEmpty())
        File(roms, "Switch/bios/prod.keys").writeText("k")
        val copies = row().needs.first().copies
        assertEquals(listOf("prod.keys", "title.keys"), copies.map { it.first.name })
        copies.forEach { (from, to) -> to.parentFile!!.mkdirs(); from.copyTo(to) }

        val firmware = row().needs.single()
        assertEquals("firmware", firmware.name)
        assertTrue(firmware.inApp)
        File(rootfs, "root/.var/app/io.github.ryubing.Ryujinx/config/Ryujinx/bis/system/Contents/registered/a.nca")
            .apply { parentFile!!.mkdirs(); writeText("x") }
        assertEquals(State.READY, row().state)
    }

    @Test fun anAppImageKeepsItsSettingsInTheUsualFolder() {
        val row = build(links = listOf(ryujinxWithNeeds("appimage:ryujinx"))).row("switch")
        assertEquals("~/.config/Ryujinx/system", row.needs.first().where)
    }

    @Test fun aRowCarriesItsServerPlatformForBiosFiles() {
        val row = build(platforms = listOf(EmulatorInventory.Platform("segacd", "Sega CD", 4, id = "17", slug = "segacd"))).row("segacd")
        assertEquals("17", row.platformId)
        assertEquals("segacd", row.slug)
        assertEquals("", build().row("segacd").platformId)
    }
}
