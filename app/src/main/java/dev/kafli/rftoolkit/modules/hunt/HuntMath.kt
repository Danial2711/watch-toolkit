package dev.kafli.rftoolkit.modules.hunt

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Exponential moving average with outlier clamping. Single BLE RSSI readings jump ±10 dB from
 * multipath and body shadowing; clamping each step to [maxStepDb] keeps one bad reading from
 * yanking the haptics around.
 */
class RssiSmoother(private val alpha: Double, private val maxStepDb: Double = 8.0) {
    var value: Double? = null
        private set

    fun update(rssi: Int): Double {
        val v = value
        val next = if (v == null) rssi.toDouble() else {
            val step = (rssi - v).coerceIn(-maxStepDb, maxStepDb)
            v + alpha * step
        }
        value = next
        return next
    }

    fun reset() { value = null }
}

/** Maps smoothed RSSI to haptic pulse interval and a 0..10 proximity level. */
object Proximity {
    const val FAR_DBM = -100.0
    const val NEAR_DBM = -35.0

    /** 0.0 (far) .. 1.0 (touching). */
    fun closeness(rssi: Double): Double = ((rssi - FAR_DBM) / (NEAR_DBM - FAR_DBM)).coerceIn(0.0, 1.0)

    fun level(rssi: Double): Int = (closeness(rssi) * 10).toInt()

    /** Quadratic curve: slow ticking far away, rapid near the source (Geiger-counter feel). */
    fun pulseIntervalMs(rssi: Double): Long = (80 + 1500 * (1 - closeness(rssi)).pow(2)).toLong()

    /**
     * Very rough log-distance path-loss estimate. [refAt1m] ≈ -59 dBm is typical for phones/beacons;
     * trackers vary by ±10 dB, so treat as an order of magnitude, never as metres you can trust.
     */
    fun roughDistanceM(rssi: Double, refAt1m: Double = -59.0, n: Double = 2.5): Double =
        10.0.pow((refAt1m - rssi) / (10 * n))
}

enum class Trend { WARMER, COLDER, STEADY }

/** Least-squares slope of RSSI over the last [windowMs]; ±[thresholdDbPerS] decides the trend. */
class TrendTracker(private val windowMs: Long = 6_000, private val thresholdDbPerS: Double = 0.6) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun add(ms: Long, rssi: Double): Trend {
        samples.addLast(ms to rssi)
        while (samples.isNotEmpty() && ms - samples.first().first > windowMs) samples.removeFirst()
        if (samples.size < 3) return Trend.STEADY
        val t0 = samples.first().first
        val xs = samples.map { (it.first - t0) / 1000.0 }
        val ys = samples.map { it.second }
        val mx = xs.average()
        val my = ys.average()
        val den = xs.sumOf { (it - mx) * (it - mx) }
        if (den == 0.0) return Trend.STEADY
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den
        return when {
            slope > thresholdDbPerS -> Trend.WARMER
            slope < -thresholdDbPerS -> Trend.COLDER
            else -> Trend.STEADY
        }
    }

    fun reset() = samples.clear()
}

data class SweepResult(
    val bestHeadingDeg: Double,
    /** Best sector mean minus the mean of all sectors, in dB. */
    val contrastDb: Double,
    val sectorsCovered: Int,
) {
    val confidence: String
        get() = when {
            contrastDb >= 6 -> "high"
            contrastDb >= 3 -> "medium"
            else -> "low"
        }
}

/**
 * Body-shadow direction finding: the human body attenuates 2.4 GHz by several dB, so while you
 * turn in place with the watch held in front, RSSI peaks when you face the transmitter.
 * Samples are binned into [sectors] by compass heading.
 */
class SweepAnalyzer(private val sectors: Int = 12) {
    private val sums = DoubleArray(sectors)
    private val counts = IntArray(sectors)
    private val sinSum = DoubleArray(sectors)
    private val cosSum = DoubleArray(sectors)
    private val width = 360.0 / sectors

    fun add(headingDeg: Double, rssi: Int) {
        val h = norm(headingDeg)
        val i = (h / width).toInt().coerceIn(0, sectors - 1)
        sums[i] += rssi
        counts[i]++
        sinSum[i] += sin(Math.toRadians(h))
        cosSum[i] += cos(Math.toRadians(h))
    }

    val covered: Int get() = counts.count { it > 0 }

    fun result(minSectors: Int = (sectors * 2) / 3): SweepResult? {
        if (covered < minSectors) return null
        val means = (0 until sectors).filter { counts[it] > 0 }.associateWith { sums[it] / counts[it] }
        val best = means.maxByOrNull { it.value }!!.key
        // Circular mean of the headings that actually landed in the best sector.
        val heading = norm(Math.toDegrees(atan2(sinSum[best], cosSum[best])))
        return SweepResult(heading, means.getValue(best) - means.values.average(), covered)
    }

    companion object {
        fun norm(deg: Double): Double = ((deg % 360) + 360) % 360

        /** Signed turn from [currentDeg] to [targetDeg]: negative = turn left, positive = right. */
        fun relativeTurn(currentDeg: Double, targetDeg: Double): Double {
            val d = norm(targetDeg - currentDeg)
            return if (d > 180) d - 360 else d
        }

        fun compassName(deg: Double): String {
            val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            return names[((norm(deg) + 22.5) / 45).toInt() % 8]
        }

        fun arrow(turnDeg: Double): String = when {
            abs(turnDeg) <= 15 -> "↑"
            turnDeg in 15.0..75.0 -> "↗"
            turnDeg in 75.0..135.0 -> "→"
            turnDeg > 135 -> "↘"
            turnDeg in -75.0..-15.0 -> "↖"
            turnDeg in -135.0..-75.0 -> "←"
            else -> "↙"
        }
    }
}
