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
import dev.kafli.rftoolkit.modules.survey.SurveyService
import dev.kafli.rftoolkit.modules.survey.SurveyState
import dev.kafli.rftoolkit.modules.wifi.WifiFindings
import dev.kafli.rftoolkit.modules.wifi.WifiScan
import dev.kafli.rftoolkit.ui.FindingsScreen
import dev.kafli.rftoolkit.ui.HomeEntry
import dev.kafli.rftoolkit.ui.HomeScreen
import dev.kafli.rftoolkit.ui.ProbeScreen
import dev.kafli.rftoolkit.ui.SurveyScreen
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
                                    if (survey.running) SurveyService.stop(this@MainActivity)
                                    else surveyPermissions.launch(surveyPermissionList())
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
