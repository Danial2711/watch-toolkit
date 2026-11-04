package dev.kafli.rftoolkit.modules.ble

/**
 * Parsed BLE advertising payload (legacy, ≤31 bytes per PDU; scan response appended by Android).
 * Pure Kotlin so it can be tested with raw payloads recorded by the survey.
 */
data class AdvData(
    val flags: Int?,
    val uuids16: List<Int>,
    /** 16-bit service UUID → service data (bytes after the UUID). */
    val serviceData16: Map<Int, ByteArray>,
    /** Company identifier → manufacturer-specific data (bytes after the company ID). */
    val manufacturer: Map<Int, ByteArray>,
    val name: String?,
    val txPower: Int?,
)

object Adv {
    // AD types (Bluetooth Assigned Numbers §2.3)
    private const val FLAGS = 0x01
    private const val UUID16_INCOMPLETE = 0x02
    private const val UUID16_COMPLETE = 0x03
    private const val NAME_SHORT = 0x08
    private const val NAME_COMPLETE = 0x09
    private const val TX_POWER = 0x0A
    private const val SERVICE_DATA16 = 0x16
    private const val MANUFACTURER = 0xFF

    fun parse(raw: ByteArray): AdvData {
        var flags: Int? = null
        val uuids = mutableListOf<Int>()
        val svc = LinkedHashMap<Int, ByteArray>()
        val mfr = LinkedHashMap<Int, ByteArray>()
        var name: String? = null
        var tx: Int? = null
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len == 0 || i + 1 + len > raw.size) break
            val type = raw[i + 1].toInt() and 0xFF
            val data = raw.copyOfRange(i + 2, i + 1 + len)
            when (type) {
                FLAGS -> flags = data.firstOrNull()?.toInt()?.and(0xFF)
                UUID16_INCOMPLETE, UUID16_COMPLETE ->
                    for (j in 0 until data.size / 2) uuids += u16le(data, j * 2)
                NAME_SHORT, NAME_COMPLETE -> if (name == null || type == NAME_COMPLETE) {
                    name = String(data, Charsets.UTF_8).trimEnd('\u0000')
                }
                TX_POWER -> tx = data.firstOrNull()?.toInt()
                SERVICE_DATA16 -> if (data.size >= 2) svc[u16le(data, 0)] = data.copyOfRange(2, data.size)
                MANUFACTURER -> if (data.size >= 2) mfr[u16le(data, 0)] = data.copyOfRange(2, data.size)
            }
            i += len + 1
        }
        return AdvData(flags, uuids, svc, mfr, name, tx)
    }

    private fun u16le(b: ByteArray, off: Int) = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}
