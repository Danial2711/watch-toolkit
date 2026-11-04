package dev.kafli.rftoolkit.modules.hunt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RssiSmootherTest {
    @Test fun firstSampleIsTakenAsIs() = assertEquals(-70.0, RssiSmoother(0.3).update(-70), 0.0)

    @Test fun outlierIsClamped() {
        val s = RssiSmoother(alpha = 1.0, maxStepDb = 8.0)
        s.update(-80)
        assertEquals(-72.0, s.update(-40), 0.0) // 40 dB jump limited to 8 dB
    }

    @Test fun convergesToSteadySignal() {
        val s = RssiSmoother(0.3)
        s.update(-90)
        repeat(40) { s.update(-60) }
        assertEquals(-60.0, s.value!!, 0.5)
    }
}

class ProximityTest {
    @Test fun intervalShrinksAsSignalRises() {
        val far = Proximity.pulseIntervalMs(-95.0)
        val mid = Proximity.pulseIntervalMs(-70.0)
        val near = Proximity.pulseIntervalMs(-40.0)
        assertTrue("$far > $mid > $near", far > mid && mid > near)
        assertTrue(near in 80..120)
        assertTrue(Proximity.pulseIntervalMs(-120.0) <= 1580)
    }

    @Test fun levels() {
        assertEquals(0, Proximity.level(-110.0))
        assertEquals(10, Proximity.level(-30.0))
    }

    @Test fun roughDistanceIsMonotonic() {
        assertEquals(1.0, Proximity.roughDistanceM(-59.0), 0.01)
        assertTrue(Proximity.roughDistanceM(-80.0) > Proximity.roughDistanceM(-65.0))
    }
}

class TrendTrackerTest {
    @Test fun risingIsWarmer() {
        val t = TrendTracker()
        var tr = Trend.STEADY
        for (i in 0..10) tr = t.add(i * 500L, -80.0 + i)
        assertEquals(Trend.WARMER, tr)
    }

    @Test fun fallingIsColder() {
        val t = TrendTracker()
        var tr = Trend.STEADY
        for (i in 0..10) tr = t.add(i * 500L, -60.0 - i)
        assertEquals(Trend.COLDER, tr)
    }

    @Test fun noiseIsSteady() {
        val t = TrendTracker()
        var tr = Trend.WARMER
        for (i in 0..12) tr = t.add(i * 500L, -70.0 + if (i % 2 == 0) 1 else -1)
        assertEquals(Trend.STEADY, tr)
    }
}

class SweepAnalyzerTest {
    /** Simulated body shadowing: source at 60°, -55 dBm facing it, -70 dBm facing away. */
    private fun simulate(sourceDeg: Double, step: Double = 10.0): SweepAnalyzer {
        val a = SweepAnalyzer()
        var h = 0.0
        while (h < 360) {
            val off = kotlin.math.abs(SweepAnalyzer.relativeTurn(h, sourceDeg))
            a.add(h, (-55 - 15 * off / 180).toInt())
            h += step
        }
        return a
    }

    @Test fun findsSourceDirection() {
        val r = simulate(60.0).result()!!
        assertTrue("got ${r.bestHeadingDeg}", kotlin.math.abs(SweepAnalyzer.relativeTurn(r.bestHeadingDeg, 60.0)) <= 15)
        assertEquals("high", r.confidence)
    }

    @Test fun wrapsAroundNorth() {
        val r = simulate(355.0).result()!!
        assertTrue("got ${r.bestHeadingDeg}", kotlin.math.abs(SweepAnalyzer.relativeTurn(r.bestHeadingDeg, 355.0)) <= 15)
    }

    @Test fun incompleteSweepHasNoResult() {
        val a = SweepAnalyzer()
        for (h in 0..90 step 10) a.add(h.toDouble(), -60)
        assertNull(a.result())
        assertEquals(4, a.covered)
    }

    @Test fun relativeTurnAndNames() {
        assertEquals(-20.0, SweepAnalyzer.relativeTurn(10.0, 350.0), 0.0)
        assertEquals(90.0, SweepAnalyzer.relativeTurn(0.0, 90.0), 0.0)
        assertEquals("NE", SweepAnalyzer.compassName(40.0))
        assertEquals("N", SweepAnalyzer.compassName(350.0))
        assertEquals("↑", SweepAnalyzer.arrow(5.0))
        assertEquals("←", SweepAnalyzer.arrow(-100.0))
        assertNotNull(SweepAnalyzer.arrow(179.0))
    }
}
