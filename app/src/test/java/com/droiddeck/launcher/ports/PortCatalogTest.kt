package com.droiddeck.launcher.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class PortCatalogTest {
    private fun entry(vararg changes: Pair<String, String>): String {
        val fields = linkedMapOf(
            "id" to "\"demo\"", "name" to "\"Demo Port\"", "game" to "\"Demo Game\"", "repo" to "\"owner/demo-port\"",
            "asset" to "\"Linux-ARM64\\\\.zip$\"", "entry" to "\"bin/demo\"", "folders" to "[\"SNES\"]", "types" to "[\"sfc\"]",
            "romSize" to "1024", "romSha1" to "\"${"ab".repeat(20)}\"", "stagedRom" to "\"game.sfc\"",
        )
        for ((k, v) in changes) if (v.isEmpty()) fields.remove(k) else fields[k] = v
        return "{" + fields.entries.joinToString(",") { "\"${it.key}\":${it.value}" } + "}"
    }

    private fun catalog(vararg entries: String) = "{\"ports\":[${entries.joinToString(",")}]}"

    @Test fun theListInTheRepositoryIsAUsableExample() {
        val file = listOf(File("../ports/ports.json"), File("ports/ports.json")).first { it.isFile }
        val port = PortCatalog.parse(file.readText())!!.single()
        assertEquals("zelda64recomp", port.id)
        assertEquals("Zelda64Recomp/Zelda64Recomp", port.source)
        assertEquals(RomForm.N64, port.form)
        assertEquals(listOf("portable.txt"), port.markers)
    }

    @Test fun theAppShipsWithNoPorts() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(emptyList<Port>(), PortCatalog.user(app))
        val asked = ArrayList<String>()
        assertEquals(false, PortCatalog.refresh(app, now = 1L) { asked += it; null })
        assertEquals(emptyList<String>(), asked)
    }

    @Test fun anEntryIsReadWithItsDefaults() {
        val port = PortCatalog.parse(catalog(entry()))!!.single()
        assertEquals("demo", port.id)
        assertEquals(listOf("snes"), port.systemFolders)
        assertEquals(RomForm.PLAIN, port.form)
        assertEquals("/opt/droiddeck-ports/demo/bin/demo", port.program)
        assertTrue(port.asset.containsMatchIn("Demo-v2-linux-arm64.zip"))
        assertEquals(emptyList<String>(), port.markers)
    }

    @Test fun anEntryThatIsIncompleteOrUnsafeIsLeftOut() {
        val bad = listOf(
            entry("id" to "\"../x\""), entry("repo" to "\"https://evil.example/x\""), entry("repo" to ""), entry("asset" to "\"(\""),
            entry("entry" to "\"../../bin/sh\""), entry("entry" to "\"/bin/sh\""), entry("stagedRom" to "\"..\\\\rom\""),
            entry("romSha1" to "\"abc\""), entry("romSize" to "0"), entry("form" to "\"wii\""), entry("folders" to "[]"),
            entry("markers" to "[\"../marker\"]"),
        )
        assertEquals(listOf("demo"), PortCatalog.parse(catalog(*(bad + entry()).toTypedArray()))!!.map { it.id })
        assertNull(PortCatalog.parse("not json"))
        assertNull(PortCatalog.parse("{\"other\":1}"))
    }

    @Test fun aListThatCannotBeUsedIsNotStored() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = PortSources.parse("someone/ports")!!
        assertTrue(PortSources.add(app, source))
        val start = 2000L * 60 * 60 * 1000
        assertEquals(false, PortCatalog.refresh(app, now = start) { "{\"ports\":[]}" })
        assertEquals(emptyList<Port>(), PortCatalog.cached(app, source))
        assertEquals(true, PortCatalog.refresh(app, now = start + 100L * 60 * 60 * 1000) { catalog(entry()) })
        assertEquals(listOf("demo"), PortCatalog.cached(app, source).map { it.id })
        assertEquals(false, PortCatalog.refresh(app, now = start + 100L * 60 * 60 * 1000 + 5) { catalog(entry()) })
        PortCatalog.forget(app, source)
    }

    @Test fun ofTwoPortsWithOneIdTheFirstSourceWins() {
        val first = PortCatalog.parse(catalog(entry("name" to "\"First\"")), "someone/ports")!!
        val second = PortCatalog.parse(catalog(entry("name" to "\"Second\""), entry("id" to "\"other\"")), "else/ports")!!
        Ports.use(first + second)
        assertEquals(listOf("demo", "other"), Ports.all.map { it.id })
        assertEquals("First", Ports.byId("demo")!!.name)
        assertEquals("someone/ports", Ports.byId("demo")!!.source)
        Ports.use(emptyList())
    }

    @Test fun aWrittenListReadsBackTheSame() {
        val open = entry("entry" to "", "stagedRom" to "")
        val full = entry("id" to "\"full\"", "form" to "\"n64\"", "markers" to "[\"portable.txt\"]", "repo" to "\"owner/a.b\"")
        val before = PortCatalog.parse(catalog(open, full))!!
        val text = PortCatalog.write(before)
        assertTrue("\"repo\": \"owner/a.b\"" in text)
        val after = PortCatalog.parse(text)!!
        assertEquals(before.map { it.id }, after.map { it.id })
        for ((a, b) in before.zip(after)) {
            assertEquals(a.name, b.name); assertEquals(a.game, b.game); assertEquals(a.repo, b.repo)
            assertEquals(a.asset.pattern, b.asset.pattern); assertEquals(a.entry, b.entry)
            assertEquals(a.systemFolders, b.systemFolders); assertEquals(a.exts, b.exts); assertEquals(a.form, b.form)
            assertEquals(a.romSize, b.romSize); assertEquals(a.romSha1, b.romSha1)
            assertEquals(a.stagedRom, b.stagedRom); assertEquals(a.markers, b.markers)
        }
    }

    @Test fun aListIsCutOffAtItsLimits() {
        val many = (0 until 250).map { entry("id" to "\"p$it\"") }.toTypedArray()
        assertEquals(200, PortCatalog.parse(catalog(*many))!!.size)
        assertNull(PortCatalog.parse(catalog(entry("name" to "\"${"x".repeat(600_000)}\""))))
    }

    @Test fun anyEntryMayLeaveItsProgramAndStagedNameOpen() {
        val open = entry("entry" to "", "stagedRom" to "")
        val port = PortCatalog.parse(catalog(open))!!.single()
        assertEquals("", port.entry)
        assertEquals("", port.stagedRom)
        assertEquals("owner/demo-port", port.source)
        assertEquals("someone/ports", PortCatalog.single(org.json.JSONObject(open), "someone/ports")!!.source)
        assertNull(PortCatalog.single(org.json.JSONObject(entry("entry" to "\"../x\"")), "owner/demo-port"))
    }

    @Test fun aListTheUserAddedIsFetchedAndKeptApart() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = PortSources.parse("someone/ports@dev")!!
        assertTrue(PortSources.add(app, source))
        val asked = ArrayList<String>()
        val now = 1000L * 60 * 60 * 1000
        PortCatalog.refresh(app, now = now) { asked += it; if ("someone" in it) "nonsense" else null }
        assertTrue("https://raw.githubusercontent.com/someone/ports/dev/ports/ports.json" in asked)
        assertTrue(source.key in PortCatalog.failed)
        assertEquals(emptyList<Port>(), PortCatalog.user(app))
        assertEquals(true, PortCatalog.refresh(app, now = now + 1, force = source) { if ("someone" in it) catalog(entry()) else null })
        assertEquals(false, source.key in PortCatalog.failed)
        assertEquals(listOf("someone/ports"), PortCatalog.user(app).map { it.source })
        PortCatalog.forget(app, source)
        assertEquals(emptyList<Port>(), PortCatalog.user(app))
    }
}
