package com.droiddeck.launcher.ports

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class PortSourcesTest {
    private val app get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    private val rom = PortRoms.Facts("snes", listOf("sfc"), RomForm.PLAIN, 1024, "ab".repeat(20))

    @Test fun aRepositoryIsReadFromWhatWasTyped() {
        assertEquals("owner/ports", PortSources.parse(" owner/ports ")!!.repo)
        assertEquals("dev/next", PortSources.parse("owner/ports@dev/next")!!.branch)
        val url = PortSources.parse("https://github.com/Owner/My.Ports/tree/feat/x")!!
        assertEquals("Owner/My.Ports", url.repo)
        assertEquals("feat/x", url.branch)
        assertEquals("owner/ports", PortSources.parse("github.com/owner/ports.git")!!.repo)
        assertNull(PortSources.parse("owner/ports")!!.branch)
        for (bad in listOf("", "owner", "../ports", "owner/..", "owner/ports@../x", "https://evil.example/owner/ports", "owner/ports@a b", "-owner/x"))
            assertNull(bad, PortSources.parse(bad))
    }

    @Test fun sourcesAreKeptInOrderAndOnlyOnce() {
        val a = PortSources.parse("owner/a")!!
        val b = PortSources.parse("owner/b@dev")!!
        assertTrue(PortSources.add(app, a))
        assertTrue(PortSources.add(app, b))
        assertFalse(PortSources.add(app, PortSources.parse("owner/a")!!))
        assertEquals(listOf("list:owner/a", "list:owner/b@dev"), PortSources.list(app).map { it.key })
        PortSources.remove(app, a.key)
        assertEquals(listOf("owner/b"), PortSources.list(app).map { it.repo })
        assertEquals("owner~b@dev", b.fileName)
    }

    @Test fun onlyDownloadsThisDeviceRunsAreOffered() {
        val names = listOf(
            "Port-v1.2-Linux-ARM64.zip", "Port-v1.2-Linux-X64.zip", "Port-v1.2-Windows-ARM64.zip", "Port-v1.2-macOS-arm64.dmg",
            "port-aarch64.AppImage", "port-android-arm64.apk", "Port-v1.2-Linux-ARM64.zip.sha256",
        )
        assertEquals(listOf("Port-v1.2-Linux-ARM64.zip", "port-aarch64.AppImage"), PortSources.deviceAssets(names))
        val releases = JSONArray("""[
            {"prerelease":true,"assets":[{"name":"new-arm64.zip"}]},
            {"draft":true,"assets":[{"name":"draft-arm64.zip"}]},
            {"assets":[{"name":"only-x64.zip"}]},
            {"assets":[{"name":"full-arm64.zip"}]}]""")
        assertEquals(listOf("full-arm64.zip"), PortSources.offered(releases))
        assertEquals(emptyList<String>(), PortSources.offered(JSONArray("""[{"assets":[{"name":"only-x64.zip"}]}]""")))
    }

    @Test fun aDownloadsPatternOutlivesItsVersion() {
        val pattern = Regex(PortSources.assetPattern("Zelda64Recompiled-v1.2.1-Linux-ARM64.zip"), RegexOption.IGNORE_CASE)
        assertTrue(pattern.containsMatchIn("Zelda64Recompiled-v1.10.0-Linux-ARM64.zip"))
        assertFalse(pattern.containsMatchIn("Zelda64Recompiled-v1.10.0-Linux-ARM64.zip.sha256"))
        assertFalse(pattern.containsMatchIn("Zelda64Recompiled-v1x2x1-Linux-ARM64.zip"))
    }

    @Test fun aPortForOneRomPassesTheListsChecks() {
        val entry = PortSources.single("owner/Cool.Port", "Cool-1.0-arm64.tar.gz", " My Game ", rom, "", "", setOf("u-cool-port"))!!
        assertEquals("u-cool-port-2", entry.getString("id"))
        val port = PortCatalog.single(entry, "owner/Cool.Port")!!
        assertEquals(listOf("snes"), port.systemFolders)
        assertEquals("My Game", port.game)
        assertEquals(rom.sha1, port.romSha1)
        assertTrue(port.asset.containsMatchIn("Cool-2.0-arm64.tar.gz"))
        assertNull(PortSources.single("owner/port", "a-arm64.zip", "Game", rom, "../bin/sh", "", emptySet()))
        assertNull(PortSources.single("owner/port", "a-arm64.zip", "Game", PortRoms.Facts("my folder!", listOf("sfc"), RomForm.PLAIN, 1, rom.sha1), "", "", emptySet()))
    }

    @Test fun theProgramFoundInADownloadIsRemembered() {
        val entry = PortSources.single("owner/port", "a-arm64.zip", "Game", rom, "", "", emptySet())!!
        assertTrue(PortSources.add(app, PortSources.Source("owner/port", null, entry)))
        PortSources.setProgram(app, "u-port", "bin/game")
        assertEquals("bin/game", PortCatalog.user(app).single().entry)
        assertNotNull(PortSources.list(app).single().port)
    }
}
