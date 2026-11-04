package dev.kafli.rftoolkit.modules.radar

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
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import dev.kafli.rftoolkit.core.Haptics
import dev.kafli.rftoolkit.modules.survey.WifiChannels
import dev.kafli.rftoolkit.modules.wifi.WifiScan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

// ======================================================================== jamming monitor

data class JamState(
    val running: Boolean = false,
    val windows: Int = 0,
    val ready: Boolean = false,
    val last: RadioWindow? = null,
    val verdict: JamVerdict? = null,
    val alerts: Int = 0,
)

/** Samples Wi-Fi AP count, BLE advert rate and GNSS C/N0 in 10 s windows and runs [JammingDetector]. */
@SuppressLint("MissingPermission")
object JamMonitor {
    private const val WINDOW_MS = 10_000L
    private val _state = MutableStateFlow(JamState())
    val state: StateFlow<JamState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private val adverts = AtomicInteger()
    @Volatile private var wifiAps: Int? = null
    @Volatile private var cn0: Double? = null
    private var bleCb: ScanCallback? = null
    private var gnssCb: GnssStatus.Callback? = null
    private var locListener: LocationListener? = null
    private var appCtx: Context? = null

    fun start(ctx: Context) {
        if (scope != null) return
        val app = ctx.applicationContext
        appCtx = app
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        val detector = JammingDetector()
        _state.value = JamState(running = true)

        app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.let { sc ->
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) { adverts.incrementAndGet() }
            }
            bleCb = cb
            runCatching { sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb) }
        }
        val lm = app.getSystemService(LocationManager::class.java)
        val g = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(st: GnssStatus) {
                val top = (0 until st.satelliteCount).map { st.getCn0DbHz(it).toDouble() }.filter { it > 0 }
                    .sortedDescending().take(4)
                cn0 = if (top.size >= 4) top.average() else null
            }
        }
        gnssCb = g
        runCatching {
            lm.registerGnssStatusCallback(ContextCompat.getMainExecutor(app), g)
            val l = LocationListener { }
            locListener = l
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, l, Looper.getMainLooper())
        }
        s.launch {
            while (isActive) {
                wifiAps = runCatching { WifiScan.scanOnce(app, timeoutMs = 8_000, maxAgeMs = 10_000).size }.getOrNull()
                delay(2_000)
            }
        }
        s.launch {
            adverts.set(0)
            while (isActive) {
                delay(WINDOW_MS)
                val w = RadioWindow(wifiAps, adverts.getAndSet(0), cn0)
                val v = detector.feed(w)
                if (v.suspected) Haptics.play(app, Haptics.ALARM)
                _state.update {
                    it.copy(windows = it.windows + 1, ready = detector.ready, last = w, verdict = v,
                        alerts = it.alerts + if (v.suspected) 1 else 0)
                }
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        val app = appCtx ?: return
        bleCb?.let { cb -> app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.let { runCatching { it.stopScan(cb) } } }
        bleCb = null
        val lm = app.getSystemService(LocationManager::class.java)
        gnssCb?.let { runCatching { lm.unregisterGnssStatusCallback(it) } }
        locListener?.let { runCatching { lm.removeUpdates(it) } }
        gnssCb = null
        locListener = null
        _state.update { it.copy(running = false) }
    }
}

// ======================================================================== ultrasonic

data class UltraState(
    val running: Boolean = false,
    val bands: DoubleArray = DoubleArray(0),
    val tone: Tone? = null,
    val lastToneMs: Long = 0,
    val detections: Int = 0,
    val source: String = "",
    val error: String? = null,
)

/** Microphone FFT (48 kHz, 4096-point ≈ 11.7 Hz bins). Audio stays in memory; nothing is recorded to disk. */
@SuppressLint("MissingPermission")
object UltraMonitor {
    const val RATE = 48_000
    const val N = 4096
    private val _state = MutableStateFlow(UltraState())
    val state: StateFlow<UltraState> = _state.asStateFlow()
    private var scope: CoroutineScope? = null

    fun start(ctx: Context) {
        if (scope != null) return
        val app = ctx.applicationContext
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // UNPROCESSED skips noise suppression / AGC, which tend to filter high frequencies.
            val (rec, src) = listOf(MediaRecorder.AudioSource.UNPROCESSED to "unprocessed", MediaRecorder.AudioSource.MIC to "mic")
                .firstNotNullOfOrNull { (source, name) ->
                    runCatching {
                        AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, N * 4))
                            .takeIf { it.state == AudioRecord.STATE_INITIALIZED }?.let { it to name }
                    }.getOrNull()
                } ?: run {
                    _state.value = UltraState(error = "Microphone unavailable (permission?)")
                    return@launch
                }
            val detector = ToneDetector(RATE, N)
            val buf = ShortArray(N)
            _state.value = UltraState(running = true, source = src)
            rec.startRecording()
            var lastUi = 0L
            try {
                while (isActive) {
                    var read = 0
                    while (read < N && isActive) {
                        val n = rec.read(buf, read, N - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read < N) continue
                    val db = Fft.spectrumDb(buf)
                    val tone = detector.feed(db)
                    val now = System.currentTimeMillis()
                    if (tone != null && now - _state.value.lastToneMs > 5_000) Haptics.play(app, Haptics.NOTICE)
                    if (tone != null || now - lastUi > 200) {
                        lastUi = now
                        _state.update {
                            it.copy(
                                bands = bandLevels(db, 12),
                                tone = tone ?: it.tone?.takeIf { _ -> now - it.lastToneMs < 3_000 },
                                lastToneMs = if (tone != null) now else it.lastToneMs,
                                detections = it.detections + if (tone != null && now - it.lastToneMs > 1_000) 1 else 0,
                            )
                        }
                    }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        _state.update { it.copy(running = false) }
    }
}

// ======================================================================== magnetometer

data class MagState(val running: Boolean = false, val fieldUt: Double? = null, val baselineUt: Double? = null, val deviationUt: Double = 0.0, val hapticsOn: Boolean = true)

object MagMonitor {
    private val _state = MutableStateFlow(MagState())
    val state: StateFlow<MagState> = _state.asStateFlow()
    private val meter = MagMeter()
    private var listener: SensorEventListener? = null
    private var sm: SensorManager? = null
    private var scope: CoroutineScope? = null

    fun start(ctx: Context) {
        if (listener != null) return
        val app = ctx.applicationContext
        val mgr = app.getSystemService(SensorManager::class.java)
        val sensor = mgr.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: return
        sm = mgr
        val l = object : SensorEventListener {
            var last = 0L
            override fun onSensorChanged(e: SensorEvent) {
                val v = meter.feed(e.values[0], e.values[1], e.values[2])
                if (meter.baseline == null) meter.calibrate() // auto-calibrate on first reading
                val now = System.currentTimeMillis()
                if (now - last > 100) {
                    last = now
                    _state.update { it.copy(running = true, fieldUt = v, baselineUt = meter.baseline, deviationUt = meter.deviation()) }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        listener = l
        mgr.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        val vib = app.getSystemService(VibratorManager::class.java).defaultVibrator
        s.launch {
            while (isActive) {
                val iv = meter.tickIntervalMs()
                if (iv == null || !_state.value.hapticsOn) {
                    delay(200)
                } else {
                    runCatching { vib.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)) }
                    delay(iv)
                }
            }
        }
    }

    fun calibrate() {
        meter.calibrate()
        _state.update { it.copy(baselineUt = meter.baseline, deviationUt = 0.0) }
    }

    fun toggleHaptics() = _state.update { it.copy(hapticsOn = !it.hapticsOn) }

    fun stop() {
        listener?.let { sm?.unregisterListener(it) }
        listener = null
        scope?.cancel()
        scope = null
        _state.update { it.copy(running = false) }
    }
}

// ======================================================================== channels (one-shot)

object ChannelScan {
    suspend fun scan(ctx: Context): List<ChannelStat> {
        @Suppress("DEPRECATION")
        val aps = WifiScan.scanOnce(ctx).filter { it.level != 0 }.map { WifiChannels.channel(it.frequency) to it.level }
        return ChannelStats.compute(aps)
    }
}
