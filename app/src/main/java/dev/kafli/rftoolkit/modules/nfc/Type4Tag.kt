package dev.kafli.rftoolkit.modules.nfc

/**
 * Read-only NFC Forum Type 4 Tag (T4T v2.0) served over Host Card Emulation.
 *
 * Reader flow: SELECT NDEF application (AID D2760000850101) → SELECT CC file (E103) →
 * READ BINARY CC → SELECT NDEF file (E104) → READ BINARY NLEN + message.
 * Pure Kotlin: one instance per reader session, fed command APDUs, returns response APDUs.
 */
class Type4Tag(ndefMessage: ByteArray) {

    companion object {
        val NDEF_AID: ByteArray = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        private val CC_FILE = byteArrayOf(0xE1.toByte(), 0x03)
        private val NDEF_FILE = byteArrayOf(0xE1.toByte(), 0x04)
        const val MAX_NDEF_FILE = 1024
        private const val MLE = 0x00FB // max bytes per READ BINARY response

        val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        val SW_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        val SW_WRONG_P1P2 = byteArrayOf(0x6B, 0x00)
        val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
        val SW_CLA_NOT_SUPPORTED = byteArrayOf(0x6E, 0x00)
        val SW_WRONG_LENGTH = byteArrayOf(0x67, 0x00)
        val SW_NOT_ALLOWED = byteArrayOf(0x69, 0x86.toByte()) // command not allowed (e.g. read before select)
        val SW_SECURITY = byteArrayOf(0x69, 0x82.toByte())    // write attempted on read-only tag

        /** Short human description of a command APDU, for the log. */
        fun describe(apdu: ByteArray): String {
            if (apdu.size < 4) return "Malformed APDU"
            val ins = apdu[1].toInt() and 0xFF
            val p1 = apdu[2].toInt() and 0xFF
            return when {
                ins == 0xA4 && p1 == 0x04 -> "SELECT application"
                ins == 0xA4 && apdu.size >= 7 && apdu[5] == CC_FILE[0] && apdu[6] == CC_FILE[1] -> "SELECT CC file"
                ins == 0xA4 && apdu.size >= 7 && apdu[5] == NDEF_FILE[0] && apdu[6] == NDEF_FILE[1] -> "SELECT NDEF file"
                ins == 0xA4 -> "SELECT file"
                ins == 0xB0 -> "READ BINARY @%d len %d".format(((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF), if (apdu.size > 4) apdu[4].toInt() and 0xFF else 0)
                ins == 0xD6 -> "UPDATE BINARY (write)"
                else -> "INS 0x%02X".format(ins)
            }
        }
    }

    private enum class File { NONE, CC, NDEF }

    private val ndefFile: ByteArray
    private val ccFile: ByteArray
    private var appSelected = false
    private var selected = File.NONE

    init {
        require(ndefMessage.size + 2 <= MAX_NDEF_FILE) { "NDEF message too large (${ndefMessage.size} bytes)" }
        // NDEF file = NLEN (2 bytes, big-endian) + message.
        ndefFile = byteArrayOf((ndefMessage.size ushr 8).toByte(), ndefMessage.size.toByte()) + ndefMessage
        ccFile = byteArrayOf(
            0x00, 0x0F,                                   // CCLEN = 15
            0x20,                                         // mapping version 2.0
            (MLE ushr 8).toByte(), MLE.toByte(),          // MLe
            0x00, 0xFF.toByte(),                          // MLc
            0x04, 0x06,                                   // NDEF File Control TLV
            NDEF_FILE[0], NDEF_FILE[1],
            (MAX_NDEF_FILE ushr 8).toByte(), MAX_NDEF_FILE.toByte(),
            0x00,                                         // read access: granted
            0xFF.toByte(),                                // write access: none (read-only tag)
        )
    }

    fun process(apdu: ByteArray): ByteArray {
        if (apdu.size < 4) return SW_WRONG_LENGTH
        if (apdu[0].toInt() and 0xFF != 0x00) return SW_CLA_NOT_SUPPORTED
        return when (apdu[1].toInt() and 0xFF) {
            0xA4 -> select(apdu)
            0xB0 -> readBinary(apdu)
            0xD6 -> SW_SECURITY
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    private fun select(apdu: ByteArray): ByteArray {
        val p1 = apdu[2].toInt() and 0xFF
        if (apdu.size < 5) return SW_WRONG_LENGTH
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return SW_WRONG_LENGTH
        val data = apdu.copyOfRange(5, 5 + lc)
        return when (p1) {
            0x04 -> if (data.contentEquals(NDEF_AID)) {
                appSelected = true
                selected = File.NONE
                SW_OK
            } else {
                SW_NOT_FOUND
            }
            0x00 -> when {
                !appSelected -> SW_NOT_ALLOWED
                data.contentEquals(CC_FILE) -> { selected = File.CC; SW_OK }
                data.contentEquals(NDEF_FILE) -> { selected = File.NDEF; SW_OK }
                else -> SW_NOT_FOUND
            }
            else -> SW_WRONG_P1P2
        }
    }

    private fun readBinary(apdu: ByteArray): ByteArray {
        val file = when (selected) {
            File.CC -> ccFile
            File.NDEF -> ndefFile
            File.NONE -> return SW_NOT_ALLOWED
        }
        val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
        if (offset > file.size) return SW_WRONG_P1P2
        val le = if (apdu.size >= 5) (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it } else 256
        val end = minOf(file.size, offset + minOf(le, MLE))
        return file.copyOfRange(offset, end) + SW_OK
    }
}
