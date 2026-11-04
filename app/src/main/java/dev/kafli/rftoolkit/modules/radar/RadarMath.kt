package dev.kafli.rftoolkit.modules.radar

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place iterative radix-2 FFT. [re]/[im] length must be a power of two. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && n > 0 && n and (n - 1) == 0) { "size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Hann-windowed magnitude spectrum in dB (bins 0..n/2-1) of 16-bit PCM samples. */
    fun spectrumDb(samples: ShortArray, n: Int = samples.size): DoubleArray {
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            re[i] = samples[i] / 32768.0 * w
        }
        transform(re, im)
        return DoubleArray(n / 2) { 20 * log10(sqrt(re[it] * re[it] + im[it] * im[it]) + 1e-12) }
    }
}

data class Tone(val freqHz: Double, val snrDb: Double)

/**
 * Detects a steady narrow-band tone in [fromHz, toHz] (ultrasonic beacons sit around 17.5–22 kHz):
 * the band's peak must exceed the band's median by [minSnrDb] for [minFrames] consecutive frames
 * at (nearly) the same frequency.
 */
class ToneDetector(
    private val sampleRate: Int,
    private val fftSize: Int,
    private val fromHz: Double = 17_500.0,
    private val toHz: Double = 22_000.0,
    private val minSnrDb: Double = 15.0,
    private val minFrames: Int = 3,
) {
    private val binHz = sampleRate.toDouble() / fftSize
    private var lastBin = -100
    private var streak = 0

    fun binOf(hz: Double) = (hz / binHz).toInt()

    /** Feed one frame's spectrum; returns the tone once it has persisted, else null. */
    fun feed(db: DoubleArray): Tone? {
        val lo = binOf(fromHz).coerceIn(0, db.size - 1)
        val hi = binOf(toHz).coerceIn(lo, db.size - 1)
        val band = db.copyOfRange(lo, hi + 1)
        val median = band.sorted()[band.size / 2]
        var peak = 0
        for (i in band.indices) if (band[i] > band[peak]) peak = i
        val snr = band[peak] - median
        val bin = lo + peak
        if (snr >= minSnrDb && kotlin.math.abs(bin - lastBin) <= 2) streak++ else streak = if (snr >= minSnrDb) 1 else 0
        lastBin = bin
        return if (streak >= minFrames) Tone(bin * binHz, snr) else null
    }
}

/** Collapses a spectrum into [bands] equal-width bands (mean dB) for a compact display. */
fun bandLevels(db: DoubleArray, bands: Int): DoubleArray {
    val per = db.size / bands
    return DoubleArray(bands) { b -> db.copyOfRange(b * per, (b + 1) * per).average() }
}

// ---------------------------------------------------------------- Wi-Fi channels

data class ChannelStat(val channel: Int, val aps: Int, val bestRssi: Int)

object ChannelStats {
    /** Per-channel AP count and strongest RSSI from (channel, rssi) pairs. */
    fun compute(aps: List<Pair<Int, Int>>): List<ChannelStat> =
        aps.filter { it.first > 0 }.groupBy { it.first }
            .map { (ch, l) -> ChannelStat(ch, l.size, l.maxOf { it.second }) }
            .sortedBy { it.channel }

    /** 2.4 GHz channels other than 1/6/11 overlap their neighbours (20 MHz wide, 5 MHz spacing). */
    fun overlapping24(stats: List<ChannelStat>): List<Int> =
        stats.filter { it.channel in 1..14 && it.channel !in setOf(1, 6, 11) }.map { it.channel }

    fun bar(aps: Int, max: Int, width: Int = 8): String {
        val n = if (max == 0) 0 else ((aps.toDouble() / max) * width).toInt().coerceAtLeast(if (aps > 0) 1 else 0)
        return "█".repeat(n) + "·".repeat(width - n)
    }
}

// ---------------------------------------------------------------- jamming heuristic

data class RadioWindow(val wifiAps: Int?, val bleAdverts: Int?, val gnssCn0: Double?)

enum class Domain { WIFI, BLE, GNSS }

data class JamVerdict(val dropped: Set<Domain>, val suspected: Boolean, val detail: String)

/**
 * "Did several radios go quiet at once?" Each window (e.g. 10 s) is compared with the median of the
 * previous [history] windows. Wi-Fi/BLE count as dropped below [ratio] × baseline; GNSS when mean
 * C/N0 falls by ≥ [gnssDropDb]. Two or more domains dropping together is the jamming signature —
 * a single domain dropping is usually just the environment (walking into a lift, etc.).
 */
class JammingDetector(
    private val history: Int = 12,
    private val ratio: Double = 0.3,
    private val gnssDropDb: Double = 10.0,
    private val minWifi: Int = 5,
    private val minBle: Int = 20,
) {
    private val windows = ArrayDeque<RadioWindow>()

    private fun median(xs: List<Double>): Double? = xs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    fun feed(w: RadioWindow): JamVerdict {
        val dropped = mutableSetOf<Domain>()
        val parts = mutableListOf<String>()
        if (windows.size >= history / 2) {
            val wifiBase = median(windows.mapNotNull { it.wifiAps?.toDouble() })
            val bleBase = median(windows.mapNotNull { it.bleAdverts?.toDouble() })
            val gnssBase = median(windows.mapNotNull { it.gnssCn0 })
            if (wifiBase != null && wifiBase >= minWifi && w.wifiAps != null && w.wifiAps < ratio * wifiBase) {
                dropped += Domain.WIFI; parts += "Wi-Fi ${w.wifiAps}/${wifiBase.toInt()}"
            }
            if (bleBase != null && bleBase >= minBle && w.bleAdverts != null && w.bleAdverts < ratio * bleBase) {
                dropped += Domain.BLE; parts += "BLE ${w.bleAdverts}/${bleBase.toInt()}"
            }
            if (gnssBase != null && w.gnssCn0 != null && gnssBase - w.gnssCn0 >= gnssDropDb) {
                dropped += Domain.GNSS; parts += "GNSS −%.0f dB".format(gnssBase - w.gnssCn0)
            }
        }
        // Don't let a jammed window poison the baseline.
        if (dropped.isEmpty()) {
            windows.addLast(w)
            while (windows.size > history) windows.removeFirst()
        }
        return JamVerdict(dropped, dropped.size >= 2, parts.joinToString(", "))
    }

    val ready: Boolean get() = windows.size >= history / 2
}

// ---------------------------------------------------------------- magnetometer

/** Field magnitude vs. a calibrated baseline (Earth's field is ~25–65 µT). */
class MagMeter(private val alpha: Double = 0.2) {
    var baseline: Double? = null
        private set
    var smoothed: Double? = null
        private set

    fun magnitude(x: Float, y: Float, z: Float): Double = sqrt((x * x + y * y + z * z).toDouble())

    fun feed(x: Float, y: Float, z: Float): Double {
        val m = magnitude(x, y, z)
        smoothed = smoothed?.let { it + alpha * (m - it) } ?: m
        return smoothed!!
    }

    fun calibrate() { baseline = smoothed }

    /** Absolute deviation from baseline in µT (0 before calibration). */
    fun deviation(): Double = smoothed?.let { s -> baseline?.let { kotlin.math.abs(s - it) } } ?: 0.0

    /** Haptic tick interval: none below 5 µT deviation, faster as the anomaly grows. */
    fun tickIntervalMs(): Long? {
        val d = deviation()
        return if (d < 5) null else (1200 / (1 + d / 10)).toLong().coerceAtLeast(60)
    }
}
