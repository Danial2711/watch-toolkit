package dev.kafli.rftoolkit.core

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

/** Distinct wrist patterns so alerts can be told apart without looking. */
object Haptics {
    /** Three long pulses — evil twin / active attack indicators. */
    val ALARM: LongArray = longArrayOf(0, 400, 150, 400, 150, 400)

    /** Two short pulses — notable finding. */
    val NOTICE: LongArray = longArrayOf(0, 120, 100, 120)

    fun play(context: Context, pattern: LongArray) {
        runCatching {
            context.getSystemService(VibratorManager::class.java)
                .defaultVibrator
                .vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }
}
