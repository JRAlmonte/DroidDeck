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

    @Test fun theListInTheRepositoryIsTheBuiltInOne() {
        val file = listOf(File("../ports/ports.json"), File("ports/ports.json")).first { it.isFile }
        val listed = PortCatalog.parse(file.readText())!!
        assertEquals(Ports.builtIn.map { it.id }, listed.map { it.id })
        for ((a, b) in listed.zip(Ports.builtIn)) {
            assertEquals(b.name, a.name); assertEquals(b.game, a.game); assertEquals(b.repo, a.repo)
            assertEquals(b.asset.pattern, a.asset.pattern); assertEquals(b.entry, a.entry)
            assertEquals(b.systemFolders, a.systemFolders); assertEquals(b.exts, a.exts); assertEquals(b.form, a.form)
            assertEquals(b.romSize, a.romSize); assertEquals(b.romSha1, a.romSha1)
            assertEquals(b.stagedRom, a.stagedRom); assertEquals(b.markers, a.markers)
        }
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

    @Test fun aFetchedEntryReplacesTheBuiltInOneOfTheSameName() {
        val fetched = PortCatalog.parse(catalog(entry("id" to "\"zelda64recomp\"", "name" to "\"Renamed\""), entry()))!!
        val merged = PortCatalog.merge(Ports.builtIn, fetched)
        assertEquals(listOf("zelda64recomp", "demo"), merged.map { it.id })
        assertEquals("Renamed", merged.first().name)
        assertEquals(Ports.builtIn.map { it.id }, PortCatalog.merge(Ports.builtIn, emptyList()).map { it.id })
    }

    @Test fun aListThatCannotBeUsedIsNotStored() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(false, PortCatalog.refresh(app, now = 1L) { "{\"ports\":[]}" })
        assertEquals(emptyList<Port>(), PortCatalog.cached(app))
        assertEquals(true, PortCatalog.refresh(app, now = 100L * 60 * 60 * 1000) { catalog(entry()) })
        assertEquals(listOf("demo"), PortCatalog.cached(app).map { it.id })
        assertEquals(false, PortCatalog.refresh(app, now = 100L * 60 * 60 * 1000 + 5) { catalog(entry()) })
    }
}
