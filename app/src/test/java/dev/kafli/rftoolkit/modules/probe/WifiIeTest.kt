package dev.kafli.rftoolkit.modules.probe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiIeTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun detectsWpsVendorIe() =
        assertTrue(WifiIe.isWps(221, bytes(0x00, 0x50, 0xF2, 0x04, 0x10, 0x4A)))

    @Test fun rejectsWpaVendorIe() =
        assertFalse(WifiIe.isWps(221, bytes(0x00, 0x50, 0xF2, 0x01)))

    @Test fun rejectsOtherIeIds() =
        assertFalse(WifiIe.isWps(48, bytes(0x00, 0x50, 0xF2, 0x04)))

    @Test fun rejectsShortBody() =
        assertFalse(WifiIe.isWps(221, bytes(0x00, 0x50)))
}
