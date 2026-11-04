package dev.kafli.rftoolkit.modules.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.ParcelUuid
import android.os.SystemClock
import kotlinx.coroutines.delay

/** Hardware-filterable scan filters matching only tracker advertisements, and a one-shot scan. */
object TrackerScan {

    private fun uuid16(v: Int): ParcelUuid = ParcelUuid.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(v))

    fun filters(): List<ScanFilter> = buildList {
        // Apple offline finding: manufacturer 0x004C, first byte = 0x12 (prefix match via mask).
        add(
            ScanFilter.Builder()
                .setManufacturerData(Trackers.APPLE, byteArrayOf(Trackers.APPLE_OFFLINE_FINDING.toByte()), byteArrayOf(0xFF.toByte()))
                .build(),
        )
        add(ScanFilter.Builder().setServiceData(uuid16(Trackers.UUID_FMDN), ByteArray(0)).build())
        add(ScanFilter.Builder().setServiceData(uuid16(Trackers.UUID_SMARTTAG), ByteArray(0)).build())
        add(ScanFilter.Builder().setServiceUuid(uuid16(Trackers.UUID_SMARTTAG)).build())
        Trackers.UUIDS_TILE.forEach {
            add(ScanFilter.Builder().setServiceUuid(uuid16(it)).build())
            add(ScanFilter.Builder().setServiceData(uuid16(it), ByteArray(0)).build())
        }
    }

    @SuppressLint("MissingPermission")
    fun lastFix(ctx: Context): GeoFix? = runCatching {
        val lm = ctx.getSystemService(LocationManager::class.java)
        listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 5 * 60_000 }
            .minByOrNull { it.accuracy }
            ?.let { GeoFix(it.latitude, it.longitude, it.accuracy) }
    }.getOrNull()

    /** Scans for [durationMs], feeding every tracker advert into [TrackerWatch]. Returns adverts matched. */
    @SuppressLint("MissingPermission")
    suspend fun scanOnce(ctx: Context, durationMs: Long = 15_000): Int {
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: error("Bluetooth is off")
        val fix = lastFix(ctx)
        var matched = 0
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                matched++
                val ms = System.currentTimeMillis() - SystemClock.elapsedRealtime() + r.timestampNanos / 1_000_000
                TrackerWatch.process(ctx, r.device.address, r.scanRecord?.bytes, r.rssi, ms, fix)
            }
        }
        scanner.startScan(filters(), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        try {
            delay(durationMs)
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
        return matched
    }
}
