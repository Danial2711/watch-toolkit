package dev.kafli.rftoolkit.modules.nfc

import dev.kafli.rftoolkit.modules.wifi.Ies
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NdefTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun uriRecordUsesPrefixCode() {
        // D1 01 0C 55 04 "example.com" — the canonical example from the RTD URI spec.
        assertEquals("d1010c5504" + hex("example.com".toByteArray()), hex(Ndef.uriRecord("https://example.com")))
    }

    @Test fun longestPrefixWins() = assertEquals(0x02, Ndef.uriRecord("https://www.vut.cz")[4].toInt())

    @Test fun unknownSchemeKeptVerbatim() = assertEquals(0x00, Ndef.uriRecord("geo:49.2,16.6")[4].toInt())

    @Test fun textRecord() {
        assertEquals("d101085402656e" + hex("Hello".toByteArray()), hex(Ndef.textRecord("Hello")))
    }

    @Test fun longPayloadUsesFourByteLength() {
        val r = Ndef.textRecord("x".repeat(300))
        assertEquals(0, r[0].toInt() and 0x10) // SR flag cleared
        assertEquals(303, ((r[2].toInt() and 0xFF) shl 24) or ((r[3].toInt() and 0xFF) shl 16) or ((r[4].toInt() and 0xFF) shl 8) or (r[5].toInt() and 0xFF))
    }
}

class Type4TagTest {
    private fun apdu(hex: String) = Ies.hexToBytes(hex.replace(" ", ""))
    private fun sw(r: ByteArray) = "%02X%02X".format(r[r.size - 2], r[r.size - 1])
    private fun data(r: ByteArray) = r.copyOfRange(0, r.size - 2)

    private val msg = Ndef.uriRecord("https://example.com")

    /** Full read sequence as performed by Android's NFC stack. */
    @Test fun readerFlowReturnsMessage() {
        val t = Type4Tag(msg)
        assertEquals("9000", sw(t.process(apdu("00 A4 04 00 07 D2760000850101 00"))))
        assertEquals("9000", sw(t.process(apdu("00 A4 00 0C 02 E103"))))
        val cc = t.process(apdu("00 B0 0000 0F"))
        assertEquals("9000", sw(cc))
        assertArrayEquals(apdu("000F 20 00FB 00FF 0406 E104 0400 00 FF"), data(cc))
        assertEquals("9000", sw(t.process(apdu("00 A4 00 0C 02 E104"))))
        val nlen = t.process(apdu("00 B0 0000 02"))
        assertEquals(msg.size, ((data(nlen)[0].toInt() and 0xFF) shl 8) or (data(nlen)[1].toInt() and 0xFF))
        assertArrayEquals(msg, data(t.process(apdu("00 B0 0002 %02X".format(msg.size)))))
    }

    @Test fun selectWithoutLeAccepted() =
        assertEquals("9000", sw(Type4Tag(msg).process(apdu("00 A4 04 00 07 D2760000850101"))))

    @Test fun unknownAidNotFound() =
        assertEquals("6A82", sw(Type4Tag(msg).process(apdu("00 A4 04 00 07 F0010203040506 00"))))

    @Test fun fileSelectBeforeAppRejected() =
        assertEquals("6986", sw(Type4Tag(msg).process(apdu("00 A4 00 0C 02 E103"))))

    @Test fun readBeforeSelectRejected() {
        val t = Type4Tag(msg)
        t.process(apdu("00 A4 04 00 07 D2760000850101 00"))
        assertEquals("6986", sw(t.process(apdu("00 B0 0000 0F"))))
    }

    @Test fun writesRefused() {
        val t = Type4Tag(msg)
        t.process(apdu("00 A4 04 00 07 D2760000850101 00"))
        t.process(apdu("00 A4 00 0C 02 E104"))
        assertEquals("6982", sw(t.process(apdu("00 D6 0000 02 0000"))))
    }

    @Test fun malformedInputsDontCrash() {
        val t = Type4Tag(msg)
        assertEquals("6700", sw(t.process(apdu("00A4"))))
        assertEquals("6700", sw(t.process(apdu("00 A4 04 00 09 D276"))))
        assertEquals("6E00", sw(t.process(apdu("80 A4 04 00"))))
        assertEquals("6D00", sw(t.process(apdu("00 CA 0000"))))
    }

    @Test fun offsetBeyondFileRejected() {
        val t = Type4Tag(msg)
        t.process(apdu("00 A4 04 00 07 D2760000850101 00"))
        t.process(apdu("00 A4 00 0C 02 E104"))
        assertEquals("6B00", sw(t.process(apdu("00 B0 0400 10"))))
    }

    @Test fun describe() {
        assertEquals("SELECT CC file", Type4Tag.describe(apdu("00 A4 00 0C 02 E103")))
        assertEquals("READ BINARY @2 len 16", Type4Tag.describe(apdu("00 B0 0002 10")))
    }
}
