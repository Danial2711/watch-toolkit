package dev.kafli.rftoolkit.modules.wifi

/**
 * Parsers for the 802.11 Information Elements that matter for security analysis.
 * Pure Kotlin — unit-tested against IEs recorded on the watch.
 *
 * References: IEEE 802.11-2020 §9.4.2.24 (RSNE), Wi-Fi Simple Configuration spec (WPS TLVs).
 */

/** Raw IE as delivered by `ScanResult.getInformationElements()`. */
data class RawIe(val id: Int, val idExt: Int, val body: ByteArray) {
    override fun equals(other: Any?) =
        other is RawIe && id == other.id && idExt == other.idExt && body.contentEquals(other.body)
    override fun hashCode() = 31 * (31 * id + idExt) + body.contentHashCode()
}

object IeIds {
    const val SSID = 0
    const val COUNTRY = 7
    const val RSN = 48
    const val VENDOR = 221
}

/** Cipher suite selectors (00-0F-AC-xx for RSN, 00-50-F2-xx for WPA1). */
enum class Cipher { WEP40, TKIP, CCMP, WEP104, BIP, GCMP, GCMP256, CCMP256, GROUP, OTHER;
    companion object {
        fun of(type: Int) = when (type) {
            0 -> GROUP; 1 -> WEP40; 2 -> TKIP; 4 -> CCMP; 5 -> WEP104; 6 -> BIP
            8 -> GCMP; 9 -> GCMP256; 10 -> CCMP256; else -> OTHER
        }
    }
}

/** Authentication and Key Management suites. */
enum class Akm { EAP, PSK, FT_EAP, FT_PSK, EAP_SHA256, PSK_SHA256, SAE, FT_SAE, SUITE_B, SUITE_B_192, OWE, SAE_EXT, FT_SAE_EXT, OTHER;
    val isEap get() = this == EAP || this == FT_EAP || this == EAP_SHA256 || this == SUITE_B || this == SUITE_B_192
    val isPsk get() = this == PSK || this == FT_PSK || this == PSK_SHA256
    val isSae get() = this == SAE || this == FT_SAE || this == SAE_EXT || this == FT_SAE_EXT

    companion object {
        fun ofRsn(type: Int) = when (type) {
            1 -> EAP; 2 -> PSK; 3 -> FT_EAP; 4 -> FT_PSK; 5 -> EAP_SHA256; 6 -> PSK_SHA256
            8 -> SAE; 9 -> FT_SAE; 11 -> SUITE_B; 12 -> SUITE_B_192; 18 -> OWE
            24 -> SAE_EXT; 25 -> FT_SAE_EXT; else -> OTHER
        }
        fun ofWpa(type: Int) = when (type) { 1 -> EAP; 2 -> PSK; else -> OTHER }
    }
}

data class RsnInfo(
    val version: Int,
    val group: Cipher,
    val pairwise: List<Cipher>,
    val akms: List<Akm>,
    /** Management Frame Protection required (MFPR, bit 6). */
    val mfpRequired: Boolean,
    /** Management Frame Protection capable (MFPC, bit 7). */
    val mfpCapable: Boolean,
)

data class WpaInfo(val group: Cipher, val pairwise: List<Cipher>, val akms: List<Akm>)

data class WpsInfo(
    /** 1 = not configured (factory/setup state), 2 = configured. */
    val state: Int?,
    val apSetupLocked: Boolean?,
    /** Registrar currently active — e.g. the WPS push button was just pressed. */
    val selectedRegistrar: Boolean?,
    val devicePasswordId: Int?,
    val manufacturer: String?,
    val modelName: String?,
    val modelNumber: String?,
    val deviceName: String?,
    val serialNumber: String?,
) {
    val unconfigured get() = state == 1
    /** Human-readable "Manufacturer Model (number)". */
    val deviceLabel: String?
        get() = listOfNotNull(manufacturer, modelName, modelNumber?.let { "($it)" })
            .filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
}

object Ies {
    private val OUI_RSN = byteArrayOf(0x00, 0x0F, 0xAC.toByte())
    private val OUI_MS = byteArrayOf(0x00, 0x50, 0xF2.toByte())
    private val OUI_WFA = byteArrayOf(0x50, 0x6F, 0x9A.toByte())

    fun rsn(ies: List<RawIe>): RsnInfo? = ies.firstOrNull { it.id == IeIds.RSN }?.let { parseRsn(it.body) }

    fun wpa(ies: List<RawIe>): WpaInfo? =
        vendor(ies, OUI_MS, 0x01)?.let { parseWpa(it.copyOfRange(4, it.size)) }

    fun wps(ies: List<RawIe>): WpsInfo? {
        // WPS data may be split across several vendor IEs; concatenate their payloads.
        val parts = ies.filter { isVendor(it, OUI_MS, 0x04) }.map { it.body.copyOfRange(4, it.body.size) }
        if (parts.isEmpty()) return null
        return parseWpsTlvs(parts.fold(ByteArray(0)) { acc, b -> acc + b })
    }

    /** OWE transition element: an "open" BSS that pairs with a hidden OWE BSS. */
    fun hasOweTransition(ies: List<RawIe>): Boolean = vendor(ies, OUI_WFA, 0x1C) != null

    /** True if the SSID element is missing, empty or all zero bytes (hidden network). */
    fun ssidHidden(ies: List<RawIe>): Boolean {
        val s = ies.firstOrNull { it.id == IeIds.SSID } ?: return true
        return s.body.isEmpty() || s.body.all { it.toInt() == 0 }
    }

    fun country(ies: List<RawIe>): String? =
        ies.firstOrNull { it.id == IeIds.COUNTRY && it.body.size >= 2 }
            ?.let { String(it.body.copyOfRange(0, 2), Charsets.US_ASCII) }
            ?.takeIf { it.all { c -> c.isLetter() } }

    // ------------------------------------------------------------------ internals

    private fun isVendor(ie: RawIe, oui: ByteArray, type: Int) =
        ie.id == IeIds.VENDOR && ie.body.size >= 4 &&
            ie.body[0] == oui[0] && ie.body[1] == oui[1] && ie.body[2] == oui[2] &&
            (ie.body[3].toInt() and 0xFF) == type

    private fun vendor(ies: List<RawIe>, oui: ByteArray, type: Int): ByteArray? =
        ies.firstOrNull { isVendor(it, oui, type) }?.body

    private class Reader(val b: ByteArray, var i: Int = 0) {
        fun left() = b.size - i
        fun u8() = b[i++].toInt() and 0xFF
        fun u16le() = u8() or (u8() shl 8)
        fun u16be() = (u8() shl 8) or u8()
        fun suite(): Pair<ByteArray, Int> = Pair(b.copyOfRange(i, i + 3), b[i + 3].toInt() and 0xFF).also { i += 4 }
    }

    fun parseRsn(body: ByteArray): RsnInfo? {
        val r = Reader(body)
        if (r.left() < 2) return null
        val version = r.u16le()
        // Defaults per spec when optional fields are absent: CCMP / CCMP / EAP.
        var group = Cipher.CCMP
        var pairwise = listOf(Cipher.CCMP)
        var akms = listOf(Akm.EAP)
        var caps = 0
        if (r.left() >= 4) group = suiteCipher(r.suite(), OUI_RSN)
        if (r.left() >= 2) {
            val n = r.u16le()
            if (r.left() < 4 * n) return null
            pairwise = List(n) { suiteCipher(r.suite(), OUI_RSN) }
        }
        if (r.left() >= 2) {
            val n = r.u16le()
            if (r.left() < 4 * n) return null
            akms = List(n) { r.suite().let { (oui, t) -> if (oui.contentEquals(OUI_RSN)) Akm.ofRsn(t) else Akm.OTHER } }
        }
        if (r.left() >= 2) caps = r.u16le()
        return RsnInfo(
            version = version, group = group, pairwise = pairwise, akms = akms,
            mfpRequired = caps and (1 shl 6) != 0,
            mfpCapable = caps and (1 shl 7) != 0,
        )
    }

    /** [body] starts after the 00-50-F2-01 header. */
    fun parseWpa(body: ByteArray): WpaInfo? {
        val r = Reader(body)
        if (r.left() < 2) return null
        r.u16le() // version
        var group = Cipher.TKIP
        var pairwise = listOf(Cipher.TKIP)
        var akms = listOf(Akm.EAP)
        if (r.left() >= 4) group = suiteCipher(r.suite(), OUI_MS)
        if (r.left() >= 2) {
            val n = r.u16le()
            if (r.left() < 4 * n) return null
            pairwise = List(n) { suiteCipher(r.suite(), OUI_MS) }
        }
        if (r.left() >= 2) {
            val n = r.u16le()
            if (r.left() < 4 * n) return null
            akms = List(n) { r.suite().let { (oui, t) -> if (oui.contentEquals(OUI_MS)) Akm.ofWpa(t) else Akm.OTHER } }
        }
        return WpaInfo(group, pairwise, akms)
    }

    private fun suiteCipher(s: Pair<ByteArray, Int>, expectedOui: ByteArray) =
        if (s.first.contentEquals(expectedOui)) Cipher.of(s.second) else Cipher.OTHER

    /** WPS attributes are big-endian TLVs: type(2) length(2) value. */
    fun parseWpsTlvs(body: ByteArray): WpsInfo {
        var state: Int? = null
        var locked: Boolean? = null
        var selReg: Boolean? = null
        var pwdId: Int? = null
        var manufacturer: String? = null
        var modelName: String? = null
        var modelNumber: String? = null
        var deviceName: String? = null
        var serial: String? = null
        val r = Reader(body)
        while (r.left() >= 4) {
            val type = r.u16be()
            val len = r.u16be()
            if (len > r.left()) break
            val v = body.copyOfRange(r.i, r.i + len)
            r.i += len
            fun str() = String(v, Charsets.UTF_8).trim { it <= ' ' }.ifEmpty { null }
            when (type) {
                0x1044 -> state = v.firstOrNull()?.toInt()?.and(0xFF)
                0x1057 -> locked = v.firstOrNull()?.let { it.toInt() != 0 }
                0x1041 -> selReg = v.firstOrNull()?.let { it.toInt() != 0 }
                0x1012 -> pwdId = if (v.size >= 2) ((v[0].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF) else null
                0x1021 -> manufacturer = str()
                0x1023 -> modelName = str()
                0x1024 -> modelNumber = str()
                0x1011 -> deviceName = str()
                0x1042 -> serial = str()
            }
        }
        return WpsInfo(state, locked, selReg, pwdId, manufacturer, modelName, modelNumber, deviceName, serial)
    }

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)).toByte() }
}
