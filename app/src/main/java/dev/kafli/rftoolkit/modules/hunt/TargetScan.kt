package dev.kafli.rftoolkit.modules.hunt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import dev.kafli.rftoolkit.modules.ble.Adv
import dev.kafli.rftoolkit.modules.ble.Trackers
import dev.kafli.rftoolkit.modules.survey.BleFilters
import dev.kafli.rftoolkit.modules.wifi.WifiScan
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

data class Candidate(val target: HuntTarget, val rssi: Int, val kind: String)

/** Quick scan for hunt targets: BLE devices (unfiltered) and Wi-Fi APs, strongest first. */
object TargetScan {

    /** Best human label for an advertisement, e.g. "Apple Find My (separated)" or "Microsoft". */
    fun bleLabel(raw: ByteArray?, deviceName: String?): String {
        if (raw != null) {
            val adv = Adv.parse(raw)
            Trackers.classify(adv)?.let { t ->
                val state = when (t.separated) { true -> " (separated)"; false -> " (owner near)"; null -> "" }
                return t.kind.label + state
            }
            adv.name?.takeIf { it.isNotBlank() }?.let { return it }
            adv.manufacturer.keys.firstOrNull()?.let { id -> BleFilters.COMPANY_IDS[id]?.let { return it } }
        }
        return deviceName?.takeIf { it.isNotBlank() } ?: "BLE device"
    }

    @SuppressLint("MissingPermission")
    suspend fun scan(ctx: Context, bleMs: Long = 6_000): List<Candidate> = coroutineScope {
        val wifi = async {
            runCatching { WifiScan.scanOnce(ctx, timeoutMs = 10_000, maxAgeMs = 20_000) }.getOrElse { emptyList() }
        }
        val best = HashMap<String, Candidate>()
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner != null) {
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, r: ScanResult) {
                    val addr = r.device.address
                    synchronized(best) {
                        val prev = best[addr]
                        if (prev == null || r.rssi > prev.rssi) {
                            val label = bleLabel(r.scanRecord?.bytes, r.scanRecord?.deviceName)
                            best[addr] = Candidate(HuntTarget.Ble(addr, label), r.rssi, "BLE")
                        }
                    }
                }
            }
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            try {
                delay(bleMs)
            } finally {
                runCatching { scanner.stopScan(cb) }
            }
        }
        @Suppress("DEPRECATION")
        val aps = wifi.await()
            .filter { it.level != 0 }
            .distinctBy { it.BSSID }
            .map { Candidate(HuntTarget.Wifi(it.BSSID, it.SSID ?: ""), it.level, "Wi-Fi") }
        val ble = synchronized(best) { best.values.toList() }
        (ble + aps).sortedByDescending { it.rssi }
    }
}
