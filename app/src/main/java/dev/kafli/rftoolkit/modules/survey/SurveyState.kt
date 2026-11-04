package dev.kafli.rftoolkit.modules.survey

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Live survey status shared between [SurveyService] and the UI (same process). */
data class SurveyStatus(
    val running: Boolean = false,
    val preset: SurveyPreset = SurveyPreset.BALANCED,
    val sessionId: String? = null,
    val startMs: Long = 0,
    val wifiScans: Int = 0,
    val uniqueAps: Int = 0,
    val bleAdverts: Int = 0,
    val uniqueBle: Int = 0,
    val records: Long = 0,
    val bytes: Long = 0,
    val fixAccM: Float? = null,
    val fixAgeS: Long? = null,
    val battStartPct: Int? = null,
    val battNowPct: Int? = null,
    val lastError: String? = null,
    /** Hold a Wi-Fi network request during the survey (keeps the link up; does NOT keep wireless ADB alive — Wear disables adbd on screen-off). */
    val keepWifi: Boolean = false,
    val locProviders: String? = null,
    val findings: Int = 0,
    val highFindings: Int = 0,
)

object SurveyState {
    private val _status = MutableStateFlow(SurveyStatus())
    val status: StateFlow<SurveyStatus> = _status.asStateFlow()

    fun update(f: (SurveyStatus) -> SurveyStatus) = _status.update(f)

    /** Preset chosen in the UI while stopped; read by the service on start. */
    fun selectPreset(p: SurveyPreset) = _status.update { if (it.running) it else it.copy(preset = p) }

    fun toggleKeepWifi() = _status.update { if (it.running) it else it.copy(keepWifi = !it.keepWifi) }
}
