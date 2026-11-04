package dev.kafli.rftoolkit.modules.survey

/**
 * Battery/detail trade-off for a survey session.
 *
 * BLE runs as on/off windows (each window = scanner start). Android allows at most
 * 5 scan starts per 30 s per app; we start 2 scanners per window (filtered + unfiltered),
 * so every preset must keep windows >= 15 s apart. `bleOffMs == 0` means continuous.
 */
enum class SurveyPreset(
    val label: String,
    val wifiIntervalMs: Long,
    val bleOnMs: Long,
    val bleOffMs: Long,
    val locationIntervalMs: Long,
    /** Minimum time between two logged sightings of the same BLE advertisement. */
    val bleRepeatMs: Long,
) {
    LOW("Low", wifiIntervalMs = 60_000, bleOnMs = 10_000, bleOffMs = 50_000, locationIntervalMs = 30_000, bleRepeatMs = 30_000),
    BALANCED("Balanced", wifiIntervalMs = 15_000, bleOnMs = 15_000, bleOffMs = 15_000, locationIntervalMs = 5_000, bleRepeatMs = 10_000),
    MAX("Max", wifiIntervalMs = 5_000, bleOnMs = 20 * 60_000, bleOffMs = 0, locationIntervalMs = 1_000, bleRepeatMs = 3_000);

    val bleContinuous: Boolean get() = bleOffMs == 0L

    fun next(): SurveyPreset = entries[(ordinal + 1) % entries.size]
}
