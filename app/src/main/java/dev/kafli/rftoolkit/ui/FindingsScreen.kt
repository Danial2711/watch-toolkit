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
import dev.kafli.rftoolkit.modules.wifi.FindingEntry
import dev.kafli.rftoolkit.core.Severity

@Composable
fun FindingsScreen(
    findings: List<FindingEntry>,
    baselineSsids: Int,
    scanning: Boolean,
    scanStatus: String?,
    onScan: () -> Unit,
    onClear: () -> Unit,
    onResetBaseline: () -> Unit,
    onHunt: (bssid: String, ssid: String) -> Unit,
) {
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val listState = rememberScalingLazyListState()
    val bySeverity = findings.groupingBy { it.finding.type.severity }.eachCount()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Wi-Fi findings") } }
            item {
                Button(
                    onClick = onScan,
                    enabled = !scanning,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (scanning) "Scanning…" else "Scan now") },
                    secondaryLabel = scanStatus?.let { { Text(it) } },
                )
            }
            item {
                Text(
                    "‼ ${bySeverity[Severity.HIGH] ?: 0}  ! ${bySeverity[Severity.MEDIUM] ?: 0}  · ${bySeverity[Severity.LOW] ?: 0}  i ${bySeverity[Severity.INFO] ?: 0}",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (findings.isEmpty()) {
                item {
                    Text(
                        "No findings yet. Scan, or run a survey.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            findings.forEach { e ->
                val f = e.finding
                val key = f.key
                item(key = key) {
                    val colors = when (f.type.severity) {
                        Severity.HIGH -> ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Severity.MEDIUM -> ButtonDefaults.filledTonalButtonColors()
                        else -> ButtonDefaults.outlinedButtonColors()
                    }
                    Button(
                        onClick = { expanded = if (expanded == key) null else key },
                        colors = colors,
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text("${f.type.severity.symbol} ${f.type.title}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        secondaryLabel = {
                            Text(
                                (f.name.ifEmpty { "<hidden>" }) + (e.bestRssi?.let { " · $it dBm" } ?: ""),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
                if (expanded == key) {
                    item(key = "$key/detail") {
                        Text(
                            buildString {
                                append(f.id)
                                if (f.detail.isNotBlank()) append("\n").append(f.detail)
                                append("\n").append(f.type.explain)
                                append("\nSeen ${e.count}×")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item(key = "$key/hunt") {
                        FilledTonalButton(
                            onClick = { onHunt(f.id, f.name) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Fox hunt this AP") },
                        )
                    }
                }
            }
            item {
                FilledTonalButton(
                    onClick = onClear,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Clear list") },
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        if (confirmReset) { onResetBaseline(); confirmReset = false } else confirmReset = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (confirmReset) "Tap again to reset" else "Reset twin baseline") },
                    secondaryLabel = { Text("$baselineSsids SSIDs learned") },
                )
            }
        }
    }
}
