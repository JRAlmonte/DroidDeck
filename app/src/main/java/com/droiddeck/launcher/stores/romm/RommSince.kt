package com.droiddeck.launcher.stores.romm

/**
 * The RomM release that first has each call or parameter the store uses only where the server has it, as major and minor,
 * read off the server's source at each release tag. Everything else the store asks for is in 4.8, the oldest it signs in to.
 */
internal object RommSince {
    /** `POST` and `DELETE /api/activity/heartbeat`. */
    val PRESENCE = listOf(5, 0)

    /** `has_saves` and `has_states` of `GET /api/roms`. */
    val SAVE_FILTERS = listOf(5, 0)

    /** `GET /api/roms/random`. */
    val RANDOM = listOf(5, 2)

    /** `GET /api/roms/{id}/similar`. */
    val SIMILAR = listOf(5, 3)

    /** `POST /api/devices/{id}/installs/claim` and `PUT /api/devices/{id}/installs/{request}`. */
    val SENT = listOf(5, 4)
}
