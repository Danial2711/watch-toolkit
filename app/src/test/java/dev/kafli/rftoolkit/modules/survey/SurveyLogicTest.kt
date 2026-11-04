package dev.kafli.rftoolkit.modules.survey

import dev.kafli.rftoolkit.modules.wifi.RawIe
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SightingThrottleTest {
    @Test fun firstSightingEmits() = assertTrue(SightingThrottle(10_000).shouldEmit("a", 0))

    @Test fun repeatWithinIntervalSuppressed() {
        val t = SightingThrottle(10_000)
        t.shouldEmit("a", 0)
        assertFalse(t.shouldEmit("a", 9_999))
        assertTrue(t.shouldEmit("a", 10_000))
    }

    @Test fun keysAreIndependent() {
        val t = SightingThrottle(10_000)
        t.shouldEmit("a", 0)
        assertTrue(t.shouldEmit("b", 1))
    }

    @Test fun boundedSize() {
        val t = SightingThrottle(10_000, maxKeys = 3)
        listOf("a", "b", "c", "d").forEachIndexed { i, k -> t.shouldEmit(k, i.toLong()) }
        assertEquals(3, t.size())
        assertTrue("evicted key emits again", t.shouldEmit("a", 5))
    }
}

class WifiChannelsTest {
    @Test fun band24() {
        assertEquals(1, WifiChannels.channel(2412))
        assertEquals(6, WifiChannels.channel(2437))
        assertEquals(13, WifiChannels.channel(2472))
        assertEquals(14, WifiChannels.channel(2484))
    }

    @Test fun band5() {
        assertEquals(36, WifiChannels.channel(5180))
        assertEquals(165, WifiChannels.channel(5825))
        assertEquals("5", WifiChannels.band(5180))
    }

    @Test fun unknown() = assertEquals(-1, WifiChannels.channel(900))
}

class RecordsTest {
    @Test fun hexEncodesUnsigned() = assertEquals("00ff7f80", Records.hex(byteArrayOf(0, -1, 127, -128)))

    @Test fun wifiRecordWithIes() {
        val o = Records.wifi(
            ms = 1, ageMs = 0, bssid = "aa:bb:cc:dd:ee:ff", ssid = "Test", freqMhz = 2437, channelWidth = 0,
            rssi = -60, capabilities = "[WPA2-PSK-CCMP][ESS]", wifiStandard = 4,
            ies = listOf(RawIe(0, 0, "Test".toByteArray())),
            fix = Records.Fix(50.0, 19.9, 5f, null, 1, "fused"),
        )
        val back = JSONObject(o.toString())
        assertEquals("wifi", back.getString("t"))
        assertEquals(6, back.getInt("ch"))
        assertEquals("54657374", back.getJSONArray("ies").getJSONArray(0).getString(2))
        assertEquals(50.0, back.getJSONArray("loc").getDouble(0), 0.0)
    }

    @Test fun wifiRecordWithoutIesOrFix() {
        val o = Records.wifi(1, 0, "x", null, 5180, 1, -70, "[ESS]", 5, null, null)
        assertFalse(o.has("ies"))
        assertTrue(o.isNull("loc"))
        assertTrue(o.isNull("ssid"))
    }

    @Test fun nullRssiSerializesAsNull() =
        assertTrue(Records.wifi(1, 0, "x", "s", 2412, 0, null, "[ESS]", 4, null, null).isNull("rssi"))

    @Test fun iesHashDetectsChange() {
        val a = listOf(RawIe(0, 0, byteArrayOf(1, 2)))
        val b = listOf(RawIe(0, 0, byteArrayOf(1, 3)))
        assertEquals(Records.iesHash(a), Records.iesHash(listOf(RawIe(0, 0, byteArrayOf(1, 2)))))
        assertNotEquals(Records.iesHash(a), Records.iesHash(b))
    }
}

class SurveyPresetTest {
    /** 2 scanner starts per BLE window; Android allows 5 starts / 30 s. */
    @Test fun presetsRespectBleStartLimit() {
        SurveyPreset.entries.filter { !it.bleContinuous }.forEach {
            val windowMs = it.bleOnMs + it.bleOffMs
            val startsPer30s = 2.0 * 30_000 / windowMs
            assertTrue("${it.name}: $startsPer30s starts/30s", startsPer30s <= 4.0)
        }
    }

    @Test fun cycles() = assertEquals(SurveyPreset.LOW, SurveyPreset.MAX.next())
}
