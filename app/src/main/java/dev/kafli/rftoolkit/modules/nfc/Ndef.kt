package dev.kafli.rftoolkit.modules.nfc

/**
 * Minimal NDEF message builder (NFC Forum NDEF 1.0 / RTD URI / RTD Text).
 * Only well-formed, single-record messages — this is the content of the watch's own tag.
 */
object Ndef {
    private const val MB = 0x80
    private const val ME = 0x40
    private const val SR = 0x10
    private const val TNF_WELL_KNOWN = 0x01

    /** URI identifier codes (RTD URI §3.2.2); longest matching prefix wins. */
    private val URI_PREFIXES = listOf(
        0x02 to "https://www.",
        0x01 to "http://www.",
        0x04 to "https://",
        0x03 to "http://",
        0x05 to "tel:",
        0x06 to "mailto:",
    )

    fun uriRecord(uri: String): ByteArray {
        val (code, rest) = URI_PREFIXES.firstOrNull { uri.startsWith(it.second) }
            ?.let { it.first to uri.removePrefix(it.second) } ?: (0x00 to uri)
        return record(type = "U", payload = byteArrayOf(code.toByte()) + rest.toByteArray(Charsets.UTF_8))
    }

    fun textRecord(text: String, lang: String = "en"): ByteArray {
        val langBytes = lang.toByteArray(Charsets.US_ASCII)
        // Status byte: bit7 = 0 (UTF-8), bits 5..0 = language code length.
        return record(type = "T", payload = byteArrayOf(langBytes.size.toByte()) + langBytes + text.toByteArray(Charsets.UTF_8))
    }

    /** Single well-known record with MB+ME set; short-record form when the payload fits in one byte. */
    fun record(type: String, payload: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val short = payload.size < 256
        val header = MB or ME or TNF_WELL_KNOWN or (if (short) SR else 0)
        val len = if (short) {
            byteArrayOf(payload.size.toByte())
        } else {
            byteArrayOf((payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte())
        }
        return byteArrayOf(header.toByte(), typeBytes.size.toByte()) + len + typeBytes + payload
    }
}
