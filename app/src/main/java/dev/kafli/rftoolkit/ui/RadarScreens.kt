package dev.kafli.rftoolkit.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.kafli.rftoolkit.modules.radar.ChannelStat
import dev.kafli.rftoolkit.modules.radar.ChannelStats
import dev.kafli.rftoolkit.modules.radar.JamState
import dev.kafli.rftoolkit.modules.radar.MagState
import dev.kafli.rftoolkit.modules.radar.UltraMonitor
import dev.kafli.rftoolkit.modules.radar.UltraState

@Composable
private fun RadarList(title: String, keepOn: Boolean = true, onEnter: () -> Unit = {}, onExit: () -> Unit = {}, content: ScalingLazyListScope.() -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        if (keepOn) view.keepScreenOn = true
        onEnter()
        onDispose {
            if (keepOn) view.keepScreenOn = false
            onExit()
        }
    }
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(title) } }
            content()
        }
    }
}

@Composable
private fun Line(text: String, center: Boolean = true, mono: Boolean = false) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    fontFamily = if (mono) FontFamily.Monospace else null,
    textAlign = if (center) TextAlign.Center else TextAlign.Start,
    modifier = Modifier.fillMaxWidth(),
)

@Composable
fun RadarMenuScreen(onEnter: () -> Unit, onOpen: (String) -> Unit) {
    LaunchedEffect(Unit) { onEnter() }
    RadarList("Threat radar", keepOn = false) {
        listOf(
            Triple("Wi-Fi channels", "Congestion · overlap", "radar/channels"),
            Triple("Jamming monitor", "Wi-Fi + BLE + GNSS", "radar/jam"),
            Triple("Ultrasonic", "Beacons 17.5–22 kHz", "radar/ultra"),
            Triple("Magnetometer", "Field anomaly meter", "radar/mag"),
        ).forEach { (t, sub, route) ->
            item {
                Button(onClick = { onOpen(route) }, modifier = Modifier.fillMaxWidth(), label = { Text(t) }, secondaryLabel = { Text(sub) })
            }
        }
    }
}

@Composable
fun ChannelsScreen(stats: List<ChannelStat>, scanning: Boolean, onScan: () -> Unit) {
    LaunchedEffect(Unit) { onScan() }
    RadarList("Wi-Fi channels", keepOn = false) {
        item {
            FilledTonalButton(onClick = onScan, enabled = !scanning, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (scanning) "Scanning…" else "Rescan") })
        }
        val max = stats.maxOfOrNull { it.aps } ?: 0
        val two4 = stats.filter { it.channel <= 14 }
        val five = stats.filter { it.channel > 14 }
        if (two4.isNotEmpty()) {
            item { Line("2.4 GHz") }
            item { Line(two4.joinToString("\n") { "ch%3d %s %2d  %d".format(it.channel, ChannelStats.bar(it.aps, max), it.aps, it.bestRssi) }, center = false, mono = true) }
            val ov = ChannelStats.overlapping24(stats)
            if (ov.isNotEmpty()) item { Line("! Overlapping channels in use: ${ov.joinToString()} (use 1/6/11)") }
        }
        if (five.isNotEmpty()) {
            item { Line("5 GHz") }
            item { Line(five.joinToString("\n") { "ch%3d %s %2d  %d".format(it.channel, ChannelStats.bar(it.aps, max), it.aps, it.bestRssi) }, center = false, mono = true) }
        }
        val busiest = two4.maxByOrNull { it.aps }
        val quiet = listOf(1, 6, 11).minByOrNull { ch -> two4.firstOrNull { it.channel == ch }?.aps ?: 0 }
        if (busiest != null) item { Line("Busiest 2.4: ch ${busiest.channel} · quietest of 1/6/11: ch $quiet") }
    }
}

@Composable
fun JammingScreen(state: JamState, onEnter: () -> Unit, onExit: () -> Unit) {
    RadarList("Jamming monitor", onEnter = onEnter, onExit = onExit) {
        item {
            val v = state.verdict
            Line(
                when {
                    !state.running -> "Stopped"
                    !state.ready -> "Learning baseline… (${state.windows}/6 windows)"
                    v?.suspected == true -> "‼ POSSIBLE JAMMING: ${v.detail}"
                    v != null && v.dropped.isNotEmpty() -> "! ${v.detail} dropped (one domain — likely environment)"
                    else -> "✓ Normal"
                },
            )
        }
        state.last?.let { w ->
            item {
                Line(
                    "Wi-Fi APs: ${w.wifiAps ?: "–"}\nBLE adverts/10 s: ${w.bleAdverts ?: "–"}\n" +
                        "GNSS C/N0: ${w.gnssCn0?.let { "%.1f dB-Hz".format(it) } ?: "– (no sats, indoors?)"}",
                )
            }
        }
        item { Line("${state.windows} windows · ${state.alerts} alerts\nDetects only — never transmits.") }
    }
}

@Composable
fun UltrasonicScreen(state: UltraState, onEnter: () -> Unit, onExit: () -> Unit) {
    RadarList("Ultrasonic", onEnter = onEnter, onExit = onExit) {
        item {
            Line(
                when {
                    state.error != null -> "⚠ ${state.error}"
                    state.tone != null -> "‼ Tone %.2f kHz (+%.0f dB)".format(state.tone.freqHz / 1000, state.tone.snrDb)
                    state.running -> "Listening (${state.source})… no tone"
                    else -> "Starting…"
                },
            )
        }
        if (state.bands.isNotEmpty()) {
            item {
                // 12 bands × 2 kHz, scaled -120..-20 dB.
                val step = UltraMonitor.RATE / 2 / state.bands.size
                Line(
                    state.bands.mapIndexed { i, db ->
                        val n = (((db + 120) / 100) * 10).toInt().coerceIn(0, 10)
                        "%2d %s".format((i * step) / 1000, "█".repeat(n) + "·".repeat(10 - n))
                    }.joinToString("\n"),
                    center = false, mono = true,
                )
            }
        }
        item { Line("kHz → level · ${state.detections} detections\nProcessed in memory, never recorded.") }
    }
}

@Composable
fun MagScreen(state: MagState, onEnter: () -> Unit, onExit: () -> Unit, onCalibrate: () -> Unit, onToggleHaptics: () -> Unit) {
    RadarList("Magnetometer", onEnter = onEnter, onExit = onExit) {
        item {
            Text(
                state.fieldUt?.let { "%.1f µT".format(it) } ?: "—",
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            val d = state.deviationUt
            val n = (d / 5).toInt().coerceIn(0, 10)
            Line("Δ %.1f µT  %s\nbaseline %s".format(d, "●".repeat(n) + "○".repeat(10 - n), state.baselineUt?.let { "%.1f µT".format(it) } ?: "–"))
        }
        item { FilledTonalButton(onClick = onCalibrate, modifier = Modifier.fillMaxWidth(), label = { Text("Calibrate here") }) }
        item {
            OutlinedButton(onClick = onToggleHaptics, modifier = Modifier.fillMaxWidth(),
                label = { Text("Haptics: ${if (state.hapticsOn) "on" else "off"}") })
        }
        item { Line("Earth ≈ 25–65 µT. Spikes: magnets, steel, motors, live wiring.") }
    }
}
