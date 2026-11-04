package dev.kafli.rftoolkit.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
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
import dev.kafli.rftoolkit.modules.nfc.ApduLogEntry
import dev.kafli.rftoolkit.modules.nfc.TagConfig
import dev.kafli.rftoolkit.modules.nfc.TagContentType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NfcTagScreen(
    config: TagConfig,
    log: List<ApduLogEntry>,
    onToggle: () -> Unit,
    onPreset: (TagContentType, String) -> Unit,
    onEditUrl: () -> Unit,
    onEditText: () -> Unit,
    onClearLog: () -> Unit,
    onEnter: () -> Unit,
    onExit: () -> Unit,
) {
    // HCE only answers while the screen is on; keep it on while this screen is open.
    // onEnter/onExit claim/release the shared NDEF AID (Samsung Health registers it too).
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onEnter()
        onDispose {
            view.keepScreenOn = false
            onExit()
        }
    }
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("NFC tag") } }
            item {
                Button(
                    onClick = onToggle,
                    colors = if (config.enabled) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (config.enabled) "Tag ON" else "Tag OFF") },
                    secondaryLabel = { Text(if (config.enabled) "Tap a phone to the watch" else "Tap to enable") },
                )
            }
            item {
                Text(
                    "${if (config.type == TagContentType.URL) "URL" else "Text"}: ${config.value}",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                FilledTonalButton(onClick = onEditUrl, modifier = Modifier.fillMaxWidth(), label = { Text("Set URL…") })
            }
            item {
                FilledTonalButton(onClick = onEditText, modifier = Modifier.fillMaxWidth(), label = { Text("Set text…") })
            }
            item {
                OutlinedButton(
                    onClick = { onPreset(TagContentType.URL, "https://example.com") },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Preset: example.com") },
                )
            }
            item { Text("Reader log", style = MaterialTheme.typography.labelMedium) }
            if (log.isEmpty()) {
                item {
                    Text(
                        "No reader yet",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            log.take(40).forEachIndexed { i, e ->
                item(key = "log/$i/${e.ms}") {
                    Text(
                        "${time.format(Date(e.ms))} ${e.description}\n→ ${e.status}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (log.isNotEmpty()) {
                item {
                    OutlinedButton(onClick = onClearLog, modifier = Modifier.fillMaxWidth(), label = { Text("Clear log") })
                }
            }
        }
    }
}
