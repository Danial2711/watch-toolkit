package dev.kafli.rftoolkit

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.kafli.rftoolkit.core.ExportStore
import dev.kafli.rftoolkit.modules.probe.CapabilityProbe
import dev.kafli.rftoolkit.modules.ble.TrackerScan
import dev.kafli.rftoolkit.modules.hunt.Candidate
import dev.kafli.rftoolkit.modules.hunt.HuntEngine
import dev.kafli.rftoolkit.modules.hunt.HuntTarget
import dev.kafli.rftoolkit.modules.hunt.TargetScan
import dev.kafli.rftoolkit.modules.ble.TrackerWatch
import dev.kafli.rftoolkit.modules.survey.SurveyPreset
import dev.kafli.rftoolkit.modules.survey.SurveyService
import dev.kafli.rftoolkit.modules.survey.SurveyState
import dev.kafli.rftoolkit.modules.wifi.WifiFindings
import dev.kafli.rftoolkit.modules.wifi.WifiScan
import dev.kafli.rftoolkit.ui.FindingsScreen
import dev.kafli.rftoolkit.ui.HomeEntry
import dev.kafli.rftoolkit.ui.HomeScreen
import dev.kafli.rftoolkit.ui.HuntPickerScreen
import dev.kafli.rftoolkit.ui.HuntScreen
import dev.kafli.rftoolkit.ui.ProbeScreen
import dev.kafli.rftoolkit.ui.SurveyScreen
import dev.kafli.rftoolkit.ui.TrackersScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // Probe state
    private var probeRunning by mutableStateOf(false)
    private var probeStatus by mutableStateOf("Tap Run to probe this watch")
    private var probeHighlights by mutableStateOf<Map<String, String>>(emptyMap())

    // Findings quick-scan state
    private var findingsScanning by mutableStateOf(false)
    private var findingsStatus by mutableStateOf<String?>(null)

    // Fox hunt target scan state
    private var huntCandidates by mutableStateOf<List<Candidate>>(emptyList())
    private var huntScanning by mutableStateOf(false)
    private var huntStatus by mutableStateOf<String?>(null)

    private val huntScanPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { huntScan() }

    // Trackers quick-scan state
    private var trackerScanning by mutableStateOf(false)
    private var trackerStatus by mutableStateOf<String?>(null)

    private val trackerScanPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { trackerScan() }

    private val scanPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { quickScan() }

    private val probePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { runProbe() }

    private val surveyPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                SurveyService.start(this)
            } else {
                Toast.makeText(this, "Survey needs precise location", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entries = listOf(
            HomeEntry("City survey", "Wi-Fi + BLE + GPS log", "survey"),
            HomeEntry("Wi-Fi findings", "Weak security · IoT · evil twin", "findings"),
            HomeEntry("Trackers", "AirTag · SmartTag · Tile · FMDN", "trackers"),
            HomeEntry("Fox hunt", "Find a device by signal", "hunt"),
            HomeEntry("Capability probe", "What this watch supports", "probe"),
        )
        setContent {
            MaterialTheme {
                AppScaffold {
                    val nav = rememberSwipeDismissableNavController()
                    val survey by SurveyState.status.collectAsStateWithLifecycle()
                    SwipeDismissableNavHost(navController = nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(entries, survey.running) { nav.navigate(it) }
                        }
                        composable("survey") {
                            SurveyScreen(
                                status = survey,
                                onToggle = {
                                    if (survey.running) {
                                        SurveyService.stop(this@MainActivity)
                                    } else {
                                        // Guard preset is only started from the Trackers screen.
                                        if (!survey.preset.selectable) SurveyState.selectPreset(SurveyPreset.BALANCED)
                                        surveyPermissions.launch(surveyPermissionList())
                                    }
                                },
                                onCyclePreset = { SurveyState.selectPreset(survey.preset.next()) },
                                onToggleKeepWifi = { SurveyState.toggleKeepWifi() },
                            )
                        }
                        composable("findings") {
                            val findings by WifiFindings.list.collectAsStateWithLifecycle()
                            val baseline by WifiFindings.baselineSize.collectAsStateWithLifecycle()
                            FindingsScreen(
                                findings = findings,
                                baselineSsids = baseline,
                                scanning = findingsScanning,
                                scanStatus = findingsStatus,
                                onScan = { scanPermissions.launch(surveyPermissionList()) },
                                onClear = { WifiFindings.clearFindings() },
                                onResetBaseline = { WifiFindings.clearBaseline(this@MainActivity) },
                                onHunt = { bssid, ssid ->
                                    HuntEngine.select(HuntTarget.Wifi(bssid, ssid))
                                    nav.navigate("hunt/active")
                                },
                            )
                        }
                        composable("trackers") {
                            val tracks by TrackerWatch.tracks.collectAsStateWithLifecycle()
                            val guard = survey.running && survey.preset == SurveyPreset.TRACKER_GUARD
                            TrackersScreen(
                                tracks = tracks,
                                scanning = trackerScanning,
                                scanStatus = trackerStatus,
                                guardRunning = guard,
                                otherSurveyRunning = survey.running && !guard,
                                onScan = { trackerScanPermissions.launch(surveyPermissionList()) },
                                onToggleGuard = {
                                    if (guard) {
                                        SurveyService.stop(this@MainActivity)
                                    } else {
                                        SurveyState.selectPreset(SurveyPreset.TRACKER_GUARD)
                                        surveyPermissions.launch(surveyPermissionList())
                                    }
                                },
                                onClear = { TrackerWatch.clear() },
                                onHunt = { t ->
                                    HuntEngine.select(HuntTarget.Ble(t.id, t.tracker.kind.label))
                                    nav.navigate("hunt/active")
                                },
                            )
                        }
                        composable("hunt") {
                            val tracks by TrackerWatch.tracks.collectAsStateWithLifecycle()
                            HuntPickerScreen(
                                trackers = tracks,
                                candidates = huntCandidates,
                                scanning = huntScanning,
                                scanStatus = huntStatus,
                                onScan = { huntScanPermissions.launch(surveyPermissionList()) },
                                onPick = { target ->
                                    HuntEngine.select(target)
                                    nav.navigate("hunt/active")
                                },
                            )
                        }
                        composable("hunt/active") {
                            val hunt by HuntEngine.state.collectAsStateWithLifecycle()
                            HuntScreen(
                                state = hunt,
                                onStart = { HuntEngine.start(this@MainActivity) },
                                onStop = { HuntEngine.stop() },
                                onSweep = { HuntEngine.startSweep() },
                                onToggleHaptics = { HuntEngine.toggleHaptics() },
                            )
                        }
                        composable("probe") {
                            ProbeScreen(
                                running = probeRunning,
                                status = probeStatus,
                                highlights = probeHighlights,
                                onRun = { probePermissions.launch(CapabilityProbe.RUNTIME_PERMISSIONS.toTypedArray()) },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun surveyPermissionList(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun huntScan() {
        if (huntScanning) return
        huntScanning = true
        lifecycleScope.launch {
            huntStatus = try {
                huntCandidates = TargetScan.scan(this@MainActivity)
                "${huntCandidates.count { it.kind == "BLE" }} BLE · ${huntCandidates.count { it.kind == "Wi-Fi" }} Wi-Fi"
            } catch (t: Throwable) {
                "Scan failed: ${t.message}"
            } finally {
                huntScanning = false
            }
        }
    }

    private fun trackerScan() {
        if (trackerScanning) return
        trackerScanning = true
        lifecycleScope.launch {
            trackerStatus = try {
                val n = TrackerScan.scanOnce(this@MainActivity)
                val tracks = TrackerWatch.tracks.value
                "$n adverts · ${tracks.size} trackers"
            } catch (t: Throwable) {
                "Scan failed: ${t.message}"
            } finally {
                trackerScanning = false
            }
        }
    }

    private fun quickScan() {
        if (findingsScanning) return
        findingsScanning = true
        lifecycleScope.launch {
            try {
                WifiFindings.ensureLoaded(this@MainActivity)
                val results = WifiScan.scanOnce(this@MainActivity)
                val profiles = withContext(Dispatchers.Default) { results.map { WifiScan.profile(it) } }
                val found = WifiFindings.process(this@MainActivity, profiles)
                withContext(Dispatchers.IO) { WifiFindings.saveBaseline(this@MainActivity) }
                findingsStatus = "${profiles.size} APs · ${found.size} findings"
            } catch (t: Throwable) {
                findingsStatus = "Scan failed: ${t.message}"
            } finally {
                findingsScanning = false
            }
        }
    }

    private fun runProbe() {
        if (probeRunning) return
        probeRunning = true
        // Keep the screen on: reader mode and scans need a foreground, resumed activity.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            try {
                val probe = CapabilityProbe(this@MainActivity)
                val json = probe.run { probeStatus = it }
                val file = withContext(Dispatchers.IO) {
                    ExportStore.writeWithLatest(this@MainActivity, "probe", "json", json.toString(2))
                }
                probeHighlights = probe.highlights.toMap()
                probeStatus = "Saved ${file.name}"
            } catch (t: Throwable) {
                probeStatus = "Probe failed: ${t.message}"
            } finally {
                probeRunning = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}
