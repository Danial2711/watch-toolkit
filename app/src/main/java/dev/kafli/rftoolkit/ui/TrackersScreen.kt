package dev.kafli.rftoolkit.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.kafli.rftoolkit.modules.ble.TrackerTrack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrackersScreen(
    tracks: List<TrackerTrack>,
    scanning: Boolean,
    scanStatus: String?,
    guardRunning: Boolean,
    otherSurveyRunning: Boolean,
    onScan: () -> Unit,
    onToggleGuard: () -> Unit,
    onClear: () -> Unit,
    onHunt: (TrackerTrack) -> Unit,
) {
    var expanded by remember { mutableStateOf<String?>(null) }
    val listState = rememberScalingLazyListState()
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Trackers") } }
            item {
                Button(
                    onClick = onScan,
                    enabled = !scanning,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (scanning) "Scanning 15 s…" else "Scan nearby") },
                    secondaryLabel = scanStatus?.let { { Text(it) } },
                )
            }
            item {
                FilledTonalButton(
                    onClick = onToggleGuard,
                    enabled = !otherSurveyRunning,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (guardRunning) "Stop tracker guard" else "Start tracker guard") },
                    secondaryLabel = {
                        Text(
                            when {
                                otherSurveyRunning -> "Survey running (covers trackers)"
                                guardRunning -> "Background · low power"
                                else -> "Alerts if one follows you"
                            },
                        )
                    },
                )
            }
            item {
                Text(
                    "${tracks.size} seen · ${tracks.count { it.tracker.separated == true }} separated · " +
                        "${tracks.count { it.following }} following",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            tracks.forEach { t ->
                item(key = t.id) {
                    val state = when {
                        t.following -> "‼ FOLLOWING"
                        t.persistent -> "! persistent"
                        t.tracker.separated == true -> "separated"
                        t.tracker.separated == false -> "owner nearby"
                        else -> "state unknown"
                    }
                    val colors = when {
                        t.following -> ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        t.persistent || t.tracker.separated == true -> ButtonDefaults.filledTonalButtonColors()
                        else -> ButtonDefaults.outlinedButtonColors()
                    }
                    Button(
                        onClick = { expanded = if (expanded == t.id) null else t.id },
                        colors = colors,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(t.tracker.kind.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = {
                            Text("$state · ${t.lastRssi} dBm", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
                if (expanded == t.id) {
                    item(key = "${t.id}/detail") {
                        Text(
                            "${t.id}\n${t.tracker.detail}\n" +
                                "${time.format(Date(t.firstMs))}–${time.format(Date(t.lastMs))} · ${t.sightings}× · " +
                                "${t.places.size} places · best ${t.bestRssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item(key = "${t.id}/hunt") {
                        FilledTonalButton(
                            onClick = { onHunt(t) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Fox hunt this") },
                        )
                    }
                }
            }
            item {
                OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth(), label = { Text("Clear list") })
            }
        }
    }
}
