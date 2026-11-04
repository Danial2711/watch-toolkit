package dev.kafli.rftoolkit.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

@Composable
fun ProbeScreen(
    running: Boolean,
    status: String,
    highlights: Map<String, String>,
    onRun: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item { ListHeader { Text("Capability probe") } }
            item {
                Button(
                    onClick = onRun,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (running) "Running…" else "Run probe") },
                )
            }
            item {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            highlights.forEach { (k, v) ->
                item {
                    Text("$k: $v", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
