package dev.kafli.rftoolkit.modules.hunt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.VibrationEffect
import android.os.VibratorManager
import dev.kafli.rftoolkit.core.Haptics
import dev.kafli.rftoolkit.modules.wifi.WifiScan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class HuntTarget(val id: String, val label: String) {
    /**
     * No sample for this long → "signal lost". A full Wi-Fi scan takes ~4.4 s on the SM-R890
     * (2.4 + 5 GHz incl. passive DFS channels; apps can't scan a single channel), so Wi-Fi hunts
     * update roughly every 5 s and occasionally miss the target's beacon.
     */
    val staleMs: Long get() = if (this is Ble) 6_000 else 15_000

    class Ble(address: String, label: String) : HuntTarget(address.uppercase(), label)
    class Wifi(bssid: String, ssid: String) : HuntTarget(bssid.lowercase(), ssid.ifEmpty { "<hidden>" })

    val isBle: Boolean get() = this is Ble
}

data class HuntState(
    val target: HuntTarget? = null,
    val running: Boolean = false,
    val rawRssi: Int? = null,
    val smoothed: Double? = null,
    val peak: Double? = null,
    val trend: Trend = Trend.STEADY,
    val samples: Int = 0,
    val lastSampleMs: Long = 0,
    val headingDeg: Double? = null,
    val sweeping: Boolean = false,
    val sweepCovered: Int = 0,
    val sweep: SweepResult? = null,
    val hapticsOn: Boolean = true,
    val error: String? = null,
)

/**
 * Drives one fox hunt: samples the target's RSSI (BLE scan or repeated Wi-Fi scans), smooths it,
 * ticks the vibration motor faster as the signal rises, and runs the 360° body-shadow sweep.
 * Lives only while the hunt screen is open (screen kept on), so it never drains in the background.
 */
@SuppressLint("MissingPermission")
object HuntEngine {
    private const val SWEEP_TIMEOUT_MS = 40_000L

    private val _state = MutableStateFlow(HuntState())
    val state: StateFlow<HuntState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var smoother = RssiSmoother(0.3)
    private val trend = TrendTracker()
    private var sweepAnalyzer: SweepAnalyzer? = null
    private var sweepJob: Job? = null
    private var sensorListener: SensorEventListener? = null
    private var bleCallback: ScanCallback? = null

    /** Selects the target; [start] begins sampling. */
    fun select(target: HuntTarget) {
        stop()
        _state.value = HuntState(target = target)
    }

    fun start(ctx: Context) {
        val target = _state.value.target ?: return
        if (_state.value.running) return
        val app = ctx.applicationContext
        appCtx = app
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        smoother = RssiSmoother(if (target.isBle) 0.3 else 0.6)
        trend.reset()
        _state.update { it.copy(running = true, error = null, samples = 0, smoothed = null, peak = null, rawRssi = null) }

        startHeading(app)
        when (target) {
            is HuntTarget.Ble -> startBle(app, target)
            is HuntTarget.Wifi -> s.launch { wifiLoop(app, target) }
        }
        s.launch { hapticLoop(app) }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        sweepJob = null
        sweepAnalyzer = null
        bleCallback?.let { cb ->
            appCtx?.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
                ?.let { runCatching { it.stopScan(cb) } }
        }
        bleCallback = null
        sensorListener?.let { l -> lastSensorManager?.unregisterListener(l) }
        sensorListener = null
        _state.update { it.copy(running = false, sweeping = false) }
    }

    fun toggleHaptics() = _state.update { it.copy(hapticsOn = !it.hapticsOn) }

    // ------------------------------------------------------------------ sampling

    private fun onSample(rssi: Int, ms: Long) {
        val sm = smoother.update(rssi)
        val tr = trend.add(ms, sm)
        val heading = _state.value.headingDeg
        sweepAnalyzer?.let { a -> if (heading != null) a.add(heading, rssi) }
        _state.update { st ->
            val newPeak = st.peak == null || sm > st.peak
            if (newPeak && st.samples >= 5 && st.peak != null && sm > st.peak + 2 && st.hapticsOn) {
                pendingPeakCue = true
            }
            st.copy(
                rawRssi = rssi, smoothed = sm, trend = tr, samples = st.samples + 1, lastSampleMs = ms,
                peak = if (newPeak) sm else st.peak,
                sweepCovered = sweepAnalyzer?.covered ?: st.sweepCovered,
            )
        }
    }

    @Volatile private var pendingPeakCue = false
    private var appCtx: Context? = null

    private fun startBle(ctx: Context, target: HuntTarget.Ble) {
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            _state.update { it.copy(error = "Bluetooth is off") }
            return
        }
        // Unfiltered + match in callback: robust for random addresses; the screen stays on while hunting.
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                if (r.device.address.equals(target.id, ignoreCase = true)) onSample(r.rssi, System.currentTimeMillis())
            }
            override fun onScanFailed(errorCode: Int) {
                _state.update { it.copy(error = "BLE scan failed ($errorCode)") }
            }
        }
        bleCallback = cb
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
    }

    private suspend fun wifiLoop(ctx: Context, target: HuntTarget.Wifi) {
        var lastScanTs = 0L // ScanResult.timestamp (µs since boot) of the last accepted measurement
        while (scope?.isActive == true) {
            val results = runCatching { WifiScan.scanOnce(ctx, timeoutMs = 8_000, maxAgeMs = 6_000) }.getOrElse { emptyList() }
            @Suppress("DEPRECATION")
            val r = results.firstOrNull { it.BSSID.equals(target.id, ignoreCase = true) }
            // Only count genuinely new measurements; cached results would fake samples and flatten the trend.
            if (r != null && r.level != 0 && r.timestamp > lastScanTs) {
                lastScanTs = r.timestamp
                onSample(r.level, WifiScan.wallClockMs(r))
            }
            delay(100) // next scan right away; the scan itself is the bottleneck
        }
    }

    // ------------------------------------------------------------------ haptics

    private suspend fun hapticLoop(ctx: Context) {
        val vibrator = ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        while (scope?.isActive == true) {
            val st = _state.value
            val sm = st.smoothed
            val fresh = System.currentTimeMillis() - st.lastSampleMs < (st.target?.staleMs ?: 6_000)
            if (sm == null || !fresh || !st.hapticsOn || st.sweeping) {
                delay(300)
                continue
            }
            if (pendingPeakCue) {
                pendingPeakCue = false
                Haptics.play(ctx, Haptics.NOTICE)
                delay(400)
                continue
            }
            val amplitude = if (vibrator.hasAmplitudeControl()) {
                (60 + 195 * Proximity.closeness(sm)).toInt().coerceIn(1, 255)
            } else {
                VibrationEffect.DEFAULT_AMPLITUDE
            }
            runCatching { vibrator.vibrate(VibrationEffect.createOneShot(35, amplitude)) }
            delay(Proximity.pulseIntervalMs(sm))
        }
    }

    // ------------------------------------------------------------------ heading + sweep

    private var lastSensorManager: SensorManager? = null

    private fun startHeading(ctx: Context) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        lastSensorManager = sm
        val rot = FloatArray(9)
        val orient = FloatArray(3)
        val l = object : SensorEventListener {
            var lastPublish = 0L
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, orient)
                val deg = SweepAnalyzer.norm(Math.toDegrees(orient[0].toDouble()))
                val now = System.currentTimeMillis()
                if (now - lastPublish > 100) {
                    lastPublish = now
                    _state.update { it.copy(headingDeg = deg) }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sensorListener = l
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    /** Starts a 360° sweep; finishes when 11/12 sectors are covered or after a timeout. */
    fun startSweep() {
        val s = scope ?: return
        if (_state.value.headingDeg == null) {
            _state.update { it.copy(error = "No compass sensor") }
            return
        }
        val a = SweepAnalyzer()
        sweepAnalyzer = a
        _state.update { it.copy(sweeping = true, sweep = null, sweepCovered = 0) }
        sweepJob = s.launch {
            val started = System.currentTimeMillis()
            while (isActive && a.covered < 11 && System.currentTimeMillis() - started < SWEEP_TIMEOUT_MS) delay(250)
            val result = a.result()
            sweepAnalyzer = null
            _state.update {
                it.copy(
                    sweeping = false, sweep = result,
                    error = if (result == null) "Sweep incomplete (${a.covered}/12) — turn slower" else null,
                )
            }
            appCtx?.let { Haptics.play(it, Haptics.NOTICE) }
        }
    }
}
