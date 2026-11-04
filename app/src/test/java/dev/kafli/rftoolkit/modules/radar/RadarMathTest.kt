package dev.kafli.rftoolkit.modules.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class FftTest {
    private fun sine(freq: Double, rate: Int, n: Int, amp: Double = 0.5, noise: Double = 0.0, seed: Int = 1): ShortArray {
        val r = Random(seed)
        return ShortArray(n) { i ->
            val v = amp * sin(2 * PI * freq * i / rate) + noise * (r.nextDouble() * 2 - 1)
            (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Test fun peakAtToneFrequency() {
        val db = Fft.spectrumDb(sine(1_000.0, 48_000, 4096))
        val peak = db.indices.maxByOrNull { db[it] }!!
        assertEquals(1_000.0, peak * 48_000.0 / 4096, 12.0)
    }

    @Test fun impulseIsFlat() {
        val re = DoubleArray(8).also { it[0] = 1.0 }
        val im = DoubleArray(8)
        Fft.transform(re, im)
        re.forEach { assertEquals(1.0, it, 1e-12) }
    }

    @Test fun ultrasonicToneDetectedAfterPersisting() {
        val det = ToneDetector(48_000, 4096)
        val frame = Fft.spectrumDb(sine(18_500.0, 48_000, 4096, amp = 0.05, noise = 0.01))
        assertNull(det.feed(frame))
        assertNull(det.feed(frame))
        val t = det.feed(frame)
        assertNotNull(t)
        assertTrue("freq ${t!!.freqHz}", abs(t.freqHz - 18_500) < 30)
    }

    @Test fun noiseAloneIsNotATone() {
        val det = ToneDetector(48_000, 4096)
        repeat(10) { assertNull(det.feed(Fft.spectrumDb(sine(0.0, 48_000, 4096, amp = 0.0, noise = 0.05, seed = it)))) }
    }

    @Test fun audibleToneIgnored() {
        val det = ToneDetector(48_000, 4096)
        val frame = Fft.spectrumDb(sine(5_000.0, 48_000, 4096, noise = 0.01))
        repeat(5) { assertNull(det.feed(frame)) }
    }

    @Test fun bandLevelsCount() = assertEquals(12, bandLevels(DoubleArray(2048) { -60.0 }, 12).size)
}

class ChannelStatsTest {
    @Test fun aggregatesAndFlagsOverlap() {
        val s = ChannelStats.compute(listOf(1 to -50, 1 to -70, 6 to -60, 3 to -80, 36 to -55, -1 to -40))
        assertEquals(listOf(1, 3, 6, 36), s.map { it.channel })
        assertEquals(2, s.first().aps)
        assertEquals(-50, s.first().bestRssi)
        assertEquals(listOf(3), ChannelStats.overlapping24(s))
        assertEquals("████····", ChannelStats.bar(1, 2))
    }
}

class JammingDetectorTest {
    private fun normal(i: Int) = RadioWindow(wifiAps = 20 + i % 3, bleAdverts = 300 + 10 * (i % 4), gnssCn0 = 38.0 + i % 2)

    @Test fun learnsThenStaysQuietOnNormalTraffic() {
        val d = JammingDetector()
        repeat(20) { assertFalse(d.feed(normal(it)).suspected) }
        assertTrue(d.ready)
    }

    @Test fun simultaneousCollapseIsSuspected() {
        val d = JammingDetector()
        repeat(12) { d.feed(normal(it)) }
        val v = d.feed(RadioWindow(wifiAps = 2, bleAdverts = 10, gnssCn0 = 20.0))
        assertTrue(v.suspected)
        assertEquals(3, v.dropped.size)
    }

    @Test fun singleDomainDropIsNotJamming() {
        val d = JammingDetector()
        repeat(12) { d.feed(normal(it)) }
        val v = d.feed(RadioWindow(wifiAps = 2, bleAdverts = 310, gnssCn0 = 38.0))
        assertFalse(v.suspected)
        assertEquals(setOf(Domain.WIFI), v.dropped)
    }

    @Test fun jammedWindowsDontPoisonBaseline() {
        val d = JammingDetector()
        repeat(12) { d.feed(normal(it)) }
        repeat(20) { assertTrue(d.feed(RadioWindow(1, 5, 15.0)).suspected) }
    }

    @Test fun quietPlacesNeverTrigger() {
        val d = JammingDetector()
        repeat(12) { d.feed(RadioWindow(wifiAps = 2, bleAdverts = 5, gnssCn0 = null)) }
        assertFalse(d.feed(RadioWindow(0, 0, null)).suspected) // baselines below minimums
    }

    @Test fun missingGnssIndoorsIsFine() {
        val d = JammingDetector()
        repeat(12) { d.feed(normal(it).copy(gnssCn0 = null)) }
        assertFalse(d.feed(normal(3).copy(gnssCn0 = null)).suspected)
    }
}

class MagMeterTest {
    @Test fun deviationAndTicks() {
        val m = MagMeter(alpha = 1.0)
        m.feed(30f, 0f, 40f) // |B| = 50 µT
        m.calibrate()
        assertEquals(0.0, m.deviation(), 1e-9)
        assertNull(m.tickIntervalMs())
        m.feed(0f, 0f, 150f) // strong magnet nearby
        assertEquals(100.0, m.deviation(), 1e-9)
        assertTrue(m.tickIntervalMs()!! < 200)
    }
}
