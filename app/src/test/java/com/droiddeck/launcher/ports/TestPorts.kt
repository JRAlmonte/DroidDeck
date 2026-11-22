package com.droiddeck.launcher.ports

object TestPorts {
    val zelda = Port(
        id = "zelda64recomp", name = "Zelda 64: Recompiled", game = "The Legend of Zelda: Majora's Mask", repo = "Zelda64Recomp/Zelda64Recomp",
        asset = Regex("Linux-ARM64\\.zip$", RegexOption.IGNORE_CASE), entry = "Zelda64Recompiled", systemFolders = listOf("n64"),
        exts = setOf("z64", "n64", "v64"), form = RomForm.N64, romSize = 32L * 1024 * 1024,
        romSha1 = "d6133ace5afaa0882cf214cf88daba39e266c078", stagedRom = "mm.n64.us.1.0.z64", markers = listOf("portable.txt"),
    )
}
