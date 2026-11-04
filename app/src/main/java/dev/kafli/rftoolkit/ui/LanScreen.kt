package dev.kafli.rftoolkit.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.kafli.rftoolkit.modules.lan.Isolation
import dev.kafli.rftoolkit.modules.lan.LanState

@Composable
fun LanScreen(state: LanState, onEnter: () -> Unit, onExit: () -> Unit, onAskOnce: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onEnter()
        onDispose {
            view.keepScreenOn = false
            onExit()
        }
    }
    var expanded by remember { mutableStateOf<String?>(null) }
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("LAN listener") } }
            item {
                Text(
                    "${if (state.running) "● " else ""}${state.listenedS}s · ${state.packets} pkts · ${state.devices.size} devices\n" +
                        state.isolation.text,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = if (state.isolation == Isolation.OFF) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                FilledTonalButton(
                    onClick = onAskOnce,
                    enabled = state.running,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ask once") },
                    secondaryLabel = { Text("1 mDNS + 1 SSDP query") },
                )
            }
            state.errors.forEach { e ->
                item { Text("⚠ $e", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth()) }
            }
            state.devices.forEach { d ->
                item(key = d.ip) {
                    Button(
                        onClick = { expanded = if (expanded == d.ip) null else d.ip },
                        colors = ButtonDefaults.outlinedButtonColors(),
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(d.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = {
                            Text(
                                d.ip + (d.services.firstOrNull()?.let { " · $it" } ?: ""),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
                if (expanded == d.ip) {
                    item(key = "${d.ip}/detail") {
                        Text(
                            buildList {
                                add("${d.protocols.joinToString()} · ${d.packets} pkts")
                                if (d.otherIps.isNotEmpty()) add("also: ${d.otherIps.joinToString()}")
                                if (d.names.isNotEmpty()) add("names: ${d.names.joinToString()}")
                                if (d.services.isNotEmpty()) add("services: ${d.services.joinToString()}")
                                if (d.hints.isNotEmpty()) add(d.hints.joinToString("\n"))
                                if (d.lookups.isNotEmpty()) add("looks up: ${d.lookups.joinToString()}")
                            }.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item {
                Text(
                    "In memory only · not saved",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
