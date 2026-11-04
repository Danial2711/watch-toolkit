package dev.kafli.rftoolkit.modules.survey

object WifiChannels {
    /** IEEE 802.11 channel number for a centre frequency in MHz, or -1 if unknown. */
    fun channel(freqMhz: Int): Int = when (freqMhz) {
        2484 -> 14
        in 2412..2472 -> (freqMhz - 2407) / 5
        in 5160..5885 -> (freqMhz - 5000) / 5
        in 5955..7115 -> (freqMhz - 5950) / 5
        else -> -1
    }

    fun band(freqMhz: Int): String = when (freqMhz) {
        in 2400..2500 -> "2.4"
        in 4900..5900 -> "5"
        in 5925..7125 -> "6"
        else -> "?"
    }
}
