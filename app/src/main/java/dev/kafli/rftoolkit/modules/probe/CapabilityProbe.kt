package dev.kafli.rftoolkit.modules.probe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult as BleScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Phase 0 capability probe: records what this specific watch actually supports,
 * so later modules are scoped by measurement instead of spec-sheet guesses.
 *
 * Every section is isolated: a failure is recorded as `{"error": ...}` and the probe continues.
 * No SSIDs, MAC addresses or device names are stored — only counts and capabilities.
 */
@SuppressLint("MissingPermission")
class CapabilityProbe(private val activity: Activity) {

    private val ctx: Context = activity.applicationContext
    private val pm: PackageManager = ctx.packageManager

    /** Human-readable key results for the watch screen. */
    val highlights = linkedMapOf<String, String>()

    suspend fun run(progress: (String) -> Unit): JSONObject {
        val root = JSONObject()
        root.put("probeVersion", 1)
        root.put("timestampMs", System.currentTimeMillis())

        progress("Device info…");      root.put("device", section { device() })
        progress("Permissions…");      root.put("permissions", section { permissions() })
        progress("Features…");         root.put("features", section { features() })
        progress("Battery…");          root.put("battery", section { battery() })
        progress("Telephony…");        root.put("telephony", section { telephony() })
        progress("Sensors…");          root.put("sensors", section { sensors() })
        progress("Audio…");            root.put("audio", section { audio() })
        progress("Wi-Fi scan (≤12 s)…"); root.put("wifi", section { wifi() })
        progress("BLE scan (8 s)…");   root.put("bluetooth", section { bluetooth() })
        progress("NFC: hold a card/tag to the watch (10 s)…")
        root.put("nfc", section { nfc() })
        progress("GNSS (20 s, better outdoors)…"); root.put("gnss", section { gnss() })

        root.put("highlights", JSONObject(highlights as Map<*, *>))
        progress("Done")
        return root
    }

    private inline fun section(block: () -> JSONObject): JSONObject =
        try {
            block()
        } catch (t: Throwable) {
            JSONObject().put("error", "${t.javaClass.simpleName}: ${t.message}")
        }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- device

    private suspend fun device(): JSONObject {
        val o = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("product", Build.PRODUCT)
            .put("hardware", Build.HARDWARE)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("fingerprint", Build.FINGERPRINT)
        if (Build.VERSION.SDK_INT >= 31) {
            o.put("socManufacturer", Build.SOC_MANUFACTURER).put("socModel", Build.SOC_MODEL)
        }
        val props = JSONObject()
        for (key in listOf(
            "ro.product.model", "ro.boot.flash.locked", "ro.boot.verifiedbootstate",
            "ro.boot.warranty_bit", "ro.warranty_bit", "ro.oem_unlock_supported",
            "ro.build.characteristics", "ro.build.version.oneui", "ro.csc.sales_code",
            "ro.hardware.chipname", "ro.boot.hardware",
        )) props.put(key, getprop(key))
        o.put("props", props)

        highlights["Model"] = "${Build.MODEL} (API ${Build.VERSION.SDK_INT})"
        highlights["Bootloader locked"] = props.optString("ro.boot.flash.locked").ifEmpty { "unknown" }
        highlights["OEM unlock supported"] = props.optString("ro.oem_unlock_supported").ifEmpty { "unknown" }
        return o
    }

    private suspend fun getprop(key: String): String = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
        } catch (t: Throwable) {
            "ERR ${t.javaClass.simpleName}"
        }
    }

    // ----------------------------------------------------------- permissions

    private fun permissions(): JSONObject {
        val o = JSONObject()
        for (p in RUNTIME_PERMISSIONS) o.put(p.substringAfterLast('.'), granted(p))
        return o
    }

    // -------------------------------------------------------------- features

    private fun features(): JSONObject {
        val all = pm.systemAvailableFeatures.mapNotNull { it.name }.sorted()
        val o = JSONObject().put("all", JSONArray(all))
        val keys = mapOf(
            "telephony" to PackageManager.FEATURE_TELEPHONY,
            "wifi" to PackageManager.FEATURE_WIFI,
            "wifiDirect" to PackageManager.FEATURE_WIFI_DIRECT,
            "wifiAware" to PackageManager.FEATURE_WIFI_AWARE,
            "wifiRtt" to PackageManager.FEATURE_WIFI_RTT,
            "wifiPasspoint" to PackageManager.FEATURE_WIFI_PASSPOINT,
            "bluetooth" to PackageManager.FEATURE_BLUETOOTH,
            "bluetoothLe" to PackageManager.FEATURE_BLUETOOTH_LE,
            "nfc" to PackageManager.FEATURE_NFC,
            "nfcHce" to PackageManager.FEATURE_NFC_HOST_CARD_EMULATION,
            "nfcHceF" to PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF,
            "nfcOffHostEse" to PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE,
            "nfcOffHostUicc" to PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC,
            "gps" to PackageManager.FEATURE_LOCATION_GPS,
            "microphone" to PackageManager.FEATURE_MICROPHONE,
            "uwb" to "android.hardware.uwb",
            "consumerIr" to PackageManager.FEATURE_CONSUMER_IR,
            "watch" to PackageManager.FEATURE_WATCH,
        )
        val key = JSONObject()
        keys.forEach { (k, f) -> key.put(k, pm.hasSystemFeature(f)) }
        o.put("key", key)

        highlights["LTE/telephony"] = yn(key.getBoolean("telephony"))
        highlights["NFC / HCE"] = "${yn(key.getBoolean("nfc"))} / ${yn(key.getBoolean("nfcHce"))}"
        highlights["Wi-Fi RTT / Aware"] = "${yn(key.getBoolean("wifiRtt"))} / ${yn(key.getBoolean("wifiAware"))}"
        return o
    }

    // --------------------------------------------------------------- battery

    private fun battery(): JSONObject {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        return JSONObject()
            .put("capacityPct", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("currentNow_uA", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            .put("chargeCounter_uAh", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
            .put("charging", bm.isCharging)
    }

    private fun currentNow(): Int =
        ctx.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

    // ------------------------------------------------------------- telephony

    private fun telephony(): JSONObject {
        val o = JSONObject().put("feature", pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return o.put("manager", false)
        o.put("phoneType", tm.phoneType)
            .put("simState", tm.simState)
            .put("activeModemCount", tm.activeModemCount)
            .put("networkOperatorName", tm.networkOperatorName)
        return o
    }

    // --------------------------------------------------------------- sensors

    private fun sensors(): JSONObject {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val arr = JSONArray()
        for (s in sm.getSensorList(Sensor.TYPE_ALL)) {
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("vendor", s.vendor)
                    .put("type", s.type)
                    .put("stringType", s.stringType)
                    .put("minDelay_us", s.minDelay)
                    .put("maxRate_Hz", if (s.minDelay > 0) 1_000_000.0 / s.minDelay else JSONObject.NULL)
                    .put("maxRange", s.maximumRange.toDouble())
                    .put("resolution", s.resolution.toDouble())
                    .put("wakeUp", s.isWakeUpSensor)
                    .put("fifoMax", s.fifoMaxEventCount),
            )
        }
        val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        highlights["Magnetometer max rate"] = when {
            mag == null -> "none"
            mag.minDelay > 0 -> "%.0f Hz".format(1_000_000.0 / mag.minDelay)
            else -> "on-change"
        }
        return JSONObject().put("count", arr.length()).put("list", arr)
    }

    // ----------------------------------------------------------------- audio

    private suspend fun audio(): JSONObject {
        val am = ctx.getSystemService(AudioManager::class.java)
        val o = JSONObject()
            .put("outputSampleRate", am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE))
            .put("unprocessedSourceSupported", am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))

        val minBuf = JSONObject()
        for (rate in listOf(8000, 16000, 22050, 44100, 48000, 96000)) {
            minBuf.put(
                rate.toString(),
                AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            )
        }
        o.put("minBufferByRate", minBuf)

        val inputs = JSONArray()
        for (d in am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            inputs.put(
                JSONObject()
                    .put("type", d.type)
                    .put("product", d.productName?.toString())
                    .put("sampleRates", JSONArray(d.sampleRates.toList())),
            )
        }
        o.put("inputDevices", inputs)

        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            highlights["Mic 48 kHz"] = "no permission"
            return o.put("recordTest", "RECORD_AUDIO not granted")
        }
        val tests = JSONObject()
        for ((label, source) in listOf("MIC" to MediaRecorder.AudioSource.MIC,
                                       "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED)) {
            tests.put(label, recordTest(source, 48000))
        }
        o.put("recordTest48k", tests)
        highlights["Mic 48 kHz"] = tests.optJSONObject("MIC")?.optBoolean("ok")?.let(::yn) ?: "?"
        return o
    }

    private suspend fun recordTest(source: Int, rate: Int): JSONObject = withContext(Dispatchers.IO) {
        val bufSize = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (bufSize <= 0) return@withContext JSONObject().put("ok", false).put("reason", "minBuffer=$bufSize")
        val rec = try {
            AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 4)
        } catch (t: Throwable) {
            return@withContext JSONObject().put("ok", false).put("reason", t.toString())
        }
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext JSONObject().put("ok", false).put("reason", "not initialized")
            }
            val samples = ShortArray(rate / 2) // 0.5 s
            rec.startRecording()
            var read = 0
            while (read < samples.size) {
                val n = rec.read(samples, read, samples.size - read)
                if (n <= 0) break
                read += n
            }
            rec.stop()
            var sum = 0.0
            for (i in 0 until read) sum += samples[i].toDouble() * samples[i]
            JSONObject()
                .put("ok", read > 0)
                .put("actualSampleRate", rec.sampleRate)
                .put("samplesRead", read)
                .put("rms", if (read > 0) sqrt(sum / read) else 0.0)
        } finally {
            rec.release()
        }
    }

    // ------------------------------------------------------------------ wifi

    private suspend fun wifi(): JSONObject {
        val wm = ctx.getSystemService(WifiManager::class.java)
        val o = JSONObject()
            .put("enabled", wm.isWifiEnabled)
            .put("scanAlwaysAvailable", runCatching { wm.isScanAlwaysAvailable }.getOrNull())
            .put("scanThrottleEnabled", wm.isScanThrottleEnabled)
            .put("band5GHz", wm.is5GHzBandSupported)
            .put("band6GHz", wm.is6GHzBandSupported)
            .put("wpa3Sae", wm.isWpa3SaeSupported)
            .put("enhancedOpen", wm.isEnhancedOpenSupported)
            .put("p2p", wm.isP2pSupported)
            .put("staApConcurrency", wm.isStaApConcurrencySupported)
            .put("std11n", wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11N))
            .put("std11ac", wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AC))
            .put("std11ax", wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX))
        if (Build.VERSION.SDK_INT >= 31) o.put("band24GHz", wm.is24GHzBandSupported)

        highlights["Wi-Fi 5 GHz"] = yn(wm.is5GHzBandSupported)
        highlights["Wi-Fi scan throttle"] = if (wm.isScanThrottleEnabled) "ON (disable via adb)" else "off"

        if (!wm.isWifiEnabled && !wm.isScanAlwaysAvailable) {
            highlights["Wi-Fi scan"] = "Wi-Fi off — turn it on and re-run"
            return o.put("scanTest", "skipped: Wi-Fi disabled")
        }

        val results: List<ScanResult>? = withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        runCatching { ctx.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(wm.scanResults)
                    }
                }
                ContextCompat.registerReceiver(
                    ctx, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                @Suppress("DEPRECATION")
                val started = wm.startScan()
                o.put("startScanReturned", started)
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
            }
        }
        val list = results ?: wm.scanResults.also { o.put("scanTimedOut", true) }

        var withIes = 0
        var withWps = 0
        val ieIds = sortedSetOf<Int>()
        val bands = JSONObject()
        val standards = JSONObject()
        for (r in list) {
            val ies = r.informationElements
            if (ies.isNotEmpty()) withIes++
            var wps = false
            for (ie in ies) {
                ieIds += ie.id
                val buf = ie.bytes.duplicate()
                val body = ByteArray(buf.remaining()).also { buf.get(it) }
                if (WifiIe.isWps(ie.id, body)) wps = true
            }
            if (wps) withWps++
            val band = when (r.frequency) {
                in 2400..2500 -> "2.4"
                in 4900..5900 -> "5"
                in 5925..7125 -> "6"
                else -> "other"
            }
            bands.put(band, bands.optInt(band) + 1)
            standards.put(r.wifiStandard.toString(), standards.optInt(r.wifiStandard.toString()) + 1)
        }
        o.put(
            "scanTest", JSONObject()
                .put("apCount", list.size)
                .put("withInformationElements", withIes)
                .put("withWps", withWps)
                .put("ieIdsSeen", JSONArray(ieIds.toList()))
                .put("bands", bands)
                .put("wifiStandards", standards),
        )
        highlights["Wi-Fi scan"] = "${list.size} APs, IEs on $withIes, WPS on $withWps"
        return o
    }

    // ------------------------------------------------------------- bluetooth

    private suspend fun bluetooth(): JSONObject {
        val adapter: BluetoothAdapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return JSONObject().put("adapter", false)
        val o = JSONObject()
            .put("enabled", adapter.isEnabled)
            .put("multipleAdvertisement", runCatching { adapter.isMultipleAdvertisementSupported }.getOrNull())
            .put("le2MPhy", runCatching { adapter.isLe2MPhySupported }.getOrNull())
            .put("leCodedPhy", runCatching { adapter.isLeCodedPhySupported }.getOrNull())
            .put("leExtendedAdvertising", runCatching { adapter.isLeExtendedAdvertisingSupported }.getOrNull())
            .put("lePeriodicAdvertising", runCatching { adapter.isLePeriodicAdvertisingSupported }.getOrNull())
            .put("leMaxAdvDataLength", runCatching { adapter.leMaximumAdvertisingDataLength }.getOrNull())
            .put("offloadedFiltering", runCatching { adapter.isOffloadedFilteringSupported }.getOrNull())
            .put("offloadedScanBatching", runCatching { adapter.isOffloadedScanBatchingSupported }.getOrNull())

        // Can we get a HID Device profile proxy (watch acting as a BT keyboard)?
        val hid = withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                val ok = adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        adapter.closeProfileProxy(profile, proxy)
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onServiceDisconnected(profile: Int) {}
                }, BluetoothProfile.HID_DEVICE)
                if (!ok && cont.isActive) cont.resume(false)
            }
        } ?: false
        o.put("hidDeviceProfile", hid)
        highlights["BLE ext. adv / coded PHY"] =
            "${yn(o.optBoolean("leExtendedAdvertising"))} / ${yn(o.optBoolean("leCodedPhy"))}"
        highlights["BT HID device"] = yn(hid)

        if (!adapter.isEnabled) return o.put("scanTest", "skipped: Bluetooth disabled")
        val scanner = adapter.bluetoothLeScanner ?: return o.put("scanTest", "no LE scanner")

        val addresses = HashSet<String>()
        val companies = HashMap<Int, Int>()
        var callbacks = 0
        var scanError: Int? = null
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: BleScanResult) {
                callbacks++
                addresses += result.device.address
                val msd = result.scanRecord?.manufacturerSpecificData ?: return
                for (i in 0 until msd.size()) companies.merge(msd.keyAt(i), 1, Int::plus)
            }
            override fun onScanFailed(errorCode: Int) { scanError = errorCode }
        }
        val idleCurrent = currentNow()
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        delay(4_000)
        val scanCurrent = currentNow()
        delay(4_000)
        scanner.stopScan(cb)

        val top = companies.entries.sortedByDescending { it.value }.take(10)
            .fold(JSONObject()) { acc, e -> acc.put("0x%04X".format(e.key), e.value) }
        o.put(
            "scanTest", JSONObject()
                .put("seconds", 8)
                .put("uniqueAddresses", addresses.size)
                .put("advertisementsReceived", callbacks)
                .put("scanFailedCode", scanError ?: JSONObject.NULL)
                .put("topCompanyIds", top)
                .put("currentIdle_uA", idleCurrent)
                .put("currentDuringScan_uA", scanCurrent),
        )
        highlights["BLE scan"] = scanError?.let { "failed ($it)" } ?: "${addresses.size} devices, $callbacks adverts/8 s"
        return o
    }

    // ------------------------------------------------------------------- nfc

    private suspend fun nfc(): JSONObject {
        val adapter = NfcAdapter.getDefaultAdapter(ctx)
            ?: return JSONObject().put("adapter", false).also { highlights["NFC reader mode"] = "no adapter" }
        val o = JSONObject()
            .put("adapter", true)
            .put("enabled", adapter.isEnabled)
            .put("secureNfcSupported", runCatching { adapter.isSecureNfcSupported }.getOrNull())
        if (Build.VERSION.SDK_INT >= 35) {
            o.put("observeModeSupported", runCatching { adapter.isObserveModeSupported }.getOrNull())
            o.put("readerOptionSupported", runCatching { adapter.isReaderOptionSupported }.getOrNull())
        } else {
            o.put("observeModeSupported", "API < 35")
        }

        if (!adapter.isEnabled) {
            highlights["NFC reader mode"] = "NFC off — enable and re-run"
            return o.put("readerMode", "skipped: NFC disabled")
        }

        // The decisive test: enable reader mode and wait for a real tag.
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        var tagInfo: JSONObject? = null
        val reader = JSONObject()
        try {
            val tag = withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine { cont ->
                    adapter.enableReaderMode(activity, { t ->
                        if (cont.isActive) cont.resume(t)
                    }, flags, null)
                }
            }
            reader.put("enableReaderMode", "ok")
            if (tag != null) {
                tagInfo = JSONObject()
                    .put("techList", JSONArray(tag.techList.map { it.substringAfterLast('.') }))
                    .put("uidLength", tag.id.size)
            }
        } catch (t: Throwable) {
            reader.put("enableReaderMode", "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { adapter.disableReaderMode(activity) }
        }
        reader.put("tagDiscovered", tagInfo ?: JSONObject.NULL)
        o.put("readerMode", reader)

        highlights["NFC reader mode"] = when {
            tagInfo != null -> "WORKS (${tagInfo.getJSONArray("techList").join(",").replace("\"", "")})"
            reader.optString("enableReaderMode") == "ok" -> "enabled, no tag seen"
            else -> "BLOCKED: ${reader.optString("enableReaderMode")}"
        }
        highlights["NFC observe mode"] = o.opt("observeModeSupported")?.toString() ?: "?"
        return o
    }

    // ------------------------------------------------------------------ gnss

    private suspend fun gnss(): JSONObject {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val o = JSONObject()
            .put("locationEnabled", lm.isLocationEnabled)
            .put("gpsProviderEnabled", lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
            .put("providers", JSONArray(lm.allProviders))
            .put("hardwareModel", lm.gnssHardwareModelName)
            .put("yearOfHardware", lm.gnssYearOfHardware)
        if (Build.VERSION.SDK_INT >= 31) {
            val caps = lm.gnssCapabilities
            o.put("capabilities", caps.toString())
                .put("hasMeasurements", caps.hasMeasurements())
                .put("hasNavigationMessages", caps.hasNavigationMessages())
                .put("hasAntennaInfo", caps.hasAntennaInfo())
            highlights["GNSS raw measurements"] = yn(caps.hasMeasurements())
        }

        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) || !lm.isLocationEnabled) {
            highlights["GNSS live test"] = "skipped (location off or no permission)"
            return o.put("liveTest", "skipped")
        }

        val exec = ContextCompat.getMainExecutor(ctx)
        var statusUpdates = 0
        var maxSats = 0
        var maxUsedInFix = 0
        var maxCn0 = 0f
        val constellations = sortedSetOf<Int>()
        var measEvents = 0
        var maxMeasPerEvent = 0
        var agcEntries = 0
        var legacyAgcSeen = false
        var fixes = 0

        val statusCb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                statusUpdates++
                maxSats = maxOf(maxSats, status.satelliteCount)
                var used = 0
                for (i in 0 until status.satelliteCount) {
                    constellations += status.getConstellationType(i)
                    maxCn0 = maxOf(maxCn0, status.getCn0DbHz(i))
                    if (status.usedInFix(i)) used++
                }
                maxUsedInFix = maxOf(maxUsedInFix, used)
            }
        }
        val measCb = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                measEvents++
                maxMeasPerEvent = maxOf(maxMeasPerEvent, event.measurements.size)
                if (Build.VERSION.SDK_INT >= 33) agcEntries += event.gnssAutomaticGainControls.size
                @Suppress("DEPRECATION")
                if (event.measurements.any { it.hasAutomaticGainControlLevelDb() }) legacyAgcSeen = true
            }
        }
        val locListener = LocationListener { fixes++ }

        lm.registerGnssStatusCallback(exec, statusCb)
        val measRegistered = lm.registerGnssMeasurementsCallback(exec, measCb)
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, locListener, Looper.getMainLooper())
        try {
            delay(20_000)
        } finally {
            lm.removeUpdates(locListener)
            lm.unregisterGnssMeasurementsCallback(measCb)
            lm.unregisterGnssStatusCallback(statusCb)
        }

        val names = mapOf(1 to "GPS", 2 to "SBAS", 3 to "GLONASS", 4 to "QZSS", 5 to "BEIDOU", 6 to "GALILEO", 7 to "IRNSS")
        o.put(
            "liveTest", JSONObject()
                .put("seconds", 20)
                .put("statusUpdates", statusUpdates)
                .put("maxSatellites", maxSats)
                .put("maxUsedInFix", maxUsedInFix)
                .put("maxCn0DbHz", maxCn0.toDouble())
                .put("constellations", JSONArray(constellations.map { names[it] ?: it.toString() }))
                .put("measurementsCallbackRegistered", measRegistered)
                .put("measurementEvents", measEvents)
                .put("maxMeasurementsPerEvent", maxMeasPerEvent)
                .put("agcEntries", agcEntries)
                .put("legacyAgcSeen", legacyAgcSeen)
                .put("fixes", fixes),
        )
        highlights["GNSS live"] = "$maxSats sats, $measEvents meas events, fixes=$fixes"
        highlights["GNSS AGC"] = yn(agcEntries > 0 || legacyAgcSeen)
        return o
    }

    private fun yn(b: Boolean) = if (b) "yes" else "no"

    companion object {
        val RUNTIME_PERMISSIONS: List<String> = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }
}
