package dev.kafli.rftoolkit.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.kafli.rftoolkit.modules.survey.SurveyStatus
import kotlinx.coroutines.delay

@Composable
fun SurveyScreen(
    status: SurveyStatus,
    onToggle: () -> Unit,
    onCyclePreset: () -> Unit,
    onToggleKeepWifi: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.running) {
        while (status.running) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("City survey") } }
            item {
                Button(
                    onClick = onToggle,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (status.running) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
                    label = { Text(if (status.running) "Stop survey" else "Start survey") },
                    secondaryLabel = if (status.running) {
                        { Text(elapsed(now - status.startMs)) }
                    } else null,
                )
            }
            item {
                FilledTonalButton(
                    onClick = onCyclePreset,
                    enabled = !status.running,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Preset: ${status.preset.label}") },
                    secondaryLabel = { Text(presetHint(status.preset.name)) },
                )
            }
            item {
                FilledTonalButton(
                    onClick = onToggleKeepWifi,
                    enabled = !status.running,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Keep Wi-Fi: ${if (status.keepWifi) "on" else "off"}") },
                    secondaryLabel = { Text("holds Wi-Fi link · battery") },
                )
            }
            val lines = buildList {
                add("Wi-Fi: ${status.uniqueAps} APs · ${status.wifiScans} scans")
                add("BLE: ${status.uniqueBle} devices · ${status.bleAdverts} adv")
                add(
                    "Fix: " + (status.fixAccM?.let { "±%.0f m, %ds ago".format(it, status.fixAgeS ?: 0) } ?: "none yet"),
                )
                status.locProviders?.let { add("Loc via: $it") }
                add("Findings: ${status.findings} (${status.highFindings} high)")
                add("Log: ${status.records} rec · ${status.bytes / 1024} KB")
                val drop = status.battStartPct?.let { s -> status.battNowPct?.let { s - it } }
                add("Battery: ${status.battNowPct ?: "?"}%" + (drop?.let { " (−$it%)" } ?: ""))
                status.lastError?.let { add("⚠ $it") }
            }
            lines.forEach { line ->
                item {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun presetHint(name: String) = when (name) {
    "LOW" -> "Wi-Fi 60 s · BLE 10/60 s"
    "BALANCED" -> "Wi-Fi 15 s · BLE 15/30 s"
    else -> "Wi-Fi 5 s · BLE continuous"
}

private fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}
