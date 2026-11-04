package dev.kafli.rftoolkit.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.kafli.rftoolkit.modules.ble.TrackerTrack
import dev.kafli.rftoolkit.modules.hunt.Candidate
import dev.kafli.rftoolkit.modules.hunt.HuntState
import dev.kafli.rftoolkit.modules.hunt.HuntTarget
import dev.kafli.rftoolkit.modules.hunt.Proximity
import dev.kafli.rftoolkit.modules.hunt.SweepAnalyzer
import dev.kafli.rftoolkit.modules.hunt.Trend
import kotlinx.coroutines.delay

@Composable
fun HuntPickerScreen(
    trackers: List<TrackerTrack>,
    candidates: List<Candidate>,
    scanning: Boolean,
    scanStatus: String?,
    onScan: () -> Unit,
    onPick: (HuntTarget) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Fox hunt") } }
            item {
                Button(
                    onClick = onScan,
                    enabled = !scanning,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (scanning) "Scanning…" else "Scan for targets") },
                    secondaryLabel = scanStatus?.let { { Text(it) } },
                )
            }
            if (trackers.isNotEmpty()) {
                item { Text("Trackers", style = MaterialTheme.typography.labelMedium) }
                trackers.take(6).forEach { t ->
                    item(key = "trk/${t.id}") {
                        FilledTonalButton(
                            onClick = { onPick(HuntTarget.Ble(t.id, t.tracker.kind.label)) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(t.tracker.kind.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            secondaryLabel = {
                                val st = when (t.tracker.separated) { true -> "separated"; false -> "owner near"; null -> "" }
                                Text("$st · ${t.lastRssi} dBm · …${t.id.takeLast(5)}", maxLines = 1)
                            },
                        )
                    }
                }
            }
            if (candidates.isNotEmpty()) item { Text("Nearby", style = MaterialTheme.typography.labelMedium) }
            candidates.take(25).forEach { c ->
                item(key = "cand/${c.target.id}") {
                    OutlinedButton(
                        onClick = { onPick(c.target) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(c.target.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text("${c.kind} · ${c.rssi} dBm · …${c.target.id.takeLast(5)}", maxLines = 1) },
                    )
                }
            }
        }
    }
}

@Composable
fun HuntScreen(
    state: HuntState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSweep: () -> Unit,
    onToggleHaptics: () -> Unit,
) {
    // Hunt runs only while this screen is visible; keep the display (and thus Wi-Fi/BLE) awake.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onStart()
        onDispose {
            view.keepScreenOn = false
            onStop()
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    val target = state.target
    val sm = state.smoothed
    val ageS = if (state.lastSampleMs > 0) (now - state.lastSampleMs) / 1000 else null
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxWidth()) {
            item {
                ListHeader {
                    Text(target?.label ?: "No target", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item {
                Text(
                    sm?.let { "%.0f dBm".format(it) } ?: "—",
                    style = MaterialTheme.typography.displayMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                val bar = sm?.let { s -> val l = Proximity.level(s); "●".repeat(l) + "○".repeat(10 - l) } ?: "○".repeat(10)
                val tr = when (state.trend) { Trend.WARMER -> "▲ warmer"; Trend.COLDER -> "▼ colder"; Trend.STEADY -> "■ steady" }
                Text("$bar\n$tr", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            item {
                val lines = buildList {
                    if (ageS != null && ageS * 1000 > (target?.staleMs ?: 6_000)) add("⚠ signal lost ${ageS}s")
                    sm?.let { add("≈ %.0f m · peak %.0f dBm".format(Proximity.roughDistanceM(it), state.peak ?: it)) }
                    add("${state.samples} samples · …${target?.id?.takeLast(8) ?: ""}")
                    if (target != null && !target.isBle) add("Wi-Fi updates every ~5 s — move slowly")
                }
                Text(
                    lines.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Direction from the 360° sweep, updated live with the compass.
            state.sweep?.let { sw ->
                item {
                    val turn = state.headingDeg?.let { SweepAnalyzer.relativeTurn(it, sw.bestHeadingDeg) }
                    val turnText = turn?.let {
                        "%s %s".format(
                            SweepAnalyzer.arrow(it),
                            if (kotlin.math.abs(it) <= 15) "ahead" else "%.0f° %s".format(kotlin.math.abs(it), if (it < 0) "left" else "right"),
                        )
                    } ?: ""
                    Text(
                        "Strongest: ${SweepAnalyzer.compassName(sw.bestHeadingDeg)} %03.0f° (%s, +%.0f dB)\n%s".format(
                            sw.bestHeadingDeg, sw.confidence, sw.contrastDb, turnText,
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (target?.isBle == true) {
                item {
                    FilledTonalButton(
                        onClick = onSweep,
                        enabled = state.running && !state.sweeping,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (state.sweeping) "Turn slowly… ${state.sweepCovered}/12" else "Sweep 360°") },
                        secondaryLabel = {
                            Text(if (state.sweeping) "arm out, screen up" else "find direction")
                        },
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = onToggleHaptics,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Haptics: ${if (state.hapticsOn) "on" else "off"}") },
                )
            }
            state.error?.let { e ->
                item { Text("⚠ $e", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}
