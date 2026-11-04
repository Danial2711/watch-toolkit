package dev.kafli.rftoolkit.modules.probe

/**
 * Pure helpers for 802.11 Information Elements (kept free of Android types so they are unit-testable).
 */
object WifiIe {
    const val ID_VENDOR_SPECIFIC = 221
    private val WPS_OUI_TYPE = byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x04)

    /** True if a vendor-specific IE body is a Wi-Fi Protected Setup (WPS) element. */
    fun isWps(id: Int, body: ByteArray): Boolean =
        id == ID_VENDOR_SPECIFIC &&
            body.size >= 4 &&
            (0 until 4).all { body[it] == WPS_OUI_TYPE[it] }
}
