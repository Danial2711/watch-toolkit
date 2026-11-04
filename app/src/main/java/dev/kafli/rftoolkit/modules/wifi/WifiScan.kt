package dev.kafli.rftoolkit.modules.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Android-side helpers: ScanResult → [ApProfile], and a one-shot scan. */
object WifiScan {

    fun rawIes(r: ScanResult): List<RawIe> = r.informationElements.map { ie ->
        val buf = ie.bytes.duplicate()
        RawIe(ie.id, ie.idExt, ByteArray(buf.remaining()).also { buf.get(it) })
    }

    fun wallClockMs(r: ScanResult, nowMs: Long = System.currentTimeMillis()): Long =
        nowMs - SystemClock.elapsedRealtime() + r.timestamp / 1000

    @Suppress("DEPRECATION")
    fun profile(r: ScanResult, ies: List<RawIe> = rawIes(r)): ApProfile =
        ApProfile.from(
            bssid = r.BSSID, ssid = r.SSID, capabilities = r.capabilities ?: "", freqMhz = r.frequency,
            rssi = r.level.takeIf { it != 0 }, ms = wallClockMs(r), ies = ies,
        )

    /** Triggers a scan and returns results younger than [maxAgeMs]; falls back to cached results on timeout. */
    @SuppressLint("MissingPermission")
    suspend fun scanOnce(ctx: Context, timeoutMs: Long = 12_000, maxAgeMs: Long = 30_000): List<ScanResult> {
        val wm = ctx.getSystemService(WifiManager::class.java)
        val results = withTimeoutOrNull(timeoutMs) {
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
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
                @Suppress("DEPRECATION")
                wm.startScan()
            }
        } ?: wm.scanResults
        val now = System.currentTimeMillis()
        return results.filter { now - wallClockMs(it, now) <= maxAgeMs }
    }
}
