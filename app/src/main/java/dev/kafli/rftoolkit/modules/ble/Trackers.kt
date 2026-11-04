package dev.kafli.rftoolkit.modules.ble

enum class TrackerKind(val label: String) {
    APPLE_FIND_MY("Apple Find My"),
    GOOGLE_FMDN("Google Find My Device"),
    SAMSUNG_SMARTTAG("Samsung SmartTag"),
    TILE("Tile"),
}

/**
 * @param separated true = broadcasting away from its owner (the state that matters for stalking),
 *                  false = owner's device nearby, null = protocol doesn't tell us (yet).
 */
data class TrackerInfo(val kind: TrackerKind, val separated: Boolean?, val detail: String)

/**
 * Identifies location-tracker advertisements. Sources: OpenHaystack / "Who Can Find My Devices?"
 * (Apple offline finding, type 0x12), Google Find My Device Network spec (Eddystone frame 0x40/0x41),
 * Bluetooth SIG assigned numbers for Samsung (0xFD5A) and Tile (0xFEED/0xFEEC).
 */
object Trackers {
    const val APPLE = 0x004C
    const val APPLE_OFFLINE_FINDING = 0x12
    const val UUID_FMDN = 0xFEAA
    const val UUID_SMARTTAG = 0xFD5A
    val UUIDS_TILE = setOf(0xFEED, 0xFEEC)

    fun classify(adv: AdvData): TrackerInfo? {
        adv.manufacturer[APPLE]?.let { d ->
            // Apple Continuity: [type][len][payload...]
            if (d.size >= 2 && (d[0].toInt() and 0xFF) == APPLE_OFFLINE_FINDING) {
                val len = d[1].toInt() and 0xFF
                return when {
                    len >= 0x19 -> TrackerInfo(TrackerKind.APPLE_FIND_MY, true, "Offline-finding, separated (full key)")
                    else -> TrackerInfo(TrackerKind.APPLE_FIND_MY, false, "Offline-finding, owner nearby")
                }
            }
        }
        adv.serviceData16[UUID_FMDN]?.let { d ->
            when (d.firstOrNull()?.toInt()?.and(0xFF)) {
                0x40 -> return TrackerInfo(TrackerKind.GOOGLE_FMDN, null, "FMDN frame")
                0x41 -> return TrackerInfo(TrackerKind.GOOGLE_FMDN, true, "FMDN, unwanted-tracking protection mode")
            }
        }
        if (UUID_SMARTTAG in adv.serviceData16 || UUID_SMARTTAG in adv.uuids16) {
            return TrackerInfo(TrackerKind.SAMSUNG_SMARTTAG, null, "SmartTag")
        }
        if (UUIDS_TILE.any { it in adv.serviceData16 || it in adv.uuids16 }) {
            return TrackerInfo(TrackerKind.TILE, null, "Tile")
        }
        return null
    }
}
