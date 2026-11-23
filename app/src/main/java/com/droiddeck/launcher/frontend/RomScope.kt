package com.droiddeck.launcher.frontend

data class RomScope(val game: String?, val system: String?) {
    val keys: List<String> get() = listOfNotNull(game, system)

    companion object {
        private const val ROMS = "/root/ROMs/"

        fun of(emulatorId: String, guestPath: String, systems: List<Library.RomSystem> = Library.romSystems()): RomScope {
            val inside = guestPath.startsWith(ROMS)
            val path = if (inside) guestPath.removePrefix(ROMS) else guestPath
            val folder = if (inside && '/' in path) path.substringBefore('/').lowercase() else null
            return RomScope(key("rom:", path), system(emulatorId, folder, systems))
        }

        fun ofPort(portId: String, folders: List<String>, rom: java.io.File?, romsRoot: java.io.File?, systems: List<Library.RomSystem> = Library.romSystems()): RomScope {
            val held = rom?.takeIf { romsRoot != null && it.path.startsWith(romsRoot.path + java.io.File.separator) }
                ?.relativeTo(romsRoot!!)?.invariantSeparatorsPath
            if (held != null) return of("", ROMS + held, systems).let { it.copy(system = it.system ?: system("", folders.firstOrNull(), systems)) }
            return RomScope(key("rom:", "port/$portId"), system("", folders.firstOrNull(), systems))
        }

        fun system(emulatorId: String, folder: String?, systems: List<Library.RomSystem> = Library.romSystems()): String? {
            val known = folder?.let { name -> systems.firstOrNull { name in it.folders } }
                ?: systems.filter { it.emulatorId == emulatorId }.singleOrNull()
            return key("system:", known?.folders?.firstOrNull() ?: folder.orEmpty())
        }

        private fun key(prefix: String, name: String) = (prefix + name).takeIf { com.droiddeck.launcher.input.validScope(it) }
    }
}
