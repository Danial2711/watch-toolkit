package dev.kafli.rftoolkit.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.kafli.rftoolkit.modules.net.LinkInfo

enum class NetTool(val label: String, val needsPrivate: Boolean = false) {
    PING("Ping"), TRACE("Traceroute"), DNS("DNS check"), TLS("TLS inspect"), HEADERS("HTTP headers"),
    PORTS("Port scan", needsPrivate = true), SWEEP("Sweep subnet"),
}

@Composable
fun NetToolsScreen(
    link: LinkInfo?,
    target: String,
    running: NetTool?,
    progress: String?,
    output: List<String>,
    onEnter: () -> Unit,
    onExit: () -> Unit,
    onEditTarget: () -> Unit,
    onSetTarget: (String) -> Unit,
    onRun: (NetTool) -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onEnter()
        onDispose {
            view.keepScreenOn = false
            onExit()
        }
    }
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Network tools") } }
            item {
                Text(
                    link?.let {
                        (if (it.viaWifi) "Wi-Fi " else "⚠ not Wi-Fi ") + "${it.ip ?: "?"}/${it.prefix ?: "?"}\ngw ${it.gateway ?: "?"} · dns ${it.dns.firstOrNull() ?: "?"}"
                    } ?: "Connecting to Wi-Fi…",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = onEditTarget,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Target: $target", maxLines = 2) },
                    secondaryLabel = { Text("tap to edit") },
                )
            }
            link?.gateway?.let { gw ->
                item {
                    OutlinedButton(onClick = { onSetTarget(gw) }, modifier = Modifier.fillMaxWidth(), label = { Text("Use gateway") })
                }
            }
            NetTool.entries.forEach { tool ->
                item {
                    FilledTonalButton(
                        onClick = { onRun(tool) },
                        enabled = running == null,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (running == tool) "${tool.label}… ${progress ?: ""}" else tool.label) },
                        secondaryLabel = when (tool) {
                            NetTool.PORTS -> ({ Text("private IPs only") })
                            NetTool.SWEEP -> ({ Text("own /24, common ports") })
                            NetTool.DNS -> ({ Text("vs DNS-over-HTTPS") })
                            else -> null
                        },
                    )
                }
            }
            output.forEach { line ->
                item {
                    Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
