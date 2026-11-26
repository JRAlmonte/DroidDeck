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

        // A system's key is the first of its folder names whichever emulator is picked for it, so its settings outlive a pick.
        fun system(emulatorId: String, folder: String?, systems: List<Library.RomSystem> = Library.romSystems()): String? {
            val named = systems.filter { !it.picked }
            val known = folder?.let { name -> named.firstOrNull { name in it.folders } }
                ?: named.filter { it.emulatorId == emulatorId }.singleOrNull()
            return key("system:", known?.folders?.firstOrNull() ?: folder.orEmpty())
        }

        private fun key(prefix: String, name: String) = (prefix + name).takeIf { com.droiddeck.launcher.input.validScope(it) }
    }
}
