package dev.kafli.rftoolkit.modules.survey

import android.bluetooth.le.ScanFilter
import android.os.ParcelUuid

/**
 * Android (8.1+) does not deliver *unfiltered* BLE scan results while the screen is off.
 * A watch screen is off most of the time, so the survey runs two scanners:
 *  - unfiltered: catches everything while the screen is on
 *  - this broad filter set: keeps working with the screen off (and is hardware-offloaded)
 *
 * The list is biased toward tracker ecosystems and common vendors; extend as needed.
 */
object BleFilters {
    /** Bluetooth SIG company identifiers (manufacturer-specific data). */
    val COMPANY_IDS = mapOf(
        0x004C to "Apple",          // AirTag / Find My / Continuity
        0x0006 to "Microsoft",
        0x0075 to "Samsung",
        0x00E0 to "Google",
        0x0087 to "Garmin",
        0x0157 to "Huami/Zepp",
        0x038F to "Xiaomi",
        0x0059 to "Nordic",
        0x02E5 to "Espressif",
        0x0499 to "Ruuvi",
        0x0171 to "Amazon",
    )

    /** 16-bit service UUIDs (as service data or advertised service). */
    val SERVICE_UUIDS16 = mapOf(
        0xFD5A to "Samsung SmartTag",
        0xFD59 to "Samsung SmartTag (alt)",
        0xFEED to "Tile",
        0xFEEC to "Tile",
        0xFEAA to "Google Eddystone/FMDN",
        0xFE2C to "Google Fast Pair",
        0xFD6F to "Exposure Notification",
        0xFE9F to "Google",
        0xFCD2 to "BTHome",
        0xFE95 to "Xiaomi MiBeacon",
    )

    fun uuid16(v: Int): ParcelUuid =
        ParcelUuid.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(v))

    fun broad(): List<ScanFilter> = buildList {
        COMPANY_IDS.keys.forEach { add(ScanFilter.Builder().setManufacturerData(it, ByteArray(0)).build()) }
        SERVICE_UUIDS16.keys.forEach {
            add(ScanFilter.Builder().setServiceData(uuid16(it), ByteArray(0)).build())
            add(ScanFilter.Builder().setServiceUuid(uuid16(it)).build())
        }
    }
}
