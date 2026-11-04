package dev.kafli.rftoolkit.modules.survey

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult as BleScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.kafli.rftoolkit.MainActivity
import dev.kafli.rftoolkit.core.ExportStore
import dev.kafli.rftoolkit.modules.wifi.ApProfile
import dev.kafli.rftoolkit.modules.wifi.Severity
import dev.kafli.rftoolkit.modules.wifi.WifiFindings
import dev.kafli.rftoolkit.modules.wifi.WifiScan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * City survey: passively logs Wi-Fi beacons (with raw IEs), BLE advertisements (raw payload),
 * location fixes and battery to `files/export/survey-<id>.jsonl`.
 *
 * Runs as a foreground service (types location|connectedDevice) holding a partial wake lock,
 * so it keeps collecting with the screen off. Start/stop via [start]/[stop].
 */
@SuppressLint("MissingPermission")
class SurveyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var preset: SurveyPreset
    private lateinit var writer: SurveyWriter
    private var wakeLock: PowerManager.WakeLock? = null

    private val wifi by lazy { getSystemService(WifiManager::class.java) }
    private val location by lazy { getSystemService(LocationManager::class.java) }
    private val battery by lazy { getSystemService(BatteryManager::class.java) }
    private val bleScanner: BluetoothLeScanner? by lazy {
        getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
    }

    @Volatile private var fix: Records.Fix? = null
    private val apIeHash = ConcurrentHashMap<String, Int>()
    private val bleAddrs = ConcurrentHashMap.newKeySet<String>()
    private lateinit var bleThrottle: SightingThrottle
    @Volatile private var wifiScans = 0
    @Volatile private var bleAdverts = 0
    @Volatile private var bleWindowAdverts = 0
    private var battStartPct: Int? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val sessionFindingKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var findingCount = 0
    @Volatile private var highFindingCount = 0

    // ------------------------------------------------------------------ lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (SurveyState.status.value.running) return START_NOT_STICKY

        preset = SurveyState.status.value.preset
        val keepWifi = SurveyState.status.value.keepWifi
        bleThrottle = SightingThrottle(preset.bleRepeatMs)
        startInForeground()

        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(ExportStore.dir(this), "survey-$id.jsonl")
        writer = SurveyWriter(file, scope)
        val startMs = System.currentTimeMillis()
        writer.write(Records.session(id, startMs, preset, deviceInfo()))

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rftoolkit:survey")
            .apply { acquire(12 * 60 * 60 * 1000L) } // hard cap 12 h

        battStartPct = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        SurveyState.update {
            SurveyStatus(
                running = true, preset = preset, sessionId = id, startMs = startMs,
                battStartPct = battStartPct, keepWifi = keepWifi,
            )
        }
        if (keepWifi) holdWifi()

        startLocation()
        startWifiLoop()
        startBleLoop()
        startBatteryAndStatusLoop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { location.removeUpdates(locationListener) }
        wifiNetworkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        runCatching { unregisterReceiver(wifiReceiver) }
        stopBleScanners()
        WifiFindings.saveBaseline(this)
        if (::writer.isInitialized) {
            val totals = JSONObject()
                .put("records", writer.records.get())
                .put("dropped", writer.dropped.get())
                .put("wifiScans", wifiScans)
                .put("uniqueAps", apIeHash.size)
                .put("bleAdverts", bleAdverts)
                .put("uniqueBle", bleAddrs.size)
                .put("findings", findingCount)
            writer.write(battRecord())
            runBlocking { writer.close(Records.end(System.currentTimeMillis(), totals)) }
        }
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        SurveyState.update { it.copy(running = false) }
        super.onDestroy()
    }

    // ------------------------------------------------------------- notification

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Survey", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SurveyService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("RF survey running")
            .setContentText("Preset: ${preset.label}")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)

        // Wear OS: shows a survey indicator on the watch face / recents.
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(android.R.drawable.ic_menu_compass)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate("RF survey · ${preset.label}").build())
            .build()
            .apply(applicationContext)

        val notification: Notification = builder.build()
        startForeground(
            NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    // ----------------------------------------------------------------- location

    private val locationListener = LocationListener { l: Location ->
        val f = Records.Fix(
            lat = l.latitude, lon = l.longitude, accM = l.accuracy,
            altM = if (l.hasAltitude()) l.altitude else null, ms = l.time, provider = l.provider,
        )
        writer.write(Records.loc(f, if (l.hasSpeed()) l.speed else null))
        val cur = fix
        if (cur == null || f.accM <= cur.accM || f.ms - cur.ms > 15_000) fix = f
    }

    private fun startLocation() {
        // Primary: fused (or GPS if no fused). Fallback: network (Wi-Fi/cell positioning) for indoors.
        val primary = if (location.hasProvider(LocationManager.FUSED_PROVIDER)) {
            LocationManager.FUSED_PROVIDER
        } else {
            LocationManager.GPS_PROVIDER
        }
        val providers = listOf(primary, LocationManager.NETWORK_PROVIDER)
            .filter { location.hasProvider(it) }
            .distinct()
        val req = LocationRequest.Builder(preset.locationIntervalMs)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(preset.locationIntervalMs / 2)
            .build()
        val active = providers.filter { p ->
            runCatching {
                location.requestLocationUpdates(p, req, ContextCompat.getMainExecutor(this), locationListener)
            }.onFailure { e ->
                SurveyState.update { it.copy(lastError = "location($p): ${e.message}") }
            }.isSuccess
        }
        val desc = active.joinToString { p -> p + if (location.isProviderEnabled(p)) "" else "(off)" }
        writer.write(Records.scan(System.currentTimeMillis(), "loc_providers", active.size, JSONObject().put("providers", desc)))
        SurveyState.update { it.copy(locProviders = desc.ifEmpty { "none" }) }
    }

    /** Latest fix if recent enough to tag a sighting with, else null. */
    private fun freshFix(): Records.Fix? =
        fix?.takeIf { System.currentTimeMillis() - it.ms <= maxOf(30_000L, preset.locationIntervalMs * 3) }

    /**
     * Wear OS turns Wi-Fi off aggressively (screen off / phone nearby). Requesting a Wi-Fi
     * network keeps the radio connected for the session — useful while debugging over ADB.
     */
    private fun holdWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                writer.write(Records.scan(System.currentTimeMillis(), "wifi_link_up", 1))
            }
            override fun onLost(network: Network) {
                writer.write(Records.scan(System.currentTimeMillis(), "wifi_link_lost", 0))
            }
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.requestNetwork(req, cb) }
            .onSuccess { wifiNetworkCallback = cb }
            .onFailure { e -> SurveyState.update { it.copy(lastError = "keepWifi: ${e.message}") } }
    }

    // --------------------------------------------------------------------- wifi

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val updated = i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            scope.launch { onWifiResults(wifi.scanResults, updated) }
        }
    }

    private fun startWifiLoop() {
        ContextCompat.registerReceiver(
            this, wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            while (isActive) {
                @Suppress("DEPRECATION")
                val ok = runCatching { wifi.startScan() }.getOrDefault(false)
                if (!ok) writer.write(Records.scan(System.currentTimeMillis(), "wifi_start_failed", 0))
                delay(preset.wifiIntervalMs)
            }
        }
    }

    private fun onWifiResults(results: List<ScanResult>, updated: Boolean) {
        val now = System.currentTimeMillis()
        val bootWall = now - SystemClock.elapsedRealtime()
        val f = freshFix()
        var fresh = 0
        val profiles = ArrayList<ApProfile>(results.size)
        for (r in results) {
            val seenMs = bootWall + r.timestamp / 1000
            val age = now - seenMs
            // Skip cached results from long ago; they'd be tagged with the wrong location.
            if (age > preset.wifiIntervalMs * 2 + 10_000) continue
            fresh++
            val ies = WifiScan.rawIes(r)
            profiles += WifiScan.profile(r, ies)
            val h = Records.iesHash(ies)
            val changed = apIeHash.put(r.BSSID, h) != h
            @Suppress("DEPRECATION")
            writer.write(
                Records.wifi(
                    ms = seenMs, ageMs = age, bssid = r.BSSID, ssid = r.SSID, freqMhz = r.frequency,
                    channelWidth = r.channelWidth, rssi = r.level.takeIf { it != 0 }, capabilities = r.capabilities ?: "",
                    wifiStandard = r.wifiStandard, ies = if (changed) ies else null, fix = f,
                ),
            )
        }
        // Phase 2: security findings + evil-twin detection. Log each (type, BSSID) once per session.
        for (finding in WifiFindings.process(this, profiles)) {
            if (sessionFindingKeys.add(finding.key)) {
                writer.write(finding.toJson().put("loc", f?.let { JSONArray().put(it.lat).put(it.lon).put(it.accM.toDouble()) } ?: JSONObject.NULL))
                findingCount++
                if (finding.type.severity == Severity.HIGH) highFindingCount++
            }
        }
        wifiScans++
        writer.write(
            Records.scan(now, "wifi", fresh, JSONObject().put("total", results.size).put("updated", updated)),
        )
    }

    // ---------------------------------------------------------------------- ble

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: BleScanResult) = onBle(result)
        override fun onBatchScanResults(results: MutableList<BleScanResult>) = results.forEach(::onBle)
        override fun onScanFailed(errorCode: Int) {
            writer.write(Records.scan(System.currentTimeMillis(), "ble_failed", errorCode))
            SurveyState.update { it.copy(lastError = "BLE scan failed: $errorCode") }
        }
    }
    // Separate callback object for the filtered scanner (one callback can't back two scans).
    private val bleFilteredCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: BleScanResult) = onBle(result)
        override fun onBatchScanResults(results: MutableList<BleScanResult>) = results.forEach(::onBle)
        override fun onScanFailed(errorCode: Int) {
            writer.write(Records.scan(System.currentTimeMillis(), "ble_filtered_failed", errorCode))
        }
    }

    private fun onBle(r: BleScanResult) {
        bleAdverts++
        bleWindowAdverts++
        val addr = r.device.address
        bleAddrs += addr
        val raw = r.scanRecord?.bytes
        val key = addr + ":" + (raw?.contentHashCode() ?: 0)
        val ms = System.currentTimeMillis() - SystemClock.elapsedRealtime() + r.timestampNanos / 1_000_000
        if (!bleThrottle.shouldEmit(key, ms)) return
        writer.write(
            Records.ble(
                ms = ms, address = addr, rssi = r.rssi,
                txPower = r.txPower.takeIf { it != BleScanResult.TX_POWER_NOT_PRESENT },
                connectable = r.isConnectable, primaryPhy = r.primaryPhy,
                raw = raw?.let { trimAdv(it) }, fix = freshFix(),
            ),
        )
    }

    /** Legacy scan records are zero-padded to 62 bytes; drop the padding after the last AD structure. */
    private fun trimAdv(raw: ByteArray): ByteArray {
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len == 0) break
            i += len + 1
        }
        return raw.copyOf(minOf(i, raw.size))
    }

    private fun startBleLoop() {
        val scanner = bleScanner ?: run {
            SurveyState.update { it.copy(lastError = "No BLE scanner (Bluetooth off?)") }
            return
        }
        val mode = if (preset == SurveyPreset.LOW) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY
        val settings = ScanSettings.Builder().setScanMode(mode).build()
        val filters = BleFilters.broad()
        scope.launch {
            while (isActive) {
                bleWindowAdverts = 0
                val started = System.currentTimeMillis()
                runCatching {
                    scanner.startScan(null, settings, bleCallback)
                    scanner.startScan(filters, settings, bleFilteredCallback)
                }.onFailure { e -> SurveyState.update { it.copy(lastError = "BLE start: ${e.message}") } }
                delay(preset.bleOnMs)
                stopBleScanners()
                writer.write(
                    Records.scan(
                        System.currentTimeMillis(), "ble_window", bleWindowAdverts,
                        JSONObject().put("windowMs", System.currentTimeMillis() - started),
                    ),
                )
                if (preset.bleOffMs > 0) delay(preset.bleOffMs)
            }
        }
    }

    private fun stopBleScanners() {
        val s = bleScanner ?: return
        runCatching { s.stopScan(bleCallback) }
        runCatching { s.stopScan(bleFilteredCallback) }
    }

    // ----------------------------------------------------------- battery/status

    private fun battRecord(): JSONObject = Records.batt(
        System.currentTimeMillis(),
        battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
    )

    private fun startBatteryAndStatusLoop() {
        scope.launch {
            var tick = 0
            while (isActive) {
                if (tick % 60 == 0) writer.write(battRecord())
                if (tick % 300 == 299) WifiFindings.saveBaseline(this@SurveyService)
                val f = fix
                SurveyState.update {
                    it.copy(
                        wifiScans = wifiScans, uniqueAps = apIeHash.size,
                        bleAdverts = bleAdverts, uniqueBle = bleAddrs.size,
                        records = writer.records.get(), bytes = writer.bytes.get(),
                        fixAccM = f?.accM, fixAgeS = f?.let { (System.currentTimeMillis() - it.ms) / 1000 },
                        battNowPct = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                        findings = findingCount, highFindings = highFindingCount,
                    )
                }
                tick++
                delay(1_000)
            }
        }
    }

    private fun deviceInfo(): JSONObject = JSONObject()
        .put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
        .put("release", Build.VERSION.RELEASE).put("app", "0.2.0")

    companion object {
        private const val CHANNEL_ID = "survey"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "dev.kafli.rftoolkit.survey.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SurveyService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SurveyService::class.java))
        }
    }
}
