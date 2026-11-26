package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.runtime.AppImageManager
import com.droiddeck.launcher.runtime.FlatpakManager
import com.droiddeck.launcher.runtime.UserApps
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorLinksTest {
    private fun flatpak(id: String, name: String, arch: String? = null) = UserApps.App(
        "flatpak:$id", UserApps.Kind.FLATPAK, name, null, null, FlatpakManager.LAUNCHER,
        if (arch == null) listOf(id) else listOf("--arch=$arch", id),
    )

    private fun appImage(id: String, name: String) =
        UserApps.App("appimage:$id", UserApps.Kind.APPIMAGE, name, null, null, AppImageManager.LAUNCHER, listOf("${AppImageManager.GUEST_DIR}/$id"))

    @After fun clear() = EmulatorLinks.replace(emptyList())

    @Test fun anEmulatorFromTheLinuxStoreIsLinkedToItsSystem() {
        val links = EmulatorLinks.match(listOf(flatpak("org.mozilla.firefox", "Firefox"), flatpak("io.github.ryubing.Ryujinx", "Ryujinx")))
        assertEquals(listOf("ryujinx"), links.map { it.id })
        assertEquals(listOf("switch"), links.single().folders)
        assertEquals(FlatpakManager.LAUNCHER, links.single().program)
        assertEquals(
            listOf("io.github.ryubing.Ryujinx", "--fullscreen", "/root/ROMs/switch/Game.nsp"),
            links.single().launchArgs("/root/ROMs/switch/Game.nsp"),
        )
    }

    @Test fun anImportedAppImageIsKnownByItsName() {
        val link = EmulatorLinks.match(listOf(appImage("eden-v0-0-3", "Eden"))).single()
        assertEquals("eden", link.id)
        assertEquals(listOf("/opt/appimages/user/eden-v0-0-3", "-f", "-g", "/root/ROMs/switch/Game.xci"), link.launchArgs("/root/ROMs/switch/Game.xci"))
    }

    @Test fun anX86FlatpakKeepsItsArchitectureArgument() {
        val link = EmulatorLinks.match(listOf(flatpak("org.flycast.Flycast", "Flycast", "x86_64"))).single()
        assertEquals(listOf("--arch=x86_64", "org.flycast.Flycast", "/root/ROMs/dc/Game.chd"), link.launchArgs("/root/ROMs/dc/Game.chd"))
    }

    @Test fun aLinkTheUserTurnedOffIsLeftOut() {
        assertEquals(emptyList<String>(), EmulatorLinks.match(listOf(flatpak("app.xemu.xemu", "xemu")), setOf("xemu")).map { it.id })
    }

    @Test fun aScriptIsNeverTakenForAnEmulator() {
        val script = UserApps.App("script:ryujinx", UserApps.Kind.SCRIPT, "Ryujinx", null, null, UserApps.SCRIPT_LAUNCHER, listOf("/opt/droiddeck-apps/ryujinx"))
        assertEquals(emptyList<String>(), EmulatorLinks.match(listOf(script)).map { it.id })
    }

    @Test fun aLinkedEmulatorMakesItsSystemPlayable() {
        assertTrue(Library.romSystems().none { "switch" in it.folders })
        EmulatorLinks.replace(EmulatorLinks.match(listOf(flatpak("io.github.ryubing.Ryujinx", "Ryujinx"))))
        assertEquals("ryujinx", Library.romSystems().first { "switch" in it.folders }.emulatorId)
        assertEquals(
            listOf("io.github.ryubing.Ryujinx", "--fullscreen", "/root/ROMs/switch/Game.nsp"),
            Library.launchArgs("ryujinx", "/root/ROMs/switch/Game.nsp"),
        )
        assertNull(Library.packageId("ryujinx"))
    }

    @Test fun theEmulatorTheUserPickedComesFirstForItsFolder() {
        EmulatorLinks.replace(
            EmulatorLinks.match(listOf(flatpak("io.github.ryubing.Ryujinx", "Ryujinx"), flatpak("dev.eden_emu.eden", "Eden"))),
            mapOf("switch" to "eden"),
        )
        assertEquals("eden", Library.romSystems().first { "switch" in it.folders }.emulatorId)
    }

    @Test fun aFlatpakIsAllowedToReadTheRomsFolderOnce() {
        assertEquals("[Context]\nfilesystems=/root/ROMs;\n", EmulatorLinks.withFilesystem("", "/root/ROMs"))
        assertEquals(
            "[Context]\nsockets=x11;\nfilesystems=home;/root/ROMs;\n\n[Environment]\nA=b\n",
            EmulatorLinks.withFilesystem("[Context]\nsockets=x11;\nfilesystems=home;\n\n[Environment]\nA=b\n", "/root/ROMs"),
        )
        assertEquals(
            "[Environment]\nA=b\n\n[Context]\nfilesystems=/root/ROMs;\n",
            EmulatorLinks.withFilesystem("[Environment]\nA=b\n", "/root/ROMs"),
        )
        assertNull(EmulatorLinks.withFilesystem("[Context]\nfilesystems=/root/ROMs:ro;\n", "/root/ROMs"))
    }
}
